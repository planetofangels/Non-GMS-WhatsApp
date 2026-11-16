package lolz.planetofangels.whatspush.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import lolz.planetofangels.whatspush.Prefs
import lolz.planetofangels.whatspush.log.AppLog

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!Prefs(context).enabled) return
        runCatching { WaService.start(context) }
            .onSuccess { AppLog.info("Started after ${intent.action}") }
            .onFailure { AppLog.error("Start after ${intent.action} failed: ${it::class.java.simpleName}") }
    }
}
