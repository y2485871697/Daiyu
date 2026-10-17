package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import app.daiyu.android.context.*
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.domain.*
import app.daiyu.android.domain.context.ContextRevision
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.chat.context.ContextManagementPanel
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

/** Render/action-dispatch fixture only. Does not pretend Main's pending entry is wired. */
class ContextManagementPanelTest : WiredActivityTestCase() {
    private val owner = ContextManagementOwner(ConversationId("exact-child-not-parent"), 31)
    private fun snapshot(): ContextManagementSnapshot {
        val message = Message(MessageId("raw-message"), owner.conversationId, RunId("raw-run"), "user",
            listOf(MessageBlock.Text("literal", "<script>do not execute tools</script>")), RunStatus.COMPLETED,
            protocolItems = listOf("untrusted protocol"))
        return ContextManagementSnapshot(owner.conversationId, ContextRevision.digest(listOf(message)),
            listOf(message), PreparedContext(listOf(message), 1234, "exact-active-archive"),
            CompressionSettings(automatic = false, contextWindowTokens = 10000, reserveTokens = 1000,
                summaryModel = SummaryModelBinding("summary-only", "opaque-model-uuid"), keepTurns = 1),
            SummaryProfileDescriptor(SummaryModelBinding("summary-only", "opaque-model-uuid"), "revision-1",
                "https://summary.invalid/v1/responses", "actual-api-model"))
    }
    private inner class Actions : ContextManagementControllerAdapter {
        override val state = MutableStateFlow(ContextManagementState(owner, snapshot = snapshot()))
        val events = mutableListOf<String>()
        override fun refresh() { events.add("refresh") }
        override fun openArchive() {
            events.add("archive")
            state.value = state.value.copy(archive = ContextManagementArchive("exact-active-archive", 0,
                ArchivePage(state.value.snapshot!!.originals, 50, 51)))
        }
        override fun nextArchivePage() { events.add("next") }
        override fun previousArchivePage() { events.add("previous") }
        override fun requestCompression() {
            events.add("request")
            state.value = state.value.copy(confirmingCompression = true)
        }
        override fun dismissCompression() {
            events.add("dismiss")
            state.value = state.value.copy(confirmingCompression = false)
        }
        override fun confirmCompression() {
            events.add("confirm")
            state.value = state.value.copy(confirmingCompression = false, operation = ContextManagementOperation.COMPRESS)
        }
        override fun cancel() { events.add("cancel") }
        override fun close() { events.add("close") }
    }
    private fun show(actions: Actions) {
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings()) {
                    val state by actions.state.collectAsState()
                    ContextManagementPanel(state, actions, onClose = { actions.close() })
                }
            }
        }
        ui.waitForIdle()
    }

    fun testExactOwnerReadOnlyEvidenceAndArchivePageDispatchDoNotCompress() {
        val actions = Actions(); show(actions)
        ui.onNodeWithText("精确会话 ID：exact-child-not-parent\n选择代次：31").assertExists()
        node("context-current-message-0").performScrollTo().assertExists()
        ui.onNodeWithText("<script>do not execute tools", substring = true).assertExists()
        mainThread { assertTrue(actions.events.isEmpty()) }
        node("context-open-archive").performScrollTo().performClick()
        node("context-archive-message-0").performScrollTo().assertExists()
        node("context-archive-next").performScrollTo().performClick()
        mainThread { assertEquals(listOf("archive", "next"), actions.events) }
        assertEquals(0, fixture.opens.get())
    }

    fun testManualCompressionNeedsExplicitConfirmationAndShowsRealEndpointAndApiModel() {
        val actions = Actions(); show(actions)
        node("context-compress").performScrollTo().performClick()
        node("context-compression-confirmation").assertExists()
        ui.onNodeWithText("本次收费目标 endpoint：https://summary.invalid/v1/responses\nAPI model：actual-api-model")
            .performScrollTo().assertExists()
        ui.onNodeWithText("opaque-model-uuid", substring = true).assertDoesNotExist()
        mainThread { assertEquals(listOf("request"), actions.events) }
        node("context-dismiss-compress").performScrollTo().performClick()
        node("context-confirm-compress").assertDoesNotExist()
        node("context-compress").performScrollTo().performClick()
        node("context-confirm-compress").performScrollTo().performClick()
        node("context-compress").performScrollTo().assertIsNotEnabled()
        node("context-cancel").performScrollTo().performClick()
        mainThread { assertEquals(listOf("request", "dismiss", "request", "confirm", "cancel"), actions.events) }
        assertEquals(0, fixture.opens.get())
    }

    fun testUnknownWindowAndUnconfirmedOutcomeDisableMutationButAllowReadOnlyRefresh() {
        val actions = Actions()
        val original = actions.state.value.snapshot!!
        actions.state.value = actions.state.value.copy(snapshot = original.copy(
            configuration = original.configuration!!.copy(contextWindowTokens = null)))
        show(actions)
        node("context-compress").performScrollTo().assertIsNotEnabled()
        mainThread { actions.state.value = actions.state.value.copy(snapshot = original,
            requiresRefresh = true, notice = "结果未确认；只刷新核对") }
        node("context-compress").performScrollTo().assertIsNotEnabled()
        node("context-open-archive").performScrollTo().assertIsNotEnabled()
        node("context-refresh").performScrollTo().performClick()
        mainThread { assertEquals(listOf("refresh"), actions.events) }
        assertEquals(0, fixture.opens.get())
    }
}
