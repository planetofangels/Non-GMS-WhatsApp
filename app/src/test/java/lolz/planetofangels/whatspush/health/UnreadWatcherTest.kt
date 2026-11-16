package lolz.planetofangels.whatspush.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnreadWatcherTest {
    private var now = 5_000_000L
    private val w = UnreadWatcher { now }.also { it.onUnread(0, linked = true) }

    @Test fun riseWithNotificationNeedsNoFallback() {
        w.onPageNotification()
        now += 1_000
        assertEquals(UnreadWatcher.Action.None, w.onUnread(1, linked = true))
    }

    @Test fun notificationArrivingAfterRiseCancelsFallback() {
        assertTrue(w.onUnread(1, true) is UnreadWatcher.Action.WaitForNotification)
        now += 3_000
        w.onPageNotification()
        now += UnreadWatcher.WAIT_MS
        assertNull(w.check())
    }

    @Test fun silentRiseFallsBackAndThreeInARowLooksBroken() {
        repeat(3) { i ->
            w.onUnread(i + 1, true)
            now += UnreadWatcher.WAIT_MS
            assertEquals(i + 1, w.check())
            now += 60_000
        }
        assertTrue(w.notificationsLookBroken)
        w.onPageNotification()
        assertFalse(w.notificationsLookBroken)
    }

    @Test fun dropToZeroClearsAll() {
        w.onPageNotification()
        w.onUnread(2, true)
        assertEquals(UnreadWatcher.Action.ClearAll, w.onUnread(0, true))
    }

    @Test fun countsWhileNotLinkedAreIgnored() {
        assertEquals(UnreadWatcher.Action.None, w.onUnread(4, linked = false))
        now += UnreadWatcher.WAIT_MS
        assertNull(w.check())
    }

    @Test fun firstCountAfterReloadIsBaselineNotARise() {
        w.resetBaseline()
        now += 60_000
        assertEquals(UnreadWatcher.Action.None, w.onUnread(0, linked = false))
        assertEquals(UnreadWatcher.Action.None, w.onUnread(3, linked = true))
        now += UnreadWatcher.WAIT_MS
        assertNull(w.check())
        assertTrue(w.onUnread(4, true) is UnreadWatcher.Action.WaitForNotification)
    }
}
