package app.daiyu.android

import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.daiyu.android.assistants.FileAssistantAssets
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.delegation.ReasoningEffort
import app.daiyu.android.model.catalog.CatalogModelEdit
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.components.DaiyuChoiceSheet
import app.daiyu.android.ui.components.ConversationCollaborationDialog
import app.daiyu.android.delegation.ConversationSubAgentPreferences
import app.daiyu.android.delegation.SubAgentConfigKey
import app.daiyu.android.delegation.SubAgentProfile
import java.util.UUID
import app.daiyu.android.ui.components.UsageGlassDialog
import app.daiyu.android.ui.components.subagent.ConversationThinkingPicker
import app.daiyu.android.ui.screens.assistants.AssistantPickerDialog
import app.daiyu.android.ui.screens.chat.conversation.ChatAttachmentSheet
import app.daiyu.android.ui.screens.chat.conversation.ChatMenuAction
import app.daiyu.android.ui.screens.chat.conversation.SubAgentStatusDialog
import app.daiyu.android.ui.screens.chat.conversation.SubAgentStatusRow
import app.daiyu.android.domain.delegation.ChildRunOwner
import app.daiyu.android.ui.screens.settings.CatalogModelEditorDialog

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class BottomSheetEdgeActivityTest : WiredActivityTestCase() {
    private var dismissals = 0

    private fun show(dark: Boolean = false, scale: Float = 1f, fontScale: Float? = null, content: @Composable (() -> Unit) -> Unit) {
        dismissals = 0
        mainThread {
            val open = mutableStateOf(true)
            activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale ?: density.fontScale)) {
                    AgentAppTheme(AppearanceSettings(interfaceScale = scale,
                        themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT), applyInterfaceScale = true) {
                        Box(Modifier.fillMaxSize()) {
                            if (open.value) content { dismissals++; open.value = false }
                        }
                    }
                }
            }
        }
        awaitVisualIdle()
    }

    private fun rawBounds(node: androidx.compose.ui.semantics.SemanticsNode): androidx.compose.ui.geometry.Rect =
        androidx.compose.ui.geometry.Rect(node.positionInRoot,
            androidx.compose.ui.geometry.Size(node.size.width.toFloat(), node.size.height.toFloat()))

    private fun surfaceBounds(node: androidx.compose.ui.semantics.SemanticsNode): Rect {
        val bounds = rawBounds(node)
        return if (node.config[SemanticsProperties.TestTag] == "subagent-collaboration-card") {
            val inset = with(node.layoutInfo.density) { 10.dp.toPx() }
            Rect(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset)
        } else bounds
    }

    private fun assertHorizontalInsets(surfaceTag: String,
        surface: androidx.compose.ui.semantics.SemanticsNode,
        viewport: androidx.compose.ui.semantics.SemanticsNode,
        insets: WindowInsetsCompat) {
        val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val viewportBounds = rawBounds(viewport)
        val safeLeft = viewportBounds.left + safe.left
        val safeRight = viewportBounds.right - safe.right
        val available = safeRight - safeLeft
        val density = surface.layoutInfo.density
        val margin = with(density) { (if (surfaceTag in listOf("daiyu-choice-sheet", "catalog-model-dialog")) 12.dp else 10.dp).toPx() }
        val width = with(density) {
            when (surfaceTag) {
                "daiyu-choice-sheet" -> minOf(available, 640.dp.toPx()) - 2 * margin
                "catalog-model-dialog" -> minOf(available - 2 * margin, 560.dp.toPx())
                "usage-glass-dialog" -> minOf(available - 2 * margin, 550.dp.toPx())
                else -> available - 2 * margin
            }
        }
        val gap = (available - width) / 2
        val surfaceLeft = surfaceBounds(surface).left
        val surfaceRight = surfaceBounds(surface).right
        assertEquals("Original left inset remains outside the surface", safeLeft + gap, surfaceLeft, 2f)
        assertEquals("Original right inset remains outside the surface", safeRight - gap, surfaceRight, 2f)
    }

    private fun assertFloating(surfaceTag: String, controlTag: String? = null, ime: Boolean = false,
        originalWindowBottom: Float? = null) {
        awaitUniqueTag(surfaceTag)
        awaitNodeWindowFocus(node(surfaceTag))
        if (!ime) {
            val root = (node(surfaceTag).fetchSemanticsNode().root as ViewRootForTest).view.rootView
            dismissIme(root)
        }
        ui.mainClock.advanceTimeBy(1_000)
        awaitVisualIdle()
        val surface = node(surfaceTag).assertIsDisplayed().fetchSemanticsNode()
        val view = (surface.root as ViewRootForTest).view
        val viewportTag = if (surfaceTag == "daiyu-choice-sheet") "choice-sheet-viewport" else "preview-dialog-viewport"
        val viewport = node(viewportTag).fetchSemanticsNode()
        assertSame(surface.root, viewport.root)
        val control = controlTag?.let { node(it).assertIsDisplayed().fetchSemanticsNode() }
        var surfaceBottom = 0f
        var safeBottom = 0f
        var controlBottom = 0f
        val margin = with(surface.layoutInfo.density) { (if (surfaceTag in listOf("daiyu-choice-sheet", "catalog-model-dialog")) 12.dp else 10.dp).toPx() }
        instrumentation.runOnMainSync {
            val viewportBottom = viewport.boundsInRoot.bottom + viewport.positionOnScreen.y - viewport.positionInRoot.y
            val screenOffset = surface.positionOnScreen - surface.positionInRoot
            surfaceBottom = surfaceBounds(surface).bottom + screenOffset.y
            safeBottom = viewportBottom
            if (control != null) {
                assertSame(surface.root, control.root)
                controlBottom = rawBounds(control).bottom + control.positionOnScreen.y - control.positionInRoot.y
            }
            val decor = view.rootView
            val location = IntArray(2)
            decor.getLocationOnScreen(location)
            assertEquals("Viewport fills the dialog window", location[1] + decor.height.toFloat(), viewportBottom, 2f)
            val insets = checkNotNull(ViewCompat.getRootWindowInsets(view))
            assertHorizontalInsets(surfaceTag, surface, viewport, insets)
            assertEquals(ime, insets.isVisible(WindowInsetsCompat.Type.ime()))
            if (ime) {
                // ADJUST_NOTHING may retain the full frame. Subtract from the original
                // window, never from a viewport that may already have been resized.
                val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                assertTrue("Visible IME has a positive bottom inset", imeBottom > 0)
                val keyboardTop = checkNotNull(originalWindowBottom) - imeBottom
                assertTrue("Viewport must reach the independent keyboard edge", viewportBottom >= keyboardTop - 2f)
                val systemBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()).bottom
                safeBottom = checkNotNull(originalWindowBottom) - maxOf(imeBottom, systemBottom)
            } else {
                safeBottom -= insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()).bottom
            }
        }
        assertEquals("The painted surface keeps its original bottom margin", safeBottom - margin, surfaceBottom, 2f)
        if (control != null) {
            assertTrue("Controls stay within the floating surface", controlBottom <= surfaceBottom + 2f)
        }
    }

    private fun close(tag: String) {
        awaitNodeWindowFocus(node(tag))
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        ui.waitForIdle()
        node(tag).assertDoesNotExist()
        assertEquals(1, dismissals)
    }

    fun testAttachmentAndThinkingSheetsKeepFloatingInsetsInBothThemesAndScales() {
        for (dark in listOf(false, true)) for (scale in listOf(1f, .8f)) {
            var action: ChatMenuAction? = null
            show(dark, scale) { dismiss -> ChatAttachmentSheet(dismiss) { action = it } }
            assertFloating("chat-selection-sheet", "chat-attachment:ATTACH_FOLDER")
            node("chat-attachment:ATTACH_IMAGE").performClick()
            assertEquals(ChatMenuAction.ATTACH_IMAGE, action)
            assertEquals(1, dismissals)
            var changes = 0
            show(dark, scale) { dismiss ->
                ConversationThinkingPicker(true, ReasoningEffort.MEDIUM,
                    listOf(ReasoningEffort.OFF, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH), dismiss, { changes++ }, null)
            }
            assertFloating("conversation-agent-sheet", "thinking-depth-slider")
            close("conversation-agent-sheet")
            assertEquals(0, changes)
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testStatusSheetMatchesAssistantHeightForEveryState() {
        val assets = FileAssistantAssets(instrumentation.targetContext)
        for (dark in listOf(false, true)) for (scale in listOf(1f, .8f)) {
            show(dark, scale) { dismiss -> AssistantPickerDialog(true, emptyList(), null, {}, {}, dismiss,
                onCopy = {}, onDelete = {}, assets = assets) }
            assertFloating("assistant-sheet", "assistant-done")
            val expectedHeight = rawBounds(node("assistant-sheet").fetchSemanticsNode()).height
            close("assistant-sheet")
            for (state in listOf(0, 1, 2, 3, 4)) {
                val count = when (state) { 3 -> 1; 4 -> 40; else -> 0 }
                val rows = (0 until count).map { index ->
                    SubAgentStatusRow(ChildRunOwner("chat", "run", "group", 1, "task-$index", "answer"),
                        "代理 $index", "执行中", model = "冻结模型 $index")
                }
                show(dark, scale) { dismiss ->
                    SubAgentStatusDialog(rows, loading = state == 0, failed = state == 2, onDismiss = dismiss)
                }
                assertFloating("conversation-agent-sheet")
                val status = node("conversation-agent-sheet").fetchSemanticsNode()
                assertEquals("Status and assistant surfaces share a default height", expectedHeight, rawBounds(status).height, 2f)
                if (state == 0) ui.onNodeWithText("正在读取任务状态…").assertIsDisplayed()
                if (state == 2) ui.onNodeWithText("任务状态读取失败；未改变任何任务").assertIsDisplayed()
                if (state == 1) {
                    val empty = node("subagent-status-empty").fetchSemanticsNode()
                    val text = ui.onNodeWithText("本会话暂无子代理任务").assertIsDisplayed().fetchSemanticsNode()
                    assertEquals(rawBounds(empty).center.y, rawBounds(text).center.y, 1f)
                }
                if (state == 4) {
                    node("subagent-status-list").performScrollToIndex(rows.lastIndex)
                    ui.mainClock.advanceTimeByFrame()
                    ui.waitForIdle()
                    node("subagent-status:task-39").scrollToVisible().assertIsDisplayed()
                    ui.onNodeWithText("冻结模型 39").assertDoesNotExist()
                    node("subagent-status:task-39").performClick()
                    ui.onNodeWithText("冻结模型 39").scrollToVisible().assertIsDisplayed()
                    assertEquals(expectedHeight, rawBounds(node("conversation-agent-sheet").fetchSemanticsNode()).height, 2f)
                    node("subagent-status:task-39").scrollToVisible().performClick()
                    ui.onNodeWithText("冻结模型 39").assertDoesNotExist()
                    assertEquals(expectedHeight, rawBounds(node("conversation-agent-sheet").fetchSemanticsNode()).height, 2f)
                }
                close("conversation-agent-sheet")
            }
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testCollaborationSheetMatchesAssistantHeightAndPinsFooterAcrossContentStates() {
        val assets = FileAssistantAssets(instrumentation.targetContext)
        for (dark in listOf(false, true)) for (scale in listOf(1f, .8f)) {
            show(dark, scale, fontScale = 1.3f) { dismiss ->
                AssistantPickerDialog(true, emptyList(), null, {}, {}, dismiss, onCopy = {}, onDelete = {}, assets = assets)
            }
            assertFloating("assistant-sheet", "assistant-done")
            val expectedHeight = rawBounds(node("assistant-sheet").fetchSemanticsNode()).height
            close("assistant-sheet")
            for (state in listOf(0, 1, 2, 3, 4, 5)) {
                val prefs = instrumentation.targetContext.getSharedPreferences("collaboration-height-${UUID.randomUUID()}", 0)
                try {
                    val repository = ConversationSubAgentPreferences(prefs)
                    val owner = SubAgentConfigKey.Conversation("height-${UUID.randomUUID()}")
                    if (state != 0) repository.createConversation(owner)
                    val count = when (state) { 2, 4, 5 -> 1; 3 -> 20; else -> 0 }
                    val presets = List(count) { repository.addPreset("Group $it") }
                    if (state >= 4) {
                        repository.update(SubAgentConfigKey.Preset(presets.single().id)) {
                            it.copy(profiles = List(if (state == 5) 8 else 0) { index -> SubAgentProfile("worker-$index", "代理 $index") })
                        }
                        assertTrue(repository.applyPreset(owner, presets.single().id) is ConversationSubAgentPreferences.WriteResult.Saved)
                    }
                    val before = prefs.all.toMap()
                    show(dark, scale, fontScale = 1.3f) { dismiss ->
                        ConversationCollaborationDialog(true, if (state == 0) null else owner.value, dismiss,
                            repositoryFactory = { repository })
                    }
                    when (state) {
                        0 -> awaitUniqueTag("subagent-no-conversation")
                        1 -> awaitUniqueTag("subagent-no-presets")
                        2, 3 -> ui.waitUntil(5_000) { ui.onAllNodesWithText("Group ${count - 1}").fetchSemanticsNodes().size == 1 }
                        else -> awaitUniqueTag("subagent-collaboration-add-row")
                    }
                    assertFloating("subagent-collaboration-card", "subagent-collaboration-done")
                    val surface = node("subagent-collaboration-card").fetchSemanticsNode()
                    val footer = node("subagent-collaboration-footer").fetchSemanticsNode()
                    val body = node("subagent-collaboration-body").fetchSemanticsNode()
                    assertSame(surface.root, footer.root)
                    assertSame(surface.root, body.root)
                    val initialFooter = rawBounds(footer)
                    assertEquals("Collaboration and assistant surfaces share a default height", expectedHeight, surfaceBounds(surface).height, 2f)
                    assertEquals("Footer has only the original bottom padding", with(surface.layoutInfo.density) { 16.dp.toPx() },
                        surfaceBounds(surface).bottom - initialFooter.bottom, 1.5f)
                    assertTrue("Only the body occupies space above the footer", rawBounds(body).bottom <= initialFooter.top)
                    if (state == 3) ui.onNodeWithText("Group 19").performScrollTo().assertIsDisplayed()
                    if (state == 5) node("subagent-card-worker-7").scrollToVisible().assertIsDisplayed()
                    assertEquals("Scrolling never moves the footer", initialFooter.bottom,
                        rawBounds(node("subagent-collaboration-footer").fetchSemanticsNode()).bottom, 1f)
                    assertEquals("Scrolling never resizes the surface", expectedHeight,
                        surfaceBounds(node("subagent-collaboration-card").fetchSemanticsNode()).height, 2f)
                    node("subagent-collaboration-done").assertIsDisplayed().assertIsEnabled()
                    assertEquals(before, prefs.all)
                    close("subagent-collaboration-card")
                    assertEquals(before, prefs.all)
                } finally { prefs.edit().clear().commit() }
            }
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testEmptyCollaborationPagesKeepFooterGeometryWhenSwitchingBackAndForth() {
        fun footerFrame(secondaryLabel: String): List<Rect> {
            ui.waitUntil(5_000) {
                ui.onAllNodesWithText(secondaryLabel).fetchSemanticsNodes().singleOrNull()
                    ?.config?.contains(SemanticsProperties.Disabled) == false
            }
            val nodes = listOf(node("subagent-collaboration-card"), node("subagent-collaboration-body"),
                node("subagent-collaboration-footer"),
                ui.onNodeWithText(secondaryLabel).assertIsEnabled().assertHasClickAction(),
                node("subagent-collaboration-done").assertIsEnabled().assertHasClickAction())
                .map { it.assertIsDisplayed().fetchSemanticsNode() }
            val root = nodes.first().root
            nodes.forEach { assertSame(root, it.root) }
            val bounds = awaitStableWindowFrame((root as ViewRootForTest).view) {
                if (nodes.any { !it.layoutInfo.isPlaced || !it.layoutInfo.coordinates.isAttached }) null
                else nodes.mapIndexed { index, node -> if (index == 0) surfaceBounds(node) else rawBounds(node) }.takeIf { sampled -> sampled.none { it.isEmpty } }
            }
            val (surface, body, footer, secondary, done) = bounds
            fun px(value: Int) = with(nodes.first().layoutInfo.density) { value.dp.toPx() }
            assertEquals("Both buttons remain 48dp tall", px(48), secondary.height, 1.5f)
            assertEquals(secondary.height, done.height, 1f)
            assertEquals("Both buttons remain equally wide", secondary.width, done.width, 1f)
            assertEquals("Button bottoms align", secondary.bottom, done.bottom, 1f)
            assertEquals("Footer keeps the original bottom inset", px(16), surface.bottom - done.bottom, 1.5f)
            assertEquals("Footer container stays pinned at the bottom", px(16), surface.bottom - footer.bottom, 1.5f)
            assertEquals("Buttons reach the footer bottom", footer.bottom, done.bottom, 1f)
            assertEquals("Secondary button keeps the left inset", px(20), secondary.left - surface.left, 1.5f)
            assertEquals("Done button keeps the right inset", px(20), surface.right - done.right, 1.5f)
            assertEquals("The button gap stays 12dp", px(12), done.left - secondary.right, 1.5f)
            assertTrue("Only the body scrolls above the buttons", body.bottom <= secondary.top)
            return listOf(surface, footer, secondary, done)
        }
        fun assertSameFooter(expected: List<Rect>, actual: List<Rect>) {
            expected.zip(actual).forEach { (before, after) ->
                assertEquals(before.left, after.left, 1f)
                assertEquals(before.top, after.top, 1f)
                assertEquals(before.right, after.right, 1f)
                assertEquals(before.bottom, after.bottom, 1f)
            }
        }
        for (dark in listOf(false, true)) for (scale in listOf(1f, .8f)) for (hasPreset in listOf(false, true)) {
            val prefs = instrumentation.targetContext.getSharedPreferences("collaboration-roundtrip-${UUID.randomUUID()}", 0)
            try {
                val repository = ConversationSubAgentPreferences(prefs)
                val owner = SubAgentConfigKey.Conversation("roundtrip-${UUID.randomUUID()}")
                repository.createConversation(owner)
                val preset = if (hasPreset) repository.addPreset("空组") else null
                val beforeOpen = prefs.all.toMap()
                show(dark, scale, fontScale = 1.3f) { dismiss ->
                    ConversationCollaborationDialog(true, owner.value, dismiss, repositoryFactory = { repository })
                }
                if (preset == null) awaitUniqueTag("subagent-no-presets")
                else ui.onNodeWithContentDescription("应用子代理组空组").assertIsDisplayed()
                awaitDialogEnter(node("subagent-collaboration-card"))
                val expected = footerFrame("使用当前配置")
                assertEquals(beforeOpen, prefs.all)
                if (preset == null) ui.onNodeWithText("使用当前配置").performClick()
                else ui.onNodeWithContentDescription("应用子代理组空组").performClick()
                awaitUniqueTag("subagent-collaboration-add-row")
                assertSameFooter(expected, footerFrame("切换子代理组"))
                assertTrue(repository.snapshot(owner).profiles.isEmpty())
                assertEquals(preset?.id, repository.snapshot(owner).appliedPresetId)
                if (preset == null) assertEquals(beforeOpen, prefs.all)
                val beforeNavigation = prefs.all.toMap()
                repeat(2) {
                    ui.onNodeWithText("切换子代理组").performClick()
                    assertSameFooter(expected, footerFrame("使用当前配置"))
                    assertEquals(beforeNavigation, prefs.all)
                    ui.onNodeWithText("使用当前配置").performClick()
                    awaitUniqueTag("subagent-collaboration-add-row")
                    assertSameFooter(expected, footerFrame("切换子代理组"))
                    assertEquals(beforeNavigation, prefs.all)
                }
                val done = node("subagent-collaboration-done")
                awaitNodeWindowFocus(done)
                done.assertIsDisplayed().assertIsEnabled().performClick()
                ui.waitUntil(5_000) {
                    ui.onAllNodesWithTag("subagent-collaboration-card")
                        .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
                }
                assertEquals(1, dismissals)
                assertEquals(beforeNavigation, prefs.all)
            } finally { prefs.edit().clear().commit() }
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testAssistantUsageChoiceAndModelEditorShareFloatingInsets() {
        val assets = FileAssistantAssets(instrumentation.targetContext)
        show { dismiss -> AssistantPickerDialog(true, emptyList(), null, {}, {}, dismiss,
            onCopy = {}, onDelete = {}, assets = assets) }
        assertFloating("assistant-sheet", "assistant-done")
        close("assistant-sheet")
        show { dismiss -> UsageGlassDialog("底部用量弹窗", dismiss) { Box(Modifier.height(60.dp)) } }
        assertFloating("usage-glass-dialog")
        close("usage-glass-dialog")
        show { dismiss -> DaiyuChoiceSheet("选择", listOf("一", "二"), {}, dismiss) }
        assertFloating("daiyu-choice-sheet", "choice-sheet-cancel")
        close("daiyu-choice-sheet")
        show { dismiss -> CatalogModelEditorDialog(CatalogModelEdit("model", "模型"), true, true, {}, dismiss) }
        assertFloating("catalog-model-dialog", "dialog-confirm")
        close("catalog-model-dialog")
        assertEquals(0, fixture.opens.get())
    }

    fun testCompactUsageDialogRemainsCenteredRatherThanBecomingASheet() {
        show { dismiss -> UsageGlassDialog("居中用量", dismiss, compact = true) { Box(Modifier.size(40.dp)) } }
        ui.mainClock.advanceTimeBy(1_000)
        awaitVisualIdle()
        val surface = node("usage-glass-dialog").fetchSemanticsNode()
        assertTrue(surface.boundsInRoot.top > 0f)
        assertTrue(surface.boundsInRoot.bottom < node("preview-dialog-viewport").fetchSemanticsNode().boundsInRoot.bottom - 20f)
        close("usage-glass-dialog")
    }

    fun testEditorSurfaceKeepsBottomMarginAboveImeAndReturnsToSafeEdge() {
        show { dismiss -> CatalogModelEditorDialog(CatalogModelEdit("model", "模型"), true, true, {}, dismiss) }
        assertFloating("catalog-model-dialog", "dialog-confirm")
        val sheet = node("catalog-model-dialog").fetchSemanticsNode()
        val view = (sheet.root as ViewRootForTest).view
        var originalBottom = 0f
        instrumentation.runOnMainSync {
            val location = IntArray(2)
            view.rootView.getLocationOnScreen(location)
            originalBottom = location[1] + view.rootView.height.toFloat()
        }
        node("settings-field:展示名称").performClick()
        mainThread {
            val focus = checkNotNull(view.rootView.findFocus())
            activity.getSystemService(InputMethodManager::class.java).showSoftInput(focus, InputMethodManager.SHOW_IMPLICIT)
        }
        ui.waitUntil(5_000) {
            var visible = false
            instrumentation.runOnMainSync {
                visible = ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
            visible
        }
        assertFloating("catalog-model-dialog", "dialog-confirm", ime = true,
            originalWindowBottom = originalBottom)
        val restoredFrames = java.util.concurrent.atomic.AtomicInteger()
        val observer = view.viewTreeObserver
        var lastRestoredFrame = Long.MIN_VALUE
        var currentFrameNanos = Long.MIN_VALUE
        lateinit var choreographer: android.view.Choreographer
        val frameCallback = object : android.view.Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                currentFrameNanos = frameTimeNanos
                view.postInvalidateOnAnimation()
                choreographer.postFrameCallback(this)
            }
        }
        val listener = android.view.ViewTreeObserver.OnPreDrawListener {
            val insets = ViewCompat.getRootWindowInsets(view)
            val location = IntArray(2)
            view.rootView.getLocationOnScreen(location)
            val bottom = rawBounds(sheet).bottom + sheet.positionOnScreen.y - sheet.positionInRoot.y
            val safeInset = insets?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())?.bottom ?: 0
            val bottomMargin = with(sheet.layoutInfo.density) { 12.dp.toPx() }
            val restored = view.isAttachedToWindow && insets != null &&
                !insets.isVisible(WindowInsetsCompat.Type.ime()) &&
                insets.getInsets(WindowInsetsCompat.Type.ime()).bottom == 0 &&
                kotlin.math.abs(location[1] + view.rootView.height - originalBottom) <= 2f &&
                kotlin.math.abs(bottom - (originalBottom - safeInset - bottomMargin)) <= 2f
            val frame = currentFrameNanos
            if (!restored) {
                restoredFrames.set(0)
                lastRestoredFrame = Long.MIN_VALUE
            } else if (frame != Long.MIN_VALUE && frame != lastRestoredFrame) {
                restoredFrames.incrementAndGet()
                lastRestoredFrame = frame
            }
            if (restoredFrames.get() < 2) view.postInvalidateOnAnimation()
            true
        }
        instrumentation.runOnMainSync {
            choreographer = android.view.Choreographer.getInstance()
            observer.addOnPreDrawListener(listener)
            choreographer.postFrameCallback(frameCallback)
        }
        try {
            dismissIme()
            ui.waitUntil(5_000) { restoredFrames.get() >= 2 }
        } finally {
            instrumentation.runOnMainSync {
                choreographer.removeFrameCallback(frameCallback)
                if (observer.isAlive) observer.removeOnPreDrawListener(listener)
            }
        }
        assertFloating("catalog-model-dialog", "dialog-confirm")
        close("catalog-model-dialog")
        assertEquals(0, fixture.opens.get())
    }
}
