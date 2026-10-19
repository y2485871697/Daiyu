package app.daiyu.android

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.platform.PlatformSafGrants
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.settings.WorkspaceSettingsScreen
import app.daiyu.android.workspace.WorkspaceFilesAccess
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

class WorkspaceSettingsActivityTest : WiredActivityTestCase() {
    private fun workspaceControl(tag: String): SemanticsNodeInteraction {
        ui.waitUntil(5_000) {
            try {
                node("page:工作区").performScrollToNode(hasTestTag(tag))
                node(tag).assertIsDisplayed()
                true
            } catch (_: AssertionError) { false }
        }
        return node(tag).assertIsDisplayed()
    }
    private fun workspaceClick(tag: String) {
        workspaceControl(tag).performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
    }
    private fun openWorkspace() {
        openSettings()
        node("settings-search").performTextReplacement("工作区与文件")
        reveal("settings-row:工作区与文件", "设置").performClick()
        awaitUniqueTag("page:工作区")
    }
    fun testRealWorkspaceEntryPreservesChatAndDoesNotReadOrChangeModelConfiguration() {
        setDraft("workspace unsent draft")
        val database = fixture.database.exportBackup(); val saved = fixture.settings.load()
        openWorkspace()
        node("workspace-private-import").assertExists()
        node("workspace-pick-directory").assertDoesNotExist(); node("workspace-pick-file").assertDoesNotExist()
        assertEquals(database, fixture.database.exportBackup()); assertEquals(saved, fixture.settings.load())
        assertEquals(0, fixture.opens.get()); assertNull(fixture.controller.active)
        backToPage(activity, "page:设置"); backToChat(activity)
        assertDraft("workspace unsent draft")
    }
    fun testCancelledOrInvalidPickerDoesNotAddAnyApplicationGrant() {
        val registry = PlatformSafGrants(instrumentation.targetContext)
        val before = registry.grants(); val launches = AtomicInteger()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action !in setOf(Intent.ACTION_OPEN_DOCUMENT_TREE, Intent.ACTION_OPEN_DOCUMENT)) return null
                assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                assertTrue(intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0)
                assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                launches.incrementAndGet()
                return if (intent.action == Intent.ACTION_OPEN_DOCUMENT_TREE) Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                else Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.parse("file:///invalid")))
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), false) {
                WorkspaceSettingsScreen(onBack = {}, engineeringUi = true)
            } } }
            workspaceClick("workspace-pick-directory")
            ui.waitUntil(5_000) { launches.get() == 1 }; ui.waitForIdle()
            workspaceClick("workspace-pick-file")
            ui.waitUntil(5_000) { ui.onAllNodesWithText("未取得有效的持久只读授权，请重新选择").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(2, launches.get()); assertEquals(before, registry.grants())
            assertEquals(0, fixture.opens.get())
        } finally { instrumentation.removeMonitor(monitor) }
    }
    fun testPausedOrDisposedWorkspaceCannotOpenPickerThroughRetainedButton() {
        val launches = AtomicInteger()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_OPEN_DOCUMENT_TREE) return null
                launches.incrementAndGet()
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), false) {
                WorkspaceSettingsScreen(onBack = { activity.setContent {} }, engineeringUi = true)
            } } }
            workspaceControl("workspace-pick-directory")
            val click = checkNotNull(node("workspace-pick-directory").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
            realPauseResume(activity, atPause = { click() })
            assertEquals(0, launches.get())
            mainThread { click() }; assertEquals(0, launches.get())
            workspaceClick("workspace-pick-directory")
            ui.waitUntil(5_000) { launches.get() == 1 }
            mainThread { activity.setContent {} }
            ui.waitForIdle()
            node("page:工作区").assertDoesNotExist()
            mainThread { click() }; assertEquals(1, launches.get())
            mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), false) {
                WorkspaceSettingsScreen(onBack = {}, engineeringUi = true)
            } } }
            workspaceClick("workspace-pick-directory")
            ui.waitUntil(5_000) { launches.get() == 2 }
            assertEquals(2, launches.get())
        } finally { instrumentation.removeMonitor(monitor) }
    }
    fun testPauseDiscardsAnInflightDirectoryResultAndExplicitRefreshCanBrowseAgain() {
        val context = instrumentation.targetContext
        val name = "workspace-page-${UUID.randomUUID()}"
        val tree = Uri.parse("content://workspace.test/tree/root")
        val reads = mutableListOf<Uri>()
        val registry = PlatformSafGrants(context.getSharedPreferences(name, Context.MODE_PRIVATE),
            { reads.toList() }, { if (it !in reads) reads += it }, { reads.remove(it); Unit })
        assertTrue(registry.accept(Intent().setData(tree).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), true))
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val returned = CountDownLatch(1)
        val queries = AtomicInteger()
        val access = WorkspaceFilesAccess(registry) { _, _ ->
            assertTrue(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
            if (queries.incrementAndGet() == 1) {
                entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); returned.countDown()
            }
            JSONObject().put("ok", true).put("data", JSONObject().put("items", JSONArray().put(JSONObject()
                .put("uri", "content://workspace.test/tree/root/document/a").put("name", "local-only.txt")
                .put("mime_type", "text/plain"))).put("truncated", false))
        }
        try {
            mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = true) {
                WorkspaceSettingsScreen(onBack = {}, suppliedAccess = access, engineeringUi = true)
            } } }
            workspaceControl("workspace-browse:0")
            workspaceClick("workspace-browse:0"); assertTrue(entered.await(5, TimeUnit.SECONDS))
            try { realPauseResume(activity) } finally { release.countDown() }
            assertTrue(returned.await(5, TimeUnit.SECONDS)); ui.waitForIdle()
            node("workspace-file:0").assertDoesNotExist()
            workspaceControl("workspace-browse:0")
            workspaceClick("workspace-browse:0")
            ui.waitUntil(5_000) {
                try {
                    reveal("workspace-file:0", "工作区")
                        .assertTextContains("local-only.txt").assertIsDisplayed()
                    true
                } catch (_: AssertionError) { false }
            }
            assertEquals(2, queries.get()); assertEquals(0, fixture.opens.get())
        } finally { release.countDown(); context.deleteSharedPreferences(name) }
    }
    fun testRetainedOldConfirmationCannotRevokeOrDismissANewConfirmationForTheSameUri() {
        val context = instrumentation.targetContext
        val name = "workspace-confirm-${UUID.randomUUID()}"
        val tree = Uri.parse("content://workspace.test/tree/root")
        val reads = mutableListOf<Uri>(); val releases = AtomicInteger()
        val registry = PlatformSafGrants(context.getSharedPreferences(name, Context.MODE_PRIVATE),
            { reads.toList() }, { reads += it }, { releases.incrementAndGet(); reads.remove(it); Unit })
        assertTrue(registry.accept(Intent().setData(tree).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), true))
        val access = WorkspaceFilesAccess(registry) { _, _ -> error("must not read directory") }
        try {
            mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = true) {
                WorkspaceSettingsScreen(onBack = {}, suppliedAccess = access, engineeringUi = true)
            } } }
            workspaceControl("workspace-revoke:0")
            workspaceClick("workspace-revoke:0")
            val confirm = checkNotNull(node("dialog-confirm").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
            val cancel = checkNotNull(node("dialog-cancel").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
            node("dialog-cancel").performClick(); workspaceClick("workspace-revoke:0")
            mainThread { confirm(); cancel() }; ui.waitForIdle()
            assertEquals(0, releases.get()); node("dialog-confirm").assertExists()
            node("dialog-confirm").performClick()
            ui.waitUntil(5_000) { releases.get() == 1 }
            assertTrue(registry.grants().isEmpty()); assertEquals(0, fixture.opens.get())
        } finally { context.deleteSharedPreferences(name) }
    }
    fun testSlowGrantRefreshHidesOldRowsAndRejectsRetainedBrowse() {
        val context = instrumentation.targetContext
        val name = "workspace-refresh-${UUID.randomUUID()}"
        val tree = Uri.parse("content://workspace.test/tree/root")
        val reads = mutableListOf<Uri>(); val hold = AtomicBoolean(false); val queries = AtomicInteger()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val registry = PlatformSafGrants(context.getSharedPreferences(name, Context.MODE_PRIVATE),
            { if (hold.get()) { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }; reads.toList() },
            { reads += it }, { reads.remove(it); Unit })
        assertTrue(registry.accept(Intent().setData(tree).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), true))
        val access = WorkspaceFilesAccess(registry) { _, _ -> queries.incrementAndGet(); error("stale browse must not read") }
        try {
            mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = true) {
                WorkspaceSettingsScreen(onBack = {}, suppliedAccess = access, engineeringUi = true)
            } } }
            workspaceControl("workspace-browse:0")
            val browse = checkNotNull(node("workspace-browse:0").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
            hold.set(true); workspaceClick("workspace-refresh"); assertTrue(entered.await(5, TimeUnit.SECONDS))
            node("workspace-browse:0").assertDoesNotExist()
            mainThread { browse() }; assertEquals(0, queries.get())
            hold.set(false); release.countDown()
            workspaceControl("workspace-browse:0")
            ui.onNodeWithText("正在读取共享状态").assertDoesNotExist()
        } finally { hold.set(false); release.countDown(); context.deleteSharedPreferences(name) }
    }
    fun testPauseDuringRevocationDiscardsOldGrantSnapshotAndRefreshesAfterBusyResume() {
        val context = instrumentation.targetContext
        val name = "workspace-revoke-pause-${UUID.randomUUID()}"
        val first = Uri.parse("content://workspace.test/tree/first")
        val second = Uri.parse("content://workspace.test/tree/second")
        val reads = mutableListOf<Uri>(); val blockNext = AtomicBoolean(false)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val registry = PlatformSafGrants(context.getSharedPreferences(name, Context.MODE_PRIVATE),
            { val snapshot = reads.toList()
                if (blockNext.getAndSet(false)) { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
                snapshot },
            { reads += it }, { reads.remove(it); blockNext.set(true) })
        for (tree in listOf(first, second)) assertTrue(registry.accept(Intent().setData(tree).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), true))
        val access = WorkspaceFilesAccess(registry) { _, _ -> error("must not read directory") }
        try {
            mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = true) {
                WorkspaceSettingsScreen(onBack = {}, suppliedAccess = access, engineeringUi = true)
            } } }
            workspaceControl("workspace-revoke:0")
            workspaceClick("workspace-revoke:0"); node("dialog-confirm").performClick()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            realPauseResume(activity, atPause = { reads.clear() })
            release.countDown()
            ui.waitUntil(5_000) { ui.onAllNodesWithText("还没有共享目录或文件").fetchSemanticsNodes().size == 1 }
            node("workspace-grant:0").assertDoesNotExist()
            ui.onNodeWithText("正在读取共享状态").assertDoesNotExist()
            assertTrue(registry.grants().isEmpty()); assertEquals(0, fixture.opens.get())
        } finally { release.countDown(); context.deleteSharedPreferences(name) }
    }
}
