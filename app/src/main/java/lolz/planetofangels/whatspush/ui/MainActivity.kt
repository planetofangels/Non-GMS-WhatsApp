package lolz.planetofangels.whatspush.ui

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import lolz.planetofangels.whatspush.Prefs
import lolz.planetofangels.whatspush.health.Problem
import lolz.planetofangels.whatspush.health.Status
import lolz.planetofangels.whatspush.log.AppLog
import lolz.planetofangels.whatspush.notify.Notifier
import lolz.planetofangels.whatspush.service.WaService

class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs

    private lateinit var dot: View
    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var fixes: LinearLayout
    private lateinit var primary: Button
    private lateinit var more: LinearLayout
    private lateinit var webPanel: LinearLayout
    private lateinit var webContainer: FrameLayout
    private lateinit var scroll: ScrollView

    private var webShown = false
    private val closeWebOnBack = OnBackInvokedCallback { hideWeb() }
    private val refresher = object : Runnable {
        override fun run() {
            render()
            main.postDelayed(this, 5_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        buildLayout()
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        resumed = true

        if (webShown && WaService.running?.webHost?.shown != true) hideWeb()

        if (prefs.enabled && WaService.running == null) WaService.start(this)
        WaService.uiListener = Runnable { render() }
        WaService.running?.refreshChecks()
        main.post(refresher)
        if (intent.getBooleanExtra(EXTRA_SHOW_WEB, false)) {
            intent.removeExtra(EXTRA_SHOW_WEB)
            main.postDelayed({ showWeb() }, 300)
        }
    }

    override fun onPause() {
        resumed = false

        if (webShown && WaService.running?.isLinked == true) hideWeb()
        WaService.uiListener = null
        main.removeCallbacks(refresher)
        super.onPause()
    }

    override fun onDestroy() {
        if (webShown) hideWeb()
        super.onDestroy()
    }

    private fun render() {
        val service = WaService.running
        val status = service?.currentStatus
        val problem = service?.currentProblem

        val (text, color) = when {
            !prefs.enabled -> "Off" to RED
            service == null -> "Starting" to AMBER
            problem != null -> problemTitle(problem) to RED
            status == Status.CONNECTED -> "Connected" to GREEN
            status == Status.NEEDS_LINK -> "Not linked" to AMBER
            else -> service.statusText().trimEnd('…', '.') to AMBER
        }
        statusText.text = text
        (dot.background as GradientDrawable).setColor(color)

        detailText.text = when {
            service?.notificationsLookBroken == true -> "Showing unread counts only"
            status == Status.CONNECTED -> service.lastActivityAgeMs?.let { "Active ${ago(it)}" }.orEmpty()
            else -> ""
        }
        detailText.visibility = if (detailText.text.isEmpty()) View.GONE else View.VISIBLE

        when {
            !prefs.enabled -> showPrimary("Start") {
                WaService.start(this)
                main.postDelayed({ render() }, 500)
            }
            status == Status.NEEDS_LINK || problem == Problem.LOGGED_OUT -> showPrimary("Link") { showWeb() }
            else -> primary.visibility = View.GONE
        }
        renderFixes()
    }

    private fun problemTitle(p: Problem) = when (p) {
        Problem.LOGGED_OUT -> "Logged out"
        Problem.NO_CONNECTION -> "No connection"
        Problem.LOAD_FAILING -> "Won't load"
        Problem.PAGE_CHANGED -> "Page changed"
        Problem.CRASHING -> "Crashing"
        Problem.CANT_VERIFY -> "Unverified"
    }

    private fun showPrimary(label: String, onClick: () -> Unit) {
        primary.text = label
        primary.setOnClickListener { onClick() }
        primary.visibility = View.VISIBLE
    }

    private fun renderFixes() {
        fixes.removeAllViews()
        if (!getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) {
            fix("Battery restricted") {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
        }
        if (!Notifier(this).canNotify) {
            fix("Notifications off") {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            }
        }
        if (!getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) {
            fix("Alarms not allowed") {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            }
        }
        if (!getSystemService(android.app.NotificationManager::class.java).canUseFullScreenIntent()) {
            fix("Calls won't ring full screen") {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
            }
        }
        if (!Settings.canDrawOverlays(this)) {
            fix("Uses extra battery") {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        fixes.visibility = if (fixes.childCount == 0) View.GONE else View.VISIBLE
    }

    private fun fix(text: String, onClick: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            TextView(this).apply {
                this.text = text
                setTextColor(RED)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.addView(flatButton("Fix") { runCatching { onClick() } })
        fixes.addView(row)
    }

    private fun confirmStop() {
        AlertDialog.Builder(this)
            .setTitle("Stop WA Push?")
            .setMessage("You won't get WhatsApp notifications.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ -> confirmStopAgain() }
            .show()
    }

    private fun confirmStopAgain() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Are you sure?")
            .setMessage("It stays off until you start it again, also after a restart.")
            .setNegativeButton("Keep running", null)
            .setPositiveButton("Stop") { _, _ ->
                prefs.enabled = false
                WaService.stop(this)
                main.postDelayed({ render() }, 500)
            }
            .show()
        val stop = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        stop.isEnabled = false
        var left = 3
        val tick = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                if (left == 0) {
                    stop.text = "Stop"
                    stop.isEnabled = true
                } else {
                    stop.text = "Stop ($left)"
                    left--
                    main.postDelayed(this, 1_000)
                }
            }
        }
        tick.run()
    }

    private fun showWeb() {
        val service = WaService.running ?: return toast("Not running")
        val host = service.webHost
        if (host.webView == null) return toast("Restarting, try again in a moment")
        webShown = true
        scroll.visibility = View.GONE
        webPanel.visibility = View.VISIBLE
        host.show(this, webContainer)
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, closeWebOnBack)
    }

    private fun hideWeb() {
        if (!webShown) return
        webShown = false
        WaService.running?.webHost?.takeIf { it.shown }?.hide()
        webContainer.removeAllViews()
        webPanel.visibility = View.GONE
        scroll.visibility = View.VISIBLE
        runCatching { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(closeWebOnBack) }
        render()
    }

    private fun buildLayout() {
        val root = FrameLayout(this).apply { fitsSystemWindows = true }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(72), dp(28), dp(28))
        }

        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        dot = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        }
        statusRow.addView(dot, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(14) })
        statusText = TextView(this).apply {
            textSize = 32f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        statusRow.addView(statusText)
        content.addView(statusRow)

        detailText = TextView(this).apply {
            alpha = 0.55f
            setPadding(dp(26), dp(4), 0, 0)
        }
        content.addView(detailText)

        fixes = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(24), 0, 0)
        }
        content.addView(fixes)

        primary = Button(this).apply {
            isAllCaps = false
            visibility = View.GONE
        }
        content.addView(
            primary,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(32)
            },
        )

        more = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val moreToggle = TextView(this).apply {
            text = "More"
            alpha = 0.55f
            setPadding(0, dp(48), 0, dp(12))
        }
        moreToggle.setOnClickListener {
            val open = more.visibility != View.VISIBLE
            more.visibility = if (open) View.VISIBLE else View.GONE
            moreToggle.text = if (open) "Less" else "More"
        }
        content.addView(moreToggle)

        more.addView(flatButton("Open WhatsApp Web") { showWeb() })
        more.addView(flatButton("Reload") {
            if (WaService.running == null) toast("Not running") else WaService.reload(this)
        })
        more.addView(flatButton("Test notification") { Notifier(this).showTest() })
        more.addView(flatButton("Test call (rings in 10 s, lock the phone)") {
            toast("Lock the phone now")
            val n = Notifier(applicationContext).also { it.ensureChannels() }
            main.postDelayed({ n.showCall("wapush-test-call", "Test call", "Incoming voice call") }, 10_000)
        })
        more.addView(flatButton("Copy log") {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("WA Push log", AppLog.read()))
            toast("Copied")
        })
        more.addView(toggle("Catch up after sleep", prefs.catchUpTimers) {
            prefs.catchUpTimers = it
            WaService.recreate(this)
        })
        more.addView(toggle("Keep phone awake (uses more battery)", prefs.alwaysAwake) {
            prefs.alwaysAwake = it
            WaService.applyPower(this)
        })
        more.addView(toggle("Unread fallback", prefs.unreadFallback) { prefs.unreadFallback = it })
        more.addView(
            flatButton("Stop") {
                if (prefs.enabled) confirmStop() else toast("Already off")
            }.apply { setTextColor(RED) },
        )
        content.addView(more)

        scroll = ScrollView(this).apply { addView(content) }
        root.addView(scroll)

        webPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        bar.addView(flatButton("Done") { hideWeb() })
        webContainer = FrameLayout(this)
        webPanel.addView(bar)
        webPanel.addView(webContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(webPanel)

        setContentView(root)
    }

    private fun flatButton(label: String, onClick: () -> Unit) =
        Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = label
            isAllCaps = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setOnClickListener { onClick() }
        }

    private fun toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = label
        isChecked = checked
        setPadding(dp(16), dp(14), dp(8), dp(14))
        setOnCheckedChangeListener { _, on -> onChange(on) }
    }

    private fun ago(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 60 -> "just now"
            s < 3600 -> "${s / 60} min ago"
            else -> "${s / 3600} h ago"
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_SHOW_WEB = "show_web"

        private const val GREEN = 0xFF4CAF50.toInt()
        private const val AMBER = 0xFFFFB300.toInt()
        private const val RED = 0xFFE5534B.toInt()

        @Volatile var resumed = false
            private set
    }
}
