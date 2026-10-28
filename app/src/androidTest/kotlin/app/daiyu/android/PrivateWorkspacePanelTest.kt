package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.components.MiuixScaffoldPage
import app.daiyu.android.ui.screens.settings.rememberPrivateWorkspacePanel
import app.daiyu.android.workspace.PrivateWorkspaceFiles
import app.daiyu.android.workspace.PrivateWorkspacePaths
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class PrivateWorkspacePanelTest : WiredActivityTestCase() {
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


    fun testPrivateBrowseDeleteConfirmationAndRetainedPageRevocation() {
        assertPrivateBrowseDeleteConfirmationAndRetainedPageRevocation(compact = false)
    }
    fun testCompactPrivateBrowseLongPressWithoutMoreMenuAndRetainedPageRevocation() {
        assertPrivateBrowseDeleteConfirmationAndRetainedPageRevocation(compact = true)
    }
    private fun assertPrivateBrowseDeleteConfirmationAndRetainedPageRevocation(compact: Boolean) {
        val root = File(instrumentation.targetContext.cacheDir, "private-panel-${UUID.randomUUID()}").apply { mkdir() }
        val paths = PrivateWorkspacePaths(root)
        val workspace = paths.ensureRoot()
        val sub = File(workspace, "sub").apply { mkdir() }
        File(sub, "keep.txt").writeText("keep")
        val gate = AtomicBoolean(true)
        val access = PrivateWorkspaceFiles(paths)
        try {
            mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = true) {
                val panel = rememberPrivateWorkspacePanel(access, gate, compact = compact)
                MiuixScaffoldPage("私有文件测试", onBack = { gate.set(false) }, actions = { panel.actions() }) { item { panel() } }
            } } }
            ui.waitUntil(5_000) { ui.onAllNodesWithTag("workspace-private-entry:sub").fetchSemanticsNodes().size == 1 }
            reveal("workspace-private-entry:sub", "私有文件测试").performClick()
            ui.waitUntil(5_000) { ui.onAllNodesWithTag("workspace-private-entry:sub/keep.txt").fetchSemanticsNodes().size == 1 }
            node("workspace-private-actions:sub/keep.txt").assertDoesNotExist()
            reveal("workspace-private-entry:sub/keep.txt", "私有文件测试").performTouchInput { longClick() }
            node("workspace-entry-action:cancel").assertDoesNotExist()
            node("workspace-entry-action:delete").performClick()
            ui.onNodeWithText("删除文件").assertIsDisplayed()
            awaitUniqueTag("dialog-cancel")
            node("dialog-cancel").assertIsDisplayed().assertIsEnabled().performClick()
            ui.onNodeWithText("删除文件").assertDoesNotExist()
            assertEquals("keep", File(sub, "keep.txt").readText())
            reveal("workspace-private-entry:sub/keep.txt", "私有文件测试").performTouchInput { longClick() }
            node("workspace-entry-action:delete").performClick()
            ui.onNodeWithText("删除文件").assertIsDisplayed()
            awaitUniqueTag("dialog-confirm")
            node("dialog-confirm").assertIsDisplayed().assertIsEnabled().performClick()
            ui.waitUntil(5_000) { !File(sub, "keep.txt").exists() }
            ui.waitUntil(5_000) { ui.onAllNodesWithText("条目已删除").fetchSemanticsNodes().isNotEmpty() }
            reveal("workspace-private-parent", "私有文件测试").performClick()
            ui.waitUntil(5_000) { ui.onAllNodesWithTag("workspace-private-entry:sub").fetchSemanticsNodes().size == 1 }
            val retained = retainedLongClickCallback(reveal("workspace-private-entry:sub", "私有文件测试"))
            realPauseResume(activity, atPause = { retained() })
            mainThread { retained() }
            ui.onNodeWithText("删除文件").assertDoesNotExist()
            node("workspace-entry-action-sheet").assertDoesNotExist()
            mainThread { gate.set(false); retained() }
            ui.onNodeWithText("删除文件").assertDoesNotExist()
            node("workspace-entry-action-sheet").assertDoesNotExist()
            assertTrue(sub.isDirectory)
        } finally { gate.set(false); ui.waitForIdle(); root.deleteRecursively() }
    }

    fun testPrivateRenameUsesCreateHintAndCancelPreservesOriginalFile() {
        for (compact in listOf(false, true)) {
            val storage = File(instrumentation.targetContext.cacheDir, "rename-panel-${UUID.randomUUID()}").apply { mkdir() }
            val paths = PrivateWorkspacePaths(storage)
            val root = paths.ensureRoot()
            val original = File(root, "keep.txt").apply { writeText("keep") }
            val renamed = File(root, "改名 文件.txt")
            val gate = AtomicBoolean(true)
            val access = PrivateWorkspaceFiles(paths)
            try {
                mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = true) {
                    val panel = rememberPrivateWorkspacePanel(access, gate, compact = compact)
                    MiuixScaffoldPage("私有文件改名测试", onBack = { gate.set(false) }) { item { panel() } }
                } } }
                awaitUniqueTag("workspace-private-entry:keep.txt")
                reveal("workspace-private-entry:keep.txt", "私有文件改名测试").performTouchInput { longClick() }
                node("workspace-entry-action:rename").performClick()
                awaitUniqueTag("prompt-input")
                node("prompt-input").assertTextContains("keep.txt").performTextClearance()
                ui.onNodeWithText("文件夹名称").assertExists()
                ui.onNodeWithContentDescription("关闭").assertDoesNotExist()
                node("prompt-input").performTextReplacement(renamed.name)
                node("dialog-cancel").performClick()
                assertEquals("keep", original.readText())
                assertFalse(renamed.exists())
                reveal("workspace-private-entry:keep.txt", "私有文件改名测试").performTouchInput { longClick() }
                node("workspace-entry-action:rename").performClick()
                awaitUniqueTag("prompt-input")
                node("prompt-input").assertTextContains("keep.txt").performTextReplacement("../invalid")
                node("dialog-confirm").performClick()
                node("prompt-input").assertTextContains("../invalid")
                assertEquals("keep", original.readText())
                node("prompt-input").performTextReplacement(renamed.name)
                node("dialog-confirm").performClick()
                awaitUniqueTag("workspace-private-entry:${renamed.name}")
                assertFalse(original.exists())
                assertEquals("keep", renamed.readText())
            } finally { gate.set(false); mainThread { activity.setContent {} }; ui.waitForIdle(); storage.deleteRecursively() }
        }
    }

    fun testCompactPrivateCreateCancelConfirmAndRetainedConfirmExpire() {
        val storage = File(instrumentation.targetContext.cacheDir, "create-panel-${UUID.randomUUID()}").apply { mkdir() }
        val paths = PrivateWorkspacePaths(storage)
        val root = paths.ensureRoot()
        val access = PrivateWorkspaceFiles(paths)
        val gate = AtomicBoolean(true)
        val ownerActive = AtomicBoolean(true)
        try {
            mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = true) {
                val panel = rememberPrivateWorkspacePanel(access, gate, compact = true, ownerCurrent = ownerActive::get)
                MiuixScaffoldPage("创建目录测试", onBack = { gate.set(false) }) { item { panel() } }
            } } }
            awaitUniqueTag("workspace-private-create-folder")
            node("workspace-private-create-folder").assertIsEnabled().performClick()
            awaitUniqueTag("prompt-input")
            node("prompt-input").performTextReplacement("cancelled")
            node("dialog-cancel").performClick()
            assertFalse(File(root, "cancelled").exists())
            node("workspace-private-create-folder").performClick()
            awaitUniqueTag("prompt-input")
            node("prompt-input").performTextReplacement("新建 文件夹")
            node("dialog-confirm").performClick()
            ui.waitUntil(5_000) { File(root, "新建 文件夹").isDirectory }
            awaitUniqueTag("workspace-private-entry:新建 文件夹")
            node("workspace-private-create-folder").performClick()
            awaitUniqueTag("prompt-input")
            node("prompt-input").performTextReplacement("stale")
            val retained = retainedClickCallback(node("dialog-confirm"))
            mainThread { ownerActive.set(false); retained() }
            assertTrue(gate.get())
            assertFalse(File(root, "stale").exists())
        } finally { gate.set(false); mainThread { activity.setContent {} }; ui.waitForIdle(); storage.deleteRecursively() }
    }

    fun testRootfsPathPlusUsesSameDialogAndRefreshesWithoutImportOrDelete() {
        val storage = File(instrumentation.targetContext.filesDir, "rootfs-create-${UUID.randomUUID()}").apply { mkdir() }
        val gate = AtomicBoolean(true)
        val access = PrivateWorkspaceFiles(PrivateWorkspacePaths.existingRoot(instrumentation.targetContext.filesDir, storage),
            allowDirectoryCreation = true)
        try {
            mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = true) {
                val panel = app.daiyu.android.ui.screens.settings.rememberLinuxRootfsPanel(storage, parentVisit = gate,
                    createDirectory = { directory, name, cancelled -> access.createDirectory(directory, name, cancelled) })
                MiuixScaffoldPage("Rootfs创建测试", onBack = { gate.set(false) }) { item { panel() } }
            } } }
            awaitUniqueTag("linux-rootfs-create-folder")
            node("linux-rootfs-create-folder").assertIsEnabled().performClick()
            awaitUniqueTag("prompt-input")
            node("prompt-input").performTextReplacement("new-rootfs-directory")
            node("dialog-confirm").performClick()
            ui.waitUntil(5_000) { File(storage, "new-rootfs-directory").isDirectory }
            awaitUniqueTag("linux-rootfs-entry:new-rootfs-directory")
            node("workspace-private-import").assertDoesNotExist()
            assertTrue(gate.get())
        } finally { gate.set(false); mainThread { activity.setContent {} }; ui.waitForIdle(); storage.deleteRecursively() }
    }
}
