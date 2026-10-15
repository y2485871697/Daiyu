package app.daiyu.android.domain.runtime

import app.daiyu.android.domain.*
import app.daiyu.android.domain.tools.*
import app.daiyu.android.domain.tools.ToolDefinition as RegisteredToolDefinition
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Boundary tests use only in-memory ports and bounded latches; no device or I/O side effects. */
class RunnerToolBoundaryTest {
    private val initial = Message(MessageId("assistant-1"), ConversationId("conversation-1"),
        RunId("run-1"), "assistant", emptyList(), RunStatus.RUNNING)
    private val call = ToolInvocation(ToolCallId("call-1"), "read", "{}")

    private fun store(writes: MutableList<Message> = mutableListOf()) = object : ConversationStore {
        override fun save(message: Message) { writes += message }
    }

    @Test fun succeededJournalEntryIsReusedWithoutExecutingTool() {
        val writes = mutableListOf<RunCheckpoint>()
        var opens = 0
        var executions = 0
        var claims = 0
        val journal = object : ToolExecutionJournal {
            override val durable = true
            override fun claim(message: Message, invocation: ToolInvocation): ToolClaim {
                claims++
                assertEquals(call, invocation)
                return ToolClaim.Cached("cached-result")
            }
            override fun complete(message: Message, invocation: ToolInvocation, result: String) {
                fail("a cached result must not be completed again")
            }
        }
        val runtime = object : RuntimeStore {
            override fun save(checkpoint: RunCheckpoint) { writes += checkpoint }
        }
        val runner = ChatRunner(ModelPort {
            opens++
            object : ModelSession {
                var round = 0
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    if (round++ == 0) sink(ModelEvent.ToolReady(call))
                    else {
                        assertEquals(listOf(ToolOutput(call.id, "cached-result")), outputs)
                        sink(ModelEvent.TextFinal("answer", "done"))
                    }
                }
                override fun cancel() {}
            }
        }, store(), BatteryTool { executions++; "must-not-run" },
            RunnerOptions(runtimeStore = runtime, toolJournal = journal)) {}

