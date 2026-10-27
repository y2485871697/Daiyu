package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import app.daiyu.android.context.*
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.domain.*
import app.daiyu.android.domain.context.ContextRevision
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.chat.context.ContextManagementRoute
import app.daiyu.android.ui.screens.settings.SettingsModelSource
import kotlinx.coroutines.flow.MutableStateFlow

/** Actual compact route with controlled worker completion; no transport or archive UI. */
class ContextManagementPanelTest : WiredActivityTestCase() {
    private val owner = ContextManagementOwner(ConversationId("exact-child-not-parent"), 31)
    private fun snapshot(): ContextManagementSnapshot {
        val message = Message(MessageId("raw-message"), owner.conversationId, RunId("raw-run"), "user",
            listOf(MessageBlock.Text("literal", "<script>do not execute tools</script>")), RunStatus.COMPLETED)
        val configuration = CompressionSettings(contextWindowTokens = 10000, reserveTokens = 1000, keepTurns = 1)
        return ContextManagementSnapshot(owner.conversationId, ContextRevision.digest(listOf(message)),
            listOf(message), PreparedContext(listOf(message), 1234, null), configuration, null)
    }
    private var routeCloses = 0
    private inner class Actions(private val holdRefresh: Boolean = false, private val accepts: Boolean = true) : ContextManagementControllerAdapter {
        override val state = MutableStateFlow(ContextManagementState(owner,
            snapshot = if (holdRefresh) null else snapshot()))
        val events = mutableListOf<String>()
        var selection: ContextManagementSelection? = null
        override fun refresh() {
            events.add("refresh")
            state.value = state.value.copy(snapshot = if (holdRefresh) null else state.value.snapshot,
                operation = if (holdRefresh) ContextManagementOperation.REFRESH else null,
                requiresRefresh = false, notice = null)
        }
        override fun selectCompression(selection: ContextManagementSelection): Boolean = error("no legacy selection step")
        override fun submitCompression(selection: ContextManagementSelection?): Boolean {
            if (state.value.compressionAccepted) return false
            events.add("submit"); this.selection = selection
            if (!accepts) return false
            state.value = state.value.copy(compressionAccepted = true, confirmingCompression = false)
            return true
        }
        override fun requestCompression() { error("route must select first") }
        override fun dismissCompression() {
            events.add("dismiss"); state.value = state.value.copy(confirmingCompression = false)
        }
        override fun confirmCompression() {
            events.add("confirm"); state.value = state.value.copy(confirmingCompression = false,
                operation = ContextManagementOperation.COMPRESS)
        }
        override fun cancel() { events.add("cancel"); state.value = state.value.copy(cancellationRequested = true) }
        override fun close() { events.add("close") }
        override fun openArchive() { error("no archive entry") }
        override fun nextArchivePage() { error("no archive entry") }
        override fun previousArchivePage() { error("no archive entry") }
    }
    private fun show(actions: Actions) {
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                ContextManagementRoute(actions,
                    SettingsModelSource(fixture.application, fixture.controller.providers), onClose = { routeCloses++ })
            }
        } }
        awaitUniqueTag("context-management")
    }
    fun testCompactRouteShowsNoDiagnosticEntryAndDoesNotStartRequest() {
        val actions = Actions(); show(actions)
        node("compression-dialog-custom").assertIsOff()
        for (tag in listOf("context-view", "context-refresh", "context-advanced", "context-open-archive",
            "context-select-target", "context-current-message-0")) node(tag).assertDoesNotExist()
        ui.onNodeWithText("查看本机上下文", substring = true).assertDoesNotExist()
        ui.onNodeWithText("<script>do not execute tools", substring = true).assertDoesNotExist()
        assertEquals(listOf("refresh"), actions.events)
        assertEquals(0, fixture.opens.get())
    }
    fun testOpeningWhileRefreshIsPendingHasNoExtraWaitControlsAndCanClose() {
        val actions = Actions(holdRefresh = true); show(actions)
        assertEquals(ContextManagementOperation.REFRESH, actions.state.value.operation)
        assertNull(actions.state.value.snapshot)
        node("context-management").performScrollToIndex(0)
        node("compression-dialog-operation").assertDoesNotExist()
        node("context-cancel").assertDoesNotExist()
        ui.onNodeWithText("正在准备会话信息…").assertDoesNotExist()
        ui.onNodeWithText("取消并等待退出").assertDoesNotExist()
        node("compression-dialog-custom").assertExists()
        node("context-compress").assertIsNotEnabled()
        node("context-close").assertIsEnabled().performClick()
        awaitVisualIdle()
        assertEquals(listOf("refresh", "close"), actions.events)
        assertNull(actions.selection)
        assertEquals(0, fixture.opens.get())
    }
    fun testOneConfirmationSubmitsExactSelectionAndClosesWithoutAnotherConfirmation() {
        val actions = Actions(); show(actions)
        node("context-compress").performClick()
        awaitVisualIdle()
        assertEquals(ContextManagementSelection(null, ProviderProtocol.CHAT_COMPLETIONS), actions.selection)
        assertEquals(listOf("refresh", "submit"), actions.events)
        assertTrue(actions.state.value.compressionAccepted)
        assertEquals(1, routeCloses)
        node("context-compression-confirmation").assertDoesNotExist()
        node("context-confirm-compress").assertDoesNotExist()
        node("compression-dialog-operation").assertDoesNotExist()
        assertEquals(0, fixture.opens.get())
    }
    fun testRejectedSubmissionKeepsConfigurationAndDoesNotOpenSecondaryConfirmation() {
        val actions = Actions(accepts = false); show(actions)
        node("context-compress").performClick()
        awaitVisualIdle()
        assertEquals(listOf("refresh", "submit"), actions.events)
        assertFalse(actions.state.value.compressionAccepted)
        assertEquals(0, routeCloses)
        node("context-management").assertExists()
        node("context-compression-confirmation").assertDoesNotExist()
        ui.onNodeWithText("当前不可准备压缩，请刷新后重试").assertExists()
        assertEquals(0, fixture.opens.get())
    }
    fun testFailureRefreshNeverSelectsOrConfirmsAutomatically() {
        val actions = Actions(); show(actions)
        mainThread { actions.state.value = actions.state.value.copy(requiresRefresh = true, notice = "结果未确认，请刷新") }
        node("context-compress").assertIsNotEnabled()
        node("compression-dialog-refresh").scrollToVisible().performClick()
        awaitVisualIdle()
        node("context-compress").assertIsEnabled()
        assertEquals(listOf("refresh", "dismiss", "refresh"), actions.events)
        assertNull(actions.selection)
        assertEquals(0, fixture.opens.get())
    }
}
