package app.daiyu.android

import android.content.Context
import app.daiyu.android.storage.ModelSettings
import app.daiyu.android.storage.ModelSettingsStore
import app.daiyu.android.voice.personal.MimoPersonalVoices
import app.daiyu.android.voice.personal.PersonalVoiceCascadeBinding
import app.daiyu.android.voice.personal.PersonalVoiceCascadeGrant
import java.util.concurrent.CopyOnWriteArrayList
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

/** Identical case-insensitive effective-key merge to account identity and DoubaoSpeech.request. */
internal fun effectiveDoubaoAuthenticationKey(config: SpeechProviderConfig): String =
    headers(config, "X-Api-Key" to config.apiKey).entries
        .firstOrNull { it.key.equals("X-Api-Key", ignoreCase = true) }?.value.orEmpty()

internal typealias VoiceProfile = app.daiyu.android.voice.VoicePlaybackProfile

/** Called at the transport boundary, once per actual HTTP execute, never at queue admission. */
internal fun interface PersonalCascadeUpload {
    fun consume(requestId: String, exactText: String): Long
}

internal class FrozenVoiceSpeech(
    val configuration: VoiceConfiguration,
    val reference: String? = null,
    val profile: VoiceProfile = VoiceProfile.READ_ALOUD,
    val doubaoPersonal: DoubaoPersonalVoice? = null,
    val doubaoResolver: (() -> DoubaoPersonalVoice?)? = null,
    val upload: PersonalCascadeUpload? = null,
    val playbackLimitMs: Long = 120_000L,
) {
    val isPersonal: Boolean get() = configuration.voice.startsWith(MimoPersonalVoices.PREFIX) ||
        (configuration.engine == "DOUBAO" && (configuration.voice.startsWith("S_") || configuration.voice.startsWith("etaClone")))
    fun withUpload(value: PersonalCascadeUpload, limitMs: Long) = FrozenVoiceSpeech(configuration, reference,
        profile, doubaoPersonal, doubaoResolver, value, limitMs)
    override fun toString() = "FrozenVoiceSpeech(redacted)"
}

/** Versioned encrypted envelope; absence is unconfigured, never another profile's selection. */
internal class VoiceProfileEnvelope(val profiles: Map<VoiceProfile, VoiceConfiguration>, val legacy: VoiceConfiguration? = null) {
    fun encode(): String = JSONObject().apply {
        put("schema", 2)
        put("profiles", JSONObject().apply { profiles.forEach { (profile, config) -> put(profile.name, JSONObject(config.encode())) } })
        legacy?.let { put("legacyPending", JSONObject(it.encode())) }
    }.toString()
    companion object {
        fun decode(text: String): VoiceProfileEnvelope {
            if (text.isEmpty()) return VoiceProfileEnvelope(VoiceProfile.entries.associateWith { VoiceConfiguration() })
            val json = JSONObject(text)
            if (!json.has("schema")) return VoiceProfileEnvelope(emptyMap(), VoiceConfiguration.decode(text))
            require(json.getInt("schema") == 2) { "voice_schema_unavailable" }
            val values = json.getJSONObject("profiles")
            return VoiceProfileEnvelope(VoiceProfile.entries.mapNotNull { profile ->
                if (values.has(profile.name)) profile to VoiceConfiguration.decode(values.getJSONObject(profile.name).toString()) else null
            }.toMap(), json.optJSONObject("legacyPending")?.let { VoiceConfiguration.decode(it.toString()) })
        }
    }
}

