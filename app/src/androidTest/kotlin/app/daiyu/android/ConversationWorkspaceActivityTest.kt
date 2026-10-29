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
import app.daiyu.android.ui.screens.chat.conversation.*

class ConversationWorkspaceActivityTest : WiredActivityTestCase() {
    private fun px(dp: Int): Float = dp * activity.resources.displayMetrics.density

    private var opens = 0
    private var voiceLongPresses = 0
    private var saves = 0
    private var dismisses = 0
    private var visible by mutableStateOf(true)
    private var busy by mutableStateOf(false)
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
        node("workspace-path-card:0").assertExists()
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
        node("workspace-add").performClick()
        awaitUniqueTag("workspace-current-path")
        ui.waitUntil(5_000) {
            ui.mainClock.advanceTimeByFrame()
            ui.onAllNodes(hasTestTag("workspace-save") and isEnabled()).fetchSemanticsNodes().size == 1
        }
        node("workspace-current-path").assertTextEquals("/workspace")
        node("workspace-error").assertDoesNotExist()
        mainThread {
            activity.getSystemService(android.content.ClipboardManager::class.java)
                .setPrimaryClip(android.content.ClipData.newPlainText("sentinel", "not-the-workspace-path"))
        }
        node("workspace-copy-path").performClick()
        mainThread {
            val clipboard = activity.getSystemService(android.content.ClipboardManager::class.java)
            assertEquals(java.io.File(fixture.application.filesDir, "workspace").canonicalPath,
                clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }
        node("workspace-cancel").performClick()
        node("workspace-path-card:0").assertExists()
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

    private var choices by mutableStateOf(listOf(
        app.daiyu.android.ui.components.WorkspaceItemOption("/private/workspace"),
        app.daiyu.android.ui.components.WorkspaceItemOption("/private/second")))
    private var activeIndex by mutableStateOf(0)
    private var selectionMode by mutableStateOf(false)
    private var selectedIndices by mutableStateOf(emptySet<Int>())
    private var switches = 0
    private var deletions = 0
    private var edits = 0
    private val copiedPaths = mutableListOf<String>()
    private var editingWorkspace by mutableStateOf(false)
    private var choosing by mutableStateOf(false)
    private var listing by mutableStateOf(false)
    private var directory by mutableStateOf<app.daiyu.android.ui.components.WorkspaceDirectoryOption?>(null)
    private val original = "/private/workspace"
    private fun setDirectory(next: String) {
        path = next
        directory = app.daiyu.android.ui.components.WorkspaceDirectoryOption(next,
            if (next == original) null else next.substringBeforeLast('/').ifEmpty { "/" }, listOf("项目 空格", "second"))
    }

    private fun showSheet(appearance: AppearanceSettings = AppearanceSettings(),
        workspaceRoots: List<String> = listOf(original)) {
        mainThread { activity.setContent {
            AgentAppTheme(appearance, applyInterfaceScale = false) {
                ConversationWorkspaceSheet(visible = visible,
                    path = path, busy = busy, choosing = choosing, listing = listing,
                    workspaces = choices, activeWorkspaceIndex = activeIndex,
                    workspaceRoots = workspaceRoots, onCopyPath = { copiedPaths += it },
                    selectionMode = selectionMode, selectedWorkspaces = selectedIndices,
                    onSelectionChange = { mode, selected -> selectionMode = mode; selectedIndices = selected },
                    onSelectWorkspace = { switches++; activeIndex = it },
                    editingWorkspace = editingWorkspace,
                    onEditWorkspace = { index -> edits++; editingWorkspace = true; choosing = true; setDirectory(choices[index].path) },
                    onDeleteWorkspaces = { removed ->
                        deletions++; choices = choices.filterIndexed { index, _ -> index !in removed }
                        activeIndex = if (choices.isEmpty()) -1 else 0
                        selectionMode = false; selectedIndices = emptySet()
                    },
                    directory = directory, error = if (choosing) "路径不可用" else null,
                    onBrowse = { choosing = true; setDirectory(path) },
                    onNavigate = { setDirectory(it) },
                    onCancel = { choosing = false; path = original; directory = null },
                    onSave = { saves++ }, onDismiss = { dismisses++; visible = false })
            }
        } }
        awaitUniqueTag("conversation-workspace-sheet")
        awaitDialogEnter(node("conversation-workspace-sheet"))
        awaitNodeWindowFocus(node("conversation-workspace-sheet"))
        awaitDialogImeFrame(node("conversation-workspace-sheet"), visible = false)
    }

    fun testFirstPageHasFixedListAndAddOpensFolderPicker() {
        showSheet()
        node("workspace-path-card:0").assertExists()
        node("workspace-path").assertDoesNotExist()
        node("workspace-save").assertDoesNotExist()
        node("workspace-close").assertDoesNotExist()
        ui.onNodeWithText("来源").assertDoesNotExist()
        ui.onNodeWithText("路径不可用").assertDoesNotExist()
        node("workspace-add").performClick()
        node("workspace-current-path").assertTextEquals("/workspace")
        node("workspace-folder:项目 空格").performClick()
        node("workspace-current-path").assertTextEquals("/workspace/项目 空格")
        node("workspace-save").performClick()
        ui.runOnIdle { assertEquals(1, saves); assertEquals(0, dismisses) }
        node("conversation-workspace-sheet").assertExists()
    }

    fun testCardsShowNamesAndRelativeLocationsWithoutChangingSelection() {
        val child = "$original/客户甲/common"
        val other = "$original/客户乙/common"
        ui.runOnIdle { choices = listOf(original, child, other).map { app.daiyu.android.ui.components.WorkspaceItemOption(it) } }
        showSheet()
        ui.onNodeWithTag("workspace-name:0", useUnmergedTree = true).assertTextEquals("/workspace")
        ui.onNodeWithTag("workspace-location:0", useUnmergedTree = true).assertDoesNotExist()
        ui.onNodeWithTag("workspace-name:1", useUnmergedTree = true).assertTextEquals("common")
        ui.onNodeWithTag("workspace-location:1", useUnmergedTree = true).assertTextEquals("/workspace / 客户甲 / common")
        ui.onNodeWithTag("workspace-location:2", useUnmergedTree = true).assertTextEquals("/workspace / 客户乙 / common")
        node("workspace-path-card:2").performClick()
        node("workspace-path-card:1").performTouchInput { longClick() }
        ui.runOnIdle {
            assertEquals(1, switches); assertEquals(2, activeIndex)
            assertEquals(setOf(1), selectedIndices); assertEquals(0, edits); assertEquals(0, saves)
        }
        node("workspace-selection-cancel").performClick()
        node("workspace-edit:2").performClick()
        node("workspace-copy-path").performClick()
        ui.runOnIdle { assertEquals(listOf(other), copiedPaths); assertEquals(1, switches); assertEquals(0, saves) }
    }

    fun testActualRootNameOnlyOmitsItsParentsAndCopiesUnchangedAbsolutePath() {
        val root = "/private/files/ 我的 项目 🐟 "
        val child = "$root/客户/src"
        ui.runOnIdle {
            choices = listOf(root, child).map { app.daiyu.android.ui.components.WorkspaceItemOption(it) }
        }
        showSheet(workspaceRoots = listOf(root))
        ui.onNodeWithTag("workspace-name:0", useUnmergedTree = true).assertTextEquals("/ 我的 项目 🐟 ")
        ui.onNodeWithTag("workspace-location:0", useUnmergedTree = true).assertDoesNotExist()
        ui.onNodeWithTag("workspace-name:1", useUnmergedTree = true).assertTextEquals("src")
        ui.onNodeWithTag("workspace-location:1", useUnmergedTree = true).assertTextEquals("/ 我的 项目 🐟  / 客户 / src")
        node("workspace-edit:1").performClick()
        node("workspace-current-path").assertTextEquals("/ 我的 项目 🐟 /客户/src")
        node("workspace-copy-path").performClick()
        ui.runOnIdle {
            assertEquals(listOf(child), copiedPaths)
            assertEquals(listOf(root, child), choices.map { it.path })
            assertEquals(0, switches)
            assertEquals(0, saves)
        }
    }

    fun testDeepPathKeepsRootAndTailAndCopiesExactUnshortenedValue() {
        val leaf = " 项目 '🐟' 空格 "
        val full = original + "/" + (1..16).joinToString("/") { "中间目录-$it" } + "/$leaf/src"
        ui.runOnIdle { choices = listOf(app.daiyu.android.ui.components.WorkspaceItemOption(full)) }
        showSheet()
        ui.onNodeWithTag("workspace-name:0", useUnmergedTree = true).assertTextEquals("src")
        ui.onNodeWithTag("workspace-location:0", useUnmergedTree = true).assertTextEquals("/workspace / … / $leaf / src")
        val footer = node("workspace-add").fetchSemanticsNode().boundsInRoot.bottom
        val card = node("workspace-path-card:0").fetchSemanticsNode().boundsInRoot
        val edit = node("workspace-edit:0").fetchSemanticsNode().boundsInRoot
        assertTrue(edit.left >= card.left && edit.right <= card.right)
        node("workspace-edit:0").performClick()
        node("workspace-current-path").assertTextEquals(full.replaceFirst(original, "/workspace"))
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node("workspace-current-path").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals(full.replaceFirst(original, "/workspace"), layout.layoutInput.text.text)
        assertFalse(layout.hasVisualOverflow)
        for (line in 0 until layout.lineCount) assertFalse(layout.isLineEllipsized(line))
        node("workspace-copy-path").performClick()
        ui.runOnIdle { assertEquals(listOf(full), copiedPaths); assertEquals(0, switches); assertEquals(0, saves) }
        node("workspace-cancel").performClick()
        assertEquals(footer, node("workspace-add").fetchSemanticsNode().boundsInRoot.bottom, 1f)
        ui.runOnIdle { assertEquals(full, choices.single().path); assertEquals(0, saves) }
    }

    fun testCopyIsDisabledWhileLoadingOrSavingAndDoesNotDispatchWhenHidden() {
        showSheet()
        node("workspace-copy-path").assertDoesNotExist()
        node("workspace-edit:0").performClick()
        ui.runOnIdle { listing = true }
        node("workspace-copy-path").assertIsNotEnabled()
        ui.runOnIdle { listing = false; busy = true }
        node("workspace-copy-path").assertIsNotEnabled()
        ui.runOnIdle { busy = false }
        node("workspace-copy-path").performClick()
        ui.runOnIdle { assertEquals(listOf(original), copiedPaths); assertEquals(0, switches); assertEquals(0, saves) }
    }

    fun testLongPressSelectsWithoutSwitchAndSelectionCanBeEmptyOrCancelled() {
        showSheet()
        node("workspace-path-card:1").performClick()
        ui.runOnIdle { assertEquals(1, switches); assertEquals(1, activeIndex) }
        node("workspace-path-card:0").performTouchInput { longClick() }
        node("workspace-delete").assertIsEnabled()
        node("workspace-add").assertDoesNotExist()
        ui.runOnIdle { assertEquals(1, switches); assertEquals(setOf(0), selectedIndices) }
        node("workspace-path-card:0").performClick()
        node("workspace-delete").assertIsNotEnabled()
        ui.runOnIdle { assertTrue(selectionMode); assertTrue(selectedIndices.isEmpty()) }
        node("workspace-selection-cancel").performClick()
        node("workspace-add").assertExists()
        ui.runOnIdle { assertEquals(0, deletions); assertEquals(1, switches); assertEquals(1, activeIndex) }
    }

    fun testEditButtonDoesNotSwitchAndIsHiddenDuringMultiSelection() {
        showSheet()
        node("workspace-edit:1").performClick()
        ui.onNodeWithText("更改工作目录").assertExists()
        node("workspace-current-path").assertTextEquals("/private/second")
        ui.runOnIdle { assertEquals(1, edits); assertEquals(0, switches); assertEquals(0, activeIndex) }
        node("workspace-cancel").performClick()
        node("workspace-path-card:0").performTouchInput { longClick() }
        node("workspace-edit:0").assertDoesNotExist()
        node("workspace-edit:1").assertDoesNotExist()
        node("workspace-selection-cancel").performClick()
        node("workspace-edit:1").assertExists()
        ui.runOnIdle { assertEquals(0, switches); assertEquals(0, saves); assertEquals(1, edits) }
    }

    private data class WorkspaceFrame(
        val sheet: androidx.compose.ui.geometry.Rect,
        val footer: androidx.compose.ui.geometry.Rect,
        val windowWidth: Int, val windowHeight: Int,
        val windowX: Int, val windowY: Int,
        val token: android.os.IBinder,
        val systemBars: androidx.core.graphics.Insets,
        val imeBottom: Int,
    )

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun settledWorkspaceFrame(footerTag: String): WorkspaceFrame {
        val sheet = node("conversation-workspace-sheet").fetchSemanticsNode()
        val footer = node(footerTag).assertIsDisplayed().fetchSemanticsNode()
        assertSame(sheet.root, footer.root)
        val view = (sheet.root as androidx.compose.ui.platform.ViewRootForTest).view
        return awaitStableWindowFrame(view) {
            val insets = androidx.core.view.ViewCompat.getRootWindowInsets(view)
                ?: return@awaitStableWindowFrame null
            val token = view.windowToken ?: return@awaitStableWindowFrame null
            if (insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) ||
                !sheet.layoutInfo.isPlaced || !sheet.layoutInfo.coordinates.isAttached ||
                !footer.layoutInfo.isPlaced || !footer.layoutInfo.coordinates.isAttached)
                return@awaitStableWindowFrame null
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            WorkspaceFrame(sheet.boundsInRoot, footer.boundsInRoot, view.width, view.height,
                location[0], location[1], token,
                insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()),
                insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom)
        }
    }

    fun testMultiDeleteIsOneCallbackAndFooterDoesNotMove() {
        showSheet()
        val before = settledWorkspaceFrame("workspace-add")
        node("workspace-path-card:0").performTouchInput { longClick() }
        node("workspace-path-card:1").performClick()
        val after = settledWorkspaceFrame("workspace-delete")
        val diagnostic = "before=$before after=$after"
        assertEquals(diagnostic, before.windowWidth, after.windowWidth)
        assertEquals(diagnostic, before.windowHeight, after.windowHeight)
        assertEquals(diagnostic, before.windowX, after.windowX)
        assertEquals(diagnostic, before.windowY, after.windowY)
        assertEquals(diagnostic, before.token, after.token)
        assertEquals(diagnostic, before.systemBars, after.systemBars)
        assertEquals(diagnostic, before.imeBottom, after.imeBottom)
        assertEquals(diagnostic, before.sheet.top, after.sheet.top, 1f)
        assertEquals(diagnostic, before.footer.bottom, after.footer.bottom, 1f)
        ui.runOnIdle { assertEquals(setOf(0, 1), selectedIndices); assertEquals(0, switches) }
        node("workspace-delete").performClick()
        node("workspace-add").assertExists()
        node("workspace-path-card:0").assertDoesNotExist()
        ui.runOnIdle { assertEquals(1, deletions); assertTrue(choices.isEmpty()); assertEquals(0, switches) }
    }

    fun testWorkspaceRootStopsParentNavigationAndCancelKeepsBinding() {
        showSheet()
        node("workspace-add").performClick()
        node("workspace-parent").assertIsNotEnabled()
        node("workspace-save").assertIsEnabled()
        node("workspace-source:rootfs").assertDoesNotExist()
        node("workspace-folder:项目 空格").performClick()
        node("workspace-parent").assertIsEnabled()
        node("workspace-current-path").assertTextEquals("/workspace/项目 空格")
        node("workspace-parent").performClick()
        node("workspace-current-path").assertTextEquals("/workspace")
        node("workspace-parent").assertIsNotEnabled()
        ui.runOnIdle { directory = null }
        node("workspace-save").assertIsNotEnabled()
        node("workspace-parent").assertIsNotEnabled()
        node("workspace-source:rootfs").assertDoesNotExist()
        node("workspace-cancel").performClick()
        node("workspace-path-card:0").assertExists()
        node("workspace-save").assertDoesNotExist()
        ui.runOnIdle { assertEquals(original, path); assertEquals(0, saves) }
    }

    fun testFailedDirectoryCannotSaveAndNavigationCanRecover() {
        showSheet()
        node("workspace-add").performClick()
        ui.runOnIdle { directory = directory?.copy(selectable = false) }
        node("workspace-save").assertIsNotEnabled()
        node("workspace-folder:项目 空格").performClick()
        node("workspace-save").assertIsEnabled()
        node("workspace-cancel").performClick()
        node("workspace-path-card:0").assertExists()
        ui.runOnIdle { assertEquals(original, path); assertEquals(0, saves) }
    }

    fun testLoadingCanCancelButBusyCannotSubmitOrNavigate() {
        showSheet()
        node("workspace-add").performClick()
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

    fun testPickerUsesDefaultActionRolesAndHasNoCloseInBothThemes() {
        for (dark in listOf(false, true)) {
            for (pureBlack in listOf(false, true)) {
                val appearance = AppearanceSettings(
                    themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT,
                    pureBlackEnabled = pureBlack)
                ui.runOnIdle { visible = true; choosing = false; busy = false; listing = false; directory = null; path = original }
                showSheet(appearance)
                node("workspace-close").assertDoesNotExist()
                ui.onNodeWithContentDescription("关闭").assertDoesNotExist()
                node("workspace-add").performTouchInput { click() }
                node("workspace-close").assertDoesNotExist()
                ui.onNodeWithContentDescription("关闭").assertDoesNotExist()
                val actions = resolveAppearanceActions(dark)
                assertPickerColorPair(node("workspace-save"), actions.primary, actions.onPrimary)
                assertPickerColorPair(node("workspace-create-folder"), actions.primary, actions.onPrimary)
                assertPickerColorPair(node("workspace-cancel"), actions.secondaryContainer, actions.onSecondary)
                node("workspace-cancel").performTouchInput { click() }
                node("workspace-path-card:0").assertExists()
                ui.runOnIdle { assertEquals(original, path); assertEquals(0, saves); assertEquals(0, dismisses) }
            }
        }
    }

    private fun assertPickerColorPair(target: SemanticsNodeInteraction, background: Color, foreground: Color) {
        val pixels = captureScreenNode(target).toPixelMap()
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
                ConversationWorkspaceSheet(false, "", onBrowse = {},
                    onNavigate = {}, onCancel = {},
                    onSave = { saves++ }, onDismiss = { dismisses++ })
            }
        } }
        node("conversation-workspace-sheet").assertDoesNotExist()
        ui.runOnIdle { assertEquals(0, saves); assertEquals(0, dismisses) }
    }
}
