package app.daiyu.android.voice.providers

import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Explicit selection: no chat model, global settings, key store or provider guessing. */
enum class SpeechEngine { OPENAI, DOUBAO, MIMO, MINIMAX, STEP, QWEN, GROQ, XAI, GEMINI, ELEVENLABS, FISH, COSYVOICE, MOSS }

class SpeechProviderConfig(
    val engine: SpeechEngine,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val providerId: String,
    customHeaders: Map<String, String> = emptyMap(),
) {
    val customHeaders: Map<String, String> = customHeaders.toMap()
    override fun toString() = "SpeechProviderConfig(engine=$engine, credentials/endpoint/model/provider=redacted)"
}

/** Sensitive wire value. Never log headers, URL queries, body or the underlying transport exception. */
class VoiceHttpRequest(val url: String, headers: Map<String, String>, val body: String) {
    val headers = headers.toMap()
    init { VoiceUrls.validate(url, "https", allowQuery = true) }
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
    override fun toString() = "VoiceHttpRequest(POST, redacted)"
}

class VoiceHttpResponse(val status: Int, val contentType: String?, val bytes: ByteArray) {
    override fun toString() = "VoiceHttpResponse(status=$status, bytes=${bytes.size})"
}

/**
 * Implement with the parent's network stack. POST UTF-8 JSON, honor coroutine cancellation by
 * cancelling the actual call, close response bodies, bound bytes WHILE reading (including unknown
 * content-length), and do not follow redirects/forward credentials to a different origin.
 * No retry is implied (especially for billable generation). No request/response body logging.
 */
fun interface VoiceHttpTransport {
    suspend fun execute(request: VoiceHttpRequest, maxResponseBytes: Int): VoiceHttpResponse
}

/** Provider-bound local references; implemented by voice.personal.MimoPersonalVoices. */
fun interface PersonalVoiceReference {
    fun reference(id: String, providerId: String): String
}

class VoiceProviderException(message: String) : IllegalStateException(message)
internal fun speechCheck(value: Boolean, message: () -> String) {
    if (!value) throw VoiceProviderException(message())
}

internal object VoiceUrls {
    fun validate(url: String, scheme: String, allowQuery: Boolean = false): URI {
        val uri = try { URI(url.trim()) } catch (_: Exception) { throw VoiceProviderException("语音接口地址无效") }
        speechCheck(uri.scheme.equals(scheme, true) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
            uri.rawFragment == null && (allowQuery || uri.rawQuery == null)) { "语音接口地址无效" }
        return uri
    }
    fun host(url: String): String = runCatching { URI(url.trim()).host.orEmpty().lowercase() }.getOrDefault("")
    fun normalize(base: String): String = base.trim().trimEnd('/').also { validate(it, "https") }
    fun join(base: String, path: String): String {
        val root = normalize(base)
        val clean = path.trimStart('/')
        return if (root.endsWith("/$clean")) root else "$root/$clean"
    }
    // Keep configured API prefixes; never force a relay back onto the public OpenAI endpoint.
    fun openAi(base: String, path: String): String = join(base, path)
}

internal fun headers(config: SpeechProviderConfig, vararg initial: Pair<String, String>): Map<String, String> {
    val result = linkedMapOf("Content-Type" to "application/json")
    fun put(name: String, value: String) {
        speechCheck(name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) && value.none { it == '\r' || it == '\n' }) { "语音请求头无效" }
        speechCheck(name.lowercase() !in setOf("host", "content-length", "transfer-encoding", "connection")) { "不可覆盖传输请求头" }
        result.keys.firstOrNull { it.equals(name, true) }?.let { result.remove(it) }
        result[name] = value
    }
    initial.forEach { (name, value) -> put(name, value) }
    config.customHeaders.forEach { (name, value) -> put(name, value) }
    return result
}

/** Bytes are synthesis results, NOT evidence of playback start or permission to use a microphone. */
class SpeechSynthesisClient(
    private val transport: VoiceHttpTransport,
    private val personalVoices: PersonalVoiceReference? = null,
) {
    suspend fun synthesize(
        config: SpeechProviderConfig,
        text: String,
        voice: String,
        doubaoPersonalVoice: DoubaoPersonalVoice? = null,
    ): ByteArray {
        speechCheck(text.isNotBlank()) { "没有可朗读的文字" }
        speechCheck(voice.isNotBlank()) { "请选择音色" }
        return try {
            currentCoroutineContext().ensureActive()
            val clone = voice.startsWith("mimo-local-")
            val request = if (clone) {
                speechCheck(config.engine == SpeechEngine.MIMO && config.providerId.isNotBlank()) { "此个人声音仅支持所属 MiMo 提供商" }
                val reference = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    personalVoices?.reference(voice, config.providerId)
                        ?: throw VoiceProviderException("个人声音不可用")
                }
                SpeechProtocols.mimoClone(config, text, reference)
            } else if (config.engine == SpeechEngine.DOUBAO) {
                DoubaoSpeech.request(config, text, voice, doubaoPersonalVoice)
            } else SpeechProtocols.request(config, text, voice)
            val response = try { transport.execute(request, MAX_AUDIO_BYTES) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { throw VoiceProviderException("朗读网络请求失败") }
            currentCoroutineContext().ensureActive()
            speechCheck(response.status in 200..299) { "朗读接口 HTTP ${response.status}" }
            speechCheck(response.bytes.size <= MAX_AUDIO_BYTES) { "朗读音频超过大小限制" }
            speechCheck(response.bytes.isNotEmpty()) { "朗读接口返回了空响应" }
            val audio = when {
                clone -> SpeechProtocols.decodeMimoClone(response.bytes)
                config.engine == SpeechEngine.DOUBAO -> DoubaoSpeech.decodeResponse(response.contentType, response.bytes)
                else -> SpeechProtocols.decode(config.engine, response.contentType, response.bytes)
            }
            speechCheck(audio.isNotEmpty() && audio.size <= MAX_AUDIO_BYTES + 44) { "朗读音频为空或超过大小限制" }
            currentCoroutineContext().ensureActive()
            audio
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (safe: VoiceProviderException) {
            throw safe
        } catch (_: Exception) {
            // JSON/base64/network exceptions can contain user text, references or credentials.
            throw VoiceProviderException("朗读音频读取失败")
        }
    }

    companion object { const val MAX_AUDIO_BYTES = 8 * 1024 * 1024 }
}
