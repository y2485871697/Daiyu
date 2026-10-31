package app.daiyu.android.voice.providers

import java.nio.ByteBuffer
import java.util.UUID
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

// Doubao ASR framing and response decoding; see docs/P32-voice-providers.md.
object DoubaoAsrProtocol {
    const val URL = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async"
    const val MAX_PACKET_BYTES = 1_048_576
    val resources = listOf("volc.seedasr.sauc.duration", "volc.seedasr.sauc.concurrent", "volc.bigasr.sauc.duration", "volc.bigasr.sauc.concurrent")

    fun config(): ByteArray = frame(1, JSONObject()
        .put("user", JSONObject().put("uid", "daiyu"))
        .put("audio", JSONObject().put("format", "pcm").put("codec", "raw").put("rate", 16000).put("bits", 16).put("channel", 1))
        .put("request", JSONObject().put("model_name", "bigmodel").put("enable_nonstream", true)
            .put("enable_itn", true).put("enable_punc", true).put("show_utterances", true)
            .put("result_type", "full").put("end_window_size", 1000)).toString().toByteArray(Charsets.UTF_8))

    fun frame(type: Int, payload: ByteArray, last: Boolean = false): ByteArray {
        require(type == 1 || type == 2) { "ASR 请求类型无效" }
        require(payload.size <= MAX_PACKET_BYTES - 8) { "ASR 请求过大" }
        return ByteBuffer.allocate(8 + payload.size).put(0x11.toByte())
            .put(((type shl 4) or if (last) 2 else 0).toByte())
            .put(if (type == 1) 0x10.toByte() else 0.toByte()).put(0.toByte())
            .putInt(payload.size).put(payload).array()
    }

    /** text is a FULL hypothesis (replace, do not append); definite utterance is not final frame. */
    data class Result(val text: String, val final: Boolean, val utteranceDone: Boolean)

    fun decode(bytes: ByteArray): Result {
        require(bytes.size in 8..MAX_PACKET_BYTES) { "ASR 响应长度无效" }
        val buffer = ByteBuffer.wrap(bytes)
        val header = buffer.get().toInt() and 255
        val headerSize = (header and 15) * 4
        require(header shr 4 == 1 && headerSize >= 4 && headerSize <= bytes.size - 4) { "ASR 响应头无效" }
        val kind = buffer.get().toInt() and 255
        val codec = buffer.get().toInt() and 255
        buffer.position(headerSize)
        if (kind shr 4 == 15) throw VoiceProviderException("豆包 ASR 错误码 ${buffer.int}")
        require(kind shr 4 == 9 && kind and 15 in 0..3) { "未知 ASR 响应类型" }
        if (kind and 1 != 0) {
            require(buffer.remaining() >= 8) { "ASR 响应序号缺失" }
            buffer.int
        }
        val size = buffer.int
        require(size >= 0 && size == buffer.remaining()) { "ASR 响应长度无效" }
        var data = ByteArray(size).also(buffer::get)
        require(codec and 15 in 0..1 && codec shr 4 == 1) { "ASR 响应编码无效" }
        if (codec and 15 == 1) data = GZIPInputStream(data.inputStream()).use {
            val out = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val n = it.read(chunk)
                if (n < 0) break
                require(out.size() + n <= MAX_PACKET_BYTES) { "ASR 解压响应过大" }
                out.write(chunk, 0, n)
            }
            out.toByteArray()
        }
        val root = JSONObject(data.toString(Charsets.UTF_8))
        val code = root.optInt("code", 1000)
        speechCheck(code == 1000 || code == 0 || code == 20000000) { "豆包 ASR 错误码 $code" }
        val result = root.optJSONObject("result")
        val utterances = result?.optJSONArray("utterances")
        return Result(result?.optString("text").orEmpty(), kind and 2 != 0,
            utterances != null && utterances.length() > 0 && utterances.optJSONObject(utterances.length() - 1)?.optBoolean("definite") == true)
    }

    fun handshake(config: DoubaoAsrConfig): VoiceSocketRequest {
        require(config.apiKey.isNotBlank()) { "请配置豆包 ASR API Key" }
        require(config.resource in resources) { "ASR 资源无效" }
        require(config.requestId.isNotBlank()) { "ASR 请求 ID 无效" }
        val headers = mapOf("X-Api-Key" to config.apiKey, "X-Api-Resource-Id" to config.resource, "X-Api-Request-Id" to config.requestId)
        require(headers.values.none { '\r' in it || '\n' in it }) { "ASR 请求头无效" }
        return VoiceSocketRequest(URL, headers)
    }

    /** Opens a transport-owned socket, sends the real full-client request, then accepts PCM16/16k/mono. */
    suspend fun open(config: DoubaoAsrConfig, transport: VoiceSocketTransport): DoubaoAsrConnection {
        val request = handshake(config)
        val socket = try { transport.connect(request, MAX_PACKET_BYTES) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw VoiceProviderException("豆包 ASR 连接失败") }
        try {
            currentCoroutineContext().ensureActive()
            socket.send(DoubaoAsrProtocol.config())
            currentCoroutineContext().ensureActive()
            return DoubaoAsrConnection(socket)
        } catch (cancelled: CancellationException) {
            socket.close()
            throw cancelled
        } catch (_: Exception) {
            socket.close()
            throw VoiceProviderException("豆包 ASR 初始化失败")
        }
    }
}

class DoubaoAsrConfig(val apiKey: String, val resource: String, val requestId: String = UUID.randomUUID().toString()) {
    override fun toString() = "DoubaoAsrConfig(redacted)"
}
class VoiceSocketRequest(val url: String, val headers: Map<String, String>) {
    override fun toString() = "VoiceSocketRequest(redacted)"
}

/** Adapter must bound received binary frames, reject text frames and close on cancellation. */
fun interface VoiceSocketTransport {
    suspend fun connect(request: VoiceSocketRequest, maxFrameBytes: Int): VoiceSocket
}
interface VoiceSocket : AutoCloseable {
    suspend fun send(bytes: ByteArray)
    suspend fun receive(): ByteArray?
    /** Idempotent and non-throwing; must cancel pending network work. */
    override fun close()
}

/** Caller serializes send calls; one receiver. Caller owns permission, capture, timeout and close. */
class DoubaoAsrConnection internal constructor(private val socket: VoiceSocket) : AutoCloseable {
    private var ended = false
    suspend fun sendPcm(pcm16Mono16k: ByteArray, last: Boolean = false) {
        check(!ended) { "ASR 音频已结束" }
        require(pcm16Mono16k.size % 2 == 0) { "PCM16 数据长度无效" }
        val frame = DoubaoAsrProtocol.frame(2, pcm16Mono16k, last)
        if (last) ended = true
        checked { socket.send(frame) }
    }
    suspend fun receive(): DoubaoAsrProtocol.Result? = checked {
        socket.receive()?.let(DoubaoAsrProtocol::decode)
    }
    private suspend fun <T> checked(operation: suspend () -> T): T = try {
        currentCoroutineContext().ensureActive()
        operation().also { currentCoroutineContext().ensureActive() }
    } catch (cancelled: CancellationException) {
        close()
        throw cancelled
    } catch (safe: VoiceProviderException) {
        close()
        throw safe
    } catch (_: Exception) {
        close()
        throw VoiceProviderException("豆包 ASR 数据传输或解码失败")
    }
    override fun close() { ended = true; socket.close() }
}
