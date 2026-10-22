package app.daiyu.android.browser.openminis

import app.daiyu.android.browser.bridge.BrowserCommand
import app.daiyu.android.browser.session.BrowserAccess
import app.daiyu.android.browser.session.BrowserGrant
import app.daiyu.android.browser.session.BrowserOwner
import app.daiyu.android.browser.session.BrowserOwnerKind
import app.daiyu.android.browser.session.BrowserSessionRegistry
import app.daiyu.android.browser.runtime.UiDispatcher
import com.openminis.app.browser.BrowserActionInput
import com.openminis.app.browser.BrowserActionResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

class LeaseBrowserHostTest {
    private class Ui : UiDispatcher {
        private var onUi = false
        var rejectPosts = false
        private val queue = ArrayDeque<() -> Unit>()
        override fun isUiThread() = onUi
        override fun post(task: () -> Unit) {
            check(!rejectPosts) { "dispatcher stopped" }
            queue.addLast(task)
        }
        fun <T> now(task: () -> T): T {
            check(!onUi)
            onUi = true
            return try { task() } finally { onUi = false }
        }
        fun next() = now { queue.removeFirst().invoke() }
        fun drain() { while (queue.isNotEmpty()) next() }
    }

    private class Resource : BlankBrowserResource {
        var calls = 0
        var destroyed = 0
        var admissionRevoked = false
        override fun revokeAdmission() { admissionRevoked = true }
        var failDestroy = false
        var idle = true
        override val isIdle: Boolean get() = idle
        val rebound = mutableListOf<app.daiyu.android.browser.session.BrowserTabLease>()
        override fun rebind(lease: app.daiyu.android.browser.session.BrowserTabLease) { rebound.add(lease) }
        val result = CompletableDeferred<BrowserActionResult>()
        override suspend fun execute(input: BrowserActionInput): BrowserActionResult {
            calls++
            return result.await()
        }
        override fun destroy() { destroyed++; if (failDestroy) error("destroy failed") }
    }

    private class Fixture {
        val ui = Ui()
        val sessions = BrowserSessionRegistry()
        val owner = BrowserOwner("adapter-test", BrowserOwnerKind.CHILD)
        val resources = mutableListOf<Resource>()
        val host = LeaseBrowserHost(sessions, ui, Dispatchers.Unconfined, { _, _, _ ->
            Resource().also { resources.add(it) }
        })
        init { sessions.open(BrowserGrant(owner, BrowserAccess.FULL)) }
        fun create() = sessions.newTab(owner).also { lease -> ui.now { host.create(lease) } }
        fun start(lease: app.daiyu.android.browser.session.BrowserTabLease) = ui.now {
            sessions.publish(lease) {
                host.execute(BrowserCommand("get_page_info", lease.tabId), lease)
            }
        }
    }

    @Test fun unsupportedDirectBackendCommandsCannotEnqueueOrRetireTheLoadedResource() {
        val f = Fixture()
        val lease = f.create()
        val commands = listOf(
            BrowserCommand("set_user_agent", lease.tabId, mapOf("user_agent" to "forged")),
            BrowserCommand("set_user_agent", lease.tabId, mapOf("user_agent" to "desktop_chrome", "owner" to "forged")),
            BrowserCommand("set_viewport", lease.tabId, mapOf("viewport_width" to 800)),
            BrowserCommand("set_viewport", lease.tabId, mapOf("reset" to true, "viewport_width" to 800, "viewport_height" to 600)),
            BrowserCommand("reload", lease.tabId, mapOf("owner" to "forged")),
            BrowserCommand("unknown_action", lease.tabId),
            BrowserCommand("navigate", lease.tabId, mapOf("url" to "https://example.invalid/", "timeout_ms" to 1000)),
        )
        try {
            for (command in commands) {
                try { f.ui.now { f.host.execute(command, lease) }; fail("unsupported backend command") }
                catch (_: IllegalArgumentException) { }
                catch (_: IllegalStateException) { }
                f.ui.drain()
                assertEquals(0, f.resources.single().calls)
                assertEquals(0, f.resources.single().destroyed)
                f.sessions.assertCurrent(lease)
            }
            val pending = f.start(lease)
            f.ui.next()
            assertEquals(1, f.resources.single().calls)
            f.resources.single().result.complete(BrowserActionResult("original resource"))
            assertEquals("original resource", pending.join().text)
        } finally {
            f.ui.now { f.host.close(lease) }
            f.ui.drain()
        }
    }

