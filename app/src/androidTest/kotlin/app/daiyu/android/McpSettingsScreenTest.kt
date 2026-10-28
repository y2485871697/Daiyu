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
    fun testLoopbackDefaultTransportDiscoversLargeCatalogAndPersistsIt() {
        val policy = android.security.NetworkSecurityPolicy.getInstance()
        assertTrue(policy.isCleartextTrafficPermitted("127.0.0.1"))
        for (host in listOf("example.com", "192.168.1.2", "127.0.0.1.attacker.invalid")) {
            assertFalse(policy.isCleartextTrafficPermitted(host))
        }
        val listener = java.net.ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1")).apply { soTimeout = 10_000 }
        val methods = java.util.Collections.synchronizedList(mutableListOf<String>())
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        val serving = worker.submit {
            repeat(3) {
                listener.accept().use { socket ->
                    socket.soTimeout = 10_000
                    val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                    check(reader.readLine() == "POST /mcp HTTP/1.1")
                    val headers = linkedMapOf<String, String>()
                    while (true) {
                        val line = checkNotNull(reader.readLine())
                        if (line.isEmpty()) break
                        headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                    }
                    check("authorization" !in headers)
                    val chars = CharArray(checkNotNull(headers["content-length"]).toInt())
                    var count = 0
                    while (count < chars.size) {
                        val read = reader.read(chars, count, chars.size - count)
                        check(read > 0); count += read
                    }
                    val request = JSONObject(String(chars)); val method = request.getString("method")
                    methods.add(method)
                    val result = when (method) {
                        "initialize" -> JSONObject().put("protocolVersion", "2025-03-26")
                            .put("capabilities", JSONObject().put("tools", JSONObject()))
                            .put("serverInfo", JSONObject().put("name", "fixture").put("version", "1"))
                        "notifications/initialized" -> null
                        "tools/list" -> JSONObject().put("tools", org.json.JSONArray().also { tools ->
                            repeat(57) { index -> tools.put(JSONObject().put("name", "tool_$index")
                                .put("description", "说明".repeat(800)).put("inputSchema", JSONObject().put("type", "object"))) }
                        })
                        else -> error("unexpected fixture request")
                    }
                    val body = if (result == null) byteArrayOf() else JSONObject().put("jsonrpc", "2.0")
                        .put("id", request.getLong("id")).put("result", result).toString().toByteArray(Charsets.UTF_8)
                    val status = if (result == null) "202 Accepted" else "200 OK"
                    val header = "HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(body); flush() }
                }
            }
        }
        val server = McpServer("test", "mt", "http://127.0.0.1:${listener.localPort}/mcp", "")
        val session = McpHttpSession(server)
        val root = File(instrumentation.targetContext.cacheDir, "mcp-loopback-wire-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(instrumentation.targetContext) { override fun getNoBackupFilesDir() = root }
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store = McpSettingsStore(context) { key }; val owner = McpSettingsOwner()
        try {
            val tools = session.discover()
            serving.get(10, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(listOf("initialize", "notifications/initialized", "tools/list"), methods.toList())
            assertEquals(57, tools.size)
            store.save(listOf(server.withPolicy(catalog = tools, allowedTools = tools.map { it.name }.toSet())), owner, store.snapshot())
            assertTrue(File(root, "mcp-private-v1.bin").length() > 65536)
            assertEquals(57, McpSettingsStore(context) { key }.snapshot().servers.single().catalog.size)
        } finally {
            session.close(); listener.close(); serving.cancel(true); worker.shutdownNow()
            worker.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)
            owner.revoke(); root.deleteRecursively()
        }
    }
    fun testLoopbackHttpCanBeAddedAndReopenedWithoutTokenOrNetworkAccess() {
        val root = File(instrumentation.targetContext.cacheDir, "mcp-loopback-form-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(instrumentation.targetContext) { override fun getNoBackupFilesDir() = root }
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store = McpSettingsStore(context) { key }; val owner = McpSettingsOwner()
        var managementOpens = 0
        try {
            mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                McpSettingsScreen(store, owner, ownerCurrent = { true }, onBack = {}, managementFactory = {
                    managementOpens++; error("saving must not open a transport")
                })
            } } }
            ui.waitUntil(5_000) { ui.onAllNodes(hasTestTag("mcp-settings") and
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
                    "能力默认可用；保存只更新服务器配置，不会执行工具。")).fetchSemanticsNodes().isNotEmpty() }
            node("mcp-settings-add").performClick()
            node("mcp-server-label").performTextReplacement("mt")
            node("mcp-server-endpoint").performTextReplacement("http://127.0.0.1:8787/mcp")
            node("mcp-settings-save").scrollToVisible().performClick()
            ui.waitUntil(5_000) { ui.onAllNodesWithTag("mcp-settings-form").fetchSemanticsNodes().isEmpty() }
            val saved = McpSettingsStore(context) { key }.snapshot().servers.single()
            assertEquals("mt", saved.label)
            assertEquals("http://127.0.0.1:8787/mcp", saved.endpoint)
            assertEquals("", saved.bearer)
            node("mcp-settings-edit").scrollToVisible().performClick()
            assertEquals(saved.endpoint, node("mcp-server-endpoint").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            assertEquals("", node("mcp-server-bearer").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            node("mcp-settings-save").scrollToVisible().performClick()
            ui.waitUntil(5_000) { ui.onAllNodesWithTag("mcp-settings-form").fetchSemanticsNodes().isEmpty() }
            assertEquals("", store.snapshot().servers.single().bearer)
            assertEquals(0, managementOpens)
            assertEquals(0, fixture.opens.get()); assertNull(fixture.controller.active)
        } finally {
            mainThread { activity.setContent {} }; owner.revoke(); root.deleteRecursively()
        }
    }
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
                    SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "能力默认可用；保存只更新服务器配置，不会执行工具。")).fetchSemanticsNodes().isNotEmpty() }
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
            awaitStatus("能力默认可用；保存只更新服务器配置，不会执行工具。")
            assertTrue(methods.isEmpty())
            node("mcp-server-enabled:server").assertDoesNotExist()
            node("mcp-tool-enabled:server:erase").assertDoesNotExist()
            click("mcp-server:server")
            click("mcp-refresh:server")
            awaitStatus("工具目录已刷新；可提交精确任务，尚未调用工具。")
            assertEquals(listOf("initialize", "notifications/initialized", "tools/list"), methods.toList())
            val saved = store.snapshot().servers.single()
            assertTrue(saved.enabled)
            assertTrue(saved.eligible("erase"))
            assertEquals(setOf("erase"), saved.allowedTools)
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
            ui.waitUntil(5_000) { ui.onAllNodes(hasTestTag("mcp-settings") and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "能力默认可用；保存只更新服务器配置，不会执行工具。")).fetchSemanticsNodes().isNotEmpty() }
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
