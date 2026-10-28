package app.daiyu.android

import android.view.KeyEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import app.daiyu.android.storage.ConversationDatabase
import app.daiyu.android.workspace.ConversationWorkspaceBinding
import app.daiyu.android.workspace.ConversationWorkspaceCodec
import app.daiyu.android.workspace.ConversationWorkspaces
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ConversationWorkspacesActivityTest : WiredActivityTestCase() {
    private fun <T> worker(action: () -> T): T {
        awaitControl("workspace operations idle") { !fixture.controller.dataBusy }
        val executor = Executors.newSingleThreadExecutor()
        return try { executor.submit<T> { action() }.get(15, TimeUnit.SECONDS) }
        finally { executor.shutdown() }
    }

    private fun awaitLoadedDirectory(path: String) {
        ui.waitUntil(5_000) {
            ui.mainClock.advanceTimeByFrame()
            ui.onAllNodes(hasTestTag("workspace-current-path") and hasText(path)).fetchSemanticsNodes().size == 1 &&
                ui.onAllNodes(hasTestTag("workspace-save") and isEnabled()).fetchSemanticsNodes().size == 1
        }
        node("workspace-error").assertDoesNotExist()
    }

    fun testAddSwitchRemoveAndReopenPreserveOtherConversationAndActualFiles() {
        val first = File(fixture.application.filesDir, "workspace/list-first").apply { mkdirs() }
        val second = File(fixture.application.filesDir, "workspace/list-second").apply { mkdirs() }
        val marker = File(first, "keep.txt").apply { writeText("keep") }
        try {
            val owner = fixture.database.create(fixture.database.selectedAssistantId()).id
            val other = fixture.database.create(fixture.database.selectedAssistantId()).id
            assertTrue(worker { fixture.controller.saveWorkspaceOnWorker(owner, null, "private", first.path) })
            val firstRaw = fixture.database.workspaceBinding(owner)
            assertTrue(worker { fixture.controller.saveWorkspaceOnWorker(owner, firstRaw, "private", second.path) })
            val added = fixture.database.workspaceBinding(owner)
            val choices = ConversationWorkspaceCodec.decodeWorkspaces(added)
            assertEquals(3, choices.bindings.size)
            assertEquals(ConversationWorkspaceBinding.Private(second.path), choices.active)
            assertTrue(worker { fixture.controller.selectWorkspaceOnWorker(owner, added, 1) })
            val selectedBeforeEdit = fixture.database.workspaceBinding(owner)
            val third = File(fixture.application.filesDir, "workspace/list-third").apply { mkdirs() }
            try {
                assertTrue(worker { fixture.controller.saveWorkspaceOnWorker(owner, selectedBeforeEdit,
                    "private", third.path, replaceIndex = 2) })
                val edited = ConversationWorkspaceCodec.decodeWorkspaces(fixture.database.workspaceBinding(owner))
                assertEquals(ConversationWorkspaceBinding.Private(first.path), edited.active)
                assertEquals(ConversationWorkspaceBinding.Private(third.path), edited.bindings[2])
            } finally { third.deleteRecursively() }
            val selected = fixture.database.workspaceBinding(owner)
            val frozen = worker { fixture.controller.freezeConversationWorkspaceOnWorker(owner) }
            try {
                assertTrue(worker { fixture.controller.removeWorkspacesOnWorker(owner, selected, setOf(1)) })
                assertEquals(first.canonicalFile, frozen.resolved.hostDirectory)
            } finally { frozen.close() }
            assertEquals("keep", marker.readText())
            assertTrue(first.isDirectory && second.isDirectory)
            assertNull(fixture.database.workspaceBinding(other))
            assertNull(fixture.database.workspaceBinding(null))
            assertFalse(worker { fixture.controller.removeWorkspacesOnWorker(owner, selected, setOf(0)) })
            val current = fixture.database.workspaceBinding(owner)
            val reopened = ConversationDatabase(fixture.application)
            try { assertEquals(current, reopened.workspaceBinding(owner)) } finally { reopened.close() }
            val remaining = ConversationWorkspaceCodec.decodeWorkspaces(current)
            assertTrue(worker { fixture.controller.removeWorkspacesOnWorker(owner, current, remaining.bindings.indices.toSet()) })
            assertTrue(ConversationWorkspaceCodec.decodeWorkspaces(fixture.database.workspaceBinding(owner)).bindings.isEmpty())
            assertEquals("keep", marker.readText())
        } finally { first.deleteRecursively(); second.deleteRecursively() }
    }

    fun testRealActivityEditReplacesInactiveChoiceAndKeepsTargetThroughNavigation() {
        val root = File(fixture.application.filesDir, "workspace")
        val first = File(root, "list-ui-first").apply { mkdirs() }
        val second = File(root, "list-ui-second").apply { mkdirs() }
        val edited = File(second, "new-path").apply { mkdirs() }
        try {
            val original = ConversationWorkspaceCodec.encode(ConversationWorkspaces(listOf(
                ConversationWorkspaceBinding.Private(), ConversationWorkspaceBinding.Private(first.path),
                ConversationWorkspaceBinding.Private(second.path)), 1))
            val owner = fixture.database.create(fixture.database.selectedAssistantId(), original)
            mainThread { activity.select(owner) }
            awaitControl("workspace editor idle") { !fixture.controller.dataBusy }
            awaitHostWindowFocus(activity)
            node("chat-workspace-gesture-area").performTouchInput {
                swipe(center, center + Offset(0f, -80f * activity.resources.displayMetrics.density))
            }
            awaitUniqueTag("conversation-workspace-sheet")
            ui.onNodeWithText("会话工作区").assertExists()
            node("workspace-edit:2").performClick()
            awaitLoadedDirectory(second.canonicalPath)
            ui.onNodeWithText("更改工作目录").assertExists()
            awaitUniqueTag("workspace-folder:new-path")
            node("workspace-folder:new-path").performClick()
            awaitLoadedDirectory(edited.canonicalPath)
            ui.onNodeWithText("更改工作目录").assertExists()
            node("workspace-save").performClick()
            awaitControl("edited workspace persisted") { fixture.database.workspaceBinding(owner.id) != original }
            ui.waitUntil(5_000) {
                ui.mainClock.advanceTimeByFrame()
                ui.onAllNodesWithText("会话工作区").fetchSemanticsNodes().size == 1
            }
            val updated = ConversationWorkspaceCodec.decodeWorkspaces(fixture.database.workspaceBinding(owner.id))
            assertEquals(3, updated.bindings.size)
            assertEquals(1, updated.activeIndex)
            assertEquals(ConversationWorkspaceBinding.Private(first.path), updated.active)
            assertEquals(ConversationWorkspaceBinding.Private(edited.canonicalPath), updated.bindings[2])
            ui.onNodeWithText("会话工作区").assertExists()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            awaitHostWindowFocus(activity)
        } finally { first.deleteRecursively(); second.deleteRecursively() }
    }

    fun testRealActivityAddUsesControllerRootAndPersistsOnlyAfterLoadedSelection() {
        val root = File(fixture.application.filesDir, "workspace")
        val added = File(root, "list-ui-added").apply { mkdirs() }
        val marker = File(added, "keep.txt").apply { writeText("keep") }
        try {
            val owner = fixture.database.create(fixture.database.selectedAssistantId())
            mainThread { activity.select(owner) }
            awaitControl("workspace editor idle") { !fixture.controller.dataBusy }
            awaitHostWindowFocus(activity)
            node("chat-workspace-gesture-area").performTouchInput {
                swipe(center, center + Offset(0f, -80f * activity.resources.displayMetrics.density))
            }
            awaitUniqueTag("workspace-add")
            node("workspace-add").performClick()
            awaitLoadedDirectory(root.canonicalPath)
            awaitUniqueTag("workspace-folder:list-ui-added")
            node("workspace-folder:list-ui-added").performClick()
            awaitLoadedDirectory(added.canonicalPath)
            assertNull(fixture.database.workspaceBinding(owner.id))
            node("workspace-save").performClick()
            awaitControl("added workspace persisted") { fixture.database.workspaceBinding(owner.id) != null }
            ui.waitUntil(5_000) {
                ui.mainClock.advanceTimeByFrame()
                ui.onAllNodesWithText("会话工作区").fetchSemanticsNodes().size == 1
            }
            val saved = ConversationWorkspaceCodec.decodeWorkspaces(fixture.database.workspaceBinding(owner.id))
            assertEquals(2, saved.bindings.size)
            assertEquals(ConversationWorkspaceBinding.Private(added.canonicalPath), saved.active)
            assertEquals("keep", marker.readText())
            assertEquals(0, fixture.opens.get())
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            awaitHostWindowFocus(activity)
        } finally { added.deleteRecursively() }
    }

    fun testCollectionRejectsAmbiguousAndroidJsonWithoutFallback() {
        for (raw in listOf(
            "{\"version\":2,\"kind\":\"collection\",\"active\":0,\"bindings\":[null]}TRAILING",
            "{\"version\":2,\"kind\":\"collection\",\"active\":8,\"active\":0,\"bindings\":[null]}",
            "{'version':2,'kind':'collection','active':0,'bindings':[null]}",
        )) {
            var rejected = false
            try { ConversationWorkspaceCodec.decodeWorkspaces(raw) } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue("ambiguous Android JSON accepted", rejected)
        }
    }

    fun testStaleRootfsCanBeRemovedWithoutResolvingItOrTouchingDirectories() {
        val stale = ConversationWorkspaceBinding.Rootfs("not-installed", "old-generation", "/project")
        val raw = ConversationWorkspaceCodec.encode(ConversationWorkspaces(listOf(stale,
            ConversationWorkspaceBinding.Private("/missing/private-directory")), 0))
        val owner = fixture.database.create(fixture.database.selectedAssistantId(), raw).id
        assertTrue(worker { fixture.controller.removeWorkspacesOnWorker(owner, raw, setOf(0)) })
        val remaining = fixture.database.workspaceBinding(owner)
        assertEquals(ConversationWorkspaceBinding.Private("/missing/private-directory"),
            ConversationWorkspaceCodec.decode(remaining))
        assertTrue(worker { fixture.controller.removeWorkspacesOnWorker(owner, remaining, setOf(0)) })
        assertTrue(ConversationWorkspaceCodec.decodeWorkspaces(fixture.database.workspaceBinding(owner)).bindings.isEmpty())
    }
}
