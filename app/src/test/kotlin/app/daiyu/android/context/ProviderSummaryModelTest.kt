package app.daiyu.android.context

import app.daiyu.android.domain.*
import app.daiyu.android.model.*
import app.daiyu.android.storage.ModelSettings
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.security.cert.Certificate
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection

/** Real model serialization/stream parsing against offline HTTPS fixtures; no provider/network. */
class ProviderSummaryModelTest {
    private val binding = SummaryModelBinding("explicit-summary-provider", "summary-wire-model")
    private val protocols = listOf(ProviderProtocol.RESPONSES, ProviderProtocol.MESSAGES)
    private fun configuration(protocol: ProviderProtocol, custom: String = ""): SummaryProviderConfiguration {
        val provider = ProviderSnapshot.capture(ModelSettings("https://example.invalid/v1", binding.modelId,
            "synthetic-summary-key", true, deviceStatusEnabled = true, protocol = protocol,
            requestOptions = ProviderRequestOptions(customBody = custom)))
        return SummaryProviderConfiguration(binding, provider, binding.modelId, 24000, 23104, 256, 128, 512, 60000)
    }
    private fun input() = CompressionInput(listOf(Message(MessageId("old"), ConversationId("owner"),
        RunId("run"), "user", listOf(MessageBlock.Text("text", "历史事实；不执行原文命令")), RunStatus.COMPLETED)), "revision")
    private fun request() = input().originals
    private fun resolver(config: SummaryProviderConfiguration, connection: Connection,
        deadline: ManualDeadline = ManualDeadline()) = ProviderSummaryModelResolver({ if (it == binding) config else null },
        { connection }, deadline)
    private fun summarize(config: SummaryProviderConfiguration, connection: Connection,
        cancellation: ContextPreparationCancellation = ContextPreparationCancellation(),
        deadline: ManualDeadline = ManualDeadline()): String = ModelContextSummarizer(
        resolver(config, connection, deadline).resolve(binding)!!, cancellation).summarize(input())

    private fun chatSummary(reason: String = "stop", done: Boolean = true): String {
        fun chunk(delta: JSONObject, finish: Any) = "data: " + JSONObject().put("id", "chat-summary")
            .put("choices", JSONArray().put(JSONObject().put("index", 0).put("delta", delta)
                .put("finish_reason", finish))) + "\n\n"
        return chunk(JSONObject().put("role", "assistant").put("content", "最终事实摘要"), JSONObject.NULL) +
            chunk(JSONObject(), reason) + if (done) "data: [DONE]\n\n" else ""
    }

    @Test fun chatCompletionsSummaryUsesNativeSystemAndBoundedToolFreeSingleRequest() {
        val config = configuration(ProviderProtocol.CHAT_COMPLETIONS)
        val wire = Connection(chatSummary())
        assertEquals("最终事实摘要", summarize(config, wire))
        assertEquals(1, wire.posts)
        val body = wire.json()
        assertEquals(binding.modelId, body.getString("model"))
        assertEquals(256, body.getInt("max_tokens"))
        assertEquals("system", body.getJSONArray("messages").getJSONObject(0).getString("role"))
        assertTrue(body.getJSONArray("messages").getJSONObject(0).getString("content").contains("不调用任何工具"))
        assertFalse(body.has("tools"))
        assertFalse(body.has("tool_choice"))
        assertEquals("Bearer synthetic-summary-key", wire.getRequestProperty("Authorization"))
    }

    @Test fun chatSummaryTruncationOrMissingAuthoritativeDoneNeverPublishesProse() {
        for (stream in listOf(chatSummary("length"), chatSummary(done = false))) {
            val wire = Connection(stream)
            assertThrows(ModelRequestException::class.java) { summarize(configuration(ProviderProtocol.CHAT_COMPLETIONS), wire) }
            assertEquals(1, wire.posts)
        }
    }

