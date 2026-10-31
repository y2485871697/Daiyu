package app.daiyu.android.delegation

import app.daiyu.android.model.GptSpeedMode
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SubAgentGptSpeedDefaultsTest {
    private val profile = SubAgentProfile("profile", "Profile", "review", providerId = "provider", modelId = "selection")
    private val key = SubAgentProfile.modelReasoningKey(profile.providerId, profile.modelId)
    private fun confirmed(value: SubAgentProfile) = SubAgentModelDefaults.confirmed(value, null,
        SubAgentParallelModel(value.providerId, "gpt-6.1-sol"), 2)
    private fun roundTrip(entry: SubAgentModelDefaults.Entry): SubAgentModelDefaults.Entry =
        SubAgentModelDefaults.decode(SubAgentModelDefaults.encode(mapOf(entry.key to entry))).getValue(entry.key)

    @Test fun legacyDisabledDefaultsRestoreOtherFieldsAndAlwaysEncodeTrue() {
        val expected = profile.copy(reasoning = ReasoningEffort.HIGH, parallelLimit = 2)
        val json = JSONObject(SubAgentModelDefaults.encode(mapOf(key to confirmed(expected))))
        json.getJSONArray("models").getJSONObject(0).put("enabled", false)
        val entry = SubAgentModelDefaults.decode(json.toString()).getValue(key)
        val restored = entry.restore(profile)
        assertEquals(ReasoningEffort.HIGH, restored.reasoning)
        assertTrue(restored.toJson().getBoolean("enabled"))
        val encoded = JSONObject(SubAgentModelDefaults.encode(mapOf(key to entry)))
        assertTrue(encoded.getJSONArray("models").getJSONObject(0).getBoolean("enabled"))
        for (invalid in listOf<Any>("false", 0, JSONObject.NULL)) {
            json.getJSONArray("models").getJSONObject(0).put("enabled", invalid)
            assertThrows(IllegalArgumentException::class.java) { SubAgentModelDefaults.decode(json.toString()) }
        }
    }

    @Test fun inheritedSpeedStaysInheritedAcrossConfirmationEncodingAndRestore() {
        val entry = roundTrip(confirmed(profile))
        assertTrue(entry.gptSpeedInherited)
        val restored = entry.restore(profile)
        assertTrue(restored.gptSpeedByModel.isEmpty())
        for (speed in GptSpeedMode.entries) assertEquals(speed, restored.gptSpeedForModel(fallback = speed))
        assertTrue(profile.gptSpeedByModel.isEmpty())
        assertEquals(2, entry.parallelLimit)
    }

    @Test fun explicitNormalFastAndUltraRemainExactOverrides() {
        for (speed in GptSpeedMode.entries) {
            val entry = roundTrip(confirmed(profile.copy(gptSpeedByModel = mapOf(key to speed))))
            assertFalse(entry.gptSpeedInherited)
            assertEquals(mapOf(key to speed), entry.restore(profile).gptSpeedByModel)
            for (fallback in GptSpeedMode.entries) assertEquals(speed, entry.restore(profile).gptSpeedForModel(fallback = fallback))
        }
    }

    @Test fun legacyRecordWithoutInheritedFlagKeepsItsExplicitSpeed() {
        for (speed in GptSpeedMode.entries) {
            val json = JSONObject(SubAgentModelDefaults.encode(mapOf(key to confirmed(profile))))
            val row = json.getJSONArray("models").getJSONObject(0)
            row.remove("gpt_speed_inherited"); row.put("gpt_speed", speed.name)
            val entry = SubAgentModelDefaults.decode(json.toString()).getValue(key)
            assertFalse(entry.gptSpeedInherited)
            assertEquals(mapOf(key to speed), entry.restore(profile).gptSpeedByModel)
        }
    }

    @Test fun malformedInheritedFlagIsRejectedAndFalseStillMeansExplicit() {
        for (invalid in listOf<Any>("true", 1, JSONObject.NULL)) {
            val json = JSONObject(SubAgentModelDefaults.encode(mapOf(key to confirmed(profile))))
            json.getJSONArray("models").getJSONObject(0).put("gpt_speed_inherited", invalid)
            assertThrows(IllegalArgumentException::class.java) { SubAgentModelDefaults.decode(json.toString()) }
        }
        val json = JSONObject(SubAgentModelDefaults.encode(mapOf(key to confirmed(profile))))
        json.getJSONArray("models").getJSONObject(0).put("gpt_speed_inherited", false)
        assertEquals(mapOf(key to GptSpeedMode.NORMAL), SubAgentModelDefaults.decode(json.toString()).getValue(key).restore(profile).gptSpeedByModel)
    }

    @Test fun anotherSelectionOverrideDoesNotBecomeThisModelsOverride() {
        val entry = roundTrip(confirmed(profile.copy(gptSpeedByModel = mapOf(
            SubAgentProfile.modelReasoningKey("another-provider", profile.modelId) to GptSpeedMode.ULTRA_FAST,
            SubAgentProfile.modelReasoningKey(profile.providerId, "another-selection") to GptSpeedMode.FAST))))
        assertTrue(entry.gptSpeedInherited)
        assertTrue(entry.restore(profile).gptSpeedByModel.isEmpty())
    }
}
