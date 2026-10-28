package app.daiyu.android

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.SemanticsNode
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
import app.daiyu.android.ui.screens.assistants.AssistantPreviewDialog
import java.util.UUID

class CollaborationEmptyStateTest : WiredActivityTestCase() {
    private var density = 1f
    private var rootsBeforeShow = ""
    private data class SheetViewport(
        val width: Int, val height: Int, val visibleFrame: Rect,
        val systemBars: androidx.core.graphics.Insets, val imeBottom: Int,
    )
    private var assistantViewport: SheetViewport? = null
    private var assistantBounds: Rect? = null

    private fun viewport(view: android.view.View): SheetViewport? {
        val insets = androidx.core.view.ViewCompat.getRootWindowInsets(view) ?: return null
        if (insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())) return null
        val visible = android.graphics.Rect()
        view.getWindowVisibleDisplayFrame(visible)
        return SheetViewport(view.width, view.height,
            Rect(visible.left.toFloat(), visible.top.toFloat(), visible.right.toFloat(), visible.bottom.toFloat()),
            insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()),
            insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom)
    }

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

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun assistantHeight(dark: Boolean): Float {
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT), applyInterfaceScale = false) {
                AssistantPreviewDialog(true, "高度参考", {}) {}
            }
        } }
        val target = node("assistant-sheet")
        awaitDialogEnter(target)
        awaitNodeWindowFocus(target)
        val current = target.fetchSemanticsNode()
        val view = (current.root as androidx.compose.ui.platform.ViewRootForTest).view
        dismissIme(view)
        awaitDialogImeFrame(target, visible = false)
        val (bounds, window) = awaitStableWindowFrame(view) {
            viewport(view)?.let { rawBounds(current) to it }
        }
        assistantBounds = bounds
        assistantViewport = window
        return bounds.height
    }

    private fun rawBounds(node: SemanticsNode): Rect =
        Rect(node.positionInRoot, Size(node.size.width.toFloat(), node.size.height.toFloat()))

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun assertSpaciousWithBottomFooter(tag: String, expectedHeight: Float) {
        awaitDialogEnter(node("subagent-collaboration-card"))
        val messageNode = node(tag).assertIsDisplayed().fetchSemanticsNode()
        val cardNode = node("subagent-collaboration-card").fetchSemanticsNode()
        val footerNode = node("subagent-collaboration-footer").assertIsDisplayed().fetchSemanticsNode()
        val surfaceNode = cardNode
        assertSame(cardNode.root, footerNode.root)
        assertSame(cardNode.root, messageNode.root)
        assertSame(cardNode.root, surfaceNode.root)
        val view = (cardNode.root as androidx.compose.ui.platform.ViewRootForTest).view
        awaitNodeWindowFocus(node("subagent-collaboration-card"))
        dismissIme(view)
        awaitDialogImeFrame(node("subagent-collaboration-card"), visible = false)
        val (layout, visible, window) = awaitStableWindowFrame(view) {
            val currentViewport = viewport(view) ?: return@awaitStableWindowFrame null
            Triple(listOf(rawBounds(cardNode), rawBounds(footerNode), rawBounds(messageNode),
                rawBounds(surfaceNode).let { outer ->
                    val inset = with(surfaceNode.layoutInfo.density) { 10.dp.toPx() }
                    Rect(outer.left + inset, outer.top + inset, outer.right - inset, outer.bottom - inset)
                }), (cardNode.boundsInRoot to footerNode.boundsInRoot), currentViewport)
        }
        val (card, footer, message, surface) = layout
        val (visibleCard, visibleFooter) = visible
        val diagnostic = "card=$card footer=$footer message=$message visible=$visible density=$density " +
            "assistant=$assistantBounds assistantViewport=$assistantViewport collaborationViewport=$window"
        assertEquals("Both sheets must use the same IME-hidden viewport: $diagnostic", assistantViewport, window)
        assertEquals("Empty sheet matches the assistant surface: $diagnostic", expectedHeight, surface.height, 2f)
        assertEquals("Footer keeps only the 16dp bottom inset: $diagnostic", 16f * density, surface.bottom - footer.bottom, 1.5f)
        assertTrue("Whitespace belongs between message and footer: $diagnostic", (footer.top - message.bottom) / density > 60f)
        assertTrue("Footer stays within sheet: $diagnostic", footer.bottom <= card.bottom + 1f)
        assertTrue("Visible footer stays within sheet: $diagnostic", visibleFooter.bottom <= visibleCard.bottom + 1f)
        assertTrue("No unused area after footer: $diagnostic", (card.bottom - footer.bottom) / density < 32f)
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
                val expectedHeight = assistantHeight(dark)
                show(null, repository, dark)
                assertSpaciousWithBottomFooter("subagent-no-conversation", expectedHeight)
                ui.onNodeWithText("尚未选择会话").assertIsDisplayed()
                ui.onNodeWithText("使用当前配置").assertDoesNotExist()
                assertPrimaryFooterWithoutCross(dark, "关闭")
                node("subagent-collaboration-done").performTouchInput { click(Offset(centerX, 2f * density)) }
                node("subagent-collaboration-card").assertDoesNotExist()
                assertEquals(before, prefs.all)
            } finally { prefs.edit().clear().commit() }
        }
    }

    fun testCurrentPanelHintAndAddStayAboveFooterForEmptyShortAndLongProfiles() {
        val provider = SubAgentProvider("provider", "Provider", listOf(
            SubAgentModel("model", "gpt-6.1-sol", supportsGptSpeed = true)))
        val source = object : SubAgentModelSource {
            override val providers = MutableStateFlow(listOf(provider))
        }
        for (dark in listOf(false, true)) for (fontScale in listOf(1f, 1.3f)) for (count in listOf(0, 1, 8)) {
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
                awaitDialogEnter(node("subagent-collaboration-card"))
                val row = node("subagent-collaboration-add-row").assertIsDisplayed().assertHeightIsEqualTo(48.dp)
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
                val divider = node("subagent-collaboration-footer-divider").fetchSemanticsNode().boundsInRoot
                val body = node("subagent-collaboration-body").fetchSemanticsNode().boundsInRoot
                val footer = node("subagent-collaboration-footer").fetchSemanticsNode().boundsInRoot
                assertEquals("Add row starts below the scroll body: $geometry", body.bottom, rowBounds.top, 1.5f)
                assertEquals("Add row stays 8dp above the footer divider: $geometry", 8f * density, divider.top - rowBounds.bottom, 1.5f)
                assertTrue("Add row remains above the buttons: $geometry", rowBounds.bottom <= footer.top)
                if (count <= 1) {
                    val contentBottom = if (count == 0) ui.onNodeWithText("自动委派").fetchSemanticsNode().boundsInRoot.bottom
                        else node("subagent-card-worker-0").fetchSemanticsNode().boundsInRoot.bottom
                    assertTrue("Short content leaves whitespace above the fixed add row: $geometry", rowBounds.top > contentBottom)
                } else {
                    val scrollRange = node("subagent-collaboration-body").fetchSemanticsNode()
                        .config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange]
                    assertTrue("Long profiles require scrolling: $geometry", scrollRange.maxValue() > 0f)
                    val initialScroll = scrollRange.value()
                    node("subagent-card-worker-${count - 1}").scrollToVisible().assertIsDisplayed()
                    val bottomScroll = scrollRange.value()
                    assertTrue("Profile scroll actually advances: $geometry", bottomScroll > initialScroll)
                    val lastCard = node("subagent-card-worker-${count - 1}").fetchSemanticsNode()
                    val lastCardBottom = lastCard.positionInRoot.y + lastCard.size.height
                    assertTrue("Last profile is reachable above the fixed add row: $geometry", lastCardBottom <= rowBounds.top + 1.5f)
                    val scrolledRow = row.fetchSemanticsNode().boundsInRoot
                    val scrolledAdd = add.fetchSemanticsNode().boundsInRoot
                    val scrolledDivider = node("subagent-collaboration-footer-divider").fetchSemanticsNode().boundsInRoot
                    val scrolledFooter = node("subagent-collaboration-footer").fetchSemanticsNode().boundsInRoot
                    assertEquals("Scrolling never moves the add row: $geometry", rowBounds, scrolledRow)
                    assertEquals("Scrolling never moves the add button: $geometry", addBounds, scrolledAdd)
                    assertEquals("Scrolling never moves the divider: $geometry", divider, scrolledDivider)
                    assertEquals("Scrolling never moves the footer: $geometry", footer, scrolledFooter)
                    node("subagent-card-worker-0").scrollToVisible().assertIsDisplayed()
                    assertTrue("Profiles scroll back toward the start: $geometry", scrollRange.value() < bottomScroll)
                    assertEquals("Return scrolling never moves the add row: $geometry", rowBounds, row.fetchSemanticsNode().boundsInRoot)
                    assertEquals("Return scrolling never moves the footer: $geometry", footer,
                        node("subagent-collaboration-footer").fetchSemanticsNode().boundsInRoot)
                }
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
                val expectedHeight = assistantHeight(dark)
                show("empty-state", repository, dark)
                ui.waitUntil(5_000) { ui.onAllNodesWithTag("subagent-no-presets").fetchSemanticsNodes().size == 1 }
                assertSpaciousWithBottomFooter("subagent-no-presets", expectedHeight)
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
