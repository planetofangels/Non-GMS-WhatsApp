package lolz.planetofangels.whatspush.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import lolz.planetofangels.whatspush.Prefs
import lolz.planetofangels.whatspush.health.HealthMonitor
import lolz.planetofangels.whatspush.health.KeepalivePolicy
import lolz.planetofangels.whatspush.health.Problem
import lolz.planetofangels.whatspush.health.Status
import lolz.planetofangels.whatspush.health.UnreadWatcher
import lolz.planetofangels.whatspush.log.AppLog
import lolz.planetofangels.whatspush.notify.Notifier
import lolz.planetofangels.whatspush.ui.MainActivity
import lolz.planetofangels.whatspush.web.WebHost
import org.json.JSONObject

class WaService : Service(), WebHost.Listener {
    private val main = Handler(Looper.getMainLooper())
    private val clock = { SystemClock.elapsedRealtime() }

    private lateinit var prefs: Prefs
    private lateinit var notifier: Notifier
    private lateinit var health: HealthMonitor
    private lateinit var unread: UnreadWatcher
    private lateinit var keepalive: KeepalivePolicy
    private lateinit var web: WebHost

    private val workLock: PowerManager.WakeLock by lazy {
        getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wapush:work")
            .apply { setReferenceCounted(false) }
    }
    private val holdLock: PowerManager.WakeLock by lazy {
        getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wapush:always")
            .apply { setReferenceCounted(false) }
    }
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    private var lastStatusText = ""
    private var shownProblem: Problem? = null
    private var linked = false
    private var lastPageMessageAt = 0L
    private var recreatePending = false
    private var batteryWarned = false
    private var online = true

    private var keepalivesSinceCheckup = 0
    private var reportsSinceCheckup = 0
    private var framesSinceCheckup = 0
    private var closesSinceCheckup = 0
    private var caughtUpSinceCheckup = 0

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        notifier = Notifier(this).also { it.ensureChannels() }
        goForeground("Starting…")

        health = HealthMonitor(clock, everLinked = prefs.everLinked)
        unread = UnreadWatcher(clock)
        keepalive = KeepalivePolicy(clock, prefs.keepaliveSec.takeIf { it > 0 } ?: KeepalivePolicy.START_SEC)
        web = WebHost(applicationContext, this) { prefs.catchUpTimers }

        if (!prefs.enabled) {
            stopSelf()
            return
        }
        running = this
        if (!web.supported) {
            AppLog.error("This WebView lacks required features; can't run")
            updateStatusNotification("Not supported: update Android System WebView")
            notifyUi()
            return
        }
        AppLog.info("Service started")
        applyPowerMode()
        checkBatteryExemption()
        watchNetwork()
        web.create()
        scheduleKeepalive()
        scheduleWatchdog()
        notifyUi()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                AppLog.info("Stopped by the user")
                prefs.enabled = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CHECKUP -> checkup()
            ACTION_RELOAD -> reloadNow("requested")
            ACTION_POWER -> applyPowerMode()
            ACTION_RECREATE -> {
                web.destroy()
                recreateWebView("settings changed")
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = null
        main.removeCallbacksAndMessages(null)
        netCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        if (prefs.enabled) {
            AppLog.warn("Service destroyed while enabled; restart scheduled")
            alarms().setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 30_000L, keepaliveIntent(this),
            )
        } else {
            alarms().cancel(keepaliveIntent(this))
            alarms().cancel(watchdogIntent(this))
        }
        web.destroy()
        if (workLock.isHeld) workLock.release()
        if (holdLock.isHeld) holdLock.release()
        AppLog.info("Service destroyed")
        notifyUi()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun onKeepalive() {
        keepalivesSinceCheckup++
        if (!online) {
            scheduleKeepalive()
            return
        }
        awake(KEEPALIVE_LEASE_MS)
        scheduleKeepalive()
        if (web.webView == null) {
            recreateWebView("missing on keepalive")
            return
        }
        web.probe()

        main.postDelayed({ evaluate() }, PROBE_SETTLE_MS)
    }

