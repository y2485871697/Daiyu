package app.daiyu.android.model.generation

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.MessageDigest

/** A fully validated, frozen, one-item POST. No protocol/endpoint selection occurs during I/O. */
class GenerationRequestPlan private constructor(
    val identity: GenerationIdentity,
    val protocol: GenerationProtocol,
    val endpoint: String,
    val kind: GenerationKind,
    internal val authorization: String?,
    internal val contentType: String,
    body: ByteArray,
) {
    private val snapshot = body.copyOf()
    internal val body: ByteArray get() = snapshot.copyOf()
    val fingerprint: String = MessageDigest.getInstance("SHA-256").run {
        update(protocol.name.toByteArray(Charsets.UTF_8))
        update(0.toByte())
        update(endpoint.toByteArray(Charsets.UTF_8))
        update(0.toByte())
        update(contentType.toByteArray(Charsets.UTF_8))
        update(0.toByte())
        // Bind the account too, without storing the credential in the ledger.
        update(authorization.orEmpty().toByteArray(Charsets.UTF_8))
        update(0.toByte())
        digest(snapshot).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    internal val attempt get() = GenerationAttempt(identity, fingerprint, protocol)
    override fun toString(): String = "GenerationRequestPlan(protocol=$protocol, payload=<redacted>)"

    companion object {
        const val MAX_INPUT_BYTES = 20 * 1024 * 1024
        const val MAX_IMAGE_BYTES = 20 * 1024 * 1024
        const val MAX_VIDEO_BYTES = 100 * 1024 * 1024
        internal const val MAX_JSON_BYTES = 30 * 1024 * 1024
        internal const val MAX_BATCH = 32

        /**
         * Small whitelist sourced from the retained client request shapes. All optional parameters
         * (seed, size, quality, duration, count/n, concurrency, extra JSON etc.) are rejected. Batch
         * count is represented only by separate parent-assigned item identities, with n=1 per image.
         */
        fun prepare(request: GenerationRequest, assets: GenerationAssets): GenerationRequestPlan {
            try {
                request.identity.validate()
                require(request.model.isNotBlank() && request.model.length <= 512)
                require(request.prompt.isNotBlank() && request.prompt.length <= 100_000)
                require(request.apiKey.length <= 8192 && request.apiKey.all { it.code in 32..126 })
                val uri = checkedHttps(request.endpoint)
                val suffix = when (request.protocol) {
                    GenerationProtocol.OPENAI_IMAGES_JSON -> "/images/generations"
                    GenerationProtocol.OPENAI_IMAGE_EDIT_MULTIPART -> "/images/edits"
                    GenerationProtocol.OPENAI_VIDEO_MULTIPART -> "/videos"
                }
                require(uri.rawPath.endsWith(suffix) && '%' !in uri.rawPath && "//" !in uri.rawPath)
                require(uri.normalize() == uri)
            } catch (_: Exception) {
                throw GenerationPreflightException(GenerationFailure.INVALID_REQUEST)
            }
            if (request.parameters.isNotEmpty()) fail(GenerationFailure.UNSUPPORTED_PARAMETER)
            val refs = request.references
            when (request.protocol) {
                GenerationProtocol.OPENAI_IMAGES_JSON -> if (refs.isNotEmpty() || request.mask != null)
                    fail(GenerationFailure.UNSUPPORTED_REFERENCE)
                GenerationProtocol.OPENAI_IMAGE_EDIT_MULTIPART -> if (refs.size != 1)
                    fail(GenerationFailure.UNSUPPORTED_REFERENCE)
                GenerationProtocol.OPENAI_VIDEO_MULTIPART -> if (refs.size > 1 || request.mask != null)
                    fail(GenerationFailure.UNSUPPORTED_REFERENCE)
            }
            val inputs = refs.map { it to validateInput(it, assets) }
            val mask = request.mask?.let { it to validateInput(it, assets) }
            if (request.protocol == GenerationProtocol.OPENAI_IMAGE_EDIT_MULTIPART) {
                // Restrict edits to one PNG. Never silently convert, resize or drop the mask.
                if (inputs.single().second.mimeType != "image/png") fail(GenerationFailure.INVALID_MEDIA)
                mask?.let {
                    val ref = inputs.single().second
                    if (it.second.mimeType != "image/png" || ref.width != it.second.width || ref.height != it.second.height)
                        fail(GenerationFailure.INVALID_MEDIA)
                }
            }
            val kind = if (request.protocol == GenerationProtocol.OPENAI_VIDEO_MULTIPART)
                GenerationKind.VIDEO else GenerationKind.IMAGE
            val body: ByteArray
            val contentType: String
            if (request.protocol == GenerationProtocol.OPENAI_IMAGES_JSON) {
                contentType = "application/json; charset=utf-8"
                body = JSONObject().put("model", request.model).put("prompt", request.prompt)
                    .put("n", 1).toString().toByteArray(Charsets.UTF_8)
            } else {
                // Deterministic boundary: identical input re-preparation has the same fingerprint.
                // Reject collisions instead of regenerating a different request under one identity.
                val boundary = "daiyu-generation-" + sha256(request.identity.operationId + ":" + request.identity.itemId).take(32)
                val parts = mutableListOf(
                    Part("model", request.model.toByteArray(Charsets.UTF_8)),
                    Part("prompt", request.prompt.toByteArray(Charsets.UTF_8)),
                )
                if (kind == GenerationKind.IMAGE) parts += Part("n", "1".toByteArray())
                inputs.forEach { (input, info) ->
                    val name = if (kind == GenerationKind.VIDEO) "input_reference" else "image"
                    parts += Part(name, input.bytes, "reference.${extension(info.mimeType)}", info.mimeType)
                }
                mask?.let { parts += Part("mask", it.first.bytes, "mask.png", "image/png") }
                val marker = boundary.toByteArray(Charsets.US_ASCII)
                if (parts.any { contains(it.bytes, marker) }) fail(GenerationFailure.INVALID_REQUEST)
                body = multipart(boundary, parts)
                contentType = "multipart/form-data; boundary=$boundary"
            }
            return GenerationRequestPlan(request.identity, request.protocol, request.endpoint, kind,
                request.apiKey.takeIf { it.isNotEmpty() }?.let { "Bearer $it" }, contentType, body)
        }

        internal fun checkedHttps(value: String): URI {
            val uri = URI(value)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank())
            require(uri.rawUserInfo == null && uri.rawFragment == null && uri.rawQuery == null)
            require(uri.port == -1 || uri.port in 1..65535)
            return uri
        }

        internal fun checkedDownload(value: String): URI {
            val uri = URI(value)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank())
            require(uri.rawUserInfo == null && uri.rawFragment == null)
            require(uri.port == -1 || uri.port in 1..65535)
            return uri
        }

        private fun validateInput(input: GenerationInput, assets: GenerationAssets): GenerationMediaInfo {
            try {
                val bytes = input.bytes
                require(bytes.isNotEmpty() && bytes.size <= MAX_INPUT_BYTES)
                require(input.mimeType in setOf("image/png", "image/jpeg", "image/webp"))
                val info = assets.inspect(GenerationKind.IMAGE, bytes, input.mimeType)
                require(info.mimeType == input.mimeType && info.width > 0 && info.height > 0)
                return info
            } catch (_: Exception) { fail(GenerationFailure.INVALID_MEDIA) }
        }

        private data class Part(val name: String, val bytes: ByteArray, val filename: String? = null, val mime: String? = null)
        private fun multipart(boundary: String, parts: List<Part>): ByteArray = ByteArrayOutputStream().use { out ->
            fun text(value: String) { out.write(value.toByteArray(Charsets.UTF_8)) }
            parts.forEach {
                text("--$boundary\r\nContent-Disposition: form-data; name=\"${it.name}\"")
                it.filename?.let { filename -> text("; filename=\"$filename\"") }
                text("\r\n")
                it.mime?.let { mime -> text("Content-Type: $mime\r\n") }
                text("\r\n")
                out.write(it.bytes)
                text("\r\n")
            }
            text("--$boundary--\r\n")
            out.toByteArray()
        }
        private fun extension(mime: String): String = when (mime) {
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            else -> error("unreachable")
        }
        private fun contains(bytes: ByteArray, needle: ByteArray): Boolean =
            (0..bytes.size - needle.size).any { start -> needle.indices.all { bytes[start + it] == needle[it] } }
        private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun fail(failure: GenerationFailure): Nothing = throw GenerationPreflightException(failure)
    }
}