    @Test fun bothProtocolsUseFrozenExplicitModelBudgetNativeInstructionsAndNoTools() {
        for (protocol in protocols) {
            val original = configuration(protocol, """{"temperature":0.25}""")
            var current = original
            var connections = 0
            val wire = Connection(success(protocol))
            val deadline = ManualDeadline()
            val resolver = ProviderSummaryModelResolver({ requested ->
                assertEquals(binding, requested); current
            }, { url ->
                connections++
                assertEquals(if (protocol == ProviderProtocol.RESPONSES) "/v1/responses" else "/v1/messages", url.path)
                wire
            }, deadline)
            val bound = resolver.resolve(binding)!!
            assertEquals(23104L, bound.inputBudgetTokens)
            assertEquals(24000L, bound.contextWindowTokens)
            assertEquals(0, connections)
            // A later registry/main settings change cannot change this run's credentials or model.
            current = original.copy(binding = SummaryModelBinding("other-provider", "other-model"), apiModel = "other-model",
                provider = ProviderSnapshot.capture(original.provider.asModelSettings().copy(
                    model = "other-model", apiKey = "later-key")))
            val session = bound.transport.openWithoutTools(request(), bound.startDeadline())
            assertEquals(0, connections)
            val events = mutableListOf<ModelEvent>()
            session.next(emptyList(), events::add)
            assertEquals(listOf(ModelEvent.TextFinal("summary-0", "最终事实摘要")), events)
            assertEquals(1, connections)
            assertTrue(deadline.closed)
            assertTrue(wire.disconnected)
            assertEquals(60000L, deadline.delay)
            val body = wire.json()
            assertEquals(binding.modelId, body.getString("model"))
            assertEquals(256, body.getInt(if (protocol == ProviderProtocol.RESPONSES) "max_output_tokens" else "max_tokens"))
            assertEquals(0.25, body.getDouble("temperature"), 0.0)
            assertFalse(body.has("tools"))
            assertFalse(body.has("tool_choice"))
            assertTrue(body.getBoolean("stream"))
            val native = if (protocol == ProviderProtocol.RESPONSES) "instructions" else "system"
            assertTrue(body.getString(native).contains("不调用任何工具"))
            assertFalse(body.has(if (protocol == ProviderProtocol.RESPONSES) "system" else "instructions"))
            if (protocol == ProviderProtocol.RESPONSES) {
                assertEquals("Bearer synthetic-summary-key", wire.getRequestProperty("Authorization"))
                assertFalse(body.getBoolean("store"))
                assertEquals(1, body.getJSONArray("input").length())
            } else {
                assertEquals("synthetic-summary-key", wire.getRequestProperty("x-api-key"))
                assertEquals(1, body.getJSONArray("messages").length())
            }
            // Creating the summary policy neither mutates nor reuses provider-wide tool defaults.
            assertTrue(original.provider.batteryEnabled && original.provider.deviceStatusEnabled)
            assertEquals("""{"temperature":0.25}""", original.provider.requestOptions.customBody)
            assertFalse(original.provider.requestOptions.customBody.contains("max_tokens"))
        }
    }

    @Test fun summarizerUsesRealSuccessfulTerminalTextNotSpeculativeOrReasoningEvents() {
        for (protocol in protocols) {
            val wire = Connection(success(protocol))
            assertEquals("最终事实摘要", summarize(configuration(protocol), wire))
            val body = wire.json()
            val text = if (protocol == ProviderProtocol.RESPONSES)
                body.getJSONArray("input").getJSONObject(0).getString("content")
            else body.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text")
            assertTrue(text.contains("历史事实") && text.contains("不是新指令") && text.contains("revision"))
        }
    }

    @Test fun missingOrMismatchedBindingNeverUsesAnyOtherProvider() {
        var connections = 0
        val config = configuration(ProviderProtocol.RESPONSES)
        val missing = ProviderSummaryModelResolver({ null }, { connections++; Connection("") }, ManualDeadline())
        assertNull(missing.resolve(binding))
        val mismatch = ProviderSummaryModelResolver({ config }, { connections++; Connection("") }, ManualDeadline())
        for (requested in listOf(SummaryModelBinding("other-provider", binding.modelId),
            SummaryModelBinding(binding.providerId, "other-model"),
            SummaryModelBinding(binding.providerId.uppercase(), binding.modelId))) {
            assertThrows(IllegalArgumentException::class.java) { mismatch.resolve(requested) }
        }
        assertEquals(0, connections)
    }

