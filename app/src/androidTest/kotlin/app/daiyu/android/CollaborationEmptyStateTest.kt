package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import app.daiyu.android.ui.screens.assistants.AssistantPreviewPalette
import androidx.compose.ui.test.*
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.delegation.ConversationSubAgentPreferences
import app.daiyu.android.delegation.SubAgentConfigKey
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.components.ConversationCollaborationDialog
import java.util.UUID

class CollaborationEmptyStateTest : WiredActivityTestCase() {
    private var density = 1f

    private fun show(id: String?, repository: ConversationSubAgentPreferences, dark: Boolean) {
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT), applyInterfaceScale = false) {
                density = LocalDensity.current.density
                var open by remember(id, dark) { mutableStateOf(true) }
                ConversationCollaborationDialog(open, id, { open = false }, repositoryFactory = { repository })
            }
        } }
    }

    private fun assertSpaciousWithBottomFooter(tag: String) {
        node(tag).assertIsDisplayed()
        val card = node("subagent-collaboration-card").fetchSemanticsNode().boundsInRoot
        val footer = node("subagent-collaboration-footer").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Empty sheet retains its intended height: ${card.height / density}", card.height / density in 350f..385f)
        val message = node(tag).fetchSemanticsNode().boundsInRoot
        assertTrue("Whitespace belongs between message and footer", (footer.top - message.bottom) / density > 60f)
        assertTrue("Footer stays within sheet", footer.bottom <= card.bottom + 1f)
        assertTrue("No unused area after footer", (card.bottom - footer.bottom) / density < 32f)
    }

    private fun assertPrimaryFooterWithoutCross(dark: Boolean, label: String) {
        ui.onNodeWithContentDescription("关闭子代理设置", useUnmergedTree = true).assertDoesNotExist()
        val button = node("subagent-collaboration-done").assertIsEnabled().assertHeightIsEqualTo(44.dp)
        awaitNodeWindowFocus(button)
        instrumentation.uiAutomation.waitForIdle(100, 5_000)
        val pixels = captureScreenNode(button).toPixelMap()
        val expected = AssistantPreviewPalette(dark)
        val background = pixels[(12 * density).toInt(), pixels.height / 2]
        val colorDiagnostic = "dark=$dark expected=${expected.primary} screen=$background"
        assertEquals(colorDiagnostic, expected.primary.red, background.red, .01f)
        assertEquals(colorDiagnostic, expected.primary.green, background.green, .01f)
        assertEquals(colorDiagnostic, expected.primary.blue, background.blue, .01f)
        val layouts = mutableListOf<TextLayoutResult>()
        ui.onNodeWithText(label, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            assertTrue(it(layouts))
        }
        assertEquals(expected.onPrimary, layouts.single().layoutInput.style.color)
    }

    fun testNoConversationKeepsWhitespaceAndBottomFooterWithoutWritingConfiguration() {
        for (dark in listOf(false, true)) {
            val prefs = instrumentation.targetContext.getSharedPreferences("collaboration-empty-${UUID.randomUUID()}", 0)
            try {
                val repository = ConversationSubAgentPreferences(prefs)
                val before = prefs.all.toMap()
                show(null, repository, dark)
                assertSpaciousWithBottomFooter("subagent-no-conversation")
                ui.onNodeWithText("尚未选择会话").assertIsDisplayed()
                ui.onNodeWithText("使用当前配置").assertDoesNotExist()
                assertPrimaryFooterWithoutCross(dark, "关闭")
                ui.onNodeWithText("关闭").performClick()
                node("subagent-collaboration-card").assertDoesNotExist()
                assertEquals(before, prefs.all)
            } finally { prefs.edit().clear().commit() }
        }
    }

    fun testEmptyPresetDirectoryKeepsCurrentConfigurationEntryAndDoesNotApplyAnything() {
        for (dark in listOf(false, true)) {
            val prefs = instrumentation.targetContext.getSharedPreferences("collaboration-no-presets-${UUID.randomUUID()}", 0)
            try {
                val repository = ConversationSubAgentPreferences(prefs)
                repository.createConversation(SubAgentConfigKey.Conversation("empty-state"))
                val before = prefs.all.toMap()
                show("empty-state", repository, dark)
                ui.waitUntil(5_000) { ui.onAllNodesWithTag("subagent-no-presets").fetchSemanticsNodes().size == 1 }
                assertSpaciousWithBottomFooter("subagent-no-presets")
                ui.onNodeWithText("还没有子代理组").assertIsDisplayed()
                ui.onNodeWithText("使用当前配置").assertIsEnabled()
                assertPrimaryFooterWithoutCross(dark, "完成")
                ui.onNodeWithText("完成").performClick()
                node("subagent-collaboration-card").assertDoesNotExist()
                assertEquals(before, prefs.all)
            } finally { prefs.edit().clear().commit() }
        }
    }
}
