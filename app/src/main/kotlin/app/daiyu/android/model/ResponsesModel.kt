package app.daiyu.android.model

import app.daiyu.android.domain.*
import app.daiyu.android.storage.ModelSettings
import app.daiyu.android.model.sessiongateway.SessionGateway
import app.daiyu.android.model.sessiongateway.SessionGatewayContext
import app.daiyu.android.model.videos.FrozenMessageVideoPlan
import app.daiyu.android.model.videos.OpenRouterVideoCapability
import app.daiyu.android.model.videos.VideoPostApprovalProvider
import app.daiyu.android.model.videos.VideoPostRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

class ResponsesModel internal constructor(
    private val settings: ModelSettings,
    private val connect: (URL) -> HttpsURLConnection,
    private val imageInputSupported: Boolean?,
    private val assistantInstructions: String? = null,
    private val videoInputCapability: OpenRouterVideoCapability? = null,
    private val videoPostApprovalProvider: VideoPostApprovalProvider? = null,
    private val sessionContext: SessionGatewayContext? = null,
    private val backgroundResume: app.daiyu.android.model.reconnect.FrozenResponsesBackground? = null,
) : ModelPort {
    /** Preserve the assembled model/media/approval context; never bypass its approved open. */
    fun withBackgroundResume(capability: app.daiyu.android.model.reconnect.FrozenResponsesBackground): ResponsesModel {
        capability.requireMatches(settings)
        return ResponsesModel(settings, connect, imageInputSupported, assistantInstructions,
            videoInputCapability, videoPostApprovalProvider, sessionContext, capability)
    }
    private fun exposed(session: Session): ModelSession = if (backgroundResume != null) session else object : ModelSession {
        override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) = session.next(outputs, sink)
        override fun cancel() = session.cancel()
    }
    internal constructor(settings: ModelSettings, connect: (URL) -> HttpsURLConnection) : this(settings, connect, null)
    constructor(settings: ModelSettings) : this(settings, { it.openConnection() as HttpsURLConnection }, null)
    internal constructor(snapshot: ProviderSnapshot, assistantInstructions: String? = null,
                         videoInputCapability: OpenRouterVideoCapability? = null,
                         videoPostApprovalProvider: VideoPostApprovalProvider? = null,
                         sessionContext: SessionGatewayContext? = null) : this(snapshot.asModelSettings(),
        { it.openConnection() as HttpsURLConnection }, snapshot.imageInputSupported, assistantInstructions,
        videoInputCapability, videoPostApprovalProvider, sessionContext)
    override fun open(history: List<Message>): ModelSession = open(history, settings.enabledTools())
    override fun open(history: List<Message>, tools: Set<ReadOnlyTool>): ModelSession = open(history, tools.map {
        ToolDefinition(it.toolName, it.description, """{"type":"object","properties":{},"required":[],"additionalProperties":false}""")
    })
    override fun open(history: List<Message>, definitions: List<ToolDefinition>): ModelSession =
        exposed(Session(settings, history, connect, definitions.toList(), imageInputSupported, null, assistantInstructions,
            sessionContext = sessionContext, backgroundResume = backgroundResume))
    override fun open(history: List<Message>, definitions: List<ToolDefinition>, images: ModelImagePlan): ModelSession =
        exposed(Session(settings, history, connect, definitions.toList(), imageInputSupported, images, assistantInstructions,
            sessionContext = sessionContext, backgroundResume = backgroundResume))

    /** Explicit OpenRouter extension only. Generic ModelPort/ModelFactory paths remain unchanged.
     * Pass the same run-local capability used to freeze videos to this model's constructor.
     * A videoPostApprovalProvider must also supply fresh approval for EVERY HTTP POST. This
     * blocking ModelSession must be driven off the UI thread; its provider may suspend for UI.
     */
    fun open(history: List<Message>, definitions: List<ToolDefinition>, videos: FrozenMessageVideoPlan,
             images: ModelImagePlan? = null): ModelSession =
        exposed(Session(settings, history, connect, definitions.toList(), imageInputSupported, images,
            assistantInstructions, videoInputCapability, videos, videoPostApprovalProvider, sessionContext, backgroundResume))

    private class Session(
        private val settings: ModelSettings,
        history: List<Message>,
        private val connect: (URL) -> HttpsURLConnection,
        private val tools: List<ToolDefinition>,
        capability: Boolean?,
        images: ModelImagePlan?,
        private val assistantInstructions: String?,
        private val videoCapability: OpenRouterVideoCapability? = null,
        videos: FrozenMessageVideoPlan? = null,
        private val videoPostApprovalProvider: VideoPostApprovalProvider? = null,
        private val sessionContext: SessionGatewayContext? = null,
        private val backgroundResume: app.daiyu.android.model.reconnect.FrozenResponsesBackground? = null,
    ) : CheckpointedResumableModelSession {
        private var decoder = ResponsesStream.State()
        private var resumeAllowed = false
        private var localSink: ((ModelEvent) -> Unit)? = null
        override fun nextAcknowledged(outputs: List<ToolOutput>, localSink: (ModelEvent) -> Unit,
            sink: (ModelEventBatch) -> ModelDeliveryAck) {
            this.localSink = localSink
            try { nextAcknowledged(outputs, sink) } finally { this.localSink = null }
        }
        private var acknowledgedSink: ((ModelEventBatch) -> ModelDeliveryAck)? = null
        override val canResume: Boolean get() = backgroundResume != null && !cancelled.get() && resumeAllowed && decoder.canResume
        @Synchronized
        override fun nextAcknowledged(outputs: List<ToolOutput>, sink: (ModelEventBatch) -> ModelDeliveryAck) {
            check(backgroundResume != null)
            acknowledgedSink = sink
            try { next(outputs) {} } finally { acknowledgedSink = null }
        }
        override fun resume(sink: (ModelEvent) -> Unit) {
            // A Unit consumer cannot authorize cursor advancement.
            throw ModelDeliveryRejectedException()
        }
        @Synchronized
        override fun resumeAcknowledged(sink: (ModelEventBatch) -> ModelDeliveryAck) {
            checkCancelled()
            check(canResume) { "Original response cannot be resumed" }
            val id = requireNotNull(decoder.responseId)
            val sequence = requireNotNull(decoder.acknowledgedSequence)
            var conn: HttpsURLConnection? = null
            var configuringRequest = true
            var deliveryFailure: Exception? = null
            val deliver: (ModelEventBatch) -> ModelDeliveryAck = { batch ->
                try { sink(batch) } catch (failure: Exception) { deliveryFailure = failure; throw failure }
            }
            try {
                val url = settings.requestEndpoint() + "/$id?stream=true&starting_after=$sequence"
                conn = connect(URL(url))
                synchronized(postLock) { checkCancelled(); connection = conn }
                conn.requestMethod = "GET"; conn.doOutput = false; conn.instanceFollowRedirects = false
                conn.connectTimeout = 20_000; conn.readTimeout = 60_000
                conn.setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
                settings.customHeaders.applyTo(conn)
                conn.setRequestProperty("Accept", "text/event-stream")
                checkCancelled()
                configuringRequest = false
                val status = conn.responseCode
                if (status !in 200..299) {
                    resumeAllowed = false
                    throw ModelRequestException(when(status) {
                        401 -> ModelFailure.AUTHENTICATION
                        403 -> ModelFailure.PERMISSION
                        404, 405, 410 -> ModelFailure.ENDPOINT
                        in 300..399 -> ModelFailure.REDIRECT
                        else -> ModelFailure.PROTOCOL
                    })
                }
                if (conn.contentType?.substringBefore(';')?.trim()?.equals("text/event-stream", true) != true) {
                    resumeAllowed = false
                    throw ModelRequestException(ModelFailure.PROTOCOL)
                }
                val items = conn.inputStream.bufferedReader(Charsets.UTF_8).useProviderResource {
                    decoder.read(it, cancelled::get, prefix = ownItems.toList(), acknowledgedSink = deliver)
                }
                acceptItems(items)
                usable = true
                resumeAllowed = false
            } catch (failure: Exception) {
                if (failure === deliveryFailure) throw failure
                checkCancelled()
                val classified = providerTransportFailure(failure, configuringRequest)
                if (classified !is ModelDeliveryRejectedException &&
                    (classified as? ModelRequestException)?.failure !in setOf(ModelFailure.NETWORK, ModelFailure.TIMEOUT)) resumeAllowed = false
                throw classified
            } finally {
                synchronized(postLock) { if (connection === conn) connection = null }
                try { conn?.disconnect() } catch (_: Exception) {}
            }
        }
        private fun acceptItems(items: List<String>) {
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
            pending = calls.toSet()
        }
        override fun disconnectTransport() {
            val current = synchronized(postLock) { connection }
            try { current?.disconnect() } catch (_: Exception) {}
        }
        private val cancelled = AtomicBoolean(false)
        private val postLock = Any()
        private val historyIdentity = Any()
        @Volatile private var approvalWait: Job? = null
        @Volatile private var pendingVideoPost: VideoPostRequest? = null
        private var postNumber = 0
        @Volatile private var connection: HttpsURLConnection? = null
        private val historySnapshot = app.daiyu.android.model.images.FrozenMessageImagePlan.snapshot(history)
        private val imagePlan = ImageRequestPreflight.history(settings, ProviderProtocol.RESPONSES, capability, historySnapshot, images)
        private val videoPlan = videos?.also { it.validate(settings, videoCapability, historySnapshot) }
        private val input = ResponsesRequest.history(historySnapshot, imagePlan, videoPlan)
        private val ownItems = mutableListOf<String>()
        private var usable = true
        private var started = false
        private var pending = emptySet<String>()
        init { settings.validate() }
        private fun checkCancelled() {
            if (cancelled.get() || Thread.currentThread().isInterrupted) throw InterruptedException()
        }
        @Synchronized
        override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
            checkCancelled()
            if (backgroundResume != null) check(acknowledgedSink != null) { "Background streaming requires an explicit checkpoint consumer" }
            decoder = ResponsesStream.State()
            resumeAllowed = false
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
            val request = ResponsesRequest.withAssistantInstructions(
                settings.requestOptions.apply(ResponsesRequest.body(settings.model, input, tools, settings.requestOptions.openAiWebSearchEnabled), ProviderProtocol.RESPONSES),
                assistantInstructions,
            )
            if (java.net.URI(settings.endpoint).host.equals("ark.cn-beijing.volces.com", true)) request.remove("include")
            if (backgroundResume != null) {
                backgroundResume.requireMatches(settings)
                request.put("background", true).put("stream", true).put("store", false)
            } else require(!request.has("background") || request.opt("background") == false) { "Background mode requires explicit informed opt-in" }
            val finalUrl = settings.requestEndpoint()
            SessionGateway.applyResponses(request, finalUrl, settings.requestOptions.sessionGateway, sessionContext)
            if (videoPlan != null) app.daiyu.android.model.videos.VideoRequestPolicy.disableReasoning(request)
            ReasoningRequestPolicy.validate(request, ProviderProtocol.RESPONSES, settings.reasoningOverride)
            val sessionHeaders = SessionGateway.headers(finalUrl, sessionContext)
            // Video-only allowlist and exact history binding apply to the final merged payload
            // on EVERY round, before serialization/connect. Ordinary provider options stay flexible.
            videoPlan?.validateRequest(settings, request, input)
            val requestBytes = ImageRequestPreflight.bytes(request, settings, imagePlan, cancelled::get)
            var deliveryFailure: Exception? = null
            val deliver: (ModelEvent) -> Unit = { event ->
                checkCancelled()
                try { sink(event) } catch (failure: Exception) { deliveryFailure = failure; throw failure }
            }
            val deliverAcknowledged = acknowledgedSink?.let { target ->
                { batch: ModelEventBatch ->
                    try { target(batch) } catch (failure: Exception) { deliveryFailure = failure; throw failure }
                }
            }
            var ownedConnection: HttpsURLConnection? = null
            var configuringRequest = false
            var transportStarted = false
            var post: VideoPostRequest? = null
            try {
                checkCancelled()
                try { (localSink ?: deliver)(ModelEvent.InputEstimate(RequestInputEstimate.from(request, imagePlan))) }
                catch (failure: Exception) { deliveryFailure = failure; throw failure }
                checkCancelled()
                // The payload and all budgets/allowlists are final before asking the UI. Never
                // acquire a connection merely to prepare an approval dialog. No default approval.
                val approval = if (videoPlan != null) {
                    val provider = requireNotNull(videoPostApprovalProvider) {
                        "Original video requires fresh approval for each POST"
                    }
                    val request = VideoPostRequest(settings, videoPlan, historySnapshot,
                        historyIdentity, ++postNumber, requestBytes)
                    post = request
                    val wait = Job()
                    synchronized(postLock) {
                        checkCancelled()
                        pendingVideoPost = request
                        approvalWait = wait
                    }
                    try {
                        // ModelSession is deliberately blocking. This child can suspend while the
                        // parent UI obtains a permit; cancel() also cancels that suspension.
                        runBlocking(wait) { provider.approve(request) }
                    } finally {
                        synchronized(postLock) { if (approvalWait === wait) approvalWait = null }
                        wait.cancel()
                    }
                } else null
                val pendingPost = post
                if (pendingPost != null) {
                    synchronized(postLock) {
                        checkCancelled()
                        check(pendingVideoPost === pendingPost) { "Video POST request expired" }
                    }
                    // Revalidate after the suspension; the host consume operation must also fence
                    // its current run/config/history before spending its one-shot permit.
                    // Neither that external operation nor the connection factory may hold the
                    // control lock: cancel must return even if either waits on DB/another gate.
                    videoPlan?.validate(settings, videoCapability, historySnapshot)
                    videoPlan?.validateRequest(settings, request, input)
                    checkCancelled()
                    pendingPost.consume(approval)
                }
                checkCancelled()
                transportStarted = true
                configuringRequest = true
                val conn = connect(URL(finalUrl))
                // Own the result before checking cancellation so a late factory return is always
                // disconnected by finally, even when it must never be published or write a body.
                ownedConnection = conn
                synchronized(postLock) {
                    checkCancelled()
                    if (pendingPost != null) {
                        check(pendingVideoPost === pendingPost) { "Video POST request expired" }
                    }
                    connection = conn
                }
                checkCancelled()
                conn.requestMethod = "POST"; conn.doOutput = true; conn.instanceFollowRedirects = false
                conn.connectTimeout = 20_000; conn.readTimeout = 60_000
                conn.setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
                settings.customHeaders.applyTo(conn)
                conn.setRequestProperty("Content-Type", "application/json"); conn.setRequestProperty("Accept", "text/event-stream")
                sessionHeaders.forEach { (name, value) -> conn.setRequestProperty(name, value) }
                checkCancelled()
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
                    resumeAllowed = backgroundResume != null
                    val items = if (backgroundResume != null) decoder.read(reader, cancelled::get,
                        prefix = ownItems.toList(), acknowledgedSink = requireNotNull(deliverAcknowledged))
                    else ResponsesStream.read(reader, cancelled::get, deliver)
                    checkCancelled()
                    acceptItems(items)
                    if (backgroundResume == null) deliver(ModelEvent.ProtocolOutput(ownItems.toList()))
                    resumeAllowed = false
                    checkCancelled()
                }
                checkCancelled()
            } catch (failure: Exception) {
                if (failure === deliveryFailure) throw failure
                checkCancelled()
                if (!transportStarted) throw failure
                val classified = providerTransportFailure(failure, configuringRequest)
                if (classified !is ModelDeliveryRejectedException &&
                    (classified as? ModelRequestException)?.failure !in setOf(ModelFailure.NETWORK, ModelFailure.TIMEOUT)) resumeAllowed = false
                throw classified
            } finally {
                post?.invalidate()
                synchronized(postLock) {
                    if (pendingVideoPost === post) pendingVideoPost = null
                    if (connection === ownedConnection) connection = null
                }
                try { ownedConnection?.disconnect() } catch (_: Exception) {}
            }
            checkCancelled()
            usable = true
        }
        override fun cancel() {
            cancelled.set(true)
            val (post, wait, current) = synchronized(postLock) {
                Triple(pendingVideoPost, approvalWait, connection)
            }
            // Revocation, coroutine cancellation handlers and transport cleanup run outside the
            // control lock too. This signals cancellation; it does not drain an in-flight worker.
            post?.invalidate()
            wait?.cancel()
            try { current?.disconnect() } catch (_: Exception) {}
        }
    }
}
