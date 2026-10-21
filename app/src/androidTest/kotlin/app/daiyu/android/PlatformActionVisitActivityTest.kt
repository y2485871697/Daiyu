package app.daiyu.android

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real Main lifecycle/route guards before disposal and real Controller preparation/CAS.
 * No device writes: every retained callback or blocked preparation must be rejected first. */
@Suppress("DEPRECATION")
class PlatformActionVisitActivityTest : WiredActivityTestCase() {
    override val engineeringUi = true
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
    private fun actionControl(tag: String): SemanticsNodeInteraction {
        dismissIme()
        node("chat-extra-actions").scrollToDescendant(hasTestTag(tag))
        return ui.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled()
    }
    private fun clickAction(tag: String) { actionControl(tag).performClick() }
    private fun preview(): ControllerPlatformActionPreparation {
        clickAction("platform-action-entry")
        clickAction("platform-action-media")
        var duringClick: ControllerPlatformActionPreparation? = null
        actionControl("platform-action-preview")
            .performSemanticsAction(SemanticsActions.OnClick) { click ->
                assertTrue(click())
                duringClick = fixture.controller.platformActionPreparation()
            }
        val pending = duringClick ?: run {
            val errors = ui.onAllNodesWithTag("platform-action-preview-error")
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .joinToString { it.config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString() }
            error("production preview callback did not create preparation; error=$errors")
        }
        mainThread {
            assertSame("created preparation was revoked before confirmation", pending, fixture.controller.platformActionPreparation())
            assertFalse(pending.cancelled)
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
        val transitions: List<(() -> Unit) -> Unit> = listOf(
            { check -> mainThread { sidebar(); check() } },
            { check -> mainThread { route(); check() } },
            { check -> realPauseResume(activity, atPause = check) },
        )
        for (transition in transitions) {
            val old = preview()
            val stale = retainedClick(activity, "platform-action-confirm")
            transition {
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
        dismissIme()
        node("platform-action-confirm").assertIsDisplayed().assertIsEnabled().performClick()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        realPauseResume(activity) { assertTrue(old.cancelled) }
        release.countDown()
        awaitControl("platform preparation drained") { ChatController::class.java.getDeclaredField("preparation").apply { isAccessible = true }.get(fixture.controller) == null }
        assertUnsent()
    }
}
