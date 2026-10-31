package app.daiyu.android.voice.providers

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

// Dedicated Doubao speech requests and response decoding.
// This is the dedicated Doubao protocol, NOT an OpenAI /audio/speech compatibility shim.
object DoubaoSpeech {
    const val BASE_URL = "https://openspeech.bytedance.com"
    val catalogModels = listOf(
        "seed-tts-2.0" to "豆包语音合成 2.0",
        "seed-audio-1.0" to "豆包音频生成 1.0",
    )
    const val CREATE_URL = "https://openspeech.bytedance.com/api/v3/tts/create"
    const val UNIDIRECTIONAL_URL = "https://openspeech.bytedance.com/api/v3/tts/unidirectional"

    fun matchesModel(modelId: String): Boolean = modelId.lowercase().let { "seed-audio" in it || "seed-tts" in it }
    fun isOpenspeech(baseUrl: String): Boolean = VoiceUrls.host(baseUrl) == "openspeech.bytedance.com"
    fun matchesEndpoint(baseUrl: String): Boolean = VoiceUrls.host(baseUrl).let {
        isOpenspeech(baseUrl) || it == "volces.com" || it.endsWith(".volces.com") ||
            it == "volcengine.com" || it.endsWith(".volcengine.com")
    }
    fun usesCreate(modelId: String): Boolean = "seed-audio" in modelId.lowercase()
    fun resourceId(modelId: String): String = when {
        "seed-tts-1.0-concurr" in modelId.lowercase() -> "seed-tts-1.0-concurr"
        "seed-tts-1.0" in modelId.lowercase() -> "seed-tts-1.0"
        else -> "seed-tts-2.0"
    }
    fun requestId(): String = UUID.randomUUID().toString()

    fun validateBaseUrl(baseUrl: String) {
        val uri = VoiceUrls.validate(baseUrl, "https")
        speechCheck(uri.host.equals("openspeech.bytedance.com", true) && uri.port in setOf(-1, 443) &&
            uri.rawPath.orEmpty() in setOf("", "/")) { "请使用豆包语音官方服务地址" }
    }

    fun request(config: SpeechProviderConfig, text: String, speaker: String, personal: DoubaoPersonalVoice? = null): VoiceHttpRequest {
        speechCheck(config.engine == SpeechEngine.DOUBAO && config.apiKey.isNotBlank()) { "请配置豆包语音 API Key" }
        val isPersonal = DoubaoVoiceIdentity.isPersonal(speaker)
        speechCheck(DoubaoVoiceIdentity.isSupportedSpeaker(speaker)) { "音色 ID 格式不受支持" }
        speechCheck(personal == null || (isPersonal && personal.id == speaker)) { "个人音色身份不匹配" }
        val create = usesCreate(config.model)
        speechCheck(!isPersonal || !create) { "个人音色需要选择 seed-tts 在线合成模型" }
        val auth = mutableListOf("Accept" to "application/json", "X-Api-Key" to config.apiKey, "X-Api-Request-Id" to requestId())
        if (!create) auth += "X-Api-Resource-Id" to if (isPersonal) "seed-icl-2.0" else resourceId(config.model)
        val request = VoiceHttpRequest(if (create) CREATE_URL else UNIDIRECTIONAL_URL, headers(config, *auth.toTypedArray()),
            if (create) createBody(config.model, text, speaker) else unidirectionalBody(text, speaker))
        if (isPersonal) {
            // Custom headers are case-insensitive overrides: authorize the account actually sent.
            val apiKey = request.header("X-Api-Key").orEmpty()
            speechCheck(apiKey.isNotBlank() && personal != null && personal.id == speaker && personal.availableFor(apiKey)) {
                "个人音色不可用，请检查训练状态、账户和首次使用确认"
            }
            speechCheck(request.header("X-Api-Resource-Id") == "seed-icl-2.0") { "个人音色需要使用 seed-icl-2.0 资源" }
        }
        return request
    }

    fun createBody(model: String, text: String, speaker: String): String = JSONObject()
        .put("model", model.ifBlank { "seed-audio-1.0" })
        .put("text_prompt", text)
        .put("references", JSONArray().put(JSONObject().put("speaker", speaker)))
        .put("audio_config", JSONObject().put("format", "mp3").put("sample_rate", 24000)).toString()

    fun unidirectionalBody(text: String, speaker: String): String = JSONObject()
        .put("user", JSONObject().put("uid", "daiyu"))
        .put("req_params", JSONObject().put("text", text).put("speaker", speaker)
            .put("audio_params", JSONObject().put("format", "mp3").put("sample_rate", 24000))).toString()

    class AudioPayload(val bytes: ByteArray, val extension: String)
    fun decodeAudio(bytes: ByteArray): AudioPayload = when {
        looksLikeMp3(stripId3(bytes)) || looksLikeMp3(bytes) -> AudioPayload(bytes, "mp3")
        looksLikeWav(bytes) -> AudioPayload(bytes, "wav")
        looksLikeOgg(bytes) -> AudioPayload(bytes, "ogg")
        else -> AudioPayload(pcmToWav(bytes), "wav")
    }

