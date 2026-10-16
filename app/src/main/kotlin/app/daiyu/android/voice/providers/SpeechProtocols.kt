package app.daiyu.android.voice.providers

import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

// Retained/adapted supplier builders and decoders from old f001fb82 tts/SpeechProtocols.kt.
// Only configuration/HTTP representation and bounded decoding have been replaced; see migration notice.
object SpeechProtocols {
    fun request(config: SpeechProviderConfig, text: String, voice: String): VoiceHttpRequest = when (config.engine) {
        SpeechEngine.OPENAI, SpeechEngine.GROQ, SpeechEngine.COSYVOICE, SpeechEngine.MOSS -> openAi(config, text, voice, config.engine == SpeechEngine.GROQ)
        SpeechEngine.STEP -> step(config, text, voice)
        SpeechEngine.MIMO -> mimo(config, text, voice)
        SpeechEngine.MINIMAX -> minimax(config, text, voice)
        SpeechEngine.QWEN -> qwen(config, text, voice)
        SpeechEngine.XAI -> xai(config, text, voice)
        SpeechEngine.GEMINI -> gemini(config, text, voice)
        SpeechEngine.ELEVENLABS -> elevenLabs(config, text, voice)
        SpeechEngine.FISH -> fish(config, text, voice)
        SpeechEngine.DOUBAO -> throw VoiceProviderException("豆包使用专用语音协议")
    }

    fun decode(engine: SpeechEngine, contentType: String?, bytes: ByteArray): ByteArray {
        speechCheck(bytes.size <= SpeechSynthesisClient.MAX_AUDIO_BYTES) { "朗读音频超过大小限制" }
        speechCheck(bytes.isNotEmpty()) { "朗读接口返回了空响应" }
        return when (engine) {
            SpeechEngine.MIMO -> DoubaoSpeech.pcmToWav(decodeMimoSse(bytes.toString(Charsets.UTF_8)))
            SpeechEngine.MINIMAX -> decodeMiniMaxSse(bytes.toString(Charsets.UTF_8))
            SpeechEngine.QWEN -> decodeQwenSse(bytes.toString(Charsets.UTF_8))
            SpeechEngine.GEMINI -> DoubaoSpeech.pcmToWav(decodeGemini(bytes.toString(Charsets.UTF_8)))
            SpeechEngine.DOUBAO -> DoubaoSpeech.decodeResponse(contentType, bytes)
            else -> {
                // Preserve encoded WAV/MP3/Ogg and the old raw PCM fallback. The old executor
                // validated raw OpenAI-family audio; extend HTML/JSON rejection to other binary APIs.
                val type = contentType.orEmpty().substringBefore(';').trim().lowercase()
                speechCheck(type.startsWith("audio/") || type == "application/octet-stream") { "朗读接口未返回音频" }
                val first = bytes.toString(Charsets.UTF_8).trimStart().firstOrNull()
                speechCheck(first != '<' && first != '{' && first != '[') { "朗读接口返回了无效音频" }
                if (engine in setOf(SpeechEngine.OPENAI, SpeechEngine.COSYVOICE, SpeechEngine.MOSS)) {
                    speechCheck(DoubaoSpeech.looksLikeMp3(bytes) || DoubaoSpeech.looksLikeWav(bytes)) { "朗读接口返回了无效音频" }
                    bytes
                } else if (engine == SpeechEngine.GROQ) bytes else DoubaoSpeech.decodeAudio(bytes).bytes
            }
        }
    }

    fun decodeMimoSse(text: String): ByteArray = collectSse(text, "小米语音没有返回音频") { json ->
        json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")?.optJSONObject("audio")?.optString("data").orEmpty()
            .takeIf { it.isNotBlank() }?.let(::base64)
    }
    fun decodeMiniMaxSse(text: String): ByteArray = collectSse(text, "MiniMax 没有返回音频") { json ->
        val status = json.optJSONObject("base_resp")?.optInt("status_code", 0) ?: 0
        speechCheck(status == 0) { "MiniMax 返回错误状态" }
        val hex = json.optJSONObject("data")?.optString("audio").orEmpty().filterNot { it.isWhitespace() }
        if (hex.isEmpty()) null else {
            speechCheck(hex.length % 2 == 0 && hex.all { it in "0123456789abcdefABCDEF" }) { "MiniMax 音频编码无效" }
            ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        }
    }
    fun decodeQwenSse(text: String): ByteArray = collectSse(text, "通义语音没有返回音频") { json ->
        json.optJSONObject("output")?.optJSONObject("audio")?.optString("data").orEmpty()
            .takeIf { it.isNotBlank() }?.let(::base64)
    }
    fun decodeGemini(text: String): ByteArray {
        val encoded = JSONObject(text).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
            ?.optJSONArray("parts")?.optJSONObject(0)?.optJSONObject("inlineData")?.optString("data").orEmpty()
        speechCheck(encoded.isNotBlank()) { "Gemini 没有返回音频" }
        return base64(encoded)
    }

