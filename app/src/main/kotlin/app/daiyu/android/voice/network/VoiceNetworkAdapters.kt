package app.daiyu.android.voice.network

import app.daiyu.android.diagnostics.DiagnosticLogStore
import app.daiyu.android.voice.duplex.DuplexTransport
import app.daiyu.android.voice.providers.SpeechEngine
import app.daiyu.android.voice.providers.VoiceHttpTransport
import app.daiyu.android.voice.providers.VoiceProviderException
import app.daiyu.android.voice.providers.VoiceSocketTransport
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** 创建 HTTP、ASR 和双向通话传输；调用方提供配置并管理音频归属。 */
object VoiceNetworkAdapters {
    fun http(): VoiceHttpTransport = OkHttpVoiceTransport()
    fun speechHttp(engine: SpeechEngine): VoiceHttpTransport = speechHttp(engine, null)
    internal fun speechHttp(engine: SpeechEngine, diagnostics: DiagnosticLogStore?): VoiceHttpTransport = OkHttpVoiceTransport(
        if (engine == SpeechEngine.DOUBAO) OkHttpVoiceTransport.doubaoSpeechClient else OkHttpVoiceTransport.speechClient,
        diagnostics,
    )
    internal fun speechTimeoutMillis(engine: SpeechEngine): Long = if (engine == SpeechEngine.DOUBAO) 210_000L else 90_000L
    fun asr(): VoiceSocketTransport = AsrNetworkTransport()
    fun duplex(): DuplexTransport = DuplexNetworkTransport()
}

internal object NetworkLimits {
    const val HTTP_BYTES = 32 * 1024 * 1024
    const val REQUEST_BYTES = 4 * 1024 * 1024
    const val FRAME_BYTES = 1024 * 1024
    const val QUEUED_MESSAGES = 8
    // 64 x 20ms PCM frames tolerate scheduling/TLS bursts without unbounded audio backlog.
    const val OUTBOUND_MESSAGES = 64
    const val OUTBOUND_BYTES = FRAME_BYTES + 14
    const val CONNECT_TIMEOUT_MS = 15_000
}

internal fun networkFailure(): VoiceProviderException = VoiceProviderException("语音网络传输失败")
internal fun checkNetwork(value: Boolean) { if (!value) throw networkFailure() }

internal fun checkedUri(url: String, scheme: String): URI = try {
    checkNetwork(url.length <= 8192)
    URI(url).also {
        checkNetwork(it.scheme == scheme && !it.host.isNullOrBlank() && it.rawUserInfo == null &&
            it.rawFragment == null && (it.port == -1 || it.port in 1..65535))
    }
} catch (_: Exception) { throw networkFailure() }

internal fun checkedHeaders(headers: Map<String, String>): Map<String, String> {
    checkNetwork(headers.size <= 64)
    val result = linkedMapOf<String, String>()
    val reserved = setOf("host", "content-length", "transfer-encoding", "connection", "upgrade",
        "cookie", "proxy-authorization", "accept-encoding")
    var size = 0
    headers.forEach { (key, value) ->
        checkNetwork(key.length <= 256 && value.length <= 16 * 1024)
        size += key.length + value.length
        val lower = key.lowercase(java.util.Locale.ROOT)
        checkNetwork(key.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) &&
            value.all { it == '\t' || it.code in 32..126 } &&
            lower !in reserved && !lower.startsWith("sec-websocket-") && size <= 16 * 1024 &&
            result.keys.none { it.equals(key, ignoreCase = true) })
        result[key] = value
    }
    return result
}

/** Check before allocating encoded bytes, then account for multibyte UTF-8 exactly. */
internal fun boundedUtf8(value: String, limit: Int): ByteArray {
    checkNetwork(value.length <= limit)
    return value.toByteArray(Charsets.UTF_8).also { checkNetwork(it.size <= limit) }
}

internal fun strictUtf8(bytes: ByteArray): String = try {
    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
} catch (_: Exception) { throw networkFailure() }
