package app.daiyu.android.model

import app.daiyu.android.domain.ModelEvent
import app.daiyu.android.domain.ModelFailure
import app.daiyu.android.domain.ModelRequestException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProviderUsageTest {
    private fun frame(value: JSONObject) = "data: $value\n\n"
    private fun completed(usage: Any? = null): String {
        val response = JSONObject().put("status", "completed").put("output", JSONArray())
        if (usage != null) response.put("usage", usage)
        return frame(JSONObject().put("type", "response.completed").put("response", response))
    }
    private fun responses(body: String, cancelled: () -> Boolean = { false }, sink: (ModelEvent) -> Unit = {}): List<String> =
        ResponsesStream.read(body.reader().buffered(), cancelled, sink)
    private fun usage(vararg values: Pair<String, Any>) = JSONObject(mapOf(*values))
    private fun rejected(work: () -> Unit) {
        try { work(); fail("invalid usage accepted") }
        catch (failure: ModelRequestException) { assertEquals(ModelFailure.PROTOCOL, failure.failure) }
    }
    @Test fun responsesPublishesOneTerminalSnapshotIncludingCache() {
        val events = mutableListOf<ModelEvent>()
        val counts = usage("input_tokens" to 15L, "output_tokens" to 4,
            "input_tokens_details" to usage("cached_tokens" to 7))
        val body = completed(counts) + completed(usage("input_tokens" to 999))
        assertTrue(responses(body, sink = events::add).isEmpty())
        assertEquals(listOf(ModelEvent.Usage(15, 4, 7)), events)
    }
    @Test fun missingAndNullStayUnknownAndExplicitZeroIsNotMissing() {
        for (counts in listOf(null, JSONObject.NULL, JSONObject(), usage("input_tokens" to JSONObject.NULL))) {
            val events = mutableListOf<ModelEvent>(); responses(completed(counts), sink = events::add)
            assertTrue(events.isEmpty())
        }
        val events = mutableListOf<ModelEvent>()
        responses(completed(usage("output_tokens" to 0)), sink = events::add)
        assertEquals(listOf(ModelEvent.Usage(null, 0, null)), events)
    }
    @Test fun usageRejectsCoercionsNegativeAndOversizeCounts() {
        for (count in listOf<Any>("12", true, 1.5, -1, 1_000_000_000_001L, JSONObject(), JSONArray())) {
            val events = mutableListOf<ModelEvent>()
            rejected { responses(completed(usage("input_tokens" to count)), sink = events::add) }
            assertTrue(events.isEmpty())
        }
        rejected { responses(completed(usage("input_tokens" to 1,
            "input_tokens_details" to usage("cached_tokens" to 2)))) }
        rejected { responses(completed(usage("input_tokens_details" to "wrong-type"))) }
    }
    @Test fun incompleteFailedAndCancelledStreamsNeverPublishUsage() {
        for (type in listOf("response.failed", "response.incomplete")) {
            val events = mutableListOf<ModelEvent>()
            try { responses(frame(JSONObject().put("type", type)), sink = events::add); fail("no terminal") }
            catch (_: ModelRequestException) {}
            assertTrue(events.none { it is ModelEvent.Usage })
        }
        val events = mutableListOf<ModelEvent>()
        try { responses(completed(usage("input_tokens" to 1)), { true }, events::add); fail("cancelled") }
        catch (_: InterruptedException) {}
        assertTrue(events.isEmpty())
        try { responses(""); fail("incomplete") } catch (failure: ModelRequestException) {
            assertEquals(ModelFailure.INCOMPLETE, failure.failure)
        }
    }
    @Test fun usageSinkFailureRetainsIdentityAndStopsDelivery() {
        val expected = org.json.JSONException("sink identity")
        try { responses(completed(usage("input_tokens" to 1))) { throw expected }; fail("sink failed") }
        catch (actual: org.json.JSONException) { assertSame(expected, actual) }
    }
    private fun messages(start: JSONObject, delta: JSONObject = JSONObject(), stop: Boolean = true): String {
        val frames = listOf(
            JSONObject().put("type", "message_start").put("message", JSONObject().put("id", "fixture")
                .put("role", "assistant").put("content", JSONArray()).put("usage", start)),
            JSONObject().put("type", "content_block_start").put("index", 0)
                .put("content_block", JSONObject().put("type", "text").put("text", "ok")),
            JSONObject().put("type", "content_block_stop").put("index", 0),
            JSONObject().put("type", "message_delta").put("delta", JSONObject().put("stop_reason", "end_turn")).put("usage", delta)
        ) + if (stop) listOf(JSONObject().put("type", "message_stop")) else emptyList()
        return frames.joinToString("") { frame(it) }
    }
    @Test fun messagesMergesSnapshotsOnceAndCountsBothCacheCategories() {
        val events = mutableListOf<ModelEvent>()
        MessagesStream.read(messages(usage("input_tokens" to 10, "cache_read_input_tokens" to 5,
            "cache_creation_input_tokens" to 2, "output_tokens" to 1), usage("output_tokens" to 8)).reader().buffered(), { false }, events::add)
        assertEquals(listOf(ModelEvent.Usage(17, 8, 5)), events.filterIsInstance<ModelEvent.Usage>())
    }
    @Test fun messagesRejectsCoercedAndOversizedUsageBeforeToolConfirmation() {
        for (bad in listOf<Any>("1", 1.5, -1, 1_000_000_000_001L, true)) {
            val events = mutableListOf<ModelEvent>()
            rejected { MessagesStream.read(messages(usage("input_tokens" to bad)).reader().buffered(), { false }, events::add) }
            assertTrue(events.none { it is ModelEvent.Usage || it is ModelEvent.ToolReady })
        }
        rejected { MessagesStream.read(messages(usage("input_tokens" to ProviderUsage.MAX_TOKENS,
            "cache_read_input_tokens" to 1, "cache_creation_input_tokens" to 0)).reader().buffered(), { false }) {} }
    }
    @Test fun messagesPartialInputCategoriesStayUnknownAndDeltaReplaces() {
        val events = mutableListOf<ModelEvent>()
        MessagesStream.read(messages(usage("input_tokens" to 10, "output_tokens" to 1),
            usage("output_tokens" to 3, "input_tokens" to JSONObject.NULL)).reader().buffered(), { false }, events::add)
        assertEquals(listOf(ModelEvent.Usage(null, 3, null)), events.filterIsInstance<ModelEvent.Usage>())
        events.clear()
        MessagesStream.read(messages(usage("input_tokens" to 0, "cache_read_input_tokens" to 0,
            "cache_creation_input_tokens" to 0)).reader().buffered(), { false }, events::add)
        assertEquals(listOf(ModelEvent.Usage(0, null, 0)), events.filterIsInstance<ModelEvent.Usage>())
    }
    @Test fun integerRuntimeTypesAndLimitsAreExact() {
        assertEquals(ProviderUsage.MAX_TOKENS, ProviderUsage.count(usage("n" to ProviderUsage.MAX_TOKENS), "n"))
        rejected { ProviderUsage.count(usage("n" to 1.0), "n") }
        rejected { ProviderUsage.count(usage("n" to java.math.BigInteger("1000000000001")), "n") }
    }
    @Test fun messagesIncompleteAndMissingUsageNeverBecomeKnownZero() {
        val events = mutableListOf<ModelEvent>()
        MessagesStream.read(messages(JSONObject()).reader().buffered(), { false }, events::add)
        assertTrue(events.none { it is ModelEvent.Usage })
        events.clear()
        try { MessagesStream.read(messages(usage("input_tokens" to 5), stop = false).reader().buffered(), { false }, events::add); fail("incomplete") }
        catch (failure: ModelRequestException) { assertEquals(ModelFailure.INCOMPLETE, failure.failure) }
        assertTrue(events.none { it is ModelEvent.Usage })
    }
    @Test fun messagesUsageSinkIdentityAndCancellationAfterPartialText() {
        val failure = org.json.JSONException("sink identity")
        val body = messages(usage("input_tokens" to 1, "cache_read_input_tokens" to 0,
            "cache_creation_input_tokens" to 0))
        try {
            MessagesStream.read(body.reader().buffered(), { false }) { if (it is ModelEvent.Usage) throw failure }
            fail("sink failed")
        } catch (actual: org.json.JSONException) { assertSame(failure, actual) }
        var cancelled = false
        val events = mutableListOf<ModelEvent>()
        try {
            MessagesStream.read(body.reader().buffered(), { cancelled }) {
                events.add(it)
                if (it is ModelEvent.TextDelta) cancelled = true
            }
            fail("cancelled after delta")
        } catch (_: InterruptedException) {}
        assertTrue(events.any { it is ModelEvent.TextDelta })
        assertTrue(events.none { it is ModelEvent.Usage })
        events.clear()
        val failed = frame(JSONObject().put("type", "error").put("error", JSONObject().put("type", "api_error")))
        try { MessagesStream.read(failed.reader().buffered(), { false }, events::add); fail("server error") }
        catch (actual: ModelRequestException) { assertEquals(ModelFailure.SERVER, actual.failure) }
        assertTrue(events.none { it is ModelEvent.Usage })
    }
    @Test fun terminalOverflowCannotConfirmRealToolBlock() {
        val frames = mutableListOf<JSONObject>()
        // Construct a real tool frame; no text-only fixture can prove this barrier.
        frames.add(JSONObject().put("type", "message_start").put("message", JSONObject().put("id", "tool")
            .put("role", "assistant").put("content", JSONArray()).put("usage", usage("input_tokens" to ProviderUsage.MAX_TOKENS,
                "cache_read_input_tokens" to 1, "cache_creation_input_tokens" to 0))))
        frames.add(JSONObject().put("type", "content_block_start").put("index", 0).put("content_block",
            JSONObject().put("type", "tool_use").put("id", "call").put("name", "fixture").put("input", JSONObject())))
        frames.add(JSONObject().put("type", "content_block_stop").put("index", 0))
        frames.add(JSONObject().put("type", "message_delta").put("delta", JSONObject().put("stop_reason", "tool_use")))
        frames.add(JSONObject().put("type", "message_stop"))
        val events = mutableListOf<ModelEvent>()
        rejected { MessagesStream.read(frames.joinToString("") { frame(it) }.reader().buffered(), { false }, events::add) }
        assertTrue(events.none { it is ModelEvent.ToolReady || it is ModelEvent.Usage })
    }
}
