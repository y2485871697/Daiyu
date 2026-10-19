package app.daiyu.android.browser.host

import app.daiyu.android.browser.bridge.BrowserCommand
import app.daiyu.android.browser.bridge.BrowserExecutionPort
import app.daiyu.android.browser.bridge.BrowserExecutionResult
import app.daiyu.android.browser.bridge.BrowserHostBridge
import app.daiyu.android.browser.bridge.BrowserOutcome
import app.daiyu.android.browser.session.BrowserGrant
import app.daiyu.android.browser.session.BrowserSessionRegistry
import app.daiyu.android.browser.session.BrowserTabLease
import app.daiyu.android.browser.session.BrowserTakeoverToken

/**
 * Synchronous resource boundary, NOT a UI queue. The host supplies the UI-thread adapter.
 * create creates a blank resource, never navigates or changes cookies/settings. close must also
 * dispose partially created resources after create throws, and tolerate an absent resource.
 * Both receive the ORIGINAL allocation lease: key resources by its owner AND both generations,
 * never by tabId alone. Takeover changes the execution lease, not the resource's allocation key.
 * close is called once per allocation, after create has returned/thrown (even if session close
 * races create). Like Registry cleanup, close must enqueue bounded cleanup, not await UI/network.
 */
interface BrowserTabLifecycle {
    /** False means takeover is rejected before changing registry generations. */
    val supportsTakeover: Boolean get() = true
    fun create(lease: BrowserTabLease)
    fun close(lease: BrowserTabLease)
    /** Original allocation key, new execution lease. Runs outside the registry/facade fence;
     * must finish in bounded time. A failure is unknown: close, never retry/recreate. */
    fun rebind(allocation: BrowserTabLease, lease: BrowserTabLease) {}
    /** Bounded state-only precheck. Rejection does not consume the takeover token. */
    fun beforeReleaseTakeover(allocation: BrowserTabLease, lease: BrowserTabLease) {}
}

/** Trusted-host capability. It is not a model argument and must not be serialized as one. */
class BrowserToolTakeoverToken internal constructor(
    internal val session: BrowserToolSession,
    internal val token: BrowserTakeoverToken,
) {
    val lease: BrowserTabLease get() = token.lease
    val cleanupFailure: Exception? get() = token.cleanupFailure
}

/**
 * One facade per owner, created by the trusted host from its frozen grant. The host must use
 * this facade for that owner's tab/session mutations (not mutate the registry behind it).
 * Owners isolate resources, NOT WebView cookies or site accounts. No file paths/cookie policy
 * are invented here. Construction opens an empty session; it never implicitly creates a tab.
 *
 * execute is the single model-facing entry: a flat map with action, optional tabId, and the
 * action's arguments. No owner/grant/session arguments exist. Exactly one action is performed.
 * new_tab accepts NO tabId/arguments and creates only a blank tab (also in read-only mode).
 * list_tabs accepts NO tabId/arguments; close_tab accepts only optional tabId. Unsupported
 * new_tab navigation/options are rejected, not ignored or split into another action.
 * tabId accepts Int, bounded Long, or canonical "0"/"1"/"2"; explicit null is not omission.
 * A successful new_tab selects it. An ordinary action selects its tab when entering the port,
 * even if execution subsequently fails. Closing current selects the lowest READY id, or none;
 * closing another tab preserves current. Takeover keeps current selected but blocks execution
 * until release; release publishes the new lease without selecting over another current tab.
 *
 * Lifecycle and port calls run outside the facade monitor. This is not a UI dispatcher or
 * execution queue. Unknown means do NOT replay: no automatic retries, fallback navigation,
 * reopening, or repeated cleanup are performed, including failed open/close operations.
 */
