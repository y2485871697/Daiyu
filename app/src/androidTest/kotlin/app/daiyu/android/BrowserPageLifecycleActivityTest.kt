package app.daiyu.android

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*

/** Lifecycle regression only; no model request and no task restore are admitted. */
@Suppress("DEPRECATION")
class BrowserPageLifecycleActivityTest : WiredActivityTestCase() {
    private fun visit(): Any? = MainActivity::class.java.declaredMethods.single {
        it.name == "getBrowserVisualVisit"
    }.apply { isAccessible = true }.invoke(activity)
    private fun assertPausedBrowser() {
        mainThread {
            val page = MainActivity::class.java.declaredMethods.single { it.name == "getRetainedPage" }
            assertEquals("browser", page.apply { isAccessible = true }.invoke(activity))
            assertNull(visit())
        }
        awaitUniqueTag("browser-paused")
    }
    fun testPauseRetainsBrowserRouteAndOnlyFreshContinueReopensVisit() {
        val conversation = fixture.database.create("default")
        mainThread { activity.select(conversation) }
        awaitControl("browser entry ready") { fixture.controller.ready && !fixture.controller.dataBusy }
        mainThread {
            MainActivity::class.java.declaredMethods.single { it.name == "openBrowserVisualPage" }
                .apply { isAccessible = true }.invoke(activity, null)
            assertNotNull(visit())
        }
        realPauseResume(activity)
        assertPausedBrowser()
        val oldContinue = checkNotNull(node("browser-resume").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        realPauseResume(activity, atPause = { oldContinue(); assertNull(visit()) })
        assertPausedBrowser()
        mainThread { oldContinue(); assertNull(visit()) }
        node("browser-resume").performClick()
        mainThread { assertNotNull(visit()) }
        realPauseResume(activity)
        assertPausedBrowser()
        backToChat(activity)
        mainThread { oldContinue(); assertNull(visit()) }
        node("browser-paused").assertDoesNotExist()
        assertEquals(0, fixture.opens.get())
    }
}
