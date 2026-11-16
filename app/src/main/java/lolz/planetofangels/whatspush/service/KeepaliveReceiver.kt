package lolz.planetofangels.whatspush.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import lolz.planetofangels.whatspush.Prefs
import lolz.planetofangels.whatspush.log.AppLog

class KeepaliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val service = WaService.running
        if (service != null) {
            service.onKeepalive()
        } else if (Prefs(context).enabled) {
            AppLog.warn("Keepalive found no service; restarting it")
            runCatching { WaService.start(context) }
                .onFailure { AppLog.error("Restart from keepalive failed: ${it::class.java.simpleName}") }
        }
    }
}
