package app.daiyu.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.lifecycle.Lifecycle
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.platform.PlatformSafGrants
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.settings.SettingsScaffoldPage
import app.daiyu.android.ui.screens.settings.WorkspaceSettingsScreen
import app.daiyu.android.ui.screens.settings.rememberLinuxRootfsPanel
import app.daiyu.android.workspace.WorkspaceFilesAccess
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class OtherPagePositionActivityTest : WiredActivityTestCase() {
    private data class Position(val scroll: Float, val top: Float)
    private fun position(page: String, tag: String): Position {
        ui.waitForIdle()
        return Position(node("page:$page").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(),
            node(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top)
    }
    private fun assertPositionWithoutScrolling(page: String, tag: String, before: Position) {
        ui.waitUntil(5_000) { ui.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 && node(tag).isDisplayed() }
        val after = position(page, tag)
        assertEquals(before.scroll, after.scroll, .001f)
        assertEquals(before.top, after.top, 2f)
    }
    private fun backgroundAndResume() {
        mainThread { assertTrue(activity.moveTaskToBack(true)) }
        ui.waitUntil(5_000) { activity.lifecycle.currentState == Lifecycle.State.CREATED }
        mainThread {
            instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
        ui.waitUntil(5_000) { activity.lifecycle.currentState == Lifecycle.State.RESUMED }
    }

    fun testRootfsKeepsNestedDirectoryAndPositionAfterPauseAndFullBackground() {
        val root = File(instrumentation.targetContext.filesDir.canonicalFile, "rootfs-position-${UUID.randomUUID()}")
        val nested = File(root, "nested").apply { check(mkdirs()) }
        repeat(40) { File(nested, "file-${it.toString().padStart(2, '0')}.txt").writeText("fixture") }
        try {
            mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = true) {
                val panel = rememberLinuxRootfsPanel(root)
                SettingsScaffoldPage("Rootfs 位置", {}) { item("rootfs") { panel() } }
            } } }
            awaitUniqueTag("linux-rootfs-entry:nested")
            node("linux-rootfs-entry:nested").performClick()
            val tag = "linux-rootfs-entry:nested/file-30.txt"
            awaitUniqueTag(tag)
            node("page:Rootfs 位置").scrollToDescendant(hasTestTag(tag))
            for (stop in listOf(false, true)) {
                val before = position("Rootfs 位置", tag)
                assertTrue(before.scroll > 0f)
                if (stop) backgroundAndResume() else realPauseResume(activity)
                ui.waitUntil(5_000) { ui.onAllNodesWithText("正在读取 Rootfs").fetchSemanticsNodes().isEmpty() }
                assertPositionWithoutScrolling("Rootfs 位置", tag, before)
            }
            assertEquals(0, fixture.opens.get())
        } finally { mainThread { activity.setContent {} }; root.deleteRecursively() }
    }

    fun testSharedDirectoryRestoresOnlyAfterFreshGrantAndDirectoryReadAndDropsRevokedTree() {
        val context = instrumentation.targetContext
        val name = "shared-position-${UUID.randomUUID()}"
        val tree = Uri.parse("content://workspace.test/tree/position")
        val reads = AtomicReference<List<Uri>>(emptyList())
        val registry = PlatformSafGrants(context.getSharedPreferences(name, Context.MODE_PRIVATE),
            { reads.get() }, { uri -> reads.set(reads.get() + uri) }, { uri -> reads.set(reads.get() - uri) })
        assertTrue(registry.accept(Intent().setData(tree).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), true))
        val queries = AtomicInteger()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val access = WorkspaceFilesAccess(registry) { _, _ ->
            if (queries.incrementAndGet() == 2) { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
            JSONObject().put("ok", true).put("data", JSONObject().put("items", JSONArray().apply {
                repeat(40) { put(JSONObject().put("uri", "$tree/document/file-$it").put("name", "file-$it.txt").put("mime_type", "text/plain")) }
            }).put("truncated", false))
        }
        try {
            mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = true) {
                WorkspaceSettingsScreen(onBack = {}, suppliedAccess = access, engineeringUi = true)
            } } }
            node("page:工作区").scrollToDescendant(hasTestTag("workspace-browse:0"))
            awaitUniqueTag("workspace-browse:0")
            node("workspace-browse:0").performClick()
            ui.waitUntil(5_000) { queries.get() == 1 && ui.onAllNodesWithText("正在读取目录").fetchSemanticsNodes().isEmpty() }
            node("page:工作区").scrollToDescendant(hasTestTag("workspace-file:30"))
            val before = position("工作区", "workspace-file:30")
            assertTrue(before.scroll > 10f)
            backgroundAndResume()
            ui.waitUntil(5_000) {
                ui.mainClock.advanceTimeByFrame()
                entered.count == 0L
            }
            node("workspace-file:30").assertDoesNotExist()
            release.countDown()
            assertPositionWithoutScrolling("工作区", "workspace-file:30", before)
            assertEquals(2, queries.get())
            realPauseResume(activity)
            assertPositionWithoutScrolling("工作区", "workspace-file:30", before)
            reads.set(emptyList())
            realPauseResume(activity)
            ui.waitUntil(5_000) { ui.onAllNodesWithText("还没有共享目录或文件").fetchSemanticsNodes().isNotEmpty() }
            node("workspace-file:30").assertDoesNotExist()
            assertEquals(3, queries.get())
            assertEquals(0, fixture.opens.get())
        } finally { release.countDown(); context.deleteSharedPreferences(name) }
    }
}
