package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.settings.ModelCatalogEntryUi

class ChatModelPickerLayoutTest : WiredActivityTestCase() {
    private val selected = mutableListOf<Pair<String, String>>()
    private val models = listOf(ModelCatalogEntryUi("same", "Display name"), ModelCatalogEntryUi("next"))
    private val state = ChatModelPickerState("same", models, providerId = "a", providers = listOf(
        ChatModelProviderUi("a", "Alpha", models, balance = "1.93"),
        ChatModelProviderUi("b", "Beta", listOf(ModelCatalogEntryUi("same", "Display name"))),
    ))

    private fun show(value: ChatModelPickerState = state, dark: Boolean = false, scale: Float = 1f, font: Float = 1f) {
        mainThread {
            activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, font)) {
                    key(value, dark, scale, font) {
                        AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT,
                            interfaceScale = scale), applyInterfaceScale = true) {
                            ChatModelPicker(value, { selected += "a" to it }, {},
                                onSelectOtherProvider = { provider, model -> selected += provider to model })
                        }
                    }
                }
            }
        }
        ui.waitForIdle()
    }

    private fun search(text: String) {
        node("chat-model-search").performSemanticsAction(SemanticsActions.SetText) { assertTrue(it(AnnotatedString(text))) }
    }

    fun testHtmlStructureAndAccordionAcrossThemesAndScales() {
        for (dark in listOf(false, true)) for (scale in listOf(1f, .8f)) {
            show(dark = dark, scale = scale)
            ui.onNodeWithText("当前模型").assertDoesNotExist()
            ui.onNodeWithText("搜索模型 / 供应商", useUnmergedTree = true).assertExists()
            node("chat-model-provider:a").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已展开"))
            node("chat-model-option:same").assertIsSelected()
            node("chat-model-option:b:same").assertDoesNotExist()
            node("chat-model-list").scrollToDescendant(hasTestTag("chat-model-provider:b"))
            node("chat-model-provider:b").performClick()
            node("chat-model-option:b:same").assertIsNotSelected().performClick()
            node("chat-model-option:same").assertDoesNotExist()
            assertEquals("b" to "same", selected.last())
            node("chat-model-provider:b").performClick()
            node("chat-model-option:b:same").assertDoesNotExist()
        }
    }

    fun testSearchMatchesProviderNameAndModelIdAndKeepsCollapseSeparate() {
        show()
        search("beta")
        node("chat-model-provider:a").assertDoesNotExist()
        node("chat-model-option:b:same").assertIsDisplayed().assertIsNotSelected()
        node("chat-model-provider:b").performClick()
        node("chat-model-option:b:same").assertDoesNotExist()
        search("same")
        node("chat-model-option:same").assertIsSelected()
        node("chat-model-option:b:same").assertIsNotSelected()
        search("")
        node("chat-model-provider:a").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已展开"))
        node("chat-model-option:b:same").assertDoesNotExist()
        search("no-match")
        ui.onNodeWithText("没有找到匹配的模型").assertIsDisplayed()
        search("next")
        node("chat-model-option:next").performClick()
        assertEquals("a" to "next", selected.last())
    }

    fun testLongCatalogRemainsLazyAndRunningStateDisablesSelection() {
        val many = (0..999).map { ModelCatalogEntryUi("model-$it", "长模型名称 $it") }
        show(ChatModelPickerState("model-999", many, enabled = false), font = 1.3f)
        node("chat-model-list").scrollToDescendant(hasTestTag("chat-model-option:model-999"))
        node("chat-model-option:model-999").assertIsDisplayed().assertIsSelected().assertIsNotEnabled()
        node("chat-model-option:model-0").assertDoesNotExist()
        assertTrue(selected.isEmpty())
    }
}
