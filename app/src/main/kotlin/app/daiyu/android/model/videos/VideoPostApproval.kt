package app.daiyu.android.model.videos

import app.daiyu.android.domain.Message
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.storage.ModelSettings
import java.security.MessageDigest
import java.util.Collections

/** UI/host adapter, invoked once for EACH actual video POST, including tool continuation POSTs.
 * Called from a blocking model worker via a cancellable coroutine, never from ordinary text/image
 * requests. The host must obtain a NEW run permit and bind its real consume operation below.
 * Returning null denies the POST. This callback and its result are never persisted.
 */
fun interface VideoPostApprovalProvider {
    suspend fun approve(request: VideoPostRequest): VideoPostApproval?
}

/** Opaque transport handoff, NOT capability evidence or a replacement for the host's run permit.
 * Only the requesting VideoPostRequest can create/consume it; it cannot authorize another POST.
 */
class VideoPostApproval private constructor(
    internal val request: VideoPostRequest,
    internal val consumePermit: () -> Unit,
) {
    override fun toString() = "VideoPostApproval(<one POST>)"
    internal companion object {
        fun bind(request: VideoPostRequest, consumePermit: () -> Unit) =
            VideoPostApproval(request, consumePermit)
    }
}

/** Immutable description of the already serialized, bounded POST. No video bytes, API key or
 * data URL is exposed. videoPlan is an identity handle for the host's complete ordered refs;
 * the host must retain that mapping when freezing, not reconstruct it from a cover/last row.
 * historyIdentity distinguishes sessions even if their expanded history has equal values.
 */
class VideoPostRequest internal constructor(
    private val configuration: ModelSettings,
    val videoPlan: FrozenMessageVideoPlan,
    history: List<Message>,
    val historyIdentity: Any,
    val postNumber: Int,
    val requestByteCount: Int,
    val requestSha256: String,
) {
    internal constructor(configuration: ModelSettings, videoPlan: FrozenMessageVideoPlan, history: List<Message>,
        historyIdentity: Any, postNumber: Int, body: app.daiyu.android.model.PreparedRequestBody) :
        this(configuration, videoPlan, history, historyIdentity, postNumber, body.size, body.sha256)
    internal constructor(configuration: ModelSettings, videoPlan: FrozenMessageVideoPlan, history: List<Message>,
        historyIdentity: Any, postNumber: Int, requestBytes: ByteArray) : this(configuration, videoPlan, history,
        historyIdentity, postNumber, requestBytes.size, MessageDigest.getInstance("SHA-256").digest(requestBytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) })
    val endpoint: String = configuration.requestEndpoint()
    val model: String = configuration.model
    val protocol: ProviderProtocol = configuration.protocol
    val history: List<Message> = Collections.unmodifiableList(history.map {
        it.copy(blocks = Collections.unmodifiableList(it.blocks.toList()),
            protocolItems = Collections.unmodifiableList(it.protocolItems.toList()),
            historySegments = emptyList())
    })
    private var active = true

    /** Includes credential, options, configured endpoint/path and every settings value, without
     * exposing credentials for UI/logging. A changed/restored configuration needs a fresh permit.
     */
    fun matchesConfiguration(settings: ModelSettings): Boolean = configuration == settings

    /** Call only after current-run authorization (including trusted default permission), with the real consumption operation.
     * That operation MUST atomically validate the current run/config/history/ordered refs and
     * consume the host's one-shot permit, throwing on invalidation/reuse. It runs immediately
     * before connection creation, NOT when the dialog first returns. An empty closure is not
     * authorization. Late UI callbacks cannot bind an expired request.
     */
    @Synchronized
    fun bindPermitConsumption(consumePermit: () -> Unit): VideoPostApproval {
        check(active) { "Video POST approval request expired" }
        return VideoPostApproval.bind(this, consumePermit)
    }

    internal fun consume(approval: VideoPostApproval?) {
        val operation = synchronized(this) {
            check(active && approval != null && approval.request === this) {
                "A fresh original-video permit is required for each POST"
            }
            // Burn the handoff even if host validation/consumption throws.
            active = false
            approval.consumePermit
        }
        operation()
    }

    @Synchronized
    internal fun invalidate() { active = false }

    override fun toString() = "VideoPostRequest(<original-video POST $postNumber; payload redacted>)"
}
