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
    private inner class Actions(private val holdRefresh: Boolean = false) : ContextManagementControllerAdapter {
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
        override fun selectCompression(selection: ContextManagementSelection): Boolean {
            events.add("select"); this.selection = selection
            state.value = state.value.copy(operation = ContextManagementOperation.SELECT)
            return true
        }
        fun selected() {
            val binding = SummaryModelBinding("fixture-target", "opaque-model-uuid")
            val before = state.value.snapshot!!
            state.value = state.value.copy(operation = null, confirmingCompression = true,
                snapshot = before.copy(configuration = before.configuration!!.copy(summaryModel = binding),
                    summaryTarget = SummaryProfileDescriptor(binding, "revision-1",
                        "https://summary.invalid/v1/responses", "actual-api-model")))
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
                    SettingsModelSource(fixture.application, fixture.controller.providers), onClose = {})
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
    fun testSelectionAndSeparateConfirmationRetainExactTargetAndCancellation() {
        val actions = Actions(); show(actions)
        node("context-compress").performClick()
        awaitVisualIdle()
        assertEquals(ContextManagementSelection(null, ProviderProtocol.CHAT_COMPLETIONS), actions.selection)
        assertEquals(listOf("refresh", "select"), actions.events)
        node("context-management").performScrollToIndex(0)
        node("compression-dialog-operation").assertDoesNotExist()
        node("context-cancel").assertDoesNotExist()
        node("context-compress").assertIsNotEnabled()
        mainThread { actions.selected() }
        node("context-management").scrollToDescendant(hasTestTag("context-confirm-compress"))
        ui.onNodeWithText("模型：actual-api-model\n地址：https://summary.invalid/v1/responses").assertExists()
        ui.onNodeWithText("opaque-model-uuid", substring = true).assertDoesNotExist()
        node("context-dismiss-compress").scrollToVisible().performClick()
        awaitVisualIdle()
        node("context-compression-confirmation").assertDoesNotExist()
        node("context-compress").performClick()
        mainThread { actions.selected() }
        node("context-confirm-compress").scrollToVisible().performClick()
        awaitVisualIdle()
        node("context-cancel").scrollToVisible().performClick()
        awaitVisualIdle()
        node("context-cancel").assertIsNotEnabled()
        node("context-compress").assertIsNotEnabled()
        assertEquals(listOf("refresh", "select", "dismiss", "select", "confirm", "cancel"), actions.events)
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
