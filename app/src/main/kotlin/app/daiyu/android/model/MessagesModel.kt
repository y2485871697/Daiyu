package app.daiyu.android.model

import app.daiyu.android.domain.*
import app.daiyu.android.storage.ModelSettings
import app.daiyu.android.model.sessiongateway.SessionGateway
import app.daiyu.android.model.sessiongateway.SessionGatewayContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

/** Anthropic Messages transport; selected through the frozen provider configuration. */
class MessagesModel internal constructor(
    private val settings: ModelSettings,
    private val maxTokens: Int,
    private val connect: (URL) -> HttpsURLConnection,
    private val imageInputSupported: Boolean?,
    private val assistantInstructions: String? = null,
    private val sessionContext: SessionGatewayContext? = null,
) : ModelPort {
    internal constructor(settings: ModelSettings, maxTokens: Int, connect: (URL) -> HttpsURLConnection) :
        this(settings, maxTokens, connect, null)
    constructor(settings: ModelSettings, maxTokens: Int = 4096) :
        this(settings, maxTokens, { it.openConnection() as HttpsURLConnection })
    internal constructor(settings: ModelSettings, connect: (URL) -> HttpsURLConnection) : this(settings, 4096, connect)
    internal constructor(snapshot: ProviderSnapshot, assistantInstructions: String? = null,
                         sessionContext: SessionGatewayContext? = null) : this(snapshot.asModelSettings(), 4096,
        { it.openConnection() as HttpsURLConnection }, snapshot.imageInputSupported, assistantInstructions, sessionContext)

    override fun open(history: List<Message>): ModelSession = open(history, settings.enabledTools())
    override fun open(history: List<Message>, tools: Set<ReadOnlyTool>): ModelSession =
        open(history, MessagesRequest.definitions(tools))
    override fun open(history: List<Message>, definitions: List<ToolDefinition>): ModelSession =
        Session(settings, maxTokens, history, definitions.toList(), connect, imageInputSupported, null, assistantInstructions, sessionContext)
    override fun open(history: List<Message>, definitions: List<ToolDefinition>, images: ModelImagePlan): ModelSession =
        Session(settings, maxTokens, history, definitions.toList(), connect, imageInputSupported, images, assistantInstructions, sessionContext)

    private class Session(
        private val settings: ModelSettings,
        private val maxTokens: Int,
        history: List<Message>,
        private val tools: List<ToolDefinition>,
        private val connect: (URL) -> HttpsURLConnection,
        capability: Boolean?,
        images: ModelImagePlan?,
        private val assistantInstructions: String?,
        private val sessionContext: SessionGatewayContext?,
    ) : ModelSession {
        private val endpoint = MessagesRequest.endpoint(settings)
        private val historySnapshot = app.daiyu.android.model.images.FrozenMessageImagePlan.snapshot(history)
        private val imagePlan = ImageRequestPreflight.history(settings, ProviderProtocol.MESSAGES, capability, historySnapshot, images)
        private val messages = MessagesRequest.history(historySnapshot, imagePlan)
        private val ownItems = mutableListOf<String>()
        private val cancelled = AtomicBoolean(false)
        @Volatile private var connection: HttpsURLConnection? = null
        private var usable = true
        private var started = false
        private var pending = emptySet<String>()

        init { MessagesRequest.body(settings.model, messages, tools, maxTokens) }
        private fun checkCancelled() { if (cancelled.get()) throw InterruptedException() }

        override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
            checkCancelled()
            check(usable && (!started || pending.isNotEmpty())) { "Messages session cannot be replayed" }
            require(outputs.map { it.callId.value }.toSet() == pending && outputs.size == pending.size) {
                "Tool outputs must match all pending tool calls exactly once"
            }
            started = true
            // After any failure, no repost of an unconfirmed request or tool output is allowed.
            usable = false
            if (outputs.isNotEmpty()) {
                val content = JSONArray()
                outputs.forEach { content.put(MessagesRequest.result(it.callId.value, it.output)) }
                val item = MessagesRequest.message("user", content)
                messages.put(item); ownItems.add(item.toString())
            }
            val request = MessagesRequest.withAssistantInstructions(
                settings.requestOptions.apply(MessagesRequest.body(settings.model, messages, tools, maxTokens), ProviderProtocol.MESSAGES),
                assistantInstructions,
            )
            ReasoningRequestPolicy.validate(request, ProviderProtocol.MESSAGES, settings.reasoningOverride)
            settings.requestOptions.sessionGateway.requireExecutable()
            SessionGateway.applyAnthropic(request, sessionContext)
            val sessionHeaders = SessionGateway.headers(endpoint, sessionContext)
            val requestBytes = ImageRequestPreflight.bytes(request, settings, imagePlan, cancelled::get)
            var deliveryFailure: Exception? = null
            val deliver: (ModelEvent) -> Unit = { event ->
                checkCancelled()
                try { sink(event) } catch (failure: Exception) { deliveryFailure = failure; throw failure }
            }
            var ownedConnection: HttpsURLConnection? = null
            var configuringRequest = true
            try {
                checkCancelled()
                val conn = connect(URL(endpoint))
                ownedConnection = conn; connection = conn
                checkCancelled()
                conn.requestMethod = "POST"; conn.doOutput = true; conn.instanceFollowRedirects = false
                conn.connectTimeout = 20_000; conn.readTimeout = 60_000
                conn.setRequestProperty("x-api-key", settings.apiKey)
                conn.setRequestProperty("anthropic-version", settings.requestOptions.anthropicVersion ?: "2023-06-01")
                settings.customHeaders.applyTo(conn)
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Accept", "text/event-stream")
                sessionHeaders.forEach { (name, value) -> conn.setRequestProperty(name, value) }
                configuringRequest = false
                conn.outputStream.useProviderResource { it.write(requestBytes) }
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
                conn.inputStream.bufferedReader(Charsets.UTF_8).useProviderResource { reader ->
                    val raw = MessagesStream.read(reader, cancelled::get, deliver)
                    checkCancelled()
                    val item = JSONObject(raw)
                    app.daiyu.android.model.images.FrozenMessageImagePlan.rejectRawImageNode(item)
                    val content = item.getJSONArray("content")
                    val calls = mutableSetOf<String>()
                    for (i in 0 until content.length()) {
                        val block = content.getJSONObject(i)
                        if (block.getString("type") == "tool_use") calls.add(block.getString("id"))
                    }
                    messages.put(item); ownItems.add(raw)
                    deliver(ModelEvent.ProtocolOutput(ownItems.toList()))
                    checkCancelled()
                    pending = calls
                }
                checkCancelled()
            } catch (failure: Exception) {
                // Caller failures retain identity even when the sink also cancels the session.
                if (failure === deliveryFailure) throw failure
                checkCancelled()
                throw providerTransportFailure(failure, configuringRequest)
            } finally {
                // Do not read or expose provider error bodies. Close any already available error stream.
                try { ownedConnection?.errorStream?.close() } catch (_: Exception) {}
                try { ownedConnection?.disconnect() } catch (_: Exception) {} finally { connection = null }
            }
            checkCancelled()
            usable = true
        }

        override fun cancel() {
            cancelled.set(true)
            try { connection?.disconnect() } catch (_: Exception) { /* Best effort; cancellation stays primary. */ }
        }
    }
}
