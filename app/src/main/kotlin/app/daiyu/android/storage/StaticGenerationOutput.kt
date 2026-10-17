package app.daiyu.android.storage

import app.daiyu.android.model.generation.GenerationKind
import app.daiyu.android.model.generation.GenerationProtocol
import app.daiyu.android.model.generation.GenerationRequestPlan
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** Immutable origin evidence, NOT an attempt, a live receipt, or permission to execute a tool.
 * Capture only after the host has selected an authoritative registered/readonly source. The
 * destination asset store independently verifies the original bytes; scalar validity alone is
 * never proof that the caller owns or can read a source. There is no toLiveReference method.
 */
internal data class StaticGenerationProvenance(
    val accountScope: String,
    val operationId: String,
    val itemId: String,
    val outputIndex: Int,
    val conversationId: String,
    val assistantMessageId: String,
    val runId: String,
    val toolCallId: String,
    val toolName: String,
    val fingerprint: String,
    val protocol: GenerationProtocol,
    val receipt: String,
    val kind: GenerationKind,
    val mimeType: String,
    val bytes: Long,
    val sha256: String,
    val width: Int,
    val height: Int,
    val durationMs: Long,
) {
    init {
        staticScope(accountScope)
        listOf(conversationId, assistantMessageId, runId, toolCallId, toolName).forEach(::staticId)
        require(operationId == GenerationAttemptBinding(conversationId, assistantMessageId, runId,
            toolCallId, toolName).operationId)
        require(itemId.matches(Regex("[A-Za-z0-9_-]{1,128}")) && outputIndex in 0..31)
        require(fingerprint.matches(STATIC_HASH) && sha256.matches(STATIC_HASH))
        require(receipt.matches(Regex("genasset-v1-[0-9a-f]{64}-[0-9a-f]{64}")) && receipt.endsWith("-$sha256"))
        require(width > 0 && height > 0 && width.toLong() * height <= 40_000_000)
        when (kind) {
            GenerationKind.IMAGE -> require(protocol != GenerationProtocol.OPENAI_VIDEO_MULTIPART &&
                mimeType in setOf("image/png", "image/jpeg", "image/webp") && durationMs == 0L &&
                bytes in 1..GenerationRequestPlan.MAX_IMAGE_BYTES.toLong())
            GenerationKind.VIDEO -> require(protocol == GenerationProtocol.OPENAI_VIDEO_MULTIPART &&
                mimeType in setOf("video/mp4", "video/webm") && durationMs in 1..3_600_000L &&
                bytes in 1..GenerationRequestPlan.MAX_VIDEO_BYTES.toLong())
        }
    }

    override fun toString() = "StaticGenerationProvenance(kind=$kind, payload=<redacted>)"

    companion object {
        /** Does not read files or grant authority: use the existing safe source reader as well. */
        fun capture(reference: GenerationOutputReference) = with(reference) {
            StaticGenerationProvenance(accountScope, identity.operationId, identity.itemId, outputIndex,
                conversationId, assistantMessageId, runId, toolCallId, toolName, fingerprint, protocol,
                receipt.assetId, kind, mimeType, bytes, sha256, width, height, durationMs)
        }
    }
}

/** A distinct physical owner and its exact remapped historical invocation. Account is a local,
 * manifest-only namespace. Never infer this owner from a displayed/last segment or current config.
 */
internal data class StaticGenerationDestination(
    val accountScope: String,
    val conversationId: String,
    val assistantMessageId: String,
    val runId: String,
    val toolCallId: String,
    val toolName: String,
) {
    init {
        staticScope(accountScope)
        listOf(conversationId, assistantMessageId, runId, toolCallId, toolName).forEach(::staticId)
    }
    internal fun fields() = listOf(accountScope, conversationId, assistantMessageId, runId, toolCallId, toolName)
    override fun toString() = "StaticGenerationDestination(<redacted>)"
}

/** A readonly mapping, never stored in generation_outputs/generation_attempts. Same destination
 * slot + different provenance/metadata is a conflict, not an overwrite. Subsequent branches keep
 * source unchanged (including the original receipt), and replace only destination.
 */
internal data class StaticGenerationOutput(
    val source: StaticGenerationProvenance,
    val destination: StaticGenerationDestination,
) {
    init { require(source.toolName == destination.toolName) { "Static tool provenance mismatch" } }

    /** Versioned length-delimited UTF-8 slot encoding. Tool names and media metadata are checked
     * by full record equality, not allowed to allocate a second slot after a rename/corruption.
     */
    val identity: String = "genstatic-v1-" + staticDigest(ByteArrayOutputStream().also { buffer ->
        DataOutputStream(buffer).use { out ->
            fun field(value: String) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                out.writeInt(bytes.size); out.write(bytes)
            }
            field("generation-static-slot-v1")
            destination.fields().dropLast(1).forEach(::field)
            field(source.accountScope); field(source.operationId); field(source.itemId)
            out.writeInt(source.outputIndex)
        }
    }.toByteArray())

    val historicalOnly: Boolean get() = true
    override fun toString() = "StaticGenerationOutput(kind=${source.kind}, historicalOnly=true, payload=<redacted>)"
}

internal val STATIC_HASH = Regex("[0-9a-f]{64}")
internal fun staticDigest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
private fun staticScope(value: String) { require(value.matches(Regex("[A-Za-z0-9_.:-]{1,200}"))) }
private fun staticId(value: String) {
    require(value.isNotBlank() && value.length <= 200 && value !in setOf(".", "..") &&
        value.none { it == '/' || it == '\\' || it.isISOControl() || it.isSurrogate() })
}
