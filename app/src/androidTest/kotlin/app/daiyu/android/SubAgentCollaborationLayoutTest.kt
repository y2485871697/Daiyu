package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.delegation.*
import app.daiyu.android.model.GptSpeedMode
import app.daiyu.android.ui.components.*
import java.util.UUID
import androidx.compose.ui.test.*
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.components.subagent.*

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class SubAgentCollaborationLayoutTest : WiredActivityTestCase() {
    private val preferenceNames = mutableListOf<String>()
    private val editors = mutableListOf<ConversationSubAgentEditor>()

    override fun cleanupFixture() {
        try { super.cleanupFixture() }
        finally {
            mainThread { editors.forEach { it.dispose() }; editors.clear() }
            preferenceNames.forEach { instrumentation.targetContext.deleteSharedPreferences(it) }
            preferenceNames.clear()
        }
    }

    private class ProfileFixture(val repository: ConversationSubAgentPreferences,
        val editor: ConversationSubAgentEditor, val provider: SubAgentProvider, val profile: SubAgentProfile)

    private fun profileFixture(): ProfileFixture {
        val name = "subagent-layout-${UUID.randomUUID()}"
        preferenceNames += name
        val repository = ConversationSubAgentPreferences(instrumentation.targetContext.getSharedPreferences(name, 0))
        val owner = SubAgentConfigKey.Conversation("layout-fixture")
        val provider = SubAgentProvider("provider", "Provider", listOf(
            SubAgentModel("gpt-local", "gpt-6.1-sol", supportsGptSpeed = true),
            SubAgentModel("other-local", "gemini-3")))
        val profile = SubAgentProfile("worker", "Worker", role = "implementation", providerId = provider.id,
            modelId = "gpt-local", tier = SubAgentTaskTier.entries.first())
        repository.update(owner) { it.copy(enabled = true, profiles = listOf(profile)) }
        lateinit var editor: ConversationSubAgentEditor
        mainThread { editor = ConversationSubAgentEditor(owner, repository,
            { id -> provider.takeIf { it.id == id } }) { true }; editors += editor }
        return ProfileFixture(repository, editor, provider, profile)
    }

    fun testFourRoleIndicatorsHaveNoClickAction() {
        val roles = listOf("implementation", "review", "image_generation", "video_generation")
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                Column { roles.forEach { SubAgentRoleIcon(SubAgentProfile(it, it, it)) } }
            }
        } }
        roles.forEach { role ->
            node("subagent-role-$role").assertIsDisplayed().assertHasNoClickAction()
        }
    }

    fun testSharedPickerShowsApiModelNotLocalIdentityAndReturnsExactSelection() {
        val local = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        var selected: Pair<String, String>? = null
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                CompositionLocalProvider(LocalConversationAgentStyle provides true) {
                    SubAgentModelPickerDialog(SubAgentModelPickerUiState(listOf(SubAgentModelGroupUi("provider", "Provider",
                        listOf(SubAgentModelOptionUi(local, "provider", "Custom GPT", false, false, "gpt-6.1-sol")))), null),
                        true, {}, { provider, model -> selected = provider to model }, "选择模型")
                }
            }
        } }
        node("chat-model-picker").assertIsDisplayed()
        node("chat-model-search").performTextInput("gpt-6.1")
        ui.onNodeWithText(local).assertDoesNotExist()
        ui.onNodeWithText("gpt-6.1-sol").assertIsDisplayed()
        node("chat-model-option:$local").performClick()
        ui.runOnIdle { assertEquals("provider" to local, selected) }
    }

    fun testActualCardKeepsRolePassiveAndRemovesSpeedAndDividerWhenModelChanges() {
        val data = profileFixture()
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                CompositionLocalProvider(LocalConversationAgentStyle provides true,
                    LocalConversationSubAgentEditor provides data.editor) {
                    val config = (data.editor.observe() as? SubAgentEditorState.Loaded)?.config
                    Box(Modifier.requiredWidth(320.dp)) {
                        config?.profiles?.singleOrNull()?.let { SubAgentProfileRow(it, listOf(data.provider)) }
                    }
                }
            }
        } }
        val role = node("subagent-role-worker")
        role.assertIsDisplayed().assertHasNoClickAction().performTouchInput { click() }
        ui.onNodeWithText("子代理配置").assertDoesNotExist()
        ui.onNodeWithContentDescription("Worker GPT 速度：标准，点击切换速度").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        node("subagent-gpt-divider-worker").assertExists()
        ui.onNodeWithContentDescription("Worker模型").assertHeightIsAtLeast(48.dp).performClick()
        node("chat-model-picker").assertIsDisplayed()
        node("chat-model-option:other-local").performClick()
        awaitControl("non-GPT binding saved") { data.repository.snapshot(data.editor.owner).profiles.single().modelId == "other-local" }
        node("subagent-gpt-divider-worker").assertDoesNotExist()
        ui.onAllNodes(hasContentDescription("GPT 速度", substring = true)).assertCountEquals(0)
        ui.onNodeWithContentDescription("Worker模型").performTouchInput { longClick() }
        awaitUniqueTag("thinking-depth-slider")
        awaitNodeWindowFocus(node("thinking-depth-slider"))
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        ui.waitForIdle()
        node("thinking-depth-slider").assertDoesNotExist()
        ui.onNodeWithContentDescription("配置Worker").performClick()
        ui.onNodeWithText("子代理配置").assertIsDisplayed()
        ui.onNodeWithContentDescription("草稿GPT速度").assertDoesNotExist()
        ui.onNodeWithText("取消").performClick()
        assertEquals(0, fixture.opens.get())
    }

    fun testProfileFormKeepsEqualFooterAndScrollsAtPhysicalWidthAndLargeFont() {
        for (dark in listOf(false, true)) for (fontScale in listOf(.9f, 1.3f)) {
            val data = profileFixture()
            lateinit var session: SubAgentProfileDraftSession
            var visible by mutableStateOf(true)
            mainThread {
                session = checkNotNull(SubAgentProfileDraftSession.open(data.editor, data.profile))
                activity.setContent {
                    key(data.editor, dark, fontScale) {
                        // A 1216px window at 525dpi is about 370.6dp wide.
                        val targetDensity = activity.resources.displayMetrics.widthPixels / 370.6f
                        CompositionLocalProvider(LocalDensity provides Density(targetDensity, fontScale)) {
                            AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                                applyInterfaceScale = false) {
                                CompositionLocalProvider(LocalConversationAgentStyle provides true) {
                                    if (visible) SubAgentProfileConfigDialog(session, data.editor, listOf(data.provider),
                                        onDismiss = { visible = false })
                                }
                            }
                        }
                    }
                }
            }
            val cancel = ui.onNode(hasText("取消") and hasClickAction())
            val confirm = ui.onNode(hasText("确认") and hasClickAction())
            cancel.assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            confirm.assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            val cancelBounds = cancel.fetchSemanticsNode().boundsInRoot
            val confirmBounds = confirm.fetchSemanticsNode().boundsInRoot
            val actionsBefore = node("subagent-config-actions").fetchSemanticsNode().boundsInRoot
            assertEquals(cancelBounds.height, confirmBounds.height, 1f)
            assertEquals(cancelBounds.top, confirmBounds.top, 1f)
            val speed = ui.onNodeWithContentDescription("草稿GPT速度")
            for (mode in listOf(GptSpeedMode.FAST, GptSpeedMode.ULTRA_FAST, GptSpeedMode.NORMAL)) {
                speed.scrollToVisible().assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
                ui.runOnIdle { assertEquals(mode, session.draft.gptSpeedForModel()) }
                ui.onNodeWithText("子代理配置").assertIsDisplayed()
                assertEquals(data.profile, data.repository.snapshot(data.editor.owner).profiles.single())
            }
            ui.onNodeWithText("并行上限（0 为不限）").scrollToVisible().assertIsDisplayed()
            val after = node("subagent-config-actions").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertEquals(actionsBefore.top, after.top, 1f)
            ui.onNodeWithContentDescription("草稿任务分工").scrollToVisible().assertHeightIsAtLeast(48.dp)
            cancel.assertIsDisplayed().performClick()
            ui.runOnIdle { assertFalse(visible) }
        }
        assertEquals(0, fixture.opens.get())
    }

}