    private fun checkup() {
        applyPowerMode()
        scheduleWatchdog()
        scheduleKeepalive()
        checkBatteryExemption()
        AppLog.info(
            "Checkup: ${health.status}, keepalive ${keepalive.intervalSec}s, " +
                "wakes $keepalivesSinceCheckup, page reports $reportsSinceCheckup, " +
                "frames $framesSinceCheckup, closes $closesSinceCheckup, timers caught up $caughtUpSinceCheckup, " +
                "last frame ${health.lastFrameAgeMs?.div(1000)?.toString()?.plus("s ago") ?: "never"}",
        )
        keepalivesSinceCheckup = 0
        reportsSinceCheckup = 0
        framesSinceCheckup = 0
        closesSinceCheckup = 0
        caughtUpSinceCheckup = 0
        if (web.supported && web.webView == null) recreateWebView("missing on checkup")

        val silentFor = clock() - lastPageMessageAt
        if (web.webView != null && lastPageMessageAt != 0L && silentFor > PAGE_SILENT_MS) {
            AppLog.warn("Page silent for ${silentFor / 1000}s; reloading")
            reloadNow("page silent")
        }
        evaluate()
    }

    override fun onPageMessage(msg: JSONObject) {
        lastPageMessageAt = clock()
        reportsSinceCheckup++
        when (msg.optString("t")) {
            "hello" -> AppLog.info("Page script active")
            "state" -> onPageState(msg)
            "notify" -> {
                awake(DELIVERY_LEASE_MS)
                unread.onPageNotification()
                notifier.clearNotificationsBroken()
                if (msg.optBoolean("call")) {
                    AppLog.info("Incoming call")
                    notifier.showCall(msg.optString("tag"), msg.optString("title"), msg.optString("body"))
                } else {
                    notifier.showMessage(msg.optString("tag"), msg.optString("title"), msg.optString("body"))
                }
            }
            "unnotify" -> msg.optString("tag").let { tag ->
                if (notifier.isRinging(tag)) notifier.endCall(tag) else notifier.cancelConversation(tag)
            }
            "ws" -> onSocket(msg.optString("ev"), msg.optInt("id"), msg.optInt("code"))
            "caughtUp" -> caughtUpSinceCheckup += msg.optInt("n")
            "error" -> AppLog.warn("Page script: ${msg.optString("what")}: ${msg.optString("msg")}")
        }
    }

    override fun onPageStarted() {
        health.onPageStarted()
        unread.resetBaseline()
        notifyUi()
    }

    override fun onPageFinished() {
        health.onPageFinished()
        main.postDelayed({ web.probe() }, 2_000)
    }

    override fun onLoadError(reason: String) {
        AppLog.warn("WhatsApp Web failed to load: $reason")
        health.onLoadError()
        evaluate()
    }

    override fun onRendererGone(crashed: Boolean) {
        AppLog.warn(if (crashed) "WebView renderer crashed" else "WebView renderer was killed (memory)")
        health.onRendererGone()
        linked = false
        recreateWebView("renderer gone", delayMs = RECREATE_DELAY_MS)
    }

    fun refreshChecks() {
        checkBatteryExemption()
        web.repark()
        evaluate()
    }

    private fun checkBatteryExemption() {
        val ok = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        if (!ok && !batteryWarned) {
            AppLog.warn("Battery optimization is on; deep sleep will stop WA Push")
            notifier.showBatteryRestricted()
        } else if (ok && batteryWarned) {
            notifier.clearBatteryRestricted()
        }
        batteryWarned = !ok
    }

    private fun onPageState(msg: JSONObject) {
        val chats = msg.optBoolean("chats")
        val qr = msg.optBoolean("qr")
        health.onState(chats, qr, msg.optBoolean("loading"))
        if (chats && !prefs.everLinked) {
            prefs.everLinked = true
            AppLog.info("Linked: chats visible")
        }
        linked = chats

        if (chats && web.shown && !MainActivity.resumed) {
            AppLog.info("Linked while the page was open in the background; hiding it")
            web.hide()
        }

        val count = msg.optInt("unread")
        when (val action = unread.onUnread(count, linked)) {
            UnreadWatcher.Action.ClearAll -> notifier.cancelAllMessages()
            is UnreadWatcher.Action.WaitForNotification -> {
                awake(action.delayMs + 2_000)
                main.postDelayed({ checkUnreadFallback() }, action.delayMs + 500)
            }
            UnreadWatcher.Action.None -> Unit
        }
        evaluate()
    }

