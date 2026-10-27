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
    private val oauthHeaders: (app.daiyu.android.model.oauth.CodexAuth) -> Map<String, String> = app.daiyu.android.model.oauth.CodexOAuthRuntime::headers,
    private val oauthRefresh: (app.daiyu.android.model.oauth.CodexAuth, String) -> Map<String, String> = app.daiyu.android.model.oauth.CodexOAuthRuntime::refreshHeaders,
    private val summaryRequest: Boolean = false,
) : ModelPort {
    /** Preserve the assembled model/media/approval context; never bypass its approved open. */
    fun withBackgroundResume(capability: app.daiyu.android.model.reconnect.FrozenResponsesBackground): ResponsesModel {
        capability.requireMatches(settings)
        return ResponsesModel(settings, connect, imageInputSupported, assistantInstructions,
            videoInputCapability, videoPostApprovalProvider, sessionContext, capability, oauthHeaders, oauthRefresh, summaryRequest)
    }
    private fun exposed(session: Session): ModelSession = if (backgroundResume != null) session else object : RequestRetryModelSession {
        override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) = session.next(outputs, sink)
        override fun cancel() = session.cancel()
        override val canResume: Boolean get() = session.canResume
        override fun resume(sink: (ModelEvent) -> Unit) = session.resume(sink)
        override fun disconnectTransport() = session.disconnectTransport()
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
            sessionContext = sessionContext, backgroundResume = backgroundResume, oauthHeaders = oauthHeaders, oauthRefresh = oauthRefresh, summaryRequest = summaryRequest))
    override fun open(history: List<Message>, definitions: List<ToolDefinition>, images: ModelImagePlan): ModelSession =
        exposed(Session(settings, history, connect, definitions.toList(), imageInputSupported, images, assistantInstructions,
            sessionContext = sessionContext, backgroundResume = backgroundResume, oauthHeaders = oauthHeaders, oauthRefresh = oauthRefresh, summaryRequest = summaryRequest))

    /** Explicit OpenRouter extension only. Generic ModelPort/ModelFactory paths remain unchanged.
     * Pass the same run-local capability used to freeze videos to this model's constructor.
     * A videoPostApprovalProvider must also supply fresh approval for EVERY HTTP POST. This
     * blocking ModelSession must be driven off the UI thread; its provider may suspend for UI.
     */
    fun open(history: List<Message>, definitions: List<ToolDefinition>, videos: FrozenMessageVideoPlan,
             images: ModelImagePlan? = null): ModelSession =
        exposed(Session(settings, history, connect, definitions.toList(), imageInputSupported, images,
            assistantInstructions, videoInputCapability, videos, videoPostApprovalProvider, sessionContext, backgroundResume, oauthHeaders, oauthRefresh, summaryRequest))

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
        private val oauthHeaders: (app.daiyu.android.model.oauth.CodexAuth) -> Map<String, String>,
        private val oauthRefresh: (app.daiyu.android.model.oauth.CodexAuth, String) -> Map<String, String>,
        private val summaryRequest: Boolean = false,
    ) : CheckpointedResumableModelSession, RequestRetryModelSession {
        private var decoder = ResponsesStream.State()
        private var resumeAllowed = false
        private var localSink: ((ModelEvent) -> Unit)? = null
        private var consumerFailure = false
        override fun nextAcknowledged(outputs: List<ToolOutput>, localSink: (ModelEvent) -> Unit,
            sink: (ModelEventBatch) -> ModelDeliveryAck) {
            this.localSink = localSink
            try { nextAcknowledged(outputs, sink) } finally { this.localSink = null }
        }
        private var acknowledgedSink: ((ModelEventBatch) -> ModelDeliveryAck)? = null
        private val retry = app.daiyu.android.model.reconnect.ModelRequestRetry()
        private val cursorAvailable: Boolean get() = backgroundResume != null && resumeAllowed && decoder.canResume
        override val retriesCurrentRequest: Boolean get() = !cursorAvailable
        override val canResume: Boolean get() = !cancelled.get() && (cursorAvailable || retry.available)
        @Synchronized
        override fun nextAcknowledged(outputs: List<ToolOutput>, sink: (ModelEventBatch) -> ModelDeliveryAck) {
            check(backgroundResume != null)
            acknowledgedSink = sink
            try { next(outputs) {} } finally { acknowledgedSink = null }
        }
        @Synchronized
        override fun resume(sink: (ModelEvent) -> Unit) {
            check(backgroundResume == null) { "Background recovery requires acknowledgements" }
            retry.replay { next(it, sink) }
        }
        @Synchronized
        override fun resumeAcknowledged(sink: (ModelEventBatch) -> ModelDeliveryAck) {
            checkCancelled()
            check(canResume) { "Original response cannot be resumed" }
            consumerFailure = false
            if (!cursorAvailable) {
                retry.replay { nextAcknowledged(it, sink) }
                return
            }
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
                val authentication = authHeaders()
                conn = connect(URL(url))
                synchronized(postLock) { checkCancelled(); connection = conn }
                conn.requestMethod = "GET"; conn.doOutput = false; conn.instanceFollowRedirects = false
                conn.connectTimeout = 20_000; conn.readTimeout = 60_000
                settings.customHeaders.applyTo(conn)
                authentication.forEach { (name, value) -> conn.setRequestProperty(name, value) }
                conn.setRequestProperty("Accept", "text/event-stream")
                checkCancelled()
                configuringRequest = false
                val status = conn.responseCode
                if (status !in 200..299) {
                    val retryAuth = retryAuthentication(status, authentication)
                    if (retryAuth == null && status != 429 && status !in 500..599) resumeAllowed = false
                    throw ProviderHttpFailure.from(conn, status, cancelled::get)
                }
                if (conn.contentType?.substringBefore(';')?.trim()?.equals("text/event-stream", true) != true) {
                    resumeAllowed = false
                    throw ModelRequestException(ModelFailure.PROTOCOL)
                }
                val items = conn.inputStream.bufferedReader(Charsets.UTF_8).useProviderResponse {
                    decoder.read(it, cancelled::get, prefix = ownItems.toList(), acknowledgedSink = deliver)
                }
                acceptItems(items)
                usable = true
                resumeAllowed = false
                retry.completed()
            } catch (failure: Exception) {
                if (failure === deliveryFailure) {
                    if (failure !is ModelDeliveryRejectedException) {
                        consumerFailure = true
                        resumeAllowed = false
                        retry.completed()
                    }
                    throw failure
                }
                checkCancelled()
                val classified = providerTransportFailure(failure, configuringRequest)
                if (classified !is ModelDeliveryRejectedException &&
                    (classified as? ModelRequestException)?.failure !in setOf(ModelFailure.NETWORK, ModelFailure.TIMEOUT, ModelFailure.SERVER, ModelFailure.RATE_LIMIT) &&
                    !((classified as? ModelRequestException)?.failure == ModelFailure.AUTHENTICATION && rejectedAuthorization != null)) resumeAllowed = false
                if (refreshFailed) { resumeAllowed = false; retry.completed() }
                else retry.failed(classified, authenticationRetry = classified is ModelRequestException &&
                    classified.failure == ModelFailure.AUTHENTICATION && rejectedAuthorization != null)
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
        private var input = ResponsesRequest.history(historySnapshot, imagePlan, videoPlan)
        private val imageFiles = OfficialImageFiles(settings, connect, cancelled::get) { connection = it }
        private val ownItems = mutableListOf<String>()
        private var usable = true
        private var started = false
        private var pending = emptySet<String>()
        init { settings.validate() }
        private var refreshAttempted = false
        private var refreshFailed = false
        private var rejectedAuthorization: String? = null
        private fun retryAuthentication(status: Int, headers: Map<String, String>): ModelFailure? {
            if (status != 401 || settings.oauth == null || refreshAttempted) return null
            val rejected = headers["Authorization"] ?: return null
            refreshAttempted = true
            rejectedAuthorization = rejected
            return ModelFailure.AUTHENTICATION
        }
        private fun authHeaders(): Map<String, String> {
            settings.validateConnection()
            val result = if (settings.oauth == null) mapOf("Authorization" to "Bearer ${settings.apiKey}")
            else try {
                val rejected = rejectedAuthorization
                if (rejected == null) oauthHeaders(settings.oauth)
                else {
                    rejectedAuthorization = null
                    refreshFailed = true
                    oauthRefresh(settings.oauth, rejected).also { refreshFailed = false }
                }
            }
                catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
                catch (failure: app.daiyu.android.model.oauth.CodexOAuthFailure) {
                    throw ModelRequestException(when (failure.reason) {
                        app.daiyu.android.model.oauth.CodexOAuthFailureReason.NETWORK -> ModelFailure.NETWORK
                        app.daiyu.android.model.oauth.CodexOAuthFailureReason.TIMEOUT -> ModelFailure.TIMEOUT
                        app.daiyu.android.model.oauth.CodexOAuthFailureReason.RATE_LIMITED -> ModelFailure.RATE_LIMIT
                        app.daiyu.android.model.oauth.CodexOAuthFailureReason.SERVER -> ModelFailure.SERVER
                        else -> ModelFailure.AUTHENTICATION
                    })
                }
                catch (_: Exception) { throw ModelRequestException(ModelFailure.AUTHENTICATION) }
            checkCancelled()
            return result
        }
        private fun checkCancelled() {
            if (cancelled.get() || Thread.currentThread().isInterrupted) throw InterruptedException()
        }
        @Synchronized
        override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
            checkCancelled()
            if (backgroundResume != null) check(acknowledgedSink != null) { "Background streaming requires an explicit checkpoint consumer" }
            decoder = ResponsesStream.State()
            resumeAllowed = false
            consumerFailure = false
            check(usable && (!started || pending.isNotEmpty())) { "Responses session cannot be replayed" }
            require(outputs.map { it.callId.value }.toSet() == pending && outputs.size == pending.size) {
                "Tool outputs must match all pending tool calls exactly once"
            }
            val savedInput = input.toString()
            val savedItems = ownItems.toList()
            val savedPending = pending
            val savedStarted = started
            if (!retry.replaying) { refreshAttempted = false; refreshFailed = false; rejectedAuthorization = null }
            retry.capture(outputs) {
                input = org.json.JSONArray(savedInput)
                ownItems.clear(); ownItems.addAll(savedItems)
                pending = savedPending; started = savedStarted; usable = true
            }
            started = true
            usable = false
            try {
                advance(outputs, sink)
                retry.completed()
            } catch (failure: Exception) {
                if (consumerFailure || refreshFailed) retry.completed() else retry.failed(failure,
                    authenticationRetry = failure is ModelRequestException && failure.failure == ModelFailure.AUTHENTICATION && rejectedAuthorization != null)
                if (!retry.available && !cursorAvailable) imageFiles.closeAsync()
                throw failure
            }
        }
        private fun advance(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
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
            if (settings.oauth != null) app.daiyu.android.model.oauth.CodexRequestPolicy.apply(request, settings.requestOptions.speedMode, preserveReasoning = summaryRequest)
            val finalUrl = settings.requestEndpoint()
            SessionGateway.applyResponses(request, finalUrl, settings.requestOptions.sessionGateway, sessionContext)
            if (videoPlan != null) app.daiyu.android.model.videos.VideoRequestPolicy.disableReasoning(request)
            ReasoningRequestPolicy.validate(request, ProviderProtocol.RESPONSES, if (summaryRequest) null else settings.reasoningOverride,
                if (summaryRequest) null else request.opt("model") as? String)
            val sessionHeaders = SessionGateway.headers(finalUrl, sessionContext)
            // Video-only allowlist and exact history binding apply to the final merged payload
            // on EVERY round, before serialization/connect. Ordinary provider options stay flexible.
            videoPlan?.validateRequest(settings, request, input)
            // Resolve on a small copy: never mutate the session's marker history between rounds.
            val wireRequest = JSONObject(request.toString())
            if (imagePlan != null && OfficialImageFiles.supported(settings) && backgroundResume == null) {
                ImageRequestPreflight.prepare(request, settings, imagePlan, cancelled::get, videoPlan).use { }
            }
            val uploaded = if (backgroundResume == null) imageFiles.resolve(wireRequest, imagePlan) else emptySet()
            val requestBytes = ImageRequestPreflight.prepare(wireRequest, settings, imagePlan, cancelled::get, videoPlan, uploaded)
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
            var completedPost = false
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
                val authentication = authHeaders()
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
                settings.customHeaders.applyTo(conn)
                authentication.forEach { (name, value) -> conn.setRequestProperty(name, value) }
                if (settings.oauth != null) app.daiyu.android.model.oauth.CodexRequestPolicy.routingHint(wireRequest)?.let {
                    conn.setRequestProperty("x-codex-routing-hint", it)
                }
                conn.setRequestProperty("Content-Type", "application/json"); conn.setRequestProperty("Accept", "text/event-stream")
                sessionHeaders.forEach { (name, value) -> conn.setRequestProperty(name, value) }
                checkCancelled()
                configuringRequest = false
                conn.setFixedLengthStreamingMode(requestBytes.size.toLong())
                conn.outputStream.useProviderResource { requestBytes.writeTo(it, cancelled::get) }
                checkCancelled()
                val status = conn.responseCode
                checkCancelled()
                if (status !in 200..299) {
                    retryAuthentication(status, authentication)
                    throw ProviderHttpFailure.from(conn, status, cancelled::get)
                }
                if (conn.contentType?.substringBefore(';')?.trim()?.equals("text/event-stream", ignoreCase = true) != true)
                    throw ModelRequestException(ModelFailure.PROTOCOL)
                conn.inputStream.bufferedReader(Charsets.UTF_8).useProviderResponse { reader ->
                    resumeAllowed = backgroundResume != null
                    val items = if (backgroundResume != null) decoder.read(reader, cancelled::get,
                        prefix = ownItems.toList(), acknowledgedSink = requireNotNull(deliverAcknowledged))
                    else ResponsesStream.read(reader, cancelled::get, deliver)
                    checkCancelled()
                    acceptItems(items)
                    completedPost = true
                    if (backgroundResume == null) deliver(ModelEvent.ProtocolOutput(ownItems.toList()))
                    resumeAllowed = false
                    checkCancelled()
                }
                checkCancelled()
            } catch (failure: Exception) {
                if (failure === deliveryFailure) {
                    if (failure !is ModelDeliveryRejectedException) {
                        consumerFailure = true
                        resumeAllowed = false
                        retry.completed()
                    }
                    throw failure
                }
                checkCancelled()
                if (!transportStarted) throw failure
                val classified = providerTransportFailure(failure, configuringRequest)
                if (classified !is ModelDeliveryRejectedException &&
                    (classified as? ModelRequestException)?.failure !in setOf(ModelFailure.NETWORK, ModelFailure.TIMEOUT, ModelFailure.SERVER, ModelFailure.RATE_LIMIT) &&
                    !((classified as? ModelRequestException)?.failure == ModelFailure.AUTHENTICATION && rejectedAuthorization != null)) resumeAllowed = false
                throw classified
            } finally {
                requestBytes.close()
                if (completedPost && pending.isEmpty()) imageFiles.closeAsync()
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
            imageFiles.closeAsync()
        }
    }
}
