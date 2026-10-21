package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.assistants.AssistantPreviewPalette
import app.daiyu.android.ui.screens.chat.context.CompressionConversationDialog
import app.daiyu.android.ui.screens.chat.context.CompressionDialogDraft

class CompressionConversationDialogTest : WiredActivityTestCase() {
    private var draft by mutableStateOf(initial())
    private var confirms = 0
    private var cancels = 0
    private var picks = 0
    private fun initial() = CompressionDialogDraft(true, "deepseek-v4-1-flash", "fixture/model",
        ProviderProtocol.CHAT_COMPLETIONS)
    private fun api(protocol: ProviderProtocol) = node("compression-dialog-api:${protocol.wireName}")
    private fun show(dark: Boolean, scale: Float = 1f, fontScale: Float = 1f) {
        mainThread {
            draft = initial()
            activity.setContent {
                val platform = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(platform.density, fontScale)) {
                    AgentAppTheme(AppearanceSettings(interfaceScale = scale,
                        themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                        applyInterfaceScale = true) {
                        CompressionConversationDialog(draft, onChange = { draft = it },
                            onChooseModel = { picks++ }, onConfirm = { confirms++ }, onDismiss = { cancels++ })
                    }
                }
            }
        }
        awaitUniqueTag("context-management")
    }

    fun testOriginalControlsUseNewThemeAndOnlyDispatchExplicitActions() {
        for (dark in listOf(false, true)) for (scale in listOf(1f, .8f)) {
            show(dark, scale)
            ui.onNodeWithText("压缩会话").assertIsDisplayed()
            node("compression-dialog-custom").assertIsOn()
            node("compression-dialog-model").assertTextContains("deepseek-v4-1-flash")
            api(ProviderProtocol.CHAT_COMPLETIONS).assertIsSelected()
            api(ProviderProtocol.RESPONSES).performClick().assertIsSelected()
            api(ProviderProtocol.CHAT_COMPLETIONS).assertIsNotSelected()
            assertEquals(ProviderProtocol.RESPONSES, draft.protocol)
            node("compression-dialog-model").performClick()
            node("compression-dialog-custom").performClick().assertIsOff()
            node("compression-dialog-model").assertDoesNotExist()
            node("compression-dialog-custom").performClick().assertIsOn()
            node("compression-dialog-model").assertTextContains("deepseek-v4-1-flash")
            node("compression-dialog-help-text").assertDoesNotExist()
            node("context-management").scrollToDescendant(hasTestTag("compression-dialog-help"))
            node("compression-dialog-help").performClick()
            node("context-management").scrollToDescendant(hasTestTag("compression-dialog-help-text"))
            node("compression-dialog-help-text").assertTextContains("Anthropic / Codex", substring = true)
            node("context-close").assertIsDisplayed().assertHeightIsAtLeast(44.dp)
            node("context-compress").assertIsDisplayed().assertHeightIsAtLeast(44.dp)
            val pixels = captureScreenNode(node("context-compress")).toPixelMap()
            val actual = pixels[(pixels.width * .1f).toInt(), pixels.height / 2]
            val expected = AssistantPreviewPalette(dark).primary
            assertEquals(expected.red, actual.red, .025f)
            assertEquals(expected.green, actual.green, .025f)
            assertEquals(expected.blue, actual.blue, .025f)
            node("context-management").scrollToDescendant(hasTestTag("compression-dialog-help"))
            node("compression-dialog-help").performClick()
            node("compression-dialog-help-text").assertDoesNotExist()
            node("context-compress").performClick()
            node("context-close").performClick()
            node("context-refresh").assertDoesNotExist()
            node("context-advanced").assertDoesNotExist()
            ui.onNodeWithText("未接入", substring = true).assertDoesNotExist()
        }
        assertEquals(4, confirms)
        assertEquals(4, cancels)
        assertEquals(4, picks)
        assertEquals(0, fixture.opens.get())
    }

    fun testExpandedHelpAtLargerFontKeepsFooterReachableAndBackCancels() {
        show(dark = true, scale = .8f, fontScale = 1.3f)
        node("context-management").scrollToDescendant(hasTestTag("compression-dialog-help"))
        node("compression-dialog-help").performClick()
        node("context-management").scrollToDescendant(hasTestTag("compression-dialog-help-text"))
        val help = node("compression-dialog-help-text").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val footer = node("context-compress").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(help.bottom <= footer.top)
        node("context-close").assertIsDisplayed()
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        ui.waitForIdle()
        assertEquals(1, cancels)
        assertEquals(0, confirms)
        assertEquals(0, fixture.opens.get())
    }
}