internal class ControllerVoiceRuntime(
    context: Context,
    /** State-only lookup supplied by the account leaf. It MUST NOT do network or disk I/O. */
    private val doubaoPersonalResolver: ((VoiceConfiguration) -> DoubaoPersonalVoice?)? = null,
) {
    private val vault = ModelSettingsStore(context.applicationContext, "voice-api-key", "voice-credentials-v1")
    val personal = MimoPersonalVoices.forContext(context.applicationContext)
    private val mutable = MutableStateFlow<VoiceConfiguration?>(null)
    val configuration = mutable.asStateFlow() // Compatibility: dictation/duplex/manual = READ_ALOUD.
    private val conversation = MutableStateFlow<VoiceConfiguration?>(null)
    val conversationConfiguration = conversation.asStateFlow()
    private val legacy = MutableStateFlow<VoiceConfiguration?>(null)
    val legacyConfiguration = legacy.asStateFlow()
    @Volatile private var failed = false
    @Volatile private var state = VoiceProfileEnvelope(emptyMap())
    private val lock = Any() // Persistence only. Never invoke listeners/cancel/drain under this lock.
    private val listeners = CopyOnWriteArrayList<(VoiceProfile?) -> Unit>()
    private data class ReferenceRecord(val voice: MimoPersonalVoices.Voice, val digest: String)
    private val references = java.util.concurrent.ConcurrentHashMap<String, ReferenceRecord>()
    private fun envelope(value: String) = ModelSettings("https://voice-credentials.invalid/v1/responses", "voice-credential", value, false)
    private fun publish(value: VoiceProfileEnvelope) {
        state = value
        mutable.value = value.profiles[VoiceProfile.READ_ALOUD]
        conversation.value = value.profiles[VoiceProfile.CONVERSATION]
        legacy.value = value.legacy
    }
    fun profileConfiguration(profile: VoiceProfile) = when (profile) {
        VoiceProfile.READ_ALOUD -> configuration
        VoiceProfile.CONVERSATION -> conversationConfiguration
    }
    fun observeInvalidation(listener: (VoiceProfile?) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }
    /** Account leaf must call this BEFORE replacing/removing an account record. No control lock. */
    fun invalidateSpeech(profile: VoiceProfile? = null) {
        var failure: Throwable? = null
        listeners.forEach { listener ->
            try { listener(profile) } catch (problem: Throwable) { if (failure == null) failure = problem }
        }
        failure?.let { throw it }
    }

    suspend fun load() = withContext(Dispatchers.IO) {
        invalidateSpeech()
        synchronized(lock) {
            try {
                val loaded = VoiceProfileEnvelope.decode(vault.load().apiKey)
                loaded.profiles.values.forEach(::validate)
                loaded.legacy?.let(::validate)
                publish(loaded); failed = false
            } catch (_: Exception) { failed = true; publish(VoiceProfileEnvelope(emptyMap())) }
        }
        personal.load()
    }

    /** Missing/legacy profiles never inherit another selection. Explicit save also repairs a failed vault. */
    suspend fun save(value: VoiceConfiguration, profile: VoiceProfile = VoiceProfile.READ_ALOUD,
        consumeLegacy: Boolean = false) = withContext(Dispatchers.IO) {
        validate(value)
        envelope(VoiceProfileEnvelope(state.profiles + (profile to value), state.legacy.takeUnless { consumeLegacy }).encode()).validate()
        // Fence new work first, then revoke and drain WITHOUT holding the storage/control lock.
        failed = true
        invalidateSpeech(profile)
        synchronized(lock) {
            val next = VoiceProfileEnvelope(state.profiles + (profile to value.copy(revision = UUID.randomUUID().toString())),
                state.legacy.takeUnless { consumeLegacy })
            val encoded = next.encode()
            try {
                vault.save(envelope(encoded))
                check(vault.load().apiKey == encoded) { "voice_save_unconfirmed" }
                publish(next); failed = false
            } catch (failure: Exception) {
                publish(VoiceProfileEnvelope(emptyMap(), state.legacy)); throw failure
            }
        }
    }
    /** Atomic explicit move into ONE chosen profile, without copying credentials into both. */
    suspend fun migrateLegacy(profile: VoiceProfile) {
        val previous = checkNotNull(legacy.value) { "voice_no_legacy_configuration" }
        save(previous, profile, consumeLegacy = true)
    }
    fun snapshot(profile: VoiceProfile = VoiceProfile.READ_ALOUD): VoiceConfiguration {
        check(!failed) { "voice_credentials_unavailable" }
        return checkNotNull(state.profiles[profile]) { "voice_profile_not_configured_explicit_migration_required" }
    }
    fun current(value: VoiceConfiguration, profile: VoiceProfile = VoiceProfile.READ_ALOUD): Boolean =
        !failed && state.profiles[profile] == value
    fun current(value: FrozenVoiceSpeech): Boolean = current(value.configuration, value.profile)

    fun prepareSpeech(profile: VoiceProfile = VoiceProfile.READ_ALOUD): FrozenVoiceSpeech {
        val value = snapshot(profile)
        if (value.engine != "SYSTEM") {
            require(value.key.isNotBlank() && value.endpoint.isNotBlank() && value.model.isNotBlank() &&
                value.provider.isNotBlank() && value.voice.isNotBlank()) { "voice_not_configured" }
            value.speech()
        }
        val resolver = if (value.engine == "DOUBAO" && (value.voice.startsWith("S_") || value.voice.startsWith("etaClone")))
            doubaoPersonalResolver?.let { lookup -> { lookup(value) } } else null
        return FrozenVoiceSpeech(value, profile = profile, doubaoPersonal = resolver?.invoke(), doubaoResolver = resolver)
    }
    /** Local-only freeze; authorization is still required before any HTTP request. */
    suspend fun authorizeReference(prepared: FrozenVoiceSpeech): FrozenVoiceSpeech = withContext(Dispatchers.IO) {
        val value = prepared.configuration
        check(current(prepared)) { "voice_configuration_changed" }
        require(value.engine == "MIMO" && value.voice.startsWith(MimoPersonalVoices.PREFIX))
        val reference = personal.reference(value.voice, value.referenceOwner())
        val record = checkNotNull(personal.state.value.firstOrNull { it.id == value.voice && it.providerId == value.referenceOwner() })
        references[value.voice] = ReferenceRecord(record, PersonalVoiceCascadeGrant.digest(reference))
        check(current(prepared)) { "voice_configuration_changed" }
        FrozenVoiceSpeech(value, reference, prepared.profile, prepared.doubaoPersonal, prepared.doubaoResolver,
            prepared.upload, prepared.playbackLimitMs)
    }
    /** Cheap live state only. Suitable for grant callbacks: no I/O, runtime lock, or grant re-entry. */
    fun personalBinding(prepared: FrozenVoiceSpeech): PersonalVoiceCascadeBinding? {
        if (!current(prepared)) return null
        val value = state.profiles[prepared.profile] ?: return null
        val digest = if (value.voice.startsWith(MimoPersonalVoices.PREFIX)) {
            val cached = references[value.voice] ?: return null
            if (personal.state.value.none { it == cached.voice && it.providerId == value.referenceOwner() }) return null
            cached.digest
        } else {
            if (value.engine != "DOUBAO") return null
            val live = prepared.doubaoResolver?.invoke() ?: return null
            if (live.id != value.voice || !live.availableFor(effectiveDoubaoAuthenticationKey(value.speech()))) return null
            PersonalVoiceCascadeGrant.digest(listOf(live.id, live.account, live.status.toString(),
                live.models.sorted().joinToString(","), live.accepted.toString()).joinToString("|"))
        }
        val account = if (value.engine == "DOUBAO") {
            val actualKey = effectiveDoubaoAuthenticationKey(value.speech())
            PersonalVoiceCascadeGrant.digest("${DoubaoSpeech.UNIDIRECTIONAL_URL}|${actualKey.length}:$actualKey")
        } else value.referenceOwner()
        return PersonalVoiceCascadeBinding(account, value.revision, value.voice, digest)
    }
    /** Full local reference check happens off-main, OUTSIDE the grant lock, before consume/upload. */
    fun referenceStillCurrent(prepared: FrozenVoiceSpeech): Boolean = current(prepared) &&
        (prepared.reference == null || personal.reference(prepared.configuration.voice,
            prepared.configuration.referenceOwner()) == prepared.reference)
    suspend fun removePersonal(id: String) = withContext(Dispatchers.IO) {
        references.remove(id)
        invalidateSpeech() // Revoke synchronously, before deleting bytes/index.
        personal.remove(id)
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