    private fun checkUnreadFallback() {
        val count = unread.check() ?: return
        if (!prefs.unreadFallback) return
        AppLog.warn("Unread count rose without a page notification (${unread.silentRises} in a row)")
        notifier.showUnreadFallback(count)
        if (unread.notificationsLookBroken) notifier.showNotificationsBroken()
    }

    private fun onSocket(event: String, id: Int, code: Int) {
        when (event) {
            "new", "close", "error" -> awake(SOCKET_LEASE_MS)
            "frame" -> awake(FRAME_LEASE_MS)
        }
        if (event == "frame") framesSinceCheckup++
        if (event == "close") closesSinceCheckup++
        val wasFresh = health.connectionFresh
        health.onSocket(event, id)
        if (event == "close") {
            AppLog.info("Connection $id closed (code $code)")
            val asleep = getSystemService(PowerManager::class.java).isDeviceIdleMode
            if (asleep && wasFresh && !health.connectionFresh && linked && keepalive.onDroppedWhileAsleep()) {
                prefs.keepaliveSec = keepalive.intervalSec
                AppLog.info("Dropped while asleep; keepalive now ${keepalive.intervalSec}s")
                scheduleKeepalive()
            }
            evaluate()
        } else if (event == "open") {
            AppLog.info("Connection $id open")
            evaluate()
        }
    }

    private fun evaluate() {
        if (!web.supported) return
        val verdict = health.evaluate()
        if (verdict.status == Status.CONNECTED && keepalive.onConnected()) {
            prefs.keepaliveSec = keepalive.intervalSec
            AppLog.info("Stable; keepalive now ${keepalive.intervalSec}s")
        }
        if (verdict.reload) reloadNow("status ${verdict.status}")

        if (verdict.problem != shownProblem) {
            if (verdict.problem != null) {
                AppLog.warn("Problem: ${verdict.problem}")
                notifier.showProblem(verdict.problem)
            } else {
                AppLog.info("Problem cleared: $shownProblem")
                notifier.clearProblem()
            }
            shownProblem = verdict.problem
        }
        updateStatusNotification(statusText(verdict.status))
        notifyUi()
    }

    private fun reloadNow(why: String) {
        AppLog.info("Reload ($why)")
        awake(RELOAD_LEASE_MS)
        health.markReloaded()
        linked = false
        if (web.webView == null) recreateWebView(why) else web.reload()
    }

    private fun recreateWebView(why: String, delayMs: Long = 0) {
        if (recreatePending) return
        recreatePending = true
        awake(delayMs + RELOAD_LEASE_MS)
        main.postDelayed({
            recreatePending = false
            AppLog.info("Creating WebView ($why)")
            web.destroy()
            web.create()
            notifyUi()
        }, delayMs)
    }

    fun statusText(status: Status = health.status): String = when (status) {
        Status.STARTING -> "Starting…"
        Status.LOADING -> "Loading WhatsApp Web…"
        Status.LOAD_FAILED -> "WhatsApp Web didn't load, retrying"
        Status.NEEDS_LINK -> "Not linked – open the app to link"
        Status.SYNCING -> "Loading chats…"
        Status.CONNECTED -> "Connected"
        Status.RECONNECTING -> "Reconnecting…"
        Status.OFFLINE -> "Waiting for network"
        Status.UNRECOGNIZED -> "Checking WhatsApp Web…"
    }

    private fun updateStatusNotification(text: String) {
        if (text == lastStatusText) return
        lastStatusText = text
        notifier.updateStatus(notifier.status(text))
    }

    val webHost: WebHost get() = web
    val isLinked: Boolean get() = linked
    val currentStatus: Status get() = health.status
    val currentProblem: Problem? get() = shownProblem
    val keepaliveSec: Int get() = keepalive.intervalSec
    val lastActivityAgeMs: Long? get() = health.lastFrameAgeMs
    val notificationsLookBroken: Boolean get() = unread.notificationsLookBroken

