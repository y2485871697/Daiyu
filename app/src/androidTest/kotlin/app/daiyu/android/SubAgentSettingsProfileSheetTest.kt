package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.daiyu.android.ui.components.subagent.ConversationAgentField
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
        val enable = ui.onNode(hasText("启用") and hasAnyAncestor(hasTestTag("subagent-config-body")),
            useUnmergedTree = true).scrollToVisible().fetchSemanticsNode().boundsInRoot
        val modelLabel = ui.onNode(hasText("模型") and hasAnyAncestor(hasContentDescription("草稿模型")),
            useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val switch = formSwitch().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            .fetchSemanticsNode().boundsInRoot
        assertEquals("Enable label shares the model text column", modelLabel.left, enable.left, 1f)
        assertEquals("Enable label centers on the switch", switch.center.y, enable.center.y, 1f)
    }

    private fun nameField(value: String) = ui.onNode(hasSetTextAction() and hasText(value))
    private fun formSwitch() = ui.onNode(isToggleable() and hasAnyAncestor(hasTestTag("subagent-config-window")))
    private fun confirm() = ui.onNode(hasText("确认") and hasClickAction())
    private fun delete() = ui.onNode(hasText("删除") and hasClickAction() and hasAnyAncestor(hasTestTag("subagent-config-actions")))
    private fun dismissNestedChoice() {
        awaitNodeWindowFocus(node("chat-selection-sheet"))
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("chat-selection-sheet").fetchSemanticsNodes().isEmpty() }
        awaitNodeWindowFocus(node("subagent-config-window"))
    }
    private fun dismissForm() {
        ui.onNode(isRoot() and hasAnyDescendant(hasTestTag("subagent-config-window"))).performTouchInput {
            click(androidx.compose.ui.geometry.Offset(2f, 2f))
        }
        ui.waitForIdle()
    }

    fun testProfileFieldsCenterTextAndKeepNameAndParallelBackgroundTransparent() {
        val backdrop = Color(0xff643a82)
        for (dark in listOf(false, true)) for (fontScale in listOf(1f, 1.3f))
            for (direction in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
                mainThread { activity.setContent {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale),
                        LocalLayoutDirection provides direction) {
                        AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                            applyInterfaceScale = false) {
                            Box(Modifier.requiredWidth(280.dp).background(backdrop).padding(12.dp)) {
                                Column {
                                    ConversationAgentField("名称", "子代理 1", {}, enabled = true)
                                    ConversationAgentField("并行上限（0 为不限）", "1", {}, enabled = true,
                                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                                }
                            }
                        }
                    }
                } }
                for (label in listOf("名称", "并行上限（0 为不限）")) {
                    val field = ui.onNodeWithTag("subagent-field:$label", useUnmergedTree = true)
                        .assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                    val frame = field.fetchSemanticsNode().boundsInRoot
                    val editor = ui.onNodeWithTag("subagent-field-editor:$label", useUnmergedTree = true)
                        .fetchSemanticsNode().boundsInRoot
                    val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                    field.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) {
                        assertTrue(it(layouts))
                    }
                    val text = layouts.single()
                    val context = "$label dark=$dark scale=$fontScale direction=$direction"
                    assertEquals("Editor is vertically centered: $context", frame.center.y, editor.center.y, 1f)
                    assertEquals("Actual text layout is vertically centered: $context", frame.center.y,
                        editor.top + text.size.height / 2f, 1f)
                    assertFalse("Text does not overflow: $context", text.hasVisualOverflow)
                    val inset = if (direction == LayoutDirection.Ltr) editor.left - frame.left else frame.right - editor.right
                    assertEquals("Editor keeps Start padding: $context", 12f * text.layoutInput.density.density, inset, 1f)
                    val pixels = field.captureToImage().toPixelMap()
                    val x = if (direction == LayoutDirection.Ltr) pixels.width * 3 / 4 else pixels.width / 4
                    val actual = pixels[x, pixels.height / 2]
                    assertEquals("No field fill red: $context", backdrop.red, actual.red, .01f)
                    assertEquals("No field fill green: $context", backdrop.green, actual.green, .01f)
                    assertEquals("No field fill blue: $context", backdrop.blue, actual.blue, .01f)
                }
            }
    }

    fun testSettingsEditUsesSharedSheetAndNewSwitchButOutsideDiscardsAllChanges() {
        val (repository, owner) = openGroup()
        val before = repository.snapshot(owner)
        openExisting()
        delete().assertIsDisplayed().assertIsEnabled()
        ui.onAllNodes(hasText("取消") and hasClickAction() and hasAnyAncestor(hasTestTag("subagent-config-actions"))).assertCountEquals(0)
        awaitNodeWindowFocus(nameField("Existing worker"))
        nameField("Existing worker").performTextReplacement("Discarded edit")
        formSwitch().scrollToVisible().assertIsOn().performClick().assertIsOff()
        ui.onNodeWithContentDescription("草稿任务分工").scrollToVisible().performClick()
        ui.onNode(hasText("未设置分工") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assertIsDisplayed().performClick()
        ui.onNode(hasText("未设置分工") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assertIsDisplayed().assertIsSelected()
        dismissNestedChoice()
        assertEquals(before, repository.snapshot(owner))
        val capacity = ui.onNode(hasSetTextAction() and hasText("1"))
        capacity.scrollToVisible().performTextReplacement("-1")
        ui.onNodeWithText("请输入 0 或正整数").assertExists()
        confirm().assertIsNotEnabled()
        assertEquals(before, repository.snapshot(owner))
        dismissForm()
        node("subagent-config-window").assertDoesNotExist()
        assertEquals(before, repository.snapshot(owner))
        openExisting()
        nameField("Existing worker").assertExists()
        formSwitch().scrollToVisible().assertIsOn()
        dismissForm()
        assertEquals(0, fixture.opens.get())
    }

    fun testSettingsDeleteRemovesOnlyTheOpenedProfileEvenWhenDraftIsInvalid() {
        val (repository, owner) = openGroup()
        val original = repository.snapshot(owner).profiles.single()
        val neighbor = original.copy(id = "neighbor", name = "Neighbor")
        repository.update(owner) { it.copy(profiles = it.profiles + neighbor) }
        val conversation = SubAgentConfigKey.Conversation("independent-conversation")
        repository.applyPreset(conversation, owner.value)
        val conversationBefore = repository.snapshot(conversation)
        openExisting()
        awaitNodeWindowFocus(nameField("Existing worker"))
        nameField("Existing worker").performTextReplacement("")
        confirm().assertIsNotEnabled()
        delete().assertIsEnabled().performTouchInput { click() }
        awaitControl("profile deleted from selected preset") { repository.snapshot(owner).profiles == listOf(neighbor) }
        node("subagent-config-window").assertDoesNotExist()
        assertTrue(repository.presetExists(owner.value))
        assertEquals(conversationBefore, repository.snapshot(conversation))
        assertEquals(0, fixture.opens.get())
    }

    fun testAddingKeepsCancelAndDoesNotExposeDeleteOrWriteConfiguration() {
        val (repository, owner) = openGroup()
        val before = repository.snapshot(owner)
        ui.onNode(hasText("添加子代理") and hasClickAction()).performClick()
        delete().assertDoesNotExist()
        ui.onNode(hasText("取消") and hasClickAction() and hasAnyAncestor(hasTestTag("subagent-config-actions")))
            .assertIsEnabled().performClick()
        node("subagent-config-window").assertDoesNotExist()
        assertEquals(before, repository.snapshot(owner))
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
        ui.onAllNodes(hasText("无") and hasClickAction() and inPicker).assertCountEquals(0)
        ui.onNode(hasTestTag("chat-model-provider:${provider.id}") and hasText(provider.name) and inPicker).assertExists()
        ui.onNode(hasTestTag("chat-model-option:${model.id}") and hasText(model.displayName) and inPicker
            and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assertIsEnabled().performClick()
        node("chat-model-picker").assertIsDisplayed()
        node("chat-model-option:${model.id}").assertIsSelected()
        assertEquals(before, repository.snapshot(owner))
        dismissNestedChoice()
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
