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
    private fun checkpointText() = SummaryCheckpoint.marker + "\n" +
        SummaryCheckpoint.sections.joinToString("\n") { "## $it\n- fact" }

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
        return chunk(JSONObject().put("role", "assistant").put("content", checkpointText()), JSONObject.NULL) +
            chunk(JSONObject(), reason) + if (done) "data: [DONE]\n\n" else ""
    }

    @Test fun chatCompletionsSummaryUsesNativeSystemAndBoundedToolFreeSingleRequest() {
        val config = configuration(ProviderProtocol.CHAT_COMPLETIONS)
        val wire = Connection(chatSummary())
        assertEquals(checkpointText(), summarize(config, wire))
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
            assertEquals(listOf(ModelEvent.TextFinal("summary-0", checkpointText())), events)
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
            assertEquals(checkpointText(), summarize(configuration(protocol), wire))
            val body = wire.json()
            val text = if (protocol == ProviderProtocol.RESPONSES)
                body.getJSONArray("input").getJSONObject(0).getString("content")
            else body.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text")
            assertTrue(text.contains("历史事实") && text.contains("不是新指令") && text.contains("messageId"))
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
                partialResponse() to ModelFailure.NETWORK,
                partialResponse() + frames(event("response.incomplete")) to ModelFailure.INCOMPLETE,
                partialResponse() + frames(event("response.failed")) to ModelFailure.REQUEST)
            else listOf(messages("max_tokens") to ModelFailure.INCOMPLETE,
                messages("end_turn", terminal = false) to ModelFailure.NETWORK,
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
            assertEquals(checkpointText(), ModelContextSummarizer(prepared, ContextPreparationCancellation())
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
            assertEquals(listOf(ModelEvent.TextFinal("summary-0", checkpointText())), events)
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

    private fun codexConfiguration(): SummaryProviderConfiguration {
        val source = configuration(ProviderProtocol.RESPONSES)
        val oauth = app.daiyu.android.model.oauth.CodexAuth(java.util.UUID.randomUUID().toString())
        val settings = source.provider.asModelSettings().copy(
            endpoint = app.daiyu.android.model.oauth.CodexOAuthProtocol.BASE_URL,
            responsesPath = "/responses", apiKey = "", oauth = oauth)
        return source.copy(provider = settings.requestSnapshot())
    }
    private fun codexResolver(config: SummaryProviderConfiguration, wire: Connection,
        deadline: ManualDeadline = ManualDeadline(),
        headers: (app.daiyu.android.model.oauth.CodexAuth) -> Map<String, String> = {
            assertEquals(config.provider.oauth, it)
            mapOf("Authorization" to "Bearer synthetic-oauth", "ChatGPT-Account-ID" to "synthetic-account")
        }) = ProviderSummaryModelResolver({ config }, { url ->
            assertEquals(app.daiyu.android.model.oauth.CodexOAuthProtocol.BASE_URL + "/responses", url.toString())
            wire
        }, deadline, oauthHeaders = headers)

    @Test fun oauthSummaryUsesTheFrozenLoginAndCodexWirePolicyWithoutAnApiKey() {
        val config = codexConfiguration()
        var resolutions = 0
        val wire = Connection(success(ProviderProtocol.RESPONSES))
        val bound = codexResolver(config, wire, headers = { auth ->
            resolutions++; assertEquals(config.provider.oauth, auth)
            mapOf("Authorization" to "Bearer synthetic-oauth", "ChatGPT-Account-ID" to "synthetic-account")
        }).resolve(binding)!!
        assertEquals(0, resolutions); assertEquals(0, wire.posts)
        assertEquals(checkpointText(), ModelContextSummarizer(bound, ContextPreparationCancellation()).summarize(input()))
        val body = wire.json()
        assertEquals(1, resolutions); assertEquals(1, wire.posts)
        assertEquals("Bearer synthetic-oauth", wire.getRequestProperty("Authorization"))
        assertEquals("synthetic-account", wire.getRequestProperty("ChatGPT-Account-ID"))
        assertFalse(body.has("max_output_tokens")); assertFalse(body.getBoolean("store"))
        assertFalse(body.has("tools")); assertFalse(body.has("tool_choice"))
        assertTrue(body.getString("instructions").contains("只生成历史摘要"))
        assertEquals("none", body.getJSONObject("reasoning").getString("effort"))
        assertEquals("", config.provider.apiKey)
        assertTrue(config.provider.batteryEnabled && config.provider.deviceStatusEnabled)
        assertTrue(wire.disconnected)
    }

    @Test fun oauthSummaryRejectsOversizedStreamingOrTerminalTextAndDisconnectsWithoutPublishing() {
        val longText = "中".repeat(700)
        val terminal = JSONObject().put("type", "message").put("role", "assistant").put("id", "final")
            .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", longText)))
        val completed = frames(event("response.completed", "response" to JSONObject().put("status", "completed")
            .put("output", JSONArray().put(terminal))))
        val streamed = frames(event("response.output_text.delta", "item_id" to "large", "delta" to longText))
        for (body in listOf(streamed, completed)) {
            val wire = Connection(body); val bound = codexResolver(codexConfiguration(), wire).resolve(binding)!!
            val session = bound.transport.openWithoutTools(request(), bound.startDeadline())
            val published = mutableListOf<ModelEvent>()
            try { session.next(emptyList(), published::add); fail("oversized summary accepted") }
            catch (failure: IllegalArgumentException) { assertTrue(failure.message!!.contains("超过本地预算")) }
            assertTrue(published.isEmpty()); assertTrue(wire.disconnected); assertEquals(1, wire.posts)
            try { session.next(emptyList(), published::add); fail("must not retry") } catch (_: IllegalStateException) {}
            assertEquals(1, wire.posts)
        }
    }

    @Test fun oauthSummaryChecksReportedOutputIncludingHiddenReasoningWithoutLosingUsage() {
        val config = codexConfiguration()
        val body = success(ProviderProtocol.RESPONSES).replace("\"status\":\"completed\"", "\"usage\":{\"input_tokens\":12,\"output_tokens\":257},\"status\":\"completed\"")
        val wire = Connection(body); var observed: ModelEvent.Usage? = null
        val usage = BoundSummaryUsagePort("owner", {}, { observed = it })
        val bound = codexResolver(config, wire).resolve(binding, usage)!!
        try { ModelContextSummarizer(bound, ContextPreparationCancellation()).summarize(input()); fail("hidden output exceeded") }
        catch (_: IllegalArgumentException) {}
        assertEquals(257L, observed!!.outputTokens); assertTrue(wire.disconnected)
    }

    @Test fun oauthSummaryCountsTerminalOnlyReasoningAndAuthoritativeBodyTogether() {
        for (reasoning in listOf("r".repeat(2049), "r".repeat(2000))) {
            val output = JSONArray().put(JSONObject().put("type", "reasoning").put("id", "reason")
                .put("summary", JSONArray().put(JSONObject().put("type", "summary_text").put("text", reasoning))))
                .put(JSONObject().put("type", "message").put("id", "answer").put("role", "assistant")
                    .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", checkpointText()))))
            val wire = Connection(frames(event("response.completed", "response" to JSONObject().put("status", "completed").put("output", output))))
            val bound = codexResolver(codexConfiguration(), wire).resolve(binding)!!
            try { ModelContextSummarizer(bound, ContextPreparationCancellation()).summarize(input()); fail("terminal reasoning exceeded") }
            catch (failure: IllegalArgumentException) { assertTrue(failure.message!!.contains("超过本地预算")) }
            assertEquals(1, wire.posts); assertTrue(wire.disconnected)
        }
    }

    @Test fun oauthSummaryRejectsToolsIncompleteAndEmptyTerminalsWithoutFallbackOrRetry() {
        for (body in listOf(tool(ProviderProtocol.RESPONSES), partialResponse(),
            frames(event("response.completed", "response" to JSONObject().put("status", "completed").put("output", JSONArray()))))) {
            val wire = Connection(body); val bound = codexResolver(codexConfiguration(), wire).resolve(binding)!!
            try { ModelContextSummarizer(bound, ContextPreparationCancellation()).summarize(input()); fail("invalid summary") }
            catch (_: Exception) {}
            assertTrue(wire.disconnected); assertEquals(1, wire.posts)
        }
        val wire = Connection("", status = 401)
        val bound = codexResolver(codexConfiguration(), wire).resolve(binding)!!
        try { ModelContextSummarizer(bound, ContextPreparationCancellation()).summarize(input()); fail("401 accepted") }
        catch (failure: ModelRequestException) { assertEquals(ModelFailure.AUTHENTICATION, failure.failure) }
        assertEquals(1, wire.posts)
    }

    @Test fun oauthSummaryCancellationWhileResolvingCredentialsNeverConnectsTheModel() {
        val config = codexConfiguration(); val entered = CountDownLatch(1); val released = CountDownLatch(1)
        val cancellation = ContextPreparationCancellation(); val failure = AtomicReference<Throwable?>(); var connections = 0
        val resolver = ProviderSummaryModelResolver({ config }, { connections++; Connection(success(ProviderProtocol.RESPONSES)) },
            ManualDeadline(), oauthHeaders = { entered.countDown(); assertTrue(released.await(5, TimeUnit.SECONDS)); emptyMap() })
        val bound = resolver.resolve(binding)!!
        val thread = Thread { try { ModelContextSummarizer(bound, cancellation).summarize(input()) }
            catch (caught: Throwable) { failure.set(caught) } }
        thread.start(); assertTrue(entered.await(5, TimeUnit.SECONDS)); cancellation.cancel(); released.countDown(); thread.join(5000)
        assertFalse(thread.isAlive); assertTrue(failure.get() is CancellationException); assertEquals(0, connections)
    }

    @Test fun oauthSummaryDeadlineStillAbortsTheRealSession() {
        val clock = ManualDeadline(); val wire = Connection(success(ProviderProtocol.RESPONSES), onRead = { clock.expire() })
        val bound = codexResolver(codexConfiguration(), wire, clock).resolve(binding)!!
        try { ModelContextSummarizer(bound, ContextPreparationCancellation()).summarize(input()); fail("expired summary") }
        catch (failure: ModelRequestException) { assertEquals(ModelFailure.TIMEOUT, failure.failure) }
        assertTrue(wire.disconnected); assertEquals(1, wire.posts)
    }

    private fun rejection(param: String = "reasoning.effort", code: String = "unsupported_value") =
        JSONObject().put("error", JSONObject().put("code", code).put("param", param)
            .put("message", "synthetic secret must not escape")).toString()

    private fun negotiated(config: SummaryProviderConfiguration, wires: List<Connection>,
        onNext: () -> Unit = {}): Pair<() -> String, () -> Int> {
        var connections = 0
        var admissions = 0
        val deadline = ManualDeadline()
        fun bind(): BoundSummaryModel {
            val port = BoundSummaryUsagePort("owner", beforeClaim = { admissions++ }, record = {})
            return ProviderSummaryModelResolver({ config }, {
                if (connections > 0) assertTrue(wires[connections - 1].disconnected)
                wires[connections++]
            }, deadline, port).resolve(binding)!!
        }
        val first = bind()
        return ({ ModelContextSummarizer(first, ContextPreparationCancellation(), nextModel = {
            onNext(); bind()
        }).summarize(input()) }) to { admissions }
    }

    @Test fun allSummaryProtocolsExplicitlyDisableThinkingWithoutChangingTheOriginalProfile() {
        for (protocol in protocols + ProviderProtocol.CHAT_COMPLETIONS) {
            val config = configuration(protocol)
            val wire = Connection(if (protocol == ProviderProtocol.CHAT_COMPLETIONS) chatSummary() else success(protocol))
            assertEquals(checkpointText(), summarize(config, wire))
            val body = wire.json()
            when (protocol) {
                ProviderProtocol.RESPONSES -> assertEquals("none", body.getJSONObject("reasoning").getString("effort"))
                ProviderProtocol.MESSAGES -> assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
                else -> assertEquals("none", body.getString("reasoning_effort"))
            }
            assertEquals("", config.provider.requestOptions.customBody)
        }
    }

    @Test fun requiredThinkingModelStartsOffThenEscalatesOneLevelWithFreshAdmission() {
        val base = configuration(ProviderProtocol.RESPONSES)
        val config = base.copy(apiModel = "gpt-6.1-sol", provider = ProviderSnapshot.capture(
            base.provider.asModelSettings().copy(model = "gpt-6.1-sol")))
        val wires = listOf(Connection(rejection(), 400), Connection(rejection(), 422), Connection(success(ProviderProtocol.RESPONSES)))
        val (call, admissions) = negotiated(config, wires)
        assertEquals(checkpointText(), call())
        assertEquals(3, admissions())
        assertEquals(listOf("none", "minimal", "low"), wires.map { it.json().getJSONObject("reasoning").getString("effort") })
        assertEquals(1, wires.map { it.json().getJSONArray("input").toString() }.distinct().size)
        wires.forEach { assertEquals(1, it.posts); assertFalse(it.json().has("tools")); assertTrue(it.disconnected) }
        assertEquals("", config.provider.requestOptions.customBody)
    }

    @Test fun thinkingLevelExhaustionStopsAfterSevenDistinctRequests() {
        val wires = (1..7).map { Connection(rejection(), 400) }
        val (call, admissions) = negotiated(configuration(ProviderProtocol.RESPONSES), wires)
        val error = assertThrows(ModelRequestException::class.java) { call() }
        assertEquals(7, admissions())
        assertEquals(ModelFailure.REQUEST, error.failure)
        assertEquals(listOf("none", "minimal", "low", "medium", "high", "xhigh", "max"),
            wires.map { it.json().getJSONObject("reasoning").getString("effort") })
        assertTrue(error.userMessage.contains("400"))
        assertFalse(error.userMessage.contains("synthetic secret"))
    }

    @Test fun unrelatedHttpErrorsUnsupportedParametersAndSummaryFieldsNeverEscalate() {
        val cases = listOf(401 to rejection(), 403 to rejection(), 429 to rejection(), 500 to rejection(),
            400 to rejection("temperature"), 400 to rejection("reasoning.summary"),
            400 to rejection(code = "unsupported_parameter"),
            400 to rejection("thinking.budget_tokens"),
            400 to """{"error":{"type":"invalid_request_error","code":"invalid_api_key","param":"thinking","message":"thinking none is not supported"}}""",
            400 to """{"error":{"type":"invalid_request_error","code":"unsupported_parameter","param":"thinking","message":"Unsupported parameter: thinking. This endpoint only supports disabled thinking."}}""",
            400 to "<html>unavailable</html>")
        for ((status, body) in cases) {
            val wire = Connection(body, status)
            val (call, admissions) = negotiated(configuration(ProviderProtocol.RESPONSES), listOf(wire)) {
                fail("non-thinking failure cannot obtain another admission: $status $body")
            }
            val error = assertThrows(ModelRequestException::class.java) { call() }
            assertEquals(status, error.diagnostic?.httpStatus)
            assertEquals(1, admissions()); assertEquals(1, wire.posts)
        }
    }

    @Test fun missingOrNullThinkingErrorCodeNegotiatesButUnknownAndAuthCodesDoNot() {
        for (protocol in protocols + ProviderProtocol.CHAT_COMPLETIONS) {
            val param = if (protocol == ProviderProtocol.MESSAGES) "thinking.type" else "reasoning.effort"
            for (code in listOf<Any?>(null, JSONObject.NULL, "unknown_error", "invalid_api_key")) {
                val error = JSONObject().put("type", "invalid_request_error").put("param", param)
                    .put("message", "Unsupported value: none is not supported; supported values are low, medium, high")
                if (code != null) error.put("code", code)
                val response = if (protocol == ProviderProtocol.CHAT_COMPLETIONS) chatSummary() else success(protocol)
                val wires = listOf(Connection(JSONObject().put("error", error).toString(), 400), Connection(response))
                val (call, admissions) = negotiated(configuration(protocol), wires)
                if (code == null || code === JSONObject.NULL) {
                    assertEquals(checkpointText(), call()); assertEquals(2, admissions())
                } else {
                    assertThrows(ModelRequestException::class.java) { call() }; assertEquals(1, admissions())
                    assertEquals(0, wires[1].posts)
                }
            }
        }
    }

    @Test fun streamingNullCodeThinkingRejectionNegotiatesAcrossProtocols() {
        for (protocol in protocols + ProviderProtocol.CHAT_COMPLETIONS) {
            val error = JSONObject().put("type", "invalid_request_error").put("code", JSONObject.NULL)
                .put("param", "reasoning.effort").put("message", "Unsupported value: none is not supported; supported values are low, medium, high")
            val response = if (protocol == ProviderProtocol.CHAT_COMPLETIONS) chatSummary() else success(protocol)
            val wires = listOf(Connection(frames(JSONObject().put("type", "error").put("error", error))), Connection(response))
            val (call, admissions) = negotiated(configuration(protocol), wires)
            assertEquals(checkpointText(), call()); assertEquals(2, admissions())
        }
    }

    @Test fun explicitStreamingAuthRateLimitAndServerErrorsNeverNegotiateEvenWithConflictingEffortCode() {
        for (protocol in protocols + ProviderProtocol.CHAT_COMPLETIONS) {
            for ((kind, expected) in listOf("authentication_error" to ModelFailure.AUTHENTICATION,
                "permission_error" to ModelFailure.PERMISSION, "rate_limit_error" to ModelFailure.RATE_LIMIT,
                "api_error" to ModelFailure.SERVER)) {
                for (code in listOf<String?>(null, "unsupported_value")) {
                    val error = JSONObject().put("type", kind).put("param", "reasoning.effort")
                        .put("message", "thinking none is not supported")
                    if (code != null) error.put("code", code)
                    val wire = Connection(frames(JSONObject().put("type", "error").put("error", error)))
                    val (call, admissions) = negotiated(configuration(protocol), listOf(wire)) {
                        fail("non-request streaming error cannot obtain another admission")
                    }
                    val failure = assertThrows(ModelRequestException::class.java) { call() }
                    assertEquals(expected, failure.failure); assertEquals(1, admissions()); assertEquals(1, wire.posts)
                }
            }
        }
    }

    @Test fun networkFailureAfterThinkingRejectionStopsAtThatAttempt() {
        val wires = listOf(Connection(rejection(), 400), Connection("", readFailure = IOException("synthetic network failure")))
        val (call, admissions) = negotiated(configuration(ProviderProtocol.RESPONSES), wires)
        val error = assertThrows(ModelRequestException::class.java) { call() }
        assertEquals(ModelFailure.NETWORK, error.failure)
        assertEquals(2, admissions())
        assertEquals(listOf("none", "minimal"), wires.map { it.json().getJSONObject("reasoning").getString("effort") })
    }

    @Test fun nvidiaSummaryStartsAtNoneWhileConversationMappingStaysUnchanged() {
        val base = configuration(ProviderProtocol.CHAT_COMPLETIONS)
        val config = base.copy(provider = ProviderSnapshot.capture(base.provider.asModelSettings()
            .copy(endpoint = "https://integrate.api.nvidia.com/v1")))
        val wires = listOf(Connection(rejection("reasoning_effort"), 400), Connection(chatSummary()))
        val (call, admissions) = negotiated(config, wires)
        assertEquals(checkpointText(), call()); assertEquals(2, admissions())
        assertEquals(listOf("none", "minimal"), wires.map { it.json().getString("reasoning_effort") })
        val chat = ReasoningRequestPolicy.freeze(config.provider, app.daiyu.android.delegation.ReasoningEffort.OFF)
        assertEquals("low", JSONObject(chat.requestOptions.customBody).getString("reasoning_effort"))
    }

    @Test fun booleanThinkingBackendDoesNotPostDuplicateEnabledLevels() {
        val base = configuration(ProviderProtocol.CHAT_COMPLETIONS)
        val config = base.copy(provider = ProviderSnapshot.capture(base.provider.asModelSettings().copy(endpoint = "https://api.moonshot.cn/v1")))
        val wires = listOf(Connection(rejection("thinking.type"), 400), Connection(rejection("thinking.type"), 400))
        val (call, admissions) = negotiated(config, wires)
        assertThrows(ModelRequestException::class.java) { call() }
        assertEquals(2, admissions())
        assertEquals(listOf("disabled", "enabled"), wires.map { it.json().getJSONObject("thinking").getString("type") })
    }

    @Test fun messagesStreamingDisabledRejectionNegotiatesOnlyTheSummary() {
        val error = JSONObject().put("type", "error").put("error", JSONObject()
            .put("type", "invalid_request_error").put("message", "thinking cannot be disabled; only supports adaptive"))
        val wires = listOf(Connection(frames(error)), Connection(success(ProviderProtocol.MESSAGES)))
        val (call, admissions) = negotiated(configuration(ProviderProtocol.MESSAGES), wires)
        assertEquals(checkpointText(), call()); assertEquals(2, admissions())
        assertEquals("disabled", wires[0].json().getJSONObject("thinking").getString("type"))
        assertEquals("adaptive", wires[1].json().getJSONObject("thinking").getString("type"))
        assertEquals("low", wires[1].json().getJSONObject("output_config").getString("effort"))
        assertEquals(256, wires[1].json().getInt("max_tokens"))
    }

    @Test fun cancellationBetweenAttemptsDoesNotOpenTheNextProviderRequest() {
        val wire = Connection(rejection(), 400)
        val cancellation = ContextPreparationCancellation()
        var opens = 0
        val config = configuration(ProviderProtocol.RESPONSES)
        val resolver = ProviderSummaryModelResolver({ config }, { opens++; wire }, ManualDeadline())
        val bound = resolver.resolve(binding)!!
        assertThrows(CancellationException::class.java) {
            ModelContextSummarizer(bound, cancellation, nextModel = {
                cancellation.cancel(); resolver.resolve(binding)!!
            }).summarize(input())
        }
        assertEquals(1, opens); assertEquals(1, wire.posts)
    }

    @Test fun reasoningEscalationSharesTheOriginalAbsoluteDeadline() {
        val config = configuration(ProviderProtocol.RESPONSES)
        val wire = Connection(rejection(), 400)
        var opens = 0
        val clock = ManualDeadline()
        val resolver = ProviderSummaryModelResolver({ config }, { opens++; wire }, clock)
        val bound = resolver.resolve(binding)!!
        val error = assertThrows(ModelRequestException::class.java) {
            ModelContextSummarizer(bound, ContextPreparationCancellation(), nextModel = {
                clock.advanceMillis(config.deadlineMillis); resolver.resolve(binding)!!
            }).summarize(input())
        }
        assertEquals(ModelFailure.TIMEOUT, error.failure)
        assertEquals(1, opens); assertEquals(1, wire.posts)
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
        override fun getErrorStream(): InputStream? = if (status >= 300) response else null
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
            .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", checkpointText())))
        partialResponse() + frames(event("response.reasoning_summary_text.delta", "item_id" to "reasoning",
            "summary_index" to 0, "delta" to "不要使用思考文本"),
            event("response.completed", "response" to JSONObject().put("status", "completed").put("output", JSONArray().put(text))))
    }
    private fun messages(reason: String, terminal: Boolean = true, text: String = checkpointText()): String = frames(
        event("message_start", "message" to JSONObject().put("id", "msg").put("role", "assistant").put("content", JSONArray())),
        event("content_block_start", "index" to 0, "content_block" to event("text", "text" to "")),
        event("content_block_delta", "index" to 0, "delta" to event("text_delta", "text" to text)),
        event("content_block_stop", "index" to 0),
        event("message_delta", "delta" to JSONObject().put("stop_reason", reason))) +
            if (terminal) frames(event("message_stop")) else ""
    private fun tool(protocol: ProviderProtocol): String = if (protocol == ProviderProtocol.RESPONSES)
        frames(event("response.completed", "response" to JSONObject().put("status", "completed").put("output", JSONArray().put(
            event("function_call", "id" to "tool-item", "call_id" to "call", "name" to "forbidden", "arguments" to "{}")))))
    else frames(
        event("message_start", "message" to JSONObject().put("id", "msg").put("role", "assistant").put("content", JSONArray())),
        event("content_block_start", "index" to 0, "content_block" to event("tool_use", "id" to "call", "name" to "forbidden", "input" to JSONObject())),
        event("content_block_stop", "index" to 0),
        event("message_delta", "delta" to JSONObject().put("stop_reason", "tool_use")), event("message_stop"))
}
