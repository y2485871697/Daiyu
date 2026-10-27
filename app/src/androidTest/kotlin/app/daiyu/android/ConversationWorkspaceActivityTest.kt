package app.daiyu.android

import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.resolveAppearanceActions
import kotlin.math.abs
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.components.ConversationWorkspaceSheet
import app.daiyu.android.ui.components.WorkspaceSourceOption
import app.daiyu.android.ui.screens.chat.conversation.*

class ConversationWorkspaceActivityTest : WiredActivityTestCase() {
    private fun px(dp: Int): Float = dp * activity.resources.displayMetrics.density

    private var opens = 0
    private var voiceLongPresses = 0
    private var saves = 0
    private var dismisses = 0
    private var visible by mutableStateOf(true)
    private var busy by mutableStateOf(false)
    private var sourceId by mutableStateOf("private")
    private var path by mutableStateOf("/private/workspace")

    private var composerDraft by mutableStateOf("可选择的正文\n第二行\n第三行\n第四行\n第五行\n第六行\n第七行")
    private var editorGeneration by mutableStateOf(0L)

    fun testRealChatTopStripOpensTheActualSheetWithoutSavingOrSending() {
        awaitControl("chat local readers idle") { !fixture.controller.dataBusy }
        awaitHostWindowFocus(activity)
        setDraft("真实输入栏上滑保留的草稿")
        dismissIme()
        node("chat-workspace-gesture-area").performTouchInput {
            swipe(center, center + Offset(0f, -px(80)))
        }
        awaitUniqueTag("conversation-workspace-sheet")
        node("workspace-path-card").assertExists()
        node("workspace-save").assertDoesNotExist()
        node("workspace-close").assertDoesNotExist()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitHostWindowFocus(activity)
        assertDraft("真实输入栏上滑保留的草稿")
        assertNull(fixture.database.workspaceBinding(null))
        assertEquals(0, fixture.opens.get())
    }

    fun testRealChatFocusedEditorOpensActualSheetAndHidesIme() {
        awaitControl("chat local readers idle") { !fixture.controller.dataBusy }
        awaitHostWindowFocus(activity)
        setDraft("真实正文手势")
        node("chat-draft").performTouchInput { click() }
        awaitImeFrame(node("chat-draft"), true)
        node("chat-draft").performTouchInput { swipe(center, center + Offset(0f, -px(80))) }
        awaitUniqueTag("conversation-workspace-sheet")
        awaitDialogImeFrame(node("conversation-workspace-sheet"), false)
        node("chat-draft").assertIsNotFocused()
        node("workspace-path-card").performClick()
        awaitUniqueTag("workspace-current-path")
        node("workspace-cancel").performClick()
        node("workspace-path-card").assertExists()
        node("workspace-close").assertDoesNotExist()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitHostWindowFocus(activity)
        assertDraft("真实正文手势")
        assertNull(fixture.database.workspaceBinding(null))
        assertEquals(0, fixture.opens.get())
    }

