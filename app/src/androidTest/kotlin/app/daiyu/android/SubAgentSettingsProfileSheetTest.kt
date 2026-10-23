package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.delegation.*
import app.daiyu.android.ui.SubAgentSettingsScreen
import app.daiyu.android.ui.app.AgentAppTheme
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

/** Settings must open the same themed profile sheet, without writing a draft before confirmation. */
class SubAgentSettingsProfileSheetTest : WiredActivityTestCase() {
    private val preferenceNames = mutableListOf<String>()
    private val model = SubAgentModel("settings-model", "gpt-settings-model", supportsGptSpeed = true)
    private val provider = SubAgentProvider("settings-provider", "Settings provider", listOf(model))
    private val source = object : SubAgentModelSource {
        override val providers = MutableStateFlow(listOf(provider))
    }

    override fun cleanupFixture() {
        try { super.cleanupFixture() }
        finally {
            preferenceNames.forEach { instrumentation.targetContext.deleteSharedPreferences(it) }
            preferenceNames.clear()
        }
    }

    private fun openGroup(dark: Boolean = false): Pair<ConversationSubAgentPreferences, SubAgentConfigKey.Preset> {
        val name = "settings-profile-sheet-${UUID.randomUUID()}"
        preferenceNames += name
        val repository = ConversationSubAgentPreferences(instrumentation.targetContext.getSharedPreferences(name, 0))
        val group = repository.addPreset("Sheet preset")
        val owner = SubAgentConfigKey.Preset(group.id)
        val profile = SubAgentProfile("existing", "Existing worker", providerId = provider.id, modelId = model.id,
            tier = SubAgentTaskTier.entries.first())
        repository.update(owner) { it.copy(profiles = listOf(profile)) }
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                applyInterfaceScale = false) {
                SubAgentSettingsScreen(onBack = {}, repositoryFactory = { repository }, modelSource = source)
            }
        } }
        ui.waitUntil(5_000) {
            ui.onAllNodesWithContentDescription("编辑子代理组${group.name}").fetchSemanticsNodes().size == 1
        }
        ui.onNodeWithContentDescription("编辑子代理组${group.name}").performClick()
        ui.waitUntil(5_000) {
            ui.onAllNodes(hasText("添加子代理") and hasClickAction()).fetchSemanticsNodes().size == 1
        }
        return repository to owner
    }

    private fun openExisting() {
        val more = ui.onNodeWithContentDescription("Existing worker更多操作")
        awaitNodeWindowFocus(more)
        dismissIme()
        more.performClick()
        ui.onNode(hasText("配置") and hasClickAction()).performClick()
        assertSheet()
    }

    private fun assertSheet() {
        node("conversation-agent-sheet").assertIsDisplayed()
        node("subagent-config-window").assertIsDisplayed()
        node("subagent-config-body").assertExists()
        node("subagent-config-actions").assertIsDisplayed()
        ui.onNodeWithContentDescription("草稿模型").assertExists()
        ui.onNodeWithContentDescription("草稿GPT速度").assertExists()
        ui.onNodeWithContentDescription("草稿职责").assertExists()
        ui.onNodeWithContentDescription("草稿任务分工").assertExists()
        ui.onNodeWithContentDescription("草稿思考深度").assertExists()
    }

    private fun nameField(value: String) = ui.onNode(hasSetTextAction() and hasText(value))
    private fun formSwitch() = ui.onNode(isToggleable() and hasAnyAncestor(hasTestTag("subagent-config-window")))
    private fun confirm() = ui.onNode(hasText("确认") and hasClickAction())
    private fun cancel() = ui.onNode(hasText("取消") and hasClickAction())

    fun testSettingsEditUsesSharedSheetAndNewSwitchButCancelDiscardsAllChanges() {
        val (repository, owner) = openGroup()
        val before = repository.snapshot(owner)
        openExisting()
        awaitNodeWindowFocus(nameField("Existing worker"))
        nameField("Existing worker").performTextReplacement("Discarded edit")
        formSwitch().scrollToVisible().assertIsOn().performClick().assertIsOff()
        ui.onNodeWithContentDescription("草稿任务分工").scrollToVisible().performClick()
        ui.onNode(hasText("未设置分工") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assertIsDisplayed().performClick()
        val capacity = ui.onNode(hasSetTextAction() and hasText("1"))
        capacity.scrollToVisible().performTextReplacement("-1")
        ui.onNodeWithText("请输入 0 或正整数").assertExists()
        confirm().assertIsNotEnabled()
        assertEquals(before, repository.snapshot(owner))
        cancel().performClick()
        node("subagent-config-window").assertDoesNotExist()
        assertEquals(before, repository.snapshot(owner))
        openExisting()
        nameField("Existing worker").assertExists()
        formSwitch().scrollToVisible().assertIsOn()
        cancel().performClick()
        assertEquals(0, fixture.opens.get())
    }

    fun testSettingsAddAndEditConfirmPersistOnlyTheSelectedPresetInDarkTheme() {
        val (repository, owner) = openGroup(dark = true)
        val before = repository.snapshot(owner)
        ui.onNode(hasText("添加子代理") and hasClickAction()).performClick()
        node("conversation-agent-sheet").assertIsDisplayed()
        node("subagent-config-actions").assertIsDisplayed()
        confirm().assertIsNotEnabled()
        awaitNodeWindowFocus(nameField("子代理 2"))
        nameField("子代理 2").performTextReplacement("Added worker")
        ui.onNodeWithContentDescription("草稿模型").scrollToVisible().performClick()
        // The nested picker inherits the same style scope as the settings profile sheet.
        val inPicker = hasAnyAncestor(hasTestTag("chat-model-picker"))
        ui.onNode(hasTestTag("chat-model-provider:${provider.id}") and hasText(provider.name) and inPicker).assertExists()
        ui.onNode(hasTestTag("chat-model-option:${model.id}") and hasText(model.displayName) and inPicker
            and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assertIsEnabled().performClick()
        assertSheet()
        ui.waitUntil(5_000) {
            confirm().fetchSemanticsNode().config.getOrNull(SemanticsProperties.Disabled) == null
        }
        assertEquals(before, repository.snapshot(owner))
        confirm().performClick()
        ui.waitUntil(5_000) { repository.snapshot(owner).profiles.size == 2 }
        node("subagent-config-window").assertDoesNotExist()
        val added = repository.snapshot(owner).profiles.single { it.name == "Added worker" }
        ui.onNode(hasContentDescription("${added.name}模型") and hasText(provider.name) and hasText(model.displayName))
            .assertExists()
        assertEquals(provider.id, added.providerId)
        assertEquals(model.id, added.modelId)
        assertEquals(before.profiles.single(), repository.snapshot(owner).profiles.first())

        openExisting()
        awaitNodeWindowFocus(nameField("Existing worker"))
        nameField("Existing worker").performTextReplacement("Saved edit")
        formSwitch().scrollToVisible().assertIsOn().performClick().assertIsOff()
        confirm().assertIsEnabled().performClick()
        ui.waitUntil(5_000) { repository.snapshot(owner).profiles.any { it.name == "Saved edit" } }
        val edited = repository.snapshot(owner).profiles.single { it.id == "existing" }
        assertFalse(edited.enabled)
        assertEquals(before.profiles.single().copy(name = "Saved edit", enabled = false), edited)
        assertEquals(added, repository.snapshot(owner).profiles.single { it.id == added.id })
        assertEquals(listOf(owner.value), repository.presets().map { it.id })
        assertEquals(0, fixture.opens.get())
    }
}
