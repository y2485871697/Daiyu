package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
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
                ui.onNodeWithText("完成").performClick()
                node("subagent-collaboration-card").assertDoesNotExist()
                assertEquals(before, prefs.all)
            } finally { prefs.edit().clear().commit() }
        }
    }
}
