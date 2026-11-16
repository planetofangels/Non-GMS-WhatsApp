package lolz.planetofangels.whatspush.health

class KeepalivePolicy(private val clock: () -> Long, startSec: Int = START_SEC) {
    var intervalSec: Int = startSec.coerceIn(MIN_SEC, MAX_SEC)
        private set

    private var stableSince = clock()

    fun onDroppedWhileAsleep(): Boolean {
        stableSince = clock()
        val next = (intervalSec * 2 / 3).coerceAtLeast(MIN_SEC)
        return change(next)
    }

    fun onConnected(): Boolean {
        if (clock() - stableSince < STABLE_MS) return false
        stableSince = clock()
        return change((intervalSec * 5 / 4).coerceAtMost(MAX_SEC))
    }

    private fun change(next: Int): Boolean {
        if (next == intervalSec) return false
        intervalSec = next
        return true
    }

    companion object {
        const val START_SEC = 60
        const val MIN_SEC = 30
        const val MAX_SEC = 180
        const val STABLE_MS = 2 * 60 * 60_000L
    }
}