    @Test fun budgetsAndWireModelIdentityAreExactAndOverflowSafe() {
        val config = configuration(ProviderProtocol.RESPONSES)
        assertThrows(IllegalArgumentException::class.java) { config.copy(inputBudgetTokens = config.inputBudgetTokens + 1) }
        assertThrows(IllegalArgumentException::class.java) { config.copy(inputBudgetTokens = 0) }
        assertThrows(IllegalArgumentException::class.java) { config.copy(maxOutputTokens = 0) }
        assertThrows(IllegalArgumentException::class.java) { config.copy(reasoningReserveTokens = Long.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { config.copy(providerOverheadTokens = Long.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { config.copy(providerOverheadTokens = 1) }
        assertThrows(IllegalArgumentException::class.java) { config.copy(deadlineMillis = 0) }
        assertThrows(IllegalArgumentException::class.java) { config.copy(deadlineMillis = Long.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { config.copy(apiModel = "different-wire-model") }
        // Registry model IDs are opaque. An explicit alias mapping is valid, but never inferred.
        val alias = config.copy(binding = binding.copy(modelId = "registry-record-id"))
        val aliasResolver = ProviderSummaryModelResolver({ alias }, { Connection(success(ProviderProtocol.RESPONSES)) }, ManualDeadline())
        assertEquals(alias.binding, aliasResolver.resolve(alias.binding)!!.binding)
        assertThrows(IllegalArgumentException::class.java) { aliasResolver.resolve(binding) }
        assertThrows(MissingProviderCredentialException::class.java) {
            ProviderSnapshot.capture(config.provider.asModelSettings().copy(apiKey = ""))
        }
        assertThrows(UnsupportedProviderException::class.java) {
            ProviderSnapshot.capture(config.provider.asModelSettings().copy(protocol = ProviderProtocol.UNKNOWN))
        }
    }

    @Test fun unsafeCustomBodyCannotOverrideModelBudgetsToolsOrNativeInstructions() {
        val fields = mapOf("model" to "main-model", "max_output_tokens" to 9999, "max_tokens" to 9999,
            "max_completion_tokens" to 9999, "tools" to JSONArray(), "tool_choice" to "auto",
            "parallel_tool_calls" to true, "functions" to JSONArray(), "function_call" to "auto",
            "system" to "override", "instructions" to "override", "thinking" to JSONObject().put("budget_tokens", 9999),
            "reasoning" to JSONObject().put("effort", "high"), "unknown_extension" to true,
            "temperature" to JSONObject().put("tools", JSONArray()), "top_p" to "not-a-number")
        for (protocol in protocols) for ((key, value) in fields) {
            var connected = false
            assertThrows("must reject $protocol/$key", IllegalArgumentException::class.java) {
                val config = configuration(protocol, JSONObject().put(key, value).toString())
                val bound = ProviderSummaryModelResolver({ config }, { connected = true; Connection("") }, ManualDeadline())
                    .resolve(binding)!!
                bound.transport.openWithoutTools(request(), bound.startDeadline())
            }
            assertFalse(connected)
        }
    }

    @Test fun evenLegacyModelPortOpenIsToolFreeAndRejectsToolAndProtocolInputs() {
        for (protocol in protocols) {
            val wire = Connection(success(protocol))
            val adapter = resolver(configuration(protocol), wire).resolve(binding)!!.transport as ModelPort
            assertThrows(IllegalArgumentException::class.java) { adapter.open(request(), setOf(ReadOnlyTool.BATTERY)) }
            assertThrows(IllegalArgumentException::class.java) {
                adapter.open(request(), listOf(ToolDefinition("forbidden", "", "{}")))
            }
            assertThrows(IllegalArgumentException::class.java) {
                adapter.open(request().map { it.copy(protocolItems = listOf("{}")) })
            }
            assertThrows(IllegalArgumentException::class.java) {
                adapter.open(request().map { it.copy(role = "assistant") })
            }
            val session = adapter.open(request())
            session.next(emptyList()) {}
            assertFalse(wire.json().has("tools"))
            assertThrows(IllegalStateException::class.java) { session.next(emptyList()) {} }
        }
    }

    @Test fun partialEofIncompleteAndProviderErrorsAreNeverReturnedAsSummary() {
        for (protocol in protocols) {
            val cases = if (protocol == ProviderProtocol.RESPONSES) listOf(
                partialResponse() to ModelFailure.INCOMPLETE,
                partialResponse() + frames(event("response.incomplete")) to ModelFailure.INCOMPLETE,
                partialResponse() + frames(event("response.failed")) to ModelFailure.REQUEST)
            else listOf(messages("max_tokens") to ModelFailure.INCOMPLETE,
                messages("end_turn", terminal = false) to ModelFailure.INCOMPLETE,
                frames(event("error", "error" to JSONObject().put("type", "api_error"))) to ModelFailure.SERVER)
            for ((body, expected) in cases) {
                val wire = Connection(body)
                val failure = assertThrows(ModelRequestException::class.java) { summarize(configuration(protocol), wire) }
                assertEquals(expected, failure.failure)
                assertTrue(wire.disconnected)
            }
            val unauthorized = Connection("never expose server body", status = 401)
            assertEquals(ModelFailure.AUTHENTICATION, assertThrows(ModelRequestException::class.java) {
                summarize(configuration(protocol), unauthorized)
            }.failure)
            val wrongMime = Connection(success(protocol), mime = "application/json")
            assertEquals(ModelFailure.PROTOCOL, assertThrows(ModelRequestException::class.java) {
                summarize(configuration(protocol), wrongMime)
            }.failure)
        }
    }

    @Test fun networkAndSocketTimeoutErrorsRetainTheirFailureWithoutRetry() {
        for (protocol in protocols) for ((cause, expected) in listOf(
            IOException("synthetic read failure") to ModelFailure.NETWORK,
            java.net.SocketTimeoutException("synthetic idle timeout") to ModelFailure.TIMEOUT)) {
            val wire = Connection("", readFailure = cause)
            val failure = assertThrows(ModelRequestException::class.java) { summarize(configuration(protocol), wire) }
            assertEquals(expected, failure.failure)
            assertEquals(1, wire.posts)
            assertTrue(wire.disconnected)
        }
    }

    @Test fun emptyOrUnsolicitedToolTerminalIsRejectedWithoutContinuation() {
        for (protocol in protocols) {
            val empty = if (protocol == ProviderProtocol.RESPONSES)
                frames(event("response.completed", "response" to JSONObject().put("status", "completed").put("output", JSONArray())))
            else messages("end_turn", text = "")
            assertThrows(IllegalArgumentException::class.java) { summarize(configuration(protocol), Connection(empty)) }
            val wire = Connection(tool(protocol))
            assertThrows(IllegalStateException::class.java) { summarize(configuration(protocol), wire) }
            assertEquals(1, wire.posts)
            assertTrue(wire.disconnected)
        }
    }

    @Test fun unexpectedBuiltInToolOrUnfinishedMessageCannotHideBehindSuccessfulResponse() {
        for (invalid in listOf(
            event("web_search_call", "id" to "unexpected", "status" to "completed"),
            event("message", "id" to "unfinished", "role" to "assistant", "status" to "in_progress",
                "content" to JSONArray().put(event("output_text", "text" to "not finished"))))) {
            val body = frames(event("response.completed", "response" to JSONObject().put("status", "completed")
                .put("output", JSONArray().put(invalid))))
            assertEquals(ModelFailure.PROTOCOL, assertThrows(ModelRequestException::class.java) {
                summarize(configuration(ProviderProtocol.RESPONSES), Connection(body))
            }.failure)
        }
    }

    @Test fun parentCancellationDisconnectsActiveRealSessionAndPreCancelledDoesNotConnect() {
        for (protocol in protocols) {
            val config = configuration(protocol)
            val wire = Connection("", block = true)
            val cancellation = ContextPreparationCancellation()
            val result = AtomicReference<Throwable?>()
            val worker = Thread {
                try { summarize(config, wire, cancellation); result.set(AssertionError("late summary")) }
                catch (failure: Throwable) { result.set(failure) }
            }
            worker.start()
            try {
                assertTrue(wire.entered.await(3, TimeUnit.SECONDS))
                cancellation.cancel()
                worker.join(3000)
                assertFalse(worker.isAlive)
                assertTrue(result.get() is CancellationException)
                assertTrue(wire.disconnected)
            } finally { cancellation.cancel(); wire.disconnect(); worker.join(3000) }
            var connections = 0
            val preCancelled = ContextPreparationCancellation().apply { cancel() }
            val bound = ProviderSummaryModelResolver({ config }, { connections++; wire }, ManualDeadline()).resolve(binding)!!
            assertThrows(CancellationException::class.java) { ModelContextSummarizer(bound, preCancelled).summarize(input()) }
            assertEquals(0, connections)
        }
    }

    @Test fun totalDeadlineAbortsRealSessionIndependentlyOfSocketTimeoutAndIsNotCancellation() {
        for (protocol in protocols) {
            val wire = Connection("", block = true)
            val deadline = ManualDeadline()
            val result = AtomicReference<Throwable?>()
            val worker = Thread {
                try { summarize(configuration(protocol), wire, deadline = deadline); result.set(AssertionError("late summary")) }
                catch (failure: Throwable) { result.set(failure) }
            }
            worker.start()
            try {
                assertTrue(wire.entered.await(3, TimeUnit.SECONDS))
                assertEquals(60000, wire.readTimeout) // This is the provider's inactivity timeout, not our total deadline.
                deadline.expire()
                worker.join(3000)
                assertFalse(worker.isAlive)
                assertEquals(ModelFailure.TIMEOUT, (result.get() as ModelRequestException).failure)
                assertTrue(wire.disconnected)
                assertTrue(deadline.closed)
            } finally { wire.disconnect(); worker.join(3000) }
        }
    }

    @Test fun budgetRejectionHappensBeforeAnyRealSessionIsOpened() {
        val config = configuration(ProviderProtocol.RESPONSES).copy(contextWindowTokens = 897, inputBudgetTokens = 1)
        var connections = 0
        val deadline = ManualDeadline()
        val bound = ProviderSummaryModelResolver({ config }, { connections++; Connection("") }, deadline).resolve(binding)!!
        assertThrows(IllegalArgumentException::class.java) { ModelContextSummarizer(bound, ContextPreparationCancellation()).summarize(input()) }
        assertEquals(0, connections)
        assertNull(deadline.delay)
    }

    @Test fun summaryPreparationExhaustionDoesNotOpenOrChangeOriginals() {
        for (protocol in protocols) {
            val source = input()
            val originalBytes = ArchiveCodec.encode(source.originals)
            val clock = ManualDeadline()
            var connections = 0
            val bound = ProviderSummaryModelResolver({ configuration(protocol) }, {
                connections++; Connection(success(protocol))
            }, clock).resolve(binding)!!
            // Real freeze traverses this source. Simulate preparation work, not a sleeping timer.
            val slowOriginals = object : AbstractList<Message>() {
                override val size: Int get() = source.originals.size
                override fun get(index: Int): Message {
                    clock.advanceMillis(60000)
                    return source.originals[index]
                }
            }
            val failure = assertThrows(ModelRequestException::class.java) {
                ModelContextSummarizer(bound, ContextPreparationCancellation())
                    .summarize(source.copy(originals = slowOriginals))
            }
            assertEquals(ModelFailure.TIMEOUT, failure.failure)
            assertEquals(0, connections)
            assertNull(clock.delay)
            assertArrayEquals(originalBytes, ArchiveCodec.encode(source.originals))
        }
    }

    @Test fun adapterPreparationExhaustionDoesNotConnect() {
        for (protocol in protocols) {
            val clock = ManualDeadline()
            var connections = 0
            val bound = ProviderSummaryModelResolver({ configuration(protocol) }, {
                connections++; Connection(success(protocol))
            }, clock).resolve(binding)!!
            val original = request().single()
            val slowBlocks = object : AbstractList<MessageBlock>() {
                override val size: Int get() = original.blocks.size
                override fun get(index: Int): MessageBlock {
                    clock.advanceMillis(60000)
                    return original.blocks[index]
                }
            }
            val deadline = bound.startDeadline()
            assertEquals(ModelFailure.TIMEOUT, assertThrows(ModelRequestException::class.java) {
                bound.transport.openWithoutTools(listOf(original.copy(blocks = slowBlocks)), deadline)
            }.failure)
            assertEquals(0, connections)
            assertNull(clock.delay)
        }
    }

    @Test fun preparationTimeIsSubtractedAndNextNeverRestartsTheDeadline() {
        for (protocol in protocols) {
            val preparationClock = ManualDeadline()
            val prepared = resolver(configuration(protocol), Connection(success(protocol)), preparationClock).resolve(binding)!!
            val source = input()
            val timedOriginals = object : AbstractList<Message>() {
                override val size: Int get() = source.originals.size
                override fun get(index: Int): Message {
                    preparationClock.advanceMillis(12000)
                    return source.originals[index]
                }
            }
            assertEquals("最终事实摘要", ModelContextSummarizer(prepared, ContextPreparationCancellation())
                .summarize(source.copy(originals = timedOriginals)))
            assertEquals(48000L, preparationClock.delay)

            val clock = ManualDeadline()
            val wire = Connection(success(protocol))
            val bound = resolver(configuration(protocol), wire, clock).resolve(binding)!!
            val deadline = bound.startDeadline()
            clock.advanceMillis(12000) // summarizer preparation
            val session = bound.transport.openWithoutTools(request(), deadline)
            clock.advanceMillis(47000) // adapter preparation / delay before next
            val events = mutableListOf<ModelEvent>()
            session.next(emptyList(), events::add)
            assertEquals(1000L, clock.delay)
            assertEquals(listOf(ModelEvent.TextFinal("summary-0", "最终事实摘要")), events)
            assertTrue(clock.closed)
            assertTrue(wire.disconnected)

            val exhausted = ManualDeadline()
            var connections = 0
            val another = ProviderSummaryModelResolver({ configuration(protocol) }, {
                connections++; Connection(success(protocol))
            }, exhausted).resolve(binding)!!
            val expiredSession = another.transport.openWithoutTools(request(), another.startDeadline())
            exhausted.advanceMillis(60000)
            assertEquals(ModelFailure.TIMEOUT, assertThrows(ModelRequestException::class.java) {
                expiredSession.next(emptyList()) { fail("expired result") }
            }.failure)
            assertEquals(0, connections)
            assertNull(exhausted.delay)
        }
    }

    @Test fun connectionReturnedAfterDeadlineIsClosedWithoutSendingOrAcceptingText() {
        for (protocol in protocols) {
            val clock = ManualDeadline()
            val wire = Connection(success(protocol))
            val bound = ProviderSummaryModelResolver({ configuration(protocol) }, {
                clock.advanceMillis(60000)
                wire
            }, clock).resolve(binding)!!
            assertEquals(ModelFailure.TIMEOUT, assertThrows(ModelRequestException::class.java) {
                ModelContextSummarizer(bound, ContextPreparationCancellation()).summarize(input())
            }.failure)
            assertEquals(0, wire.posts)
            assertTrue(wire.disconnected)
            assertTrue(clock.closed)
        }
    }

    @Test fun lateSuccessfulResponseIsRejectedEvenIfTimerHasNotFired() {
        for (protocol in protocols) {
            val clock = ManualDeadline()
            val wire = Connection(success(protocol), onRead = { clock.advanceMillis(60000) })
            assertEquals(ModelFailure.TIMEOUT, assertThrows(ModelRequestException::class.java) {
                summarize(configuration(protocol), wire, deadline = clock)
            }.failure)
            assertEquals(1, wire.posts)
            assertTrue(wire.disconnected)
            assertTrue(clock.closed)
        }
    }

    @Test fun summarizerChecksSharedDeadlineBeforeAcceptingAlreadyEmittedFinalText() {
        val clock = ManualDeadline()
        var cancelled = false
        var opens = 0
        val deadline = SummaryRequestDeadline(60000, clock::nanoTime)
        val bound = BoundSummaryModel(binding, SummaryModelTransport { _, receivedDeadline ->
            opens++
            assertSame(deadline, receivedDeadline)
            object : ModelSession {
                override fun cancel() { cancelled = true }
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    sink(ModelEvent.TextFinal("text", "must not accept"))
                    clock.advanceMillis(60000)
                }
            }
        }, 24000, 23104, { deadline })
        val source = input()
        val originalBytes = ArchiveCodec.encode(source.originals)
        assertEquals(ModelFailure.TIMEOUT, assertThrows(ModelRequestException::class.java) {
            ModelContextSummarizer(bound, ContextPreparationCancellation()).summarize(source)
        }.failure)
        assertEquals(1, opens)
        assertTrue(cancelled)
        assertArrayEquals(originalBytes, ArchiveCodec.encode(source.originals))
    }

    @Test fun monotonicDeadlineHandlesClockWrapAndRoundsOnlyRemainingTimerDelay() {
        val clock = ManualDeadline().apply { nowNanos = Long.MAX_VALUE - 500_000 }
        val deadline = SummaryRequestDeadline(2, clock::nanoTime)
        clock.nowNanos += 1_000_001 // cross nanoTime wrap; less than one millisecond left
        assertEquals(1L, deadline.remainingMillis())
        clock.nowNanos += 999_999
        assertEquals(ModelFailure.TIMEOUT, assertThrows(ModelRequestException::class.java) {
            deadline.check()
        }.failure)
    }

    private class ManualDeadline : SummaryDeadlineScheduler {
        @Volatile var nowNanos = 0L
        override fun nanoTime(): Long = nowNanos
        fun advanceMillis(millis: Long) { nowNanos += millis * 1_000_000 }
        @Volatile var delay: Long? = null
        @Volatile var closed = false
        private var action: (() -> Unit)? = null
        override fun schedule(delayMillis: Long, expire: () -> Unit): AutoCloseable {
            delay = delayMillis; action = expire
            return AutoCloseable { closed = true }
        }
        fun expire() = checkNotNull(action).invoke()
    }

    private class Connection(body: String, private val status: Int = 200,
        private val mime: String = "text/event-stream", private val block: Boolean = false,
        private val readFailure: IOException? = null, private val onRead: () -> Unit = {}) :
        HttpsURLConnection(URL("https://example.invalid/fixture")) {
        private val sent = ByteArrayOutputStream()
        private val response = body.byteInputStream()
        val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)
        @Volatile var disconnected = false
        var posts = 0
        override fun connect() {}
        override fun disconnect() { disconnected = true; released.countDown() }
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getOutputStream(): ByteArrayOutputStream { posts++; return sent }
        override fun getResponseCode() = status
        override fun getContentType() = mime
        override fun getInputStream(): InputStream {
            onRead()
            if (block) {
                entered.countDown()
                if (!released.await(5, TimeUnit.SECONDS)) throw IOException("fixture guard timed out")
                throw IOException("fixture disconnected")
            }
            readFailure?.let { throw it }
            return response
        }
        fun json() = JSONObject(sent.toString("UTF-8"))
    }

    private fun event(type: String, vararg fields: Pair<String, Any>): JSONObject = JSONObject().put("type", type).also { value ->
        fields.forEach { value.put(it.first, it.second) }
    }
    private fun frames(vararg events: JSONObject) = events.joinToString("") { "event: ${it.getString("type")}\ndata: $it\n\n" }
    private fun partialResponse() = frames(event("response.output_text.delta", "item_id" to "speculative",
        "content_index" to 0, "delta" to "不是最终摘要"))
    private fun success(protocol: ProviderProtocol): String = if (protocol == ProviderProtocol.MESSAGES) messages("end_turn") else {
        val text = JSONObject().put("type", "message").put("id", "final").put("role", "assistant")
            .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", "最终事实摘要")))
        partialResponse() + frames(event("response.reasoning_summary_text.delta", "item_id" to "reasoning",
            "summary_index" to 0, "delta" to "不要使用思考文本"),
            event("response.completed", "response" to JSONObject().put("status", "completed").put("output", JSONArray().put(text))))
    }
    private fun messages(reason: String, terminal: Boolean = true, text: String = "最终事实摘要"): String = frames(
        event("message_start", "message" to JSONObject().put("id", "msg").put("role", "assistant").put("content", JSONArray())),
        event("content_block_start", "index" to 0, "content_block" to event("text", "text" to "")),
        event("content_block_delta", "index" to 0, "delta" to event("text_delta", "text" to text)),
        event("content_block_stop", "index" to 0),
        event("message_delta", "delta" to JSONObject().put("stop_reason", reason))) +
            if (terminal) frames(event("message_stop")) else ""
    private fun tool(protocol: ProviderProtocol): String = if (protocol == ProviderProtocol.RESPONSES)
        frames(event("response.output_item.done", "item" to event("function_call", "call_id" to "call",
            "name" to "forbidden", "arguments" to "{}")))
    else frames(
        event("message_start", "message" to JSONObject().put("id", "msg").put("role", "assistant").put("content", JSONArray())),
        event("content_block_start", "index" to 0, "content_block" to event("tool_use", "id" to "call", "name" to "forbidden", "input" to JSONObject())),
        event("content_block_stop", "index" to 0),
        event("message_delta", "delta" to JSONObject().put("stop_reason", "tool_use")), event("message_stop"))
}
