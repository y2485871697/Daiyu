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

    private fun profileFixture(providerName: String = "Provider", modelName: String = "gpt-6.1-sol",
        defaultSpeed: GptSpeedMode = GptSpeedMode.NORMAL): ProfileFixture {
        val name = "subagent-layout-${UUID.randomUUID()}"
        preferenceNames += name
        val repository = ConversationSubAgentPreferences(instrumentation.targetContext.getSharedPreferences(name, 0))
        val owner = SubAgentConfigKey.Conversation("layout-fixture")
        val provider = SubAgentProvider("provider", providerName, listOf(
            SubAgentModel("gpt-local", "gpt-6.1-sol", displayName = modelName, supportsGptSpeed = true, defaultGptSpeed = defaultSpeed),
            SubAgentModel("other-local", "gemini-3")))
        val profile = SubAgentProfile("worker", "Worker", role = "implementation", providerId = provider.id,
            modelId = "gpt-local", tier = SubAgentTaskTier.entries.first())
        repository.update(owner) { it.copy(enabled = true, profiles = listOf(profile)) }
        lateinit var editor: ConversationSubAgentEditor
        mainThread { editor = ConversationSubAgentEditor(owner, repository,
            { id -> provider.takeIf { it.id == id } }) { true }; editors += editor }
        return ProfileFixture(repository, editor, provider, profile)
    }

    fun testCardDisplaysInheritedSpeedAndFirstClickAdvancesTheActualSavedMode() {
        for (inherited in listOf(GptSpeedMode.FAST, GptSpeedMode.ULTRA_FAST)) {
            val data = profileFixture(defaultSpeed = inherited)
            mainThread { activity.setContent {
                key(data.editor) {
                    AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                        CompositionLocalProvider(LocalConversationAgentStyle provides true,
                            LocalConversationSubAgentEditor provides data.editor) {
                            val config = (data.editor.observe() as? SubAgentEditorState.Loaded)?.config
                            config?.profiles?.singleOrNull()?.let { SubAgentProfileRow(it, listOf(data.provider)) }
                        }
                    }
                }
            } }
            val label = if (inherited == GptSpeedMode.FAST) "快速" else "极速"
            ui.onNodeWithText("GPT 速度 · $label", useUnmergedTree = true).assertIsDisplayed()
            assertTrue(data.repository.snapshot(data.editor.owner).profiles.single().gptSpeedByModel.isEmpty())
            val next = inherited.next()
            node("subagent-gpt-speed-worker").performClick()
            ui.waitUntil(5_000) { data.repository.snapshot(data.editor.owner).profiles.single().gptSpeedForModel() == next }
            val nextLabel = if (next == GptSpeedMode.ULTRA_FAST) "极速" else "标准"
            ui.onNodeWithText("GPT 速度 · $nextLabel", useUnmergedTree = true).assertIsDisplayed()
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testDraftUsesInheritedSpeedAndCancelDoesNotSaveTheCycle() {
        for (inherited in listOf(GptSpeedMode.FAST, GptSpeedMode.ULTRA_FAST)) {
            val data = profileFixture(defaultSpeed = inherited)
            lateinit var session: SubAgentProfileDraftSession
            var visible by mutableStateOf(true)
            mainThread {
                session = checkNotNull(SubAgentProfileDraftSession.open(data.editor, data.profile))
                activity.setContent {
                    key(data.editor) {
                        AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                            if (visible) SubAgentProfileConfigDialog(session, data.editor, listOf(data.provider),
                                onDismiss = { visible = false })
                        }
                    }
                }
            }
            val before = data.repository.snapshot(data.editor.owner)
            assertTrue(before.profiles.single().gptSpeedByModel.isEmpty())
            val label = if (inherited == GptSpeedMode.FAST) "快速" else "极速"
            ui.onNode(hasText(label) and hasAnyAncestor(hasContentDescription("草稿GPT速度")),
                useUnmergedTree = true).assertIsDisplayed()
            ui.onNodeWithContentDescription("草稿GPT速度").scrollToVisible().performClick()
            ui.runOnIdle { assertEquals(inherited.next(), session.draft.gptSpeedForModel()) }
            assertEquals(before, data.repository.snapshot(data.editor.owner))
            ui.runOnIdle { session.dismiss(); visible = false }
            ui.onNodeWithText("子代理配置").assertDoesNotExist()
            assertEquals(before, data.repository.snapshot(data.editor.owner))
        }
        assertEquals(0, fixture.opens.get())
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
        assertEquals(1, label.lineCount)
        assertFalse(geometry, label.isLineEllipsized(0))
        assertTrue(geometry, label.getLineLeft(0) >= 0f)
        assertTrue(geometry, label.getLineRight(0) <= label.size.width.toFloat())
        assertTrue(geometry, label.getLineBottom(0) <= label.size.height.toFloat())
        val speedBounds = speed.fetchSemanticsNode().boundsInRoot
        val density = label.layoutInput.density.density
        assertTrue("Compact speed does not keep the old 48dp painted block", speedBounds.height < 32f * density)
        val divider = node("subagent-gpt-divider-worker").fetchSemanticsNode().boundsInRoot
        val card = node("subagent-card-worker").fetchSemanticsNode().boundsInRoot
        val topGap = speedBounds.top - divider.bottom
        val bottomGap = card.bottom - speedBounds.bottom
        assertEquals("Speed tag has 8dp above it", 8f * density, topGap, 1.5f)
        assertEquals("Speed tag centers between divider and card bottom", topGap, bottomGap, 1.5f)
        val speedText = ui.onNodeWithText("GPT 速度 · 标准", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertEquals("Speed text centers inside its tag", speedBounds.center.y, speedText.center.y, 1f)
        speed.performTouchInput { click() }
        awaitControl("compact speed still saves the next mode") {
            data.repository.snapshot(data.editor.owner).profiles.single().gptSpeedForModel() == GptSpeedMode.FAST
        }
        ui.onNodeWithText("GPT 速度 · 快速").assertIsDisplayed()
        node("subagent-gpt-divider-worker").assertExists()
        ui.onNodeWithContentDescription("Worker模型").assertHeightIsAtLeast(48.dp).performClick()
        node("chat-model-picker").assertIsDisplayed()
        ui.onAllNodes(hasText("无") and hasClickAction() and hasAnyAncestor(hasTestTag("chat-model-picker"))).assertCountEquals(0)
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
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        ui.waitForIdle()
        assertEquals(0, fixture.opens.get())
    }

    fun testModelProviderChipStaysBetweenFlexibleNameAndArrowInBothThemes() {
        val providerName = "Very long provider display name that must never take the arrow slot"
        for (dark in listOf(false, true)) for (fontScale in listOf(1f, 1.3f)) {
            val data = profileFixture(providerName, "Very long model display name that must ellipsize")
            mainThread { activity.setContent {
                key(data.editor, dark, fontScale) {
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                        AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                            applyInterfaceScale = false) {
                            CompositionLocalProvider(LocalConversationAgentStyle provides true,
                                LocalConversationSubAgentEditor provides data.editor) {
                                Box(Modifier.requiredWidth(280.dp)) {
                                    SubAgentProfileRow(data.profile, listOf(data.provider))
                                }
                            }
                        }
                    }
                }
            } }
            val chip = ui.onNodeWithTag("subagent-model-provider-worker", useUnmergedTree = true)
                .assertIsDisplayed().assertTextEquals(providerName).assertHasNoClickAction()
                .assert(SemanticsMatcher.keyNotDefined(androidx.compose.ui.semantics.SemanticsActions.OnLongClick))
            val model = ui.onNodeWithTag("subagent-model-name-worker", useUnmergedTree = true)
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val provider = chip.fetchSemanticsNode().boundsInRoot
            val arrow = ui.onNodeWithTag("subagent-model-arrow-worker", useUnmergedTree = true)
                .assertIsDisplayed().assertWidthIsEqualTo(20.dp).fetchSemanticsNode().boundsInRoot
            val row = ui.onNodeWithContentDescription("Worker模型").assertHeightIsAtLeast(48.dp)
                .assertHasClickAction().assertIsEnabled().fetchSemanticsNode().boundsInRoot
            assertTrue("Model keeps flexible space", model.width > 0f)
            assertTrue("Provider follows model", model.right <= provider.left)
            assertTrue("Arrow follows provider", provider.right <= arrow.left)
            assertTrue("Arrow stays within touch row", arrow.right <= row.right)
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            chip.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            val label = layouts.single()
            val density = label.layoutInput.density.density
            val title = ui.onNodeWithText("Worker", useUnmergedTree = true)
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val gapDp = (model.top - title.bottom) / density
            val centerDistanceDp = (model.center.y - title.center.y) / density
            val geometry = "dark=$dark fontScale=$fontScale title=$title model=$model"
            assertTrue("Model remains below the title: $geometry", gapDp >= -1f / density)
            assertTrue("Title/model gap stays compact: $geometry", gapDp <= 24f + 1f / density)
            assertTrue("Title/model centers stay compact: $geometry",
                centerDistanceDp > 0f && centerDistanceDp <= 44f + 1f / density)
            val configure = ui.onNodeWithContentDescription("配置Worker")
                .assertHasClickAction().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
                .fetchSemanticsNode().boundsInRoot
            assertTrue("Configure and model touch rows do not overlap", configure.bottom <= row.top + 1f)
            assertTrue("Provider chip is bounded to 96dp", provider.width <= 96f * label.layoutInput.density.density + 1f)
            assertEquals(11f, label.layoutInput.style.fontSize.value, .01f)
            assertEquals(ConversationAgentColors(dark).accent, label.layoutInput.style.color)
            assertEquals(1, label.lineCount)
            assertTrue("Long provider is ellipsized", label.isLineEllipsized(0))
        }
    }

    fun testModelProviderChipOmitsUnselectedUnavailableAndBlankProviderNames() {
        for (providerName in listOf("Provider", "   ")) {
            val data = profileFixture(providerName)
            var profile by mutableStateOf(data.profile)
            mainThread { activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    CompositionLocalProvider(LocalConversationAgentStyle provides true,
                        LocalConversationSubAgentEditor provides data.editor) {
                        SubAgentProfileRow(profile, listOf(data.provider), enabled = false)
                    }
                }
            } }
            val chip = ui.onNodeWithTag("subagent-model-provider-worker", useUnmergedTree = true)
            if (providerName.isBlank()) chip.assertDoesNotExist() else chip.assertIsDisplayed()
            ui.onNodeWithContentDescription("Worker模型").assertIsNotEnabled().assertHeightIsAtLeast(48.dp)
            mainThread { profile = data.profile.copy(modelId = "missing-model") }
            ui.onNodeWithText("模型不可用").assertExists()
            chip.assertDoesNotExist()
            mainThread { profile = data.profile.copy(providerId = "", modelId = "") }
            ui.onNodeWithText("无").assertExists()
            chip.assertDoesNotExist()
        }
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
            val delete = ui.onNode(hasText("删除") and hasClickAction())
            val confirm = ui.onNode(hasText("确认") and hasClickAction())
            delete.assertIsDisplayed().assertHeightIsAtLeast(48.dp)
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
            val titleBounds = ui.onNodeWithText("子代理配置").fetchSemanticsNode().boundsInRoot
            val formBounds = node("subagent-config-window").fetchSemanticsNode().boundsInRoot
            assertEquals("Main configuration title must be centered", formBounds.center.x, titleBounds.center.x, 1f)
            // The main model row hides its provider badge without changing picker identity/grouping.
            ui.onAllNodes(hasText(data.provider.name) and hasAnyAncestor(hasContentDescription("草稿模型")),
                useUnmergedTree = true).assertCountEquals(0)
            val speedLabel = ui.onNode(hasText("GPT 速度") and hasAnyAncestor(hasContentDescription("草稿GPT速度")),
                useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val speedValue = ui.onNode(hasText("标准") and hasAnyAncestor(hasContentDescription("草稿GPT速度")),
                useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("Compact row must keep label left of current value", speedLabel.right < speedValue.left)
            assertEquals("Compact row label/value share one line", speedLabel.center.y, speedValue.center.y, 1f)
            val windowBefore = windowFrame()
            val deleteNode = delete.fetchSemanticsNode()
            val confirmNode = confirm.fetchSemanticsNode()
            val actionsNode = actions.fetchSemanticsNode()
            val (deleteBounds, confirmBounds, actionsBefore) = ui.runOnIdle {
                Triple(deleteNode.boundsInRoot, confirmNode.boundsInRoot, actionsNode.boundsInRoot)
            }
            val baseline = "dark=$dark fontScale=$fontScale window=$windowBefore delete=$deleteBounds confirm=$confirmBounds actions=$actionsBefore"
            assertEquals(baseline, deleteBounds.height, confirmBounds.height, 1f)
            assertEquals(baseline, deleteBounds.top, confirmBounds.top, 1f)
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
            delete.assertIsDisplayed().performClick()
            ui.runOnIdle { assertFalse(visible) }
            assertTrue(data.repository.snapshot(data.editor.owner).profiles.isEmpty())
        }
        assertEquals(0, fixture.opens.get())
    }

}
