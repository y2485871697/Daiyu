package app.daiyu.android

import androidx.compose.ui.test.*
import app.daiyu.android.ui.RetainedPlatformActionSubmitAction
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real Main lifecycle/route guards and Controller preparation/CAS. No device writes:
 * retained original submits are rejected, and valid submissions stop before DB/model admission. */
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
    override fun tearDown() { release.countDown(); super.tearDown() }
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
    private fun openMedia() {
        actionControl("platform-action-entry").performClick()
        actionControl("platform-action-media").performClick()
        actionControl("platform-action-preview")
    }
    private fun assertUnsent() {
        assertDraft(text)
        assertEquals(text, fixture.database.draft())
        assertTrue(fixture.database.conversations().isEmpty())
        assertEquals(0, fixture.opens.get())
    }
    fun testRetainedOriginalSubmitCannotReviveAfterSidebarRouteOrPauseRoundTrip() {
        setDraft(text)
        val transitions: List<(() -> Unit) -> Unit> = listOf(
            { check -> mainThread { sidebar(); check() } },
            { check -> mainThread { route(); check() } },
            { check -> realPauseResume(activity, atPause = check) },
        )
        for (transition in transitions) {
            openMedia()
            val stale = node("platform-action-preview").fetchSemanticsNode()
                .config[RetainedPlatformActionSubmitAction]
            transition { stale(); assertNull(fixture.controller.platformActionPreparation()) }
            ui.waitForIdle()
            mainThread { stale(); assertNull(fixture.controller.platformActionPreparation()) }
            assertUnsent()
        }
    }
    fun testOriginalExecuteAutomaticallySubmitsAndPauseRevokesBlockedWorkerBeforeAcceptance() {
        setDraft(text)
        openMedia()
        actionControl("platform-action-preview").performClick()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        lateinit var original: ControllerPlatformActionPreparation
        mainThread {
            original = checkNotNull(fixture.controller.platformActionPreparation())
            assertTrue(original.submitted)
            assertNotNull(original.scope)
        }
        ui.onNodeWithTag("platform-action-confirm").assertDoesNotExist()
        realPauseResume(activity) { assertTrue(original.cancelled) }
        release.countDown()
        awaitControl("platform preparation drained") {
            ChatController::class.java.getDeclaredField("preparation").apply { isAccessible = true }.get(fixture.controller) == null
        }
        assertUnsent()
    }
}
