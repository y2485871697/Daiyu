package app.daiyu.android.mcp

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class McpHttpSessionTest {
    private val server = McpServer("test", "fixture", "https://fixture.invalid/mcp", "synthetic-token")
    private fun response(chain: Interceptor.Chain, input: JSONObject, result: String, session: String = "session", media: String = "application/json", id: Long = input.getLong("id")): Response =
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
            .header("Content-Type", media).header("Mcp-Session-Id", session)
            .body(JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", McpJson.objectValue(result)).toString().toResponseBody(media.toMediaType())).build()
    private val init = """{"protocolVersion":"2025-03-26","capabilities":{"tools":{}},"serverInfo":{"name":"fixture","version":"1"}}"""
    private val list = """{"tools":[{"name":"read","inputSchema":{"type":"object"}}]}"""
    @Test fun boundSessionHeaderAndJsonRpcIdAreRequiredAndNoImplicitSessionReplacementIsPossible() {
        for (failure in listOf("session", "id", "sse", "cursor")) {
            val calls = AtomicInteger()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                calls.incrementAndGet(); val buffer = Buffer(); chain.request().body!!.writeTo(buffer)
                val input = McpJson.objectValue(buffer.readUtf8())
                assertEquals("Bearer synthetic-token", chain.request().header("Authorization"))
                when (input.getString("method")) {
                    "initialize" -> { assertNull(chain.request().header("Mcp-Session-Id")); response(chain, input, init) }
                    "notifications/initialized" -> {
                        assertEquals("session", chain.request().header("Mcp-Session-Id"))
                        assertEquals(McpHttpSession.VERSION, chain.request().header("MCP-Protocol-Version"))
                        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(202).message("fixture")
                            .body("".toResponseBody()).build()
                    }
                    else -> response(chain, input, if (failure == "cursor") """{"tools":[],"nextCursor":"more"}""" else list,
                        session = if (failure == "session") "replacement" else "session",
                        media = if (failure == "sse") "text/event-stream" else "application/json",
                        id = if (failure == "id") input.getLong("id") + 1 else input.getLong("id"))
                }
            }.build()
            val session = McpHttpSession(server, client)
            try { assertThrows(RuntimeException::class.java) { session.discover() }; assertEquals(3, calls.get()) }
            finally { session.close() }
        }
    }
    @Test fun invalidVersionsAndJsonRpcErrorsDoNotAdvertiseToolsOrRetryInitialize() {
        for (body in listOf(init.replace("2025-03-26", "unknown"), "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32000,\"message\":\"synthetic-token\"}}")) {
            val calls = AtomicInteger()
            val session = McpHttpSession(server, OkHttpClient.Builder().addInterceptor { chain ->
                calls.incrementAndGet()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                    .header("Content-Type", "application/json")
                    .body((if (body.contains("jsonrpc")) body else JSONObject().put("jsonrpc", "2.0").put("id", 1).put("result", McpJson.objectValue(body)).toString())
                        .toResponseBody("application/json".toMediaType())).build()
            }.build())
            try {
                val failure = assertThrows(RuntimeException::class.java) { session.discover() }
                assertFalse(failure.message.orEmpty().contains("synthetic-token")); assertEquals(1, calls.get())
            } finally { session.close() }
        }
    }
    @Test fun defaultTransportDisablesRedirectConnectionRetryAuthenticationAndBoundsReadBeforeAllocation() {
        val session = McpHttpSession(server)
        try {
            val client = McpHttpSession::class.java.getDeclaredField("client").apply { isAccessible = true }.get(session) as OkHttpClient
            assertFalse(client.followRedirects); assertFalse(client.followSslRedirects); assertFalse(client.retryOnConnectionFailure)
            assertSame(Authenticator.NONE, client.authenticator); assertSame(Authenticator.NONE, client.proxyAuthenticator)
            assertEquals(30000, client.callTimeoutMillis); assertEquals(1, client.networkInterceptors.size)
            assertThrows(IllegalArgumentException::class.java) { McpHttpSession.boundedRead(ByteArray(65537).inputStream(), 65536) }
        } finally { session.close() }
    }
}
