package app.daiyu.android.model

import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
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
        assertEquals(listOf(ModelEvent.Usage(17, 8, 5, 2)), events.filterIsInstance<ModelEvent.Usage>())
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
    @Test fun messagesPartialInputCategoriesArePreservedAndDeltaReplaces() {
        val events = mutableListOf<ModelEvent>()
        MessagesStream.read(messages(usage("input_tokens" to 10, "output_tokens" to 1),
            usage("output_tokens" to 3, "input_tokens" to JSONObject.NULL)).reader().buffered(), { false }, events::add)
        assertEquals(listOf(ModelEvent.Usage(10, 3, null)), events.filterIsInstance<ModelEvent.Usage>())
        events.clear()
        MessagesStream.read(messages(usage("input_tokens" to 0, "cache_read_input_tokens" to 0,
            "cache_creation_input_tokens" to 0)).reader().buffered(), { false }, events::add)
        assertEquals(listOf(ModelEvent.Usage(0, null, 0, 0)), events.filterIsInstance<ModelEvent.Usage>())
    }
    @Test fun creationOnlyAndExplicitZeroArePreserved() {
        assertEquals(ModelEvent.Usage(9, null, null, 9), ProviderUsage.messages(null, null, null, 9))
        assertEquals(ModelEvent.Usage(0, 0, 0, 0), ProviderUsage.messages(0, 0, 0, 0))
        assertNull(ProviderUsage.responses(JSONObject().put("usage", JSONObject().put("input_tokens_details", JSONObject()))))
    }
    @Test fun messagesPreservesEveryReportedInputSubsetWithoutInventingMissingCategories() {
        for (mask in 0..7) for (explicitNull in listOf(false, true)) {
            val fields = listOf("input_tokens" to 40_000L, "cache_read_input_tokens" to 190_000L,
                "cache_creation_input_tokens" to 10_000L)
            val start = JSONObject()
            fields.forEachIndexed { index, (key, value) ->
                if (mask and (1 shl index) != 0) start.put(key, value)
                else if (explicitNull) start.put(key, JSONObject.NULL)
            }
            val events = mutableListOf<ModelEvent>()
            MessagesStream.read(messages(start).reader().buffered(), { false }, events::add)
            val observed = events.filterIsInstance<ModelEvent.Usage>()
            if (mask == 0) assertTrue(observed.isEmpty()) else {
                val total = fields.withIndex().filter { mask and (1 shl it.index) != 0 }.sumOf { it.value.second }
                assertEquals(listOf(ModelEvent.Usage(total, null,
                    fields[1].second.takeIf { mask and 2 != 0 }, fields[2].second.takeIf { mask and 4 != 0 })), observed)
            }
        }
    }
    @Test fun messagesMissingCreationProducesCloudWindowInputAndDeltaDoesNotAccumulate() {
        val start = usage("input_tokens" to 40_000, "cache_read_input_tokens" to 200_000, "output_tokens" to 1)
        val delta = usage("input_tokens" to 50_000, "output_tokens" to 123)
        val body = messages(start, delta, stop = false) + frame(JSONObject().put("type", "message_delta")
            .put("delta", JSONObject().put("stop_reason", "end_turn")).put("usage", delta)) +
            frame(JSONObject().put("type", "message_stop"))
        val events = mutableListOf<ModelEvent>()
        MessagesStream.read(body.reader().buffered(), { false }, events::add)
        assertEquals(listOf(ModelEvent.Usage(250_000, 123, 200_000)), events.filterIsInstance<ModelEvent.Usage>())
        val first = mutableListOf<ModelEvent>()
        MessagesStream.read(messages(start).reader().buffered(), { false }, first::add)
        val reported = first.filterIsInstance<ModelEvent.Usage>().single()
        val actual = app.daiyu.android.ui.model.acceptedContextInput(reported, 272_000)
        assertEquals(240_000L, actual)
        assertEquals("240k/272k", app.daiyu.android.ui.model.CloudContextUsageUi(actual, 272_000).tokensLabel(java.util.Locale.US))
        assertTrue(actual!! >= 272_000 * 80 / 100)
    }
    @Test fun messagesOutputOnlyDoesNotBecomeAnInputAndReportedZeroRemainsKnown() {
        val events = mutableListOf<ModelEvent>()
        MessagesStream.read(messages(usage("output_tokens" to 123)).reader().buffered(), { false }, events::add)
        assertEquals(listOf(ModelEvent.Usage(null, 123)), events.filterIsInstance<ModelEvent.Usage>())
        events.clear()
        MessagesStream.read(messages(usage("input_tokens" to 0)).reader().buffered(), { false }, events::add)
        assertEquals(listOf(ModelEvent.Usage(0, null)), events.filterIsInstance<ModelEvent.Usage>())
        assertNull(ProviderUsage.messages(null, null, null, null))
    }
    @Test fun messagesCloudInputDrivesRunnerPressureAfterToolsWithoutAddingOutputOrEnablingAuto() {
        for (automatic in listOf(false, true)) for (highInput in listOf(false, true)) {
            val frames = listOf(
                JSONObject().put("type", "message_start").put("message", JSONObject().put("id", "tool")
                    .put("role", "assistant").put("content", JSONArray()).put("usage", usage(
                        "input_tokens" to if (highInput) 40_000 else 10_000,
                        "cache_read_input_tokens" to if (highInput) 200_000 else 10_000))),
                JSONObject().put("type", "content_block_start").put("index", 0).put("content_block",
                    JSONObject().put("type", "tool_use").put("id", "call").put("name", "read").put("input", JSONObject())),
                JSONObject().put("type", "content_block_stop").put("index", 0),
                JSONObject().put("type", "message_delta").put("delta", JSONObject().put("stop_reason", "tool_use"))
                    .put("usage", usage("output_tokens" to 250_000)),
                JSONObject().put("type", "message_stop"),
            ).joinToString("") { frame(it) }
            var compactions = 0; var tools = 0; var nexts = 0
            val store = object : AtomicRuntimeStore {
                val saved = mutableListOf<RunCheckpoint>()
                override fun commitCheckpoint(checkpoint: RunCheckpoint) { saved += checkpoint }
                override fun reconcileCheckpoint(checkpoint: RunCheckpoint): RunCheckpoint = error("no recovery")
            }
            val runtime = object : RunContextRuntime {
                override val automatic = automatic
                override val windowTokens = 272_000L
                override fun localTokens(history: List<Message>, tools: List<ToolDefinition>) = 100L
                override fun compact(history: List<Message>, billedTokens: Long?, localTokens: Long,
                    request: RequestCheckpoint, observe: (ModelEvent.Usage) -> Unit,
                    admit: () -> Unit, cancelled: () -> Boolean): List<Message>? {
                    assertEquals(1, tools)
                    assertEquals(240_000L, billedTokens)
                    compactions++
                    return null
                }
            }
            val model = ModelPort { object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    if (nexts++ == 0) {
                        sink(ModelEvent.InputEstimate(100))
                        MessagesStream.read(frames.reader().buffered(), { false }, sink)
                    } else sink(ModelEvent.TextFinal("done", "answer"))
                }
                override fun cancel() {}
            } }
            val initial = Message(MessageId("assistant"), ConversationId("c"), RunId("r"), "assistant", emptyList(), RunStatus.RUNNING)
            val result = ChatRunner(model, object : ConversationStore { override fun save(message: Message) {} },
                BatteryTool { tools++; "done" }, RunnerOptions(runtimeStore = store, contextRuntime = runtime)) {}.run(initial, emptyList())
            assertEquals(RunStatus.COMPLETED, result.status)
            assertEquals(1, tools)
            assertEquals(2, nexts)
            assertEquals(if (automatic && highInput) 1 else 0, compactions)
            assertEquals(if (highInput) 240_000L else 20_000L, store.saved.last().usage.single().snapshot.inputTokens)
        }
    }

    @Test fun messagesLateCategoriesAndZeroReplaceRatherThanAddToEarlierSnapshots() {
        val events = mutableListOf<ModelEvent>()
        MessagesStream.read(messages(usage("input_tokens" to 10, "cache_read_input_tokens" to 5,
            "cache_creation_input_tokens" to 2, "output_tokens" to 1), usage("input_tokens" to 12,
            "cache_read_input_tokens" to 0, "cache_creation_input_tokens" to JSONObject.NULL,
            "output_tokens" to 3)).reader().buffered(), { false }, events::add)
        assertEquals(listOf(ModelEvent.Usage(14, 3, 0, 2)), events.filterIsInstance<ModelEvent.Usage>())
        events.clear()
        MessagesStream.read(messages(usage("input_tokens" to 10), usage("cache_creation_input_tokens" to 2))
            .reader().buffered(), { false }, events::add)
        assertEquals(listOf(ModelEvent.Usage(12, null, null, 2)), events.filterIsInstance<ModelEvent.Usage>())
        for (field in listOf("input_tokens", "cache_read_input_tokens", "cache_creation_input_tokens")) {
            events.clear()
            MessagesStream.read(messages(usage(field to 0)).reader().buffered(), { false }, events::add)
            val observed = events.filterIsInstance<ModelEvent.Usage>().single()
            assertEquals(0L, observed.inputTokens)
            assertEquals(0L.takeIf { field == "cache_read_input_tokens" }, observed.cachedTokens)
            assertEquals(0L.takeIf { field == "cache_creation_input_tokens" }, observed.cacheCreationTokens)
        }
    }
    @Test fun messagesAbsentUsageContainersAndFailedPartialStreamsPublishNoUsage() {
        val start = JSONObject().put("type", "message_start").put("message", JSONObject().put("id", "fixture")
            .put("role", "assistant").put("content", JSONArray()))
        val delta = JSONObject().put("type", "message_delta").put("delta", JSONObject().put("stop_reason", "end_turn"))
        val stop = JSONObject().put("type", "message_stop")
        val events = mutableListOf<ModelEvent>()
        val textStart = JSONObject().put("type", "content_block_start").put("index", 0)
            .put("content_block", JSONObject().put("type", "text").put("text", "ok"))
        val textStop = JSONObject().put("type", "content_block_stop").put("index", 0)
        MessagesStream.read((frame(start) + frame(textStart) + frame(textStop) + frame(delta) + frame(stop))
            .reader().buffered(), { false }, events::add)
        assertTrue(events.none { it is ModelEvent.Usage })
        for (field in listOf("input_tokens", "cache_read_input_tokens", "cache_creation_input_tokens")) {
            for (bad in listOf<Any>("1", 1.5, -1, 1_000_000_000_001L, true)) {
                events.clear()
                rejected { MessagesStream.read(messages(usage("input_tokens" to 10), usage(field to bad))
                    .reader().buffered(), { false }, events::add) }
                assertTrue(events.none { it is ModelEvent.Usage || it is ModelEvent.ToolReady })
            }
        }
        val partial = messages(usage("input_tokens" to 40_000, "cache_read_input_tokens" to 200_000), stop = false)
        events.clear()
        assertThrows(ModelRequestException::class.java) {
            MessagesStream.read((partial + frame(JSONObject().put("type", "error")
                .put("error", JSONObject().put("type", "invalid_request_error")))).reader().buffered(), { false }, events::add)
        }
        assertTrue(events.none { it is ModelEvent.Usage || it is ModelEvent.ToolReady })
    }

    @Test fun messagesPartialSumsStillEnforceTheTotalBudget() {
        val events = mutableListOf<ModelEvent>()
        rejected { MessagesStream.read(messages(usage("input_tokens" to ProviderUsage.MAX_TOKENS,
            "cache_read_input_tokens" to 1)).reader().buffered(), { false }, events::add) }
        assertTrue(events.none { it is ModelEvent.Usage || it is ModelEvent.ToolReady })
        events.clear()
        MessagesStream.read(messages(usage("input_tokens" to ProviderUsage.MAX_TOKENS - 1,
            "cache_read_input_tokens" to 1)).reader().buffered(), { false }, events::add)
        assertEquals(ProviderUsage.MAX_TOKENS, events.filterIsInstance<ModelEvent.Usage>().single().inputTokens)
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