class BrowserToolSession(
    val grant: BrowserGrant,
    private val sessions: BrowserSessionRegistry,
    private val lifecycle: BrowserTabLifecycle,
    private val port: BrowserExecutionPort,
    /** Pure backend support check; runs before selection, reservation, or port entry. */
    private val preflight: (BrowserCommand) -> Unit = {},
) : AutoCloseable {
    private enum class Phase { CREATING, READY, TRANSITION, TAKEN_OVER, CLOSING }
    private class Slot(val allocation: BrowserTabLease) {
        var lease = allocation
        var phase = Phase.CREATING
        var creationFinished = false
        var cleanupRequested = false
        var cleanupStarted = false
    }
    // Same reentrant monitor as native initiation: no registry -> facade lock inversion when a
    // native prefix synchronously calls close while another thread selects/closes a tab.
    private val lock = sessions.serializationMonitor
    private val slots = mutableMapOf<Int, Slot>()
    private var current: Slot? = null
    private var closed = false

    init { sessions.open(grant) }

    /** Snapshot for trusted host code; may be a taken-over lease, never an execution authority. */
    val currentTab: BrowserTabLease? get() = synchronized(lock) { current?.lease }

    val visibleTabs: List<BrowserTabLease> get() = synchronized(lock) {
        if (closed) emptyList() else slots.values.filter { it.phase == Phase.READY || it.phase == Phase.TAKEN_OVER }
            .map { it.lease }.sortedBy { it.tabId }
    }

    /** Trusted host only: resolve the exact token to its original allocation. */
    fun <T> withTakenOverTab(
        token: BrowserToolTakeoverToken,
        block: (allocation: BrowserTabLease, lease: BrowserTabLease) -> T,
    ): T = synchronized(lock) {
        checkOpen()
        check(token.session === this) { "BROWSER_TAKEOVER_OWNER" }
        check(token.cleanupFailure == null) { "BROWSER_TAKEOVER_CLEANUP_FAILED" }
        val slot = slots[token.lease.tabId] ?: error("BROWSER_TAB_MISSING")
        check(slot.phase == Phase.TAKEN_OVER && slot.lease == token.lease) { "BROWSER_TAKEOVER_EXPIRED" }
        sessions.publishTakenOver(token.lease) { block(slot.allocation, token.lease) }
    }

    fun execute(parameters: Map<String, Any?>): BrowserOutcome {
        var entered = false
        return try {
            // Snapshot top-level arguments. Bridge performs all ordinary-action validation.
            val input = parameters.toMap()
            val action = input["action"] as? String ?: error("BROWSER_ACTION_REQUIRED")
            val id = if ("tabId" in input) normalizeTabId(input["tabId"]) else null
            val arguments = input.filterKeys { it != "action" && it != "tabId" }
            synchronized(lock) { checkOpen() }
            val result = when (action) {
                "new_tab" -> {
                    require(id == null && arguments.isEmpty()) { "BROWSER_TAB_ARGUMENTS" }
                    newTab { entered = true }
                }
                "list_tabs" -> {
                    require(id == null && arguments.isEmpty()) { "BROWSER_TAB_ARGUMENTS" }
                    synchronized(lock) {
                        checkOpen()
                        val tabs = slots.values.filter { it.phase == Phase.READY || it.phase == Phase.TAKEN_OVER }
                            .sortedBy { it.lease.tabId }.joinToString(",") {
                                "{\"tabId\":${it.lease.tabId},\"takenOver\":${it.phase == Phase.TAKEN_OVER}}"
                            }
                        BrowserExecutionResult("{\"tabs\":[$tabs],\"currentTabId\":${current?.lease?.tabId}}")
                    }
                }
                "close_tab" -> {
                    require(arguments.isEmpty()) { "BROWSER_TAB_ARGUMENTS" }
                    val lease = synchronized(lock) { resolve(id).lease }
                    closeTabChecked(lease) { entered = true }
                    synchronized(lock) {
                        BrowserExecutionResult("{\"closedTabId\":${lease.tabId},\"currentTabId\":${current?.lease?.tabId}}")
                    }
                }
                else -> {
                    val expected = synchronized(lock) { resolve(id).lease }
                    val command = BrowserCommand(action, expected.tabId, arguments)
                    preflight(command)
                    // Bridge resolves by integer id. Pin the resolved generation at the port
                    // boundary so a concurrent close/reuse cannot redirect this call to a new tab.
                    val bridge = BrowserHostBridge(sessions, object : BrowserExecutionPort {
                        override fun execute(command: BrowserCommand, lease: BrowserTabLease): BrowserExecutionResult {
                            check(lease == expected) { "BROWSER_STALE_TAB" }
                            val reservation = synchronized(lock) {
                                current = requireReady(expected)
                                sessions.reserveOperation(expected)
                            }
                            entered = true
                            return try { port.execute(command, lease) } finally { reservation.close() }
                        }
                    })
                    bridge.execute(grant.owner, command)
                }
            }
            BrowserOutcome.Confirmed(result)
        } catch (e: Exception) {
            if (entered) BrowserOutcome.Unknown
            else BrowserOutcome.NotExecuted(e.message?.takeIf { it.startsWith("BROWSER_") } ?: "BROWSER_PRECHECK_REJECTED")
        }
    }

    private fun newTab(entering: () -> Unit): BrowserExecutionResult {
        val slot = synchronized(lock) {
            checkOpen()
            Slot(sessions.newTab(grant.owner)).also { slots[it.lease.tabId] = it }
        }
        try {
            sessions.registerCleanup(slot.allocation) { requestCleanup(slot) }
            synchronized(lock) {
                checkOpen()
                check(slots[slot.lease.tabId] === slot && !slot.cleanupRequested) { "BROWSER_STALE_TAB" }
            }
            entering()
            lifecycle.create(slot.allocation)
            synchronized(lock) {
                checkOpen()
                check(slots[slot.lease.tabId] === slot && !slot.cleanupRequested) { "BROWSER_STALE_TAB" }
                sessions.assertCurrent(slot.lease)
                slot.phase = Phase.READY
                current = slot
            }
            return BrowserExecutionResult("{\"tabId\":${slot.lease.tabId},\"currentTabId\":${slot.lease.tabId}}")
        } catch (e: Exception) {
            // Exact lease only: never close a replacement that reused the integer slot.
            try { sessions.closeTab(slot.allocation) } catch (cleanup: Exception) { e.addSuppressed(cleanup) }
            try { requestCleanup(slot) } catch (cleanup: Exception) { e.addSuppressed(cleanup) }
            throw e
        } finally {
            val cleanup = synchronized(lock) {
                slot.creationFinished = true
                claimCleanup(slot)
            }
            if (cleanup) lifecycle.close(slot.allocation)
        }
    }

    private fun requestCleanup(slot: Slot) {
        val cleanup = synchronized(lock) {
            slot.cleanupRequested = true
            if (slots[slot.allocation.tabId] === slot) {
                slots.remove(slot.allocation.tabId)
                if (current === slot) selectAfterClose()
            }
            claimCleanup(slot)
        }
        if (cleanup) lifecycle.close(slot.allocation)
    }

    private fun claimCleanup(slot: Slot): Boolean {
        if (!slot.creationFinished || !slot.cleanupRequested || slot.cleanupStarted) return false
        slot.cleanupStarted = true
        return true
    }

    private fun selectAfterClose() {
        current = slots.values.filter { it.phase == Phase.READY }.minByOrNull { it.lease.tabId }
    }

    private fun checkOpen() { check(!closed) { "BROWSER_SESSION_CLOSED" } }

    private fun resolve(id: Int?): Slot {
        checkOpen()
        val slot = if (id == null) current ?: error("BROWSER_CURRENT_TAB_MISSING")
        else slots[id] ?: error("BROWSER_TAB_MISSING")
        requireReady(slot.lease)
        return slot
    }

    private fun requireReady(lease: BrowserTabLease): Slot {
        checkOpen()
        check(lease.owner == grant.owner) { "BROWSER_OWNER_MISMATCH" }
        val slot = slots[lease.tabId] ?: error("BROWSER_TAB_MISSING")
        check(slot.lease == lease) { "BROWSER_STALE_TAB" }
        check(slot.phase != Phase.TAKEN_OVER) { "BROWSER_TAKEN_OVER" }
        check(slot.phase == Phase.READY) { "BROWSER_TAB_BUSY" }
        sessions.assertCurrent(lease)
        return slot
    }

    /** Trusted host close by full generation-bearing lease, never by untrusted integer alone. */
    fun closeTab(lease: BrowserTabLease) = closeTabChecked(lease) {}

    private fun closeTabChecked(lease: BrowserTabLease, entering: () -> Unit) {
        synchronized(lock) {
            val slot = requireReady(lease)
            slot.phase = Phase.CLOSING
            if (current === slot) selectAfterClose()
        }
        // Registry removes the slot BEFORE callbacks. Failure is unknown, not retryable.
        entering()
        sessions.closeTab(lease)
    }

    /** Trusted host takeover. Token is returned even if an invalidation callback failed. */
    fun takeover(lease: BrowserTabLease): BrowserToolTakeoverToken {
        check(lifecycle.supportsTakeover) { "BROWSER_TAKEOVER_UNSUPPORTED" }
        val slot = synchronized(lock) { requireReady(lease).also { it.phase = Phase.TRANSITION } }
        try {
            val token = sessions.takeover(lease)
            synchronized(lock) {
                if (!closed && slots[lease.tabId] === slot) {
                    slot.lease = token.lease
                    slot.phase = Phase.TAKEN_OVER
                }
            }
            return BrowserToolTakeoverToken(this, token)
        } catch (e: Exception) {
            synchronized(lock) { if (slots[lease.tabId] === slot) slot.phase = Phase.READY }
            throw e
        }
    }

    /** Trusted host resume; old leases and consumed tokens remain stale. */
    fun releaseTakeover(token: BrowserToolTakeoverToken): BrowserTabLease {
        val slot = beginRelease(token)
        val resumed = try {
            lifecycle.beforeReleaseTakeover(slot.allocation, token.lease)
            sessions.releaseTakeover(token.token)
        } catch (failure: Exception) {
            restoreTakenOver(slot, token)
            throw failure
        }
        try {
            lifecycle.rebind(slot.allocation, resumed)
            synchronized(lock) {
                checkOpen()
                check(slots[resumed.tabId] === slot) { "BROWSER_STALE_TAB" }
                sessions.assertCurrent(resumed)
                slot.lease = resumed
                slot.phase = Phase.READY
                if (current == null) current = slot
            }
            return resumed
        } catch (failure: Exception) {
            // A rebind may already have touched the resource. Never retry or reopen it.
            try { sessions.closeTab(resumed) } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    /** A taken-over tab can be closed by the trusted host without exposing a READY window. */
    fun closeTab(token: BrowserToolTakeoverToken) {
        val slot = beginRelease(token)
        val resumed = try {
            sessions.releaseTakeover(token.token)
        } catch (failure: Exception) {
            restoreTakenOver(slot, token)
            throw failure
        }
        synchronized(lock) {
            if (slots[resumed.tabId] === slot) {
                slot.lease = resumed
                slot.phase = Phase.CLOSING
                if (current === slot) selectAfterClose()
            }
        }
        sessions.closeTab(resumed)
    }

    private fun restoreTakenOver(slot: Slot, token: BrowserToolTakeoverToken) = synchronized(lock) {
        if (!closed && slots[token.lease.tabId] === slot && slot.lease == token.lease &&
            slot.phase == Phase.TRANSITION) slot.phase = Phase.TAKEN_OVER
    }

    private fun beginRelease(token: BrowserToolTakeoverToken): Slot = synchronized(lock) {
        checkOpen()
        check(token.session === this) { "BROWSER_TAKEOVER_OWNER" }
        val slot = slots[token.lease.tabId] ?: error("BROWSER_TAB_MISSING")
        check(slot.phase == Phase.TAKEN_OVER && slot.lease == token.lease) { "BROWSER_TAKEOVER_EXPIRED" }
        slot.phase = Phase.TRANSITION
        slot
    }

    /** Invalidates the entire owner, including taken-over and still-creating resources. */
    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            current = null
            slots.clear()
        }
        sessions.close(grant.owner)
    }

    private fun normalizeTabId(value: Any?): Int {
        val id = when (value) {
            is Int -> value
            is Long -> if (value in 0L..2L) value.toInt() else error("BROWSER_TAB_ID")
            is String -> when (value) { "0" -> 0; "1" -> 1; "2" -> 2; else -> error("BROWSER_TAB_ID") }
            else -> error("BROWSER_TAB_ID")
        }
        require(id in 0..2) { "BROWSER_TAB_ID" }
        return id
    }
}
