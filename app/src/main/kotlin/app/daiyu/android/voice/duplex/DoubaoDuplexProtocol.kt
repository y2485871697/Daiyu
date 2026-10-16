package app.daiyu.android.voice.duplex

import java.util.UUID
import org.json.JSONObject

/** Explicit, per-call snapshot. No provider preferences, old package or TTS credentials. */
class DoubaoDuplexConfig(
    val apiKey: String,
    val voice: String = DoubaoDuplexProtocol.DEFAULT_VOICE,
    val instructions: String = "你是代鱼，一个简洁、自然、友善的中文语音助手。",
) {
    init {
        require(apiKey.isNotBlank() && '\r' !in apiKey && '\n' !in apiKey)
        require(voice.isNotBlank())
    }
    override fun toString() = "DoubaoDuplexConfig(redacted)"
}

class DuplexRequest internal constructor(val url: String, val headers: Map<String, String>) {
    override fun toString() = "DuplexRequest($url, headers=redacted)"
}

/** Migrated from the frozen DoubaoDuplexProtocol, not an OpenAI compatibility layer. */
object DoubaoDuplexProtocol {
    const val ENDPOINT = "wss://openspeech.bytedance.com/api/v3/duplex/realtime/dialogue"
    const val DEFAULT_VOICE = "zh_female_xiaohe_jupiter_bigtts"
    const val INPUT_RATE = 16_000
    const val OUTPUT_RATE = 24_000
    const val INPUT_FRAME_BYTES = 640

    fun request(config: DoubaoDuplexConfig) = DuplexRequest(ENDPOINT, mapOf(
        "X-Api-Key" to config.apiKey,
        "X-Api-Connect-Id" to UUID.randomUUID().toString(),
    ))

    fun sessionCreate(config: DoubaoDuplexConfig): JSONObject = JSONObject()
        .put("type", "session.create")
        .put("event_id", UUID.randomUUID().toString())
        .put("extension", JSONObject().put("asr", JSONObject().put("extra",
            JSONObject().put("enable_asr_twopass", true))))
        .put("session", JSONObject()
            .put("model", "1.2.6.1")
            .put("instructions", config.instructions)
            .put("audio", JSONObject()
                .put("input", JSONObject().put("format", JSONObject()
                    .put("type", "pcm").put("rate", INPUT_RATE)))
                .put("output", JSONObject().put("format", JSONObject()
                    .put("type", "pcm_s16le").put("rate", OUTPUT_RATE))
                    .put("voice", config.voice))))

    fun inputAudio(bytes: ByteArray): String = JSONObject()
        .put("type", "input_audio_buffer.append")
        .put("audio", java.util.Base64.getEncoder().encodeToString(bytes)).toString()

    fun sessionClose(): String = JSONObject().put("type", "session.close")
        .put("event_id", UUID.randomUUID().toString()).toString()

    fun eventText(event: JSONObject): String = listOf("text", "delta", "transcript", "content")
        .firstNotNullOfOrNull { (event.opt(it) as? String)?.takeIf(String::isNotBlank) }.orEmpty()

    /** Output is append-only, including whitespace, until the authoritative done snapshot. */
    fun outputText(event: JSONObject): String = listOf("text", "delta", "transcript", "content")
        .firstNotNullOfOrNull { (event.opt(it) as? String)?.takeIf(String::isNotEmpty) }.orEmpty()

    fun audioPayload(event: JSONObject): String = event.optString("audio").ifBlank { event.optString("delta") }
}
