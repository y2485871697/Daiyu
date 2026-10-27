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

}