    // watch wifi/mobile directly, an always-on vpn stays "connected" in airplane mode
    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val validated = mutableSetOf<Network>()
        fun usable(caps: NetworkCapabilities?) = caps != null &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val ok = usable(caps)
                main.post { if (ok) validated += network else validated -= network; changed(validated.isNotEmpty()) }
            }
            override fun onLost(network: Network) {
                main.post { validated -= network; changed(validated.isNotEmpty()) }
            }
        }

        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        runCatching { cm.registerNetworkCallback(request, callback) }
            .onSuccess { netCallback = callback }
            .onFailure { AppLog.warn("Can't watch the network: ${it::class.java.simpleName}") }

        main.postDelayed({ if (validated.isEmpty()) changed(false) }, NETWORK_SETTLE_MS)
    }

    private fun changed(now: Boolean) {
        if (now == online) return
        online = now
        AppLog.info(if (now) "Network back" else "Network gone")
        health.onNetwork(now)
        if (now) {
            awake(NETWORK_LEASE_MS)
            scheduleKeepalive()

            for (delay in NETWORK_KICKS_MS) main.postDelayed({ kickIfStuck() }, delay)
        }
        evaluate()
    }

    private fun kickIfStuck() {
        if (!online || !health.stuckAfterNetworkBack()) return
        awake(NETWORK_LEASE_MS)
        reloadNow("still down after network came back")
    }

    private fun awake(ms: Long) {
        if (holdLock.isHeld) return
        workLock.acquire(ms)
    }

    private fun applyPowerMode() {
        if (prefs.alwaysAwake) {
            holdLock.acquire(WATCHDOG_MS + 5 * 60_000L)
        } else if (holdLock.isHeld) {
            holdLock.release()
        }
    }

    private fun alarms(): AlarmManager = getSystemService(AlarmManager::class.java)

    private fun scheduleKeepalive() {
        val sec = if (online) keepalive.intervalSec else KeepalivePolicy.MAX_SEC * 2
        val at = SystemClock.elapsedRealtime() + sec * 1000L
        val am = alarms()
        if (am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, keepaliveIntent(this))
        } else {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, keepaliveIntent(this))
        }
    }

    private fun scheduleWatchdog() {
        alarms().setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + WATCHDOG_MS,
            watchdogIntent(this),
        )
    }

    private fun goForeground(text: String) {
        lastStatusText = text
        startForeground(
            Notifier.ID_STATUS, notifier.status(text),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    private fun notifyUi() {
        uiListener?.let { main.post(it) }
    }

    companion object {
        const val ACTION_STOP = "lolz.planetofangels.whatspush.STOP"
        const val ACTION_CHECKUP = "lolz.planetofangels.whatspush.CHECKUP"
        const val ACTION_RELOAD = "lolz.planetofangels.whatspush.RELOAD"
        const val ACTION_POWER = "lolz.planetofangels.whatspush.POWER"
        const val ACTION_RECREATE = "lolz.planetofangels.whatspush.RECREATE"

        private const val KEEPALIVE_LEASE_MS = 3_000L
        private const val PROBE_SETTLE_MS = 1_500L
        private const val FRAME_LEASE_MS = 3_000L
        private const val SOCKET_LEASE_MS = 15_000L
        private const val DELIVERY_LEASE_MS = 5_000L
        private const val RELOAD_LEASE_MS = 30_000L
        private const val NETWORK_LEASE_MS = 20_000L
        private const val NETWORK_SETTLE_MS = 2_000L
        private val NETWORK_KICKS_MS = longArrayOf(12_000L, 45_000L)
        private const val RECREATE_DELAY_MS = 3_000L
        private const val WATCHDOG_MS = 15 * 60_000L
        private const val PAGE_SILENT_MS = 10 * 60_000L

        @Volatile var running: WaService? = null
            private set

        var uiListener: Runnable? = null

        fun start(context: Context) {
            Prefs(context).enabled = true
            context.startForegroundService(Intent(context, WaService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, WaService::class.java).setAction(ACTION_STOP))
        }

        fun reload(context: Context) = send(context, ACTION_RELOAD)

        fun applyPower(context: Context) = send(context, ACTION_POWER)

        fun recreate(context: Context) = send(context, ACTION_RECREATE)

        private fun send(context: Context, action: String) {
            if (running == null) return
            context.startService(Intent(context, WaService::class.java).setAction(action))
        }

        fun keepaliveIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 4, Intent(context, KeepaliveReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        fun watchdogIntent(context: Context): PendingIntent = PendingIntent.getForegroundService(
            context, 3, Intent(context, WaService::class.java).setAction(ACTION_CHECKUP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
