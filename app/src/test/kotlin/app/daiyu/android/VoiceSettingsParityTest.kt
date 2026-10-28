package app.daiyu.android

import app.daiyu.android.model.ProviderHeaders
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.model.catalog.CatalogModelParameters
import app.daiyu.android.model.catalog.CatalogModelSelection
import app.daiyu.android.model.catalog.SavedCatalogModel
import app.daiyu.android.storage.ProviderEditorSlot
import app.daiyu.android.ui.screens.settings.SettingsModelChoice
import app.daiyu.android.ui.screens.settings.SettingsSpeechModelChoice
import app.daiyu.android.ui.screens.settings.SettingsVoiceProviderChoice
import app.daiyu.android.voice.providers.*
import app.daiyu.android.voice.account.PersonalVoices
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VoiceSettingsParityTest {
    private fun cloud() = VoiceConfiguration(revision = "account", engine = "MIMO", provider = "provider-A",
        endpoint = "https://speech.invalid/v1", key = "fixture-only", model = "mimo-v2.5-tts", voice = "mimo_default",
        customHeaders = ProviderHeaders.parse("{\"x-fixture\":\"fixture-only\"}"), modelIdentity = "local-model-A")

    @Test fun speechPickerKeysKeepLocalIdentityAndSeparateBuiltInModelIds() {
        val editor = ProviderEditorSlot("provider-A", 1)
        val connection = SettingsVoiceProviderChoice(editor, "Speech", "https://speech.invalid", 1)
        fun cached(identity: String) = SettingsSpeechModelChoice(connection, "tts-1", "Speech",
            SettingsModelChoice(editor, "Speech", ProviderProtocol.CHAT_COMPLETIONS,
                CatalogModelSelection("catalog-A", 1,
                    SavedCatalogModel("tts-1", "Speech", CatalogModelParameters(localIdentity = identity)))))
        val first = cached("identity-A")
        val second = cached("identity-B")
        assertEquals("local:identity-A", first.speechPickerId)
        assertNotEquals(first.speechPickerId, second.speechPickerId)
        assertEquals(first.speechPickerId, first.copy(name = "Renamed").speechPickerId)
        val builtIn = SettingsSpeechModelChoice(connection, "tts-1", "Built-in")
        assertEquals("model:tts-1", builtIn.speechPickerId)
        assertNotEquals(builtIn.speechPickerId, cached("tts-1").speechPickerId)
        assertNotEquals(builtIn.speechPickerId, builtIn.copy(model = "tts-1-hd").speechPickerId)
    }

    @Test fun personalSlotsSortUnusedFirstAndKeepFriendlyLabelsAndQuotaOnce() {
        val used = PersonalVoices.Voice("S_used", "A made voice", "account", status = 2,
            models = setOf(4), remaining = 3)
        val unused = PersonalVoices.Voice("S_unused", "S_unused", "account", catalogState = "Unknown", remaining = 2)
        val other = used.copy(id = "etaClone123", name = "Postpaid")
        assertEquals(listOf(unused, used), personalVoiceSlots(listOf(used, other, unused), true, false))
        assertEquals(emptyList<PersonalVoices.Voice>(), personalVoiceSlots(listOf(used, unused), true, true))
        assertEquals(listOf(used, other), personalVoiceSlots(listOf(unused, other, used), false, false))
        assertEquals("音色名额 1", personalVoiceSlotTitle(unused, 0))
        assertEquals(used.name, personalVoiceSlotTitle(used, 1))
        assertEquals("未使用名额 · 剩余 2 次", voiceSlotSummary(unused))
        assertTrue(voiceSlotSummary(used).contains("剩余 3 次"))
        assertTrue(voiceSlotSummary(used).contains("再次制作可能覆盖已有声音"))
        assertTrue(voiceSlotSummary(used.copy(remaining = -1)).contains("次数待查询"))
    }

    @Test fun personalVoiceStatePreservesUncertainExpiredReclaimedAndTrainingBoundaries() {
        val voice = PersonalVoices.Voice("S_test", "Fixture", "account")
        assertTrue(voiceStatus(voice.copy(uncertain = true)).contains("提交结果未知"))
        assertEquals("已到期", voiceStatus(voice.copy(catalogState = "Expired")))
        assertEquals("已回收", voiceStatus(voice.copy(catalogState = "Reclaimed")))
        assertEquals("训练中", voiceStatus(voice.copy(status = 1)))
        assertEquals("已激活", voiceStatus(voice.copy(catalogState = "Active")))
        assertEquals("制作次数已用完", voiceStatus(voice.copy(remaining = 0)))
        assertFalse(voice.copy(uncertain = true).canTrain)
        assertFalse(voice.copy(status = 1).canTrain)
        assertFalse(voice.copy(catalogState = "Active").canTrain)
    }

    @Test fun personalVoiceSummaryShowsActualTrainingStateNotAcceptanceOrQueryErrors() {
        val voice = PersonalVoices.Voice("S_test", "Fixture", "account", status = 2)
        for (accepted in listOf(false, true)) for (error in listOf("", "查询未确认；不会重新训练，请手动查询")) {
            assertEquals("训练成功", voiceStatus(voice.copy(accepted = accepted, error = error)))
        }
        assertEquals("训练成功", voiceStatus(voice.copy(status = -1, catalogState = "Success")))
        assertEquals("训练失败", voiceStatus(voice.copy(status = 3, error = "service failure")))
        assertEquals("已激活", voiceStatus(voice.copy(status = 4, error = "query failed")))
        assertEquals("状态查询失败，请重试", voiceStatus(voice.copy(status = -1, error = "query failed")))
        assertTrue(voiceStatus(voice.copy(uncertain = true)).contains("提交结果未知"))
    }

    @Test fun speechSelectionPreservesRecognitionRealtimeAndRevision() {
        val current = VoiceConfiguration(revision = "profile", dictation = "DOUBAO", asrKey = "asr-only",
            asrResource = DoubaoAsrProtocol.resources.last(), duplexKey = "duplex-only", duplexProvider = "realtime-provider",
            duplexVoice = realtimeSpeechVoices.last().id, instructions = "local instructions")
        val next = current.withSpeech(cloud())
        assertEquals(current.revision, next.revision)
        assertEquals(current.dictation, next.dictation)
        assertEquals(current.asrKey, next.asrKey)
        assertEquals(current.asrResource, next.asrResource)
        assertEquals(current.duplexKey, next.duplexKey)
        assertEquals(current.duplexProvider, next.duplexProvider)
        assertEquals(current.duplexVoice, next.duplexVoice)
        assertEquals(current.instructions, next.instructions)
        assertEquals(cloud().customHeaders, next.customHeaders)
        assertEquals(cloud().modelIdentity, next.modelIdentity)
        assertEquals(cloud().voice, next.voice)
    }

    @Test fun savedLeafUpdateKeepsUnsubmittedDraftAndAdoptsCurrentRevision() {
        val previous = cloud().copy(revision = "old", asrKey = "old-key", instructions = "old instructions")
        val pending = previous.copy(model = "other-tts", modelIdentity = "other-local", voice = "other-voice")
        val saved = previous.copy(revision = "saved", asrKey = "saved-key", instructions = "saved instructions")
        val merged = mergeVoiceSettingsDraft(previous, pending, saved)
        assertEquals(saved.revision, merged.revision)
        assertEquals(saved.asrKey, merged.asrKey)
        assertEquals(saved.instructions, merged.instructions)
        assertEquals(pending.model, merged.model)
        assertEquals(pending.voice, merged.voice)
        assertEquals(pending.modelIdentity, merged.modelIdentity)
    }

    @Test fun profileAndIndependentAccountEnvelopeRoundTripWithoutSharingSelections() {
        val reading = cloud().copy(revision = "reading", voice = "冰糖")
        val conversation = cloud().copy(revision = "conversation", voice = "茉莉", asrKey = "conversation-key")
        val account = cloud().copy(revision = "personal-account", provider = "personal-provider")
        val encoded = VoiceProfileEnvelope(mapOf(VoiceProfile.READ_ALOUD to reading,
            VoiceProfile.CONVERSATION to conversation), accounts = mapOf("MIMO" to account)).encode()
        val restored = VoiceProfileEnvelope.decode(encoded)
        assertEquals(reading, restored.profiles[VoiceProfile.READ_ALOUD])
        assertEquals(conversation, restored.profiles[VoiceProfile.CONVERSATION])
        assertEquals(account, restored.accounts["MIMO"])
        assertNotEquals(restored.accounts["MIMO"], restored.profiles[VoiceProfile.READ_ALOUD])
    }

    @Test fun oldConfigurationWithoutNewFieldsDecodesAndHeadersReachSpeechRequest() {
        val original = cloud()
        val old = JSONObject(original.encode()).apply {
            remove("customHeaders"); remove("duplexProvider"); remove("modelIdentity"); remove("modelLabel")
        }
        val restored = VoiceConfiguration.decode(old.toString())
        assertTrue(restored.customHeaders.isEmpty)
        assertEquals("", restored.duplexProvider)
        assertEquals("", restored.modelIdentity)
        val request = SpeechProtocols.request(original.speech(), "fixture text", original.voice)
        assertEquals("fixture-only", request.header("x-fixture"))
        assertFalse(original.toString().contains("fixture-only"))
    }

    @Test fun readAloudFilterAcceptsSynthesisAndRejectsChatRecognitionAndCreation() {
        listOf("tts-1", "gpt-4o-mini-tts", "seed-tts-2.0", "mimo-v2.5-tts", "speech-2.8-hd",
            "stepaudio-2.5-tts", "qwen-audio-3.0-tts-flash", "canopylabs/orpheus-v1-english",
            "CosyVoice2", "MOSS-TTSD", "eleven_multilingual_v2", "eleven_flash_v2_5").forEach {
            assertTrue(it, SpeechModelCapabilities.readAloud(it))
        }
        listOf("gpt-4o", "whisper-1", "qwen-asr", "seed-audio-1.0", "mimo-v2.5-tts-voiceclone",
            "qwen3-tts-voicedesign", "tts-transcribe").forEach { assertFalse(it, SpeechModelCapabilities.readAloud(it)) }
    }

    @Test fun allThirteenEnginesResolveAndFishDoesNotInventAVoiceCatalog() {
        val examples = listOf(
            Triple("https://api.openai.com", "tts-1", SpeechEngine.OPENAI),
            Triple("https://openspeech.bytedance.com", "seed-tts-2.0", SpeechEngine.DOUBAO),
            Triple("https://api.xiaomimimo.com", "mimo-v2.5-tts", SpeechEngine.MIMO),
            Triple("https://api.minimax.io", "speech-2.8-hd", SpeechEngine.MINIMAX),
            Triple("https://api.stepfun.com", "step-tts-mini", SpeechEngine.STEP),
            Triple("https://dashscope.aliyuncs.com", "qwen-audio-3.0-tts-flash", SpeechEngine.QWEN),
            Triple("https://api.groq.com", "canopylabs/orpheus-v1-english", SpeechEngine.GROQ),
            Triple("https://api.x.ai", "tts", SpeechEngine.XAI),
            Triple("https://generativelanguage.googleapis.com", "gemini-tts", SpeechEngine.GEMINI),
            Triple("https://api.elevenlabs.io", "eleven_multilingual_v2", SpeechEngine.ELEVENLABS),
            Triple("https://api.fish.audio", "fish-speech", SpeechEngine.FISH),
            Triple("https://relay.invalid", "CosyVoice2", SpeechEngine.COSYVOICE),
            Triple("https://relay.invalid", "MOSS-TTSD", SpeechEngine.MOSS),
        )
        assertEquals(SpeechEngine.entries.toSet(), examples.map { it.third }.toSet())
        examples.forEach { (endpoint, model, engine) ->
            assertEquals(engine, SpeechModelCapabilities.engine(endpoint, model))
            val voices = SpeechVoices.catalog(engine, model)
            if (engine == SpeechEngine.FISH) assertTrue(voices.isEmpty()) else assertTrue(voices.isNotEmpty())
            assertEquals(voices.size, voices.map { it.id }.distinct().size)
        }
    }

    @Test fun builtInSpeechModelsDoNotNeedAnImportedChatCatalog() {
        val expected = mapOf(
            "https://openspeech.bytedance.com" to "seed-tts-2.0",
            "https://api.xiaomimimo.com" to "mimo-v2.5-tts",
            "https://api.minimax.io" to "speech-2.8-hd",
            "https://api.stepfun.com" to "step-tts-mini",
            "https://dashscope.aliyuncs.com/compatible-mode" to "qwen-audio-3.0-tts-flash",
            "https://api.groq.com" to "canopylabs/orpheus-v1-english",
        )
        expected.forEach { (endpoint, model) ->
            val models = SpeechModelCapabilities.builtInModels(endpoint)
            assertEquals(model, models.first().first)
            assertTrue(models.all { SpeechModelCapabilities.readAloud(it.first) })
        }
        assertTrue(SpeechModelCapabilities.builtInModels("https://relay.invalid").isEmpty())
        assertTrue(SpeechModelCapabilities.builtInModels("https://openspeech.bytedance.com.attacker.invalid").isEmpty())
    }

    @Test fun historyUsesLocalModelIdentityAndRealtimeKeepsItsOwnFourVoices() {
        assertEquals("local-model-A", cloud().historyModel)
        assertEquals(cloud().model, cloud().copy(modelIdentity = "").historyModel)
        assertEquals(listOf("Vivi", "小何", "云舟", "小天"), realtimeSpeechVoices.map { it.name })
        assertTrue(realtimeSpeechVoices.all { "jupiter" in it.id })
        assertFalse(realtimeSpeechVoices.any { DoubaoVoices.contains(it.id) })
        assertEquals(realtimeSpeechVoices.take(2), parseRealtimeSpeechVoices(realtimeSpeechVoices.take(2).joinToString { it.id }))
        assertTrue(parseRealtimeSpeechVoices("unknown voice").isEmpty())
    }
}