    @Test fun admissionOnlyPostsAndNeverCallsResourceOnSameStack() {
        val f = Fixture()
        val lease = f.create()
        assertEquals(0, f.resources.single().calls)
        val pending = f.start(lease)
        assertEquals(0, f.resources.single().calls)
        assertFalse(pending.isDone)
        f.ui.next()
        assertEquals(1, f.resources.single().calls)
        f.resources.single().result.complete(BrowserActionResult("real result"))
        assertEquals("real result", pending.join().text)
    }

    @Test fun postedActiveExecutionRejectsTakeoverBeforeGenerationChanges() {
        val f = Fixture()
        val lease = f.create()
        val pending = f.start(lease)
        try { f.sessions.takeover(lease); fail("queued command is active") }
        catch (e: IllegalStateException) { assertEquals("BROWSER_TAB_ACTIVE", e.message) }
        f.sessions.assertCurrent(lease)
        f.ui.next()
        assertEquals(1, f.resources.single().calls)
        assertFalse(pending.isDone)
        try { f.sessions.takeover(lease); fail("native command is active") }
        catch (e: IllegalStateException) { assertEquals("BROWSER_TAB_ACTIVE", e.message) }
        f.sessions.assertCurrent(lease)
        f.resources.single().result.complete(BrowserActionResult("done"))
        assertEquals("done", pending.join().text)
        val token = f.sessions.takeover(lease)
        assertNotEquals(lease, token.lease)
        assertEquals(0, f.resources.single().destroyed)
    }

    @Test fun postedNativeStartIsFencedAgainstCloseWithoutCallingResource() {
        val f = Fixture()
        val lease = f.create()
        val pending = f.start(lease)
        f.sessions.closeTab(lease)
        f.ui.drain()
        assertTrue(pending.isCompletedExceptionally)
        assertEquals(0, f.resources.single().calls)
        assertEquals(1, f.resources.single().destroyed)
    }

    @Test fun completionAfterInvalidationCannotCommit() {
        val f = Fixture()
        val lease = f.create()
        val pending = f.start(lease)
        f.ui.next()
        f.sessions.closeTab(lease)
        // Even before posted cancellation runs, the backend must fence completion.
        f.resources.single().result.complete(BrowserActionResult("must not commit"))
        assertTrue(pending.isCompletedExceptionally)
        f.ui.drain()
        assertEquals(1, f.resources.single().destroyed)
    }

    @Test fun cancelAndOldCloseDoNotTouchReplacementIntegerSlot() {
        val f = Fixture()
        val old = f.create()
        f.sessions.closeTab(old)
        val replacement = f.create()
        assertEquals(old.tabId, replacement.tabId)
        assertNotEquals(old, replacement)
        f.host.cancel(old)
        f.host.close(old)
        f.ui.drain()
        assertEquals(1, f.resources[0].destroyed)
        assertEquals(0, f.resources[1].destroyed)
        val pending = f.start(replacement)
        f.ui.next()
        f.resources[1].result.complete(BrowserActionResult("replacement"))
        assertEquals("replacement", pending.join().text)
    }

    @Test fun cancelQuarantinesManagerAndNeverReplays() {
        val f = Fixture()
        val lease = f.create()
        val pending = f.start(lease)
        f.ui.next()
        f.host.cancel(lease)
        f.host.cancel(lease)
        f.ui.drain()
        assertTrue(pending.isCompletedExceptionally)
        assertEquals(1, f.resources.single().calls)
        assertEquals(1, f.resources.single().destroyed)
        try {
            f.start(lease)
            fail("retired manager must not be reused")
        } catch (_: IllegalStateException) { }
    }

