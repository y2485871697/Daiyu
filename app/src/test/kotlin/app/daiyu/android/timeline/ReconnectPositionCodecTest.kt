package app.daiyu.android.timeline

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReconnectPositionCodecTest {
    private val original = ChatTimelineEntry.Reconnect("one", TimelineAnchor("c", "r", "a", 0),
        7L, "running", 10L, "network", 0, "model")

    @Test fun legacyWireRemainsUnchangedAndOptionalCoordinatesRoundTrip() {
        val legacy = ChatTimelineStore.encode(original)
        assertFalse(legacy.has("offset"))
        assertEquals(original, ChatTimelineStore.decode(original.id, original.created, legacy))
        for (offset in listOf(0L, 3L, Long.MAX_VALUE)) {
            val positioned = original.copy(position = ReconnectPosition(offset))
            assertEquals(positioned, ChatTimelineStore.decode(positioned.id, positioned.created, ChatTimelineStore.encode(positioned)))
        }
    }

    @Test fun invalidOffsetTypesAndNegativeValuesAreRejectedNotCoerced() {
        for (value in listOf(-1L, 1.5, "3", JSONObject.NULL, true)) {
            val row = ChatTimelineStore.encode(original).put("offset", value)
            try {
                ChatTimelineStore.decode(original.id, original.created, row)
                fail("Invalid offset was accepted")
            } catch (_: IllegalArgumentException) {
                // Expected strict wire rejection.
            }
        }
        try { ReconnectPosition(-1); fail("negative coordinate") } catch (_: IllegalArgumentException) { }
    }

    @Test fun allSameIdTransitionsRetainFirstPositionEvenIfIncomingCoordinatesChange() {
        for (first in listOf(original, original.copy(position = ReconnectPosition(3)))) {
            var prior = first
            for (state in listOf("stopped", "running", "succeeded")) {
                assertTrue(reconnectTransitionAllowed(prior.status, state))
                val next = prior.copy(status = state, created = 99L, anchor = prior.anchor.copy(segment = 9),
                    position = ReconnectPosition(999), elapsedMs = prior.elapsedMs + 10).keepPositionOf(prior)
                assertEquals(first.id, next.id)
                assertEquals(first.anchor, next.anchor)
                assertEquals(first.created, next.created)
                assertEquals(first.position, next.position)
                prior = next
            }
        }
    }
}
