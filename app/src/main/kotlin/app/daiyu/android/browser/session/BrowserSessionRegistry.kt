package app.daiyu.android.browser.session

enum class BrowserOwnerKind { MAIN, CHILD }
enum class BrowserAccess { DISABLED, READ_ONLY, FULL }
data class BrowserOwner(val sessionId: String, val kind: BrowserOwnerKind) {
    init { require(sessionId.isNotBlank()) }
}
data class BrowserGrant(val owner: BrowserOwner, val access: BrowserAccess)
data class BrowserTabLease(val owner: BrowserOwner, val tabId: Int, val sessionGeneration: Long, val tabGeneration: Long)
class BrowserTakeoverToken internal constructor(val lease: BrowserTabLease, val cleanupFailure: Exception? = null)

/** Resource ownership is isolated; WebView cookies and site accounts are not. */
class BrowserSessionRegistry(private val release: (BrowserTabLease) -> Unit = {}) {
    private class OwnershipObserver(
        val beforeTakeover: () -> Unit,
        val changed: (BrowserTabLease, Boolean) -> Unit,
        val closed: () -> Unit,
    )
    private data class Tab(
        var generation: Long, var takenOver: Boolean = false, var cleanup: (() -> Unit)? = null,
        val invalidations: MutableList<() -> Unit> = mutableListOf(),
        var active: Int = 0, var ownership: OwnershipObserver? = null,
    )
    private data class Session(
        val grant: BrowserGrant, val generation: Long,
        val tabs: MutableMap<Int, Tab> = linkedMapOf(),
        var tabGeneration: Long = 0, var closed: Boolean = false,
    )
    private val lock = Any()
    internal val serializationMonitor: Any get() = lock
    private val sessions = mutableMapOf<BrowserOwner, Session>()
    private var generation = 0L

    fun open(grant: BrowserGrant) = synchronized(lock) {
        require(grant.access != BrowserAccess.DISABLED) { "BROWSER_DISABLED" }
        val previous = sessions[grant.owner]
        if (previous != null) {
            check(!previous.closed) { "BROWSER_SESSION_CLOSED" }
            check(previous.grant == grant) { "BROWSER_GRANT_FROZEN" }
        } else sessions[grant.owner] = Session(grant, ++generation)
    }
    fun newTab(owner: BrowserOwner): BrowserTabLease = synchronized(lock) {
        val session = session(owner)
        val id = (0..2).firstOrNull { it !in session.tabs } ?: error("BROWSER_TAB_LIMIT")
        val tab = Tab(++session.tabGeneration)
        session.tabs[id] = tab
        lease(session, id, tab)
    }
    fun lease(owner: BrowserOwner, tabId: Int): BrowserTabLease = synchronized(lock) {
        require(tabId in 0..2) { "BROWSER_TAB_ID" }
        val session = session(owner)
        val tab = session.tabs[tabId] ?: error("BROWSER_TAB_MISSING")
        check(!tab.takenOver) { "BROWSER_TAKEN_OVER" }
        lease(session, tabId, tab)
    }
    fun listTabs(owner: BrowserOwner): List<BrowserTabLease> = synchronized(lock) {
        val session = session(owner)
        session.tabs.map { (id, tab) -> lease(session, id, tab) }
    }
    fun assertAllowed(lease: BrowserTabLease, action: String) = synchronized(lock) {
        val session = session(lease.owner)
        tab(session, lease)
        when (session.grant.access) {
            BrowserAccess.DISABLED -> error("BROWSER_DISABLED")
            BrowserAccess.READ_ONLY -> check(action in readOnlyActions) { "BROWSER_READ_ONLY" }
            BrowserAccess.FULL -> Unit
        }
    }
    fun assertCurrent(lease: BrowserTabLease) = synchronized(lock) { tab(session(lease.owner), lease); Unit }
    fun <T> publishTakenOver(lease: BrowserTabLease, block: () -> T): T = synchronized(lock) {
        val tab = tab(session(lease.owner), lease, allowTakeover = true)
        check(tab.takenOver) { "BROWSER_TAKEOVER_EXPIRED" }
        block()
    }

    /** Shared state monitor for the facade: never hold a separate facade lock then enter here.
     * Blocks must be bounded and must not await UI, network, or coroutine completion. */
    fun <T> serialized(block: () -> T): T = synchronized(lock) { block() }

    /** Reserve the entire command, including queued admission. Takeover cannot change generations
     * until every reservation is terminal. Closing does not wait for reservations. */
    fun reserveOperation(lease: BrowserTabLease): AutoCloseable = synchronized(lock) {
        val tab = tab(session(lease.owner), lease)
        tab.active++
        var released = false
        AutoCloseable {
            synchronized(lock) {
                if (!released) { released = true; tab.active-- }
            }
        }
    }

    /** Native initiation fence. The block may run a bounded, nonblocking native/coroutine prefix
     * up to its FIRST suspension, but must never wait. Permission/close/takeover and that prefix
     * are serialized together. Reentrant synchronous close is legal (the monitor is reentrant).
     * Once suspended, the operation is unknown on cancellation and its resource is quarantined. */
    fun <T> startNative(lease: BrowserTabLease, action: String, block: () -> T): T = synchronized(lock) {
        assertAllowed(lease, action)
        block()
    }

