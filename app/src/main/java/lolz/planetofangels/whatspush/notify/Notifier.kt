package lolz.planetofangels.whatspush.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.app.Person
import android.media.AudioAttributes
import android.media.RingtoneManager
import lolz.planetofangels.whatspush.ui.IncomingCallActivity
import lolz.planetofangels.whatspush.R
import lolz.planetofangels.whatspush.health.Problem
import lolz.planetofangels.whatspush.ui.MainActivity

class Notifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    private val lines = mutableMapOf<String, ArrayDeque<String>>()

    fun ensureChannels() {
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(CH_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "WhatsApp messages"
                },
                NotificationChannel(CH_CALLS, "Call notes", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Quiet note after a WhatsApp call rang"
                },
                NotificationChannel(CH_RING, "Ringing calls", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Incoming WhatsApp calls ring like phone calls"
                    setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 1000, 1000)
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                },
                NotificationChannel(CH_PROBLEMS, "Problems", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Logged out, connection lost and other problems"
                },
                NotificationChannel(CH_STATUS, "Status", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Permanent status notification"
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                },
            ),
        )
    }

    val canNotify: Boolean
        get() = manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(CH_MESSAGES)?.importance != NotificationManager.IMPORTANCE_NONE

    fun showMessage(tag: String, title: String, body: String) {
        val key = tag.ifBlank { title }.ifBlank { "whatsapp" }
        val id = messageId(key)
        val stillShown = manager.activeNotifications.any { it.id == id }
        val queue = lines.getOrPut(key) { ArrayDeque() }
        if (!stillShown) queue.clear()
        queue.addLast(body.ifBlank { "New message" })
        while (queue.size > MAX_LINES) queue.removeFirst()

        val shownTitle = title.ifBlank { "WhatsApp" }
        val builder = Notification.Builder(context, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(shownTitle)
            .setContentText(queue.last())
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(openWhatsApp())
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(genericPublic())
        if (queue.size > 1) {
            val style = Notification.InboxStyle().setBigContentTitle(shownTitle)
            queue.forEach { style.addLine(it) }
            builder.setStyle(style).setNumber(queue.size)
        } else {
            builder.setStyle(Notification.BigTextStyle().bigText(queue.last()))
        }
        manager.notify(id, builder.build())
    }

    fun isRinging(tag: String) = ringing.containsKey(tag)

    fun showCall(tag: String, title: String, body: String) {
        val key = tag.ifBlank { "call:$title" }
        val caller = title.ifBlank { "WhatsApp" }
        ringing[key] = caller
        val id = callId(key)
        val screen = PendingIntent.getActivity(
            context, id, IncomingCallActivity.intent(context, key, caller),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val answer = PendingIntent.getActivity(
            context, id + 1, IncomingCallActivity.intent(context, key, caller, IncomingCallActivity.ACTION_ANSWER),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val decline = PendingIntent.getBroadcast(
            context, id + 2,
            Intent(context, CallActionReceiver::class.java).putExtra(CallActionReceiver.EXTRA_KEY, key),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = Notification.Builder(context, CH_RING)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(caller)
            .setContentText(body.ifBlank { "Incoming WhatsApp call" })
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(screen)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(genericPublic("Incoming call"))
        if (manager.canUseFullScreenIntent()) {
            builder.setFullScreenIntent(screen, true)
                .setStyle(Notification.CallStyle.forIncomingCall(Person.Builder().setName(caller).build(), decline, answer))
        } else {
            builder.addAction(Notification.Action.Builder(null, "Decline", decline).build())
                .addAction(Notification.Action.Builder(null, "Answer", answer).build())
        }
        val n = builder.build().apply { flags = flags or Notification.FLAG_INSISTENT }
        manager.notify(id, n)

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ endCall(key) }, CALL_RING_MS)
    }

    fun endCall(tag: String) {
        val caller = ringing.remove(tag) ?: return
        IncomingCallActivity.current?.finish()
        if (manager.activeNotifications.none { it.id == callId(tag) }) return
        val n = Notification.Builder(context, CH_CALLS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(caller)
            .setContentText("WhatsApp call")
            .setCategory(Notification.CATEGORY_MISSED_CALL)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(openWhatsApp())
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(genericPublic("WhatsApp call"))
            .build()
        manager.cancel(callId(tag))
        manager.notify(callId(tag), n)
    }

    fun cancelConversation(tag: String) {
        if (tag.isBlank()) return
        lines.remove(tag)
        manager.cancel(messageId(tag))
    }

    fun cancelAllMessages() {
        lines.keys.toList().forEach { manager.cancel(messageId(it)) }
        lines.clear()
        manager.cancel(ID_FALLBACK)
    }

    fun showUnreadFallback(unreadChats: Int) {
        val text = if (unreadChats == 1) "1 chat with unread messages" else "$unreadChats chats with unread messages"
        manager.notify(
            ID_FALLBACK,
            Notification.Builder(context, CH_MESSAGES)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("New WhatsApp messages")
                .setContentText(text)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(openWhatsApp())
                .build(),
        )
    }

    fun showTest() = showMessage("wapush-test", "WA Push", "Test notification – this is what messages look like.")

    fun showProblem(problem: Problem) {
        val (title, text) = when (problem) {
            Problem.LOGGED_OUT -> "WhatsApp Web logged out" to "No notifications until you link it again. Tap to link."
            Problem.NO_CONNECTION -> "Can't reach WhatsApp" to "Messages may be delayed. Retrying automatically."
            Problem.LOAD_FAILING -> "WhatsApp Web won't load" to "Retrying automatically. Tap for details."
            Problem.PAGE_CHANGED -> "Can't tell if WhatsApp Web works" to "WhatsApp may have changed its page. Tap to check."
            Problem.CRASHING -> "WhatsApp Web keeps crashing" to "Notifications may not work. Tap for details."
            Problem.CANT_VERIFY -> "Can't check the connection" to "WhatsApp Web changed. Notifications may still work, but WA Push can't confirm it."
        }
        manager.notify(
            ID_PROBLEM,
            Notification.Builder(context, CH_PROBLEMS)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp(showWeb = problem == Problem.LOGGED_OUT))
                .build(),
        )
    }

    fun showNotificationsBroken() {
        val text = "WhatsApp Web stopped sending message notifications. You'll get \"unread messages\" alerts instead. Tap to check."
        manager.notify(
            ID_NOTIFY_BROKEN,
            Notification.Builder(context, CH_PROBLEMS)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Message details unavailable")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp(showWeb = false))
                .build(),
        )
    }

    fun clearNotificationsBroken() = manager.cancel(ID_NOTIFY_BROKEN)

    fun clearProblem() = manager.cancel(ID_PROBLEM)

    fun showBatteryRestricted() {
        val text = "Android will stop WA Push in deep sleep, so messages arrive late. Tap and allow \"unrestricted\" battery use."
        manager.notify(
            ID_BATTERY,
            Notification.Builder(context, CH_PROBLEMS)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Battery use is restricted")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp(showWeb = false))
                .build(),
        )
    }

    fun clearBatteryRestricted() = manager.cancel(ID_BATTERY)

    fun status(text: String): Notification =
        Notification.Builder(context, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("WA Push")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openApp(showWeb = false))
            .build()

    fun updateStatus(notification: Notification) = manager.notify(ID_STATUS, notification)

    private fun genericPublic(text: String = "New message"): Notification =
        Notification.Builder(context, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("WhatsApp")
            .setContentText(text)
            .build()

    private fun openWhatsApp(): PendingIntent {
        val launch = whatsAppLaunchIntent(context)
            ?: Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(context, 10, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun openApp(showWeb: Boolean): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_SHOW_WEB, showWeb)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context, if (showWeb) 12 else 11, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun messageId(key: String) = ID_MESSAGE_BASE + (key.hashCode() and 0x7fffffff) % 1_000_000

    companion object {
        const val CH_MESSAGES = "messages"
        const val CH_PROBLEMS = "problems"
        const val CH_STATUS = "status"
        const val CH_CALLS = "calls"
        const val CH_RING = "calls_ring"
        private const val MAX_LINES = 6

        const val ID_STATUS = 1
        private const val ID_PROBLEM = 2
        private const val ID_NOTIFY_BROKEN = 3
        private const val ID_FALLBACK = 4
        private const val ID_BATTERY = 5
        private const val ID_MESSAGE_BASE = 1000
        private const val ID_CALL_BASE = 2_000_000
        private const val CALL_RING_MS = 60_000L

        val WHATSAPP_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")

        private val ringing = java.util.concurrent.ConcurrentHashMap<String, String>()

        private fun callId(key: String) = ID_CALL_BASE + (key.hashCode() and 0x7fffffff) % 1_000_000

        fun stopRinging(context: Context, key: String) {
            ringing.remove(key)
            context.getSystemService(NotificationManager::class.java).cancel(callId(key))
        }

        fun whatsAppLaunchIntent(context: Context): Intent? {
            val pm = context.packageManager
            return WHATSAPP_PACKAGES.firstNotNullOfOrNull { pm.getLaunchIntentForPackage(it) }
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
