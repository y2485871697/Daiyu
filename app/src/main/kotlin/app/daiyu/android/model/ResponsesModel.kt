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
) : ModelPort {
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
        Session(settings, history, connect, definitions.toList(), imageInputSupported, null, assistantInstructions,
            sessionContext = sessionContext)
    override fun open(history: List<Message>, definitions: List<ToolDefinition>, images: ModelImagePlan): ModelSession =
        Session(settings, history, connect, definitions.toList(), imageInputSupported, images, assistantInstructions,
            sessionContext = sessionContext)

    /** Explicit OpenRouter extension only. Generic ModelPort/ModelFactory paths remain unchanged.
     * Pass the same run-local capability used to freeze videos to this model's constructor.
     * A videoPostApprovalProvider must also supply fresh approval for EVERY HTTP POST. This
     * blocking ModelSession must be driven off the UI thread; its provider may suspend for UI.
     */
    fun open(history: List<Message>, definitions: List<ToolDefinition>, videos: FrozenMessageVideoPlan,
             images: ModelImagePlan? = null): ModelSession =
        Session(settings, history, connect, definitions.toList(), imageInputSupported, images,
            assistantInstructions, videoInputCapability, videos, videoPostApprovalProvider, sessionContext)

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
    ) : ModelSession {
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
                settings.requestOptions.apply(ResponsesRequest.body(settings.model, input, tools), ProviderProtocol.RESPONSES),
                assistantInstructions,
            )
            val finalUrl = settings.requestEndpoint()
            SessionGateway.applyResponses(request, finalUrl, settings.requestOptions.sessionGateway, sessionContext)
            ReasoningRequestPolicy.validate(request, ProviderProtocol.RESPONSES)
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
            var ownedConnection: HttpsURLConnection? = null
            var post: VideoPostRequest? = null
            try {
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
                conn.setRequestProperty("Content-Type", "application/json"); conn.setRequestProperty("Accept", "text/event-stream")
                sessionHeaders.forEach { (name, value) -> conn.setRequestProperty(name, value) }
                checkCancelled()
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