    /** Allocation observer survives takeover/release. beforeTakeover and changed run under the
     * fence and MUST be bounded state-only callbacks; closed runs outside it. No native work. */
    fun observeOwnership(
        lease: BrowserTabLease,
        beforeTakeover: () -> Unit,
        changed: (BrowserTabLease, Boolean) -> Unit,
        closed: () -> Unit,
    ): AutoCloseable = synchronized(lock) {
        val tab = tab(session(lease.owner), lease)
        check(tab.ownership == null) { "BROWSER_RESOURCE_ALREADY_ATTACHED" }
        val observer = OwnershipObserver(beforeTakeover, changed, closed)
        tab.ownership = observer
        AutoCloseable { synchronized(lock) { if (tab.ownership === observer) tab.ownership = null } }
    }

    /** Only bounded state publication, never I/O, may run inside this block. */
    fun <T> publish(lease: BrowserTabLease, block: () -> T): T = synchronized(lock) {
        tab(session(lease.owner), lease)
        block()
    }
    /** The callback must enqueue bounded cleanup; it must not wait for WebView or network shutdown. */
    fun registerCleanup(lease: BrowserTabLease, cleanup: () -> Unit) {
        try {
            synchronized(lock) {
                val tab = tab(session(lease.owner), lease)
                check(tab.cleanup == null) { "BROWSER_RESOURCE_ALREADY_ATTACHED" }
                tab.cleanup = cleanup
            }
        } catch (e: Exception) {
            cleanup()
            throw e
        }
    }
    fun onInvalidated(lease: BrowserTabLease, callback: () -> Unit): AutoCloseable {
        try {
            synchronized(lock) { tab(session(lease.owner), lease).invalidations.add(callback) }
        } catch (e: Exception) {
            callback()
            throw e
        }
        return AutoCloseable {
            synchronized(lock) {
                val session = sessions[lease.owner]
                if (session?.generation == lease.sessionGeneration) {
                    val tab = session.tabs[lease.tabId]
                    if (tab?.generation == lease.tabGeneration) tab.invalidations.remove(callback)
                }
            }
        }
    }
    private fun drain(callbacks: List<() -> Unit>) {
        var failure: Exception? = null
        callbacks.forEach { callback ->
            try { callback() } catch (e: Exception) {
                if (failure == null) failure = e else failure.addSuppressed(e)
            }
        }
        failure?.let { throw it }
    }
    fun closeTab(lease: BrowserTabLease) {
        val cleanup = synchronized(lock) {
            val session = session(lease.owner)
            val tab = tab(session, lease)
            session.tabs.remove(lease.tabId)
            listOfNotNull(tab.ownership?.closed) + tab.invalidations.toList() + listOfNotNull(tab.cleanup) + { release(lease) }
        }
        drain(cleanup)
    }
    fun close(owner: BrowserOwner) {
        val resources = synchronized(lock) {
            val session = sessions.getOrPut(owner) {
                Session(BrowserGrant(owner, BrowserAccess.DISABLED), ++generation)
            }
            if (session.closed) return
            session.closed = true
            val leases = session.tabs.flatMap { (id, tab) ->
                val resource = lease(session, id, tab)
                listOfNotNull(tab.ownership?.closed) + tab.invalidations.toList() + listOfNotNull(tab.cleanup) + { release(resource) }
            }
            session.tabs.clear()
            leases
        }
        drain(resources)
    }
    /** Increment on both transitions: late responses stay stale even after resume. */
    fun takeover(lease: BrowserTabLease): BrowserTakeoverToken {
        val (token, callbacks) = synchronized(lock) {
            val session = session(lease.owner)
            val tab = tab(session, lease)
            check(tab.active == 0) { "BROWSER_TAB_ACTIVE" }
            tab.ownership?.beforeTakeover?.invoke()
            tab.takenOver = true
            tab.generation = ++session.tabGeneration
            tab.ownership?.changed?.invoke(lease(session, lease.tabId, tab), true)
            val callbacks = tab.invalidations.toList()
            tab.invalidations.clear()
            BrowserTakeoverToken(lease(session, lease.tabId, tab)) to callbacks
        }
        return try {
            drain(callbacks)
            token
        } catch (e: Exception) {
            BrowserTakeoverToken(token.lease, e)
        }
    }
    fun releaseTakeover(token: BrowserTakeoverToken): BrowserTabLease = synchronized(lock) {
        val lease = token.lease
        val session = session(lease.owner)
        val tab = tab(session, lease, allowTakeover = true)
        check(tab.takenOver) { "BROWSER_TAKEOVER_EXPIRED" }
        tab.takenOver = false
        tab.generation = ++session.tabGeneration
        lease(session, lease.tabId, tab).also { tab.ownership?.changed?.invoke(it, false) }
    }
    private fun session(owner: BrowserOwner): Session {
        val session = sessions[owner] ?: error("BROWSER_SESSION_MISSING")
        check(!session.closed) { "BROWSER_SESSION_CLOSED" }
        return session
    }
    private fun tab(session: Session, lease: BrowserTabLease, allowTakeover: Boolean = false): Tab {
        check(session.generation == lease.sessionGeneration) { "BROWSER_STALE_SESSION" }
        val tab = session.tabs[lease.tabId] ?: error("BROWSER_TAB_MISSING")
        check(tab.generation == lease.tabGeneration) { "BROWSER_STALE_TAB" }
        check(allowTakeover || !tab.takenOver) { "BROWSER_TAKEN_OVER" }
        return tab
    }
    private fun lease(session: Session, id: Int, tab: Tab) =
        BrowserTabLease(session.grant.owner, id, session.generation, tab.generation)
    companion object {
        val readOnlyActions: Set<String> = setOf(
            "get_readable", "get_text", "find_elements", "get_page_info", "get_backbone", "screenshot",
        )
    }
}
