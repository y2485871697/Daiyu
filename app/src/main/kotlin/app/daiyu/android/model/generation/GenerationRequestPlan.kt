package app.daiyu.android.model.generation

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.Base64

/** One validated, frozen paid POST; no protocol-changing retries during I/O. */
class GenerationRequestPlan private constructor(
    val identity: GenerationIdentity,
    val protocol: GenerationProtocol,
    val endpoint: String,
    val kind: GenerationKind,
    internal val requestHeaders: Map<String, String>,
    internal val contentType: String,
    body: ByteArray,
    internal val imageOptions: ImageGenerationOptions? = null,
    internal val expectedSize: String? = null,
    internal val concurrency: Int = 1,
) {
    private val snapshot = body.copyOf()
    internal val body: ByteArray get() = snapshot.copyOf()
    internal val authorization: String? get() = requestHeaders.entries.firstOrNull { it.key.equals("Authorization", true) }?.value
    internal val responseLimit: Int get() = when {
        protocol == GenerationProtocol.NOVELAI_IMAGE_JSON -> NovelAiImageProtocol.MAX_RESPONSE_BYTES
        kind == GenerationKind.VIDEO -> MAX_VIDEO_BYTES / 3 * 4 + 1024 * 1024
        else -> MAX_JSON_BYTES
    }
    val fingerprint: String = MessageDigest.getInstance("SHA-256").run {
        listOf(protocol.name, endpoint, contentType).forEach { update(it.toByteArray()); update(0.toByte()) }
        requestHeaders.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (name, value) ->
            update(name.lowercase().toByteArray()); update(0.toByte()); update(value.toByteArray()); update(0.toByte())
        }
        digest(snapshot).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    internal val attempt get() = GenerationAttempt(identity, fingerprint, protocol)
    override fun toString() = "GenerationRequestPlan(protocol=$protocol, payload=<redacted>)"

    private fun videoBase(): String = endpoint.removeSuffix(protocol.suffix)
    internal fun pollUrls(taskId: String): List<String> {
        require(GenerationIo.validTaskId(taskId))
        val base = videoBase()
        return (listOfNotNull(if (protocol == GenerationProtocol.ARK_CONTENTS_JSON) "$endpoint/$taskId" else null) +
            listOf("$base/videos/$taskId", "$base/video/generations/$taskId")).distinct()
    }
    internal fun taskUrls(taskId: String): Pair<String, String> =
        pollUrls(taskId).first() to "${videoBase()}/videos/$taskId/content"

    companion object {
        const val MAX_INPUT_BYTES = 20 * 1024 * 1024
        const val MAX_IMAGE_BYTES = 20 * 1024 * 1024
        const val MAX_VIDEO_BYTES = 96 * 1024 * 1024
        internal const val MAX_JSON_BYTES = 30 * 1024 * 1024
        internal const val MAX_BATCH = 32
        internal val imageParameterKeys = setOf("aspect_ratio", "resolution", "size", "n", "quality", "response_format", "concurrency")

        fun prepare(request: GenerationRequest, assets: GenerationAssets): GenerationRequestPlan {
            try {
                request.identity.validate()
                require(request.model.isNotBlank() && request.model.length <= 512)
                require(request.prompt.isNotBlank() && request.prompt.length <= 100_000)
                require(request.apiKey.length <= 8192 && request.apiKey.all { it.code in 32..126 })
                val uri = checkedHttps(request.endpoint)
                require(uri.rawPath.endsWith(request.protocol.suffix) && '%' !in uri.rawPath && "//" !in uri.rawPath && uri.normalize() == uri)
            } catch (_: Exception) { fail(GenerationFailure.INVALID_REQUEST) }
            val headers = try { validateHeaders(request.headers) } catch (_: Exception) { fail(GenerationFailure.INVALID_REQUEST) }
            val inputs = request.references.map { it to validateInput(it, assets) }
            val mask = request.mask?.let { it to validateInput(it, assets) }
            if (mask != null) {
                if (request.protocol.isVideo || inputs.size != 1 || mask.second.mimeType != "image/png" ||
                    inputs.single().second.width != mask.second.width || inputs.single().second.height != mask.second.height)
                    fail(GenerationFailure.INVALID_MEDIA)
            }
            val extras = try {
                require(request.requestBodyJson.length <= 262144 && request.parameters.size <= 128)
                (if (request.requestBodyJson.isBlank()) JSONObject() else JSONObject(request.requestBodyJson)).also { body ->
                    request.parameters.forEach { (name, value) ->
                        require(name.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")) && value.length <= 65536)
                        body.put(name, parameterValue(value))
                    }
                }
            } catch (_: Exception) { fail(GenerationFailure.UNSUPPORTED_PARAMETER) }
            val reasoning = MediaReasoningSettings.resolve(extras, if (request.protocol.isVideo) "video_generation" else "image_generation")
            if (reasoning.status == MediaReasoningSettings.Status.INVALID) fail(GenerationFailure.UNSUPPORTED_PARAMETER)
            if (request.protocol.isVideo && reasoning.transport != null && reasoning.transport != when (request.protocol) {
                GenerationProtocol.OPENAI_VIDEO_MULTIPART -> "videos_multipart"
                GenerationProtocol.OPENAI_VIDEO_JSON -> "videos_json"
                GenerationProtocol.OPENAI_VIDEOS_GENERATIONS_JSON -> "videos_generations"
                GenerationProtocol.OPENAI_VIDEO_GENERATIONS_JSON -> "video_generations"
                GenerationProtocol.ARK_CONTENTS_JSON -> "ark_contents"
                else -> "chat_completions"
            }) fail(GenerationFailure.UNSUPPORTED_PARAMETER)
            val resolvedReasoning = try { MediaReasoningSettings.apply(extras, if (request.protocol.isVideo) "video_generation" else "image_generation", null) }
                catch (_: Exception) { fail(GenerationFailure.UNSUPPORTED_PARAMETER) }
            extras.keys().asSequence().toList().forEach(extras::remove)
            resolvedReasoning.keys().forEach { extras.put(it, resolvedReasoning.get(it)) }
            listOf("tools", "tool_choice", "stream", "messages").forEach(extras::remove)
            extras.put("model", request.model).put("prompt", request.prompt)
            var target = request.endpoint
            var effectiveProtocol = request.protocol
            var imageOptions: ImageGenerationOptions? = null
            var expectedSize: String? = null
            val parts = mutableListOf<Part>()
            val body: ByteArray
            val contentType: String
            try {
                if (!request.protocol.isVideo) {
                    val explicitJson = JSONObject()
                    request.parameters.filterKeys { it in imageParameterKeys }.forEach { (name, value) -> explicitJson.put(name, parameterValue(value)) }
                    val explicit = ImageGenerationOptions.fromJson(explicitJson)
                    val parsed = ImagePromptOptions.parse(request.prompt, explicit)
                    require(parsed.prompt.isNotBlank())
                    extras.put("prompt", parsed.prompt)
                    val forced = when (request.protocol) {
                        GenerationProtocol.OPENAI_IMAGE_EDIT_MULTIPART -> "multipart"
                        GenerationProtocol.OPENAI_IMAGE_EDIT_JSON -> "json_image_url"
                        GenerationProtocol.OPENAI_IMAGE_REFERENCE_JSON -> "generations_image"
                        else -> null
                    }
                    if (forced != null || request.protocol == GenerationProtocol.NOVELAI_IMAGE_JSON) {
                        val config = if (extras.has(ImageRequestParameters.CONFIG_KEY))
                            extras.getJSONObject(ImageRequestParameters.CONFIG_KEY) else JSONObject()
                        if (forced != null) {
                            require(!config.has("edit_protocol") || config.getString("edit_protocol") == forced)
                            config.put("edit_protocol", forced)
                        }
                        if (request.protocol == GenerationProtocol.NOVELAI_IMAGE_JSON) {
                            require(!config.has("endpoint") || config.getString("endpoint") == "novelai_native")
                            config.put("endpoint", "novelai_native")
                        }
                        extras.put(ImageRequestParameters.CONFIG_KEY, config)
                    }
                    if (forced != null && inputs.isEmpty()) fail(GenerationFailure.UNSUPPORTED_REFERENCE)
                    val prepared = if (GrokImageProfile.applies(target, request.model, extras))
                        GrokImageProfile.prepare(extras, parsed.options, explicit, inputs.size, mask != null)
                    else ImageRequestParameters.prepare(extras, parsed.options, explicit, defaultCount = 1)
                    val plan = ImageEndpointPlan.create(prepared, inputs.size, mask != null)
                    effectiveProtocol = when (plan.kind) {
                        ImageEndpointPlan.Kind.GENERATIONS -> GenerationProtocol.OPENAI_IMAGES_JSON
                        ImageEndpointPlan.Kind.EDITS_MULTIPART -> GenerationProtocol.OPENAI_IMAGE_EDIT_MULTIPART
                        ImageEndpointPlan.Kind.EDITS_JSON -> GenerationProtocol.OPENAI_IMAGE_EDIT_JSON
                        ImageEndpointPlan.Kind.GENERATIONS_IMAGE -> GenerationProtocol.OPENAI_IMAGE_REFERENCE_JSON
                        ImageEndpointPlan.Kind.NOVELAI -> {
                            require(request.protocol == GenerationProtocol.NOVELAI_IMAGE_JSON) {
                                "NovelAI原生契约需选择NovelAI生成协议和原生地址"
                            }
                            GenerationProtocol.NOVELAI_IMAGE_JSON
                        }
                    }
                    imageOptions = plan.options
                    expectedSize = plan.expectedSize
                    target = plan.url(target)
                    val json = plan.body
                    when (plan.kind) {
                        ImageEndpointPlan.Kind.EDITS_MULTIPART -> {
                            json.keys().forEach { key -> parts += Part(key, json.get(key).toString().toByteArray()) }
                            inputs.forEachIndexed { index, (input, info) -> parts += Part("image", input.bytes,
                                "image$index.${ImageGenerationParser.extensionForMime(info.mimeType)}", info.mimeType) }
                            mask?.let { parts += Part("mask", it.first.bytes, "mask.png", "image/png") }
                        }
                        ImageEndpointPlan.Kind.EDITS_JSON -> json.put("image", JSONObject().put("type", "image_url").put("url", dataUrl(inputs.single().first)))
                        ImageEndpointPlan.Kind.GENERATIONS_IMAGE -> {
                            json.put("image", dataUrl(inputs.single().first))
                            mask?.let { json.put("mask", dataUrl(it.first)) }
                        }
                        else -> Unit
                    }
                    if (parts.isEmpty()) {
                        contentType = "application/json; charset=utf-8"
                        body = json.toString().toByteArray()
                    } else {
                        val boundary = boundary(request.identity)
                        body = multipart(boundary, parts)
                        contentType = "multipart/form-data; boundary=$boundary"
                    }
                } else {
                    if (mask != null) fail(GenerationFailure.UNSUPPORTED_REFERENCE)
                    if (request.protocol !in setOf(GenerationProtocol.ARK_CONTENTS_JSON, GenerationProtocol.VIDEO_CHAT_JSON) && inputs.size > 1)
                        fail(GenerationFailure.UNSUPPORTED_REFERENCE)
                    extras.remove(ImageRequestParameters.CONFIG_KEY)
                    listOf("image", "images", "image_url", "input_reference", "content").forEach { key ->
                        if (extras.has(key)) fail(GenerationFailure.UNSUPPORTED_REFERENCE)
                    }
                    when (request.protocol) {
                        GenerationProtocol.OPENAI_VIDEO_MULTIPART -> {
                            extras.remove("n")
                            extras.keys().forEach { key -> parts += Part(key, extras.get(key).toString().toByteArray()) }
                            inputs.forEach { (input, info) -> parts += Part("input_reference", input.bytes,
                                "image.${ImageGenerationParser.extensionForMime(info.mimeType)}", info.mimeType) }
                        }
                        GenerationProtocol.ARK_CONTENTS_JSON -> {
                            val content = JSONArray().put(JSONObject().put("type", "text").put("text", request.prompt))
                            inputs.forEach { (input, _) -> content.put(JSONObject().put("type", "image_url")
                                .put("image_url", JSONObject().put("url", dataUrl(input)))) }
                            extras.remove("prompt"); extras.remove("n"); extras.put("content", content)
                        }
                        GenerationProtocol.VIDEO_CHAT_JSON -> {
                            val content: Any = if (inputs.isEmpty()) request.prompt else JSONArray().put(JSONObject().put("type", "text").put("text", request.prompt)).also { array ->
                                inputs.forEach { (input, _) -> array.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", dataUrl(input)))) }
                            }
                            extras.remove("prompt"); extras.put("stream", false)
                            extras.put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
                        }
                        else -> {
                            if (!extras.has("n")) extras.put("n", 1)
                            inputs.firstOrNull()?.let { extras.put("image", dataUrl(it.first)).put("image_url", dataUrl(it.first)) }
                        }
                    }
                    if (parts.isEmpty()) {
                        body = extras.toString().toByteArray(); contentType = "application/json; charset=utf-8"
                    } else {
                        val boundary = boundary(request.identity)
                        body = multipart(boundary, parts); contentType = "multipart/form-data; boundary=$boundary"
                    }
                }
            } catch (failure: GenerationPreflightException) { throw failure }
            catch (_: Exception) { fail(GenerationFailure.UNSUPPORTED_PARAMETER) }
            return GenerationRequestPlan(request.identity, effectiveProtocol, target, effectiveProtocol.kind,
                buildMap {
                    put("Accept", if (effectiveProtocol == GenerationProtocol.NOVELAI_IMAGE_JSON) "application/zip, image/*" else "*/*")
                    if (request.apiKey.isNotEmpty()) put("Authorization", "Bearer ${request.apiKey}")
                    headers.forEach { (name, value) ->
                        keys.firstOrNull { it.equals(name, true) }?.let(::remove)
                        put(name, value)
                    }
                }, contentType, body, imageOptions, expectedSize, imageOptions?.concurrency ?: 1)
        }

        internal fun validateHeaders(headers: Map<String, String>): Map<String, String> {
            require(headers.size <= 64 && headers.keys.map { it.lowercase() }.distinct().size == headers.size)
            require(headers.all { (name, value) -> name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}")) &&
                name.lowercase() !in setOf("host", "cookie", "content-length", "content-type", "connection", "transfer-encoding") &&
                value.length <= 8192 && value.all { it.code in 32..126 } })
            return headers.toMap()
        }
        internal fun parameterValue(value: String): Any = when {
            value == "true" -> true
            value == "false" -> false
            value.matches(Regex("-?[0-9]+")) -> value.toLongOrNull() ?: value
            value.matches(Regex("-?[0-9]+\\.[0-9]+")) -> value.toDoubleOrNull() ?: value
            value.startsWith('{') -> JSONObject(value)
            value.startsWith('[') -> JSONArray(value)
            else -> value
        }
        internal fun checkedHttps(value: String): URI = checkedDownload(value).also { require(it.rawQuery == null) }
        internal fun checkedDownload(value: String): URI = URI(value).also {
            require(it.scheme == "https" && !it.host.isNullOrBlank() && it.rawUserInfo == null && it.rawFragment == null)
            require(it.port == -1 || it.port in 1..65535)
        }
        private fun validateInput(input: GenerationInput, assets: GenerationAssets): GenerationMediaInfo = try {
            val bytes = input.bytes
            require(bytes.isNotEmpty() && bytes.size <= MAX_INPUT_BYTES && input.mimeType in setOf("image/png", "image/jpeg", "image/webp"))
            assets.inspect(GenerationKind.IMAGE, bytes, input.mimeType).also {
                require(it.mimeType == input.mimeType && it.width > 0 && it.height > 0)
            }
        } catch (_: Exception) { fail(GenerationFailure.INVALID_MEDIA) }
        private fun dataUrl(input: GenerationInput) = "data:${input.mimeType};base64," + Base64.getEncoder().encodeToString(input.bytes)
        private fun boundary(identity: GenerationIdentity) = "daiyu-generation-" + MessageDigest.getInstance("SHA-256")
            .digest((identity.operationId + ":" + identity.itemId).toByteArray()).take(16).joinToString("") { "%02x".format(it) }
        private data class Part(val name: String, val bytes: ByteArray, val filename: String? = null, val mime: String? = null)
        private fun multipart(boundary: String, parts: List<Part>): ByteArray = ByteArrayOutputStream().use { out ->
            require(parts.all { it.name.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")) })
            val marker = boundary.toByteArray()
            require(parts.none { part -> (0..part.bytes.size - marker.size).any { start -> marker.indices.all { part.bytes[start + it] == marker[it] } } })
            fun text(value: String) { out.write(value.toByteArray()) }
            parts.forEach {
                text("--$boundary\r\nContent-Disposition: form-data; name=\"${it.name}\"")
                it.filename?.let { name -> text("; filename=\"$name\"") }
                text("\r\n"); it.mime?.let { mime -> text("Content-Type: $mime\r\n") }; text("\r\n")
                out.write(it.bytes); text("\r\n")
            }
            text("--$boundary--\r\n"); out.toByteArray()
        }
        private fun fail(failure: GenerationFailure): Nothing = throw GenerationPreflightException(failure)
    }
}
