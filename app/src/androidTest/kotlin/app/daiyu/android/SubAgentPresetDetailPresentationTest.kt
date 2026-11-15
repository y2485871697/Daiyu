package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.delegation.*
import app.daiyu.android.ui.SubAgentSettingsScreen
import app.daiyu.android.ui.app.AgentAppTheme
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

class SubAgentPresetDetailPresentationTest : WiredActivityTestCase() {
    private val preferenceNames = mutableListOf<String>()
    private val model = SubAgentModel("presentation-model", "gpt-presentation", supportsGptSpeed = true)
    private val provider = SubAgentProvider("presentation-provider", "Presentation provider", listOf(model))
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

    private fun openGroup(empty: Boolean, dark: Boolean): Pair<ConversationSubAgentPreferences, SubAgentConfigKey.Preset> {
        val name = "preset-presentation-${UUID.randomUUID()}"
        preferenceNames += name
        val repository = ConversationSubAgentPreferences(instrumentation.targetContext.getSharedPreferences(name, 0))
        val group = repository.addPreset("Presentation group")
        val owner = SubAgentConfigKey.Preset(group.id)
        if (!empty) repository.update(owner) { it.copy(profiles = listOf(
            SubAgentProfile("presentation-worker", "Presentation worker", providerId = provider.id,
                modelId = model.id, tier = SubAgentTaskTier.entries.first()),
        )) }
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                applyInterfaceScale = false) {
                SubAgentSettingsScreen(onBack = {}, repositoryFactory = { repository }, modelSource = source)
            }
        } }
        ui.waitUntil(5_000) {
            ui.onAllNodesWithContentDescription("编辑子代理组${group.name}").fetchSemanticsNodes().size == 1
        }
        val entry = ui.onNodeWithContentDescription("编辑子代理组${group.name}")
        awaitNodeTouchReady(entry)
        entry.performClick()
        ui.waitUntil(5_000) {
            ui.onAllNodes(hasText("添加子代理") and hasClickAction()).fetchSemanticsNodes().size == 1
        }
        return repository to owner
    }

    private fun assertPresentationAndCancelAdd(empty: Boolean, dark: Boolean) {
        val (repository, owner) = openGroup(empty, dark)
        val before = repository.snapshot(owner)
        val add = ui.onNode(hasText("添加子代理") and hasClickAction())
        awaitNodeTouchReady(add)
        add.assertIsEnabled()
        ui.onNodeWithText("Presentation group").assertIsDisplayed()
        ui.onNodeWithText("配置属于此预设组；会话应用时复制，后续修改互不影响。").assertDoesNotExist()
        ui.onNodeWithText("正在读取子代理组配置…").assertDoesNotExist()
        if (empty) {
            ui.onNodeWithContentDescription("Presentation worker更多操作").assertDoesNotExist()
        } else {
            val more = ui.onNodeWithContentDescription("Presentation worker更多操作")
            awaitNodeTouchReady(more)
            more.assertIsDisplayed().assertHasClickAction().assertIsEnabled()
            ui.onNodeWithContentDescription("Presentation worker模型").assertIsDisplayed().assertHasClickAction()
        }
        assertEquals(before, repository.snapshot(owner))
        add.performClick()
        val actions = node("subagent-config-actions")
        awaitNodeWindowFocus(actions)
        actions.assertIsDisplayed()
        val cancel = ui.onNode(hasText("取消") and hasClickAction() and hasAnyAncestor(hasTestTag("subagent-config-actions")))
        awaitNodeTouchReady(cancel)
        cancel.performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("subagent-config-window").fetchSemanticsNodes().isEmpty() }
        assertEquals(before, repository.snapshot(owner))
        ui.onNodeWithText("Presentation group").assertIsDisplayed()
        ui.onNodeWithText("配置属于此预设组；会话应用时复制，后续修改互不影响。").assertDoesNotExist()
    }

    fun testLoadedGroupKeepsTitleFieldsAndAddWithoutExplanation() {
        assertPresentationAndCancelAdd(empty = false, dark = false)
    }

    fun testEmptyDarkGroupKeepsTitleAndAddWithoutExplanation() {
        assertPresentationAndCancelAdd(empty = true, dark = true)
    }
}
