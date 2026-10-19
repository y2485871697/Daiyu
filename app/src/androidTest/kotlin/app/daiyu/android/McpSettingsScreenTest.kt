package app.daiyu.android

import android.content.ContextWrapper
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.mcp.*
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.settings.McpSettingsScreen
import java.io.File
import java.util.UUID
import javax.crypto.KeyGenerator

/** Actual settings form/store; only ephemeral synthetic encrypted data under an isolated noBackup root. */
class McpSettingsScreenTest : WiredActivityTestCase() {
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
            ui.onAllNodes(hasTestTag("mcp-settings-status") and hasText(text)).fetchSemanticsNodes().isNotEmpty()
        } }
        fun click(tag: String) { node(tag).performScrollTo().performClick() }
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
            ui.waitUntil(5_000) { ui.onAllNodes(hasTestTag("mcp-settings-status") and hasText("保存只配置服务器，不授予工具权限。")).fetchSemanticsNodes().isNotEmpty() }
            node("mcp-server-label").assertDoesNotExist()
            node("mcp-server-id").assertDoesNotExist()
            node("mcp-settings-clear").assertDoesNotExist()
            node("mcp-settings-add").performClick()
            node("mcp-server-id").assertDoesNotExist()
            node("mcp-server-label").performScrollTo().performTextReplacement("Synthetic fixture")
            node("mcp-server-endpoint").performScrollTo().performTextReplacement("https://fixture.invalid/mcp")
            node("mcp-server-bearer").performScrollTo().performTextReplacement("synthetic-fixture-token")
            node("mcp-settings-save").performScrollTo().performClick()
            ui.waitUntil(5_000) { ui.onAllNodes(hasTestTag("mcp-settings-status") and hasText("已保存；旧的本轮批准已失效，尚未连接服务器。")).fetchSemanticsNodes().isNotEmpty() }
            val saved = store.snapshot()
            assertEquals(1, saved.servers.size); assertEquals("synthetic-fixture-token", saved.servers.single().bearer)
            assertEquals(saved.revision, McpSettingsStore(context) { key }.snapshot().revision)
            assertEquals(0, fixture.opens.get()); assertNull(fixture.controller.active)
            mainThread { activity.onBackPressedDispatcher.onBackPressed() }
            assertTrue(owner.current())
            mainThread { activity.onBackPressedDispatcher.onBackPressed() }
            assertFalse(owner.current())
            try { store.save(emptyList(), owner, saved); fail("revoked settings page saved") } catch (_: IllegalStateException) { }
            assertEquals(1, store.snapshot().servers.size)
        } finally {
            mainThread { activity.setContent {} }
            root.deleteRecursively()
        }
    }
}
