package lolz.planetofangels.whatspush.health

enum class Status {
    STARTING, LOADING, LOAD_FAILED, NEEDS_LINK, SYNCING, CONNECTED, RECONNECTING, OFFLINE, UNRECOGNIZED,
}

enum class Problem {
    LOGGED_OUT, NO_CONNECTION, LOAD_FAILING, PAGE_CHANGED, CRASHING,

    CANT_VERIFY,
}

data class Verdict(val status: Status, val problem: Problem?, val reload: Boolean)

class HealthMonitor(private val clock: () -> Long, var everLinked: Boolean = false) {
    private var online = true
    private var onlineSince = clock()

    private var pageStartedAt = clock()
    private var pageLoadedAt: Long? = null
    private var loadFailedAt: Long? = null

    private var chats = false
    private var qr = false
    private var qrSince: Long? = null
    private var unknownSince: Long? = null
    private var loadingSince: Long? = null

    private val seenSockets = mutableSetOf<Int>()
    private val socketsSeen get() = seenSockets.size
    private val openSockets = mutableSetOf<Int>()
    private var allClosedSince: Long? = null
    private var lastFrameAt = 0L

    private val crashes = ArrayDeque<Long>()

    private var lastReloadAt = Long.MIN_VALUE / 2
    private var failedRecoveries = 0

    var status: Status = Status.STARTING
        private set

    fun onNetwork(isOnline: Boolean) {
        if (isOnline && !online) {
            onlineSince = clock()

            failedRecoveries = 0
            lastReloadAt = Long.MIN_VALUE / 2
        }
        online = isOnline
    }

    fun stuckAfterNetworkBack(): Boolean {
        if (!online) return false
        if (loadFailedAt != null) return true
        return chats && pageLoadedAt != null && lastFrameAt < onlineSince
    }

    fun onPageStarted() {
        pageStartedAt = clock()
        pageLoadedAt = null
        loadFailedAt = null
        chats = false
        qr = false
        qrSince = null
        unknownSince = null
        loadingSince = null
        seenSockets.clear()
        openSockets.clear()
        allClosedSince = clock()
        lastFrameAt = 0L
    }

    fun onPageFinished() {
        if (loadFailedAt == null) pageLoadedAt = clock()
    }

    fun onLoadError() {
        if (loadFailedAt == null) loadFailedAt = clock()
        pageLoadedAt = null
    }

    fun onRendererGone() {
        crashes.addLast(clock())
        onPageStarted()
    }

    fun onState(chatsVisible: Boolean, qrVisible: Boolean, loadingVisible: Boolean = false) {
        val now = clock()
        if (pageLoadedAt == null && loadFailedAt == null) pageLoadedAt = now
        chats = chatsVisible
        qr = qrVisible && !chatsVisible
        qrSince = if (qr) qrSince ?: now else null
        val loading = loadingVisible && !chats && !qr
        loadingSince = if (loading) loadingSince ?: now else null
        unknownSince = if (!chats && !qr && !loading) unknownSince ?: now else null
        if (chats) everLinked = true
    }

    fun onSocket(event: String, id: Int) {
        val now = clock()

        seenSockets += id
        when (event) {
            "new" -> Unit
            "open" -> {
                openSockets += id
                allClosedSince = null
                lastFrameAt = now
            }
            "frame" -> {
                lastFrameAt = now
                if (id !in openSockets) {
                    openSockets += id
                    allClosedSince = null
                }
            }
            "close" -> {
                openSockets -= id
                if (openSockets.isEmpty() && allClosedSince == null) allClosedSince = now
            }
        }
    }

    val connectionFresh: Boolean
        get() = openSockets.isNotEmpty() && clock() - lastFrameAt < STALE_FRAME_MS

    val lastFrameAgeMs: Long?
        get() = if (lastFrameAt == 0L) null else clock() - lastFrameAt

    fun markReloaded() {
        lastReloadAt = clock()
        failedRecoveries++
    }

    fun evaluate(): Verdict {
        val now = clock()
        val verdict = decide(now)
        status = verdict.status
        if (verdict.status == Status.CONNECTED || verdict.status == Status.NEEDS_LINK) failedRecoveries = 0
        while (crashes.isNotEmpty() && now - crashes.first() > CRASH_WINDOW_MS) crashes.removeFirst()
        return if (verdict.problem == null && crashes.size >= CRASH_LIMIT) {
            verdict.copy(problem = Problem.CRASHING)
        } else {
            verdict
        }
    }

