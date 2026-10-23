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

    private var displayRestore: String? = null
    private var originalEffectiveSize: String? = null

    private fun displaySize(command: String): String =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }

    private fun effectiveSize(sizes: String): String =
        checkNotNull(Regex("(?:Override|Physical) size: (\\d+x\\d+)").findAll(sizes).lastOrNull()) { sizes }.groupValues[1]

    override fun setUp() {
        if (name == "testProfileFormKeepsEqualFooterAndScrollsAtPhysicalWidthAndLargeFont") {
            val sizes = displaySize("wm size")
            check(Regex("Physical size: \\d+x\\d+").containsMatchIn(sizes)) { sizes }
            originalEffectiveSize = effectiveSize(sizes)
            displayRestore = Regex("Override size: (\\d+x\\d+)").find(sizes)?.groupValues?.get(1) ?: "reset"
            check(displaySize("wm size 1216x2432").isBlank())
            val applied = displaySize("wm size")
            check(effectiveSize(applied) == "1216x2432") { "Requested physical-width fixture; actual: $applied" }
            instrumentation.waitForIdleSync()
        }
        super.setUp()
        if (displayRestore != null) {
            ui.waitUntil(5_000) {
                var width = 0
                instrumentation.runOnMainSync { width = activity.window.decorView.width }
                width == 1216
            }
        }
    }

    override fun cleanupAfterLegacy() {
        try { super.cleanupAfterLegacy() }
        finally {
            displayRestore?.let { original ->
                check(displaySize("wm size $original").isBlank())
                val restored = displaySize("wm size")
                check(effectiveSize(restored) == originalEffectiveSize) { "Display restore failed: $restored" }
                displayRestore = null
                originalEffectiveSize = null
                instrumentation.waitForIdleSync()
            }
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
        for (dark in listOf(false, true)) {
            mainThread { activity.setContent {
                key(dark) {
                    AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                        applyInterfaceScale = false) {
                        Column { roles.forEach { SubAgentRoleIcon(SubAgentProfile(it, it, it)) } }
                    }
                }
            } }
            roles.forEach { role ->
                node("subagent-role-$role").assertIsDisplayed().assertHasNoClickAction()
                    .assertWidthIsEqualTo(24.dp).assertHeightIsEqualTo(24.dp)
                    .assert(SemanticsMatcher.keyNotDefined(androidx.compose.ui.semantics.SemanticsActions.OnLongClick))
                ui.onNodeWithTag("subagent-role-glyph-$role", useUnmergedTree = true).assertIsDisplayed().assertHasNoClickAction()
                    .assertWidthIsEqualTo(16.dp).assertHeightIsEqualTo(16.dp)
            }
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
        role.assertIsDisplayed().assertHasNoClickAction().assertWidthIsEqualTo(24.dp).assertHeightIsEqualTo(24.dp)
            .performTouchInput { click() }
        ui.onNodeWithText("子代理配置").assertDoesNotExist()
        val speed = node("subagent-gpt-speed-worker").assertIsDisplayed().assertHeightIsAtLeast(24.dp)
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        ui.onNodeWithText("GPT 速度 · 标准", useUnmergedTree = true)
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val label = layouts.single()
        assertEquals(10f, label.layoutInput.style.fontSize.value, .01f)
        assertEquals(16f, label.layoutInput.style.lineHeight.value, .01f)
        val paragraph = label.multiParagraph
        val geometry = "size=${label.size} constraints=${label.layoutInput.constraints} " +
            "density=${label.layoutInput.density} style=${label.layoutInput.style} " +
            "paragraph=${paragraph.width}x${paragraph.height} " +
            "overflowWidth=${label.didOverflowWidth} overflowHeight=${label.didOverflowHeight} " +
            "exceededMaxLines=${paragraph.didExceedMaxLines} lineCount=${label.lineCount} " +
            "line0=${label.getLineLeft(0)}..${label.getLineRight(0)} " +
            "line0Bottom=${label.getLineBottom(0)} ellipsized=${label.isLineEllipsized(0)}"
        assertFalse(geometry, label.hasVisualOverflow)
        val speedBounds = speed.fetchSemanticsNode().boundsInRoot
        val density = label.layoutInput.density.density
        assertTrue("Compact speed does not keep the old 48dp painted block", speedBounds.height < 32f * density)
        speed.performTouchInput { click() }
        awaitControl("compact speed still saves the next mode") {
            data.repository.snapshot(data.editor.owner).profiles.single().gptSpeedForModel() == GptSpeedMode.FAST
        }
        ui.onNodeWithText("GPT 速度 · 快速").assertIsDisplayed()
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
            val actions = node("subagent-config-actions")
            awaitNodeWindowFocus(actions)
            val view = (actions.fetchSemanticsNode().root as androidx.compose.ui.platform.ViewRootForTest).view
            data class WindowFrame(val width: Int, val height: Int, val visible: android.graphics.Rect,
                val imeVisible: Boolean, val imeBottom: Int)
            fun windowFrame() = awaitStableWindowFrame(view) {
                val insets = androidx.core.view.ViewCompat.getRootWindowInsets(view)
                if (insets == null) null else {
                    val visibleFrame = android.graphics.Rect()
                    view.getWindowVisibleDisplayFrame(visibleFrame)
                    WindowFrame(view.rootView.width, view.rootView.height, visibleFrame,
                        insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()),
                        insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom)
                }
            }
            dismissIme(view.rootView)
            awaitImeFrame(actions, visible = false)
            awaitDialogEnter(actions)
            val windowBefore = windowFrame()
            val cancelNode = cancel.fetchSemanticsNode()
            val confirmNode = confirm.fetchSemanticsNode()
            val actionsNode = actions.fetchSemanticsNode()
            val (cancelBounds, confirmBounds, actionsBefore) = ui.runOnIdle {
                Triple(cancelNode.boundsInRoot, confirmNode.boundsInRoot, actionsNode.boundsInRoot)
            }
            val baseline = "dark=$dark fontScale=$fontScale window=$windowBefore cancel=$cancelBounds confirm=$confirmBounds actions=$actionsBefore"
            assertEquals(baseline, cancelBounds.height, confirmBounds.height, 1f)
            assertEquals(baseline, cancelBounds.top, confirmBounds.top, 1f)
            val speed = ui.onNodeWithContentDescription("草稿GPT速度")
            for (mode in listOf(GptSpeedMode.FAST, GptSpeedMode.ULTRA_FAST, GptSpeedMode.NORMAL)) {
                speed.scrollToVisible().assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
                ui.runOnIdle { assertEquals(mode, session.draft.gptSpeedForModel()) }
                ui.onNodeWithText("子代理配置").assertIsDisplayed()
                assertEquals(data.profile, data.repository.snapshot(data.editor.owner).profiles.single())
            }
            ui.onNodeWithText("并行上限（0 为不限）").scrollToVisible().assertIsDisplayed()
            val windowAfter = windowFrame()
            val after = actions.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val geometry = "dark=$dark fontScale=$fontScale window=$windowBefore->$windowAfter actions=$actionsBefore->$after"
            assertEquals("Pure scrolling must keep window and IME unchanged: $geometry", windowBefore, windowAfter)
            assertEquals(geometry, actionsBefore.top, after.top, 1f)
            ui.onNodeWithContentDescription("草稿任务分工").scrollToVisible().assertHeightIsAtLeast(48.dp)
            cancel.assertIsDisplayed().performClick()
            ui.runOnIdle { assertFalse(visible) }
        }
        assertEquals(0, fixture.opens.get())
    }

}
