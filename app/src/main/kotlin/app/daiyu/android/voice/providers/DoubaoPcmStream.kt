package app.daiyu.android.voice.providers

import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import org.json.JSONObject
import org.json.JSONTokener

sealed interface VoiceHttpChunk {
    class Headers(val status: Int, val contentType: String?) : VoiceHttpChunk
    class Bytes(val value: ByteArray) : VoiceHttpChunk
}

/** One POST, bounded incremental response. Cancellation closes the actual call; no replay. */
interface VoiceHttpStreamingTransport : VoiceHttpTransport {
    fun stream(request: VoiceHttpRequest, maxResponseBytes: Int): Flow<VoiceHttpChunk>
}

internal class DoubaoPcmSynthesis(private val transport: VoiceHttpStreamingTransport) {
    suspend fun synthesize(
        config: SpeechProviderConfig, text: String, voice: String, personal: DoubaoPersonalVoice?,
        beforeSend: () -> Unit, audio: suspend (ByteArray) -> Unit,
    ) {
        try {
            speechCheck(text.isNotBlank() && voice.isNotBlank()) { "没有可朗读的文字或音色" }
            speechCheck(!DoubaoSpeech.usesCreate(config.model)) { "此模型不支持流式朗读" }
            val original = DoubaoSpeech.request(config, text, voice, personal)
            val body = JSONObject(original.body)
            body.getJSONObject("req_params").getJSONObject("audio_params").put("format", "pcm")
            val request = VoiceHttpRequest(original.url, original.headers, body.toString())
            val decoder = DoubaoPcmDecoder()
            var headers = false
            currentCoroutineContext().ensureActive()
            beforeSend()
            transport.stream(request, SpeechSynthesisClient.MAX_AUDIO_BYTES).collect { chunk ->
                currentCoroutineContext().ensureActive()
                when (chunk) {
                    is VoiceHttpChunk.Headers -> {
                        speechCheck(!headers && chunk.status in 200..299) { "朗读接口 HTTP ${chunk.status}" }
                        speechCheck(chunk.contentType.orEmpty().substringBefore(';').trim().lowercase() in
                            setOf("", "application/json", "text/plain", "text/event-stream", "application/octet-stream")) {
                            "朗读接口未返回流式音频"
                        }
                        headers = true
                    }
                    is VoiceHttpChunk.Bytes -> {
                        speechCheck(headers) { "朗读接口响应顺序无效" }
                        decoder.accept(chunk.value, audio)
                    }
                }
            }
            speechCheck(headers) { "朗读接口返回了空响应" }
            decoder.finish()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (safe: VoiceProviderException) { throw safe }
        catch (_: Exception) { throw VoiceProviderException("流式朗读音频读取失败") }
    }
}

/** Frames SSE events before scanning JSON bytes; HTTP/UTF-8/base64 splits remain lossless. */
internal class DoubaoPcmDecoder {
    private val objectBytes = ByteArrayOutputStream()
    private val prefix = StringBuilder()
    private val sseLine = ByteArrayOutputStream()
    private val sseData = ByteArrayOutputStream()
    private var sse: Boolean? = null
    private var depth = 0
    private var quoted = false
    private var escaped = false
    private var wireBytes = 0
    private var audioBytes = 0
    private var trailingSample: Byte? = null
    private var terminal = false

    suspend fun accept(bytes: ByteArray, audio: suspend (ByteArray) -> Unit) {
        speechCheck(wireBytes.toLong() + bytes.size <= SpeechSynthesisClient.MAX_AUDIO_BYTES) { "朗读音频超过大小限制" }
        wireBytes += bytes.size
        for (byte in bytes) {
            if (sse == null) {
                val char = (byte.toInt() and 255).toChar()
                if (char.isWhitespace()) continue
                sse = char != '{'
            }
            if (sse == true) {
                if (byte.toInt() == 10) acceptSseLine(audio) else sseLine.write(byte.toInt())
            } else acceptJsonByte(byte, audio)
        }
    }