    internal fun looksLikeMp3(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        val id3 = bytes.size >= 10 && bytes[0] == 73.toByte() && bytes[1] == 68.toByte() && bytes[2] == 51.toByte()
        val frame = (bytes[0].toInt() and 0xff) == 0xff && (bytes[1].toInt() and 0xe0) == 0xe0 && (bytes[1].toInt() and 0x06) != 0
        return id3 || frame
    }
    internal fun looksLikeWav(bytes: ByteArray): Boolean = bytes.size >= 12 &&
        bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" && bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WAVE"
    private fun looksLikeOgg(bytes: ByteArray): Boolean = bytes.size >= 4 && bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "OggS"
    private fun stripId3(bytes: ByteArray): ByteArray {
        if (bytes.size < 10 || bytes[0] != 73.toByte() || bytes[1] != 68.toByte() || bytes[2] != 51.toByte()) return bytes
        val size = ((bytes[6].toInt() and 0x7f) shl 21) or ((bytes[7].toInt() and 0x7f) shl 14) or
            ((bytes[8].toInt() and 0x7f) shl 7) or (bytes[9].toInt() and 0x7f)
        val start = 10 + size
        return if (start in 1 until bytes.size) bytes.copyOfRange(start, bytes.size) else bytes
    }

    fun pcmToWav(pcm: ByteArray, sampleRate: Int = 24000, channels: Int = 1, bits: Int = 16): ByteArray {
        val out = ByteArrayOutputStream(44 + pcm.size)
        fun string(value: String) = out.write(value.toByteArray(Charsets.US_ASCII))
        fun int(value: Int) { out.write(value and 255); out.write(value shr 8 and 255); out.write(value shr 16 and 255); out.write(value shr 24 and 255) }
        fun short(value: Int) { out.write(value and 255); out.write(value shr 8 and 255) }
        string("RIFF"); int(36 + pcm.size); string("WAVE"); string("fmt "); int(16); short(1); short(channels)
        int(sampleRate); int(sampleRate * channels * bits / 8); short(channels * bits / 8); short(bits)
        string("data"); int(pcm.size); out.write(pcm)
        return out.toByteArray()
    }

    /** Handles raw MP3, create JSON, adjacent JSON objects and unidirectional data: events. */
    fun decodeResponse(contentType: String?, bytes: ByteArray): ByteArray {
        speechCheck(bytes.size <= SpeechSynthesisClient.MAX_AUDIO_BYTES) { "朗读音频超过大小限制" }
        if (looksLikeMp3(bytes)) return bytes
        val type = contentType.orEmpty().substringBefore(';').trim().lowercase()
        speechCheck(type in setOf("", "application/json", "text/plain", "text/event-stream", "application/octet-stream")) { "朗读接口未返回音频或 JSON" }
        val text = bytes.toString(Charsets.UTF_8)
        speechCheck(text.isNotBlank()) { "朗读接口返回了空响应" }
        val payloads = extractJsonPayloads(text, type == "text/event-stream")
        speechCheck(payloads.isNotEmpty()) { "朗读接口返回了无效音频" }
        val out = ByteArrayOutputStream()
        payloads.forEach { json ->
            val code = json.opt("code")
            speechCheck(code !is Number || code.toInt() in setOf(0, 20000000, 3000)) { "朗读接口返回了错误状态" }
            val audio = json.optString("audio").ifBlank { json.optString("data") }
            if (audio.isNotBlank()) {
                val decoded = Base64.getDecoder().decode(audio.filterNot { it.isWhitespace() })
                speechCheck(out.size() + decoded.size <= SpeechSynthesisClient.MAX_AUDIO_BYTES) { "朗读音频超过大小限制" }
                out.write(decoded)
            }
        }
        speechCheck(out.size() > 0) { "朗读接口没有返回音频数据" }
        return decodeAudio(out.toByteArray()).bytes
    }

    internal fun extractJsonPayloads(text: String, eventStream: Boolean = false): List<JSONObject> {
        val firstLine = text.trimStart().lineSequence().firstOrNull().orEmpty()
        val isSse = eventStream || firstLine.startsWith(":") ||
            firstLine.substringBefore(':') in setOf("data", "event", "id", "retry")
        val payloads = if (isSse) SpeechProtocols.sseData(text) else listOf(text)
        val out = ArrayList<JSONObject>()
        for (payload in payloads) {
            if (isSse && payload.trim() == "[DONE]") continue
            // JSONTokener implementations can treat a literal NUL as end-of-input.
            speechCheck('\u0000' !in payload) { "朗读接口返回了无效 JSON" }
            val tokener = JSONTokener(payload)
            try {
                while (tokener.more()) {
                    val next = tokener.next()
                    if (next in " \t\r\n") continue
                    speechCheck(next == '{') { "朗读接口返回了无效 JSON" }
                    tokener.back()
                    out += tokener.nextValue() as? JSONObject ?: throw VoiceProviderException("朗读接口返回了无效 JSON")
                }
            } catch (_: org.json.JSONException) {
                // Never return already-read audio when any later object is malformed/truncated.
                throw VoiceProviderException("朗读接口返回了无效 JSON")
            }
        }
        return out
    }
}

/** Pure retained selection semantics from old doubao/PersonalVoices; no catalog or key storage. */
class DoubaoPersonalVoice(val id: String, val account: String, val status: Int, val models: Set<Int>, val accepted: Boolean) {
    fun availableFor(apiKey: String): Boolean = DoubaoVoiceIdentity.isPersonal(id) && accepted && (status == 2 || status == 4) &&
        models.any { it == 4 || it == 5 } && account == accountFingerprint(apiKey)
    companion object {
        fun accountFingerprint(key: String): String = MessageDigest.getInstance("SHA-256")
            .digest(key.trim().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
