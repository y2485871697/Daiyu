package app.daiyu.android

import android.content.ContextWrapper
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import androidx.compose.ui.graphics.toPixelMap
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.mcp.*
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.settings.McpSettingsScreen
import java.io.File
import java.util.UUID
import javax.crypto.KeyGenerator

/** Actual settings form/store; only ephemeral synthetic encrypted data under an isolated noBackup root. */
class McpSettingsScreenTest : WiredActivityTestCase() {
    fun testEmptyCardAndBothAddEntrypointsKeepHorizontalThemedActionsWithoutSaving() {
        for (dark in listOf(false, true)) {
            val root = File(instrumentation.targetContext.cacheDir, "mcp-layout-${UUID.randomUUID()}").apply { mkdirs() }
            val context = object : ContextWrapper(instrumentation.targetContext) { override fun getNoBackupFilesDir() = root }
            val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val store = McpSettingsStore(context) { key }; val owner = McpSettingsOwner()
            try {
                mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(
                    themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT), applyInterfaceScale = false) {
                    McpSettingsScreen(store, owner, ownerCurrent = { true }, onBack = {})
                } } }
                ui.waitUntil(5_000) { ui.onAllNodes(hasTestTag("mcp-settings") and
                    SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "保存只配置服务器，不授予工具权限。")).fetchSemanticsNodes().isNotEmpty() }
                node("mcp-settings-count").assertTextEquals("服务器 · 0")
                node("mcp-settings-status").assertDoesNotExist()
                node("mcp-settings-empty").assertIsDisplayed()
                node("mcp-settings-add").assert(hasContentDescription("添加服务器"))
                ui.onNodeWithText("还没有 MCP 服务器").assertIsDisplayed()
                ui.onNodeWithText("点击添加服务器").assertIsDisplayed()
                for (entry in listOf("mcp-settings-empty-add", "mcp-settings-add")) {
                    node(entry).performClick()
                    node("mcp-server-label").assertContentDescriptionEquals("名称")
                    node("mcp-server-endpoint").assertContentDescriptionEquals("服务器地址")
                    node("mcp-server-bearer").assertContentDescriptionEquals("Bearer Token（可选）")
                    val cancel = node("mcp-settings-cancel").scrollToVisible().assertIsEnabled()
                    val save = node("mcp-settings-save").scrollToVisible().assertIsEnabled().assertTextEquals("添加服务器")
                    val left = cancel.fetchSemanticsNode().boundsInRoot
                    val right = save.fetchSemanticsNode().boundsInRoot
                    assertTrue(left.right <= right.left)
                    assertTrue(kotlin.math.abs(left.top - right.top) <= 1f)
                    assertTrue(kotlin.math.abs(left.width - right.width) <= 1f)
                    assertTrue(kotlin.math.abs(left.height - right.height) <= 1f)
                    val pixels = captureScreenNode(save).toPixelMap()
                    val expected = androidx.compose.ui.graphics.Color(if (dark) 0xFFB6A4C7 else 0xFF8E7C9E)
                    for (x in listOf(1f / 3f, .5f, 2f / 3f)) for (y in listOf(.15f, .85f)) {
                        val fill = pixels[(pixels.width * x).toInt(), (pixels.height * y).toInt()]
                        assertTrue(kotlin.math.abs(fill.red - expected.red) < .025f)
                        assertTrue(kotlin.math.abs(fill.green - expected.green) < .025f)
                        assertTrue(kotlin.math.abs(fill.blue - expected.blue) < .025f)
                    }
                    node("mcp-server-label").performTextReplacement("Unsaved fixture")
                    node("mcp-server-endpoint").performTextReplacement("https://fixture.invalid/mcp")
                    node("mcp-server-bearer").performTextReplacement("not-saved-token")
                    if (entry == "mcp-settings-empty-add") cancel.scrollToVisible().performClick()
                    else mainThread { activity.onBackPressedDispatcher.onBackPressed() }
                    node("mcp-settings-form").assertDoesNotExist()
                    assertTrue(store.snapshot().servers.isEmpty())
                    assertTrue(McpSettingsStore(context) { key }.snapshot().servers.isEmpty())
                    assertTrue(owner.current())
                }
                assertEquals(0, fixture.opens.get()); assertNull(fixture.controller.active)
            } finally {
                mainThread { activity.setContent {} }; owner.revoke(); root.deleteRecursively()
            }
        }
    }
    fun testDiscoveryPageNeverEnablesServerOrGrantsTools() {
        val root = File(instrumentation.targetContext.cacheDir, "mcp-discovery-screen-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(instrumentation.targetContext) { override fun getNoBackupFilesDir() = root }
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store = McpSettingsStore(context) { key }; val owner = McpSettingsOwner()
        val methods = java.util.Collections.synchronizedList(mutableListOf<String>())
        val management = McpManagement(store) { server ->
            check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
            McpHttpSession(server, OkHttpClient.Builder().addInterceptor { chain ->
                val buffer = Buffer(); chain.request().body!!.writeTo(buffer)
                val input = McpJson.objectValue(buffer.readUtf8()); val method = input.getString("method")
                methods.add(method)
                val result = when (method) {
                    "initialize" -> """{"protocolVersion":"2025-03-26","capabilities":{"tools":{}},"serverInfo":{"name":"fixture","version":"1"}}"""
                    "notifications/initialized" -> null
                    "tools/list" -> """{"tools":[{"name":"erase","description":"synthetic-token","inputSchema":{"type":"object"}}]}"""
                    else -> error("settings must not execute tools")
                }
                val body = if (result == null) "" else JSONObject().put("jsonrpc", "2.0").put("id", input.getLong("id"))
                    .put("result", McpJson.objectValue(result)).toString()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("fixture")
                    .code(if (result == null) 202 else 200).header("Content-Type", "application/json")
                    .header("Mcp-Session-Id", "fixed-session").body(body.toResponseBody("application/json".toMediaType())).build()
            }.build())
        }
        fun awaitStatus(text: String) { ui.waitUntil(5_000) {
            ui.onAllNodes(hasTestTag("mcp-settings") and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text)).fetchSemanticsNodes().isNotEmpty()
        } }
        fun click(tag: String) {
            if (tag.startsWith("mcp-refresh:")) node(tag).assertIsDisplayed().assertIsEnabled().performClick()
            else reveal(tag, "MCP 服务器").performClick()
        }
        try {
            store.save(listOf(McpServer("server", "fixture", "https://fixture.invalid/mcp", "synthetic-token")), owner, store.snapshot())
            mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                McpSettingsScreen(store, owner, ownerCurrent = { true }, onBack = {}, managementFactory = { management })
            } } }
            awaitStatus("保存只配置服务器，不授予工具权限。")
            assertTrue(methods.isEmpty())
            node("mcp-server-enabled:server").assertDoesNotExist()
            node("mcp-tool-enabled:server:erase").assertDoesNotExist()
            click("mcp-server:server")
            click("mcp-refresh:server")
            awaitStatus("工具目录已刷新；未授予任何工具权限。")
            assertEquals(listOf("initialize", "notifications/initialized", "tools/list"), methods.toList())
            val saved = store.snapshot().servers.single()
            assertFalse(saved.enabled)
            assertTrue(saved.allowedTools.isEmpty())
            assertEquals(listOf("erase"), saved.catalog.map { it.name })
            ui.onAllNodesWithText("synthetic-token", substring = true).assertCountEquals(0)
            assertEquals(0, fixture.opens.get()); assertNull(fixture.controller.active)
        } finally {
            owner.revoke(); mainThread { activity.setContent {} }; root.deleteRecursively()
        }
    }
    fun testSaveReopenAndRevokedPageCannotSaveOrBecomeTaskAuthorization() {
        val root = File(instrumentation.targetContext.cacheDir, "mcp-screen-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getNoBackupFilesDir() = root
        }
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store = McpSettingsStore(context) { key }; val owner = McpSettingsOwner()
        try {
            mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                McpSettingsScreen(store, owner, ownerCurrent = { true }, onBack = {})
            } } }
            ui.waitUntil(5_000) { ui.onAllNodes(hasTestTag("mcp-settings") and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "保存只配置服务器，不授予工具权限。")).fetchSemanticsNodes().isNotEmpty() }
            node("mcp-server-label").assertDoesNotExist()
            node("mcp-server-id").assertDoesNotExist()
            node("mcp-settings-clear").assertDoesNotExist()
            node("mcp-settings-add").performClick()
            node("mcp-server-id").assertDoesNotExist()
            node("mcp-server-label").scrollToVisible().performTextReplacement("Synthetic fixture")
            node("mcp-server-endpoint").scrollToVisible().performTextReplacement("https://fixture.invalid/mcp")
            node("mcp-server-bearer").scrollToVisible().performTextReplacement("synthetic-fixture-token")
            node("mcp-settings-save").scrollToVisible().performClick()
            ui.waitUntil(5_000) { ui.onAllNodes(hasTestTag("mcp-settings-status") and hasText("已保存；旧的本轮批准已失效，尚未连接服务器。")).fetchSemanticsNodes().isNotEmpty() }
            val saved = store.snapshot()
            assertEquals(1, saved.servers.size); assertEquals("synthetic-fixture-token", saved.servers.single().bearer)
            assertEquals(saved.revision, McpSettingsStore(context) { key }.snapshot().revision)
            assertEquals(0, fixture.opens.get()); assertNull(fixture.controller.active)
            node("mcp-settings-edit").scrollToVisible().performClick()
            node("mcp-server-bearer").assertContentDescriptionEquals("Bearer Token（留空保留）")
            assertEquals("", node("mcp-server-bearer").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            node("mcp-server-label").performTextReplacement("Renamed fixture")
            node("mcp-settings-save").scrollToVisible().assertTextEquals("保存").performClick()
            ui.waitUntil(5_000) { ui.onAllNodesWithTag("mcp-settings-form").fetchSemanticsNodes().isEmpty() }
            assertEquals("Renamed fixture", store.snapshot().servers.single().label)
            assertEquals("synthetic-fixture-token", store.snapshot().servers.single().bearer)
            val beforeRevocation = store.snapshot()
            mainThread { activity.onBackPressedDispatcher.onBackPressed() }
            assertTrue(owner.current())
            mainThread { activity.onBackPressedDispatcher.onBackPressed() }
            assertFalse(owner.current())
            try { store.save(emptyList(), owner, beforeRevocation); fail("revoked settings page saved") } catch (_: IllegalStateException) { }
            val afterRevocation = store.snapshot()
            assertEquals(beforeRevocation.revision, afterRevocation.revision)
            assertSame(beforeRevocation.stamp, afterRevocation.stamp)
            assertEquals(beforeRevocation.servers.size, afterRevocation.servers.size)
            beforeRevocation.servers.zip(afterRevocation.servers).forEach { (before, after) ->
                assertTrue("server id changed", before.id == after.id)
                assertTrue("server label changed", before.label == after.label)
                assertTrue("server endpoint changed", before.endpoint == after.endpoint)
                assertTrue("server bearer changed", before.bearer == after.bearer)
                assertEquals(before.enabled, after.enabled)
                assertTrue("allowed tools changed", before.allowedTools == after.allowedTools)
                assertEquals(before.catalog.size, after.catalog.size)
                assertTrue("catalog metadata changed", before.catalog.zip(after.catalog).all { (old, fresh) ->
                    old.sameMetadata(fresh)
                })
            }
        } finally {
            mainThread { activity.setContent {} }
            root.deleteRecursively()
        }
    }
}
