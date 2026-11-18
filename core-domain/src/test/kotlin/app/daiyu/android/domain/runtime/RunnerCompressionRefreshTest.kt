package app.daiyu.android.domain.runtime

import app.daiyu.android.domain.*
import org.junit.Assert.*
import org.junit.Test

class RunnerCompressionRefreshTest {
    private val initial = Message(MessageId("assistant"), ConversationId("c"), RunId("r"),
        Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)
    private fun user(id: String) = initial.copy(id = MessageId(id), role = Message.USER_ROLE,
        status = RunStatus.COMPLETED, blocks = listOf(MessageBlock.Text(id, id)))
    private data class Choice(val automatic: Boolean, val model: String)
    private class Store : AtomicRuntimeStore {
        val checkpoints = mutableListOf<RunCheckpoint>()
        override fun commitCheckpoint(checkpoint: RunCheckpoint) { checkpoints += checkpoint }
        override fun reconcileCheckpoint(checkpoint: RunCheckpoint) = error("no recovery")
    }
    private inner class Runtime(start: Choice, private val saved: () -> Choice,
        private val thresholdRead: () -> Unit = {}) : RunContextRuntime {
        val summaries = mutableListOf<Choice>()
        override val automatic = start.automatic
        override val windowTokens = 1000L
        override fun localTokens(history: List<Message>, tools: List<ToolDefinition>) = when {
            history.any { it.id == MessageId("summary") } -> 10L
            history.any { row -> row.blocks.any { it is MessageBlock.Tool } } -> 900L
            else -> 100L
        }
        override fun compressionSnapshot(): RunContextRuntime {
            val selected = saved()
            return object : RunContextRuntime {
                override val automatic = selected.automatic
                override val windowTokens = this@Runtime.windowTokens
                override val pressureThresholdTokens: Long get() { thresholdRead(); return 800L }
                override fun localTokens(history: List<Message>, tools: List<ToolDefinition>) =
                    this@Runtime.localTokens(history, tools)
                override fun compact(history: List<Message>, billedTokens: Long?, localTokens: Long,
                    request: RequestCheckpoint, observe: (ModelEvent.Usage) -> Unit,
                    admit: () -> Unit, cancelled: () -> Boolean): List<Message> {
                    summaries += selected
                    admit()
                    return listOf(user("summary")) + history.takeLast(1)
                }
            }
        }
        override fun compact(history: List<Message>, billedTokens: Long?, localTokens: Long,
            request: RequestCheckpoint, observe: (ModelEvent.Usage) -> Unit,
            admit: () -> Unit, cancelled: () -> Boolean): List<Message>? = error("stale run configuration")
    }
    private var toolFinished = false
    private fun toolModel() = ModelPort { object : ModelSession {
        override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
            if (!toolFinished) sink(ModelEvent.ToolReady(ToolInvocation(ToolCallId("read"), "read", "{}")))
            else sink(ModelEvent.TextFinal("answer", "done"))
        }
        override fun cancel() {}
    } }
    private fun runner(model: ModelPort, runtime: RunContextRuntime, tool: () -> String) = ChatRunner(
        model, object : ConversationStore { override fun save(message: Message) {} }, BatteryTool { _ -> tool() },
        RunnerOptions(runtimeStore = Store(), contextRuntime = runtime)) {}

    @Test fun automaticSwitchIsReloadedAfterAToolInBothDirections() {
        for (initialAutomatic in listOf(false, true)) {
            toolFinished = false
            var saved = Choice(initialAutomatic, "old")
            val runtime = Runtime(saved, { saved })
            var tools = 0
            val result = runner(toolModel(), runtime) {
                tools++; toolFinished = true; saved = Choice(!initialAutomatic, "new"); "once"
            }.run(initial, listOf(user("source")))
            assertEquals(RunStatus.COMPLETED, result.status)
            assertEquals(1, tools)
            assertEquals(if (initialAutomatic) emptyList<Choice>() else listOf(Choice(true, "new")), runtime.summaries)
            assertEquals("once", result.blocks.filterIsInstance<MessageBlock.Tool>().single().result)
        }
    }

    @Test fun pressureDecisionAndCompactionKeepTheSameConfigurationSnapshot() {
        var saved = Choice(false, "old")
        val runtime = Runtime(saved, { saved }) { saved = Choice(false, "later") }
        val result = runner(toolModel(), runtime) {
            toolFinished = true; saved = Choice(true, "selected"); "once"
        }.run(initial, listOf(user("source")))
        assertEquals(RunStatus.COMPLETED, result.status)
        assertEquals(listOf(Choice(true, "selected")), runtime.summaries)
        assertEquals(Choice(false, "later"), saved)
    }

    @Test fun overflowRecoveryReloadsTheAutomaticSwitchAndModel() {
        for (atOpen in listOf(true, false)) for (enable in listOf(true, false)) {
            var saved = Choice(!enable, "old")
            val runtime = Runtime(saved, { saved })
            var opens = 0
            val model = ModelPort {
                if (++opens == 1 && atOpen) {
                    saved = Choice(enable, "new")
                    throw ModelRequestException(ModelFailure.CONTEXT_LIMIT)
                }
                object : ModelSession {
                    override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                        if (opens == 1 && !atOpen) {
                            saved = Choice(enable, "new")
                            throw ModelRequestException(ModelFailure.CONTEXT_LIMIT)
                        }
                        sink(ModelEvent.TextFinal("answer", "done"))
                    }
                    override fun cancel() {}
                }
            }
            val result = runner(model, runtime) { error("no tool replay") }.run(initial, listOf(user("source")))
            assertEquals(if (enable) RunStatus.COMPLETED else RunStatus.FAILED, result.status)
            assertEquals(if (enable) 2 else 1, opens)
            assertEquals(if (enable) listOf(Choice(true, "new")) else emptyList<Choice>(), runtime.summaries)
        }
    }

    @Test fun manualCompressionReceivesNewConfigurationWhileAutomaticIsOff() {
        var saved = Choice(false, "old")
        val runtime = Runtime(saved, { saved })
        lateinit var active: ChatRunner
        var completed = 0
        active = runner(toolModel(), runtime) {
            toolFinished = true; saved = Choice(false, "manual-new")
            assertTrue(active.requestCompaction({ it }) { applied, error ->
                assertTrue(applied); assertNull(error); completed++
            })
            "once"
        }
        val result = active.run(initial, listOf(user("source")))
        assertEquals(RunStatus.COMPLETED, result.status)
        assertEquals(1, completed)
        assertEquals(listOf(Choice(false, "manual-new")), runtime.summaries)
    }
}
