package app.daiyu.android

import androidx.compose.ui.test.*
import androidx.test.platform.graphics.HardwareRendererCompat
import app.daiyu.android.domain.*
import app.daiyu.android.ui.screens.chat.conversation.ChatMenuAction

@Suppress("DEPRECATION")
class ModalRouteBackgroundActivityTest : WiredActivityTestCase() {
    private var drawingWasEnabled = false

    override fun setUp() {
        super.setUp()
        drawingWasEnabled = HardwareRendererCompat.isDrawingEnabled()
        if (!drawingWasEnabled) HardwareRendererCompat.setDrawingEnabled(true)
    }

    override fun tearDown() {
        try { super.tearDown() }
        finally { if (!drawingWasEnabled) HardwareRendererCompat.setDrawingEnabled(false) }
    }

    private fun assertModalKeepsChat(action: ChatMenuAction, route: String, modalTag: String, closeTag: String? = null) {
        val conversation = fixture.database.create("default")
        val user = Message(MessageId("modal-user"), conversation.id, RunId("modal-history"), "user",
            listOf(MessageBlock.Text("body", "modal background continuity")), RunStatus.COMPLETED)
        val answer = user.copy(id = MessageId("modal-answer"), role = "assistant", status = RunStatus.RUNNING)
        fixture.database.send(user, answer)
        fixture.database.save(answer.copy(status = RunStatus.COMPLETED))
        fixture.database.draft(conversation.id, "modal unsent draft")
        mainThread { activity.select(conversation) }
        awaitControl("modal entry ready") { fixture.controller.ready && !fixture.controller.dataBusy }
        dismissIme()
        ui.waitForIdle()
        captureScreenNode(ui.onNodeWithTag("page-back-host"))
        val initial = node("page-back-surface").fetchSemanticsNode().boundsInRoot
        val originalDraftId = node("chat-draft").fetchSemanticsNode().id
        val before = fixture.database.exportBackup()
        fun assertStationaryFrames() {
            repeat(40) {
                ui.mainClock.advanceTimeByFrame()
                for (tag in listOf("page-enter-previous", "page-enter-scrim", "page-enter-blocker",
                    "page-back-departure", "page-back-target")) node(tag).assertDoesNotExist()
                assertEquals(initial, node("page-back-surface").fetchSemanticsNode().boundsInRoot)
                assertEquals(originalDraftId, node("chat-draft").fetchSemanticsNode().id)
            }
        }
        repeat(2) { attempt ->
            ui.onNodeWithContentDescription("更多").performTouchInput { click() }
            awaitUniqueTag("chat-overflow-menu")
            val entry = node("chat-menu:${action.name}").scrollToVisible().assertIsDisplayed()
            ui.waitForIdle()
            ui.mainClock.autoAdvance = false
            try {
                entry.performTouchInput { click() }
                assertStationaryFrames()
                node(modalTag).assertExists()
                mainThread { assertEquals(route, invokeHost(activity, "getRetainedPage")) }
            } finally { ui.mainClock.autoAdvance = true }
            dismissIme()
            ui.waitForIdle()
            ui.mainClock.autoAdvance = false
            try {
                if (attempt == 0 && closeTag != null) node(closeTag).performTouchInput { click() }
                else mainThread { activity.onBackPressedDispatcher.onBackPressed() }
                assertStationaryFrames()
            } finally { ui.mainClock.autoAdvance = true }
            mainThread { assertEquals("chat", invokeHost(activity, "getRetainedPage")) }
            node(modalTag).assertDoesNotExist()
            assertDraft("modal unsent draft")
            node("chat-title").assertExists()
        }
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get())
    }

    fun testBrowserSheetDoesNotSlideOrRemountChatOnOpenAndClose() {
        assertModalKeepsChat(ChatMenuAction.OPEN_BROWSER, "browser", "browser-visual-page", "browser-visual-back")
    }

    fun testHistorySearchDoesNotRemountChatOnRepeatedOpenAndBack() {
        assertModalKeepsChat(ChatMenuAction.SEARCH, "history-search", "search-history-dialog")
    }
}
