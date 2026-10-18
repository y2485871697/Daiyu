package app.daiyu.android.mcp

import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit

internal class McpRemoteTool(val name: String, val description: String,
    inputSchema: String = "{\"type\":\"object\"}", val readOnlyHint: Boolean? = null) {
    val inputSchema: String = McpJson.canonical(McpJson.objectValue(inputSchema))
    init {
        require(name.length in 1..128 && name.none { it < ' ' } && description.length <= 4096)
        require(this.inputSchema.toByteArray(Charsets.UTF_8).size <= 16384)
        require(McpJson.text(McpJson.objectValue(this.inputSchema), "type") == "object")
    }
    fun redacted(secret: String): McpRemoteTool = fromJson(McpJson.redact(json(), secret) as JSONObject)
    fun sameMetadata(other: McpRemoteTool): Boolean = McpJson.canonical(json()) == McpJson.canonical(other.json())
    fun json(): JSONObject = JSONObject().put("name", name).put("description", description)
        .put("inputSchema", McpJson.objectValue(inputSchema)).also {
            readOnlyHint?.let { hint -> it.put("annotations", JSONObject().put("readOnlyHint", hint)) }
        }
    override fun toString() = "McpRemoteTool(untrusted)"
    companion object {
        fun fromJson(tool: JSONObject): McpRemoteTool {
            val name = McpJson.text(tool, "name")
            val description = if (tool.has("description")) McpJson.text(tool, "description") else name
            val schema = tool.getJSONObject("inputSchema")
            val annotation = if (tool.has("annotations")) tool.getJSONObject("annotations") else null
            val hint = annotation?.let { if (it.has("readOnlyHint")) {
                require(it.get("readOnlyHint") is Boolean); it.getBoolean("readOnlyHint")
            } else null }
            return McpRemoteTool(name, description, McpJson.canonical(schema), hint)
        }
    }
}

/** One frozen endpoint, one HTTP session, no background GET, reconnect, redirect or retry.
 * JSON response subset of Streamable HTTP 2025-03-26. SSE/batches/server requests are rejected.
 * Close cancels the REAL okhttp Call and keeps handles until execute/body drain has returned.
 */
