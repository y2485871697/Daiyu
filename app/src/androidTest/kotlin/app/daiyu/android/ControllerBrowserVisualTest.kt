package app.daiyu.android

import android.test.InstrumentationTestCase
import app.daiyu.android.assistants.AssistantProfile
import app.daiyu.android.browser.*
import app.daiyu.android.browser.bridge.*
import app.daiyu.android.browser.host.*
import app.daiyu.android.browser.network.BrowserCloseResult
import app.daiyu.android.browser.session.*
import app.daiyu.android.domain.*
import app.daiyu.android.integration.tools.CatalogBrowserBinding
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

@Suppress("DEPRECATION")
class ControllerBrowserVisualTest : InstrumentationTestCase() {
    private fun <T> main(block: () -> T): T {
        val task = FutureTask(block)
        instrumentation.runOnMainSync(task)
        return task.get(10, TimeUnit.SECONDS)
    }
    private class Fixture : AutoCloseable {
        val identity = BrowserRunIdentity(ConversationId("visual-conversation"), RunId("visual-run"), "profile", MessageId("visual-message"))
        val grant = BrowserGrant(BrowserOwner(identity.runId.value, BrowserOwnerKind.MAIN), BrowserAccess.FULL)
        val registry = BrowserSessionRegistry()
        var allocations = 0
        var calls = 0
        var paused = false
        var pauses = 0
        var resumes = 0
        var live = true
        val session = BrowserToolSession(grant, registry, object : BrowserTabLifecycle {
            override fun create(lease: BrowserTabLease) { allocations++ }
            override fun close(lease: BrowserTabLease) { }
        }, object : BrowserExecutionPort {
            override fun execute(command: BrowserCommand, lease: BrowserTabLease): BrowserExecutionResult {
                calls++
                return BrowserExecutionResult("ok")
            }
        })
        val preparation = BrowserRunPreparation.prepare(identity, BrowserSettings(BrowserAccess.FULL), grant,
            setOf("new_tab", "list_tabs"), listOf("""{"action":"new_tab"}"""))
        val binding = CatalogBrowserBinding.create(AssistantProfile(identity.assistantId, "Test", ""),
            preparation.confirm(preparation.preview)) {
            object : CatalogBrowserBinding.Host {
                override val session get() = this@Fixture.session
                override fun closeWithResult(): BrowserCloseResult { session.close(); return BrowserCloseResult(true) }
                override fun closeStatus() = BrowserCloseResult(false)
            }
        }
        val owner = Message(identity.assistantMessageId, identity.conversationId, identity.runId,
            Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)
        val controller = ControllerBrowserVisual(owner, binding, { live }, { pauses++; true },
            { paused }, { resumes++; true }, { live = false; true })
        init { check(session.execute(mapOf("action" to "new_tab")) is BrowserOutcome.Confirmed) }
        override fun close() { controller.revoke(); binding.close() }
    }

    fun testTabSnapshotListsOnlyOriginalSessionWithoutOpeningOrExecuting() = main {
        val f = Fixture()
        try {
            val visit = requireNotNull(f.controller.openVisit())
            assertEquals(listOf(f.session.currentTab), visit.snapshot().tabs)
            assertNull(visit.snapshot().page)
            repeat(3) { visit.snapshot() }
            assertEquals(1, f.allocations)
            assertEquals(0, f.calls)
            assertEquals(0, f.pauses)
            visit.close()
            assertTrue(visit.snapshot().tabs.isEmpty())
        } finally { f.close() }
    }

    fun testTakeoverWaitsForOriginalPausedBoundaryWithoutReplacingOrExecutingPage() = main {
        val f = Fixture()
        try {
            val original = requireNotNull(f.session.currentTab)
            val visit = requireNotNull(f.controller.openVisit())
            visit.takeover(original)
            assertEquals(1, f.pauses)
            assertTrue(visit.snapshot().waiting)
            assertFalse(visit.snapshot().takenOver)
            assertTrue(f.controller.automationHeld)
            f.registry.assertCurrent(original)
            assertEquals(1, f.allocations)
            assertEquals(0, f.calls)
            f.paused = true
            assertTrue(visit.snapshot().takenOver)
            assertFalse(visit.snapshot().waiting)
            assertNotSame(original, f.session.currentTab)
            assertEquals(1, f.allocations)
            visit.restore()
            assertEquals(1, f.resumes)
            assertFalse(f.controller.automationHeld)
        } finally { f.close() }
    }

    fun testLeavingPendingVisitCancelsOnlyItsTakeoverAndNeverResumes() = main {
        val f = Fixture()
        try {
            val original = requireNotNull(f.session.currentTab)
            val visit = requireNotNull(f.controller.openVisit())
            visit.takeover(original)
            visit.close()
            f.paused = true
            assertFalse(visit.snapshot().live)
            assertFalse(f.controller.automationHeld)
            f.registry.assertCurrent(original)
            assertEquals(0, f.resumes)
            assertFalse(requireNotNull(f.controller.openVisit()).snapshot().takenOver)
        } finally { f.close() }
    }

    fun testKnownBusyPrecheckDoesNotPermanentlyPoisonOriginalBrowser() = main {
        val f = Fixture()
        try {
            val original = requireNotNull(f.session.currentTab)
            val reservation = f.registry.reserveOperation(original)
            try {
                f.paused = true
                val visit = requireNotNull(f.controller.openVisit())
                visit.takeover(original)
                assertFalse(visit.snapshot().unknown)
                assertFalse(visit.snapshot().takenOver)
                assertFalse(f.controller.automationHeld)
                f.registry.assertCurrent(original)
            } finally { reservation.close() }
            assertEquals(1, f.allocations)
            assertEquals(0, f.calls)
        } finally { f.close() }
    }
}
