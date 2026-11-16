package lolz.planetofangels.whatspush.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthMonitorTest {
    private var now = 1_000_000L
    private val min = 60_000L
    private fun monitor(everLinked: Boolean = false) = HealthMonitor({ now }, everLinked)

    private fun HealthMonitor.connectedPage() {
        onPageStarted()
        onPageFinished()
        onSocket("new", 1)
        onSocket("open", 1)
        onState(chatsVisible = true, qrVisible = false)
    }

    @Test fun connectedWhenChatsAndFreshSocket() {
        val m = monitor()
        m.connectedPage()
        val v = m.evaluate()
        assertEquals(Status.CONNECTED, v.status)
        assertNull(v.problem)
        assertFalse(v.reload)
        assertTrue(m.everLinked)
    }

    @Test fun parallelSocketClosingIsNotADisconnect() {
        val m = monitor()
        m.connectedPage()
        m.onSocket("new", 2)
        m.onSocket("open", 2)
        m.onSocket("close", 1)
        assertEquals(Status.CONNECTED, m.evaluate().status)
    }

    @Test fun shortDisconnectToleratedThenReloadThenWarning() {
        val m = monitor()
        m.connectedPage()
        m.onSocket("close", 1)
        now += 1 * min
        assertEquals(Verdict(Status.RECONNECTING, null, false), m.evaluate())
        now += 2 * min
        val v = m.evaluate()
        assertTrue(v.reload)
        assertNull(v.problem)
        m.markReloaded()
        m.onPageStarted(); m.onPageFinished(); m.onState(true, false)
        m.onSocket("new", 2)
        m.onSocket("close", 2)
        now += 11 * min
        assertEquals(Problem.NO_CONNECTION, m.evaluate().problem)
    }

    @Test fun offlineNeverWarnsAndTimeOfflineDoesNotCount() {
        val m = monitor()
        m.connectedPage()
        m.onSocket("close", 1)
        m.onNetwork(false)
        now += 8 * 60 * min
        val off = m.evaluate()
        assertEquals(Status.OFFLINE, off.status)
        assertNull(off.problem)
        assertFalse(off.reload)
        m.onNetwork(true)
        now += 1 * min
        val v = m.evaluate()
        assertEquals(Status.RECONNECTING, v.status)
        assertFalse("WhatsApp gets a grace period to reconnect itself", v.reload)
        assertNull(v.problem)
    }

    @Test fun staleOpenSocketCountsAsDead() {
        val m = monitor()
        m.connectedPage()
        now += HealthMonitor.STALE_FRAME_MS + 1
        assertEquals(Status.RECONNECTING, m.evaluate().status)
        m.onSocket("frame", 1)
        assertEquals(Status.CONNECTED, m.evaluate().status)
    }

    @Test fun qrAfterBeingLinkedWarnsLoggedOutAfterConfirmation() {
        val m = monitor(everLinked = true)
        m.onPageStarted(); m.onPageFinished()
        m.onState(chatsVisible = false, qrVisible = true)
        assertNull(m.evaluate().problem)
        now += 2 * min
        m.onState(chatsVisible = false, qrVisible = true)
        val v = m.evaluate()
        assertEquals(Status.NEEDS_LINK, v.status)
        assertEquals(Problem.LOGGED_OUT, v.problem)
    }

    @Test fun qrBeforeFirstLinkIsNotAProblem() {
        val m = monitor(everLinked = false)
        m.onPageStarted(); m.onPageFinished()
        m.onState(false, true)
        now += 60 * min
        assertNull(m.evaluate().problem)
    }

    @Test fun silentPageBecomesUnrecognizedThenWarns() {
        val m = monitor()
        m.onPageStarted(); m.onPageFinished()
        now += 2 * min
        assertEquals(Status.SYNCING, m.evaluate().status)
        now += 4 * min
        val v = m.evaluate()
        assertEquals(Status.UNRECOGNIZED, v.status)
        assertTrue(v.reload)
        m.markReloaded()
        now += 10 * min
        assertEquals(Problem.PAGE_CHANGED, m.evaluate().problem)
    }

    @Test fun loadFailureRetriesWithBackoffAndWarnsLater() {
        val m = monitor()
        m.onPageStarted()
        m.onLoadError()
        assertTrue(m.evaluate().reload)
        m.markReloaded()
        now += 1 * min
        assertFalse(m.evaluate().reload)
        now += 4 * min
        assertTrue(m.evaluate().reload)
        m.markReloaded()
        now += 6 * min
        assertEquals(Problem.LOAD_FAILING, m.evaluate().problem)
    }

    @Test fun repeatedCrashesWarn() {
        val m = monitor()
        repeat(3) { m.onRendererGone(); now += 5 * min }
        m.connectedPage()
        assertEquals(Problem.CRASHING, m.evaluate().problem)
        now += 61 * min
        m.onSocket("frame", 1)
        assertNull(m.evaluate().problem)
    }

    @Test fun stuckLoadingReloads() {
        val m = monitor()
        m.onPageStarted()
        now += 3 * min
        val v = m.evaluate()
        assertEquals(Status.LOADING, v.status)
        assertTrue(v.reload)
    }

    @Test fun chatsWithoutAnyConnectionSeenIsNotSilentlyOk() {
        val m = monitor()
        m.onPageStarted(); m.onPageFinished(); m.onState(true, false)
        assertEquals(Status.SYNCING, m.evaluate().status)
        now += 3 * min
        val v = m.evaluate()
        assertTrue("tries a reload first", v.reload)
        assertNull(v.problem)
        m.markReloaded()
        now += 8 * min
        assertEquals(Problem.CANT_VERIFY, m.evaluate().problem)
    }

    @Test fun longFirstSyncIsNotReloaded() {
        val m = monitor()
        m.onPageStarted(); m.onPageFinished()
        m.onState(chatsVisible = false, qrVisible = false, loadingVisible = true)
        now += 30 * min
        val v = m.evaluate()
        assertEquals(Status.SYNCING, v.status)
        assertFalse(v.reload)
        assertNull(v.problem)
        now += 20 * min
        assertEquals(Problem.PAGE_CHANGED, m.evaluate().problem)
    }

    @Test fun socketEventBeforePageStartIsStillSeen() {
        val m = monitor()
        m.onPageStarted(); m.onPageFinished()
        m.onState(true, false)
        m.onPageStarted()
        m.onPageFinished()
        m.onSocket("frame", 1)
        m.onState(true, false)
        assertEquals(Status.CONNECTED, m.evaluate().status)
    }

    @Test fun failedLoadWhileOfflineIsRetriedRightAwayWhenBack() {
        val m = monitor()
        m.connectedPage()
        m.markReloaded()
        m.onPageStarted()
        m.onLoadError()
        m.onNetwork(false)
        now += 3 * min
        m.onNetwork(true)
        assertTrue(m.stuckAfterNetworkBack())
        assertTrue(m.evaluate().reload)
    }

    @Test fun connectionThatRecoversByItselfIsLeftAlone() {
        val m = monitor()
        m.connectedPage()
        m.onNetwork(false)
        now += 2 * min
        m.onNetwork(true)
        assertTrue(m.stuckAfterNetworkBack())
        now += 5_000
        m.onSocket("new", 2)
        m.onSocket("open", 2)
        m.onSocket("frame", 2)
        assertFalse(m.stuckAfterNetworkBack())
    }

    @Test fun neverStuckWhileOffline() {
        val m = monitor()
        m.connectedPage()
        m.onLoadError()
        m.onNetwork(false)
        assertFalse(m.stuckAfterNetworkBack())
    }
}
