package lolz.planetofangels.whatspush.health

class UnreadWatcher(private val clock: () -> Long) {
    sealed interface Action {
        data object None : Action
        data object ClearAll : Action

        data class WaitForNotification(val delayMs: Long) : Action
    }

    var unread = 0
        private set
    private var lastPageNotificationAt = Long.MIN_VALUE / 2
    private var riseAt: Long? = null
    private var needsBaseline = true

    var silentRises = 0
        private set

    val notificationsLookBroken: Boolean get() = silentRises >= BROKEN_AFTER

    fun onPageNotification() {
        lastPageNotificationAt = clock()
        riseAt = null
        silentRises = 0
    }

    fun onUnread(count: Int, linked: Boolean): Action {
        val before = unread
        unread = count
        if (!linked) {
            riseAt = null
            return Action.None
        }
        if (needsBaseline) {
            needsBaseline = false
            riseAt = null
            return Action.None
        }
        return when {
            count == 0 && before > 0 -> {
                riseAt = null
                Action.ClearAll
            }
            count > before -> {
                if (clock() - lastPageNotificationAt <= MATCH_BEFORE_MS) {
                    Action.None
                } else {
                    riseAt = clock()
                    Action.WaitForNotification(WAIT_MS)
                }
            }
            else -> Action.None
        }
    }

    fun check(): Int? {
        val rise = riseAt ?: return null
        if (clock() - rise < WAIT_MS) return null
        riseAt = null
        if (lastPageNotificationAt >= rise - MATCH_BEFORE_MS) return null
        silentRises++
        return unread.takeIf { it > 0 }
    }

    fun resetBaseline() {
        needsBaseline = true
        riseAt = null
    }

    companion object {
        const val WAIT_MS = 15_000L
        const val MATCH_BEFORE_MS = 10_000L
        const val BROKEN_AFTER = 3
    }
}
