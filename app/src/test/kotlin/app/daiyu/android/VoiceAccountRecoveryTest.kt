package app.daiyu.android

import app.daiyu.android.model.ProviderHeaders
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VoiceAccountRecoveryTest {
    private fun doubao(key: String = "fixture-account-A") = VoiceConfiguration(
        revision = "saved", engine = "DOUBAO", endpoint = "https://openspeech.bytedance.com",
        key = key, provider = "fixture-provider", model = "seed-tts-2.0", voice = "zh_female_vv_uranus_bigtts")

    @Test fun oldSchemaTwoWithoutAccountEntryRestoresSavedAccountAndKeepsProfiles() {
        val reading = doubao()
        val original = VoiceProfileEnvelope(mapOf(VoiceProfile.READ_ALOUD to reading,
            VoiceProfile.CONVERSATION to VoiceConfiguration()))
        val old = JSONObject(original.encode()).apply { remove("accounts") }.toString()
        val restored = VoiceProfileEnvelope.decode(old).recoverPersonalAccounts()
        assertEquals(reading, restored.accounts["DOUBAO"])
        assertEquals(original.profiles, restored.profiles)
        assertNull(restored.legacy)
        assertEquals(restored.accounts, VoiceProfileEnvelope.decode(restored.encode()).accounts)
    }

    @Test fun legacyAccountIsVisibleWithoutConsumingOrAssigningAPlaybackProfile() {
        val legacy = doubao()
        val restored = VoiceProfileEnvelope.decode(legacy.encode()).recoverPersonalAccounts()
        assertEquals(legacy, restored.accounts["DOUBAO"])
        assertEquals(legacy, restored.legacy)
        assertTrue(restored.profiles.isEmpty())
    }

    @Test fun explicitPersonalAccountAlwaysWinsOverOtherSavedProfiles() {
        val explicit = doubao("fixture-explicit")
        val original = VoiceProfileEnvelope(mapOf(VoiceProfile.READ_ALOUD to doubao()), accounts = mapOf("DOUBAO" to explicit))
        assertSame(original, original.recoverPersonalAccounts())
        assertEquals(explicit, original.recoverPersonalAccounts().accounts["DOUBAO"])
    }

    @Test fun differentSavedKeysAreNotSilentlyChosen() {
        val original = VoiceProfileEnvelope(mapOf(VoiceProfile.READ_ALOUD to doubao(),
            VoiceProfile.CONVERSATION to doubao("fixture-account-B")))
        assertTrue(original.recoverPersonalAccounts().accounts.isEmpty())
        assertEquals(original.profiles, original.recoverPersonalAccounts().profiles)
    }

    @Test fun effectiveHeaderIdentityNotModelOrProviderLabelDeterminesAccount() {
        val headers = ProviderHeaders.parse("{\"x-api-key\":\"fixture-effective\"}")
        val reading = doubao().copy(customHeaders = headers)
        val conversation = doubao("fixture-other-placeholder").copy(customHeaders = headers,
            model = "another-model", provider = "another-label")
        val original = VoiceProfileEnvelope(mapOf(VoiceProfile.READ_ALOUD to reading,
            VoiceProfile.CONVERSATION to conversation))
        assertEquals(reading, original.recoverPersonalAccounts().accounts["DOUBAO"])
    }

    @Test fun mimoKeepsProviderAndCustomHeaderIdentitySeparate() {
        val first = doubao().copy(engine = "MIMO", endpoint = "https://api.xiaomimimo.com/v1",
            model = "mimo-v2.5-tts", voice = "mimo_default")
        val original = VoiceProfileEnvelope(mapOf(VoiceProfile.READ_ALOUD to first,
            VoiceProfile.CONVERSATION to first.copy(provider = "another-provider")))
        assertTrue(original.recoverPersonalAccounts().accounts.isEmpty())
        val restored = VoiceProfileEnvelope(mapOf(VoiceProfile.READ_ALOUD to first)).recoverPersonalAccounts()
        assertEquals(first, restored.accounts["MIMO"])
        assertSame(restored, restored.recoverPersonalAccounts())
    }

    @Test fun freshDefaultsRemainUnconfiguredAndNeverInventCredentials() {
        val restored = VoiceProfileEnvelope.decode("").recoverPersonalAccounts()
        assertTrue(restored.accounts.isEmpty())
        assertEquals(setOf(VoiceProfile.READ_ALOUD, VoiceProfile.CONVERSATION), restored.profiles.keys)
        assertTrue(restored.profiles.values.all { it.engine == "SYSTEM" && it.key.isEmpty() })
    }
}
