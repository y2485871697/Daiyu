package app.daiyu.android

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.delegation.*
import kotlinx.coroutines.flow.MutableStateFlow
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.components.ConversationCollaborationDialog
import java.util.UUID

class CollaborationEmptyStateTest : WiredActivityTestCase() {
    private var density = 1f
    private var rootsBeforeShow = ""

    private fun windowRoots(): String {
        if (android.os.Build.VERSION.SDK_INT < 29) return "unavailable before API29"
        var roots = ""
        instrumentation.runOnMainSync {
            roots = runCatching {
                android.view.inspector.WindowInspector.getGlobalWindowViews().joinToString { view ->
                    val params = view.layoutParams as? android.view.WindowManager.LayoutParams
                    "${view.javaClass.simpleName}@${System.identityHashCode(view)}" +
                        " attached=${view.isAttachedToWindow} visible=${view.visibility} focused=${view.hasWindowFocus()}" +
                        " viewAlpha=${view.alpha} size=${view.width}x${view.height}" +
                        " type=${params?.type} flags=${params?.flags} alpha=${params?.alpha} dim=${params?.dimAmount}"
                }
            }.fold({ it }, { "root snapshot failed: $it" })
        }
        return roots
    }

    private fun show(id: String?, repository: ConversationSubAgentPreferences, dark: Boolean,
        fontScale: Float = 1f, modelSource: SubAgentModelSource = SubAgentModelSource.Empty) {
        rootsBeforeShow = runCatching { windowRoots() }.fold({ it }, { "root snapshot failed: $it" })
        mainThread { activity.setContent {
            val currentDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(currentDensity.density, fontScale)) {
                AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT), applyInterfaceScale = false) {
                    density = LocalDensity.current.density
                    var open by remember(id, dark, fontScale) { mutableStateOf(true) }
                    ConversationCollaborationDialog(open, id, { open = false }, repositoryFactory = { repository }, modelSource = modelSource)
                }
            }
        } }
    }

    private fun assertSpaciousWithBottomFooter(tag: String) {
        node(tag).assertIsDisplayed()
        val card = node("subagent-collaboration-card").fetchSemanticsNode().boundsInRoot
        val footer = node("subagent-collaboration-footer").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Empty sheet retains its intended height: ${card.height / density}", card.height / density in 350f..385f)
        val message = node(tag).fetchSemanticsNode().boundsInRoot
        assertTrue("Whitespace belongs between message and footer", (footer.top - message.bottom) / density > 60f)
        assertTrue("Footer stays within sheet", footer.bottom <= card.bottom + 1f)
        assertTrue("No unused area after footer", (card.bottom - footer.bottom) / density < 32f)
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun assertPrimaryFooterWithoutCross(dark: Boolean, label: String) {
        ui.onNodeWithContentDescription("关闭子代理设置", useUnmergedTree = true).assertDoesNotExist()
        val button = node("subagent-collaboration-done").assertIsEnabled().assertHeightIsEqualTo(48.dp)
        awaitNodeWindowFocus(button)
        instrumentation.uiAutomation.waitForIdle(100, 5_000)
        val pixels = captureScreenNode(button).toPixelMap()
        val expectedPrimary = Color(if (dark) 0xFFB6A4C7 else 0xFF8E7C9E)
        val expectedOnPrimary = Color(if (dark) 0xFF211735 else 0xFFFFFFFF)
        val background = pixels[(12 * density).toInt(), pixels.height / 2]
        val mismatch = kotlin.math.abs(expectedPrimary.red - background.red) > .01f ||
            kotlin.math.abs(expectedPrimary.green - background.green) > .01f ||
            kotlin.math.abs(expectedPrimary.blue - background.blue) > .01f
        val captureDiagnostic = if (mismatch) runCatching {
            val roots = windowRoots()
            val semantics = button.fetchSemanticsNode()
            val bounds = semantics.boundsInRoot
            val composeView = (semantics.root as androidx.compose.ui.platform.ViewRootForTest).view
            var rootIdentity = ""
            instrumentation.runOnMainSync {
                rootIdentity = runCatching { System.identityHashCode(composeView.rootView).toString() }
                    .fold({ it }, { "root identity failed: $it" })
            }
            val windowPixel = if (android.os.Build.VERSION.SDK_INT >= 28) runCatching {
                val windowPixels = button.captureToImage().toPixelMap()
                "${windowPixels[(12 * density).toInt(), windowPixels.height / 2]}" +
                    " size=${windowPixels.width}x${windowPixels.height}"
            }.fold({ it }, { "capture failed: $it" }) else "unavailable before API28"
            " windowPixel=$windowPixel screenSize=${pixels.width}x${pixels.height}" +
                " sample=${(12 * density).toInt()},${pixels.height / 2} bounds=$bounds root=$rootIdentity" +
                " rootsBeforeShow=[$rootsBeforeShow] rootsAtCapture=[$roots]"
        }.fold({ it }, { " diagnostic failed: $it" }) else ""
        val colorDiagnostic = "dark=$dark expected=${expectedPrimary} screen=$background$captureDiagnostic"
        assertEquals(colorDiagnostic, expectedPrimary.red, background.red, .01f)
        assertEquals(colorDiagnostic, expectedPrimary.green, background.green, .01f)
        assertEquals(colorDiagnostic, expectedPrimary.blue, background.blue, .01f)
        val layouts = mutableListOf<TextLayoutResult>()
        ui.onNodeWithText(label, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            assertTrue(it(layouts))
        }
        assertEquals(expectedOnPrimary, layouts.single().layoutInput.style.color)
    }

    fun testNoConversationKeepsWhitespaceAndBottomFooterWithoutWritingConfiguration() {
        for (dark in listOf(false, true)) {
            val prefs = instrumentation.targetContext.getSharedPreferences("collaboration-empty-${UUID.randomUUID()}", 0)
            try {
                val repository = ConversationSubAgentPreferences(prefs)
                val before = prefs.all.toMap()
                show(null, repository, dark)
                assertSpaciousWithBottomFooter("subagent-no-conversation")
                ui.onNodeWithText("尚未选择会话").assertIsDisplayed()
                ui.onNodeWithText("使用当前配置").assertDoesNotExist()
                assertPrimaryFooterWithoutCross(dark, "关闭")
                node("subagent-collaboration-done").performTouchInput { click(Offset(centerX, 2f * density)) }
                node("subagent-collaboration-card").assertDoesNotExist()
                assertEquals(before, prefs.all)
            } finally { prefs.edit().clear().commit() }
        }
    }

    fun testCurrentPanelHintAndAddCenterInCompactRowForEmptyAndThreeProfiles() {
        val provider = SubAgentProvider("provider", "Provider", listOf(
            SubAgentModel("model", "gpt-6.1-sol", supportsGptSpeed = true)))
        val source = object : SubAgentModelSource {
            override val providers = MutableStateFlow(listOf(provider))
        }
        for (dark in listOf(false, true)) for (fontScale in listOf(1f, 1.3f)) for (count in listOf(0, 3)) {
            val prefs = instrumentation.targetContext.getSharedPreferences("collaboration-add-row-${UUID.randomUUID()}", 0)
            try {
                val repository = ConversationSubAgentPreferences(prefs)
                val preset = repository.addPreset("Layout group")
                repository.update(SubAgentConfigKey.Preset(preset.id)) { it.copy(profiles = List(count) { index ->
                    SubAgentProfile("worker-$index", "子代理 ${index + 1}", providerId = provider.id, modelId = "model")
                }) }
                val owner = SubAgentConfigKey.Conversation("layout-${UUID.randomUUID()}")
                repository.createConversation(owner)
                assertTrue(repository.applyPreset(owner, preset.id) is ConversationSubAgentPreferences.WriteResult.Saved)
                val before = prefs.all.toMap()
                show(owner.value, repository, dark, fontScale, source)
                val row = node("subagent-collaboration-add-row").scrollToVisible().assertIsDisplayed().assertHeightIsEqualTo(48.dp)
                val add = node("subagent-collaboration-add").assertIsEnabled().assertHasClickAction()
                    .assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
                awaitNodeWindowFocus(add)
                val rowBounds = row.fetchSemanticsNode().boundsInRoot
                val addBounds = add.fetchSemanticsNode().boundsInRoot
                val hint = ui.onNodeWithTag("subagent-collaboration-hint", useUnmergedTree = true)
                val hintBounds = hint.fetchSemanticsNode().boundsInRoot
                val glyphBounds = ui.onNodeWithContentDescription("添加子代理", useUnmergedTree = true)
                    .fetchSemanticsNode().boundsInRoot
                val geometry = "profiles=$count dark=$dark fontScale=$fontScale"
                assertEquals("Hint and add centers: $geometry", addBounds.center.y, hintBounds.center.y, 1f)
                assertEquals("Add glyph centers: $geometry", addBounds.center.y, glyphBounds.center.y, 1f)
                assertEquals("No extra top inset: $geometry", rowBounds.top, addBounds.top, 1f)
                assertEquals("No extra bottom inset: $geometry", rowBounds.bottom, addBounds.bottom, 1f)
                val layouts = mutableListOf<TextLayoutResult>()
                hint.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
                val text = layouts.single()
                assertFalse("Hint does not overflow: $geometry", text.hasVisualOverflow)
                assertEquals("Text line box centers: $geometry", addBounds.center.y,
                    hintBounds.top + (text.getLineTop(0) + text.getLineBottom(text.lineCount - 1)) / 2f, 1f)
                if (count == 3) {
                    val lastCard = node("subagent-card-worker-2").fetchSemanticsNode()
                    val lastCardBottom = lastCard.positionInRoot.y + lastCard.size.height
                    assertEquals("Last card gap stays 8dp: $geometry", 8f * density, rowBounds.top - lastCardBottom, 1.5f)
                }
                val divider = node("subagent-collaboration-footer-divider").fetchSemanticsNode().boundsInRoot
                val body = node("subagent-collaboration-body").fetchSemanticsNode().boundsInRoot
                assertEquals("Body has no stacked bottom inset: $geometry", body.bottom, rowBounds.bottom, 1.5f)
                assertEquals("Footer gap stays 8dp: $geometry", 8f * density, divider.top - body.bottom, 1.5f)
                assertEquals(before, prefs.all)
                add.performTouchInput { click(androidx.compose.ui.geometry.Offset(2f * density, height / 2f)) }
                node("subagent-config-window").assertIsDisplayed()
                ui.onNodeWithText("取消").performClick()
                node("subagent-config-window").assertDoesNotExist()
                assertEquals(before, prefs.all)
                val done = node("subagent-collaboration-done")
                awaitNodeWindowFocus(done)
                done.assertIsDisplayed().assertIsEnabled().performClick()
                ui.waitUntil(5_000) {
                    ui.onAllNodesWithTag("subagent-collaboration-card")
                        .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
                }
                node("subagent-collaboration-card").assertDoesNotExist()
                assertEquals(0, fixture.opens.get())
            } finally { prefs.edit().clear().commit() }
        }
    }

    fun testEmptyPresetDirectoryKeepsCurrentConfigurationEntryAndDoesNotApplyAnything() {
        for (dark in listOf(false, true)) {
            val prefs = instrumentation.targetContext.getSharedPreferences("collaboration-no-presets-${UUID.randomUUID()}", 0)
            try {
                val repository = ConversationSubAgentPreferences(prefs)
                repository.createConversation(SubAgentConfigKey.Conversation("empty-state"))
                val before = prefs.all.toMap()
                show("empty-state", repository, dark)
                ui.waitUntil(5_000) { ui.onAllNodesWithTag("subagent-no-presets").fetchSemanticsNodes().size == 1 }
                assertSpaciousWithBottomFooter("subagent-no-presets")
                ui.onNodeWithText("还没有子代理组").assertIsDisplayed()
                ui.onNodeWithText("使用当前配置").assertIsEnabled()
                assertPrimaryFooterWithoutCross(dark, "完成")
                node("subagent-collaboration-done").performTouchInput { click(Offset(centerX, height - 2f * density)) }
                node("subagent-collaboration-card").assertDoesNotExist()
                assertEquals(before, prefs.all)
            } finally { prefs.edit().clear().commit() }
        }
    }
}
