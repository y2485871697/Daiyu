package app.daiyu.android.domain.runtime

import app.daiyu.android.domain.*
import org.junit.Assert.*
import org.junit.Test

class RunnerContextRuntimeTest {
    private val initial = Message(MessageId("assistant"), ConversationId("c"), RunId("r"),
        "assistant", emptyList(), RunStatus.RUNNING)
    private fun user(id: String, text: String) = initial.copy(id = MessageId(id), role = "user",
        status = RunStatus.COMPLETED, blocks = listOf(MessageBlock.Text(id, text)))
    private class Store : AtomicRuntimeStore {
        val snapshots = mutableListOf<RunCheckpoint>()
        override fun commitCheckpoint(checkpoint: RunCheckpoint) { snapshots += checkpoint }
        override fun reconcileCheckpoint(checkpoint: RunCheckpoint) = error("no recovery")
    }
    @Test fun realUsageCompactsWithinToolRunWithSameRequestCalibrationAndNoOutputReplay() {
        val store = Store(); var opens = 0; var tools = 0; var summaries = 0
        val retained = user("summary", "short")
        val runtime = object : RunContextRuntime {
            override val windowTokens = 1000L
            override fun localTokens(history: List<Message>, tools: List<ToolDefinition>) =
                history.sumOf { 10L + it.blocks.size * 10L }
            override fun compact(history: List<Message>, billedTokens: Long?, localTokens: Long,
                request: RequestCheckpoint, observe: (ModelEvent.Usage) -> Unit,
                admit: () -> Unit, cancelled: () -> Boolean): List<Message> {
                summaries++
                assertTrue(history.any { row -> row.blocks.any { it is MessageBlock.Tool && it.result == "done" } })
                val anchor = store.snapshots.last().context!!.calibration!!
                assertEquals(900L, anchor.billedTokens)
                assertEquals(100L, anchor.localTokens)
                assertEquals(900 + localTokens - anchor.localTokens, billedTokens)
                admit()
                assertEquals(request, store.snapshots.last().context!!.summaries.single())
                observe(ModelEvent.Usage(40, 5))
                return listOf(retained) + history.takeLast(1)
            }
        }
        val model = ModelPort { history ->
            opens++
            if (opens == 2) assertEquals(retained, history.first())
            object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    assertTrue(outputs.isEmpty())
                    sink(ModelEvent.InputEstimate(100))
                    if (tools == 0) {
                        sink(ModelEvent.ToolReady(ToolInvocation(ToolCallId("call"), "read", "{}")))
                        sink(ModelEvent.Usage(900, 10))
                    } else sink(ModelEvent.TextFinal("end", "answer"))
                }
                override fun cancel() {}
            }
        }
        val runner = ChatRunner(model, object : ConversationStore { override fun save(message: Message) {} },
            BatteryTool { tools++; "done" }, RunnerOptions(runtimeStore = store, contextRuntime = runtime)) {}
        val result = runner.run(initial, listOf(user("u", "long source")))
        assertEquals(RunStatus.COMPLETED, result.status)
        assertEquals(1, tools); assertEquals(1, summaries); assertEquals(2, opens)
        assertTrue(result.blocks.any { it is MessageBlock.Tool && it.result == "done" })
        val last = store.snapshots.last()
        assertEquals(retained, last.history.first())
        assertEquals(1, last.usage.count { it.source == UsageRequestSource.SUMMARY })
        assertEquals(1, last.context!!.summaries.size)
    }
    @Test fun localEstimateAndSummaryUsageNeverTriggerMainPressure() {
        val store = Store(); var compact = 0
        val runtime = object : RunContextRuntime {
            override val windowTokens = 1000L
            override fun localTokens(history: List<Message>, tools: List<ToolDefinition>) = 100L
            override fun compact(history: List<Message>, billedTokens: Long?, localTokens: Long,
                request: RequestCheckpoint, observe: (ModelEvent.Usage) -> Unit,
                admit: () -> Unit, cancelled: () -> Boolean): List<Message>? { compact++; return null }
        }
        val model = ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                sink(ModelEvent.InputEstimate(9000)); sink(ModelEvent.Usage(null, 1000))
                sink(ModelEvent.TextFinal("t", "answer"))
            }
            override fun cancel() {}
        } }
        val result = ChatRunner(model, object : ConversationStore { override fun save(message: Message) {} },
            BatteryTool { "unused" }, RunnerOptions(runtimeStore = store, contextRuntime = runtime)) {}.run(initial, emptyList())
        assertEquals(RunStatus.COMPLETED, result.status); assertEquals(0, compact)
        assertNull(store.snapshots.last().context!!.calibration)
    }
    @Test fun fullUsageReplacementClearsPressureAndRealUsageWorksWithoutEstimate() {
        for (estimate in listOf(false, true)) for (correction in listOf<Long?>(900L, 100L, null)) {
            val store = Store(); var compact = 0
            val runtime = object : RunContextRuntime {
                override val windowTokens = 1000L
                override fun localTokens(history: List<Message>, tools: List<ToolDefinition>) = 100L
                override fun compact(history: List<Message>, billedTokens: Long?, localTokens: Long,
                    request: RequestCheckpoint, observe: (ModelEvent.Usage) -> Unit,
                    admit: () -> Unit, cancelled: () -> Boolean): List<Message>? { compact++; return null }
            }
            val model = ModelPort { object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    if (estimate) sink(ModelEvent.InputEstimate(100))
                    sink(ModelEvent.Usage(900, 10)); sink(ModelEvent.Usage(correction, 10))
                    sink(ModelEvent.TextFinal("t", "answer"))
                }
                override fun cancel() {}
            } }
            val result = ChatRunner(model, object : ConversationStore { override fun save(message: Message) {} },
                BatteryTool { "unused" }, RunnerOptions(runtimeStore = store, contextRuntime = runtime)) {}.run(initial, emptyList())
            assertEquals(RunStatus.COMPLETED, result.status)
            assertEquals(if (correction == 900L) 1 else 0, compact)
            assertEquals(correction, store.snapshots.last().usage.single().snapshot.inputTokens)
            if (correction == null || !estimate) assertNull(store.snapshots.last().context!!.calibration)
        }
    }
    @Test fun reconnectRemapsContextAnchorsBeforeResumeAdmission() {
        val store = Store(); var opens = 0; var nexts = 0; var resumes = 0
        lateinit var original: RequestCheckpoint
        val checkedStore = object : AtomicRuntimeStore {
            override fun commitCheckpoint(checkpoint: RunCheckpoint) {
                checkpoint.context?.let { state ->
                    listOfNotNull(state.input?.request, state.calibration?.request, state.pressure)
                        .forEach { require(it in checkpoint.usageRequests) }
                }
                store.commitCheckpoint(checkpoint)
            }
            override fun reconcileCheckpoint(checkpoint: RunCheckpoint): RunCheckpoint = error("no recovery")
        }
        fun assertAnchors(checkpoint: RunCheckpoint, request: RequestCheckpoint) {
            val state = checkNotNull(checkpoint.context)
            assertEquals(RequestInputCheckpoint(request, 100L, historyTokens = 70L), state.input)
            assertEquals(RequestInputCheckpoint(request, 100L, 900L, 70L), state.calibration)
            assertEquals(request, state.pressure)
            assertEquals(listOf(request), checkpoint.usageRequests)
        }
        val runtime = object : RunContextRuntime {
            override val windowTokens = 1000L
            override fun localTokens(history: List<Message>, tools: List<ToolDefinition>) = 70L
            override fun compact(history: List<Message>, billedTokens: Long?, localTokens: Long,
                request: RequestCheckpoint, observe: (ModelEvent.Usage) -> Unit,
                admit: () -> Unit, cancelled: () -> Boolean): List<Message>? = error("pressure was cleared")
        }
        val model = ModelPort {
            opens++
            object : ResumableModelSession {
                override val canResume = true
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    nexts++; assertTrue(outputs.isEmpty())
                    original = checkNotNull(store.snapshots.last().request)
                    assertEquals(RequestOperation.NEXT, original.operation)
                    sink(ModelEvent.InputEstimate(100)); sink(ModelEvent.Usage(900, 10))
                    assertAnchors(store.snapshots.last(), original)
                    throw ModelRequestException(ModelFailure.NETWORK)
                }
                override fun resume(sink: (ModelEvent) -> Unit) {
                    resumes++
                    val admitted = store.snapshots.last(); val resumed = checkNotNull(admitted.request)
                    assertEquals(ExecutionPhase.STREAMING, admitted.phase); assertTrue(admitted.requestUnconfirmed)
                    assertEquals(RequestOperation.RESUME, resumed.operation)
                    assertEquals(original.requestId, resumed.requestId); assertEquals(original.owner, resumed.owner)
                    assertNotEquals(original.attemptId, resumed.attemptId); assertAnchors(admitted, resumed)
                    sink(ModelEvent.Usage(100, 12)); sink(ModelEvent.TextFinal("answer", "ok"))
                }
                override fun cancel() {}
            }
        }
        val result = ChatRunner(model, object : ConversationStore {
            override fun save(message: Message) = error("atomic only")
        }, BatteryTool { error("no tools") }, RunnerOptions(reconnect = ReconnectPolicy(1),
            runtimeStore = checkedStore, contextRuntime = runtime)) {}.run(initial, emptyList())
        assertEquals(RunStatus.COMPLETED, result.status)
        assertEquals("ok", result.blocks.filterIsInstance<MessageBlock.Text>().single().text)
        assertEquals(1, opens); assertEquals(1, nexts); assertEquals(1, resumes)
        assertTrue(store.snapshots.any { it.phase == ExecutionPhase.RECONNECTING })
        val last = store.snapshots.last(); val request = checkNotNull(last.request)
        assertEquals(ExecutionPhase.TERMINAL, last.phase); assertFalse(last.requestUnconfirmed)
        assertEquals(RequestOperation.RESUME, request.operation)
        assertEquals(RequestInputCheckpoint(request, 100L, 100L, 70L), last.context!!.calibration)
        assertNull(last.context!!.pressure)
        assertEquals(RequestUsageCheckpoint(request, ModelEvent.Usage(100, 12)), last.usage.single())
    }
    @Test fun manualCompressionIsDeferredPastToolsAndBeforeReopenEvenWithSupplementAndAutoDisabled() {
        val store = Store(); var opens = 0; var tools = 0; var compactions = 0; var completed = 0
        lateinit var runner: ChatRunner
        val marker = user("summary", "retained")
        val runtime = object : RunContextRuntime {
            override val automatic = false
            override val windowTokens = 1000L
            override fun localTokens(history: List<Message>, tools: List<ToolDefinition>) = 100L
            override fun compact(history: List<Message>, billedTokens: Long?, localTokens: Long,
                request: RequestCheckpoint, observe: (ModelEvent.Usage) -> Unit,
                admit: () -> Unit, cancelled: () -> Boolean): List<Message> {
                assertEquals(1, tools); assertEquals(1, opens)
                assertTrue(history.any { it.id == MessageId("supplement") })
                assertTrue(history.any { row -> row.blocks.any { it is MessageBlock.Tool && it.result == "done" } })
                admit(); observe(ModelEvent.Usage(10, 4)); compactions++
                return listOf(marker) + history.takeLast(1)
            }
        }
        val model = ModelPort { history ->
            opens++
            if (opens == 2) { assertEquals(1, compactions); assertEquals(marker, history.first()) }
            object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    sink(ModelEvent.InputEstimate(100)); sink(ModelEvent.Usage(900, 1))
                    if (tools == 0) sink(ModelEvent.ToolReady(ToolInvocation(ToolCallId("call"), "read", "{}")))
                    else sink(ModelEvent.TextFinal("answer", "ok"))
                }
                override fun cancel() {}
            }
        }
        runner = ChatRunner(model, object : ConversationStore { override fun save(message: Message) {} },
            BatteryTool {
                assertTrue(runner.requestCompaction({ runtime }) { applied, error ->
                    assertTrue(applied); assertNull(error); completed++
                })
                assertEquals(0, compactions)
                assertTrue(runner.supplement(user("supplement", "also keep this")))
                tools++; "done"
            }, RunnerOptions(runtimeStore = store, contextRuntime = runtime)) {}
        assertEquals(RunStatus.COMPLETED, runner.run(initial, listOf(user("old", "history"))).status)
        assertEquals(2, opens); assertEquals(1, compactions); assertEquals(1, completed)
    }

    @Test fun stopSettlesQueuedManualCompressionExactlyOnceWithoutOpeningSummary() {
        var summaries = 0; var completions = 0
        lateinit var runner: ChatRunner
        val runtime = object : RunContextRuntime {
            override val windowTokens = 1000L
            override fun localTokens(history: List<Message>, tools: List<ToolDefinition>) = 100L
            override fun compact(history: List<Message>, billedTokens: Long?, localTokens: Long,
                request: RequestCheckpoint, observe: (ModelEvent.Usage) -> Unit,
                admit: () -> Unit, cancelled: () -> Boolean): List<Message>? { summaries++; return null }
        }
        val model = ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                sink(ModelEvent.ToolReady(ToolInvocation(ToolCallId("call"), "read", "{}")))
            }
            override fun cancel() {}
        } }
        runner = ChatRunner(model, object : ConversationStore { override fun save(message: Message) {} }, BatteryTool {
            assertTrue(runner.requestCompaction({ summaries++; runtime }) { applied, _ ->
                assertFalse(applied); completions++; error("observer failure")
            }); runner.stop(); "done"
        }, RunnerOptions(runtimeStore = Store(), contextRuntime = runtime)) {}
        assertEquals(RunStatus.STOPPED, runner.run(initial, emptyList()).status)
        assertEquals(0, summaries); assertEquals(1, completions)
    }

    @Test fun swallowedSummaryAdmissionFailureStillStopsFurtherWritesAndRequests() {
        var commits = 0; var failedAt = -1; var opens = 0
        val store = object : AtomicRuntimeStore {
            override fun commitCheckpoint(checkpoint: RunCheckpoint) {
                commits++
                if (checkpoint.context?.summaries?.isNotEmpty() == true) {
                    failedAt = commits; error("disk failure")
                }
            }
            override fun reconcileCheckpoint(checkpoint: RunCheckpoint): RunCheckpoint = error("no recovery")
        }
        lateinit var runner: ChatRunner
        val runtime = object : RunContextRuntime {
            override val automatic = false
            override val windowTokens = 1000L
            override fun localTokens(history: List<Message>, tools: List<ToolDefinition>) = 100L
            override fun compact(history: List<Message>, billedTokens: Long?, localTokens: Long,
                request: RequestCheckpoint, observe: (ModelEvent.Usage) -> Unit,
                admit: () -> Unit, cancelled: () -> Boolean): List<Message> {
                try { admit() } catch (_: PersistenceException) {}
                return listOf(user("forged", "must not apply"))
            }
        }
        runner = ChatRunner(ModelPort { opens++; object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                assertTrue(runner.requestCompaction({ runtime }))
                sink(ModelEvent.TextFinal("answer", "ok"))
            }
            override fun cancel() {}
        } }, object : ConversationStore { override fun save(message: Message) {} }, BatteryTool { "unused" },
            RunnerOptions(runtimeStore = store, contextRuntime = runtime)) {}
        val result = runner.run(initial, emptyList())
        assertEquals(RunStatus.FAILED, result.status)
        assertTrue(failedAt > 0); assertEquals(failedAt, commits); assertEquals(1, opens)
    }

    @Test fun growingContextStopsBeforeNextRequestAndManualCompressionResumesWithoutToolReplay() {
        for (calibrated in listOf(false, true)) {
            val paused = java.util.concurrent.CountDownLatch(1)
            val failedSummary = java.util.concurrent.CountDownLatch(1)
            val checkpoints = java.util.concurrent.CopyOnWriteArrayList<RunCheckpoint>()
            val opens = java.util.concurrent.atomic.AtomicInteger()
            val nexts = java.util.concurrent.atomic.AtomicInteger()
            val tools = java.util.concurrent.atomic.AtomicInteger()
            val summaries = java.util.concurrent.atomic.AtomicInteger()
            val marker = user("compressed", "summary")
            val runtime = object : RunContextRuntime {
                override val automatic = false
                override val windowTokens = 272_000L
                override fun localTokens(history: List<Message>, tools: List<ToolDefinition>): Long = when {
                    history.any { it.id == marker.id } -> 100_000L
                    history.any { row -> row.blocks.any { it is MessageBlock.Tool && it.result != null } } ->
                        if (calibrated) 140_000L else 280_000L
                    else -> if (calibrated) 100_000L else 240_000L
                }
                override fun compact(history: List<Message>, billedTokens: Long?, localTokens: Long,
                    request: RequestCheckpoint, observe: (ModelEvent.Usage) -> Unit,
                    admit: () -> Unit, cancelled: () -> Boolean): List<Message>? {
                    assertEquals(1, tools.get()); assertEquals(1, nexts.get())
                    admit()
                    return if (summaries.incrementAndGet() == 1) null else listOf(marker)
                }
            }
            val store = object : AtomicRuntimeStore {
                override fun commitCheckpoint(checkpoint: RunCheckpoint) {
                    checkpoints += checkpoint
                    if (checkpoint.context?.capacity != null) paused.countDown()
                }
                override fun reconcileCheckpoint(checkpoint: RunCheckpoint) = error("no recovery")
            }
            val model = ModelPort { history ->
                val opened = opens.incrementAndGet()
                if (opened == 2) assertEquals(listOf(marker), history)
                object : ModelSession {
                    override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                        nexts.incrementAndGet()
                        if (opened == 1) {
                            sink(ModelEvent.InputEstimate(if (calibrated) 240_000L else 240_000L))
                            if (calibrated) sink(ModelEvent.Usage(240_000, 1))
                            sink(ModelEvent.ToolReady(ToolInvocation(ToolCallId("once"), "read", "{}")))
                        } else {
                            assertTrue(outputs.isEmpty())
                            sink(ModelEvent.TextFinal("answer", "continued"))
                        }
                    }
                    override fun cancel() {}
                }
            }
            val result = java.util.concurrent.atomic.AtomicReference<Message>()
            val runner = ChatRunner(model, object : ConversationStore { override fun save(message: Message) {} },
                BatteryTool { tools.incrementAndGet(); "done" }, RunnerOptions(runtimeStore = store, contextRuntime = runtime)) {}
            val worker = Thread { result.set(runner.run(initial, listOf(user("old", "source")))) }
            worker.start()
            try {
                assertTrue(paused.await(5, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals(ExecutionPhase.PAUSED, runner.state().phase)
                assertTrue(runner.state().contextLimited); assertFalse(runner.resume())
                val limited = checkpoints.first { it.context?.capacity != null }
                assertEquals(280_000L, limited.context!!.capacity!!.estimatedTokens)
                assertEquals(1, tools.get()); assertEquals(1, nexts.get()); assertEquals(1, opens.get())
                assertTrue(runner.requestCompaction { applied, _ -> assertFalse(applied); failedSummary.countDown() })
                assertTrue(failedSummary.await(5, java.util.concurrent.TimeUnit.SECONDS))
                assertTrue(runner.state().contextLimited); assertEquals(1, opens.get())
                assertTrue(runner.requestCompaction())
                worker.join(5000)
                assertFalse(worker.isAlive)
                assertEquals(RunStatus.COMPLETED, result.get().status)
                assertEquals(1, tools.get()); assertEquals(2, nexts.get()); assertEquals(2, opens.get())
                assertNull(checkpoints.last().context!!.capacity)
                assertTrue(result.get().blocks.any { it is MessageBlock.Tool && it.result == "done" })
                assertTrue(result.get().blocks.any { it is MessageBlock.Text && it.text == "continued" })
                assertEquals(1, checkpoints.mapNotNull { it.context?.capacity?.id }.distinct().size)
            } finally { runner.stop(); worker.join(5000) }
        }
    }

    @Test fun nonCompactableRuntimeStillEnforcesCapacityAndCanBeStopped() {
        val paused = java.util.concurrent.CountDownLatch(1)
        var opens = 0
        val runtime = object : RunContextRuntime {
            override val automatic = false
            override val canCompact = false
            override val windowTokens = 1000L
            override val sendBudgetTokens = 900L
            override fun localTokens(history: List<Message>, tools: List<ToolDefinition>) = 901L
            override fun compact(history: List<Message>, billedTokens: Long?, localTokens: Long,
                request: RequestCheckpoint, observe: (ModelEvent.Usage) -> Unit,
                admit: () -> Unit, cancelled: () -> Boolean): List<Message>? = error("not compactable")
        }
        val result = java.util.concurrent.atomic.AtomicReference<Message>()
        val runner = ChatRunner(ModelPort { opens++; error("must not open") },
            object : ConversationStore { override fun save(message: Message) {} }, BatteryTool { error("no tools") },
            RunnerOptions(contextRuntime = runtime)) { if (it.status == RunStatus.RUNNING) paused.countDown() }
        val worker = Thread { result.set(runner.run(initial, listOf(user("u", "history")))) }
        worker.start()
        try {
            val end = System.nanoTime() + 5_000_000_000L
            while (!runner.state().contextLimited && System.nanoTime() < end) Thread.yield()
            assertTrue(runner.state().contextLimited)
            assertEquals(0, opens); assertFalse(runner.requestCompaction()); assertFalse(runner.resume())
            runner.stop(); worker.join(5000)
            assertEquals(RunStatus.STOPPED, result.get().status)
        } finally { runner.stop(); worker.join(5000) }
    }

    @Test fun pauseDuringCapacityEstimateStaysResumableBeforeEnteringCapacityWait() {
        val estimating = java.util.concurrent.CountDownLatch(1)
        val estimateReleased = java.util.concurrent.CountDownLatch(1)
        val userPaused = java.util.concurrent.CountDownLatch(1)
        val limited = java.util.concurrent.CountDownLatch(1)
        val firstEstimate = java.util.concurrent.atomic.AtomicBoolean(true)
        val compressed = java.util.concurrent.atomic.AtomicBoolean()
        val opens = java.util.concurrent.atomic.AtomicInteger()
        val runtime = object : RunContextRuntime {
            override val automatic = false
            override val windowTokens = 1000L
            override fun localTokens(history: List<Message>, tools: List<ToolDefinition>): Long {
                if (firstEstimate.compareAndSet(true, false)) {
                    estimating.countDown()
                    check(estimateReleased.await(5, java.util.concurrent.TimeUnit.SECONDS))
                }
                return if (compressed.get()) 100L else 1100L
            }
            override fun compact(history: List<Message>, billedTokens: Long?, localTokens: Long,
                request: RequestCheckpoint, observe: (ModelEvent.Usage) -> Unit,
                admit: () -> Unit, cancelled: () -> Boolean): List<Message> {
                admit(); compressed.set(true); return listOf(user("summary", "kept"))
            }
        }
        val store = object : AtomicRuntimeStore {
            override fun commitCheckpoint(checkpoint: RunCheckpoint) {
                if (checkpoint.phase == ExecutionPhase.PAUSED) {
                    if (checkpoint.context?.capacity == null) userPaused.countDown() else limited.countDown()
                }
            }
            override fun reconcileCheckpoint(checkpoint: RunCheckpoint) = error("no recovery")
        }
        val runner = ChatRunner(ModelPort {
            opens.incrementAndGet()
            object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    sink(ModelEvent.TextFinal("answer", "continued"))
                }
                override fun cancel() {}
            }
        }, object : ConversationStore { override fun save(message: Message) {} }, BatteryTool { error("no tool") },
            RunnerOptions(runtimeStore = store, contextRuntime = runtime)) {}
        val result = java.util.concurrent.FutureTask { runner.run(initial, listOf(user("old", "source"))) }
        val worker = Thread(result)
        worker.start()
        try {
            assertTrue(estimating.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(runner.pause())
            estimateReleased.countDown()
            assertTrue("user pause must commit before the capacity stop", userPaused.await(2, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(runner.state().contextLimited)
            assertTrue(runner.resume())
            assertTrue(limited.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(runner.state().contextLimited)
            assertEquals(0, opens.get())
            assertTrue(runner.requestCompaction())
            assertEquals(RunStatus.COMPLETED, result.get(5, java.util.concurrent.TimeUnit.SECONDS).status)
            assertEquals(1, opens.get())
        } finally { estimateReleased.countDown(); runner.stop(); worker.join(5000) }
    }

    @Test fun compressionRetainsBilledCalibrationUntilTheNextRequestEstimate() {
        for (negativeAdjustment in listOf(false, true)) {
            val limited = java.util.concurrent.CountDownLatch(1)
            val firstSummary = java.util.concurrent.CountDownLatch(1)
            val reopened = java.util.concurrent.CountDownLatch(1)
            val summaries = java.util.concurrent.atomic.AtomicInteger()
            val opens = java.util.concurrent.atomic.AtomicInteger()
            val runtime = object : RunContextRuntime {
                override val automatic = false
                override val windowTokens = 1000L
                override val sendBudgetTokens = 900L
                override fun localTokens(history: List<Message>, tools: List<ToolDefinition>): Long = when {
                    history.any { it.id.value == "summary-2" } -> if (negativeAdjustment) 1150L else 100L
                    history.any { it.id.value == "summary-1" } -> if (negativeAdjustment) 1210L else 350L
                    history.any { row -> row.blocks.any { it is MessageBlock.Tool && it.result != null } } -> if (negativeAdjustment) 1250L else 400L
                    else -> if (negativeAdjustment) 600L else 100L
                }
                override fun compact(history: List<Message>, billedTokens: Long?, localTokens: Long,
                    request: RequestCheckpoint, observe: (ModelEvent.Usage) -> Unit,
                    admit: () -> Unit, cancelled: () -> Boolean): List<Message> {
                    admit()
                    return listOf(user("summary-${summaries.incrementAndGet()}", "kept"))
                }
            }
            val store = object : AtomicRuntimeStore {
                override fun commitCheckpoint(checkpoint: RunCheckpoint) {
                    if (checkpoint.context?.capacity != null) limited.countDown()
                }
                override fun reconcileCheckpoint(checkpoint: RunCheckpoint) = error("no recovery")
            }
            val runner = ChatRunner(ModelPort {
                val opened = opens.incrementAndGet()
                if (opened > 1) reopened.countDown()
                object : ModelSession {
                    override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                        if (opened == 1) {
                            sink(ModelEvent.InputEstimate(if (negativeAdjustment) 700L else 300L))
                            sink(ModelEvent.Usage(if (negativeAdjustment) 300L else 700L, 1))
                            sink(ModelEvent.ToolReady(ToolInvocation(ToolCallId("once"), "read", "{}")))
                        } else sink(ModelEvent.TextFinal("answer", "continued"))
                    }
                    override fun cancel() {}
                }
            }, object : ConversationStore { override fun save(message: Message) {} }, BatteryTool { "done" },
                RunnerOptions(runtimeStore = store, contextRuntime = runtime)) {}
            val result = java.util.concurrent.FutureTask { runner.run(initial, listOf(user("old", "source"))) }
            val worker = Thread(result)
            worker.start()
            try {
                assertTrue(limited.await(5, java.util.concurrent.TimeUnit.SECONDS))
                assertTrue(runner.requestCompaction { applied, _ -> assertTrue(applied); firstSummary.countDown() })
                assertTrue(firstSummary.await(5, java.util.concurrent.TimeUnit.SECONDS))
                assertFalse("compression must retain both positive and negative billed calibration adjustments",
                    reopened.await(200, java.util.concurrent.TimeUnit.MILLISECONDS))
                assertTrue(runner.state().contextLimited)
                assertTrue(runner.requestCompaction())
                assertEquals(RunStatus.COMPLETED, result.get(5, java.util.concurrent.TimeUnit.SECONDS).status)
                assertEquals(2, opens.get())
                assertEquals(2, summaries.get())
            } finally { runner.stop(); worker.join(5000) }
        }
    }

}
