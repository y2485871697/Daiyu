package app.daiyu.android.voice

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64

/**
 * Pure counterpart of the frozen fork's voice/tts/SpeechPlaybackSettings.kt.
 * Resource catalogs may be shared; selection and per-model history namespaces MUST NOT be.
 * The key names are logical Prefs.Keys identifiers, not a claim about their old persisted values.
 * The parent maps these identifiers to its own storage; this class never reads global settings.
 */
enum class VoicePlaybackProfile(
    val modeKey: String,
    val providerKey: String,
    val modelKey: String,
    val voiceKey: String,
    val historyFile: String,
) {
    READ_ALOUD("AGENT_TTS_MODE", "AGENT_TTS_MODEL_PROVIDER_ID", "AGENT_TTS_MODEL_ID",
        "AGENT_TTS_VOICE", "read_aloud_voice_history"),
    CONVERSATION("AGENT_VOICE_CONVERSATION_MODE", "AGENT_VOICE_CONVERSATION_PROVIDER_ID",
        "AGENT_VOICE_CONVERSATION_MODEL_ID", "AGENT_VOICE_CONVERSATION_VOICE", "voice_conversation_voice_history");

    /** Identical selection semantics to the frozen source, including voice-only trimming. */
    fun snapshot(readString: (String) -> String): VoicePlaybackSettings = VoicePlaybackSettings(
        cloud = readString(modeKey) == "cloud",
        providerId = readString(providerKey),
        modelId = readString(modelKey),
        voiceId = readString(voiceKey).trim(),
    )
}

/** Selection only. No credentials, reference audio, authorization, or playback capability. */
data class VoicePlaybackSettings(
    val cloud: Boolean,
    val providerId: String,
    val modelId: String,
    val voiceId: String,
) {
    override fun toString(): String = "VoicePlaybackSettings(redacted)"
}

/** No fallback/copy from the other feature when one profile has not been configured. */
data class VoicePlaybackProfiles(
    val readAloud: VoicePlaybackSettings,
    val conversation: VoicePlaybackSettings,
) {
    fun settings(profile: VoicePlaybackProfile): VoicePlaybackSettings = when (profile) {
        VoicePlaybackProfile.READ_ALOUD -> readAloud
        VoicePlaybackProfile.CONVERSATION -> conversation
    }

    fun withSettings(profile: VoicePlaybackProfile, value: VoicePlaybackSettings): VoicePlaybackProfiles = when (profile) {
        VoicePlaybackProfile.READ_ALOUD -> copy(readAloud = value)
        VoicePlaybackProfile.CONVERSATION -> copy(conversation = value)
    }

    fun freeze(profile: VoicePlaybackProfile, configurationRevision: String): FrozenVoicePlaybackProfile =
        FrozenVoicePlaybackProfile(profile, configurationRevision, settings(profile))

    override fun toString(): String = "VoicePlaybackProfiles(redacted)"
}

/**
 * Immutable selection/revision envelope for parent-runtime injection. Decoding is NOT consent:
 * it never restores a personal-reference grant. Callers must also validate revision/account
 * against their live runtime before use. History remains external and keyed by profile.historyFile.
 */
data class FrozenVoicePlaybackProfile(
    val profile: VoicePlaybackProfile,
    val configurationRevision: String,
    val settings: VoicePlaybackSettings,
) {
    init {
        require(configurationRevision.isNotBlank()) { "voice_profile_revision_required" }
        val fields = listOf(configurationRevision, settings.providerId, settings.modelId, settings.voiceId)
        // UTF-8 replaces unpaired surrogates with '?', while writeUTF emits three bytes each.
        // Reject malformed UTF-16 before measuring; keep the existing versioned codec unchanged.
        require(fields.all(::wellFormedUtf16)) { "voice_profile_invalid_unicode" }
        require(fields.all { it.toByteArray(Charsets.UTF_8).size <= MAX_FIELD_BYTES }) { "voice_profile_field_too_large" }
    }

    /** Versioned length-delimited codec, independent of Android/JSON/global preferences. */
    fun encode(): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
            out.writeByte(VERSION)
            out.writeUTF(profile.name)
            out.writeUTF(configurationRevision)
            out.writeByte(if (settings.cloud) 1 else 0)
            out.writeUTF(settings.providerId)
            out.writeUTF(settings.modelId)
            out.writeUTF(settings.voiceId)
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray())
    }

    override fun toString(): String = "FrozenVoicePlaybackProfile(redacted)"

    companion object {
        private const val MAGIC = 0x50333256 // P32V
        private const val VERSION = 1
        private const val MAX_FIELD_BYTES = 8_192
        private const val MAX_ENCODED_LENGTH = 100_000

        private fun wellFormedUtf16(value: String): Boolean {
            var i = 0
            while (i < value.length) {
                val char = value[i++]
                if (char.isHighSurrogate()) {
                    if (i == value.length || !value[i++].isLowSurrogate()) return false
                } else if (char.isLowSurrogate()) return false
            }
            return true
        }

        /** A profile tag mismatch is an error, never an implicit migration or cross-profile fallback. */
        fun decode(encoded: String, expectedProfile: VoicePlaybackProfile): FrozenVoicePlaybackProfile {
            require(encoded.length in 1..MAX_ENCODED_LENGTH) { "voice_profile_invalid" }
            try {
                val bytes = Base64.getDecoder().decode(encoded)
                val input = DataInputStream(ByteArrayInputStream(bytes))
                val value = input.use {
                    require(it.readInt() == MAGIC && it.readUnsignedByte() == VERSION)
                    val profile = VoicePlaybackProfile.valueOf(it.readUTF())
                    require(profile == expectedProfile)
                    val revision = it.readUTF()
                    val cloud = it.readUnsignedByte().also { flag -> require(flag in 0..1) } == 1
                    val settings = VoicePlaybackSettings(cloud, it.readUTF(), it.readUTF(), it.readUTF())
                    require(it.available() == 0)
                    FrozenVoicePlaybackProfile(profile, revision, settings)
                }
                // Also reject noncanonical encodings (including modified-UTF aliases).
                require(value.encode() == encoded)
                return value
            } catch (_: Exception) {
                throw IllegalArgumentException("voice_profile_invalid")
            }
        }
    }
}
