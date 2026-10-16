package app.daiyu.android.pet

import app.daiyu.android.domain.*
import app.daiyu.android.domain.pet.*
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.model.ProviderRequestOptions
import app.daiyu.android.storage.ModelSettings
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class AndroidPetModelPortTest {
    private val selection = PetModelSelection("pet-assistant", "provider-record", "model-record")
    private fun settings() = ModelSettings("https://example.invalid", "api-model", "not-a-real-key", true,
        deviceStatusEnabled = true, protocol = ProviderProtocol.RESPONSES,
        requestOptions = ProviderRequestOptions("{\"temperature\":0.9}"))
    private fun request(id: String = "r") = PetModelRequest(id, selection, PetEvent.FEED, "pet only prompt", "pet:$id")
    private fun textTransport(beforeReply: () -> Unit = {}) = ModelPort {
        object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                assertTrue(outputs.isEmpty())
                beforeReply()
                sink(ModelEvent.TextFinal("t", "pet reply"))
            }
            override fun cancel() {}
        }
    }

    @Test fun isolatedPublicModelSessionUsesExplicitSelectionNoToolsAndActualUsage() {
        val result = AtomicReference<PetModelResult>(); val finished = CountDownLatch(1)
        var capturedSettings: ModelSettings? = null
        val adapter = AndroidPetModelPort(PetModelResolver { selected ->
            assertEquals(selection, selected); ResolvedPetModel(selected, settings())
        }) { settings, limit ->
            capturedSettings = settings
            assertEquals(120, limit)
            object : ModelPort {
                override fun open(history: List<Message>): ModelSession = error("must use explicit empty tools")
                override fun open(history: List<Message>, definitions: List<ToolDefinition>): ModelSession {
                    assertTrue(definitions.isEmpty())
                    assertEquals(1, history.size); assertEquals("pet:r", history.single().conversationId.value)
                    assertEquals("pet only prompt", (history.single().blocks.single() as MessageBlock.Text).text)
                    return object : ModelSession {
                        override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                            assertTrue(outputs.isEmpty())
                            sink(ModelEvent.TextDelta("t", "partial"))
                            sink(ModelEvent.TextFinal("t", "{\"mood\":\"happy\",\"speech\":\"米饭真香\"}"))
                            sink(ModelEvent.Usage(41, 12))
                        }
                        override fun cancel() {}
                    }
                }
            }
        }
        try {
            adapter.complete(request()) { result.set(it); finished.countDown() }
            assertTrue(finished.await(3, TimeUnit.SECONDS))
            val response = result.get() as PetModelResult.Completed
            assertEquals(41L, response.inputTokens); assertEquals(12L, response.outputTokens)
            assertFalse(response.raw.contains("partial"))
            assertFalse(capturedSettings!!.batteryEnabled); assertFalse(capturedSettings!!.deviceStatusEnabled)
            assertEquals("{\"max_output_tokens\":120}", capturedSettings!!.requestOptions.customBody)
        } finally { adapter.close() }
    }
    @Test fun queuedRequestKeepsAcceptanceSettingsWhileNextRequestUsesEditedResolver() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val finished = CountDownLatch(3)
        val original = settings().copy(responsesPath = "/old/responses")
        val edited = settings().copy(endpoint = "https://new.invalid", model = "new-api-model",
            apiKey = "new-fake-key", protocol = ProviderProtocol.MESSAGES)
        val current = AtomicReference(original)
        val lookups = CopyOnWriteArrayList<PetModelSelection>()
        val captured = CopyOnWriteArrayList<ModelSettings>()
        val limits = CopyOnWriteArrayList<Int>()
        val results = ConcurrentHashMap<String, PetModelResult>()
        val adapter = AndroidPetModelPort(PetModelResolver {
            lookups.add(it)
            ResolvedPetModel(it, current.get())
        }) { settings, limit ->
            captured.add(settings); limits.add(limit)
            val first = captured.size == 1
            textTransport {
                if (first) { entered.countDown(); release.await() }
            }
        }
        try {
            adapter.complete(request("blocker")) { results["blocker"] = it; finished.countDown() }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            adapter.complete(request("old")) { results["old"] = it; finished.countDown() }
            // The worker is still blocked: resolution must already have captured the old configuration.
            assertEquals(2, lookups.size)
            current.set(edited)
            adapter.complete(request("new").copy(maxOutputTokens = 47)) { results["new"] = it; finished.countDown() }
            assertEquals(3, lookups.size)
            assertEquals(1, captured.size)
            assertTrue(lookups.all { it == selection })
            assertNotSame(selection, lookups[1])
            release.countDown()
            assertTrue(finished.await(3, TimeUnit.SECONDS))
            assertEquals(setOf("blocker", "old", "new"), results.keys)
            assertTrue(results.values.all { it is PetModelResult.Completed })
            assertEquals(3, captured.size); assertEquals(3, lookups.size)
            assertEquals(listOf(120, 120, 47), limits.toList())
            assertNotSame(original, captured[1])
            assertEquals(original.copy(batteryEnabled = false, deviceStatusEnabled = false,
                requestOptions = ProviderRequestOptions("{\"max_output_tokens\":120}")), captured[1])
            assertNotSame(edited, captured[2])
            assertEquals(edited.copy(batteryEnabled = false, deviceStatusEnabled = false,
                requestOptions = ProviderRequestOptions()), captured[2])
        } finally { release.countDown(); adapter.close() }
    }
    @Test fun queuedUnavailableOrMismatchedReferencesStayFailedWithoutFallbackOrRetry() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val valid = ResolvedPetModel(selection, settings())
        val current = AtomicReference<ResolvedPetModel?>(valid)
        val throwOnResolve = AtomicInteger()
        val lookups = AtomicInteger(); val opened = AtomicInteger()
        val invalid = listOf(null,
            ResolvedPetModel(selection.copy(assistantId = "main-assistant"), settings()),
            ResolvedPetModel(selection.copy(providerId = "main-provider"), settings()),
            ResolvedPetModel(selection.copy(modelId = "main-model"), settings()))
        val finished = CountDownLatch(invalid.size + 3)
        val results = ConcurrentHashMap<String, PetModelResult>()
        val adapter = AndroidPetModelPort(PetModelResolver {
            assertEquals(selection, it); lookups.incrementAndGet()
            if (throwOnResolve.get() != 0) error("lookup failed")
            current.get()
        }) { _, _ ->
            val first = opened.incrementAndGet() == 1
            textTransport {
                if (first) { entered.countDown(); release.await() }
            }
        }
        try {
            adapter.complete(request("blocker")) { results["blocker"] = it; finished.countDown() }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            invalid.forEachIndexed { index, resolved ->
                current.set(resolved)
                adapter.complete(request("invalid-$index")) { results["invalid-$index"] = it; finished.countDown() }
            }
            throwOnResolve.set(1)
            adapter.complete(request("exception")) { results["exception"] = it; finished.countDown() }
            throwOnResolve.set(0)
            current.set(valid)
            adapter.complete(request("restored")) { results["restored"] = it; finished.countDown() }
            assertEquals(invalid.size + 3, lookups.get())
            assertEquals(1, opened.get()); assertTrue(results.isEmpty())
            release.countDown()
            assertTrue(finished.await(3, TimeUnit.SECONDS))
            invalid.indices.forEach { assertTrue(results["invalid-$it"] is PetModelResult.Failed) }
            assertTrue(results["exception"] is PetModelResult.Failed)
            assertTrue(results["restored"] is PetModelResult.Completed)
            assertEquals(2, opened.get()); assertEquals(invalid.size + 3, lookups.get())
        } finally { release.countDown(); adapter.close() }
    }
    @Test fun cancellingFrozenQueuedRequestDoesNotOpenTransportOrDeliverCompletion() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val finished = CountDownLatch(1)
        val opened = AtomicInteger(); val lookups = AtomicInteger(); val callbacks = AtomicInteger()
        val adapter = AndroidPetModelPort(PetModelResolver {
            lookups.incrementAndGet(); ResolvedPetModel(it, settings())
        }) { _, _ ->
            val first = opened.incrementAndGet() == 1
            textTransport {
                if (first) { entered.countDown(); release.await() }
            }
        }
        try {
            adapter.complete(request("blocker")) {}
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val cancellation = adapter.complete(request("cancelled")) { callbacks.incrementAndGet() }
            assertEquals(2, lookups.get())
            cancellation.cancel(); cancellation.cancel()
            adapter.complete(request("sentinel")) { finished.countDown() }
            release.countDown()
            assertTrue(finished.await(3, TimeUnit.SECONDS))
            assertEquals(0, callbacks.get()); assertEquals(2, opened.get()); assertEquals(3, lookups.get())
        } finally { release.countDown(); adapter.close() }
    }
    @Test fun protocolFailureAndUnexpectedToolsAreNotSuccessfulPetReactions() {
        val failures: List<(ModelEvent) -> Unit> = listOf({}, { throw IllegalStateException("incomplete") })
        failures.forEachIndexed { index, terminal ->
            val result = AtomicReference<PetModelResult>(); val finished = CountDownLatch(1)
            val lookups = AtomicInteger(); val opened = AtomicInteger()
            val adapter = AndroidPetModelPort(PetModelResolver {
                lookups.incrementAndGet(); ResolvedPetModel(it, settings())
            }) { _, _ ->
                opened.incrementAndGet()
                ModelPort { object : ModelSession {
                    override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                        if (index == 0) sink(ModelEvent.ToolReady(ToolInvocation(ToolCallId("t"), "forbidden", "{}")))
                        else { sink(ModelEvent.TextFinal("t", "not confirmed")); terminal(ModelEvent.TextFinal("t", "")) }
                    }
                    override fun cancel() {}
                } }
            }
            try {
                adapter.complete(request()) { result.set(it); finished.countDown() }
                assertTrue(finished.await(3, TimeUnit.SECONDS)); assertTrue(result.get() is PetModelResult.Failed)
                assertEquals(1, lookups.get()); assertEquals(1, opened.get())
            } finally { adapter.close() }
        }
    }
    @Test fun closeCancelsActiveSessionAndDoesNotInventCompletion() {
        val entered = CountDownLatch(1); val cancelled = CountDownLatch(1)
        val callbacks = AtomicInteger()
        val adapter = AndroidPetModelPort(PetModelResolver { ResolvedPetModel(it, settings()) }) { _, _ ->
            ModelPort { object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    entered.countDown(); cancelled.await(3, TimeUnit.SECONDS); throw InterruptedException()
                }
                override fun cancel() { cancelled.countDown() }
            } }
        }
        adapter.complete(request()) { callbacks.incrementAndGet() }
        assertTrue(entered.await(3, TimeUnit.SECONDS)); adapter.close()
        assertTrue(cancelled.await(3, TimeUnit.SECONDS)); assertEquals(0, callbacks.get())
    }
}
