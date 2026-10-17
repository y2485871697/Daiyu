package app.daiyu.android

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real Main lifecycle/route guards before disposal and real Controller preparation/CAS.
 * No device writes: every retained callback or blocked preparation must be rejected first. */
@Suppress("DEPRECATION")
class PlatformActionVisitActivityTest : WiredActivityTestCase() {
    private val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val text = "  exact platform visit draft  "
    override fun createFixture() = RunControlFixture(instrumentation.targetContext, beforeModel = {
        entered.countDown()
        check(release.await(10, TimeUnit.SECONDS))
    })
    override fun tearDown() {
        release.countDown()
        super.tearDown()
    }
    private fun host(name: String, vararg arguments: Any?) {
        MainActivity::class.java.declaredMethods.single { it.name == name && it.parameterCount == arguments.size }
            .apply { isAccessible = true }.invoke(activity, *arguments)
    }
    private fun sidebar() { host("setSidebarVisible", true); host("setSidebarVisible", false) }
    private fun route() { host("setRetainedPage", "settings"); host("setRetainedPage", "chat") }
    private fun pause() { instrumentation.callActivityOnPause(activity); instrumentation.callActivityOnResume(activity) }
    private fun clickAction(tag: String) {
        node(tag).assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
    }
    private fun preview(): ControllerPlatformActionPreparation {
        clickAction("platform-action-entry")
        clickAction("platform-action-media")
        clickAction("platform-action-preview")
        lateinit var pending: ControllerPlatformActionPreparation
        mainThread {
            pending = checkNotNull(fixture.controller.platformActionPreparation()) {
                "production preview click did not create platform preparation"
            }
        }
        ui.waitUntil(5_000) {
            ui.onAllNodesWithTag("platform-action-confirm")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1
        }
        node("platform-action-confirm").assertExists()
        return pending
    }
    private fun assertUnsent() {
        assertDraft(text)
        assertEquals(text, fixture.database.draft())
        assertTrue(fixture.database.conversations().isEmpty())
        assertEquals(0, fixture.opens.get())
    }
    fun testRetainedApprovalCannotReviveAfterSidebarRouteOrPauseRoundTrip() {
        setDraft(text)
        for (transition in listOf<() -> Unit>(::sidebar, ::route, ::pause)) {
            val old = preview()
            val stale = retainedClick(activity, "platform-action-confirm")
            mainThread {
                transition()
                assertTrue(old.cancelled)
                stale()
                assertFalse(old.submitted)
                assertNull(fixture.controller.platformActionPreparation())
            }
            ui.waitForIdle()
            // Check after composition too: an updated callback must not authorize a stale visit.
            mainThread { stale() }
            assertFalse(old.submitted)
            assertUnsent()
        }
    }
    fun testApprovedButBlockedWorkerIsRevokedSynchronouslyBeforeDatabaseAcceptance() {
        setDraft(text)
        val old = preview()
        clickAction("platform-action-confirm")
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        mainThread { pause(); assertTrue(old.cancelled) }
        release.countDown()
        awaitControl("platform preparation drained") { ChatController::class.java.getDeclaredField("preparation").apply { isAccessible = true }.get(fixture.controller) == null }
        assertUnsent()
    }
}
