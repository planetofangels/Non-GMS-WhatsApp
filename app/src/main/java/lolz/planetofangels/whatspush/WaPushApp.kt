package lolz.planetofangels.whatspush

import android.app.Application
import lolz.planetofangels.whatspush.log.AppLog
import lolz.planetofangels.whatspush.notify.Notifier

class WaPushApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        Notifier(this).ensureChannels()
    }
}
