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
    private val summaryRequest: Boolean = false,
) : ModelPort {
    internal constructor(settings: ModelSettings, maxTokens: Int, connect: (URL) -> HttpsURLConnection) :
        this(settings, maxTokens, connect, null)
    constructor(settings: ModelSettings, maxTokens: Int = MessagesRequest.DEFAULT_MAX_TOKENS) :
        this(settings, maxTokens, { it.openConnection() as HttpsURLConnection })
    internal constructor(settings: ModelSettings, connect: (URL) -> HttpsURLConnection) : this(settings, MessagesRequest.DEFAULT_MAX_TOKENS, connect)
    internal constructor(snapshot: ProviderSnapshot, assistantInstructions: String? = null,
                         sessionContext: SessionGatewayContext? = null) : this(snapshot.asModelSettings(), MessagesRequest.DEFAULT_MAX_TOKENS,
        { it.openConnection() as HttpsURLConnection }, snapshot.imageInputSupported, assistantInstructions, sessionContext)

    override fun open(history: List<Message>): ModelSession = open(history, settings.enabledTools())
    override fun open(history: List<Message>, tools: Set<ReadOnlyTool>): ModelSession =
        open(history, MessagesRequest.definitions(tools))
    override fun open(history: List<Message>, definitions: List<ToolDefinition>): ModelSession =
        Session(settings, maxTokens, history, definitions.toList(), connect, imageInputSupported, null, assistantInstructions, sessionContext, summaryRequest)
    override fun open(history: List<Message>, definitions: List<ToolDefinition>, images: ModelImagePlan): ModelSession =
        Session(settings, maxTokens, history, definitions.toList(), connect, imageInputSupported, images, assistantInstructions, sessionContext, summaryRequest)

    private class Session(
        private val settings: ModelSettings,
        private val maxTokens: Int,
        history: List<Message>,
        private var tools: List<ToolDefinition>,
        private val connect: (URL) -> HttpsURLConnection,
        capability: Boolean?,
        images: ModelImagePlan?,
        private val assistantInstructions: String?,
        private val sessionContext: SessionGatewayContext?,
        private val summaryRequest: Boolean,
    ) : RequestRetryModelSession {
        private val endpoint = MessagesRequest.endpoint(settings)
        private val historySnapshot = app.daiyu.android.model.images.FrozenMessageImagePlan.snapshot(history)
        private val imagePlan = ImageRequestPreflight.history(settings, ProviderProtocol.MESSAGES, capability, historySnapshot, images)
        private var messages = MessagesRequest.history(historySnapshot, imagePlan)
        private val ownItems = mutableListOf<String>()
        private val cancelled = AtomicBoolean(false)
        @Volatile private var connection: HttpsURLConnection? = null
        private var usable = true
        private var started = false
        private var pending = emptySet<String>()

        init { MessagesRequest.body(settings.model, messages, tools, maxTokens) }
        private fun checkCancelled() { if (cancelled.get()) throw InterruptedException() }

        @Synchronized
        override fun updateToolDefinitions(definitions: List<ToolDefinition>) { tools = definitions.toList() }

        private val retry = app.daiyu.android.model.reconnect.ModelRequestRetry()
        override val canResume: Boolean get() = !cancelled.get() && retry.available
        @Synchronized
        override fun resume(sink: (ModelEvent) -> Unit) = retry.replay { next(it, sink) }
        override fun disconnectTransport() { try { connection?.disconnect() } catch (_: Exception) {} }

        @Synchronized
        override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
            checkCancelled()
            check(usable && (!started || pending.isNotEmpty())) { "Messages session cannot be replayed" }
            require(outputs.map { it.callId.value }.toSet() == pending && outputs.size == pending.size) {
                "Tool outputs must match all pending tool calls exactly once"
            }
            val savedMessages = messages.toString()
            val savedItems = ownItems.toList()
            val savedPending = pending
            val savedStarted = started
            val savedTools = tools
            retry.capture(outputs) {
                messages = JSONArray(savedMessages)
                ownItems.clear(); ownItems.addAll(savedItems)
                pending = savedPending; started = savedStarted; usable = true; tools = savedTools
            }
            started = true
            // Retain the exact request boundary; only an explicitly enabled Runner may replay it.
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
            ReasoningRequestPolicy.validate(request, ProviderProtocol.MESSAGES, if (summaryRequest) null else settings.reasoningOverride,
                if (summaryRequest) null else request.opt("model") as? String)
            settings.requestOptions.sessionGateway.requireExecutable()
            SessionGateway.applyAnthropic(request, sessionContext, settings.requestOptions.sessionGateway)
            val sessionHeaders = SessionGateway.headers(endpoint, sessionContext, settings.requestOptions.sessionGateway)
            val requestBytes = ImageRequestPreflight.prepare(request, settings, imagePlan, cancelled::get)
            var deliveryFailure: Exception? = null
            val deliver: (ModelEvent) -> Unit = { event ->
                checkCancelled()
                try { sink(event) } catch (failure: Exception) { deliveryFailure = failure; throw failure }
            }
            var ownedConnection: HttpsURLConnection? = null
            var configuringRequest = true
            try {
                checkCancelled()
                deliver(ModelEvent.InputEstimate(RequestInputEstimate.from(request, imagePlan)))
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
                conn.setFixedLengthStreamingMode(requestBytes.size.toLong())
                conn.outputStream.useProviderResource { requestBytes.writeTo(it, cancelled::get) }
                checkCancelled()
                val status = conn.responseCode
                checkCancelled()
                if (status !in 200..299) throw ProviderHttpFailure.from(conn, status, cancelled::get, request = request)
                if (conn.contentType?.substringBefore(';')?.trim()?.equals("text/event-stream", ignoreCase = true) != true)
                    throw ModelRequestException(ModelFailure.PROTOCOL)
                conn.inputStream.bufferedReader(Charsets.UTF_8).useProviderResponse { reader ->
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
                if (failure === deliveryFailure) {
                    if (failure is ModelDeliveryRejectedException) retry.failed(failure) else retry.completed()
                    throw failure
                }
                checkCancelled()
                val classified = providerTransportFailure(failure, configuringRequest)
                retry.failed(classified)
                throw classified
            } finally {
                requestBytes.close()
                // Do not read or expose provider error bodies. Close any already available error stream.
                try { ownedConnection?.errorStream?.close() } catch (_: Exception) {}
                try { ownedConnection?.disconnect() } catch (_: Exception) {} finally { connection = null }
            }
            checkCancelled()
            usable = true
            retry.completed()
        }

        override fun cancel() {
            cancelled.set(true)
            try { connection?.disconnect() } catch (_: Exception) { /* Best effort; cancellation stays primary. */ }
        }
    }
}