internal class McpHttpSession(
    private val server: McpServer,
    private val client: OkHttpClient = createClient(),
) : AutoCloseable {
    private val lock = Any()
    private var closed = false
    private var active: Call? = null
    private var requests = 0
    private var nextId = 0L
    private var session: String? = null
    private var initialized = false
    private var released = false
    private val observers = mutableListOf<() -> Unit>()

    fun discover(): List<McpRemoteTool> {
        check(!initialized)
        val init = rpc("initialize", JSONObject().put("protocolVersion", VERSION)
            .put("capabilities", JSONObject()).put("clientInfo", JSONObject().put("name", "Daiyu").put("version", "1")), initializing = true)
        require(McpJson.text(init, "protocolVersion") == VERSION && init.get("capabilities") is JSONObject)
        val info = init.getJSONObject("serverInfo")
        require(McpJson.text(info, "name").length in 1..256 && McpJson.text(info, "version").length in 1..128)
        val tools = init.getJSONObject("capabilities").opt("tools")
        require(tools is JSONObject) { "mcp_tools_not_supported" }
        notification()
        initialized = true
        val result = rpc("tools/list", JSONObject())
        require(!result.has("nextCursor")) { "mcp_pagination_not_supported" }
        val list = result.getJSONArray("tools"); require(list.length() <= 64)
        val names = HashSet<String>()
        return (0 until list.length()).map { i ->
            val tool = list.getJSONObject(i)
            val name = McpJson.text(tool, "name")
            require(name.length in 1..128 && name.none { it < ' ' } && names.add(name))
            McpRemoteTool.fromJson(tool)
        }
    }
    fun call(tool: String, arguments: String): Pair<String, Boolean> {
        check(initialized)
        val result = rpc("tools/call", JSONObject().put("name", tool).put("arguments", McpJson.objectValue(arguments)))
        if (result.has("isError")) require(result.get("isError") is Boolean)
        val content = result.getJSONArray("content"); require(content.length() <= 32)
        for (i in 0 until content.length()) {
            val block = content.getJSONObject(i)
            // Remote images/resources cannot silently become trusted local attachments or URLs.
            require(McpJson.text(block, "type") == "text" && block.get("text") is String) { "mcp_content_not_supported" }
        }
        if (result.has("structuredContent")) require(result.get("structuredContent") is JSONObject)
        return McpJson.canonical(result) to result.optBoolean("isError", false)
    }
    private fun rpc(method: String, params: JSONObject, initializing: Boolean = false): JSONObject {
        val id = synchronized(lock) { check(!closed); ++nextId }
        val envelope = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)
        val response = exchange(envelope.toString(), initializing, false)
        require(McpJson.text(response, "jsonrpc") == "2.0")
        val responseId = response.get("id")
        require(responseId is Number && responseId.toString() == id.toString()) { "mcp_response_id_mismatch" }
        require(response.has("result") != response.has("error"))
        require(!response.has("method") && !response.has("params")) { "mcp_server_request_not_supported" }
        if (response.has("error")) {
            val rpcError = response.getJSONObject("error")
            val code = rpcError.get("code")
            require(code is Number && rpcError.get("message") is String)
            require(code.toString().toBigDecimal().stripTrailingZeros().scale() <= 0)
            // No remote exception text: it may contain credentials. Sent writes remain UNKNOWN.
            error("mcp_rpc_rejected")
        }
        return response.getJSONObject("result")
    }
    private fun notification() {
        exchange(JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized").toString(), false, true)
    }
    private fun exchange(payload: String, initializing: Boolean, notification: Boolean): JSONObject {
        require(payload.toByteArray(Charsets.UTF_8).size <= 65536)
        val call = synchronized(lock) {
            check(!closed && active == null && ++requests <= 32) { "mcp_session_unavailable" }
            val request = Request.Builder().url(server.endpoint)
                .header("Accept", "application/json, text/event-stream")
                .header("Accept-Encoding", "identity")
                .post(payload.toRequestBody("application/json".toMediaType()))
            if (!initializing) request.header("MCP-Protocol-Version", VERSION)
            session?.let { request.header("Mcp-Session-Id", it) }
            if (server.bearer.isNotEmpty()) request.header("Authorization", "Bearer ${server.bearer}")
            client.newCall(request.build()).also { active = it }
        }
        try {
            return call.execute().use { response ->
                require(response.code in 200..299) { "mcp_http_rejected" }
                val ids = response.headers.values("Mcp-Session-Id")
                require(ids.size <= 1)
                ids.singleOrNull()?.let { value ->
                    require(value.length in 1..256 && value.all { it.code in 0x21..0x7e })
                    if (initializing) session = value else require(session != null && session == value) { "mcp_session_mismatch" }
                }
                val body = response.body
                if (notification) {
                    require(response.code == 202 || response.code == 204)
                    require(body == null || body.byteStream().read() == -1)
                    JSONObject()
                } else {
                    require(response.code == 200 && body != null)
                    require(response.header("Content-Encoding")?.let { it.equals("identity", true) } != false)
                    require(response.headers.values("Content-Type").size == 1)
                    val media = body.contentType()
                    require(media?.type == "application" && media.subtype == "json" && media.charset(Charsets.UTF_8) == Charsets.UTF_8) {
                        "mcp_response_format_unsupported"
                    }
                    require(body.contentLength() <= 65536)
                    val bytes = boundedRead(body.byteStream(), 65536)
                    val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                    McpJson.objectValue(text)
                }
            }
        } finally {
            synchronized(lock) { check(active === call); active = null }
            releaseIfDrained()
        }
    }
    override fun close() {
        val call = synchronized(lock) { closed = true; active }
        call?.cancel()
        releaseIfDrained()
    }
    fun observeDrained(complete: () -> Unit) {
        val done = synchronized(lock) { if (released) true else { observers.add(complete); false } }
        if (done) complete()
    }
    private fun releaseIfDrained() {
        val callbacks = synchronized(lock) {
            if (!closed || active != null || released) return
            // Execute/body use returned, not just cancel() requested. Client belongs to this session.
            client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
            released = true
            observers.toList().also { observers.clear() }
        }
        callbacks.forEach { runCatching(it) }
    }
    companion object {
        const val VERSION = "2025-03-26"
        private fun createClient() = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false)
            // OkHttp can otherwise follow a 503 Retry-After: 0 independently of connection retries.
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (response.code !in 200..299) {
                    response.close(); throw java.io.IOException("mcp_http_rejected")
                }
                response
            }
            .authenticator(okhttp3.Authenticator.NONE)
            .proxyAuthenticator(okhttp3.Authenticator.NONE)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
        internal fun boundedRead(input: java.io.InputStream, max: Int): ByteArray {
            val output = ByteArrayOutputStream(); val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer, 0, minOf(buffer.size, max + 1 - output.size()))
                if (count < 0) break
                output.write(buffer, 0, count); require(output.size() <= max) { "mcp_response_limit" }
            }
            return output.toByteArray()
        }
    }
}
