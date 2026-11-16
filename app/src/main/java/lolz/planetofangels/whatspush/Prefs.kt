package lolz.planetofangels.whatspush

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("wapush", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    var everLinked: Boolean
        get() = sp.getBoolean("ever_linked", false)
        set(v) = sp.edit().putBoolean("ever_linked", v).apply()

    var keepaliveSec: Int
        get() = sp.getInt("keepalive_sec", 0)
        set(v) = sp.edit().putInt("keepalive_sec", v).apply()

    var unreadFallback: Boolean
        get() = sp.getBoolean("unread_fallback", true)
        set(v) = sp.edit().putBoolean("unread_fallback", v).apply()

    var catchUpTimers: Boolean
        get() = sp.getBoolean("catch_up_timers", true)
        set(v) = sp.edit().putBoolean("catch_up_timers", v).apply()

    var alwaysAwake: Boolean
        get() = sp.getBoolean("always_awake", false)
        set(v) = sp.edit().putBoolean("always_awake", v).apply()
}