    @Test fun failedNativeResultIsNotAConfirmedSuccess() {
        val f = Fixture()
        val lease = f.create()
        val pending = f.start(lease)
        f.ui.next()
        f.resources.single().result.complete(BrowserActionResult.error("native failure"))
        assertTrue(pending.isCompletedExceptionally)
        f.ui.drain()
        assertEquals(1, f.resources.single().destroyed)
    }
    @Test fun cleanupQueueRejectionStillSettlesPendingAndQuarantinesResource() {
        val f = Fixture()
        val lease = f.create()
        val pending = f.start(lease)
        f.ui.next()
        f.ui.rejectPosts = true
        try { f.host.cancel(lease); fail("rejection must be observable") }
        catch (e: IllegalStateException) { assertEquals("dispatcher stopped", e.message) }
        assertTrue(pending.isCompletedExceptionally)
        assertEquals(0, f.resources.single().destroyed)
        assertNotNull(f.host.cleanupFailure(lease))
        f.ui.rejectPosts = false
        f.host.retryCleanup(lease)
        f.ui.drain()
        assertEquals(1, f.resources.single().destroyed)
        f.host.retryCleanup(lease)
        f.ui.drain()
        assertEquals(1, f.resources.single().destroyed)
        try { f.start(lease); fail("quarantined resource must not execute") }
        catch (_: IllegalStateException) { }
    }

    @Test fun factoryFailureDoesNotWaitForCleanupQueueToSettleCreate() {
        val ui = Ui()
        val sessions = BrowserSessionRegistry()
        val owner = BrowserOwner("factory-failure", BrowserOwnerKind.MAIN)
        sessions.open(BrowserGrant(owner, BrowserAccess.FULL))
        val host = LeaseBrowserHost(sessions, ui, Dispatchers.Unconfined, { _, _, _ ->
            error("factory failed")
        })
        val lease = sessions.newTab(owner)
        try { ui.now { host.create(lease) }; fail("factory failure") }
        catch (e: java.util.concurrent.CompletionException) {
            assertEquals("factory failed", e.cause?.message)
        }
        ui.drain()
        sessions.close(owner)
    }

    @Test fun destroyFailureIsObservableWithoutEscapingUiOrReplayingDestroy() {
        val f = Fixture()
        val lease = f.create()
        f.resources.single().failDestroy = true
        val pending = f.start(lease)
        f.ui.next()
        f.host.cancel(lease)
        f.ui.drain()
        assertTrue(pending.isCompletedExceptionally)
        assertEquals("destroy failed", f.host.cleanupFailure(lease)?.message)
        f.host.retryCleanup(lease)
        f.host.close(lease)
        f.ui.drain()
        assertEquals(1, f.resources.single().destroyed)
    }

    @Test fun facadeIdleTakeoverKeepsPageAndRebindsOriginalAllocation() {
        val f = Fixture()
        val facade = app.daiyu.android.browser.host.BrowserToolSession(
            BrowserGrant(f.owner, BrowserAccess.FULL), f.sessions, f.host,
            app.daiyu.android.browser.runtime.BrowserQueuedExecutionPort(f.sessions, f.ui, f.host))
        val opened = f.ui.now { facade.execute(mapOf("action" to "new_tab")) }
        assertTrue(opened is app.daiyu.android.browser.bridge.BrowserOutcome.Confirmed)
        val lease = checkNotNull(facade.currentTab)
        val token = facade.takeover(lease)
        f.ui.now {
            f.host.withTakenOverResource(lease, token.lease) { assertSame(f.resources.single(), it) }
        }
        try { f.start(lease); fail("old generation") } catch (_: IllegalStateException) { }
        val resumed = f.ui.now { facade.releaseTakeover(token) }
        assertEquals(listOf(resumed), f.resources.single().rebound)
        assertEquals(resumed, facade.currentTab)
        f.host.cancel(lease) // delayed old cancellation must not destroy the resumed resource
        f.ui.drain()
        assertEquals(0, f.resources.single().destroyed)
        try {
            f.ui.now { f.host.withTakenOverResource(lease, token.lease) { fail("expired token") } }
            fail("expired token accepted")
        } catch (_: IllegalStateException) { }
        val pending = f.start(resumed)
        f.ui.next()
        f.resources.single().result.complete(BrowserActionResult("still valid"))
        assertEquals("still valid", pending.join().text)
        facade.close()
        f.ui.drain()
        assertEquals(1, f.resources.single().destroyed)
    }

