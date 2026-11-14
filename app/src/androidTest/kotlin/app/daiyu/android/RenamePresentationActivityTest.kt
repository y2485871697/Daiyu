package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.components.*
import app.daiyu.android.delegation.*
import app.daiyu.android.model.GptSpeedMode
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class RenamePresentationActivityTest : WiredActivityTestCase() {
    private val saves = AtomicInteger()
    private val dismissals = AtomicInteger()
    private var savedName: String? = null

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
        val editor: ConversationSubAgentEditor, val provider: SubAgentProvider) {
        var enabled by mutableStateOf(true)
    }

    private fun profileFixture(defaultSpeed: GptSpeedMode = GptSpeedMode.NORMAL): ProfileFixture {
        val name = "rename-settings-${UUID.randomUUID()}"
        preferenceNames += name
        val repository = ConversationSubAgentPreferences(instrumentation.targetContext.getSharedPreferences(name, 0))
        val owner = SubAgentConfigKey.Conversation("settings-fixture")
        val provider = SubAgentProvider("provider", "主模型服务商名称很长也不会挤压模型", listOf(
            SubAgentModel("gpt-local", "gpt-6.1-sol", displayName = "gpt-6.1-sol-very-long-model-name",
                supportsGptSpeed = true, defaultGptSpeed = defaultSpeed),
            SubAgentModel("other-local", "gemini-3")))
        val profile = SubAgentProfile("worker", "Worker", role = "implementation", providerId = provider.id,
            modelId = "gpt-local", tier = SubAgentTaskTier.entries.first())
        repository.update(owner) { it.copy(enabled = true, profiles = listOf(profile)) }
        lateinit var data: ProfileFixture
        mainThread {
            val editor = ConversationSubAgentEditor(owner, repository,
                { id -> provider.takeIf { it.id == id } }) { data.enabled }
            editors += editor
            data = ProfileFixture(repository, editor, provider)
        }
        return data
    }

    private fun mountProfile(data: ProfileFixture, width: Int = 300, fontScale: Float = 1f) {
        val visit = Any()
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) { key(visit) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale),
                    LocalConversationSubAgentEditor provides data.editor) {
                    val config = (data.editor.observe() as? SubAgentEditorState.Loaded)?.config
                    Box(Modifier.width(width.dp)) {
                        config?.profiles?.singleOrNull()?.let {
                            SubAgentProfileRow(it, listOf(data.provider), enabled = data.enabled, settings = true)
                        }
                    }
                }
            } }
        } }
        ui.waitUntil(5_000) {
            ui.onAllNodesWithTag("subagent-setting-model-name", useUnmergedTree = true).fetchSemanticsNodes().size == 1
        }
        ui.waitForIdle()
    }

    private fun mount(kind: Int, dark: Boolean = false) {
        val visit = Any()
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                applyInterfaceScale = false) { key(visit) {
                var open by remember { mutableStateOf(true) }
                var name by remember { mutableStateOf("原名称") }
                val save: (String) -> Unit = { savedName = it; saves.incrementAndGet(); open = false }
                val dismiss: () -> Unit = { dismissals.incrementAndGet(); open = false }
                if (open) when (kind) {
                    0 -> WorkspaceNameDialog("重命名", "原名称", "文件夹名称", "保存", save, dismiss)
                    1 -> DaiyuTextInputDialog("重命名", "原名称", "名称", "保存", save, dismiss,
                        preview = true, nameStyle = true)
                    else -> SubAgentPresetNameDialog(if (kind == 4) "添加子代理组" else "重命名", name, { name = it }, name.trim().isNotBlank(),
                        dismiss, { save(name) }, fieldHint = if (kind == 2) "组名称" else "名称")
                }
            } }
        } }
        awaitUniqueTag("prompt-input")
        awaitNodeWindowFocus(node("prompt-input"))
        awaitDialogEnter(node("prompt-input"))
        ui.waitForIdle()
    }

    fun testAllRenameSurfacesShareFieldButtonsAndNoHeaderClose() {
        for (dark in listOf(false, true)) {
            var expectedWidth = 0f
            var expectedHeight = 0f
            for (kind in 0..4) {
                mount(kind, dark)
                node("chat-selection-sheet").assertExists()
                ui.onNodeWithText(if (kind == 4) "添加子代理组" else "重命名").assertIsDisplayed()
                ui.onAllNodesWithContentDescription("关闭").assertCountEquals(0)
                ui.onNodeWithText("名称").assertDoesNotExist()
                ui.onNodeWithText("组名称").assertDoesNotExist()
                node("prompt-input").assertTextContains("原名称")
                val field = node("prompt-input").fetchSemanticsNode().boundsInRoot
                if (kind == 0) { expectedWidth = field.width; expectedHeight = field.height }
                else { assertEquals(expectedWidth, field.width, 1f); assertEquals(expectedHeight, field.height, 1f) }
                val cancel = node("dialog-cancel").assertHeightIsAtLeast(48.dp).fetchSemanticsNode().boundsInRoot
                val confirm = node("dialog-confirm").assertHeightIsAtLeast(48.dp).fetchSemanticsNode().boundsInRoot
                assertEquals(cancel.width, confirm.width, 1f)
                assertEquals(cancel.height, confirm.height, 1f)
                assertEquals(cancel.top, confirm.top, 1f)
                assertTrue(cancel.right < confirm.left)
            }
        }
    }

    fun testControlledNamesRetainLimitsAndOnlyConfirmSaves() {
        for (kind in 0..4) {
            mount(kind)
            val beforeSave = saves.get()
            val beforeDismiss = dismissals.get()
            node("prompt-input").performTextReplacement("取消草稿")
            node("dialog-cancel").performClick()
            ui.waitForIdle()
            assertEquals(beforeSave, saves.get())
            assertEquals(beforeDismiss + 1, dismissals.get())
            mount(kind)
            if (kind >= 2) {
                node("prompt-input").performTextReplacement(" ")
                node("dialog-confirm").assertIsNotEnabled()
                node("prompt-input").performTextReplacement("字".repeat(90))
                node("prompt-input").assertTextEquals("字".repeat(80))
            }
            node("prompt-input").performTextReplacement("新名称")
            node("dialog-confirm").assertIsEnabled().performClick()
            ui.waitForIdle()
            assertEquals(beforeSave + 1, saves.get())
            assertEquals("新名称", savedName)
        }
    }

    fun testModelAndProviderStackWithoutCompetingWithSpeedAtNarrowWidths() {
        val data = profileFixture()
        for (width in listOf(260, 300)) for (fontScale in listOf(1f, 1.3f)) {
            mountProfile(data, width, fontScale)
            val model = ui.onNodeWithTag("subagent-setting-model-name", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val provider = ui.onNodeWithTag("subagent-setting-model-provider", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val modelRow = ui.onNodeWithContentDescription("Worker模型").fetchSemanticsNode().boundsInRoot
            val speed = node("subagent-gpt-speed-worker").assertHeightIsAtLeast(48.dp).fetchSemanticsNode().boundsInRoot
            val role = ui.onNodeWithContentDescription("选择Worker职责").fetchSemanticsNode().boundsInRoot
            assertEquals(model.left, provider.left, 1f)
            assertEquals(model.width, provider.width, 1f)
            assertTrue(model.bottom <= provider.top)
            assertTrue("Speed is a full row below the model, not a trailing control", modelRow.bottom <= speed.top)
            assertTrue(speed.bottom <= role.top)
            assertEquals(modelRow.left, speed.left, 1f)
            assertEquals(modelRow.width, speed.width, 1f)
            ui.onNode(hasText("GPT 速度") and hasAnyAncestor(hasTestTag("subagent-gpt-speed-worker")),
                useUnmergedTree = true).assertIsDisplayed()
            assertTrue("Model text must retain useful horizontal space", model.width > 80f * activity.resources.displayMetrics.density)
            assertTrue("Single-line model text must not become a vertical column", model.width > model.height * 2f)
        }
    }

    fun testDedicatedSpeedRowCyclesSavedModeAndHidesForNonGptModel() {
        for (inherited in GptSpeedMode.entries) {
            val data = profileFixture(inherited)
            mountProfile(data)
            val speed = node("subagent-gpt-speed-worker").assertIsEnabled()
            val initial = data.repository.snapshot(data.editor.owner)
            assertTrue(initial.profiles.single().gptSpeedByModel.isEmpty())
            speed.assertTextContains(subAgentGptSpeedDisplayName(inherited))
            speed.performClick()
            val next = inherited.next()
            awaitControl("dedicated speed row saves the next inherited mode") {
                data.repository.snapshot(data.editor.owner).profiles.single().gptSpeedForModel() == next
            }
            node("subagent-gpt-speed-worker").assertTextContains(subAgentGptSpeedDisplayName(next))
            val saved = data.repository.snapshot(data.editor.owner)
            assertEquals(initial.profiles.single().copy(gptSpeedByModel = mapOf(
                SubAgentProfile.modelReasoningKey("provider", "gpt-local") to next)), saved.profiles.single())
            assertEquals(next, ConversationSubAgentPreferences(instrumentation.targetContext
                .getSharedPreferences(preferenceNames.last(), 0)).snapshot(data.editor.owner).profiles.single().gptSpeedForModel())
            mountProfile(data)
            node("subagent-gpt-speed-worker").assertTextContains(subAgentGptSpeedDisplayName(next))
            ui.runOnIdle { data.enabled = false }
            node("subagent-gpt-speed-worker").assertIsNotEnabled()
            assertEquals(saved, data.repository.snapshot(data.editor.owner))
            ui.runOnIdle { data.enabled = true }
            ui.onNodeWithContentDescription("Worker模型").assertIsEnabled().performClick()
            awaitUniqueTag("chat-model-picker")
            node("chat-model-option:other-local").performClick()
            awaitControl("non-GPT model selected from actual picker") {
                data.repository.snapshot(data.editor.owner).profiles.single().modelId == "other-local"
            }
            awaitNodeWindowFocus(node("chat-model-picker"))
            dismissIme()
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            ui.waitUntil(5_000) { ui.onAllNodesWithTag("chat-model-picker").fetchSemanticsNodes().isEmpty() }
            node("subagent-gpt-speed-worker").assertDoesNotExist()
            ui.onAllNodes(hasContentDescription("GPT 速度", substring = true)).assertCountEquals(0)
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testPresetOverflowHasOnlyRenameAndDeleteAndAddKeepsConfigurationForm() {
        val repository = ConversationSubAgentPreferences(activity.getSharedPreferences("subagent_configuration", 0))
        val preset = repository.addPreset("Menu ${UUID.randomUUID()}")
        val profile = SubAgentProfile("menu-worker", "Menu Worker", role = "implementation")
        repository.update(SubAgentConfigKey.Preset(preset.id)) { it.copy(profiles = listOf(profile)) }
        val before = repository.snapshot(SubAgentConfigKey.Preset(preset.id))
        try {
            openSettings()
            reveal("settings-row:子代理", "设置").performClick()
            ui.onNodeWithContentDescription("编辑子代理组${preset.name}").assertIsEnabled().performClick()
            ui.onNodeWithContentDescription("${profile.name}更多操作").assertIsEnabled().performClick()
            ui.onNodeWithText("配置").assertDoesNotExist()
            ui.onNode(hasText("重命名") and hasClickAction()).assertIsDisplayed()
            ui.onNode(hasText("删除") and hasClickAction()).assertIsDisplayed()
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            ui.waitForIdle()
            ui.onNode(hasText("添加子代理") and hasClickAction()).assertIsEnabled().performClick()
            awaitUniqueTag("subagent-config-body")
            node("subagent-config-body").assertExists()
            assertEquals(before, repository.snapshot(SubAgentConfigKey.Preset(preset.id)))
        } finally { repository.removePreset(preset.id) }
    }

    fun testAddGroupUsesSharedNameSheetAndCreatesOnlyAfterConfirm() {
        val repository = ConversationSubAgentPreferences(activity.getSharedPreferences("subagent_configuration", 0))
        val before = repository.presets()
        val createdName = "Shared sheet ${UUID.randomUUID()}"
        try {
            openSettings()
            reveal("settings-row:子代理", "设置").performClick()
            ui.onNode(hasText("添加子代理组") and hasClickAction()).performClick()
            awaitUniqueTag("prompt-input")
            awaitNodeWindowFocus(node("prompt-input"))
            awaitDialogEnter(node("prompt-input"))
            node("chat-selection-sheet").assertExists()
            node("dialog-confirm").assertIsNotEnabled()
            node("prompt-input").performTextReplacement(" ")
            node("dialog-confirm").assertIsNotEnabled()
            node("prompt-input").performTextReplacement("字".repeat(90))
            node("prompt-input").assertTextEquals("字".repeat(80))
            node("dialog-cancel").performClick()
            ui.waitForIdle()
            assertEquals(before, repository.presets())
            ui.onNode(hasText("添加子代理组") and hasClickAction()).performClick()
            awaitUniqueTag("prompt-input")
            awaitNodeWindowFocus(node("prompt-input"))
            awaitDialogEnter(node("prompt-input"))
            node("prompt-input").performTextReplacement(createdName)
            node("dialog-confirm").assertIsEnabled().performClick()
            awaitControl("new group stored only after shared sheet confirmation") {
                repository.presets().count { it.name == createdName } == 1
            }
            assertEquals(before.size + 1, repository.presets().size)
            node("prompt-input").assertDoesNotExist()
        } finally { repository.presets().filter { it.name == createdName }.forEach { repository.removePreset(it.id) } }
    }
}
