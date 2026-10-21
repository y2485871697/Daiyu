package app.daiyu.android

import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.delegation.*
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.components.*
import app.daiyu.android.ui.components.subagent.*
import java.util.UUID

class SubAgentDialogAffordanceTest : WiredActivityTestCase() {
    private var preferencesName: String? = null

    override fun tearDown() {
        try { super.tearDown() }
        finally { preferencesName?.let { instrumentation.targetContext.deleteSharedPreferences(it) } }
    }

    private fun noHeaderNavigation() {
        ui.onAllNodesWithContentDescription("返回", useUnmergedTree = true).assertCountEquals(0)
        ui.onAllNodesWithContentDescription("关闭", useUnmergedTree = true).assertCountEquals(0)
    }
    private fun back() {
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        ui.waitForIdle()
    }

    fun testRoleAndTierChoicesKeepSelectionAndBackWithoutHeaderClose() {
        for (title in listOf("职责", "任务分工", "默认分辨率")) {
            var visible by mutableStateOf(true)
            var selected = 0
            mainThread { activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    CompositionLocalProvider(LocalConversationAgentStyle provides true) {
                        if (visible) SubAgentChoiceWindow(title, { visible = false }) {
                            ConversationAgentChoice("选项", false) { selected++; visible = false }
                        }
                    }
                }
            } }
            ui.onNodeWithText(title).assertIsDisplayed()
            noHeaderNavigation()
            back()
            ui.runOnIdle { assertFalse(visible); assertEquals(0, selected); visible = true }
            ui.onNode(isRoot() and hasAnyDescendant(hasText(title))).performTouchInput {
                click(androidx.compose.ui.geometry.Offset(2f, 2f))
            }
            ui.runOnIdle { assertFalse(visible); assertEquals(0, selected); visible = true }
            ui.onNodeWithText("选项").performClick()
            ui.runOnIdle { assertFalse(visible); assertEquals(1, selected) }
        }
    }

    fun testDraftThinkingSliderSavesOnlyOnConfirmAndRestoresItsRequestedValue() {
        val name = "subagent-affordance-${UUID.randomUUID()}"
        preferencesName = name
        val repository = ConversationSubAgentPreferences(instrumentation.targetContext.getSharedPreferences(name, 0))
        val owner = SubAgentConfigKey.Conversation("fixture-conversation")
        val model = SubAgentModel("selection", "arbitrary-api-model")
        val provider = SubAgentProvider("provider", "Provider", listOf(model))
        val profile = SubAgentProfile("profile", "Worker", providerId = provider.id, modelId = model.id)
        repository.update(owner) { it.copy(profiles = listOf(profile)) }
        lateinit var editor: ConversationSubAgentEditor
        var active by mutableStateOf<SubAgentProfileDraftSession?>(null)
        mainThread {
            editor = ConversationSubAgentEditor(owner, repository, { id -> provider.takeIf { it.id == id } }) { true }
            active = checkNotNull(SubAgentProfileDraftSession.open(editor, profile))
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    CompositionLocalProvider(LocalConversationAgentStyle provides true) {
                        active?.let { session -> SubAgentProfileConfigDialog(session, editor, listOf(provider), onDismiss = { active = null }) }
                    }
                }
            }
        }
        ui.onNodeWithText("子代理配置").assertIsDisplayed()
        ui.onNodeWithText("取消").assertIsDisplayed()
        ui.onNodeWithText("确认").assertIsDisplayed()
        noHeaderNavigation()
        ui.onNodeWithContentDescription("草稿思考深度").assertIsEnabled().performClick()
        noHeaderNavigation()
        node("thinking-depth-slider").performScrollTo().assertIsDisplayed()
        node("subagent-thinking-value").assertTextEquals("选择思考深度")
        assertNull(repository.snapshot(owner).profiles.single().reasoning)
        node("thinking-depth-slider").performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(4f)) }
        node("subagent-thinking-value").assertTextEquals("高")
        assertNull(repository.snapshot(owner).profiles.single().reasoning)
        back()
        ui.onNodeWithText("确认").assertIsEnabled().performClick()
        awaitControl("confirmed child reasoning") { repository.snapshot(owner).profiles.single().reasoning == ReasoningEffort.HIGH }
        ui.waitForIdle()
        ui.runOnIdle { assertNull(active) }
        mainThread { active = checkNotNull(SubAgentProfileDraftSession.open(editor, repository.snapshot(owner).profiles.single())) }
        ui.onNodeWithContentDescription("草稿思考深度").performClick()
        node("subagent-thinking-value").assertTextEquals("高")
        node("thinking-depth-slider").performScrollTo().assertIsDisplayed().performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(0f)) }
        node("subagent-thinking-value").assertTextEquals("关闭")
        ui.onAllNodes(hasText("关闭") and hasClickAction()).assertCountEquals(0)
        assertEquals(ReasoningEffort.HIGH, repository.snapshot(owner).profiles.single().reasoning)
        back()
        ui.onNodeWithText("取消").performClick()
        assertEquals(ReasoningEffort.HIGH, repository.snapshot(owner).profiles.single().reasoning)
        ui.runOnIdle { assertNull(active) }
        assertEquals(0, fixture.opens.get())
    }
}