    @Test fun trustedReadRequiresUiAndCurrentWholeLease() {
        val f = Fixture()
        val allocation = f.create()
        try { f.host.withResource(allocation) { it }; fail("off UI read") }
        catch (e: IllegalStateException) { assertEquals("BROWSER_DISPATCH_NOT_UI", e.message) }
        assertSame(f.resources.single(), f.ui.now { f.host.withResource(allocation) { it } })
        val token = f.sessions.takeover(allocation)
        try { f.ui.now { f.host.withResource(token.lease) { it } }; fail("taken-over read") }
        catch (e: IllegalStateException) { assertEquals("BROWSER_TAKEN_OVER", e.message) }
        val resumed = f.sessions.releaseTakeover(token)
        f.ui.now { f.host.rebind(allocation, resumed) }
        try { f.ui.now { f.host.withResource(allocation) { it } }; fail("stale read") }
        catch (_: IllegalStateException) { }
        assertSame(f.resources.single(), f.ui.now { f.host.withResource(resumed) { it } })
        f.sessions.close(f.owner)
        f.ui.drain()
    }

    @Test fun nativeIdleCapabilityRejectsBeforeGenerationChanges() {
        val f = Fixture()
        val lease = f.create()
        f.resources.single().idle = false
        try { f.sessions.takeover(lease); fail("native callback still active") }
        catch (e: IllegalStateException) { assertEquals("BROWSER_OPENMINIS_NATIVE_ACTIVE", e.message) }
        f.sessions.assertCurrent(lease)
        assertEquals(0, f.resources.single().destroyed)
        f.resources.single().idle = true
        val token = f.sessions.takeover(lease)
        f.ui.now { f.host.withTakenOverResource(lease, token.lease) { assertSame(f.resources.single(), it) } }
        f.sessions.close(f.owner)
        f.ui.drain()
        assertEquals(1, f.resources.single().destroyed)
    }

    @Test fun releasedLeaseCannotExecuteBeforeRebindAndCloseSurvivesGenerationChanges() {
        val f = Fixture()
        val allocation = f.create()
        val token = f.sessions.takeover(allocation)
        val resumed = f.sessions.releaseTakeover(token)
        try { f.start(resumed); fail("unbound resource") }
        catch (e: IllegalStateException) { assertEquals("BROWSER_OPENMINIS_RESOURCE_RETIRED", e.message) }
        f.ui.now { f.host.rebind(allocation, resumed) }
        val pending = f.start(resumed)
        f.ui.next()
        f.sessions.closeTab(resumed)
        f.resources.single().result.complete(BrowserActionResult("late after resumed close"))
        assertTrue(pending.isCompletedExceptionally)
        f.ui.drain()
        assertEquals(1, f.resources.single().calls)
        assertEquals(1, f.resources.single().destroyed)
    }

    @Test fun synchronousCloseFromNativePrefixSettlesWithoutWaitingOrReplaying() {
        val ui = Ui()
        val sessions = BrowserSessionRegistry()
        val owner = BrowserOwner("sync-close", BrowserOwnerKind.MAIN)
        sessions.open(BrowserGrant(owner, BrowserAccess.FULL))
        var calls = 0
        var destroyed = 0
        val host = LeaseBrowserHost(sessions, ui, Dispatchers.Unconfined, { _, _, _ ->
            object : BlankBrowserResource {
                override suspend fun execute(input: BrowserActionInput): BrowserActionResult {
                    calls++
                    sessions.close(owner)
                    return BrowserActionResult("late")
                }
                override fun destroy() { destroyed++ }
            }
        })
        val lease = sessions.newTab(owner)
        ui.now { host.create(lease) }
        val pending = ui.now { sessions.publish(lease) { host.execute(BrowserCommand("get_page_info", lease.tabId), lease) } }
        ui.next()
        assertTrue(pending.isCompletedExceptionally)
        ui.drain()
        assertEquals(1, calls)
        assertEquals(1, destroyed)
    }