    private fun showComposer() {
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                ChatMenuLayer(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
                        ChatComposer(ComposerUi(text = composerDraft, editorGeneration = editorGeneration),
                            ChatScreenActions(onTextChange = { composerDraft = it }, onOpenWorkspace = { opens++ }, onVoiceLongPress = { voiceLongPresses++ }))
                    }
                }
            }
        } }
        awaitHostWindowFocus(activity)
    }

    fun testUpwardBlankStripCommitsOnlyOnceOnRelease() {
        showComposer()
        val strip = node("chat-workspace-gesture-area")
        strip.performTouchInput {
            down(center)
            moveBy(Offset(0f, -px(80)))
            advanceEventTime(100)
        }
        ui.runOnIdle { assertEquals(0, opens) }
        strip.performTouchInput {
            moveBy(Offset(0f, -px(20)))
            up()
        }
        ui.runOnIdle { assertEquals(1, opens) }
        strip.performTouchInput { swipe(center, center + Offset(0f, -px(80))) }
        ui.runOnIdle { assertEquals(2, opens) }
    }

    fun testCancelHorizontalDownAndShortDragsDoNotOpen() {
        showComposer()
        val strip = node("chat-workspace-gesture-area")
        strip.performTouchInput {
            down(center); moveBy(Offset(0f, -px(80))); cancel()
        }
        strip.performTouchInput { swipe(center, center + Offset(px(100), 0f)) }
        strip.performTouchInput { swipe(center, center + Offset(0f, px(60))) }
        strip.performTouchInput { swipe(center, center + Offset(0f, -px(12))) }
        strip.performTouchInput {
            down(center)
            moveBy(Offset(px(40), 0f))
            moveBy(Offset(0f, -px(100)))
            up()
        }
        ui.runOnIdle { assertEquals(0, opens) }
    }

    fun testEditorUpwardDragOpensButLongPressAndVoiceKeepTheirGestures() {
        showComposer()
        node("chat-draft").performTouchInput {
            down(center); moveBy(Offset(0f, -px(64))); advanceEventTime(80)
        }
        ui.runOnIdle { assertEquals(0, opens) }
        node("chat-draft").performTouchInput { up() }
        ui.runOnIdle { assertEquals(1, opens) }
        node("chat-draft").performTouchInput { longClick() }
        ui.runOnIdle { assertEquals(1, opens) }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitHostWindowFocus(activity)
        node("chat-voice").performTouchInput { longClick() }
        awaitUniqueTag("chat-voice-mode-menu")
        ui.runOnIdle { assertEquals(1, opens); assertEquals(1, voiceLongPresses) }
    }

    fun testEditorTapAndRejectedDragsDoNotOpen() {
        showComposer()
        node("chat-draft").performTouchInput { click() }
        node("chat-draft").assertIsFocused()
        node("chat-draft").performTextReplacement("编辑保持可用")
        node("chat-draft").performTouchInput { swipe(center, center + Offset(px(80), 0f)) }
        node("chat-draft").performTouchInput { swipe(center, center + Offset(0f, -px(12))) }
        node("chat-draft").performTouchInput { down(center); moveBy(Offset(0f, -px(80))); cancel() }
        node("chat-draft").performTouchInput {
            down(center); advanceEventTime(700); moveBy(Offset(0f, -px(80))); up()
        }
        ui.runOnIdle { assertEquals(0, opens) }
    }

    fun testEmptyAndShortEditorUpwardDragAndGenerationCancellation() {
        showComposer()
        for (value in listOf("", "短草稿")) {
            ui.runOnIdle { composerDraft = value }
            node("chat-draft").performTouchInput { swipe(center, center + Offset(0f, -px(80))) }
        }
        ui.runOnIdle { assertEquals(2, opens) }
        node("chat-workspace-gesture-area").performTouchInput { down(center); moveBy(Offset(0f, -px(80))) }
        ui.runOnIdle { editorGeneration++ }
        node("chat-workspace-gesture-area").performTouchInput { up() }
        ui.runOnIdle { assertEquals(2, opens) }
        node("chat-draft").performTouchInput {
            down(0, center); down(1, center + Offset(px(12), 0f))
            moveTo(0, center + Offset(0f, -px(80))); up(0); up(1)
        }
        ui.runOnIdle { assertEquals(2, opens) }
    }

    fun testPointersSplitBetweenStripAndEditorNeverOpenWorkspace() {
        showComposer()
        val draftBounds = node("chat-draft").fetchSemanticsNode().boundsInRoot
        val stripBounds = node("chat-workspace-gesture-area").fetchSemanticsNode().boundsInRoot
        val surfaceBounds = node("chat-composer-surface").fetchSemanticsNode().boundsInRoot
        val editorStart = draftBounds.center - surfaceBounds.topLeft
        val stripStart = stripBounds.center - surfaceBounds.topLeft
        node("chat-composer-surface").performTouchInput {
            down(0, stripStart); down(1, editorStart)
            moveTo(0, stripStart + Offset(0f, -px(80)))
            moveTo(1, editorStart + Offset(0f, -px(80)))
            up(0); up(1)
        }
        ui.runOnIdle { assertEquals(0, opens) }
    }

    fun testOuterInitialOwnershipCannotOpenWorkspace() {
        for (consumeType in listOf(PointerEventType.Press, PointerEventType.Move, PointerEventType.Release, null)) {
            mainThread { activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    val pointers = remember { WorkspacePullUpPointers() }
                    Box(Modifier.fillMaxSize().observeWorkspacePointers(pointers).pointerInput(consumeType) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.type == consumeType) event.changes.forEach { it.consume() }
                            }
                        }
                    }) {
                        Box(Modifier.fillMaxWidth().height(200.dp).testTag("workspace-ownership-gesture")
                            .workspacePullUpGesture(pointers) { opens++ })
                    }
                }
            } }
            awaitHostWindowFocus(activity)
            node("workspace-ownership-gesture").performTouchInput { swipe(center, center + Offset(0f, -px(80))) }
            ui.runOnIdle { assertEquals(if (consumeType == null) 1 else 0, opens) }
        }
    }

    fun testLateSecondPointerInVoiceControlCancelsAndNextSingleSwipeWorks() {
        showComposer()
        val surfaceBounds = node("chat-composer-surface").fetchSemanticsNode().boundsInRoot
        val editorStart = node("chat-draft").fetchSemanticsNode().boundsInRoot.center - surfaceBounds.topLeft
        val voiceStart = node("chat-voice").fetchSemanticsNode().boundsInRoot.center - surfaceBounds.topLeft
        node("chat-composer-surface").performTouchInput {
            down(0, editorStart)
            moveTo(0, editorStart + Offset(0f, -px(64)))
            down(1, voiceStart)
            up(1); up(0)
        }
        ui.runOnIdle { assertEquals(0, opens) }
        node("chat-draft").performTouchInput { swipe(center, center + Offset(0f, -px(80))) }
        ui.runOnIdle { assertEquals(1, opens) }
    }

    private var choosing by mutableStateOf(false)
    private var listing by mutableStateOf(false)
    private var directory by mutableStateOf<app.daiyu.android.ui.components.WorkspaceDirectoryOption?>(null)
    private val original = "/private/workspace"
    private fun setDirectory(next: String) {
        path = next
        directory = app.daiyu.android.ui.components.WorkspaceDirectoryOption(next,
            if (next == "/") null else next.substringBeforeLast('/').ifEmpty { "/" }, listOf("项目 空格", "second"))
    }

    private fun showSheet(appearance: AppearanceSettings = AppearanceSettings()) {
        mainThread { activity.setContent {
            AgentAppTheme(appearance, applyInterfaceScale = false) {
                ConversationWorkspaceSheet(visible = visible,
                    sources = listOf(
                        WorkspaceSourceOption("private", "应用私有", "/private", original),
                        WorkspaceSourceOption("rootfs", "rootfs", "/approved/rootfs", "/")),
                    path = path, busy = busy, choosing = choosing, listing = listing,
                    directory = directory, error = if (choosing) "路径不可用" else null,
                    onBrowse = { choosing = true; setDirectory(path) },
                    onSourceChange = { sourceId = it; setDirectory(if (it == "rootfs") "/" else original) },
                    onNavigate = { setDirectory(it) }, onSources = { directory = null },
                    onCancel = { choosing = false; sourceId = "private"; path = original; directory = null },
                    onSave = { saves++ }, onDismiss = { dismisses++; visible = false })
            }
        } }
        awaitUniqueTag("conversation-workspace-sheet")
        awaitNodeWindowFocus(node("conversation-workspace-sheet"))
        ui.mainClock.advanceTimeBy(1_000)
        awaitVisualIdle()
    }

    fun testFirstPageHasOnlyPathCardAndCardOpensFolderPicker() {
        showSheet()
        node("workspace-path-card").assertExists()
        node("workspace-path").assertDoesNotExist()
        node("workspace-save").assertDoesNotExist()
        node("workspace-close").assertDoesNotExist()
        ui.onNodeWithText("来源").assertDoesNotExist()
        ui.onNodeWithText("路径不可用").assertDoesNotExist()
        node("workspace-path-card").performClick()
        node("workspace-current-path").assertTextEquals(original)
        node("workspace-folder:项目 空格").performClick()
        node("workspace-current-path").assertTextEquals("$original/项目 空格")
        node("workspace-save").performClick()
        ui.runOnIdle { assertEquals(1, saves); assertEquals(0, dismisses) }
        node("conversation-workspace-sheet").assertExists()
    }

    fun testCancelRestoresCardAndSourceRootCanSelectRootfs() {
        showSheet()
        node("workspace-path-card").performClick()
        ui.runOnIdle { directory = null }
        node("workspace-save").assertIsNotEnabled()
        node("workspace-source:rootfs").performClick()
        node("workspace-current-path").assertTextEquals("/")
        node("workspace-folder:项目 空格").performClick()
        node("workspace-current-path").assertTextEquals("/项目 空格")
        node("workspace-cancel").performClick()
        node("workspace-path-card").assertExists()
        node("workspace-save").assertDoesNotExist()
        ui.runOnIdle { assertEquals(original, path); assertEquals(0, saves) }
    }

    fun testFailedDirectoryCannotSaveAndNavigationCanRecover() {
        showSheet()
        node("workspace-path-card").performClick()
        ui.runOnIdle { directory = directory?.copy(selectable = false) }
        node("workspace-save").assertIsNotEnabled()
        node("workspace-folder:项目 空格").performClick()
        node("workspace-save").assertIsEnabled()
        node("workspace-cancel").performClick()
        node("workspace-path-card").assertExists()
        ui.runOnIdle { assertEquals(original, path); assertEquals(0, saves) }
    }

    fun testLoadingCanCancelButBusyCannotSubmitOrNavigate() {
        showSheet()
        node("workspace-path-card").performClick()
        ui.runOnIdle { listing = true }
        node("workspace-save").assertIsNotEnabled()
        node("workspace-cancel").performClick()
        ui.runOnIdle { assertEquals(0, saves); listing = false; choosing = true; busy = true }
        node("workspace-save").assertIsNotEnabled()
        node("workspace-parent").assertIsNotEnabled()
        node("workspace-cancel").assertIsNotEnabled()
        node("workspace-close").assertDoesNotExist()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        ui.waitUntil(5_000) { !visible }
        ui.runOnIdle { assertEquals(0, saves); assertEquals(1, dismisses) }
    }

    fun testPickerUsesIndependentSavedAccentsAndHasNoCloseInBothThemes() {
        for (dark in listOf(false, true)) {
            for ((primary, secondary) in listOf(
                null to null,
                0xff006b3c.toInt() to 0xffffa500.toInt(),
                0xffffffff.toInt() to 0xff000000.toInt(),
                0xff000000.toInt() to 0xffffffff.toInt(),
            )) {
                val appearance = AppearanceSettings(
                    themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT,
                    primaryAccentColor = primary, secondaryAccentColor = secondary)
                ui.runOnIdle { visible = true; choosing = false; busy = false; listing = false; directory = null; path = original }
                showSheet(appearance)
                node("workspace-close").assertDoesNotExist()
                ui.onNodeWithContentDescription("关闭").assertDoesNotExist()
                node("workspace-path-card").performTouchInput { click() }
                node("workspace-close").assertDoesNotExist()
                ui.onNodeWithContentDescription("关闭").assertDoesNotExist()
                val actions = resolveAppearanceActions(appearance, dark)
                assertPickerColorPair(node("workspace-save"), actions.primary, actions.onPrimary)
                assertPickerColorPair(node("workspace-cancel"), actions.secondaryContainer, actions.onSecondary)
                node("workspace-cancel").performTouchInput { click() }
                node("workspace-path-card").assertExists()
                ui.runOnIdle { assertEquals(original, path); assertEquals(0, saves); assertEquals(0, dismisses) }
            }
        }
    }

    private fun assertPickerColorPair(target: SemanticsNodeInteraction, background: Color, foreground: Color) {
        val pixels = target.captureToImage().toPixelMap()
        fun Color.matches(expected: Color) = abs(red - expected.red) < .035f &&
            abs(green - expected.green) < .035f && abs(blue - expected.blue) < .035f
        var backgroundCount = 0
        var foregroundCount = 0
        for (y in 0 until pixels.height) for (column in 0 until pixels.width) {
            val pixel = pixels[column, y]
            if (pixel.matches(background)) backgroundCount++
            if (pixel.matches(foreground)) foregroundCount++
        }
        if (background.alpha == 1f) {
            assertTrue("Missing picker button fill $background", backgroundCount > pixels.width * pixels.height * .04f)
        }
        assertTrue("Missing picker button content $foreground", foregroundCount > 8)
    }

    fun testBackAndOutsideDismissNeverSaveOnEitherPageEvenWhenBusy() {
        var expectedDismisses = 0
        for (picker in listOf(false, true)) {
            ui.runOnIdle {
                visible = true; choosing = picker; busy = true
                setDirectory("$original/unsaved")
            }
            showSheet()
            node("workspace-close").assertDoesNotExist()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            ui.waitUntil(5_000) { !visible }
            expectedDismisses++
            ui.runOnIdle { assertEquals(0, saves); assertEquals(expectedDismisses, dismisses); visible = true }
            awaitUniqueTag("conversation-workspace-sheet")
            awaitNodeWindowFocus(node("conversation-workspace-sheet"))
            ui.mainClock.advanceTimeBy(1_000)
            awaitVisualIdle()
            node("preview-dialog-scrim").performTouchInput { click(Offset(center.x, 2f)) }
            ui.waitUntil(5_000) { !visible }
            expectedDismisses++
            ui.runOnIdle { assertEquals(0, saves); assertEquals(expectedDismisses, dismisses) }
        }
    }

    fun testInvisibleOwnerDoesNotRenderOrDispatch() {
        visible = false
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                ConversationWorkspaceSheet(false, emptyList(), "", onBrowse = {}, onSourceChange = {},
                    onNavigate = {}, onSources = {}, onCancel = {},
                    onSave = { saves++ }, onDismiss = { dismisses++ })
            }
        } }
        node("conversation-workspace-sheet").assertDoesNotExist()
        ui.runOnIdle { assertEquals(0, saves); assertEquals(0, dismisses) }
    }
}
