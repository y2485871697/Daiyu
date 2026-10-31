package app.daiyu.android

import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
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

    fun testThinkingPickerSharesChatGeometryInBothThemes() {
        val choices = listOf(ReasoningEffort.OFF, ReasoningEffort.MINIMAL, ReasoningEffort.LOW,
            ReasoningEffort.MEDIUM, ReasoningEffort.HIGH, ReasoningEffort.XHIGH, ReasoningEffort.MAX)
        for (dark in listOf(false, true)) {
            var mainChat by mutableStateOf(true)
            var changes = 0
            mainThread {
                activity.setContent {
                    AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                        applyInterfaceScale = false) {
                        SubAgentThinkingEffortPickerDialog(true, ReasoningEffort.HIGH, choices,
                            onDismiss = {}, onEffortChange = { changes++ }, mainChat = mainChat)
                    }
                }
            }
            fun geometry(child: Boolean): List<androidx.compose.ui.geometry.Rect> {
                ui.runOnIdle { mainChat = !child }
                val prefix = if (child) "subagent" else "chat"
                awaitUniqueTag("$prefix-thinking-title")
                val title = node("$prefix-thinking-title")
                awaitDialogEnter(title)
                awaitNodeWindowFocus(title)
                title.assertTextEquals("调整思考深度")
                ui.onNodeWithText("仅修改当前草稿，确认后保存").assertDoesNotExist()
                ui.onNodeWithTag("chat-thinking-speed").assertHasNoClickAction()
                return listOf("conversation-agent-sheet", "$prefix-thinking-title", "chat-thinking-speed",
                    "chat-thinking-speed-icon", "$prefix-thinking-value", "thinking-depth-slider").map {
                    ui.onNodeWithTag(it, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                }
            }
            val chat = geometry(child = false)
            val child = geometry(child = true)
            chat.zip(child).forEachIndexed { index, (expected, actual) ->
                assertEquals("left $index", expected.left, actual.left, 1f)
                assertEquals("top $index", expected.top, actual.top, 1f)
                assertEquals("right $index", expected.right, actual.right, 1f)
                assertEquals("bottom $index", expected.bottom, actual.bottom, 1f)
            }
            ui.runOnIdle { assertEquals(0, changes) }
        }
    }

    fun testSettingsRowThinkingPickerUsesNewSliderAndKeepsImmediateSave() = rowThinkingSlider(chatStyle = false)
    fun testConversationRowThinkingPickerUsesNewSliderAndKeepsImmediateSave() = rowThinkingSlider(chatStyle = true)

    private fun rowThinkingSlider(chatStyle: Boolean) {
        val name = "subagent-row-thinking-${UUID.randomUUID()}"
        preferencesName = name
        val repository = ConversationSubAgentPreferences(instrumentation.targetContext.getSharedPreferences(name, 0))
        val owner = SubAgentConfigKey.Conversation("row-fixture")
        val model = SubAgentModel("selection", "arbitrary-api-model")
        val provider = SubAgentProvider("provider", "Provider", listOf(model))
        val profile = SubAgentProfile("profile", "Worker", providerId = provider.id, modelId = model.id)
        repository.update(owner) { it.copy(profiles = listOf(profile)) }
        mainThread {
            val editor = ConversationSubAgentEditor(owner, repository, { id -> provider.takeIf { it.id == id } }) { true }
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    CompositionLocalProvider(LocalConversationAgentStyle provides chatStyle,
                        LocalConversationSubAgentEditor provides editor) {
                        val config = (editor.observe() as? SubAgentEditorState.Loaded)?.config
                        config?.profiles?.singleOrNull()?.let {
                            SubAgentProfileRow(it, listOf(provider), settings = !chatStyle)
                        }
                    }
                }
            }
        }
        if (chatStyle) ui.onNodeWithContentDescription("Worker模型").performTouchInput { longClick() }
        else ui.onNodeWithContentDescription("调整Worker思考深度").performClick()
        awaitUniqueTag("thinking-depth-slider")
        awaitNodeWindowFocus(node("thinking-depth-slider"))
        node("conversation-agent-sheet").assertIsDisplayed()
        ui.onNodeWithText("调整思考深度").assertIsDisplayed()
        ui.onNodeWithText("Worker · 仅影响此代理").assertDoesNotExist()
        node("subagent-thinking-value").assertTextEquals("关闭")
        node("thinking-depth-slider").performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(4f)) }
        node("subagent-thinking-value").assertTextEquals("高")
        awaitControl("row reasoning saved without outer confirmation") {
            repository.snapshot(owner).profiles.single().reasoning == ReasoningEffort.HIGH
        }
        back()
        node("thinking-depth-slider").assertDoesNotExist()
        assertEquals(ReasoningEffort.HIGH, repository.snapshot(owner).profiles.single().reasoning)
        assertEquals(0, fixture.opens.get())
    }

    fun testDraftThinkingSliderSavesOnlyOnConfirmAndRestoresItsRequestedValue() = draftThinkingSlider(chatStyle = true, adding = false)
    fun testSettingsDraftThinkingSliderSavesOnlyOnConfirmAndRestoresItsRequestedValue() = draftThinkingSlider(chatStyle = false, adding = false)
    fun testNewConversationAgentThinkingSliderSavesOnlyOnConfirmAndRestoresItsRequestedValue() = draftThinkingSlider(chatStyle = true, adding = true)
    fun testNewSettingsAgentThinkingSliderSavesOnlyOnConfirmAndRestoresItsRequestedValue() = draftThinkingSlider(chatStyle = false, adding = true)

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun draftThinkingSlider(chatStyle: Boolean, adding: Boolean) {
        val name = "subagent-affordance-${UUID.randomUUID()}"
        preferencesName = name
        val preferences = instrumentation.targetContext.getSharedPreferences(name, 0)
        val repository = ConversationSubAgentPreferences(preferences)
        val owner = SubAgentConfigKey.Conversation("fixture-conversation")
        val model = SubAgentModel("selection", "arbitrary-api-model")
        val provider = SubAgentProvider("provider", "Provider", listOf(model))
        val profile = SubAgentProfile("profile", "Worker", providerId = provider.id, modelId = model.id)
        repository.update(owner) { if (adding) it else it.copy(profiles = listOf(profile)) }
        lateinit var editor: ConversationSubAgentEditor
        var active by mutableStateOf<SubAgentProfileDraftSession?>(null)
        fun openDraft(newProfile: Boolean) {
            mainThread {
                active = checkNotNull(SubAgentProfileDraftSession.open(editor,
                    if (newProfile) null else repository.snapshot(owner).profiles.single())).also {
                    if (newProfile) it.selectModel(ModelFeatureSelection(true, provider.id, model.id))
                }
            }
        }
        fun openThinking(title: String) {
            val titleNode = ui.onNodeWithText(title)
            awaitNodeWindowFocus(titleNode)
            awaitDialogEnter(titleNode)
            val semantic = titleNode.fetchSemanticsNode()
            val view = (semantic.root as androidx.compose.ui.platform.ViewRootForTest).view
            awaitStableWindowFrame(view) {
                val coordinates = semantic.layoutInfo.coordinates
                if (coordinates.isAttached && coordinates.size.width > 0 && coordinates.size.height > 0) {
                    val position = coordinates.positionInRoot()
                    listOf(view.width.toFloat(), view.height.toFloat(), position.x, position.y,
                        coordinates.size.width.toFloat(), coordinates.size.height.toFloat())
                } else null
            }
            ui.onNodeWithContentDescription("草稿思考深度").scrollToVisible().assertIsDisplayed().assertIsEnabled().performClick()
            awaitUniqueTag("thinking-depth-slider")
            awaitNodeWindowFocus(node("thinking-depth-slider"))
            node("thinking-depth-slider").scrollToVisible().assertIsDisplayed()
            ui.onNodeWithText("调整思考深度").assertIsDisplayed()
            ui.onNodeWithText("仅修改当前草稿，确认后保存").assertDoesNotExist()
            ui.onNode(hasTestTag("conversation-agent-sheet") and hasAnyDescendant(hasTestTag("thinking-depth-slider")),
                useUnmergedTree = true).assertIsDisplayed()
            val root = checkNotNull(node("thinking-depth-slider").fetchSemanticsNode().root)
            val inPicker = SemanticsMatcher("thinking picker root") { it.root === root }
            ui.onAllNodes(inPicker and hasContentDescription("返回"), useUnmergedTree = true).assertCountEquals(0)
            ui.onAllNodes(inPicker and hasContentDescription("关闭"), useUnmergedTree = true).assertCountEquals(0)
            ui.onAllNodes(inPicker and hasText("关闭") and hasClickAction()).assertCountEquals(0)
        }
        fun select(index: Int, label: String) {
            node("thinking-depth-slider").performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(index.toFloat())) }
            node("subagent-thinking-value").assertTextEquals(label)
            val range = node("thinking-depth-slider").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
            assertEquals(0f..6f, range.range)
            assertEquals(5, range.steps)
            assertEquals(index.toFloat(), range.current)
        }
        fun closeThinking(title: String) {
            back()
            node("thinking-depth-slider").assertDoesNotExist()
            awaitNodeWindowFocus(ui.onNodeWithText(title))
        }
        mainThread {
            editor = ConversationSubAgentEditor(owner, repository, { id -> provider.takeIf { it.id == id } }) { true }
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    CompositionLocalProvider(LocalConversationAgentStyle provides chatStyle) {
                        active?.let { session -> SubAgentProfileConfigDialog(session, editor, listOf(provider), onDismiss = { active = null }) }
                    }
                }
            }
        }
        openDraft(newProfile = adding)
        val initialTitle = if (adding) "添加子代理" else "子代理配置"
        ui.onNodeWithText(initialTitle).assertIsDisplayed()
        ui.onAllNodes(hasText("启用") and hasAnyAncestor(hasTestTag("subagent-config-body")),
            useUnmergedTree = true).assertCountEquals(0)
        ui.onAllNodes(isToggleable() and hasAnyAncestor(hasTestTag("subagent-config-body")),
            useUnmergedTree = true).assertCountEquals(0)
        ui.onNodeWithText(if (adding) "取消" else "删除").assertIsDisplayed()
        ui.onNodeWithText("确认").assertIsDisplayed()
        if (chatStyle) noHeaderNavigation()
        val initialPreferences = preferences.all.toMap()
        openThinking(initialTitle)
        node("subagent-thinking-value").assertTextEquals("关闭")
        val initialRange = node("thinking-depth-slider").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(0f, initialRange.current)
        assertEquals(0f..6f, initialRange.range)
        node("thinking-depth-slider").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        select(4, "高")
        assertEquals(if (adding) 0 else 1, repository.snapshot(owner).profiles.size)
        assertNull(repository.snapshot(owner).profiles.singleOrNull()?.reasoning)
        assertEquals(initialPreferences, preferences.all.toMap())
        closeThinking(initialTitle)
        if (adding) ui.onNodeWithText("取消").performClick() else back()
        ui.runOnIdle { assertNull(active) }
        assertEquals(initialPreferences, preferences.all.toMap())

        openDraft(newProfile = adding)
        openThinking(initialTitle)
        node("subagent-thinking-value").assertTextEquals("关闭")
        select(4, "高")
        closeThinking(initialTitle)
        assertEquals(initialPreferences, preferences.all.toMap())
        ui.onNodeWithText("确认").assertIsEnabled().performClick()
        awaitControl("confirmed child reasoning") { repository.snapshot(owner).profiles.singleOrNull()?.reasoning == ReasoningEffort.HIGH }
        ui.waitForIdle()
        ui.runOnIdle { assertNull(active) }
        assertTrue(repository.snapshot(owner).profiles.single().toJson().getBoolean("enabled"))
        val savedHigh = preferences.all.toMap()

        openDraft(newProfile = false)
        openThinking("子代理配置")
        node("subagent-thinking-value").assertTextEquals("高")
        select(0, "关闭")
        assertEquals(ReasoningEffort.HIGH, repository.snapshot(owner).profiles.single().reasoning)
        assertEquals(savedHigh, preferences.all.toMap())
        closeThinking("子代理配置")
        back()
        ui.runOnIdle { assertNull(active) }
        assertEquals(savedHigh, preferences.all.toMap())

        openDraft(newProfile = false)
        openThinking("子代理配置")
        node("subagent-thinking-value").assertTextEquals("高")
        select(0, "关闭")
        closeThinking("子代理配置")
        assertEquals(savedHigh, preferences.all.toMap())
        ui.onNodeWithText("确认").assertIsEnabled().performClick()
        awaitControl("confirmed explicit off") { repository.snapshot(owner).profiles.singleOrNull()?.reasoning == ReasoningEffort.OFF }
        ui.waitForIdle()
        ui.runOnIdle { assertNull(active) }
        openDraft(newProfile = false)
        openThinking("子代理配置")
        node("subagent-thinking-value").assertTextEquals("关闭")
        val offRange = node("thinking-depth-slider").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(0f, offRange.current)
        assertEquals(0f..6f, offRange.range)
        closeThinking("子代理配置")
        back()
        assertEquals(ReasoningEffort.OFF, repository.snapshot(owner).profiles.single().reasoning)
        assertEquals(1, repository.snapshot(owner).profiles.size)
        assertEquals(0, fixture.opens.get())
    }
}