        val result = runner.run(initial, emptyList())
        assertEquals(RunStatus.COMPLETED, result.status)
        assertEquals(1, opens)
        assertEquals(1, claims)
        assertEquals(0, executions)
        assertEquals(ToolDisposition.SUCCEEDED, writes.last().tools.single().disposition)
        assertEquals("cached-result", writes.last().tools.single().result)
        assertEquals(ExecutionPhase.TERMINAL, writes.last().phase)
    }

    @Test fun unknownJournalClaimIsTerminalAndNeverAutomaticallyReplayed() {
        var executions = 0
        val writes = mutableListOf<RunCheckpoint>()
        val journal = object : ToolExecutionJournal {
            override val durable = true
            override fun claim(message: Message, invocation: ToolInvocation) = ToolClaim.Unknown
            override fun complete(message: Message, invocation: ToolInvocation, result: String) {
                fail("unknown operation cannot be completed by replay")
            }
        }
        val runner = ChatRunner(ModelPort {
            object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    sink(ModelEvent.ToolReady(call))
                }
                override fun cancel() {}
            }
        }, store(), BatteryTool { executions++; "side effect" },
            RunnerOptions(runtimeStore = object : RuntimeStore {
                override fun save(checkpoint: RunCheckpoint) { writes += checkpoint }
            }, toolJournal = journal)) {}

        val result = runner.run(initial, emptyList())
        assertEquals(RunStatus.FAILED, result.status)
        assertEquals(0, executions)
        assertEquals(ExecutionPhase.TERMINAL, writes.last().phase)
        assertEquals(ToolDisposition.UNKNOWN, writes.last().tools.single().disposition)
        assertTrue(result.error!!.contains("禁止重放"))
    }

    @Test fun failedBatchMarksEveryCallUnknownBeforeTerminalFailure() {
        val second = call.copy(id = ToolCallId("call-2"))
        val writes = mutableListOf<RunCheckpoint>()
        var opens = 0
        var dispatched = 0
        val runner = ChatRunner(ModelPort {
            opens++
            object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    sink(ModelEvent.ToolReady(call))
                    sink(ModelEvent.ToolReady(second))
                }
                override fun cancel() {}
            }
        }, store(), BatteryTool { error("batch executor is required") }, RunnerOptions(
            runtimeStore = object : RuntimeStore {
                override fun save(checkpoint: RunCheckpoint) { writes += checkpoint }
            },
            batchExecutor = BatchToolExecutor { _, invocations ->
                dispatched++
                assertEquals(listOf(call, second), invocations)
                throw IllegalStateException("dispatcher outcome lost")
            })) {}

        val result = runner.run(initial, emptyList())
        assertEquals(RunStatus.FAILED, result.status)
        assertEquals(1, opens)
        assertEquals(1, dispatched)
        assertEquals(listOf(ToolDisposition.UNKNOWN, ToolDisposition.UNKNOWN),
            writes.last().tools.map { it.disposition })
        assertTrue(result.error!!.contains("禁止重放"))
    }

    @Test fun pauseDuringBatchIsPersistedAndResumeDoesNotReopenTransport() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pausedCheckpoint = CountDownLatch(1)
        val writes = mutableListOf<RunCheckpoint>()
        val lock = Any()
        var opens = 0
        lateinit var runner: ChatRunner
        val runtime = object : RuntimeStore {
            override fun save(checkpoint: RunCheckpoint) {
                synchronized(lock) { writes += checkpoint }
                if (checkpoint.phase == ExecutionPhase.PAUSED) pausedCheckpoint.countDown()
            }
        }
        val runnerTask = FutureTask {
            runner.run(initial, emptyList())
        }
        runner = ChatRunner(ModelPort {
            opens++
            object : ModelSession {
                var round = 0
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    if (round++ == 0) sink(ModelEvent.ToolReady(call))
                    else {
                        assertEquals("tool-result", outputs.single().output)
                        sink(ModelEvent.TextFinal("answer", "resumed"))
                    }
                }
                override fun cancel() {}
            }
        }, store(), BatteryTool { "unused" }, RunnerOptions(
            runtimeStore = runtime,
            batchExecutor = BatchToolExecutor { _, calls ->
                assertEquals(listOf(call), calls)
                assertTrue(runner.pause())
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                listOf(ToolOutput(call.id, "tool-result"))
            })) {}
        val thread = Thread(runnerTask).apply { isDaemon = true; start() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            release.countDown()
            assertTrue(pausedCheckpoint.await(5, TimeUnit.SECONDS))
            assertTrue(runner.resume())
            val result = runnerTask.get(5, TimeUnit.SECONDS)
            assertEquals(RunStatus.COMPLETED, result.status)
            assertEquals(1, opens)
            synchronized(lock) {
                assertTrue(writes.any { it.phase == ExecutionPhase.TOOL_EXECUTING &&
                    it.tools.single().disposition == ToolDisposition.IN_FLIGHT })
                assertTrue(writes.any { it.phase == ExecutionPhase.PAUSED &&
                    it.tools.single().disposition == ToolDisposition.SUCCEEDED })
                assertEquals(ExecutionPhase.TERMINAL, writes.last().phase)
            }
        } finally {
            release.countDown()
            runner.stop()
            thread.join(5000)
            assertFalse(thread.isAlive)
        }
    }

    @Test fun stopAllowsLateBatchResultToCloseToolButNeverStartsAnotherBatch() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writes = mutableListOf<RunCheckpoint>()
        var opens = 0
        lateinit var runner: ChatRunner
        val task = FutureTask { runner.run(initial, emptyList()) }
        runner = ChatRunner(ModelPort {
            opens++
            object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    sink(ModelEvent.ToolReady(call))
                }
                override fun cancel() {}
            }
        }, store(), BatteryTool { error("batch executor is required") }, RunnerOptions(
            runtimeStore = object : RuntimeStore {
                override fun save(checkpoint: RunCheckpoint) { writes += checkpoint }
            },
            batchExecutor = BatchToolExecutor { _, _ ->
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                listOf(ToolOutput(call.id, "late-result"))
            })) {}
        val thread = Thread(task).apply { isDaemon = true; start() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            runner.stop()
            release.countDown()
            val result = task.get(5, TimeUnit.SECONDS)
            assertEquals(RunStatus.STOPPED, result.status)
            assertEquals(1, opens)
            assertEquals("late-result", (result.blocks.single { it is MessageBlock.Tool } as MessageBlock.Tool).result)
            assertTrue(writes.any { it.phase == ExecutionPhase.TOOL_EXECUTING &&
                it.tools.single().disposition == ToolDisposition.IN_FLIGHT })
            assertEquals(ExecutionPhase.TERMINAL, writes.last().phase)
            assertEquals(ToolDisposition.SUCCEEDED, writes.last().tools.single().disposition)
        } finally {
            release.countDown()
            runner.stop()
            thread.join(5000)
            assertFalse(thread.isAlive)
        }
    }

    @Test fun reconnectKeepsUnconfirmedRequestUntilResumeAndTerminalToolSetIsConsistent() {
        val writes = mutableListOf<RunCheckpoint>()
        val session = object : ResumableModelSession {
            var nextCalls = 0
            var resumes = 0
            override val canResume = true
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                if (nextCalls++ == 0) sink(ModelEvent.ToolReady(call))
                else {
                    assertEquals("result", outputs.single().output)
                    sink(ModelEvent.TextDelta("answer", "prefix"))
                    throw ModelRequestException(ModelFailure.NETWORK)
                }
            }
            override fun resume(sink: (ModelEvent) -> Unit) {
                resumes++
                sink(ModelEvent.TextFinal("answer", "prefix-suffix"))
            }
            override fun cancel() {}
        }
        var opens = 0
        val runner = ChatRunner(ModelPort { opens++; session }, store(), BatteryTool { "result" },
            RunnerOptions(
                runtimeStore = object : RuntimeStore {
                    override fun save(checkpoint: RunCheckpoint) { writes += checkpoint }
                }, reconnect = ReconnectPolicy(maxAttempts = 1))) {}

        val result = runner.run(initial, emptyList())
        assertEquals(RunStatus.COMPLETED, result.status)
        assertEquals(1, opens)
        assertEquals(2, session.nextCalls)
        assertEquals(1, session.resumes)
        assertTrue(writes.any { it.phase == ExecutionPhase.RECONNECTING && it.requestUnconfirmed })
        assertFalse(writes.last().requestUnconfirmed)
        assertEquals(ExecutionPhase.TERMINAL, writes.last().phase)
        assertEquals(listOf(ToolDisposition.SUCCEEDED), writes.last().tools.map { it.disposition })
        assertEquals("prefix-suffix", result.blocks.filterIsInstance<MessageBlock.Text>().single().text)
    }

    @Test fun unknownLedgerProtectsNewRunnersCallIdsEquivalentJsonAndRoutes() {
        val ledgerStore = InMemoryToolLedgerStore()
        val schema = ToolSchema(listOf(ArgumentField("target", ArgumentType.Text()), ArgumentField("body", ArgumentType.Text())))
        var localExecutions = 0; var httpExecutions = 0; var completions = 0; var claims = 0
        fun writeRoute(name: String, execute: () -> ExecutionReceipt) = object : RegisteredTool {
            override val definition = RegisteredToolDefinition(name, 1, "Replace a note", "notes.write", ToolEffect.WRITE, schema)
            override fun prepare(arguments: ToolArguments) = PreparedTool(
                WriteEffect("notes", "replace", arguments.text("target").trim().lowercase(), arguments.text("body")), execute)
        }
        val catalog = ToolRegistry().register(writeRoute("write_note_local") {
            localExecutions++; throw IllegalStateException("remote result unavailable")
        }).register(writeRoute("write_note_http") {
            httpExecutions++; ExecutionReceipt.Confirmed("must not run")
        }).snapshot()
        // Test bridge only: semantic normalization/reservation is the production ledger's job.
        // This in-memory journal is neither a production authorization adapter nor durable storage.
        fun journal(): ToolExecutionJournal {
            val ledger = ToolLedger(ledgerStore)
            val pending = mutableMapOf<ToolCallId, LedgerEntry>()
            return object : ToolExecutionJournal {
                override val durable = false
                override fun claim(message: Message, invocation: ToolInvocation): ToolClaim {
                    claims++
                    val (definition, registered) = catalog.find(invocation.name) ?: error("unknown test tool")
                    val arguments = definition.schema.parse(invocation.arguments)
                    val effect = registered.prepare(arguments).writeEffect ?: error("write effect required")
                    val request = LedgerRequest(invocation.id.value, invocationKey(invocation, definition, arguments), effect.key())
                    return when (val reservation = ledger.reserve(message.conversationId.value, message.runId.value, listOf(request))) {
                        is Reservation.Rejected -> { check(reservation.code == "unknown_effect_protected"); ToolClaim.Unknown }
                        is Reservation.Accepted -> {
                            val entry = reservation.entries.single()
                            when (entry.state) {
                                LedgerState.CONFIRMED -> ToolClaim.Cached(entry.payload)
                                LedgerState.UNKNOWN -> {
                                    check(entry.owner == reservation.owner); pending[invocation.id] = entry; ToolClaim.Execute
                                }
                                LedgerState.NOT_EXECUTED -> error("unexpected non-execution")
                            }
                        }
                    }
                }
                override fun complete(message: Message, invocation: ToolInvocation, result: String) {
                    completions++
                    ledger.finish(pending.remove(invocation.id) ?: error("completion without reservation"), ExecutionReceipt.Confirmed(result))
                }
            }
        }
        val tools = BatteryTool { invocation ->
            val (definition, registered) = catalog.find(invocation.name) ?: error("unknown test tool")
            when (val receipt = registered.prepare(definition.schema.parse(invocation.arguments)).execute()) {
                is ExecutionReceipt.Confirmed -> receipt.payload
                is ExecutionReceipt.Unknown -> error("unconfirmed execution")
                is ExecutionReceipt.NotExecuted -> error("unexpected non-execution")
            }
        }
        fun runWithNewRunner(index: Int, invocation: ToolInvocation) {
            val checkpoints = mutableListOf<RunCheckpoint>()
            val message = initial.copy(id = MessageId("assistant-$index"), runId = RunId("run-$index"))
            val runner = ChatRunner(ModelPort { object : ModelSession {
                var round = 0
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    if (round++ == 0) sink(ModelEvent.ToolReady(invocation))
                    else sink(ModelEvent.TextFinal("answer", "unexpected success"))
                }
                override fun cancel() {}
            } }, store(), tools, RunnerOptions(runtimeStore = object : RuntimeStore {
                override fun save(checkpoint: RunCheckpoint) { checkpoints += checkpoint }
            }, toolJournal = journal())) {}
            val before = claims
            val result = runner.run(message, emptyList())
            assertEquals(before + 1, claims)
            assertEquals(RunStatus.FAILED, result.status)
            assertEquals(ExecutionPhase.TERMINAL, checkpoints.last().phase)
            assertEquals(ToolDisposition.UNKNOWN, checkpoints.last().tools.single().disposition)
        }
        val originalJson = """{"target":"a","body":"hello"}"""
        runWithNewRunner(1, ToolInvocation(ToolCallId("original"), "write_note_local", originalJson))
        assertEquals(1, localExecutions)
        runWithNewRunner(2, ToolInvocation(ToolCallId("new-call"), "write_note_local", originalJson))
        runWithNewRunner(3, ToolInvocation(ToolCallId("new-json"), "write_note_local", """{ "body" : "hello", "target" : "a" }"""))
        runWithNewRunner(4, ToolInvocation(ToolCallId("new-route"), "write_note_http", """{ "body" : "hello", "target" : " A " }"""))
        assertEquals(1, localExecutions); assertEquals(0, httpExecutions); assertEquals(0, completions)
        val rows = ledgerStore.transaction { it.toList() }
        assertEquals(1, rows.size); assertEquals("original", rows.single().callId)
        assertEquals(LedgerState.UNKNOWN, rows.single().state)
    }

}