    /** Complete response parser: multi-line data fields, CRLF, comments and final unterminated event. */
    fun sseData(text: String): List<String> {
        val out = ArrayList<String>()
        val data = ArrayList<String>()
        text.lineSequence().forEach { line ->
            when {
                line.substringBefore(':') == "data" ->
                    data += line.substringAfter(':', "").removePrefix(" ")
                line.isEmpty() && data.isNotEmpty() -> { out += data.joinToString("\n"); data.clear() }
                // Comments and all other SSE fields (event, id, retry, extensions) carry no data.
            }
        }
        if (data.isNotEmpty()) out += data.joinToString("\n")
        return out
    }
    private fun collectSse(text: String, empty: String, decode: (JSONObject) -> ByteArray?): ByteArray {
        speechCheck(text.toByteArray(Charsets.UTF_8).size <= SpeechSynthesisClient.MAX_AUDIO_BYTES) { "朗读音频超过大小限制" }
        val out = ByteArrayOutputStream()
        for (payload in sseData(text)) {
            if (payload.trim() == "[DONE]") break
            val json = runCatching { JSONObject(payload) }.getOrNull() ?: continue
            speechCheck(!json.has("error")) { "语音接口返回错误状态" }
            val chunk = decode(json) ?: continue
            speechCheck(out.size() + chunk.size <= SpeechSynthesisClient.MAX_AUDIO_BYTES) { "朗读音频超过大小限制" }
            out.write(chunk)
        }
        speechCheck(out.size() > 0) { empty }
        return out.toByteArray()
    }
    private fun base64(encoded: String): ByteArray {
        speechCheck(encoded.length <= SpeechSynthesisClient.MAX_AUDIO_BYTES) { "朗读音频超过大小限制" }
        return try { Base64.getDecoder().decode(encoded.filterNot { it.isWhitespace() }) }
        catch (_: IllegalArgumentException) { throw VoiceProviderException("语音音频编码无效") }
    }

