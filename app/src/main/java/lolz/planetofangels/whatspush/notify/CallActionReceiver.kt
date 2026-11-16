package lolz.planetofangels.whatspush.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Notifier.stopRinging(context, intent.getStringExtra(EXTRA_KEY).orEmpty())
        lolz.planetofangels.whatspush.ui.IncomingCallActivity.current?.finish()
    }

    companion object {
        const val EXTRA_KEY = "key"
    }
}