    @Test fun cancelledLeaseCannotBeAllocatedAgainAfterSuccessfulCleanup() {
        val f = Fixture()
        val lease = f.create()
        f.host.cancel(lease)
        f.ui.drain()
        assertEquals(1, f.resources.single().destroyed)
        try { f.ui.now { f.host.create(lease) }; fail("cancelled lease allocation") }
        catch (e: IllegalStateException) { assertEquals("BROWSER_OPENMINIS_RESOURCE_EXISTS", e.message) }
        assertEquals(1, f.resources.size)
        f.host.close(lease)
        f.ui.drain()
        assertEquals(1, f.resources.single().destroyed)
    }

    @Test fun invalidationDuringFactoryDestroysTheLateUnattachedResource() {
        val ui = Ui()
        val sessions = BrowserSessionRegistry()
        val owner = BrowserOwner("late-factory", BrowserOwnerKind.MAIN)
        sessions.open(BrowserGrant(owner, BrowserAccess.FULL))
        val resource = Resource()
        val host = LeaseBrowserHost(sessions, ui, Dispatchers.Unconfined, { _, _, _ ->
            sessions.close(owner)
            resource
        })
        val lease = sessions.newTab(owner)
        try { ui.now { host.create(lease) }; fail("invalidated create") }
        catch (_: Exception) { }
        ui.drain()
        assertEquals(1, resource.destroyed)
    }

    @Test fun lateUnattachedDestroyFailureSurvivesSuccessfulQueuedCleanup() {
        val ui = Ui()
        val sessions = BrowserSessionRegistry()
        val owner = BrowserOwner("late-factory-failure", BrowserOwnerKind.MAIN)
        sessions.open(BrowserGrant(owner, BrowserAccess.FULL))
        val resource = Resource().also { it.failDestroy = true }
        val host = LeaseBrowserHost(sessions, ui, Dispatchers.Unconfined, { _, _, _ ->
            sessions.close(owner)
            resource
        })
        val lease = sessions.newTab(owner)
        try { ui.now { host.create(lease) }; fail("invalidated create") }
        catch (_: Exception) { }
        ui.drain()
        assertEquals(1, resource.destroyed)
        assertEquals("destroy failed", host.cleanupFailure(lease)?.message)
        host.retryCleanup(lease)
        host.close(lease)
        ui.drain()
        assertEquals(1, resource.destroyed)
        assertEquals("destroy failed", host.cleanupFailure(lease)?.message)
    }

    @Test fun admittedCloseCannotReportDrainedBeforeIdleSlotRetirement() {
        val f = Fixture()
        f.create()
        // Reproduce exactly the interleaving after close seals admission but before retire runs.
        LeaseBrowserHost::class.java.getDeclaredField("closed").apply {
            isAccessible = true
            setBoolean(f.host, true)
        }
        try {
            val between = f.host.closeStatus()
            assertTrue(between.closeRequested)
            assertTrue("WEBVIEW_CLEANUP" in between.pending)
            assertFalse(between.drained)
            assertFalse(between.successful)
            assertEquals(0, f.resources.single().destroyed)
        } finally {
            f.host.closeWithResult()
            f.ui.drain()
        }
        assertTrue(f.host.closeStatus().successful)
        assertEquals(1, f.resources.single().destroyed)
    }

    @Test fun closeReceiptDoesNotConfuseSettledCommandWithExecutedCleanup() {
        val f = Fixture()
        val lease = f.create()
        val pending = f.start(lease)
        f.ui.next()
        val closing = f.host.closeWithResult()
        assertTrue(pending.isDone)
        assertFalse(closing.drained)
        assertTrue("WEBVIEW_CLEANUP" in closing.pending)
        assertEquals(0, f.resources.single().destroyed)
        f.ui.drain()
        assertTrue(f.host.closeStatus().successful)
        f.host.closeWithResult()
        f.ui.drain()
        assertEquals(1, f.resources.single().destroyed)
        try { f.ui.now { f.host.create(lease) }; fail("closed host cannot allocate") }
        catch (_: IllegalStateException) { }
    }

