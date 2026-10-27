package app.daiyu.android

import androidx.compose.ui.test.*
import app.daiyu.android.ui.screens.chat.conversation.WorkStepOwnerUi

/** Lifecycle regression only; no model request or automatic task takeover is admitted. */
@Suppress("DEPRECATION")
class BrowserPageLifecycleActivityTest : WiredActivityTestCase() {
    private fun visit(): BrowserVisualVisit? = MainActivity::class.java.declaredMethods.single {
        it.name == "getBrowserVisualVisit"
    }.apply { isAccessible = true }.invoke(activity) as BrowserVisualVisit?

    private fun openHuman(): BrowserVisualVisit {
        val conversation = fixture.database.create("default")
        mainThread { activity.select(conversation) }
        awaitControl("browser entry ready") { fixture.controller.ready && !fixture.controller.dataBusy }
        mainThread {
            MainActivity::class.java.declaredMethods.single { it.name == "openBrowserVisualPage" }
                .apply { isAccessible = true }.invoke(activity, null)
        }
        awaitUniqueTag("browser-visual-page")
        lateinit var result: BrowserVisualVisit
        mainThread { result = checkNotNull(visit()) }
        return result
    }

    private fun assertPausedBrowser() {
        mainThread {
            assertEquals("browser", invokeHost(activity, "getRetainedPage"))
            assertNull(visit())
        }
        awaitUniqueTag("browser-paused")
    }

    fun testHumanBrowserReconnectsAfterPauseWithFreshVisit() {
        var old = openHuman()
        repeat(2) {
            val previous = old
            realPauseResume(activity, atPause = {
                assertNull(visit())
                assertFalse(previous.snapshot().live)
                previous.newTab()
            })
            awaitUniqueTag("browser-visual-page")
            mainThread {
                val next = checkNotNull(visit())
                assertNotSame(previous, next)
                assertSame(previous.memory, next.memory)
                assertTrue(next.snapshot().live)
                assertFalse(previous.snapshot().live)
                val tabs = next.snapshot().tabs
                previous.newTab()
                assertEquals(tabs, next.snapshot().tabs)
                old = next
            }
            node("browser-paused").assertDoesNotExist()
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testHumanBrowserRetainsAddressSelectionAndHistoryAcrossPauseAndHome() {
        openHuman()
        val address = "unfinished address, do not navigate"
        val range = androidx.compose.ui.text.TextRange(2, 9)
        node("browser-visual-address").performTextReplacement(address)
        node("browser-visual-address").performTextInputSelection(range)
        dismissIme()
        fun presentation(): app.daiyu.android.browser.BrowserSheetUiState {
            val target = checkNotNull(invokeHost(activity, "getBrowserResumeTarget"))
            return target.javaClass.getDeclaredMethod("getPresentation").apply { isAccessible = true }
                .invoke(target) as app.daiyu.android.browser.BrowserSheetUiState
        }
        lateinit var retained: app.daiyu.android.browser.BrowserSheetUiState
        mainThread { retained = presentation() }
        repeat(2) {
            val previous = checkNotNull(visit())
            realPauseResume(activity)
            awaitUniqueTag("browser-visual-page")
            node("browser-visual-address").assertTextContains(address)
            node("browser-visual-address").assert(SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange, range))
            mainThread {
                assertSame(retained, presentation())
                assertNotSame(previous, visit())
                assertFalse(previous.snapshot().live)
                assertEquals(address, retained.address.text)
            }
        }
        node("browser-visual-history").performClick()
        val query = "unsaved history search"
        ui.onNode(hasSetTextAction() and hasText(activity.getString(R.string.om_browser_history_search)))
            .performTextReplacement(query)
        dismissIme()
        realPauseResume(activity)
        ui.onNode(hasSetTextAction() and hasText(query)).assertIsDisplayed()
        val original = activity
        val stopped = java.util.concurrent.CountDownLatch(1)
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) stopped.countDown()
        }
        mainThread { original.lifecycle.addObserver(observer) }
        try {
            assertTrue(instrumentation.uiAutomation.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME))
            assertTrue(stopped.await(5, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            mainThread { original.lifecycle.removeObserver(observer) }
            restoreOriginalHost(original)
        }
        awaitHostWindowFocus(original)
        ui.onNode(hasSetTextAction() and hasText(query)).assertIsDisplayed()
        mainThread {
            assertSame(retained, presentation())
            assertTrue(retained.showHistory)
            assertEquals(query, retained.historyQuery)
            assertEquals(address, retained.address.text)
            assertEquals(range, retained.address.selection)
        }
        ui.onNodeWithText(activity.getString(R.string.om_browser_history_done)).performClick()
        node("browser-visual-address").assertTextContains(address)
        backToChat(activity)
        openHuman()
        mainThread {
            assertNotSame(retained, presentation())
            assertFalse(presentation().showHistory)
            assertEquals("", presentation().historyQuery)
            assertFalse(address == presentation().address.text)
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testTaskTargetDoesNotAutoReconnectOrFallBackToHuman() {
        val old = openHuman()
        val conversation = checkNotNull(old.conversationId)
        realPauseResume(activity, atPause = {
            val type = MainActivity::class.java.declaredClasses.single { it.simpleName == "BrowserResumeTarget" }
            val target = type.getDeclaredConstructor(String::class.java, WorkStepOwnerUi::class.java)
                .apply { isAccessible = true }.newInstance(
                    conversation.value, WorkStepOwnerUi(conversation.value, "expired-run", "expired-message"))
            invokeHost(activity, "setBrowserResumeTarget", target)
        })
        assertPausedBrowser()
        val oldContinue = node("browser-resume").fetchSemanticsNode().config[RetainedPausedResumeAction]
        realPauseResume(activity, atPause = { oldContinue(); assertNull(visit()) })
        assertPausedBrowser()
        mainThread { oldContinue(); assertNull(visit()) }
        node("browser-resume").performClick()
        assertPausedBrowser()
        backToChat(activity)
        mainThread { oldContinue(); assertNull(visit()) }
        node("browser-paused").assertDoesNotExist()
        assertEquals(0, fixture.opens.get())
    }

    fun testLeavingBrowserWhilePausedCancelsAutomaticReconnect() {
        val old = openHuman()
        realPauseResume(activity, atPause = {
            invokeHost(activity, "returnToChat")
            assertNull(visit())
        })
        mainThread {
            assertEquals("chat", invokeHost(activity, "getRetainedPage"))
            assertFalse(old.snapshot().live)
            assertNull(visit())
        }
        node("browser-visual-page").assertDoesNotExist()
        node("browser-paused").assertDoesNotExist()
        assertEquals(0, fixture.opens.get())
    }
}
