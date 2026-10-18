package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
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
    fun testPrivateBrowseDeleteConfirmationAndRetainedPageRevocation() {
        val root = File(instrumentation.targetContext.cacheDir, "private-panel-${UUID.randomUUID()}").apply { mkdir() }
        val paths = PrivateWorkspacePaths(root)
        val workspace = paths.ensureRoot()
        val sub = File(workspace, "sub").apply { mkdir() }
        File(sub, "keep.txt").writeText("keep")
        val gate = AtomicBoolean(true)
        val access = PrivateWorkspaceFiles(paths)
        try {
            mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = true) {
                val panel = rememberPrivateWorkspacePanel(access, gate)
                MiuixScaffoldPage("私有文件测试", onBack = { gate.set(false) }) { item { panel() } }
            } } }
            ui.waitUntil(5_000) { ui.onAllNodesWithTag("workspace-private-entry:sub").fetchSemanticsNodes().size == 1 }
            reveal("workspace-private-entry:sub", "私有文件测试").performClick()
            ui.waitUntil(5_000) { ui.onAllNodesWithTag("workspace-private-entry:sub/keep.txt").fetchSemanticsNodes().size == 1 }
            reveal("workspace-private-delete:sub/keep.txt", "私有文件测试").performClick()
            ui.onNodeWithText("删除私有文件").assertIsDisplayed()
            awaitUniqueTag("dialog-cancel")
            node("dialog-cancel").assertIsDisplayed().assertIsEnabled().performClick()
            ui.onNodeWithText("删除私有文件").assertDoesNotExist()
            assertEquals("keep", File(sub, "keep.txt").readText())
            reveal("workspace-private-delete:sub/keep.txt", "私有文件测试").performClick()
            ui.onNodeWithText("删除私有文件").assertIsDisplayed()
            awaitUniqueTag("dialog-confirm")
            node("dialog-confirm").assertIsDisplayed().assertIsEnabled().performClick()
            ui.waitUntil(5_000) { !File(sub, "keep.txt").exists() }
            ui.waitUntil(5_000) { ui.onAllNodesWithText("私有条目已删除").fetchSemanticsNodes().isNotEmpty() }
            reveal("workspace-private-parent", "私有文件测试").performClick()
            ui.waitUntil(5_000) { ui.onAllNodesWithTag("workspace-private-entry:sub").fetchSemanticsNodes().size == 1 }
            val retained = checkNotNull(reveal("workspace-private-delete:sub", "私有文件测试").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
            realPauseResume(activity, atPause = { retained() })
            ui.onNodeWithText("删除私有文件").assertDoesNotExist()
            mainThread { gate.set(false); retained() }
            ui.onNodeWithText("删除私有文件").assertDoesNotExist()
            assertTrue(sub.isDirectory)
        } finally { gate.set(false); ui.waitForIdle(); root.deleteRecursively() }
    }
}
