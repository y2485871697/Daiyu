package app.daiyu.android.voice.providers

import org.junit.Assert.*
import org.junit.Test

class DoubaoVoiceIdentityTest {
    @Test fun onlyCompletePersonalIdentitiesAreAccepted() {
        for (id in listOf("S_purchased-1", "Daiyu" + "a0".repeat(16)))
            assertTrue(id, DoubaoVoiceIdentity.isPersonal(id))
        for (id in listOf("S_", "Daiyu", "DaiyuClone" + "a".repeat(32),
            "Daiyu" + "A".repeat(32), "Daiyu" + "aA".repeat(16),
            "Daiyu" + "a".repeat(31), "Daiyu" + "a".repeat(33),
            "foreign" + "a".repeat(32), "daiyu" + "a".repeat(32))) {
            assertFalse(id, DoubaoVoiceIdentity.isPersonal(id))
            val voice = DoubaoPersonalVoice(id, DoubaoPersonalVoice.accountFingerprint("key"), 2, setOf(4), true)
            assertFalse(id, voice.availableFor("key"))
        }
    }

    @Test fun malformedReservedIdentitiesNeverBecomeOrdinarySpeakers() {
        val config = SpeechProviderConfig(SpeechEngine.DOUBAO, "https://openspeech.bytedance.com", "key", "seed-tts-2.0", "provider")
        for (id in listOf("S_", "Daiyu", "DaiyuClone" + "a".repeat(32), "Daiyu" + "A".repeat(32))) {
            assertThrows(id, VoiceProviderException::class.java) { DoubaoSpeech.request(config, "text", id) }
        }
    }

    @Test fun unsupportedCustomShapedIdentitiesNeverReachTransport() = kotlinx.coroutines.runBlocking {
        var requests = 0
        val client = SpeechSynthesisClient(VoiceHttpTransport { _, _ ->
            requests++
            VoiceHttpResponse(200, "audio/mpeg", byteArrayOf(0xff.toByte(), 0xfb.toByte(), 0x90.toByte(), 0))
        })
        val unsupported = listOf("foreign" + "a".repeat(32), "foreign" + "A".repeat(32),
            "daiyu" + "a".repeat(32), "DaiyuClone" + "a".repeat(32), "S_", "Daiyu")
        for (model in listOf("seed-tts-2.0", "seed-audio-1.0")) {
            val config = SpeechProviderConfig(SpeechEngine.DOUBAO, "https://openspeech.bytedance.com", "key", model, "provider")
            for (id in unsupported) {
                try {
                    client.synthesize(config, "text", id)
                    fail("Unsupported custom identity reached synthesis")
                } catch (_: VoiceProviderException) {
                    assertEquals(id, 0, requests)
                }
            }
        }
        assertEquals(0, requests)
    }

    @Test fun officialAndSupportedPersonalSpeakersStillReachTransport() = kotlinx.coroutines.runBlocking {
        val requests = mutableListOf<VoiceHttpRequest>()
        val client = SpeechSynthesisClient(VoiceHttpTransport { request, _ ->
            requests += request
            VoiceHttpResponse(200, "audio/mpeg", byteArrayOf(0xff.toByte(), 0xfb.toByte(), 0x90.toByte(), 0))
        })
        for (model in listOf("seed-tts-2.0", "seed-audio-1.0")) {
            val config = SpeechProviderConfig(SpeechEngine.DOUBAO, "https://openspeech.bytedance.com", "key", model, "provider")
            for (id in listOf("zh_female_xiaohe_uranus_bigtts", "BV001_streaming")) {
                assertTrue(client.synthesize(config, "text", id).isNotEmpty())
                assertNotEquals("seed-icl-2.0", requests.last().header("X-Api-Resource-Id"))
            }
        }
        val config = SpeechProviderConfig(SpeechEngine.DOUBAO, "https://openspeech.bytedance.com", "key", "seed-tts-2.0", "provider")
        for (id in listOf("S_purchased-1", "Daiyu" + "a".repeat(32))) {
            val personal = DoubaoPersonalVoice(id, DoubaoPersonalVoice.accountFingerprint("key"), 2, setOf(4), true)
            assertTrue(client.synthesize(config, "text", id, personal).isNotEmpty())
            assertEquals("seed-icl-2.0", requests.last().header("X-Api-Resource-Id"))
            assertEquals(id, org.json.JSONObject(requests.last().body).getJSONObject("req_params").getString("speaker"))
        }
        assertEquals(6, requests.size)
    }
}
