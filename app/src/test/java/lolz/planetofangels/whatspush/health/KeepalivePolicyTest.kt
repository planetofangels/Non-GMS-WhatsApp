package lolz.planetofangels.whatspush.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepalivePolicyTest {
    private var now = 0L
    private val p = KeepalivePolicy({ now })

    @Test fun dropsShortenDownToMinimum() {
        assertTrue(p.onDroppedWhileAsleep())
        assertEquals(40, p.intervalSec)
        repeat(5) { p.onDroppedWhileAsleep() }
        assertEquals(KeepalivePolicy.MIN_SEC, p.intervalSec)
    }

    @Test fun stabilityLengthensUpToMaximum() {
        assertFalse(p.onConnected())
        repeat(20) {
            now += KeepalivePolicy.STABLE_MS
            p.onConnected()
        }
        assertEquals(KeepalivePolicy.MAX_SEC, p.intervalSec)
    }
}
