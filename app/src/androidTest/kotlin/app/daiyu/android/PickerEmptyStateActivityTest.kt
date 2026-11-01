package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import app.daiyu.android.assistants.FileAssistantAssets
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.model.catalog.ModelSelectionState
import app.daiyu.android.model.catalog.RemoteModel
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.assistants.AssistantPickerDialog
import app.daiyu.android.ui.screens.chat.conversation.ChatModelPicker
import app.daiyu.android.ui.screens.chat.conversation.ChatModelPickerState
import app.daiyu.android.ui.screens.chat.conversation.ComposerShortcutKind
import app.daiyu.android.ui.screens.chat.conversation.ComposerShortcutPicker
import app.daiyu.android.ui.screens.settings.ModelCatalogEntryUi
import app.daiyu.android.ui.screens.settings.ProviderModelSelectionDialog
import kotlin.math.abs

class PickerEmptyStateActivityTest : WiredActivityTestCase() {
    private fun show(dark: Boolean, font: Float = 1f, content: @Composable () -> Unit) {
        mainThread { activity.setContent {
            val platform = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(platform.density, font)) {
                AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                    applyInterfaceScale = false, content = content)
            }
        } }
        ui.waitForIdle()
        dismissIme()
        awaitVisualIdle()
    }

    private fun setSearch(tag: String, query: String) {
        node(tag).performSemanticsAction(SemanticsActions.SetText) { assertTrue(it(AnnotatedString(query))) }
        awaitVisualIdle()
    }

    private fun assertCentered(message: String, contentTag: String) {
        awaitVisualIdle()
        val text = ui.onNodeWithText(message, useUnmergedTree = true).assertIsDisplayed()
        val bounds = text.fetchSemanticsNode().boundsInRoot
        val content = node(contentTag).fetchSemanticsNode().boundsInRoot
        assertTrue("empty text centered horizontally", abs(bounds.center.x - content.center.x) <= 2f)
        assertTrue("empty text centered vertically", abs(bounds.center.y - content.center.y) <= 2f)
        assertTrue("empty text fits content", bounds.top >= content.top - 1f && bounds.bottom <= content.bottom + 1f)
        val layouts = mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        assertEquals(TextAlign.Center, layouts.single().layoutInput.style.textAlign)
        assertFalse(layouts.single().hasVisualOverflow)
    }

    fun testModelEmptyFeedbackAndLoadedTransitionKeepSearchFixed() {
        val message = "暂无可选朗读模型，请先在供应商中添加语音模型。"
        for (dark in listOf(false, true)) for (font in listOf(1f, 1.3f)) {
            val value = mutableStateOf(ChatModelPickerState("", emptyList(), enabled = false, message = message))
            show(dark, font) { ChatModelPicker(value.value, { fail("empty picker cannot select") }, {}) }
            val search = node("chat-model-search").fetchSemanticsNode().boundsInRoot
            for (state in listOf(value.value, value.value.copy(loading = true),
                value.value.copy(message = "模型列表读取失败，请重新打开。"))) {
                mainThread { value.value = state }
                assertCentered(if (state.loading) "正在读取模型目录…" else state.message!!, "chat-model-content")
                assertEquals(search, node("chat-model-search").fetchSemanticsNode().boundsInRoot)
            }
            mainThread { value.value = ChatModelPickerState("one", listOf(ModelCatalogEntryUi("one"))) }
            node("chat-model-option:one").assertIsDisplayed().assertIsEnabled()
            node("chat-model-empty").assertDoesNotExist()
            assertEquals(search, node("chat-model-search").fetchSemanticsNode().boundsInRoot)
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testSearchEmptyAndClearSelectionKeepOriginalCallbacks() {
        var clears = 0
        var selects = 0
        val value = mutableStateOf(ChatModelPickerState("one", listOf(ModelCatalogEntryUi("one"))))
        show(false) { ChatModelPicker(value.value, { selects++ }, {}, onClearSelection = { clears++ }) }
        setSearch("chat-model-search", "not-a-model")
        assertCentered("没有找到匹配的模型", "chat-model-content")
        setSearch("chat-model-search", "")
        node("chat-model-clear").assertIsEnabled().performClick()
        assertEquals(1, clears)
        node("chat-model-option:one").assertIsDisplayed().performClick()
        assertEquals(1, selects)
        mainThread { value.value = value.value.copy(models = emptyList(), message = "目录为空") }
        node("chat-model-empty").assertDoesNotExist()
        node("chat-model-clear").assertIsDisplayed().performClick()
        assertEquals(2, clears)
        ui.onNodeWithText("目录为空").assertIsDisplayed()
    }

    fun testSuppressedEmptyStatusDoesNotReturnAsCenteredCopy() {
        for (loading in listOf(false, true)) {
            show(false) { ChatModelPicker(ChatModelPickerState("", emptyList(), loading = loading,
                enabled = false, message = "读取失败"), {}, {}, showStatusMessages = false) }
            ui.onNodeWithText("读取失败").assertDoesNotExist()
            ui.onNodeWithText("正在读取模型目录…").assertDoesNotExist()
            node("chat-model-empty").assertDoesNotExist()
        }
    }

    fun testToneEmptyAndSearchFeedbackShareTheRemainingRegion() {
        for (dark in listOf(false, true)) for (font in listOf(1f, 1.3f)) {
            val options = mutableStateOf(emptyList<VoiceToneOption>())
            var selected = ""
            show(dark, font) { VoiceTonePicker("音色", "", options.value, { selected = it }, {}) }
            val search = node("voice-tone-search").fetchSemanticsNode().boundsInRoot
            assertCentered("暂无可选音色", "voice-tone-content")
            mainThread { options.value = listOf(VoiceToneOption("zh_female_one", "第一音色")) }
            node("voice-tone-option:zh_female_one").assertIsDisplayed()
            setSearch("voice-tone-search", "not-a-voice")
            assertCentered("没有找到匹配的音色", "voice-tone-content")
            assertEquals(search, node("voice-tone-search").fetchSemanticsNode().boundsInRoot)
            setSearch("voice-tone-search", "")
            node("voice-tone-option:zh_female_one").performClick()
            assertEquals("zh_female_one", selected)
            node("voice-tone-empty").assertDoesNotExist()
        }
    }

    fun testConversationAndSkillEmptyMessagesUseTheListViewport() {
        for (dark in listOf(false, true)) for (kind in ComposerShortcutKind.entries) {
            show(dark, 1.3f) { ComposerShortcutPicker(kind, "", emptyList(), null,
                { fail("empty references cannot select") }, {}) }
            assertCentered(if (kind == ComposerShortcutKind.CONVERSATION) "暂无可引用会话" else "暂无可用 Skill",
                "reference-picker-list")
            node("reference-picker-done").assertIsDisplayed()
        }
    }

    fun testAssistantEmptyAndProviderEmptyKeepTheirFootersOutsideTheRegion() {
        val assets = FileAssistantAssets(instrumentation.targetContext)
        for (dark in listOf(false, true)) {
            show(dark, 1.3f) { AssistantPickerDialog(show = true, assistants = emptyList(), selectedId = null,
                onSelect = {}, onEdit = {}, onDismiss = {}, onCopy = {}, onDelete = {}, assets = assets, onCreate = {}) }
            assertCentered(instrumentation.targetContext.getString(R.string.assistant_empty), "assistant-list")
            val list = node("assistant-list").fetchSemanticsNode().boundsInRoot
            assertTrue(node("assistant-new").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top >= list.bottom - 1f)
            node("assistant-done").assertIsDisplayed()
            val empty = ModelSelectionState.capture("fixture", 0, emptyList())
            val filtered = ModelSelectionState.capture("fixture", 0, listOf(RemoteModel("one", "One"))).withQuery("missing")
            for (selection in listOf(empty, filtered)) {
                show(dark, 1.3f) { ProviderModelSelectionDialog(selection, {}, {}, {}, errorText = "添加失败") }
                assertCentered(if (selection.totalCount == 0) "远端模型目录为空" else "没有匹配的模型", "provider-model-empty")
                ui.onNodeWithText("添加失败").assertIsDisplayed()
                node("dialog-confirm").assertIsDisplayed().assertIsNotEnabled()
            }
        }
        assertEquals(0, fixture.opens.get())
    }
}
