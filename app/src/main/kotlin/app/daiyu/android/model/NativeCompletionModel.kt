package app.daiyu.android.model

import app.daiyu.android.domain.*
import app.daiyu.android.storage.ModelSettings
import app.daiyu.android.model.images.FrozenMessageImagePlan
import app.daiyu.android.model.sessiongateway.SessionGateway
import app.daiyu.android.model.sessiongateway.SessionGatewayContext
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

/** Native Chat Completions and Gemini transports. ModelPort/Runner still own the run. No retry/resume. */
class NativeCompletionModel internal constructor(
    private val settings: ModelSettings,
    private val connect: (URL) -> HttpsURLConnection,
    private val instructions: String? = null,
    private val sessionContext: SessionGatewayContext? = null,
    private val imageInputSupported: Boolean? = null,
) : ModelPort {
    internal constructor(snapshot: ProviderSnapshot, instructions: String? = null, sessionContext: SessionGatewayContext? = null) :
        this(snapshot.asModelSettings(), { it.openConnection() as HttpsURLConnection }, instructions, sessionContext,
            imageInputSupported = snapshot.imageInputSupported)

    override fun open(history: List<Message>): ModelSession = open(history, settings.enabledTools())
    override fun open(history: List<Message>, tools: Set<ReadOnlyTool>): ModelSession = open(history, NativeCompletionRequest.definitions(tools))
    override fun open(history: List<Message>, definitions: List<ToolDefinition>): ModelSession =
        Session(history, definitions.toList(), null)
    override fun open(history: List<Message>, definitions: List<ToolDefinition>, images: ModelImagePlan): ModelSession =
        Session(history, definitions.toList(), images)

    init { require(settings.protocol in setOf(ProviderProtocol.CHAT_COMPLETIONS, ProviderProtocol.GEMINI)) { "Unsupported native completion protocol" } }

    private inner class Session(
        history: List<Message>,
        private val tools: List<ToolDefinition>,
        images: ModelImagePlan?,
    ) : ModelSession {
        private val endpoint = settings.also { it.validate() }.requestEndpoint()
        private val historySnapshot = FrozenMessageImagePlan.snapshot(history)
        private val imagePlan = ImageRequestPreflight.history(
            settings, settings.protocol, imageInputSupported, historySnapshot, images)
        private val rows = NativeCompletionRequest.history(settings.protocol, historySnapshot, images = imagePlan)
        private val ownItems = mutableListOf<String>()
        private val cancelled = AtomicBoolean(false)
        @Volatile private var connection: HttpsURLConnection? = null
        private var usable = true
        private var started = false
        private var pending = emptyMap<String, String>()
        init { NativeCompletionRequest.body(settings, rows, tools, instructions) }
        private fun checkCancelled() { if (cancelled.get()) throw InterruptedException() }

        @Synchronized
        override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
            checkCancelled()
            check(usable && (!started || pending.isNotEmpty())) { "Model session cannot be replayed" }
            require(outputs.size == pending.size && outputs.map { it.callId.value }.toSet() == pending.keys) {
                "Tool outputs must match all pending tool calls exactly once"
            }
            started = true; usable = false
            val resultRows = outputs.map { output ->
                NativeCompletionRequest.result(settings.protocol, output.callId.value, pending.getValue(output.callId.value), output.output)
            }
            NativeCompletionRequest.groupResults(settings.protocol, resultRows).forEach { row ->
                val raw = row.toString()
                require(raw.length <= 1_000_000) { "工具结果超过原生历史存储上限" }
                rows.put(row); ownItems.add(raw)
            }
            val request = settings.requestOptions.apply(NativeCompletionRequest.body(settings, rows, tools, instructions), settings.protocol)
            if (settings.protocol == ProviderProtocol.CHAT_COMPLETIONS) require(request.opt("model") == settings.model) { "自定义请求体不能替换所选模型" }
            settings.requestOptions.sessionGateway.requireExecutable()
            // Existing session gateway field rules are OpenAI-specific; Gemini uses only owned session headers.
            if (settings.protocol == ProviderProtocol.CHAT_COMPLETIONS)
                SessionGateway.applyResponses(request, endpoint, settings.requestOptions.sessionGateway, sessionContext)
            else require(!settings.requestOptions.sessionGateway.matches(settings.model, endpoint)) { "Gemini 不支持 OpenAI 会话网关请求字段" }
            ReasoningRequestPolicy.validate(request, settings.protocol, settings.reasoningOverride)
            val headers = SessionGateway.headers(endpoint, sessionContext)
            val bytes = ImageRequestPreflight.prepare(request, settings, imagePlan, cancelled::get)
            var deliveryFailure: Exception? = null
            val deliver: (ModelEvent) -> Unit = { event ->
                checkCancelled()
                try { sink(event) } catch (failure: Exception) { deliveryFailure = failure; throw failure }
            }
            var owned: HttpsURLConnection? = null
            var configuringRequest = true
            try {
                checkCancelled()
                deliver(ModelEvent.InputEstimate(RequestInputEstimate.from(request, imagePlan)))
                checkCancelled()
                val conn = connect(URL(endpoint)); owned = conn; connection = conn
                checkCancelled()
                conn.requestMethod = "POST"; conn.doOutput = true; conn.instanceFollowRedirects = false
                conn.connectTimeout = 20_000; conn.readTimeout = 60_000
                conn.setRequestProperty(if (settings.protocol == ProviderProtocol.GEMINI) "x-goog-api-key" else "Authorization",
                    if (settings.protocol == ProviderProtocol.GEMINI) settings.apiKey else "Bearer ${settings.apiKey}")
                settings.customHeaders.applyTo(conn)
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Accept", "text/event-stream")
                headers.forEach { (name, value) -> conn.setRequestProperty(name, value) }
                configuringRequest = false
                conn.setFixedLengthStreamingMode(bytes.size.toLong())
                conn.outputStream.useProviderResource { bytes.writeTo(it, cancelled::get) }
                checkCancelled()
                val status = conn.responseCode
                checkCancelled()
                if (status !in 200..299) throw ProviderHttpFailure.from(conn, status, cancelled::get)
                if (conn.contentType?.substringBefore(';')?.trim()?.equals("text/event-stream", true) != true)
                    throw ModelRequestException(ModelFailure.PROTOCOL)
                conn.inputStream.bufferedReader(Charsets.UTF_8).useProviderResource { reader ->
                    val row = NativeCompletionStream.read(settings.protocol, reader, cancelled::get, deliver)
                    checkCancelled()
                    app.daiyu.android.model.images.FrozenMessageImagePlan.rejectRawImageNode(row)
                    val calls = NativeCompletionRequest.calls(settings.protocol, row)
                    rows.put(row); ownItems.add(row.toString())
                    deliver(ModelEvent.ProtocolOutput(ownItems.toList()))
                    checkCancelled(); pending = calls
                }
                checkCancelled()
            } catch (failure: Exception) {
                if (failure === deliveryFailure) throw failure
                checkCancelled()
                throw providerTransportFailure(failure, configuringRequest)
            } finally {
                bytes.close()
                try { owned?.errorStream?.close() } catch (_: Exception) {}
                try { owned?.disconnect() } catch (_: Exception) {} finally { connection = null }
            }
            checkCancelled(); usable = true
        }
        // Deliberately not synchronized: disconnect must interrupt a next() holding the session monitor.
        override fun cancel() {
            cancelled.set(true)
            try { connection?.disconnect() } catch (_: Exception) {}
        }
    }
}
