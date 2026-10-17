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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class AndroidPetModelPortTest {
    @Test fun closeSnapshotToleratesConcurrentSingleEntryRemoval() {
        val backing = ConcurrentHashMap<String, String>()
        backing["request"] = "job"
        val shrinking = object : AbstractCollection<String>() {
            override val size: Int get() = backing.size
            override fun iterator(): Iterator<String> {
                backing.clear()
                return backing.values.iterator()
            }
        }
        assertEquals(emptyList<String>(), snapshotForDrain(shrinking))
        assertEquals(listOf("retained"), snapshotForDrain(listOf("retained")))
        assertEquals(listOf("a", "b"), snapshotForDrain(listOf("a", "b")))
    }

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
    /** Deliberately ignores cancellation interrupts, like a transport/factory already in native IO. */
    private fun awaitRelease(latch: CountDownLatch) {
        while (true) {
            try { check(latch.await(10, TimeUnit.SECONDS)); return }
            catch (_: InterruptedException) { /* Cancellation alone is not our authorization fence. */ }
        }
    }

    @Test fun ioRevocationWhileFactoryPausedFencesActualOpenAndQueuedWorkNotNewOwner() {
        val factoryEntered = CountDownLatch(1); val release = CountDownLatch(1)
        val revoked = CountDownLatch(1); val finished = CountDownLatch(1)
        val factories = AtomicInteger(); val opens = AtomicInteger(); val staleCallbacks = AtomicInteger()
        val adapter = AndroidPetModelPort(PetModelResolver { ResolvedPetModel(it, settings()) }) { _, _ ->
            if (factories.incrementAndGet() == 1) { factoryEntered.countDown(); awaitRelease(release) }
            ModelPort {
                opens.incrementAndGet()
                object : ModelSession {
                    override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                        sink(ModelEvent.TextFinal("t", "reply"))
                    }
                    override fun cancel() {}
                }
            }
        }
        val worker = Thread { adapter.invalidateConfiguration(); revoked.countDown() }
        try {
            adapter.complete(request("factory-paused")) { staleCallbacks.incrementAndGet() }
            assertTrue(factoryEntered.await(3, TimeUnit.SECONDS))
            adapter.complete(request("queued-old")) { staleCallbacks.incrementAndGet() }
            worker.start()
            // No executor/main-loop progress is needed for revocation to return.
            assertTrue(revoked.await(3, TimeUnit.SECONDS))
            adapter.complete(request("new-owner")) { assertTrue(it is PetModelResult.Completed); finished.countDown() }
            release.countDown()
            assertTrue(finished.await(3, TimeUnit.SECONDS)) // Executor barrier: both old jobs are drained.
            assertEquals(0, staleCallbacks.get())
            assertEquals(1, opens.get()) // Not even the already-created old transport may open.
            assertEquals(2, factories.get())
        } finally { release.countDown(); worker.join(3000); adapter.close() }
    }

    @Test fun frozenRegistryEpochIsCheckedAtActualOpenWithoutRelyingOnCancellation() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val finished = CountDownLatch(1)
        val epoch = AtomicInteger(); val factories = AtomicInteger(); val opens = AtomicInteger()
        val callbacks = AtomicInteger()
        val adapter = AndroidPetModelPort(PetModelResolver {
            val frozenEpoch = epoch.get()
            ResolvedPetModel(it, settings(), isCurrent = { epoch.get() == frozenEpoch })
        }) { _, _ ->
            if (factories.incrementAndGet() == 1) { entered.countDown(); awaitRelease(release) }
            ModelPort {
                opens.incrementAndGet()
                object : ModelSession {
                    override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                        sink(ModelEvent.TextFinal("t", "reply"))
                    }
                    override fun cancel() {}
                }
            }
        }
        try {
            adapter.complete(request("old-epoch")) { callbacks.incrementAndGet() }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            epoch.incrementAndGet() // Same IDs; no cancel or adapter invalidation to hide a missing epoch check.
            adapter.complete(request("current-epoch")) { finished.countDown() }
            release.countDown()
            assertTrue(finished.await(3, TimeUnit.SECONDS))
            assertEquals(1, opens.get()); assertEquals(0, callbacks.get())
        } finally { release.countDown(); adapter.close() }
    }

    @Test fun completedReceiptWaitingForEffectOwnerRechecksGenerationInsideDeliveryBoundary() {
        val terminal = CountDownLatch(1); val release = CountDownLatch(1)
        val checkedAfterTerminal = CountDownLatch(1); val finished = CountDownLatch(1)
        val terminalReturned = java.util.concurrent.atomic.AtomicBoolean()
        val callbacks = AtomicInteger(); val sessions = AtomicInteger()
        val owner = Any()
        val adapter = AndroidPetModelPort(PetModelResolver {
            ResolvedPetModel(it, settings(), isCurrent = {
                if (terminalReturned.get()) checkedAfterTerminal.countDown()
                true
            })
        }) { _, _ ->
            val first = sessions.incrementAndGet() == 1
            ModelPort { object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    sink(ModelEvent.TextFinal("t", "reply")); sink(ModelEvent.Usage(91, 13))
                    if (first) {
                        terminal.countDown(); awaitRelease(release)
                        terminalReturned.set(true)
                    }
                }
                override fun cancel() {}
            } }
        }
        adapter.bindOwner(owner)
        try {
            adapter.complete(request("old-receipt")) { callbacks.incrementAndGet() }
            assertTrue(terminal.await(3, TimeUnit.SECONDS))
            synchronized(owner) {
                release.countDown()
                // Worker has parsed a genuine terminal result and passed its pre-delivery check,
                // but cannot enter the effect owner. Invalidate before allowing it to enter.
                assertTrue(checkedAfterTerminal.await(3, TimeUnit.SECONDS))
                adapter.invalidateConfiguration()
            }
            adapter.complete(request("barrier")) { finished.countDown() }
            assertTrue(finished.await(3, TimeUnit.SECONDS))
            assertEquals(0, callbacks.get()) // Neither reaction nor a late usage receipt crosses.
        } finally { release.countDown(); adapter.close() }
    }

    @Test fun invalidationReturnsBeforeBlockedActualOpenAndCancelsLateSessionOnce() {
        blockedActualOpen { adapter, _ -> adapter.invalidateConfiguration() }
    }

    @Test fun closeReturnsBeforeBlockedActualOpenAndCancelsLateSessionOnce() {
        blockedActualOpen { adapter, _ -> adapter.close() }
    }

    @Test fun epochOnlyChangeDuringBlockedActualOpenCancelsLateSessionWithoutNext() {
        // Stable IDs and no adapter invalidation/cancel: the frozen registry epoch alone revokes it.
        blockedActualOpen { _, epoch -> epoch.incrementAndGet() }
    }

    private fun blockedActualOpen(revoke: (AndroidPetModelPort, AtomicInteger) -> Unit) {
        val owner = Any(); val epoch = AtomicInteger()
        val openEntered = CountDownLatch(1); val releaseOpen = CountDownLatch(1)
        val revoked = CountDownLatch(1); val cleaned = CountDownLatch(1)
        val nextCalls = AtomicInteger(); val cancelCalls = AtomicInteger(); val callbacks = AtomicInteger()
        val openHeldOwner = AtomicBoolean(); val cancelHeldOwner = AtomicBoolean()
        val adapter = AndroidPetModelPort(PetModelResolver {
            val acceptedEpoch = epoch.get()
            ResolvedPetModel(it, settings(), isCurrent = { acceptedEpoch == epoch.get() })
        }) { _, _ ->
            ModelPort {
                openHeldOwner.set(Thread.holdsLock(owner))
                openEntered.countDown()
                awaitRelease(releaseOpen) // Ignore the private Future's interrupt, including after close.
                object : ModelSession {
                    override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                        nextCalls.incrementAndGet()
                        sink(ModelEvent.TextFinal("t", "must not run"))
                    }
                    override fun cancel() {
                        cancelHeldOwner.set(Thread.holdsLock(owner))
                        cancelCalls.incrementAndGet(); cleaned.countDown()
                    }
                }
            }
        }
        adapter.bindOwner(owner)
        val revoker = Thread {
            // Real controller calls can already own this monitor when invalidating or closing.
            synchronized(owner) { revoke(adapter, epoch) }
            revoked.countDown()
        }.apply { isDaemon = true }
        var lease: PetCancel? = null
        try {
            lease = adapter.complete(request("blocked-open")) { callbacks.incrementAndGet() }
            assertTrue(openEntered.await(3, TimeUnit.SECONDS))
            revoker.start()
            assertTrue("revocation must not wait for external open", revoked.await(3, TimeUnit.SECONDS))
            assertEquals(1L, releaseOpen.count)
            assertEquals(0, cancelCalls.get()) // No session exists yet to cancel.
            releaseOpen.countDown()
            assertTrue("late session must be reclaimed even after close", cleaned.await(3, TimeUnit.SECONDS))
            lease.cancel(); lease.cancel(); adapter.close()
            assertEquals(1, cancelCalls.get())
            assertEquals(0, nextCalls.get()); assertEquals(0, callbacks.get())
            assertFalse(openHeldOwner.get()); assertFalse(cancelHeldOwner.get())
        } finally {
            releaseOpen.countDown(); lease?.cancel(); adapter.close(); revoker.join(3000)
        }
    }

    @Test fun ownerReentrantInvalidationDoesNotRunBlockingCancelInlineOrBlockNewWork() {
        blockingCancelDoesNotBlockOwnerOrWorker(invalidate = true)
    }

    @Test fun ownerReentrantLeaseCancelDoesNotRunBlockingCancelInlineOrBlockNewWork() {
        blockingCancelDoesNotBlockOwnerOrWorker(invalidate = false)
    }

    private fun blockingCancelDoesNotBlockOwnerOrWorker(invalidate: Boolean) {
        val owner = Any()
        val nextEntered = CountDownLatch(1); val releaseNext = CountDownLatch(1)
        val cancelEntered = CountDownLatch(1); val releaseCancel = CountDownLatch(1)
        val cancelFinished = CountDownLatch(1); val cancelAcquiredOwner = CountDownLatch(1)
        val revoked = CountDownLatch(1); val finished = CountDownLatch(1)
        val sessions = AtomicInteger(); val firstCancels = AtomicInteger(); val callbacks = AtomicInteger()
        val cancelHeldOwner = AtomicBoolean(); val cleanupDaemon = AtomicBoolean()
        val adapter = AndroidPetModelPort(PetModelResolver { ResolvedPetModel(it, settings()) }) { _, _ ->
            val first = sessions.incrementAndGet() == 1
            ModelPort { object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    if (first) { nextEntered.countDown(); awaitRelease(releaseNext) }
                    sink(ModelEvent.TextFinal("t", "reply"))
                }
                override fun cancel() {
                    if (first) {
                        firstCancels.incrementAndGet()
                        cancelHeldOwner.set(Thread.holdsLock(owner))
                        cleanupDaemon.set(Thread.currentThread().isDaemon)
                        cancelEntered.countDown()
                        synchronized(owner) { cancelAcquiredOwner.countDown() }
                        awaitRelease(releaseCancel)
                        cancelFinished.countDown()
                    }
                }
            } }
        }
        adapter.bindOwner(owner)
        var revoker: Thread? = null
        try {
            val lease = adapter.complete(request("old")) { callbacks.incrementAndGet() }
            assertTrue(nextEntered.await(3, TimeUnit.SECONDS))
            revoker = Thread {
                synchronized(owner) {
                    if (invalidate) adapter.invalidateConfiguration() else lease.cancel()
                }
                revoked.countDown()
            }.apply { isDaemon = true; start() }
            assertTrue("cancel must not wait for transport cleanup", revoked.await(3, TimeUnit.SECONDS))
            assertTrue(cancelEntered.await(3, TimeUnit.SECONDS))
            assertTrue("cleanup must not retain the controller owner", cancelAcquiredOwner.await(3, TimeUnit.SECONDS))
            assertFalse(cancelHeldOwner.get()); assertTrue(cleanupDaemon.get())
            assertEquals(1L, releaseCancel.count)
            // Keep the old cancel blocked while the private model worker runs a new generation.
            adapter.complete(request("new")) { finished.countDown() }
            releaseNext.countDown()
            assertTrue("cleanup must not occupy the single model worker", finished.await(3, TimeUnit.SECONDS))
            assertEquals(0, callbacks.get()); assertEquals(2, sessions.get())
            lease.cancel(); lease.cancel()
            adapter.close() // Nor may close wait for the already-running cleanup.
            assertEquals(1L, releaseCancel.count)
            assertEquals(1, firstCancels.get())
            releaseCancel.countDown()
            assertTrue(cancelFinished.await(3, TimeUnit.SECONDS))
            assertEquals(1, firstCancels.get())
        } finally {
            releaseNext.countDown(); releaseCancel.countDown(); adapter.close(); revoker?.join(3000)
        }
    }

    @Test fun successfulAndExceptionalNextBothCleanTheirSessionExactlyOnce() {
        listOf(false, true).forEach { fail ->
            val delivered = CountDownLatch(1); val cleaned = CountDownLatch(1)
            val cancelCalls = AtomicInteger(); val result = AtomicReference<PetModelResult>()
            val adapter = AndroidPetModelPort(PetModelResolver { ResolvedPetModel(it, settings()) }) { _, _ ->
                ModelPort { object : ModelSession {
                    override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                        if (fail) error("next failed")
                        sink(ModelEvent.TextFinal("t", "reply"))
                    }
                    override fun cancel() {
                        cancelCalls.incrementAndGet(); cleaned.countDown()
                        error("cleanup failure must stay isolated")
                    }
                } }
            }
            try {
                val lease = adapter.complete(request()) { result.set(it); delivered.countDown() }
                assertTrue(delivered.await(3, TimeUnit.SECONDS))
                assertTrue("finally must clean successful and failed sessions", cleaned.await(3, TimeUnit.SECONDS))
                if (fail) assertTrue(result.get() is PetModelResult.Failed)
                else assertTrue(result.get() is PetModelResult.Completed)
                lease.cancel(); lease.cancel(); adapter.close()
                assertEquals(1, cancelCalls.get())
            } finally { adapter.close() }
        }
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
