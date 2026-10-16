package app.daiyu.android

import android.content.Context
import app.daiyu.android.storage.ModelSettings
import app.daiyu.android.storage.ModelSettingsStore
import app.daiyu.android.voice.personal.MimoPersonalVoices
import app.daiyu.android.voice.providers.*
import app.daiyu.android.voice.duplex.DoubaoDuplexProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/** Separate from chat credentials. The entire envelope (including keys) uses the existing vault. */
internal data class VoiceConfiguration(
    val revision: String = UUID.randomUUID().toString(),
    val engine: String = "SYSTEM",
    val endpoint: String = "", val key: String = "", val model: String = "",
    val provider: String = "", val voice: String = "",
    val dictation: String = "SYSTEM", val asrKey: String = "", val asrResource: String = "",
    val duplexKey: String = "", val duplexVoice: String = DoubaoDuplexProtocol.DEFAULT_VOICE,
    val instructions: String = "你是代鱼，一个简洁、自然、友善的中文语音助手。",
) {
    override fun toString() = "VoiceConfiguration(redacted)"
    fun referenceOwner(): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(listOf(provider, endpoint, key).joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    fun speech() = SpeechProviderConfig(SpeechEngine.valueOf(engine), endpoint, key, model,
        if (voice.startsWith(MimoPersonalVoices.PREFIX)) referenceOwner() else provider)
    fun encode(): String = JSONObject().apply {
        put("revision", revision); put("engine", engine); put("endpoint", endpoint); put("key", key)
        put("model", model); put("provider", provider); put("voice", voice); put("dictation", dictation)
        put("asrKey", asrKey); put("asrResource", asrResource); put("duplexKey", duplexKey)
        put("duplexVoice", duplexVoice); put("instructions", instructions)
    }.toString()
    companion object {
        fun decode(text: String): VoiceConfiguration {
            val j = JSONObject(text)
            return VoiceConfiguration(j.getString("revision"), j.getString("engine"), j.getString("endpoint"),
                j.getString("key"), j.getString("model"), j.getString("provider"), j.getString("voice"),
                j.getString("dictation"), j.getString("asrKey"), j.getString("asrResource"),
                j.getString("duplexKey"), j.getString("duplexVoice"), j.getString("instructions"))
        }
    }
}

internal class FrozenVoiceSpeech(val configuration: VoiceConfiguration, val reference: String? = null) {
    override fun toString() = "FrozenVoiceSpeech(redacted)"
}

internal class ControllerVoiceRuntime(context: Context) {
    private val vault = ModelSettingsStore(context.applicationContext, "voice-api-key", "voice-credentials-v1")
    val personal = MimoPersonalVoices.forContext(context.applicationContext)
    private val mutable = MutableStateFlow<VoiceConfiguration?>(null)
    val configuration = mutable.asStateFlow()
    private var failed = false
    private val lock = Any()
    private fun envelope(value: String) = ModelSettings("https://voice-credentials.invalid/v1/responses", "voice-credential", value, false)

    suspend fun load() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            try {
                val stored = vault.load().apiKey
                val loaded = if (stored.isEmpty()) VoiceConfiguration() else VoiceConfiguration.decode(stored)
                validate(loaded)
                mutable.value = loaded
                failed = false
            } catch (_: Exception) { failed = true; mutable.value = null }
        }
        personal.load()
    }

    /** Explicit save is also the recovery path; no unreadable credential silently becomes SYSTEM. */
    suspend fun save(value: VoiceConfiguration) = withContext(Dispatchers.IO) {
        validate(value)
        synchronized(lock) {
            val next = value.copy(revision = UUID.randomUUID().toString())
            failed = true; mutable.value = null
            vault.save(envelope(next.encode()))
            check(vault.load().apiKey == next.encode()) { "voice_save_unconfirmed" }
            mutable.value = next; failed = false
        }
    }

    fun snapshot(): VoiceConfiguration = synchronized(lock) {
        check(!failed) { "voice_credentials_unavailable" }
        checkNotNull(mutable.value) { "voice_settings_not_loaded" }
    }
    fun current(value: VoiceConfiguration): Boolean = synchronized(lock) {
        !failed && mutable.value?.revision == value.revision
    }
    fun prepareSpeech(): FrozenVoiceSpeech {
        val value = snapshot()
        if (value.engine != "SYSTEM") {
            require(value.key.isNotBlank() && value.endpoint.isNotBlank() && value.model.isNotBlank() &&
                value.provider.isNotBlank() && value.voice.isNotBlank()) { "voice_not_configured" }
            value.speech()
        }
        return FrozenVoiceSpeech(value)
    }
    /** Called only after a one-request consent, never as a consequence of selecting a local ID. */
    suspend fun authorizeReference(prepared: FrozenVoiceSpeech): FrozenVoiceSpeech = withContext(Dispatchers.IO) {
        val value = prepared.configuration
        check(current(value)) { "voice_configuration_changed" }
        require(value.engine == "MIMO" && value.voice.startsWith(MimoPersonalVoices.PREFIX))
        val reference = personal.reference(value.voice, value.referenceOwner())
        check(current(value)) { "voice_configuration_changed" }
        FrozenVoiceSpeech(value, reference)
    }
    private fun validate(v: VoiceConfiguration) {
        require(v.engine == "SYSTEM" || SpeechEngine.entries.any { it.name == v.engine })
        require(v.dictation in setOf("SYSTEM", "DOUBAO"))
        require(listOf(v.key, v.asrKey, v.duplexKey).none { '\r' in it || '\n' in it })
        if (v.engine != "SYSTEM") {
            require(v.key.isNotBlank() && v.model.isNotBlank() && v.provider.isNotBlank() && v.voice.isNotBlank())
            val uri = java.net.URI(v.endpoint)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
                uri.rawFragment == null && uri.rawQuery == null)
        }
        if (v.dictation == "DOUBAO") require(v.asrKey.isNotBlank() && v.asrResource in DoubaoAsrProtocol.resources)
        if (v.voice.startsWith(MimoPersonalVoices.PREFIX))
            require(v.engine == "MIMO" && v.model == MimoPersonalVoices.MODEL)
        require(v.duplexVoice.isNotBlank())
        envelope(v.encode()).validate()
    }
}
