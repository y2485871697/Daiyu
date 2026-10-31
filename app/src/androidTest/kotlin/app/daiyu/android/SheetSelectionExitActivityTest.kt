package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.*
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.delegation.*
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.components.*
import app.daiyu.android.ui.screens.chat.conversation.ChatSelectionSheet
import java.util.UUID

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class SheetSelectionExitActivityTest : WiredActivityTestCase() {
    private val preferenceNames = mutableListOf<String>()

    override fun cleanupFixture() {
        try { super.cleanupFixture() }
        finally { preferenceNames.forEach { instrumentation.targetContext.deleteSharedPreferences(it) } }
    }

    fun testDraftModelRoleAndTierRetainTheParentUntilTheChildFinishesExiting() {
        for (dark in listOf(false, true)) for (field in listOf("model", "role", "tier")) {
            val preferencesName = "sheet-selection-${UUID.randomUUID()}"
            preferenceNames += preferencesName
            val preferences = instrumentation.targetContext.getSharedPreferences(preferencesName, 0)
            val repository = ConversationSubAgentPreferences(preferences)
            val owner = SubAgentConfigKey.Conversation("selection-owner")
            val models = listOf(SubAgentModel("first", "first-model"), SubAgentModel("second", "second-model"))
            val provider = SubAgentProvider("provider", "Provider", models)
            val editor = ConversationSubAgentEditor(owner, repository, { provider.takeIf { it.id == provider.id } }) { true }
            lateinit var session: SubAgentProfileDraftSession
            mainThread {
                session = checkNotNull(SubAgentProfileDraftSession.open(editor))
                session.selectModel(ModelFeatureSelection(true, provider.id, models.first().id))
                session.name = "Retained name"
                activity.setContent {
                    AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                        applyInterfaceScale = false) {
                        SubAgentProfileConfigDialog(session, editor, listOf(provider), onDismiss = {})
                    }
                }
            }
            val parent = node("subagent-config-window")
            awaitNodeWindowFocus(parent)
            awaitDialogEnter(parent)
            val parentView = (parent.fetchSemanticsNode().root as ViewRootForTest).view
            val ticket = session.openToken
            val before = session.draft
            val savedBefore = preferences.all.toMap()
            val description = when (field) { "model" -> "草稿模型"; "role" -> "草稿职责"; else -> "草稿任务分工" }
            ui.onNodeWithContentDescription(description).scrollToVisible().performClick()
            val option = when (field) {
                "model" -> node("chat-model-option:second")
                "role" -> ui.onNode(hasText("审查／总结") and hasClickAction())
                else -> ui.onNode(hasText(SubAgentTaskTier.entries.last().label) and hasClickAction())
            }
            awaitNodeWindowFocus(option)
            awaitDialogEnter(option)
            val previousAutoAdvance = ui.mainClock.autoAdvance
            try {
                ui.mainClock.autoAdvance = false
                option.performClick()
                ui.mainClock.advanceTimeBy(80)
                val progress = ui.onNode(SemanticsMatcher.expectValue(DialogDismissing, true), useUnmergedTree = true)
                    .fetchSemanticsNode().config[DialogMotionProgress]
                assertTrue("$field has an intermediate exit frame", progress > 0f && progress < 1f)
                assertEquals(before, session.draft)
                assertEquals(savedBefore, preferences.all.toMap())
                assertSame(ticket, session.openToken)
                ui.mainClock.advanceTimeBy(3_000)
                ui.waitForIdle()
                assertSame(parentView, (parent.fetchSemanticsNode().root as ViewRootForTest).view)
                assertSame(ticket, session.openToken)
                assertEquals("Retained name", session.name)
                when (field) {
                    "model" -> { assertEquals(provider.id, session.draft.providerId); assertEquals("second", session.draft.modelId) }
                    "role" -> assertEquals("review", session.draft.role)
                    else -> assertEquals(SubAgentTaskTier.entries.last(), session.draft.tier)
                }
                assertEquals(savedBefore, preferences.all.toMap())
                assertTrue(repository.snapshot(owner).profiles.isEmpty())
                assertEquals(0, fixture.opens.get())
            } finally { ui.mainClock.autoAdvance = previousAutoAdvance }
        }
    }

    fun testAChoiceThatNeedsSaveDoesNotInheritOneShotSelectionExit() {
        var selected by mutableStateOf(false)
        var dismissals = 0
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                ChatSelectionSheet("需要保存", onDismiss = { dismissals++ }) {
                    OptionSelectionItem("草稿选项", selected, onClick = { selected = true })
                }
            }
        } }
        val option = ui.onNodeWithText("草稿选项")
        awaitNodeWindowFocus(option)
        awaitDialogEnter(option)
        option.performClick()
        ui.waitForIdle()
        assertTrue(selected)
        assertEquals(0, dismissals)
        node("chat-selection-sheet").assertIsDisplayed()
        ui.onAllNodes(SemanticsMatcher.expectValue(DialogDismissing, true), useUnmergedTree = true).assertCountEquals(0)
    }
}
