package app.daiyu.android.model.reconnect

import org.junit.Assert.*
import org.junit.Test

class ReconnectSettingsCodecTest {
    @Test fun retainedFivePoliciesKeepTheirStableValuesAndTotalWindows() {
        val expected = listOf(
            Triple(ReconnectDurationPreset.NONE, "none", 0L),
            Triple(ReconnectDurationPreset.WINDOW_30S, "window_30s", 30_000L),
            Triple(ReconnectDurationPreset.WINDOW_1M, "window_1m", 60_000L),
            Triple(ReconnectDurationPreset.WINDOW_5M, "window_5m", 300_000L),
            Triple(ReconnectDurationPreset.CONTINUOUS, "continuous", null))
        assertEquals(expected.map { it.first }, ReconnectDurationPreset.entries.toList())
        expected.forEach { (policy, persisted, window) ->
            assertEquals(persisted, policy.wireId)
            assertEquals(window, policy.windowMillis)
            val settings = ReconnectSettings(policy)
            assertEquals(settings, ReconnectSettingsCodec.decode(ReconnectSettingsCodec.encode(settings)))
        }
        assertEquals(ReconnectSettings(ReconnectDurationPreset.CONTINUOUS), ReconnectSettingsCodec.decode(null))
        assertFalse(ReconnectSettings(ReconnectDurationPreset.NONE).enabled)
    }

    @Test fun unknownPoliciesFailClosedWithoutCoercion() {
        for (raw in listOf(null, "", "unknown", "WINDOW_30S", "30", " continuous ")) {
            assertEquals(ReconnectDurationPreset.NONE, ReconnectDurationPreset.fromPersistedValue(raw))
            if (raw != null) assertEquals(ReconnectSettings(ReconnectDurationPreset.NONE),
                ReconnectSettingsCodec.decode("v1:$raw"))
        }
    }

    @Test fun invalidTypeEnvelopeAndVersionAreExplicitErrors() {
        for (raw in listOf("", "null", "v0:none", "v2:continuous", "V1:none", "none", "v1:none:extra",
            " v1:none", ":".repeat(10_000))) {
            try { ReconnectSettingsCodec.decode(raw); fail("invalid envelope accepted") }
            catch (_: ReconnectSettingsException) { }
        }
    }
}