    private fun decide(now: Long): Verdict {
        if (!online) return Verdict(Status.OFFLINE, null, reload = false)

        fun lasted(since: Long) = now - maxOf(since, onlineSince)

        loadFailedAt?.let { failedAt ->
            val problem = if (lasted(failedAt) >= LOAD_FAIL_WARN_MS) Problem.LOAD_FAILING else null
            return Verdict(Status.LOAD_FAILED, problem, reload = reloadAllowed(now))
        }

        val loadedAt = pageLoadedAt
            ?: return Verdict(Status.LOADING, null, reload = lasted(pageStartedAt) >= LOAD_STUCK_MS && reloadAllowed(now))

        if (chats) {
            if (socketsSeen == 0) {
                val blindFor = lasted(loadedAt)
                if (blindFor < RECONNECT_GRACE_MS) return Verdict(Status.SYNCING, null, reload = false)
                val problem = if (blindFor >= DISCONNECT_WARN_MS) Problem.CANT_VERIFY else null
                return Verdict(Status.CONNECTED, problem, reload = reloadAllowed(now))
            }
            if (openSockets.isNotEmpty()) {
                if (now - lastFrameAt < STALE_FRAME_MS) return Verdict(Status.CONNECTED, null, reload = false)
                val staleFor = lasted(lastFrameAt + STALE_FRAME_MS)
                val problem = if (staleFor >= DISCONNECT_WARN_MS) Problem.NO_CONNECTION else null
                return Verdict(Status.RECONNECTING, problem, reload = reloadAllowed(now))
            }
            val downFor = lasted(allClosedSince ?: now)
            val problem = if (downFor >= DISCONNECT_WARN_MS) Problem.NO_CONNECTION else null
            return Verdict(
                Status.RECONNECTING, problem,
                reload = downFor >= RECONNECT_GRACE_MS && reloadAllowed(now),
            )
        }

        if (qr) {
            val loggedOut = everLinked && now - (qrSince ?: now) >= LOGOUT_CONFIRM_MS
            return Verdict(Status.NEEDS_LINK, if (loggedOut) Problem.LOGGED_OUT else null, reload = false)
        }

        loadingSince?.let { since ->
            val loadingFor = lasted(since)
            if (loadingFor < LONG_SYNC_MS) return Verdict(Status.SYNCING, null, reload = false)
            return Verdict(Status.UNRECOGNIZED, Problem.PAGE_CHANGED, reload = reloadAllowed(now))
        }

        val unknownFor = lasted(unknownSince ?: loadedAt)
        if (unknownFor < SYNC_GRACE_MS) return Verdict(Status.SYNCING, null, reload = false)
        val problem = if (unknownFor >= PAGE_CHANGED_WARN_MS) Problem.PAGE_CHANGED else null
        return Verdict(Status.UNRECOGNIZED, problem, reload = reloadAllowed(now))
    }

    private fun reloadAllowed(now: Long): Boolean {
        val backoff = (RELOAD_BASE_MS shl failedRecoveries.coerceAtMost(4)).coerceAtMost(RELOAD_MAX_MS)
        return now - lastReloadAt >= backoff
    }

    companion object {
        const val STALE_FRAME_MS = 8 * 60_000L
        const val RECONNECT_GRACE_MS = 2 * 60_000L
        const val DISCONNECT_WARN_MS = 10 * 60_000L
        const val LOAD_STUCK_MS = 2 * 60_000L
        const val LOAD_FAIL_WARN_MS = 10 * 60_000L
        const val LOGOUT_CONFIRM_MS = 60_000L
        const val SYNC_GRACE_MS = 5 * 60_000L
        const val PAGE_CHANGED_WARN_MS = 15 * 60_000L
        const val LONG_SYNC_MS = 45 * 60_000L
        const val RELOAD_BASE_MS = 2 * 60_000L
        const val RELOAD_MAX_MS = 30 * 60_000L
        const val CRASH_WINDOW_MS = 60 * 60_000L
        const val CRASH_LIMIT = 3
    }
}