    private fun bearer(config: SpeechProviderConfig, vararg extra: Pair<String, String>): Map<String, String> = headers(config,
        *buildList { if (config.apiKey.isNotBlank()) add("Authorization" to "Bearer ${config.apiKey}"); addAll(extra) }.toTypedArray())
    private fun post(url: String, headers: Map<String, String>, body: JSONObject) = VoiceHttpRequest(url, headers, body.toString())
    private fun component(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun openAi(config: SpeechProviderConfig, text: String, voice: String, wav: Boolean): VoiceHttpRequest {
        val format = if (wav) "wav" else "mp3"
        return post(VoiceUrls.openAi(config.baseUrl, "audio/speech"), bearer(config, "Accept" to if (wav) "audio/wav" else "audio/mpeg"),
            JSONObject().put("model", config.model).put("input", text).put("voice", voice).put("response_format", format))
    }
    private fun step(config: SpeechProviderConfig, text: String, voice: String): VoiceHttpRequest {
        val base = VoiceUrls.normalize(config.baseUrl)
        val url = if (base.endsWith("/v1")) "$base/audio/speech" else "$base/v1/audio/speech"
        return post(url, bearer(config, "Accept" to "application/octet-stream"), JSONObject().put("model", config.model)
            .put("input", text).put("voice", voice).put("responseFormat", "mp3").put("sampleRate", 24000))
    }

    fun mimoClone(config: SpeechProviderConfig, text: String, reference: String): VoiceHttpRequest {
        speechCheck(config.engine == SpeechEngine.MIMO && config.apiKey.isNotBlank()) { "请配置 MiMo 提供商" }
        speechCheck(reference.startsWith("data:audio/mpeg;base64,") || reference.startsWith("data:audio/wav;base64,")) { "请选择有效的参考录音" }
        speechCheck(reference.substringAfter(',').length <= 10_000_000) { "参考录音过大" }
        val payload = JSONObject().put("model", "mimo-v2.5-tts-voiceclone")
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", ""))
                .put(JSONObject().put("role", "assistant").put("content", text)))
            .put("audio", JSONObject().put("format", "wav").put("voice", reference)).put("stream", false)
        return post(VoiceUrls.openAi(config.baseUrl, "chat/completions"),
            headers(config, "Accept" to "application/json", "api-key" to config.apiKey, "Authorization" to "Bearer ${config.apiKey}"), payload)
    }
    fun decodeMimoClone(bytes: ByteArray): ByteArray {
        speechCheck(bytes.size <= SpeechSynthesisClient.MAX_AUDIO_BYTES) { "试听音频超过大小限制" }
        val encoded = JSONObject(bytes.toString(Charsets.UTF_8)).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getJSONObject("audio").getString("data")
        val wav = base64(encoded)
        speechCheck(wav.size >= 44 && DoubaoSpeech.looksLikeWav(wav)) { "MiMo 未返回有效的 WAV 音频" }
        return wav
    }
    private fun mimo(config: SpeechProviderConfig, text: String, voice: String): VoiceHttpRequest {
        val auth = buildList {
            add("Accept" to "text/event-stream")
            if (config.apiKey.isNotBlank()) { add("api-key" to config.apiKey); add("Authorization" to "Bearer ${config.apiKey}") }
        }
        return post(VoiceUrls.openAi(config.baseUrl, "chat/completions"), headers(config, *auth.toTypedArray()),
            JSONObject().put("model", config.model).put("messages", JSONArray().put(JSONObject().put("role", "assistant").put("content", text)))
                .put("audio", JSONObject().put("format", "pcm16").put("voice", voice)).put("stream", true))
    }
    private fun minimax(config: SpeechProviderConfig, text: String, voice: String): VoiceHttpRequest = post(
        VoiceUrls.join(config.baseUrl, "t2a_v2"), bearer(config, "Accept" to "text/event-stream"),
        JSONObject().put("model", config.model).put("text", text).put("stream", true).put("output_format", "hex")
            .put("voice_setting", JSONObject().put("voice_id", voice).put("speed", 1.0)))
    private fun qwen(config: SpeechProviderConfig, text: String, voice: String): VoiceHttpRequest {
        val host = VoiceUrls.host(config.baseUrl)
        val url = if (host.contains("dashscope") && !config.baseUrl.contains("maas.aliyuncs.com"))
            "https://dashscope.aliyuncs.com/api/v1/services/audio/tts/SpeechSynthesizer"
        else VoiceUrls.join(config.baseUrl, "services/audio/tts/SpeechSynthesizer")
        return post(url, bearer(config, "X-DashScope-SSE" to "enable", "Accept" to "text/event-stream"),
            JSONObject().put("model", config.model).put("input", JSONObject().put("text", text).put("voice", voice).put("format", "wav").put("sample_rate", 24000)))
    }
    private fun xai(config: SpeechProviderConfig, text: String, voice: String): VoiceHttpRequest = post(
        VoiceUrls.join(config.baseUrl, "tts"), bearer(config), JSONObject().put("text", text).put("voice_id", voice).put("language", "auto"))
    private fun gemini(config: SpeechProviderConfig, text: String, voice: String): VoiceHttpRequest {
        val auth = if (config.apiKey.isBlank()) emptyArray() else arrayOf("x-goog-api-key" to config.apiKey)
        return post("${VoiceUrls.normalize(config.baseUrl)}/models/${component(config.model)}:generateContent", headers(config, *auth),
            JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text)))))
                .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("AUDIO"))
                    .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice)))))
                .put("model", config.model))
    }
    private fun elevenLabs(config: SpeechProviderConfig, text: String, voice: String): VoiceHttpRequest {
        val auth = buildList { add("Accept" to "audio/mpeg"); if (config.apiKey.isNotBlank()) add("xi-api-key" to config.apiKey) }
        val url = "${VoiceUrls.join(config.baseUrl, "v1/text-to-speech")}/${component(voice)}?output_format=mp3_44100_128"
        return post(url, headers(config, *auth.toTypedArray()), JSONObject().put("text", text).put("model_id", config.model.ifBlank { "eleven_multilingual_v2" }))
    }
    private fun fish(config: SpeechProviderConfig, text: String, voice: String): VoiceHttpRequest = post(
        VoiceUrls.join(config.baseUrl, "v1/tts"), bearer(config, "model" to config.model),
        JSONObject().put("text", text).put("format", "mp3").apply { if (voice.isNotBlank()) put("reference_id", voice) })
}
