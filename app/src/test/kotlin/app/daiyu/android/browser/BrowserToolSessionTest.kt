package app.daiyu.android.browser

import app.daiyu.android.browser.bridge.BrowserCommand
import app.daiyu.android.browser.bridge.BrowserExecutionPort
import app.daiyu.android.browser.bridge.BrowserExecutionResult
import app.daiyu.android.browser.bridge.BrowserOutcome
import app.daiyu.android.browser.host.BrowserTabLifecycle
import app.daiyu.android.browser.host.BrowserToolSession
import app.daiyu.android.browser.session.BrowserAccess
import app.daiyu.android.browser.session.BrowserGrant
import app.daiyu.android.browser.session.BrowserOwner
import app.daiyu.android.browser.session.BrowserOwnerKind
import app.daiyu.android.browser.session.BrowserSessionRegistry
import app.daiyu.android.browser.session.BrowserTabLease
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class BrowserToolSessionTest {
    private val main = BrowserOwner("conversation", BrowserOwnerKind.MAIN)
    private val child = BrowserOwner("conversation", BrowserOwnerKind.CHILD)

    private class Resources : BrowserTabLifecycle {
        val live = mutableSetOf<BrowserTabLease>()
        val created = mutableListOf<BrowserTabLease>()
        val closed = mutableListOf<BrowserTabLease>()
        var failCreate = false
        var failClose = false
        var failRelease = false
        override fun beforeReleaseTakeover(allocation: BrowserTabLease, lease: BrowserTabLease) {
            if (failRelease) error("visible attachment still live")
        }
        var failRebind = false
        val rebound = mutableListOf<Pair<BrowserTabLease, BrowserTabLease>>()
        override fun rebind(allocation: BrowserTabLease, lease: BrowserTabLease) {
            rebound.add(allocation to lease)
            if (failRebind) error("rebind uncertain")
        }
        var onCreate: (BrowserTabLease) -> Unit = {}
        override fun create(lease: BrowserTabLease) {
            assertTrue(live.add(lease))
            created.add(lease)
            onCreate(lease)
            if (failCreate) error("create failed after allocation")
        }
        override fun close(lease: BrowserTabLease) {
            closed.add(lease)
            live.remove(lease)
            if (failClose) error("cleanup failed after enqueue")
        }
    }

    private class Port : BrowserExecutionPort {
        val calls = mutableListOf<Pair<BrowserCommand, BrowserTabLease>>()
        var perform: (BrowserCommand, BrowserTabLease) -> BrowserExecutionResult = { _, _ -> BrowserExecutionResult("ok") }
        override fun execute(command: BrowserCommand, lease: BrowserTabLease): BrowserExecutionResult {
            calls.add(command to lease)
            return perform(command, lease)
        }
    }

    private class Fixture(
        owner: BrowserOwner,
        access: BrowserAccess = BrowserAccess.FULL,
        val registry: BrowserSessionRegistry = BrowserSessionRegistry(),
        val resources: Resources = Resources(),
        val port: Port = Port(),
    ) {
        val session = BrowserToolSession(BrowserGrant(owner, access), registry, resources, port)
    }

    private fun call(session: BrowserToolSession, action: String, vararg arguments: Pair<String, Any?>): BrowserOutcome =
        session.execute(mapOf("action" to action, *arguments))

    private fun confirmed(outcome: BrowserOutcome): String {
        assertTrue("expected Confirmed, got $outcome", outcome is BrowserOutcome.Confirmed)
        return (outcome as BrowserOutcome.Confirmed).result.text
    }

    private fun rejected(outcome: BrowserOutcome, code: String? = null) {
        assertTrue("expected NotExecuted, got $outcome", outcome is BrowserOutcome.NotExecuted)
        if (code != null) assertEquals(code, (outcome as BrowserOutcome.NotExecuted).code)
    }

    private fun throwsState(block: () -> Unit) {
        try { block(); fail("expected rejection") }
        catch (_: IllegalStateException) { }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun closeBeforeFacadeCreationRejectsWithoutHostSideEffects() {
        for (owner in listOf(main, child)) {
            val released = mutableListOf<BrowserTabLease>()
            val registry = BrowserSessionRegistry { released.add(it) }
            repeat(2) { registry.close(owner) }
            val resources = Resources()
            val port = Port()
            var preflights = 0
            for (access in listOf(BrowserAccess.READ_ONLY, BrowserAccess.FULL)) {
                try {
                    BrowserToolSession(BrowserGrant(owner, access), registry, resources, port) { preflights++ }
                    fail("facade opened after owner close")
                } catch (e: IllegalStateException) {
                    assertEquals("BROWSER_SESSION_CLOSED", e.message)
                }
            }
            assertTrue(resources.created.isEmpty())
            assertTrue(resources.closed.isEmpty())
            assertTrue(resources.rebound.isEmpty())
            assertTrue(resources.live.isEmpty())
            assertTrue(port.calls.isEmpty())
            assertEquals(0, preflights)
            assertTrue(released.isEmpty())
            val other = Fixture(if (owner == main) child else main, registry = registry)
            confirmed(call(other.session, "new_tab"))
            val lease = other.session.currentTab!!
            repeat(2) { registry.close(owner) }
            confirmed(call(other.session, "get_text"))
            assertEquals(1, other.port.calls.size)
            repeat(2) { other.session.close() }
            assertEquals(listOf(lease), other.resources.closed)
            assertEquals(listOf(lease), released)
        }
    }

    @Test fun startsEmptyAndDefaultTabBelongsToThisSession() {
        val f = Fixture(main)
        assertNull(f.session.currentTab)
        rejected(call(f.session, "get_text"), "BROWSER_CURRENT_TAB_MISSING")
        assertEquals("{\"tabs\":[],\"currentTabId\":null}", confirmed(call(f.session, "list_tabs")))
        assertTrue(confirmed(call(f.session, "new_tab")).contains("\"tabId\":0"))
        confirmed(call(f.session, "get_text"))
        assertEquals(0, f.port.calls.single().first.tabId)
        assertEquals(f.session.currentTab, f.port.calls.single().second)
        assertEquals(main, f.session.currentTab!!.owner)
        f.session.close()
    }

    @Test fun eachOwnerGetsExactlyZeroOneTwoWithIndependentCurrentAndCleanup() {
        val registry = BrowserSessionRegistry()
        val a = Fixture(main, registry = registry)
        val b = Fixture(child, registry = registry)
        repeat(3) { confirmed(call(a.session, "new_tab")); confirmed(call(b.session, "new_tab")) }
        assertEquals(listOf(0, 1, 2), a.resources.created.map { it.tabId })
        assertEquals(listOf(0, 1, 2), b.resources.created.map { it.tabId })
        rejected(call(a.session, "new_tab"), "BROWSER_TAB_LIMIT")
        rejected(call(b.session, "new_tab"), "BROWSER_TAB_LIMIT")
        confirmed(call(a.session, "get_text", "tabId" to 0))
        assertEquals(0, a.session.currentTab!!.tabId)
        assertEquals(2, b.session.currentTab!!.tabId)
        throwsState { a.session.closeTab(b.session.currentTab!!) }
        rejected(call(a.session, "get_text", "owner" to child))
        rejected(call(a.session, "new_tab", "owner" to child))
        a.session.close()
        assertTrue(a.resources.live.isEmpty())
        assertEquals(3, b.resources.live.size)
        confirmed(call(b.session, "get_text"))
        assertEquals(child, b.port.calls.last().second.owner)
        b.session.close()
    }

    @Test fun disabledNeverOpensSessionOrAllocatesResources() {
        val r = BrowserSessionRegistry()
        val resources = Resources()
        throwsState { BrowserToolSession(BrowserGrant(main, BrowserAccess.DISABLED), r, resources, Port()) }
        throwsState { r.listTabs(main) }
        assertTrue(resources.created.isEmpty())
        assertTrue(resources.closed.isEmpty())
    }

    @Test fun frozenReadOnlyGrantCannotBeUpgradedByReopenOrArguments() {
        val f = Fixture(main, BrowserAccess.READ_ONLY)
        throwsState { BrowserToolSession(BrowserGrant(main, BrowserAccess.FULL), f.registry, f.resources, f.port) }
        for (options in listOf(
            arrayOf("url" to "https://example.com"),
            arrayOf("script" to "location.href='https://example.com'"),
            arrayOf("access" to "FULL"),
            arrayOf("cookies" to "x=y"),
        )) rejected(call(f.session, "new_tab", *options))
        assertTrue(f.resources.created.isEmpty())
        confirmed(call(f.session, "new_tab")) // Explicitly blank, never a navigation shortcut.
        confirmed(call(f.session, "get_text"))
        for ((action, args) in listOf(
            "navigate" to arrayOf("url" to "https://example.com"),
            "execute_js" to arrayOf("script" to "1"),
            "set_cookies" to arrayOf("cookies" to "x=y"),
            "reload" to emptyArray(),
            "go_back" to emptyArray(),
        )) rejected(call(f.session, action, *args), "BROWSER_READ_ONLY")
        assertEquals(1, f.port.calls.size)
        confirmed(call(f.session, "close_tab"))
        assertNull(f.session.currentTab)
        f.session.close()
        throwsState { BrowserToolSession(f.session.grant, f.registry, f.resources, f.port) }
    }

    @Test fun tabManagementRejectsAllUnsupportedOptionsAndMalformedIds() {
        val f = Fixture(main)
        for (id in listOf<Any?>(null, -1, 3, 3L, Long.MAX_VALUE, 0.0, true, "00", " 0", "0.0", "3", listOf(0))) {
            rejected(call(f.session, "close_tab", "tabId" to id), "BROWSER_TAB_ID")
        }
        for (action in listOf("new_tab", "list_tabs")) {
            rejected(call(f.session, action, "tabId" to 0), "BROWSER_TAB_ARGUMENTS")
            rejected(call(f.session, action, "url" to "https://example.com"), "BROWSER_TAB_ARGUMENTS")
        }
        confirmed(call(f.session, "new_tab"))
        rejected(call(f.session, "close_tab", "force" to true), "BROWSER_TAB_ARGUMENTS")
        rejected(call(f.session, "get_text", "owner" to "another"))
        rejected(call(f.session, "not_supported"))
        rejected(f.session.execute(mapOf("action" to listOf("new_tab", "navigate"))))
        rejected(f.session.execute(emptyMap()))
        assertEquals(1, f.resources.live.size)
        assertTrue(f.port.calls.isEmpty())
        for (id in listOf<Any>(0, 0L, "0")) {
            confirmed(call(f.session, "get_text", "tabId" to id, "offset" to 0L))
            assertEquals(0, f.port.calls.last().first.tabId)
            assertEquals(0, f.port.calls.last().first.arguments["offset"])
        }
        f.session.close()
    }

    @Test fun closeCurrentChoosesSmallestReadyAndDoesNotOpenAnotherTab() {
        val f = Fixture(main)
        repeat(3) { confirmed(call(f.session, "new_tab")) }
        confirmed(call(f.session, "close_tab", "tabId" to "1"))
        assertEquals(2, f.session.currentTab!!.tabId)
        confirmed(call(f.session, "close_tab"))
        assertEquals(0, f.session.currentTab!!.tabId)
        confirmed(call(f.session, "get_text"))
        assertEquals(0, f.port.calls.single().second.tabId)
        confirmed(call(f.session, "close_tab"))
        assertNull(f.session.currentTab)
        rejected(call(f.session, "close_tab"), "BROWSER_CURRENT_TAB_MISSING")
        assertEquals(3, f.resources.created.size)
        assertEquals(3, f.resources.closed.size)
        f.session.close()
        f.session.close()
        assertEquals(3, f.resources.closed.size)
        rejected(call(f.session, "new_tab"), "BROWSER_SESSION_CLOSED")
    }

    @Test fun partialCreateFailureRollsBackSlotAndCleansExactlyThatGeneration() {
        val f = Fixture(main)
        f.resources.failCreate = true
        assertEquals(BrowserOutcome.Unknown, call(f.session, "new_tab"))
        val failed = f.resources.created.single()
        assertEquals(listOf(failed), f.resources.closed)
        assertTrue(f.resources.live.isEmpty())
        assertTrue(f.registry.listTabs(main).isEmpty())
        assertNull(f.session.currentTab)
        assertEquals(1, f.resources.created.size) // No replay of unknown open.
        f.resources.failCreate = false
        confirmed(call(f.session, "new_tab"))
        val fresh = f.session.currentTab!!
        assertEquals(failed.tabId, fresh.tabId)
        assertNotEquals(failed.tabGeneration, fresh.tabGeneration)
        throwsState { f.session.closeTab(failed) }
        assertEquals(setOf(fresh), f.resources.live)
        f.session.close()
        assertEquals(listOf(failed, fresh), f.resources.closed)
    }

    @Test fun cleanupFailureDoesNotReplayCloseOrPreventSlotReuse() {
        val f = Fixture(main)
        confirmed(call(f.session, "new_tab"))
        val old = f.session.currentTab!!
        f.resources.failClose = true
        assertEquals(BrowserOutcome.Unknown, call(f.session, "close_tab"))
        assertEquals(listOf(old), f.resources.closed)
        assertTrue(f.registry.listTabs(main).isEmpty())
        rejected(call(f.session, "close_tab"))
        f.resources.failClose = false
        confirmed(call(f.session, "new_tab"))
        val fresh = f.session.currentTab!!
        throwsState { f.session.closeTab(old) }
        assertEquals(setOf(fresh), f.resources.live)
        f.session.close()
        assertEquals(listOf(old, fresh), f.resources.closed)
    }

    @Test fun takeoverAndReleaseSynchronizeCurrentLeaseAndRejectOldTokens() {
        val f = Fixture(main)
        confirmed(call(f.session, "new_tab"))
        val allocation = f.session.currentTab!!
        val token = f.session.takeover(allocation)
        assertEquals(token.lease, f.session.currentTab)
        assertNotEquals(allocation.tabGeneration, token.lease.tabGeneration)
        rejected(call(f.session, "get_text"), "BROWSER_TAKEN_OVER")
        rejected(call(f.session, "close_tab"), "BROWSER_TAKEN_OVER")
        assertTrue(confirmed(call(f.session, "list_tabs")).contains("\"takenOver\":true"))
        val b = Fixture(child)
        throwsState { b.session.releaseTakeover(token) }
        val resumed = f.session.releaseTakeover(token)
        assertEquals(listOf(allocation to resumed), f.resources.rebound)
        assertEquals(resumed, f.session.currentTab)
        assertNotEquals(token.lease.tabGeneration, resumed.tabGeneration)
        throwsState { f.session.releaseTakeover(token) }
        throwsState { f.session.closeTab(allocation) }
        confirmed(call(f.session, "get_text"))
        assertEquals(resumed, f.port.calls.single().second)
        f.session.closeTab(resumed)
        // Resource identity is the allocation lease, NOT an integer or takeover generation.
        assertEquals(listOf(allocation), f.resources.closed)
        f.session.close()
        b.session.close()
    }

    @Test fun uncertainRebindClosesAllocationAndNeverRetriesOrReopens() {
        val f = Fixture(main)
        confirmed(call(f.session, "new_tab"))
        val allocation = f.session.currentTab!!
        val token = f.session.takeover(allocation)
        f.resources.failRebind = true
        throwsState { f.session.releaseTakeover(token) }
        assertEquals(1, f.resources.rebound.size)
        assertEquals(listOf(allocation), f.resources.closed)
        assertNull(f.session.currentTab)
        throwsState { f.session.releaseTakeover(token) }
        assertEquals(1, f.resources.created.size)
        assertEquals(1, f.resources.rebound.size)
        f.session.close()
    }

    @Test fun hostCanCloseTakenOverTabAndSessionWithoutLeavingReadyWindow() {
        val f = Fixture(main)
        confirmed(call(f.session, "new_tab"))
        val a = f.session.currentTab!!
        val token = f.session.takeover(a)
        confirmed(call(f.session, "new_tab"))
        val b = f.session.currentTab!!
        f.session.closeTab(token)
        assertEquals(b, f.session.currentTab)
        assertEquals(listOf(a), f.resources.closed)
        throwsState { f.session.releaseTakeover(token) }
        val tokenB = f.session.takeover(b)
        f.session.close()
        assertEquals(listOf(a, b), f.resources.closed)
        assertNull(f.session.currentTab)
        throwsState { f.session.releaseTakeover(tokenB) }
    }

    @Test fun resumeDoesNotStealCurrentFromAnotherTabAndCloseSkipsTakenOverTabs() {
        val f = Fixture(main)
        confirmed(call(f.session, "new_tab"))
        val a = f.session.currentTab!!
        val tokenA = f.session.takeover(a)
        confirmed(call(f.session, "new_tab"))
        val b = f.session.currentTab!!
        val resumedA = f.session.releaseTakeover(tokenA)
        assertEquals(b, f.session.currentTab)
        val secondTokenA = f.session.takeover(resumedA)
        confirmed(call(f.session, "close_tab"))
        assertNull(f.session.currentTab) // The remaining tab is not READY.
        rejected(call(f.session, "get_text"), "BROWSER_CURRENT_TAB_MISSING")
        val nextA = f.session.releaseTakeover(secondTokenA)
        assertEquals(nextA, f.session.currentTab)
        confirmed(call(f.session, "get_text"))
        assertEquals(nextA, f.port.calls.single().second)
        f.session.close()
    }

    @Test fun rejectedOrdinaryArgumentsDoNotChangeCurrentOrReachPort() {
        val f = Fixture(main)
        repeat(2) { confirmed(call(f.session, "new_tab")) }
        val selected = f.session.currentTab
        rejected(call(f.session, "navigate", "tabId" to 0, "url" to "file:///not-allowed"))
        rejected(call(f.session, "get_text", "tabId" to 0, "offset" to "0"))
        rejected(call(f.session, "unknown_action", "tabId" to 0))
        assertEquals(selected, f.session.currentTab)
        assertTrue(f.port.calls.isEmpty())
        f.session.close()
    }

    @Test fun takeoverCallbackFailureStillExposesRecoveryToken() {
        val f = Fixture(main)
        confirmed(call(f.session, "new_tab"))
        f.registry.onInvalidated(f.session.currentTab!!) { error("invalidation failed") }
        val token = f.session.takeover(f.session.currentTab!!)
        assertNotNull(token.cleanupFailure)
        assertEquals(token.lease, f.session.currentTab)
        val resumed = f.session.releaseTakeover(token)
        assertEquals(resumed, f.session.currentTab)
        f.session.close()
    }

    @Test fun lateBridgeResultsAfterCloseReuseOrTakeoverAreUnknownNeverReplayed() {
        for (change in listOf("reuse", "session_close")) {
            val f = Fixture(main)
            confirmed(call(f.session, "new_tab"))
            val old = f.session.currentTab!!
            f.port.perform = { _, lease ->
                when (change) {
                    "reuse" -> { f.session.closeTab(lease); confirmed(call(f.session, "new_tab")) }
                    else -> f.session.close()
                }
                BrowserExecutionResult("late")
            }
            assertEquals(BrowserOutcome.Unknown, call(f.session, "get_text"))
            assertEquals(1, f.port.calls.size)
            if (change != "session_close") assertNotEquals(old, f.session.currentTab)
            f.session.close()
            assertTrue(f.resources.live.isEmpty())
        }
    }

    @Test fun activePortRejectsReentrantTakeoverWithoutChangingGeneration() {
        val f = Fixture(main)
        confirmed(call(f.session, "new_tab"))
        val lease = f.session.currentTab!!
        f.port.perform = { _, active ->
            try { f.session.takeover(active); fail("active takeover") }
            catch (e: IllegalStateException) { assertEquals("BROWSER_TAB_ACTIVE", e.message) }
            assertEquals(lease, f.session.currentTab)
            f.registry.assertCurrent(lease)
            BrowserExecutionResult("not replayed")
        }
        assertEquals("not replayed", confirmed(call(f.session, "get_text")))
        assertEquals(1, f.port.calls.size)
        val token = f.session.takeover(lease)
        f.session.releaseTakeover(token)
        f.session.close()
    }

    @Test fun reentrantCloseInsideNativeFenceNeverWaitsForFacadeMonitor() {
        val f = Fixture(main)
        confirmed(call(f.session, "new_tab"))
        f.port.perform = { _, lease ->
            f.registry.startNative(lease, "get_text") { f.session.close() }
            BrowserExecutionResult("late")
        }
        assertEquals(BrowserOutcome.Unknown, call(f.session, "get_text"))
        assertEquals(1, f.resources.closed.size)
        assertNull(f.session.currentTab)
    }

    @Test fun unknownPortFailureIsNotRetriedAndOneActionNeverNavigatesOnOpen() {
        val f = Fixture(main)
        confirmed(call(f.session, "new_tab"))
        assertTrue(f.port.calls.isEmpty())
        f.port.perform = { _, _ -> error("may already have executed") }
        assertEquals(BrowserOutcome.Unknown, call(f.session, "navigate", "url" to "https://example.com"))
        assertEquals(1, f.port.calls.size)
        assertEquals("navigate", f.port.calls.single().first.action)
        assertEquals(1, f.resources.created.size)
        f.session.close()
    }

    @Test fun sessionCloseDuringCreateDefersExactCleanupUntilCreateCompletes() {
        val f = Fixture(main)
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val outcome = AtomicReference<BrowserOutcome>()
        f.resources.onCreate = { entered.countDown(); check(finish.await(3, TimeUnit.SECONDS)) }
        val worker = thread { outcome.set(call(f.session, "new_tab")) }
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        try {
            f.session.close()
            assertNull(f.session.currentTab)
            assertTrue(f.resources.closed.isEmpty()) // Never close before partial create returns.
            rejected(call(f.session, "new_tab"), "BROWSER_SESSION_CLOSED")
        } finally { finish.countDown(); worker.join(3000) }
        assertFalse(worker.isAlive)
        assertEquals(BrowserOutcome.Unknown, outcome.get())
        assertEquals(f.resources.created, f.resources.closed)
        assertTrue(f.resources.live.isEmpty())
        assertEquals(1, f.resources.created.size)
    }

    @Test fun oneOwnerCleanupFailureStillDrainsEveryResourceAndInvalidatesSession() {
        val f = Fixture(main)
        repeat(3) { confirmed(call(f.session, "new_tab")) }
        f.resources.failClose = true
        throwsState { f.session.close() }
        assertEquals(3, f.resources.closed.size)
        assertTrue(f.resources.live.isEmpty())
        assertNull(f.session.currentTab)
        rejected(call(f.session, "list_tabs"), "BROWSER_SESSION_CLOSED")
        f.session.close()
        assertEquals(3, f.resources.closed.size)
    }
    @Test fun rejectedVisibleReleaseKeepsTokenForExplicitCloseWithoutReadyWindow() {
        val f = Fixture(main)
        confirmed(call(f.session, "new_tab"))
        val allocation = requireNotNull(f.session.currentTab)
        val token = f.session.takeover(allocation)
        f.resources.failRelease = true
        throwsState { f.session.releaseTakeover(token) }
        assertEquals(token.lease, f.session.currentTab)
        val original = f.session.withTakenOverTab(token) { first, second ->
            assertEquals(token.lease, second)
            first
        }
        assertEquals(allocation, original)
        rejected(call(f.session, "get_page_info"))
        f.session.closeTab(token)
        assertNull(f.session.currentTab)
        assertEquals(listOf(allocation), f.resources.closed)
        throwsState { f.session.withTakenOverTab(token) { _, _ -> fail("stale token") } }
        f.session.close()
    }

    @Test fun visibleCapabilityRejectsOldAndForeignTokensAfterOriginalRelease() {
        val f = Fixture(main)
        val foreign = Fixture(child)
        confirmed(call(f.session, "new_tab"))
        val token = f.session.takeover(requireNotNull(f.session.currentTab))
        throwsState { foreign.session.withTakenOverTab(token) { _, _ -> fail("foreign token") } }
        val resumed = f.session.releaseTakeover(token)
        assertNotEquals(token.lease, resumed)
        throwsState { f.session.withTakenOverTab(token) { _, _ -> fail("consumed token") } }
        f.session.close()
        foreign.session.close()
    }

}
