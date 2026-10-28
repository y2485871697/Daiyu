package app.daiyu.android

import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.components.MiuixScaffoldPage
import app.daiyu.android.ui.screens.settings.rememberLinuxRootfsPanel
import java.io.File
import java.util.UUID

class WorkspaceEntryActionsActivityTest : WiredActivityTestCase() {
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Suppress("UNCHECKED_CAST")
    private fun retainedLongClickCallback(target: SemanticsNodeInteraction): () -> Unit {
        val callback = target.fetchSemanticsNode().layoutInfo.getModifierInfo().asSequence()
            .map { it.modifier }
            .filterIsInstance<androidx.compose.ui.platform.InspectableValue>()
            .filter { it.nameFallback == "combinedClickable" }
            .flatMap { it.inspectableElements }
            .single { it.name == "onLongClick" }.value
        return callback as () -> Unit
    }


    fun testRootfsProtectsSystemDirectoryAndUsesHorizontalActionRowsForUserDirectory() {
        val fixtureRoot = File(activity.filesDir, "workspace-actions-${UUID.randomUUID()}").apply { mkdir() }
        val rootfs = File(fixtureRoot, "rootfs").apply { mkdir() }
        File(rootfs, "etc").mkdir()
        val project = File(rootfs, "project").apply { mkdir() }
        File(project, "keep").writeText("keep")
        try {
            mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                val panel = rememberLinuxRootfsPanel(rootfs)
                MiuixScaffoldPage("Rootfs文件测试", onBack = {}, actions = { panel.actions() }) { item { panel() } }
            } } }
            awaitUniqueTag("linux-rootfs-entry:etc")
            reveal("linux-rootfs-entry:etc", "Rootfs文件测试").performTouchInput { longClick() }
            node("workspace-entry-action-sheet").assertDoesNotExist()
            assertTrue(File(rootfs, "etc").isDirectory)
            val retainedRow = retainedLongClickCallback(reveal("linux-rootfs-entry:project", "Rootfs文件测试"))
            realPauseResume(activity)
            awaitUniqueTag("linux-rootfs-entry:project")
            mainThread { retainedRow() }
            node("workspace-entry-action-sheet").assertDoesNotExist()
            ui.waitUntil(5_000) {
                ui.mainClock.advanceTimeByFrame()
                ui.onAllNodes(hasTestTag("linux-rootfs-entry:project") and isEnabled()).fetchSemanticsNodes().size == 1
            }
            reveal("linux-rootfs-entry:project", "Rootfs文件测试").performTouchInput { longClick() }
            awaitUniqueTag("workspace-entry-action:rename")
            val icon = ui.onNodeWithTag("workspace-entry-icon:rename", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val label = ui.onNodeWithTag("workspace-entry-label:rename", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val arrow = ui.onNodeWithTag("workspace-entry-chevron:rename", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue(icon.right <= label.left)
            assertTrue(label.right <= arrow.left)
            assertTrue(kotlin.math.abs(icon.center.y - arrow.center.y) < 2f)
            node("workspace-entry-action:cancel").assertDoesNotExist()
            node("preview-dialog-scrim").performTouchInput { click(Offset(center.x, 4f)) }
            node("workspace-entry-action-sheet").assertDoesNotExist()
            assertEquals("keep", File(project, "keep").readText())
            reveal("linux-rootfs-entry:project", "Rootfs文件测试").performTouchInput { longClick() }
            node("workspace-entry-action:rename").performClick()
            awaitUniqueTag("prompt-input")
            ui.onNodeWithContentDescription("关闭").assertDoesNotExist()
            node("prompt-input").performTextClearance()
            ui.onNodeWithText("文件夹名称").assertExists()
            node("prompt-input").performTextReplacement("renamed")
            node("dialog-confirm").performClick()
            awaitUniqueTag("linux-rootfs-entry:renamed")
            assertEquals("keep", File(rootfs, "renamed/keep").readText())
            reveal("linux-rootfs-entry:renamed", "Rootfs文件测试").performTouchInput { longClick() }
            node("workspace-entry-action:delete").performClick()
            node("dialog-cancel").performClick()
            assertTrue(File(rootfs, "renamed").isDirectory)
            reveal("linux-rootfs-entry:renamed", "Rootfs文件测试").performTouchInput { longClick() }
            node("workspace-entry-action:delete").performClick()
            node("dialog-confirm").performClick()
            ui.waitUntil(5_000) { !File(rootfs, "renamed").exists() }
            assertTrue(File(rootfs, "etc").isDirectory)
        } finally { mainThread { activity.setContent {} }; fixtureRoot.deleteRecursively() }
    }

    fun testRealConversationPickerRenamesAndDeletesWithoutSavingBindings() {
        awaitControl("workspace readers idle") { !fixture.controller.dataBusy }
        val root = File(fixture.application.filesDir, "workspace").apply { mkdir() }
        val name = "actions-${UUID.randomUUID()}"
        val file = File(root, "$name.txt").apply { writeText("keep") }
        val renamed = File(root, "$name-renamed.txt")
        val folder = File(root, name).apply { mkdir() }
        File(folder, "child").writeText("remove")
        val before = fixture.database.workspaceBinding(null)
        try {
            awaitHostWindowFocus(activity)
            node("chat-workspace-gesture-area").performTouchInput { swipe(center, center + Offset(0f, -100f)) }
            awaitUniqueTag("workspace-add")
            node("workspace-add").performClick()
            awaitUniqueTag("workspace-file:$name.txt")
            node("workspace-file:$name.txt").performTouchInput { longClick() }
            node("workspace-entry-action:cancel").assertDoesNotExist()
            awaitNodeWindowFocus(node("workspace-entry-action:rename"))
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            node("workspace-entry-action-sheet").assertDoesNotExist()
            node("conversation-workspace-sheet").assertExists()
            node("workspace-save").assertIsEnabled()
            assertEquals(before, fixture.database.workspaceBinding(null))
            assertEquals("keep", file.readText())
            node("workspace-file:$name.txt").performTouchInput { longClick() }
            node("workspace-entry-action:rename").performClick()
            awaitUniqueTag("prompt-input")
            ui.onNodeWithContentDescription("关闭").assertDoesNotExist()
            node("prompt-input").performTextClearance()
            ui.onNodeWithText("文件夹名称").assertExists()
            node("prompt-input").performTextReplacement(renamed.name)
            node("dialog-confirm").performClick()
            awaitUniqueTag("workspace-file:${renamed.name}")
            assertFalse(file.exists())
            assertEquals("keep", renamed.readText())
            node("workspace-save").assertIsEnabled()
            assertEquals(before, fixture.database.workspaceBinding(null))
            node("workspace-folder:$name").performTouchInput { longClick() }
            node("workspace-entry-action:delete").performClick()
            node("dialog-confirm").performClick()
            ui.waitUntil(5_000) { !folder.exists() }
            node("workspace-save").assertIsEnabled()
            assertEquals(before, fixture.database.workspaceBinding(null))
            node("workspace-file:${renamed.name}").performTouchInput { longClick() }
            val retained = retainedClickCallback(node("workspace-entry-action:delete"))
            realPauseResume(activity)
            mainThread { retained() }
            assertTrue(renamed.isFile)
            node("dialog-confirm").assertDoesNotExist()
            node("workspace-cancel").performClick()
            assertEquals(before, fixture.database.workspaceBinding(null))
        } finally { file.delete(); renamed.delete(); folder.deleteRecursively() }
    }
}
