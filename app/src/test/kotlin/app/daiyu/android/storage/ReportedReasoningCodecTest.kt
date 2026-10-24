package app.daiyu.android.storage

import app.daiyu.android.domain.ReportedReasoning
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReportedReasoningCodecTest {
    @Test fun elapsedRoundTripsAndLegacyEncodingRemainsUnchanged() {
        val measured = ReportedReasoning("r", "summary", 1_234L)
        assertEquals(measured, ReportedReasoningCodec.decode(ReportedReasoningCodec.encode(measured)))
        val legacy = ReportedReasoning("r", "summary")
        val encoded = ReportedReasoningCodec.encode(legacy)
        assertEquals(setOf("item_id", "text"), encoded.keys().asSequence().toSet())
        assertEquals(legacy, ReportedReasoningCodec.decode(encoded))
        assertFalse(ReportedReasoningCodec.encode(measured.copy(activeSinceNanos = 0L)).has("activeSinceNanos"))
    }

    @Test fun malformedElapsedCannotBecomeAClock() {
        listOf(-1L, 1.5, "1200", true, JSONObject.NULL).forEach { invalid ->
            try {
                ReportedReasoningCodec.decode(JSONObject().put("item_id", "r").put("text", "summary").put("elapsed_ms", invalid))
                fail("accepted $invalid")
            } catch (_: IllegalArgumentException) { }
        }
    }
}
