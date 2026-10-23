package app.daiyu.android.ui.icons

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.chat.conversation.ChatComposer
import app.daiyu.android.ui.screens.chat.conversation.ChatScreenActions
import app.daiyu.android.ui.screens.chat.conversation.ChatModelPicker
import app.daiyu.android.ui.screens.chat.conversation.ChatModelPickerState
import app.daiyu.android.ui.screens.settings.ModelCatalogEntryUi
import app.daiyu.android.ui.screens.chat.conversation.ComposerUi

class ModelBrandIconTest : WiredActivityTestCase() {
    fun testAllTenVectorsDrawInBothThemesAndKeepBrandColors() {
        for (mode in listOf(AppearanceThemeMode.LIGHT, AppearanceThemeMode.DARK)) {
            mainThread {
                activity.setContent {
                    AgentAppTheme(AppearanceSettings(themeMode = mode), applyInterfaceScale = false) {
                        Row(Modifier.safeDrawingPadding()) {
                            ModelBrand.entries.forEach { brand ->
                                ModelBrandIcon(brand.name, Modifier.size(24.dp).testTag("brand:${brand.name}"), tint = Color.Magenta)
                            }
                        }
                    }
                }
            }
            ui.waitForIdle()
            for (brand in ModelBrand.entries) {
                val icon = node("brand:${brand.name}").assert(SemanticsMatcher.expectValue(ModelBrandIdentity, brand.name))
                val pixels = icon.captureToImage().toPixelMap()
                var drawn = 0
                var brandColored = 0
                for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                    val c = pixels[x, y]
                    if (c.alpha > .8f) {
                        drawn++
                        if (maxOf(c.red, c.green, c.blue) - minOf(c.red, c.green, c.blue) > .2f &&
                            !(c.red > .9f && c.blue > .9f && c.green < .1f)) brandColored++
                    }
                }
                assertTrue("${brand.name} draws visible paths", drawn > 4)
                if (!brand.monochrome) assertTrue("${brand.name} retains colored fill", brandColored > 0)
            }
        }
    }

    fun testChatPickerUsesRealModelIdForBrandAndPreservesSelection() {
        var selected: String? = null
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    ChatModelPicker(ChatModelPickerState("openai/deepseek-r1", listOf(
                        ModelCatalogEntryUi("openai/deepseek-r1", "Claude alias"),
                        ModelCatalogEntryUi("gpt-4.1", "工作模型"),
                    )), { selected = it }, {})
                }
            }
        }
        node("chat-model-option:openai/deepseek-r1")
            .assert(SemanticsMatcher.expectValue(ModelBrandIdentity, "DEEPSEEK")).assertIsSelected()
        node("chat-model-option:gpt-4.1")
            .assert(SemanticsMatcher.expectValue(ModelBrandIdentity, "OPENAI")).performClick()
        assertEquals("gpt-4.1", selected)
    }

    fun testChatInputModelSelectorUpdatesBrandAndKeepsClickAction() {
        val state = mutableStateOf(ComposerUi(modelLabel = "gpt-6.1-sol"))
        var clicks = 0
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    ChatComposer(state.value, ChatScreenActions(onModelClick = { clicks++ }))
                }
            }
        }
        node("chat-model").assert(SemanticsMatcher.expectValue(ModelBrandIdentity, "OPENAI")).performClick()
        assertEquals(1, clicks)
        mainThread { state.value = state.value.copy(modelLabel = "DeepSeek-R1") }
        node("chat-model").assert(SemanticsMatcher.expectValue(ModelBrandIdentity, "DEEPSEEK"))
        mainThread { state.value = state.value.copy(modelLabel = "unknown-local") }
        node("chat-model").assert(SemanticsMatcher.expectValue(ModelBrandIdentity, "GENERIC"))
    }
}
