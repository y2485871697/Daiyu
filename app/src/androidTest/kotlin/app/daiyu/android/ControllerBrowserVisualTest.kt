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
    private class Fixture(initialTab: Boolean = true) : AutoCloseable {
        val identity = BrowserRunIdentity(ConversationId("visual-conversation"), RunId("visual-run"), "profile", MessageId("visual-message"))
        val grant = BrowserGrant(BrowserOwner(identity.runId.value, BrowserOwnerKind.MAIN), BrowserAccess.FULL)
        val registry = BrowserSessionRegistry()
        var allocations = 0
        var calls = 0
        var closes = 0
        var failCreate = false
        var failRebind = false
        var failBeforeRelease = false
        var releaseChecks = 0
        var loading = false
        var mounts = 0
        var detaches = 0
        var failDetach = false
        var failConfiguration = false
        val controls = mutableListOf<app.daiyu.android.browser.openminis.BrowserVisibleAction>()
        val configurations = mutableListOf<app.daiyu.android.browser.openminis.BrowserVisibleConfiguration>()
        val downloadListeners = mutableListOf<app.daiyu.android.browser.openminis.BrowserVisibleDownload?>()
        var onCreate: () -> Unit = {}
        var onBeforeRelease: () -> Unit = {}
        var paused = false
        var pauses = 0
        var resumes = 0
        var live = true
        val session = BrowserToolSession(grant, registry, object : BrowserTabLifecycle {
            override fun create(lease: BrowserTabLease) {
                allocations++
                onCreate()
                check(!failCreate) { "native creation uncertain" }
            }
            override fun close(lease: BrowserTabLease) { closes++ }
            override fun beforeReleaseTakeover(allocation: BrowserTabLease, lease: BrowserTabLease) {
                releaseChecks++
                onBeforeRelease()
                check(!failBeforeRelease) { "release state uncertain" }
            }
            override fun rebind(allocation: BrowserTabLease, lease: BrowserTabLease) {
                check(!failRebind) { "native rebind uncertain" }
            }
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
            { paused }, { resumes++; true }, { live = false; true }, attachVisible = { _, parent ->
                mounts++
                val child = android.view.View(parent.context)
                parent.addView(child)
                CatalogBrowserBinding.VisibleAttachment({ error("navigation not part of this fixture") },
                    { loading }, {
                        detaches++
                        check(!failDetach) { "detach uncertain" }
                        parent.removeView(child)
                    }, controlOriginal = { controls.add(it) }, configureOriginal = {
                        configurations.add(it)
                        check(!failConfiguration) { "configuration uncertain" }
                    }, downloadOriginal = { downloadListeners.add(it) })
            })
        init { if (initialTab) check(session.execute(mapOf("action" to "new_tab")) is BrowserOutcome.Confirmed) }
        override fun close() { controller.revoke(); binding.close() }
    }

    fun testVisibleCommandsForwardExactValuesAndConfigurationFailureIsNotReplayed() = main {
        val f = Fixture()
        try {
            f.paused = true
            val visit = requireNotNull(f.controller.openVisit())
            visit.takeover(requireNotNull(f.session.currentTab))
            assertTrue(visit.snapshot().takenOver)
            val parent = android.widget.FrameLayout(instrumentation.targetContext)
            visit.mount(parent)
            val config = app.daiyu.android.browser.openminis.BrowserVisibleConfiguration(
                com.openminis.app.browser.UserAgentProfile.DESKTOP_CHROME, null, 800, 600)
            val listener = app.daiyu.android.browser.openminis.BrowserVisibleDownload { _, _, _, _ -> }
            visit.control(app.daiyu.android.browser.openminis.BrowserVisibleAction.BACK)
            visit.configure(config)
            visit.downloads(listener)
            assertEquals(listOf(app.daiyu.android.browser.openminis.BrowserVisibleAction.BACK), f.controls)
            assertEquals(listOf(config), f.configurations)
            assertSame(listener, f.downloadListeners.last())
            f.failConfiguration = true
            visit.configure(config.copy(width = 900))
            assertTrue(visit.snapshot().unknown)
            val count = f.configurations.size
            visit.configure(config)
            visit.restore()
            assertEquals(count, f.configurations.size)
            assertEquals(0, f.resumes)
        } finally { f.close() }
    }

    fun testNewTabWaitsForPauseAndRetainsOriginalSessionAndAllocation() = main {
        val f = Fixture()
        try {
            val original = requireNotNull(f.session.currentTab)
            val visit = requireNotNull(f.controller.openVisit())
            visit.newTab()
            assertEquals(1, f.pauses)
            repeat(3) { assertTrue(visit.snapshot().waiting) }
            assertEquals(1, f.pauses)
            assertEquals(1, f.allocations)
            f.onCreate = {
                assertTrue(f.controller.automationHeld)
                assertTrue(visit.snapshot().waiting)
                visit.restore(); visit.closeTab(); visit.newTab()
                assertEquals(0, f.resumes)
                assertEquals(0, f.closes)
            }
            f.paused = true
            val state = visit.snapshot()
            assertTrue(state.takenOver)
            assertFalse(state.waiting)
            assertEquals(2, f.allocations)
            assertEquals(2, state.tabs.size)
            assertTrue(state.tabs.contains(original))
            assertEquals(original.owner, state.lease!!.owner)
            assertEquals(0, f.closes)
            assertEquals(0, f.calls)
            assertEquals(0, f.resumes)
            repeat(3) { visit.snapshot() }
            assertEquals(2, f.allocations)
            visit.restore()
            assertEquals(1, f.resumes)
        } finally { f.close() }
    }

    fun testNewTabFromEmptySessionAndLimitPreserveExistingTabs() = main {
        val f = Fixture(initialTab = false)
        try {
            val visit = requireNotNull(f.controller.openVisit())
            f.paused = true
            f.onCreate = { assertTrue(f.controller.automationHeld) }
            repeat(3) { visit.newTab(); assertTrue(visit.snapshot().takenOver) }
            val before = visit.snapshot().tabs
            visit.newTab()
            assertEquals(before, visit.snapshot().tabs)
            assertEquals(3, f.allocations)
            assertEquals(0, f.closes)
            assertEquals(0, f.calls)
            assertEquals(0, f.resumes)
            visit.restore()
            assertEquals(1, f.resumes)
        } finally { f.close() }
    }

    fun testLeavingPendingNewTabAndChangedCurrentNeverAllocate() = main {
        val f = Fixture()
        try {
            val visit = requireNotNull(f.controller.openVisit())
            visit.newTab()
            visit.close()
            f.paused = true
            visit.newTab()
            assertFalse(visit.snapshot().live)
            assertEquals(1, f.allocations)
            assertEquals(0, f.resumes)
            val current = requireNotNull(f.controller.openVisit())
            f.paused = false
            current.newTab()
            assertTrue(f.session.execute(mapOf("action" to "new_tab")) is BrowserOutcome.Confirmed)
            f.paused = true
            assertFalse(current.snapshot().waiting)
            assertFalse(current.snapshot().takenOver)
            assertEquals(2, f.allocations)
        } finally { f.close() }
    }

    fun testUnknownCreationAndOldTabReleaseNeverRetryOrResume() = main {
        for (releaseFailure in listOf(false, true)) {
            val f = Fixture()
            try {
                f.paused = true
                val visit = requireNotNull(f.controller.openVisit())
                visit.takeover(requireNotNull(f.session.currentTab))
                f.failRebind = releaseFailure
                f.failCreate = !releaseFailure
                visit.newTab()
                assertTrue(visit.snapshot().unknown)
                val attempts = f.allocations
                repeat(3) { visit.newTab(); visit.restore(); visit.snapshot() }
                assertEquals(attempts, f.allocations)
                assertEquals(if (releaseFailure) 1 else 2, attempts)
                assertEquals(0, f.resumes)
                assertTrue(f.controller.automationHeld)
            } finally { f.close() }
        }
    }

    fun testUnknownBeforeReleaseDoesNotRetryStillValidToken() = main {
        val f = Fixture()
        try {
            f.paused = true
            val visit = requireNotNull(f.controller.openVisit())
            visit.takeover(requireNotNull(f.session.currentTab))
            val held = f.session.currentTab
            f.failBeforeRelease = true
            visit.newTab()
            assertTrue(visit.snapshot().unknown)
            repeat(3) { visit.newTab(); visit.restore(); visit.snapshot() }
            assertEquals(1, f.releaseChecks)
            assertEquals(held, f.session.currentTab)
            assertEquals(1, f.allocations)
            assertEquals(0, f.closes)
            assertEquals(0, f.resumes)
        } finally { f.close() }
    }

    fun testMountedLoadingPageWaitsThenDetachesBeforeCreatingNewTab() = main {
        val f = Fixture()
        val parent = android.widget.FrameLayout(instrumentation.targetContext)
        try {
            f.paused = true
            val original = requireNotNull(f.session.currentTab)
            val visit = requireNotNull(f.controller.openVisit())
            visit.takeover(original)
            visit.mount(parent)
            f.loading = true
            visit.newTab()
            repeat(3) { assertTrue(visit.snapshot().waiting) }
            assertEquals(1, f.allocations)
            assertEquals(1, parent.childCount)
            assertEquals(0, f.detaches)
            assertEquals(0, f.releaseChecks)
            f.onBeforeRelease = {
                assertEquals(0, parent.childCount)
                assertEquals(f.releaseChecks, f.detaches)
            }
            f.onCreate = {
                assertEquals(0, parent.childCount)
                assertEquals(1, f.detaches)
                assertEquals(1, f.releaseChecks)
                assertTrue(f.controller.automationHeld)
            }
            f.loading = false
            val state = visit.snapshot()
            assertTrue(state.takenOver)
            assertFalse(state.waiting)
            assertEquals(2, f.allocations)
            assertEquals(2, state.tabs.size)
            assertTrue(state.tabs.any { it.tabId == original.tabId })
            assertEquals(0, f.closes)
            assertEquals(0, f.resumes)
            visit.mount(parent)
            assertEquals(2, f.mounts)
            assertEquals(1, parent.childCount)
            visit.restore()
            assertEquals(0, parent.childCount)
            assertEquals(2, f.detaches)
            assertEquals(1, f.resumes)
        } finally { f.close(); parent.removeAllViews() }
    }

    fun testMountedDetachFailurePreventsReleaseCreateAndRetry() = main {
        val f = Fixture()
        val parent = android.widget.FrameLayout(instrumentation.targetContext)
        try {
            f.paused = true
            val visit = requireNotNull(f.controller.openVisit())
            visit.takeover(requireNotNull(f.session.currentTab))
            visit.mount(parent)
            f.failDetach = true
            visit.newTab()
            assertTrue(visit.snapshot().unknown)
            repeat(3) { visit.newTab(); visit.restore(); visit.mount(parent); visit.snapshot() }
            assertEquals(1, f.mounts)
            assertEquals(1, f.detaches)
            assertEquals(0, f.releaseChecks)
            assertEquals(1, f.allocations)
            assertEquals(0, f.resumes)
        } finally { f.close(); parent.removeAllViews() }
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
