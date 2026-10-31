package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import app.daiyu.android.storage.ProviderCatalogRunTarget
import app.daiyu.android.storage.ProviderEditorSlot
import app.daiyu.android.ui.screens.chat.context.CompressionDialogDraft
import app.daiyu.android.ui.screens.chat.context.ContextManagementPresentation
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
        var delayNextRefresh = false
        val events = mutableListOf<String>()
        var selection: ContextManagementSelection? = null
        override fun refresh() {
            events.add("refresh")
            state.value = state.value.copy(snapshot = if (holdRefresh) null else state.value.snapshot,
                operation = if (holdRefresh || delayNextRefresh) ContextManagementOperation.REFRESH else null,
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
    private fun show(actions: Actions, presentation: ContextManagementPresentation = ContextManagementPresentation()) {
        val source = SettingsModelSource(fixture.application, fixture.controller.providers)
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                ContextManagementRoute(actions,
                    source, onClose = { routeCloses++ },
                    presentation = presentation)
            }
        } }
        awaitUniqueTag("context-management")
    }
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    fun testRepeatedCompressionModelSelectionKeepsPickerAndOnlyChangesDraft() {
        val catalog = fixture.settings.modelCatalogSnapshot()
        assertEquals(app.daiyu.android.model.catalog.CatalogCommitResult.COMMITTED,
            fixture.settings.modelCatalog.compareAndAppend(catalog.providerIdentity, catalog.revision,
                listOf(app.daiyu.android.model.catalog.SavedCatalogModel("picker-audit-b", "picker-audit-b",
                    app.daiyu.android.model.catalog.CatalogModelParameters()))) { false })
        val source = SettingsModelSource(fixture.application, fixture.controller.providers)
        val choices = source.choices(setOf(ProviderProtocol.RESPONSES, ProviderProtocol.CHAT_COMPLETIONS, ProviderProtocol.MESSAGES))
        val presentation = ContextManagementPresentation()
        val actions = Actions()
        val before = fixture.settings.load()
        show(actions, presentation)
        node("compression-dialog-custom").performClick()
        node("compression-dialog-model").performClick()
        ui.waitUntil(timeoutMillis = 5000) {
            ui.onAllNodes(hasTestTag("chat-model-picker") and SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "模型目录读取完成")).fetchSemanticsNodes().size == 1
        }
        val sheet = node("chat-selection-sheet").fetchSemanticsNode().root
        val provider = choices.first().provider.slotId
        node("chat-model-search").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetText) {
            assertTrue(it(androidx.compose.ui.text.AnnotatedString(choices.first().providerName)))
        }
        for (choice in choices.take(2)) {
            val id = choice.selection.record.id
            ui.onNode(hasTestTag("chat-model-option:$id") or hasTestTag("chat-model-option:$provider:$id")).performClick()
            assertTrue(presentation.choosingModel)
            assertEquals(choice.key, presentation.draft?.modelKey)
            assertEquals(id, checkNotNull(CatalogSummaryBindings.decode(checkNotNull(presentation.binding))).apiModel)
            assertSame(sheet, node("chat-selection-sheet").fetchSemanticsNode().root)
            node("chat-model-option:$id").assertIsSelected()
            ui.onNodeWithText("正在读取模型目录…").assertDoesNotExist()
            assertNull(actions.selection)
        }
        awaitNodeWindowFocus(node("chat-selection-sheet"))
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        awaitUniqueTag("context-management")
        assertFalse(presentation.choosingModel)
        assertEquals(before, fixture.settings.load())
        assertFalse(actions.events.contains("submit"))
        assertEquals(0, routeCloses)
        assertEquals(0, fixture.opens.get())
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
    private fun binding(model: String, protocol: ProviderProtocol): SummaryModelBinding {
        val identity = java.util.UUID.nameUUIDFromBytes(model.toByteArray()).toString()
        return CatalogSummaryBindings.encode(ProviderCatalogRunTarget(ProviderEditorSlot("00000000-0000-0000-0000-000000000003", 7),
            identity, 11, identity, "api-$model", protocol))
    }

    fun testExplicitRefreshInitializesOnlyFromCompletedFreshSnapshot() {
        val presentation = ContextManagementPresentation()
        val actions = Actions()
        val a = binding("A", ProviderProtocol.RESPONSES)
        val b = binding("B", ProviderProtocol.CHAT_COMPLETIONS)
        actions.state.value = actions.state.value.copy(snapshot = snapshot().copy(
            savedConfiguration = snapshot().configuration!!.copy(summaryModel = a)))
        show(actions, presentation)
        awaitVisualIdle()
        assertEquals(a, presentation.binding)
        mainThread {
            actions.delayNextRefresh = true
            actions.state.value = actions.state.value.copy(requiresRefresh = true)
        }
        node("compression-dialog-refresh").scrollToVisible().performClick()
        awaitVisualIdle()
        assertEquals(ContextManagementOperation.REFRESH, actions.state.value.operation)
        assertNull(presentation.draft)
        assertNull(presentation.binding)
        mainThread {
            actions.state.value = actions.state.value.copy(operation = null, requiresRefresh = false,
                snapshot = snapshot().copy(savedConfiguration = snapshot().configuration!!.copy(summaryModel = b)))
        }
        awaitVisualIdle()
        assertEquals(b, presentation.binding)
        assertEquals(ProviderProtocol.CHAT_COMPLETIONS, presentation.draft!!.protocol)
        assertFalse(actions.events.contains("submit"))
        assertEquals(0, fixture.opens.get())
    }

    fun testAdapterReplacementKeepsUnsavedExactTargetAndOnlyRefreshes() {
        val a = binding("A", ProviderProtocol.RESPONSES)
        val b = binding("B", ProviderProtocol.CHAT_COMPLETIONS)
        val presentation = ContextManagementPresentation().apply {
            retain(CompressionDialogDraft(true, "unsaved A", "local-A", ProviderProtocol.RESPONSES), a)
        }
        val old = Actions(accepts = false)
        val fresh = Actions(accepts = false)
        fresh.state.value = fresh.state.value.copy(snapshot = snapshot().copy(
            savedConfiguration = snapshot().configuration!!.copy(summaryModel = b)))
        var current by mutableStateOf<ContextManagementControllerAdapter?>(old)
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                val captured = current
                ContextManagementRoute(captured,
                    SettingsModelSource(fixture.application, fixture.controller.providers), onClose = { routeCloses++ },
                    presentation = presentation, isCurrent = { current === captured })
            }
        } }
        awaitUniqueTag("context-management")
        mainThread { current = null }
        awaitVisualIdle()
        node("context-compress").assertIsNotEnabled()
        node("context-management-paused").assertDoesNotExist()
        mainThread { current = fresh }
        awaitVisualIdle()
        node("compression-dialog-custom").assertIsOn()
        assertEquals(a, presentation.binding)
        assertEquals(listOf("refresh"), fresh.events)
        assertFalse(old.events.contains("submit"))
        node("context-compress").performClick()
        awaitVisualIdle()
        assertEquals(ContextManagementSelection(a, ProviderProtocol.RESPONSES), fresh.selection)
        assertEquals(listOf("refresh", "submit"), fresh.events)
        assertEquals(0, fixture.opens.get())
        assertEquals(0, routeCloses)
    }

}
