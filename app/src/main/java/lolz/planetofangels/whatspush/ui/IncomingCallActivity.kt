package lolz.planetofangels.whatspush.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import lolz.planetofangels.whatspush.notify.Notifier

class IncomingCallActivity : Activity() {
    private var key = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        current = this
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        key = intent.getStringExtra(EXTRA_KEY).orEmpty()
        when (intent.getStringExtra(EXTRA_ACTION)) {
            ACTION_ANSWER -> answer()
            else -> show(intent.getStringExtra(EXTRA_CALLER) ?: "WhatsApp")
        }
    }

    private fun show(caller: String) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(32), dp(120), dp(32), dp(64))
        }
        root.addView(text(caller, 32f, Color.WHITE, bold = true))
        root.addView(text("WhatsApp call", 16f, Color.GRAY).apply { setPadding(0, dp(8), 0, 0) })
        root.addView(android.view.View(this), LinearLayout.LayoutParams(1, 0, 1f))
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        buttons.addView(round("Decline", 0xFFD32F2F.toInt()) { decline() })
        buttons.addView(android.view.View(this), LinearLayout.LayoutParams(dp(64), 1))
        buttons.addView(round("Answer", 0xFF2E7D32.toInt()) { answer() })
        root.addView(buttons)
        setContentView(root)
    }

    private fun answer() {
        Notifier.stopRinging(this, key)
        val km = getSystemService(KeyguardManager::class.java)
        if (km.isKeyguardLocked) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = openWhatsApp()
            })
        } else {
            openWhatsApp()
        }
    }

    private fun decline() {
        Notifier.stopRinging(this, key)
        finish()
    }

    private fun openWhatsApp() {
        Notifier.whatsAppLaunchIntent(this)?.let { runCatching { startActivity(it) } }
        finish()
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun round(label: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        text = label
        setTextColor(Color.WHITE)
        isAllCaps = false
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        layoutParams = ViewGroup.LayoutParams(dp(96), dp(96))
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_KEY = "key"
        const val EXTRA_CALLER = "caller"
        const val EXTRA_ACTION = "action"
        const val ACTION_ANSWER = "answer"

        @Volatile var current: IncomingCallActivity? = null
            private set

        fun intent(context: Context, key: String, caller: String, action: String? = null): Intent =
            Intent(context, IncomingCallActivity::class.java)
                .putExtra(EXTRA_KEY, key)
                .putExtra(EXTRA_CALLER, caller)
                .putExtra(EXTRA_ACTION, action)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
    }
}
