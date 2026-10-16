package app.daiyu.android.model.generation

/** Explicit wire contracts, not provider/model auto-detection or a claim of model capability. */
enum class GenerationProtocol { OPENAI_IMAGES_JSON, OPENAI_IMAGE_EDIT_MULTIPART, OPENAI_VIDEO_MULTIPART }
enum class GenerationKind { IMAGE, VIDEO }

data class GenerationIdentity(val operationId: String, val itemId: String) {
    internal fun validate() {
        require(operationId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        require(itemId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
    }
}

/** Takes a defensive snapshot; later caller mutation cannot change the submitted reference/mask. */
class GenerationInput(bytes: ByteArray, val mimeType: String) {
    private val snapshot = bytes.copyOf()
    val bytes: ByteArray get() = snapshot.copyOf()
    override fun toString(): String = "GenerationInput(<redacted>)"
}

/** endpoint is the full, exact resource URL, including any parent-configured API prefix. */
class GenerationRequest(
    val identity: GenerationIdentity,
    val protocol: GenerationProtocol,
    val endpoint: String,
    val apiKey: String,
    val model: String,
    val prompt: String,
    references: List<GenerationInput> = emptyList(),
    val mask: GenerationInput? = null,
    parameters: Map<String, String> = emptyMap(),
) {
    val references: List<GenerationInput> = references.toList()
    val parameters: Map<String, String> = parameters.toMap()
    override fun toString(): String = "GenerationRequest(protocol=$protocol, payload=<redacted>)"
}

data class GenerationMediaInfo(val mimeType: String, val width: Int = 0, val height: Int = 0)
data class GenerationAssetReceipt(val assetId: String)

/**
 * Parent boundary: inspect must actually decode/validate bytes, MIME, dimensions and resource
 * limits (not trust the declaration). commit must be atomic/idempotent for identity+outputIndex,
 * and must recheck the parent's owner/cancellation fence before publication. No chat DB access.
 */
interface GenerationAssets {
    fun inspect(kind: GenerationKind, bytes: ByteArray, declaredMime: String): GenerationMediaInfo
    fun commit(
        identity: GenerationIdentity,
        outputIndex: Int,
        kind: GenerationKind,
        bytes: ByteArray,
        info: GenerationMediaInfo,
    ): GenerationAssetReceipt
}

enum class GenerationFailure {
    INVALID_REQUEST, UNSUPPORTED_PARAMETER, UNSUPPORTED_REFERENCE, INVALID_MEDIA, INVALID_BATCH,
    CANCELLED, STORE_UNAVAILABLE, IDENTITY_CONFLICT, ALREADY_ATTEMPTED, TRANSPORT,
    HTTP, EMPTY_RESPONSE, PROTOCOL, REMOTE_FAILED, DOWNLOAD_DENIED, ASSET_REJECTED,
}

class GenerationPreflightException(val failure: GenerationFailure) : IllegalArgumentException(failure.name)

/** Contains no credential. Poll/content URLs are always derived from the frozen request. */
data class GenerationHandle internal constructor(
    val identity: GenerationIdentity,
    val planFingerprint: String,
    val taskId: String,
    val pollUrl: String,
    val contentUrl: String,
)

sealed class GenerationSource {
    class Inline(bytes: ByteArray, val mimeType: String) : GenerationSource() {
        private val snapshot = bytes.copyOf()
        val bytes: ByteArray get() = snapshot.copyOf()
        override fun toString(): String = "Inline(<redacted>)"
    }
    class Remote internal constructor(val url: String, internal val authenticated: Boolean = false) : GenerationSource() {
        override fun toString(): String = "Remote(<redacted>)"
    }
}

/** Invalid siblings are retained by index instead of causing valid outputs to disappear. */
data class GenerationOutput(
    val index: Int,
    val source: GenerationSource? = null,
    val failure: GenerationFailure? = null,
)

sealed class Submission {
    /** Provider completed; assets still need explicit downloadAndCommit through the parent boundary. */
    data class Completed(val outputs: List<GenerationOutput>) : Submission()
    data class Pending(val handle: GenerationHandle) : Submission()
    /** POST may have been accepted/billed. This result MUST NOT be interpreted as permission to retry. */
    data class Unknown(
        val reason: GenerationFailure,
        val statusCode: Int? = null,
        val handle: GenerationHandle? = null,
    ) : Submission()
    /** No POST was made by this invocation. An earlier attempt may still exist for this identity. */
    data class NotExecuted(val reason: GenerationFailure) : Submission()
}

data class GenerationBatchItem(val identity: GenerationIdentity, val submission: Submission)

/** Only fingerprints/identities/protocol go to the ledger, never prompts, bytes or credentials. */
data class GenerationAttempt(
    val identity: GenerationIdentity,
    val fingerprint: String,
    val protocol: GenerationProtocol,
)

sealed class GenerationReservation {
    object Started : GenerationReservation()
    data class Existing(val fingerprint: String, val submission: Submission? = null) : GenerationReservation()
}

/**
 * Required PERSISTENT parent implementation, shared by all clients/processes. reserve must atomically
 * durably insert a tombstone before returning Started; existing identity must NEVER return Started,
 * including after crashes. finish updates that row without deleting/reopening it; it must merge
 * monotonically (never replace Completed with a late Pending/Unknown, or erase known outputs).
 * On ambiguous store failure the caller sends nothing. Persisted outcomes may contain signed
 * URLs/media: protect them. Identities must be unique in the parent's ownership/account scope.
 * Intentionally no in-memory/default implementation and no reset/retry API.
 */
interface GenerationAttemptStore {
    fun reserve(attempt: GenerationAttempt): GenerationReservation
    fun finish(attempt: GenerationAttempt, submission: Submission)
}

sealed class GenerationAssetResult {
    data class Committed(val receipt: GenerationAssetReceipt) : GenerationAssetResult()
    data class Failed(val reason: GenerationFailure) : GenerationAssetResult()
}

/** Injected HTTP implementations must execute at most once: no redirects/auth/transport retries. */
fun interface GenerationHttp {
    fun execute(request: GenerationHttpRequest): GenerationHttpResponse
}

class GenerationHttpRequest internal constructor(
    val method: String,
    val url: String,
    headers: Map<String, String>,
    body: ByteArray?,
    val maxResponseBytes: Int,
) {
    val headers: Map<String, String> = headers.toMap()
    private val snapshot = body?.copyOf()
    val body: ByteArray? get() = snapshot?.copyOf()
    override fun toString(): String = "GenerationHttpRequest(method=$method, payload=<redacted>)"
}

class GenerationHttpResponse(val statusCode: Int, bytes: ByteArray, val mimeType: String = "") {
    private val snapshot = bytes.copyOf()
    val bytes: ByteArray get() = snapshot.copyOf()
    override fun toString(): String = "GenerationHttpResponse(status=$statusCode, payload=<redacted>)"
}
