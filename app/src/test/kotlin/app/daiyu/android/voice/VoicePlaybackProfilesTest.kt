package app.daiyu.android.voice

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class VoicePlaybackProfilesTest {
    private val read = VoicePlaybackSettings(true, "read-provider", "read-model", "read-voice")
    private val conversation = VoicePlaybackSettings(false, "conversation-provider", "conversation-model", "conversation-voice")

    @Test fun frozenSourceKeysAndHistoryNamespacesRemainDisjoint() {
        val read = VoicePlaybackProfile.READ_ALOUD
        val talk = VoicePlaybackProfile.CONVERSATION
        val readKeys = setOf(read.modeKey, read.providerKey, read.modelKey, read.voiceKey)
        val talkKeys = setOf(talk.modeKey, talk.providerKey, talk.modelKey, talk.voiceKey)
        assertEquals(4, readKeys.size)
        assertEquals(4, talkKeys.size)
        assertTrue(readKeys.intersect(talkKeys).isEmpty())
        assertEquals("AGENT_TTS_MODEL_PROVIDER_ID", read.providerKey)
        assertEquals("AGENT_VOICE_CONVERSATION_PROVIDER_ID", talk.providerKey)
        assertEquals("read_aloud_voice_history", read.historyFile)
        assertEquals("voice_conversation_voice_history", talk.historyFile)
    }

    @Test fun snapshotReadsOnlyItsOwnKeysAndRetainsFrozenTrimmingRules() {
        val read = VoicePlaybackProfile.READ_ALOUD
        val values = mapOf(read.modeKey to "cloud", read.providerKey to " provider ",
            read.modelKey to " model ", read.voiceKey to "  voice  ")
        val seen = mutableListOf<String>()
        val snapshot = read.snapshot { seen += it; values.getValue(it) }
        assertEquals(VoicePlaybackSettings(true, " provider ", " model ", "voice"), snapshot)
        assertEquals(values.keys, seen.toSet())
        assertFalse(read.snapshot { if (it == read.modeKey) "CLOUD" else "" }.cloud)
    }

    @Test fun changingConversationDoesNotMutateReadAloudOrAnExistingFrozenSelection() {
        val profiles = VoicePlaybackProfiles(read, conversation)
        val frozen = profiles.freeze(VoicePlaybackProfile.CONVERSATION, "rev-1")
        val changed = profiles.withSettings(VoicePlaybackProfile.CONVERSATION, conversation.copy(voiceId = "next"))
        assertEquals(read, changed.settings(VoicePlaybackProfile.READ_ALOUD))
        assertEquals(conversation, profiles.settings(VoicePlaybackProfile.CONVERSATION))
        assertEquals(conversation, frozen.settings)
        assertEquals("next", changed.conversation.voiceId)
        assertEquals("rev-1", frozen.configurationRevision)
    }

    @Test fun versionedCodecRoundTripsBothProfilesWithoutTrimmingOrCrossProfileFallback() {
        for (profile in VoicePlaybackProfile.values()) {
            val selected = VoicePlaybackSettings(true, "提供商:\u0000", "模型/\n", " voice-🎵 ")
            val frozen = FrozenVoicePlaybackProfile(profile, "revision-1", selected)
            assertEquals(frozen, FrozenVoicePlaybackProfile.decode(frozen.encode(), profile))
            val other = VoicePlaybackProfile.values().first { it != profile }
            assertThrows(IllegalArgumentException::class.java) { FrozenVoicePlaybackProfile.decode(frozen.encode(), other) }
        }
    }

    @Test fun malformedVersionTrailingDataAndOversizedEnvelopesFailClosed() {
        val profile = VoicePlaybackProfile.READ_ALOUD
        val frozen = FrozenVoicePlaybackProfile(profile, "revision", read)
        val bytes = Base64.getDecoder().decode(frozen.encode())
        val changedVersion = bytes.copyOf().also { it[4] = 2 }
        val trailing = bytes + byteArrayOf(0)
        val invalidMagic = bytes.copyOf().also { it[0] = 0 }
        listOf(changedVersion, trailing, invalidMagic, bytes.copyOf(5)).forEach {
            assertThrows(IllegalArgumentException::class.java) {
                FrozenVoicePlaybackProfile.decode(Base64.getEncoder().encodeToString(it), profile)
            }
        }
        listOf("", "not base64", "A".repeat(100_001)).forEach {
            assertThrows(IllegalArgumentException::class.java) { FrozenVoicePlaybackProfile.decode(it, profile) }
        }
        assertThrows(IllegalArgumentException::class.java) { FrozenVoicePlaybackProfile(profile, " ", read) }
        assertThrows(IllegalArgumentException::class.java) {
            FrozenVoicePlaybackProfile(profile, "revision", read.copy(voiceId = "v".repeat(8_193)))
        }
    }

    @Test fun malformedSurrogatesCannotExpandPastTheDecodeEnvelopeBound() {
        val profile = VoicePlaybackProfile.CONVERSATION
        // Regression: UTF-8 counted 8192 replacement '?' bytes, writeUTF emitted 24576 bytes/field.
        for (bad in listOf("\uD800".repeat(8192), "\uDC00".repeat(8192), "a\uD800b", "\uDC00\uD800")) {
            assertThrows(IllegalArgumentException::class.java) {
                FrozenVoicePlaybackProfile(profile, bad, VoicePlaybackSettings(true, bad, bad, bad))
            }
            assertThrows(IllegalArgumentException::class.java) {
                FrozenVoicePlaybackProfile(profile, "valid", read.copy(voiceId = bad))
            }
        }
    }

    @Test fun maximumValidUtf8FieldsIncludingModifiedUtfWorstCasesStillRoundTrip() {
        val profile = VoicePlaybackProfile.CONVERSATION
        // NUL is two modified-UTF bytes; supplementary scalar values are six instead of four.
        for (field in listOf("\u0000".repeat(8192), "🎵".repeat(2048), "x".repeat(8192))) {
            val valid = FrozenVoicePlaybackProfile(profile, field,
                VoicePlaybackSettings(true, field, field, field))
            assertTrue(valid.encode().length <= 100_000)
            assertEquals(valid, FrozenVoicePlaybackProfile.decode(valid.encode(), profile))
        }
    }

    @Test fun selectionAndFrozenDiagnosticsDoNotPrintIdentifiers() {
        assertEquals("VoicePlaybackSettings(redacted)", read.toString())
        assertEquals("VoicePlaybackProfiles(redacted)", VoicePlaybackProfiles(read, conversation).toString())
        assertEquals("FrozenVoicePlaybackProfile(redacted)",
            FrozenVoicePlaybackProfile(VoicePlaybackProfile.READ_ALOUD, "private-revision", read).toString())
    }
}
