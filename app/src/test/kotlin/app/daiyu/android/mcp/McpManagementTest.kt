package app.daiyu.android.mcp

import android.app.Application
import android.os.Looper
import app.daiyu.android.testing.HostAtomicFileShadow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], shadows = [HostAtomicFileShadow::class])
class McpManagementTest {
    private class Fixture : AutoCloseable {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store = McpSettingsStore(RuntimeEnvironment.getApplication()) { key }
        val owner = McpSettingsOwner()
        var listing = """{"tools":[{"name":"erase","inputSchema":{"type":"object"}}]}"""
        val methods = mutableListOf<String>()
        val sessions = mutableListOf<McpHttpSession>()
        var onList: (() -> Unit)? = null
        val worker = Executors.newSingleThreadExecutor()
        val management = McpManagement(store) { server ->
            check(Looper.myLooper() != Looper.getMainLooper())
            McpHttpSession(server, OkHttpClient.Builder().addInterceptor { chain ->
                val buffer = Buffer(); chain.request().body!!.writeTo(buffer)
                val input = McpJson.objectValue(buffer.readUtf8())
                val method = input.getString("method"); methods.add(method)
                val result = when (method) {
                    "initialize" -> """{"protocolVersion":"2025-03-26","capabilities":{"tools":{}},"serverInfo":{"name":"fixture","version":"1"}}"""
                    "notifications/initialized" -> null
                    "tools/list" -> { onList?.invoke(); listing }
                    else -> error("settings must never call $method")
                }
                val body = if (result == null) "" else JSONObject().put("jsonrpc", "2.0").put("id", input.getLong("id"))
                    .put("result", McpJson.objectValue(result)).toString()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("fixture")
                    .code(if (result == null) 202 else 200).header("Content-Type", "application/json")
                    .header("Mcp-Session-Id", "fixed-session").body(body.toResponseBody("application/json".toMediaType())).build()
            }.build()).also { sessions.add(it) }
        }
        init { store.clear(owner) }
        fun seed(server: McpServer = McpServer("server", "fixture", "https://fixture.invalid/mcp", "synthetic-token")) =
            store.save(listOf(server), owner, store.snapshot())
        fun <T> offMain(action: () -> T): T = worker.submit<T> { action() }.get(10, TimeUnit.SECONDS)
        fun assertDrained() { sessions.forEach { var done = false; it.observeDrained { done = true }; assertTrue(done) } }
        override fun close() { owner.revoke(); worker.shutdownNow(); store.clear(McpSettingsOwner()) }
    }
    @Test fun explicitDiscoveryOnlyRedactsMetadataKeepsDefaultOffAndDoesNotCreateExecution() {
        Fixture().use { f ->
            val before = f.seed()
            f.listing = """{"tools":[{"name":"echo-synthetic-token","description":"synthetic-token","inputSchema":{"type":"object","properties":{"synthetic-token":{"default":"synthetic-token"}}}}]}"""
            val result = f.offMain { f.management.refresh(before, "server", f.owner) }
            assertEquals(listOf("initialize", "notifications/initialized", "tools/list"), f.methods)
            val server = result.servers.single()
            assertFalse(server.enabled); assertTrue(server.allowedTools.isEmpty())
            assertFalse(McpJson.canonical(server.catalog.single().json()).contains("synthetic-token"))
            assertFalse(f.store.isCurrent(before)); f.assertDrained()
        }
    }
    @Test fun onlyUnchangedMetadataKeepsAllowlistAndRefreshFailurePreservesLastDirectory() {
        Fixture().use { f ->
            val original = f.seed(McpServer("server", "fixture", "https://fixture.invalid/mcp", "", true,
                listOf(McpRemoteTool("erase", "erase")), setOf("erase")))
            val same = f.offMain { f.management.refresh(original, "server", f.owner) }
            assertEquals(setOf("erase"), same.servers.single().allowedTools)
            f.listing = """{"tools":[{"name":"erase","description":"changed","inputSchema":{"type":"object"}},{"name":"new","inputSchema":{"type":"object"}}]}"""
            val changed = f.offMain { f.management.refresh(same, "server", f.owner) }
            assertTrue(changed.servers.single().enabled); assertTrue(changed.servers.single().allowedTools.isEmpty())
            assertEquals(2, changed.servers.single().catalog.size)
            f.listing = """{"tools":[{"name":"invalid","inputSchema":{"type":"array"}}]}"""
            f.offMain { assertThrows(IllegalStateException::class.java) { f.management.refresh(changed, "server", f.owner) } }
            assertEquals(changed.revision, f.store.snapshot().revision)
            assertEquals(2, f.store.snapshot().servers.single().catalog.size); f.assertDrained()
        }
    }
    @Test fun mainThreadAndRevokedPageRejectWithoutOpeningAnyTransport() {
        Fixture().use { f ->
            val before = f.seed()
            assertEquals(Looper.getMainLooper(), Looper.myLooper())
            assertEquals("mcp_worker_required", assertThrows(IllegalStateException::class.java) {
                f.management.refresh(before, "server", f.owner)
            }.message)
            f.owner.revoke()
            f.offMain { assertThrows(IllegalStateException::class.java) { f.management.refresh(before, "server", f.owner) } }
            assertTrue(f.methods.isEmpty()); assertTrue(f.sessions.isEmpty())
        }
    }
    @Test fun leavingOrConcurrentConfigurationChangeCancelsBlockedDiscoveryWithoutOverwriting() {
        for (leave in listOf(true, false)) Fixture().use { f ->
            val before = f.seed()
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            f.onList = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            val future = f.worker.submit<Throwable?> {
                runCatching { f.management.refresh(before, "server", f.owner) }.exceptionOrNull()
            }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val expected = if (leave) { f.owner.revoke(); before } else {
                    f.store.save(before.servers.map { it.withPolicy(enabled = true) }, McpSettingsOwner(), before)
                }
                release.countDown()
                assertNotNull(future.get(10, TimeUnit.SECONDS))
                assertEquals(expected.revision, f.store.snapshot().revision)
                assertTrue(f.store.snapshot().servers.single().catalog.isEmpty()); f.assertDrained()
            } finally { release.countDown() }
        }
    }
}
