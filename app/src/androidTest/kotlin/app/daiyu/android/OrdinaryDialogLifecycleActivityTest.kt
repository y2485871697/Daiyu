package app.daiyu.android

import android.view.KeyEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import app.daiyu.android.ui.components.DialogActionLease
import app.daiyu.android.ui.components.RetainedWorkspaceAction
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The real host retains presentation, not an executable grant or a replayed user action. */
class OrdinaryDialogLifecycleActivityTest : WiredActivityTestCase() {
    private fun lease(): DialogActionLease? = invokeHost(activity, "getOrdinaryDialogLease") as DialogActionLease?
    private fun activeLease(): DialogActionLease {
        lateinit var current: DialogActionLease
        mainThread { current = checkNotNull(lease()); assertTrue(current.isActive) }
        return current
    }
    private fun assertRenewed(previous: DialogActionLease) {
        val current = activeLease()
        assertFalse(previous.isActive)
        assertNotSame(previous, current)
    }
    private fun pauseAndReturn() {
        val previous = activeLease()
        realPauseResume(activity, atPause = { assertFalse(previous.isActive) })
        assertRenewed(previous)
    }
    private fun homeAndReturn() {
        val previous = activeLease()
        val original = activity
        val stopped = CountDownLatch(1)
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) stopped.countDown()
        }
        mainThread { original.lifecycle.addObserver(observer) }
        try {
            assertTrue(instrumentation.uiAutomation.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME))
            assertTrue("Original host reached STOP", stopped.await(5, TimeUnit.SECONDS))
            assertFalse(previous.isActive)
        } finally {
            mainThread { original.lifecycle.removeObserver(observer) }
            restoreOriginalHost(original)
        }
        awaitHostWindowFocus(original)
        assertRenewed(previous)
    }
    private fun openWorkspace() {
        awaitControl("chat local readers idle") { !fixture.controller.dataBusy }
        awaitHostWindowFocus(activity)
        dismissIme()
        val distance = 80f * activity.resources.displayMetrics.density
        node("chat-workspace-gesture-area").performTouchInput { swipe(center, center + Offset(0f, -distance)) }
        awaitUniqueTag("conversation-workspace-sheet")
    }
    private fun loadedDirectory(path: String) {
        ui.waitUntil(5_000) {
            ui.mainClock.advanceTimeByFrame()
            val current = ui.onAllNodes(hasTestTag("workspace-current-path") and hasText(path)).fetchSemanticsNodes()
            current.size == 1 && ui.onAllNodes(hasTestTag("workspace-save") and isEnabled()).fetchSemanticsNodes().size == 1
        }
        node("workspace-current-path").assertTextEquals(path)
        node("workspace-error").assertDoesNotExist()
    }
    fun testWorkspaceManagementSurvivesPauseAndStopButOldClickNeverRevives() {
        setDraft("后台返回必须保留的草稿")
        openWorkspace()
        val originalBackup = fixture.database.exportBackup()
        val oldAdd = node("workspace-add").fetchSemanticsNode().config[RetainedWorkspaceAction]
        val oldLease = activeLease()
        repeat(2) {
            pauseAndReturn()
            awaitNodeWindowFocus(node("workspace-add"))
            node("conversation-workspace-sheet").assertIsDisplayed()
            homeAndReturn()
            awaitNodeWindowFocus(node("workspace-add"))
            mainThread { oldAdd() }
            node("workspace-current-path").assertDoesNotExist()
            assertEquals(originalBackup, fixture.database.exportBackup())
            assertRenewed(oldLease)
        }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitHostWindowFocus(activity)
        assertDraft("后台返回必须保留的草稿")
        pauseAndReturn()
        node("conversation-workspace-sheet").assertDoesNotExist()
        assertEquals(0, fixture.opens.get())
    }
    fun testUnsavedWorkspaceDirectorySurvivesPauseAndHomeWithoutSaving() {
        val root = java.io.File(fixture.application.filesDir, "workspace").apply { mkdirs() }
        val child = java.io.File(root, "retained-choice").apply { mkdirs() }
        val marker = java.io.File(child, "marker").apply { writeText("unchanged") }
        openWorkspace()
        node("workspace-add").performClick()
        loadedDirectory(root.canonicalPath)
        node("workspace-folder:retained-choice").performClick()
        loadedDirectory(child.canonicalPath)
        val oldSave = node("workspace-save").fetchSemanticsNode().config[RetainedWorkspaceAction]
        val before = fixture.database.workspaceBinding(null)
        repeat(2) {
            pauseAndReturn()
            loadedDirectory(child.canonicalPath)
            homeAndReturn()
            loadedDirectory(child.canonicalPath)
            mainThread {
                val beforeVisit = invokeHost(activity, "getWorkspaceVisit")
                oldSave()
                // Admission replaces the visit synchronously, before launching the save worker.
                assertSame(beforeVisit, invokeHost(activity, "getWorkspaceVisit"))
            }
            assertEquals(before, fixture.database.workspaceBinding(null))
            assertEquals("unchanged", marker.readText())
        }
        node("workspace-save").performClick()
        awaitControl("fresh workspace selection saved") { fixture.database.workspaceBinding(null) != before }
        assertTrue(checkNotNull(fixture.database.workspaceBinding(null)).contains("retained-choice"))
        assertEquals("unchanged", marker.readText())
        assertEquals(0, fixture.opens.get())
    }
    fun testChatModelSearchSurvivesPauseAndStopWithoutAnySelection() {
        awaitControl("chat local readers idle") { !fixture.controller.dataBusy }
        mainThread { invokeHost(activity, "openChatModels") }
        awaitUniqueTag("chat-model-search")
        node("chat-model-search").performTextReplacement("unfinished model search")
        dismissIme()
        val before = fixture.database.exportBackup()
        repeat(2) {
            pauseAndReturn()
            node("chat-model-search").assertEditableText("unfinished model search")
            homeAndReturn()
            node("chat-model-search").assertEditableText("unfinished model search")
        }
        assertEquals(before, fixture.database.exportBackup())
        assertEquals(0, fixture.opens.get())
        awaitNodeWindowFocus(node("chat-model-search"))
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitHostWindowFocus(activity)
        pauseAndReturn()
        node("chat-model-picker").assertDoesNotExist()
    }
}