    private suspend fun acceptSseLine(audio: suspend (ByteArray) -> Unit) {
        val line = sseLine.toString(Charsets.UTF_8.name()).removeSuffix("\r")
        sseLine.reset()
        when {
            line.isEmpty() -> dispatchSse(audio)
            line.startsWith(":") -> Unit
            line.substringBefore(':') == "data" -> {
                if (sseData.size() > 0) sseData.write(10)
                sseData.write(line.substringAfter(':', "").removePrefix(" ").toByteArray(Charsets.UTF_8))
            }
            else -> Unit
        }
    }

    private suspend fun dispatchSse(audio: suspend (ByteArray) -> Unit) {
        if (sseData.size() == 0) return
        val bytes = sseData.toByteArray()
        sseData.reset()
        if (bytes.toString(Charsets.UTF_8).trim() == "[DONE]") {
            speechCheck(terminal) { "朗读音频未完整结束" }
            return
        }
        for (byte in bytes) acceptJsonByte(byte, audio)
        speechCheck(depth == 0) { "朗读接口返回了无效 JSON" }
        checkPrefix()
    }

    private suspend fun acceptJsonByte(byte: Byte, audio: suspend (ByteArray) -> Unit) {
        val char = (byte.toInt() and 255).toChar()
        if (depth == 0) {
            if (char == '{') {
                speechCheck(!terminal && prefix.toString().trim().isEmpty()) { "朗读接口响应顺序无效" }
                prefix.setLength(0)
                objectBytes.write(byte.toInt())
                depth = 1
            } else if (char == '\n') {
                checkPrefix()
            } else {
                speechCheck(prefix.length < 8192 && char != '\u0000') { "朗读接口返回了无效 JSON" }
                prefix.append(char)
            }
        } else {
            speechCheck(char != '\u0000') { "朗读接口返回了无效 JSON" }
            objectBytes.write(byte.toInt())
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> depth++
                '}', ']' -> depth--
            }
            if (depth == 0) {
                val text = objectBytes.toString(Charsets.UTF_8.name())
                objectBytes.reset()
                val tokens = JSONTokener(text)
                val json = tokens.nextValue() as? JSONObject ?: throw VoiceProviderException("朗读接口返回了无效 JSON")
                speechCheck(tokens.nextClean() == '\u0000') { "朗读接口返回了无效 JSON" }
                val code = json.opt("code")
                speechCheck((code is Int || code is Long) && (code as Number).toLong() in setOf(0L, 20000000L)) { "朗读接口返回了错误状态" }
                if ((code as Number).toLong() == 20000000L) {
                    speechCheck(json.isNull("data") || json.optString("data").isEmpty()) { "朗读接口结束响应无效" }
                    terminal = true
                } else {
                    val payload = json.opt("data")
                    speechCheck(payload == null || payload == JSONObject.NULL || payload is String) { "朗读接口音频格式无效" }
                    if (payload is String && payload.isNotBlank()) {
                        val decoded = Base64.getDecoder().decode(payload.filterNot { it.isWhitespace() })
                        speechCheck(audioBytes.toLong() + decoded.size <= SpeechSynthesisClient.MAX_AUDIO_BYTES) { "朗读音频超过大小限制" }
                        audioBytes += decoded.size
                        val combined = trailingSample?.let { byteArrayOf(it) + decoded } ?: decoded
                        val count = combined.size - combined.size % 2
                        trailingSample = if (count < combined.size) combined.last() else null
                        if (count > 0) audio(if (count == combined.size) combined else combined.copyOf(count))
                    }
                }
            }
        }
    }

    private fun checkPrefix() {
        speechCheck(prefix.toString().trim().isEmpty()) { "朗读接口返回了无效 JSON" }
        prefix.setLength(0)
    }

    fun finish() {
        speechCheck(sse != true || sseLine.size() == 0 && sseData.size() == 0) { "朗读音频未完整结束" }
        checkPrefix()
        speechCheck(depth == 0 && terminal && audioBytes > 0 && trailingSample == null) { "朗读音频未完整结束" }
    }
}
