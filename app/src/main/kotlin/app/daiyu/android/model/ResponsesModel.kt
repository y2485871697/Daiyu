package app.daiyu.android.model

import app.daiyu.android.domain.*
import app.daiyu.android.storage.ModelSettings
import org.json.JSONObject
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

class ResponsesModel internal constructor(
    private val settings: ModelSettings,
    private val connect: (URL) -> HttpsURLConnection,
    private val imageInputSupported: Boolean?,
) : ModelPort {
    internal constructor(settings: ModelSettings, connect: (URL) -> HttpsURLConnection) : this(settings, connect, null)
    constructor(settings: ModelSettings) : this(settings, { it.openConnection() as HttpsURLConnection }, null)
    internal constructor(snapshot: ProviderSnapshot) : this(snapshot.asModelSettings(),
        { it.openConnection() as HttpsURLConnection }, snapshot.imageInputSupported)
    override fun open(history: List<Message>): ModelSession = open(history, settings.enabledTools())
    override fun open(history: List<Message>, tools: Set<ReadOnlyTool>): ModelSession = open(history, tools.map {
        ToolDefinition(it.toolName, it.description, """{"type":"object","properties":{},"required":[],"additionalProperties":false}""")
    })
    override fun open(history: List<Message>, definitions: List<ToolDefinition>): ModelSession =
        Session(settings, history, connect, definitions.toList(), imageInputSupported, null)
    override fun open(history: List<Message>, definitions: List<ToolDefinition>, images: ModelImagePlan): ModelSession =
        Session(settings, history, connect, definitions.toList(), imageInputSupported, images)

    private class Session(
        private val settings: ModelSettings,
        history: List<Message>,
        private val connect: (URL) -> HttpsURLConnection,
        private val tools: List<ToolDefinition>,
        capability: Boolean?,
        images: ModelImagePlan?,
    ) : ModelSession {
        private val cancelled = AtomicBoolean(false)
        @Volatile private var connection: HttpsURLConnection? = null
        private val historySnapshot = app.daiyu.android.model.images.FrozenMessageImagePlan.snapshot(history)
        private val imagePlan = ImageRequestPreflight.history(settings, ProviderProtocol.RESPONSES, capability, historySnapshot, images)
        private val input = ResponsesRequest.history(historySnapshot, imagePlan)
        private val ownItems = mutableListOf<String>()
        private var usable = true
        private var started = false
        private var pending = emptySet<String>()
        init { settings.validate() }
        private fun checkCancelled() { if (cancelled.get()) throw InterruptedException() }
        override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
            checkCancelled()
            check(usable && (!started || pending.isNotEmpty())) { "Responses session cannot be replayed" }
            require(outputs.map { it.callId.value }.toSet() == pending && outputs.size == pending.size) {
                "Tool outputs must match all pending tool calls exactly once"
            }
            started = true
            usable = false
            outputs.forEach {
                val item = JSONObject().put("type", "function_call_output").put("call_id", it.callId.value).put("output", it.output)
                input.put(item); ownItems.add(item.toString())
            }
            val request = settings.requestOptions.apply(ResponsesRequest.body(settings.model, input, tools), ProviderProtocol.RESPONSES)
            val requestBytes = ImageRequestPreflight.bytes(request, settings, imagePlan, cancelled::get)
            var deliveryFailure: Exception? = null
            val deliver: (ModelEvent) -> Unit = { event ->
                checkCancelled()
                try { sink(event) } catch (failure: Exception) { deliveryFailure = failure; throw failure }
            }
            var ownedConnection: HttpsURLConnection? = null
            try {
                checkCancelled()
                val conn = connect(URL(settings.requestEndpoint()))
                ownedConnection = conn; connection = conn
                checkCancelled()
                conn.requestMethod = "POST"; conn.doOutput = true; conn.instanceFollowRedirects = false
                conn.connectTimeout = 20_000; conn.readTimeout = 60_000
                conn.setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
                conn.setRequestProperty("Content-Type", "application/json"); conn.setRequestProperty("Accept", "text/event-stream")
                conn.outputStream.use { it.write(requestBytes) }
                checkCancelled()
                val status = conn.responseCode
                checkCancelled()
                if (status !in 200..299) throw ModelRequestException(when (status) {
                    401 -> ModelFailure.AUTHENTICATION
                    403 -> ModelFailure.PERMISSION
                    404, 405 -> ModelFailure.ENDPOINT
                    429 -> ModelFailure.RATE_LIMIT
                    in 300..399 -> ModelFailure.REDIRECT
                    in 500..599 -> ModelFailure.SERVER
                    else -> ModelFailure.REQUEST
                })
                if (conn.contentType?.substringBefore(';')?.trim()?.equals("text/event-stream", ignoreCase = true) != true)
                    throw ModelRequestException(ModelFailure.PROTOCOL)
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val items = ResponsesStream.read(reader, cancelled::get, deliver)
                    checkCancelled()
                    val calls = mutableSetOf<String>()
                    items.forEach { raw ->
                        val item = JSONObject(raw)
                        if (item.optString("type") == "function_call") {
                            val id = item.opt("call_id") as? String ?: throw ModelRequestException(ModelFailure.PROTOCOL)
                            if (id.isBlank() || !calls.add(id)) throw ModelRequestException(ModelFailure.PROTOCOL)
                        }
                        app.daiyu.android.model.images.FrozenMessageImagePlan.rejectRawImageNode(item)
                        input.put(item); ownItems.add(raw)
                    }
                    deliver(ModelEvent.ProtocolOutput(ownItems.toList()))
                    checkCancelled()
                    pending = calls.toSet()
                }
                checkCancelled()
            } catch (failure: Exception) {
                if (failure === deliveryFailure) throw failure
                checkCancelled()
                throw when (failure) {
                    is java.net.SocketTimeoutException -> ModelRequestException(ModelFailure.TIMEOUT)
                    is javax.net.ssl.SSLException -> ModelRequestException(ModelFailure.TLS)
                    is java.io.IOException -> ModelRequestException(ModelFailure.NETWORK)
                    else -> failure
                }
            } finally {
                try { ownedConnection?.disconnect() } catch (_: Exception) {} finally { connection = null }
            }
            checkCancelled()
            usable = true
        }
        override fun cancel() {
            cancelled.set(true)
            try { connection?.disconnect() } catch (_: Exception) {}
        }
    }
}