    @Test fun factoryFrameRemainsPendingEvenAfterEarlyCreatedFutureAndCleanupSettle() {
        val ui = Ui()
        val sessions = BrowserSessionRegistry()
        val owner = BrowserOwner("factory-receipt", BrowserOwnerKind.MAIN)
        sessions.open(BrowserGrant(owner, BrowserAccess.FULL))
        lateinit var host: LeaseBrowserHost
        val resource = Resource()
        host = LeaseBrowserHost(sessions, ui, Dispatchers.Unconfined, { _, _, _ ->
            val during = host.closeWithResult()
            assertTrue("WEBVIEW_CREATION" in during.pending)
            assertFalse(during.drained)
            resource
        })
        try { ui.now { host.create(sessions.newTab(owner)) }; fail("closed during factory") }
        catch (_: java.util.concurrent.CompletionException) { }
        assertEquals(1, resource.destroyed)
        assertFalse(host.closeStatus().drained) // queued subscription cleanup has not run
        ui.drain()
        assertTrue(host.closeStatus().successful)
    }

    @Test fun partialInitializationIsDestroyedAndFailureCannotBeReplayedByCloseReceipt() {
        val ui = Ui()
        val sessions = BrowserSessionRegistry()
        val owner = BrowserOwner("partial-receipt", BrowserOwnerKind.MAIN)
        sessions.open(BrowserGrant(owner, BrowserAccess.FULL))
        var destroys = 0
        val host = LeaseBrowserHost(sessions, ui, Dispatchers.Unconfined, { _, _, _ ->
            object : BlankBrowserResource {
                override fun initialize() { error("layout failed after native allocation") }
                override suspend fun execute(input: BrowserActionInput) = BrowserActionResult("unused")
                override fun destroy() { destroys++; error("native destroy failed") }
            }
        })
        try { ui.now { host.create(sessions.newTab(owner)) }; fail("initialization") }
        catch (_: java.util.concurrent.CompletionException) { }
        host.closeWithResult()
        ui.drain()
        val result = host.closeStatus()
        assertTrue(result.drained)
        assertFalse(result.successful)
        assertEquals(setOf("WEBVIEW_DESTROY"), result.failures)
        host.closeWithResult(); ui.drain()
        assertEquals(1, destroys)
    }

    @Test fun rejectedCloseQueueRemainsPendingUntilExplicitRetryAndNeverReplaysDestroy() {
        val f = Fixture()
        f.create()
        f.ui.rejectPosts = true
        val blocked = f.host.closeWithResult()
        assertFalse(blocked.drained)
        assertTrue("WEBVIEW_CLEANUP_QUEUE" in blocked.failures)
        f.ui.rejectPosts = false
        f.host.closeWithResult(); f.ui.drain()
        assertTrue(f.host.closeStatus().successful)
        assertEquals(1, f.resources.single().destroyed)
    }
    @Test fun takenOverResourceAdmissionIsRevokedBeforeQueuedNativeCleanup() {
        val f = Fixture()
        val lease = f.create()
        val token = f.sessions.takeover(lease)
        val resource = f.resources.single()
        f.ui.now { f.host.withTakenOverResource(lease, token.lease) { assertSame(resource, it) } }
        f.host.closeWithResult()
        assertTrue(resource.admissionRevoked)
        assertEquals(0, resource.destroyed)
        // A still-valid registry token cannot resurrect an already-retired allocation.
        f.sessions.publishTakenOver(token.lease) { }
        try {
            f.ui.now { f.host.withTakenOverResource(lease, token.lease) { fail("retired allocation") } }
            fail("expected rejection")
        } catch (_: IllegalStateException) { }
        f.ui.drain()
        assertEquals(1, resource.destroyed)
        assertTrue(f.host.closeStatus().successful)
    }

}
