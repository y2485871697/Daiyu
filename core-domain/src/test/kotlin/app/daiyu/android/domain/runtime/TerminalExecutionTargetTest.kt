package app.daiyu.android.domain.runtime

import app.daiyu.android.domain.*
import app.daiyu.android.domain.tools.TaskAuthorization
import app.daiyu.android.domain.tools.ToolExecutionContext
import org.junit.Assert.*
import org.junit.Test

class TerminalExecutionTargetTest {
    private fun owner() = RunExecutionOwner.issue(Message(MessageId("m"), ConversationId("c"), RunId("r"),
        Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING))

    @Test fun metadataSnapshotsAreOwnerAndExactInvocationScoped() {
        val first = owner()
        val second = owner()
        val call = ToolInvocation(ToolCallId("same"), "terminal_exec", "{\"command\":\"one\"}")
        val other = call.copy(arguments = "{\"command\":\"two\"}")
        val before = first.terminalTargets
        val target = TerminalExecutionTarget("linux:debian", 0, 0, true, true)
        ToolExecutionContext(first, call, TaskAuthorization("s", "r")) { false }.reportTerminalTarget(target)
        assertTrue(before.isEmpty())
        assertEquals(target, first.terminalTargets[call])
        assertNull(first.terminalTargets[other])
        assertTrue(second.terminalTargets.isEmpty())
    }

    @Test fun parallelPublicationsDoNotLoseOtherCallTargets() {
        val owner = owner()
        val threads = List(16) { index ->
            Thread {
                val call = ToolInvocation(ToolCallId("call-$index"), "terminal_exec", "{}")
                ToolExecutionContext(owner, call, TaskAuthorization("s", "r")) { false }
                    .reportTerminalTarget(TerminalExecutionTarget("android", 10000 + index))
            }.apply { start() }
        }
        threads.forEach { it.join(5000); assertFalse(it.isAlive) }
        assertEquals(16, owner.terminalTargets.size)
        assertEquals(16, owner.terminalTargets.values.map { it.uid }.toSet().size)
    }
    @Test fun publishedMetadataCannotBeMutatedThroughPublicSnapshots() {
        val owner = owner()
        val call = ToolInvocation(ToolCallId("call"), "terminal_exec", "{}")
        val target = TerminalExecutionTarget("android", 10444)
        owner.reportTerminalTarget(call, target)
        owner.reportTerminalTarget(call.copy(id = ToolCallId("second")), target)
        try {
            (owner.terminalTargets as MutableMap<ToolInvocation, TerminalExecutionTarget>)[call] = TerminalExecutionTarget("android", 0)
            fail("target snapshots must be immutable")
        } catch (_: UnsupportedOperationException) { }
        assertEquals(target, owner.terminalTargets[call])
    }

    @Test fun runnerStateIncludesTargetWhileCommandIsBlocked() {
        val initial = Message(MessageId("m"), ConversationId("c"), RunId("r"), Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)
        val call = ToolInvocation(ToolCallId("call"), "terminal_exec", "{}")
        val target = TerminalExecutionTarget("linux:debian", 0, 0, true, true)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val registry = app.daiyu.android.domain.tools.ToolRegistry().register(object : app.daiyu.android.domain.tools.RegisteredTool {
            override val definition = app.daiyu.android.domain.tools.ToolDefinition("terminal_exec", 1, "test", "terminal.test",
                app.daiyu.android.domain.tools.ToolEffect.READ_ONLY, app.daiyu.android.domain.tools.ToolSchema(emptyList()))
            override fun prepare(arguments: app.daiyu.android.domain.tools.ToolArguments) =
                app.daiyu.android.domain.tools.PreparedTool.contextual { context ->
                    context.reportTerminalTarget(target)
                    entered.countDown()
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    app.daiyu.android.domain.tools.ExecutionReceipt.Confirmed("done")
                }
        })
        val dispatcher = app.daiyu.android.domain.tools.ToolDispatcher(registry.snapshot(),
            app.daiyu.android.domain.tools.SessionCapabilities("s", setOf("terminal.test")),
            app.daiyu.android.domain.tools.ToolLedger(app.daiyu.android.domain.tools.InMemoryToolLedgerStore()))
        val executor = object : ReceiptBatchToolExecutor {
            private lateinit var bound: RunExecutionOwner
            override fun bindOwner(owner: RunExecutionOwner) { bound = owner }
            override fun execute(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean) =
                dispatcher.dispatchOwned(TaskAuthorization("s", "r"), invocations, bound, cancelled)
        }
        val catalog = object : CatalogTool {
            override val definitions = listOf(app.daiyu.android.domain.ToolDefinition("terminal_exec", "test", """{"type":"object"}"""))
            override fun execute(invocation: ToolInvocation): String = error("must use receipt dispatcher")
        }
        val session = object : ModelSession {
            private var round = 0
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                if (round++ == 0) sink(ModelEvent.ToolReady(call)) else sink(ModelEvent.TextFinal("answer", "done"))
            }
            override fun cancel() { }
        }
        val port = object : ModelPort {
            override fun open(history: List<Message>): ModelSession = session
            override fun open(history: List<Message>, definitions: List<app.daiyu.android.domain.ToolDefinition>): ModelSession = session
        }
        val notified = java.util.concurrent.atomic.AtomicReference<Map<ToolInvocation, TerminalExecutionTarget>>()
        val runner = ChatRunner(port, object : ConversationStore { override fun save(message: Message) { } }, catalog,
            RunnerOptions(receiptBatchExecutor = executor, onTerminalTargetChanged = { owner ->
                notified.set(owner.terminalTargets)
            })) { }
        val before = runner.state()
        val running = java.util.concurrent.FutureTask<Message> { runner.run(initial, emptyList()) }
        val worker = Thread(running).apply { isDaemon = true; start() }
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(running.isDone)
            val state = runner.state()
            assertEquals(ExecutionPhase.TOOL_EXECUTING, state.phase)
            assertEquals(setOf(call.id), state.activeToolCalls)
            assertEquals(mapOf(call to target), state.terminalTargets)
            assertEquals(state.terminalTargets, notified.get())
            assertTrue(before.terminalTargets.isEmpty())
        } finally { release.countDown(); worker.join(6000) }
        assertFalse(worker.isAlive)
        assertEquals(RunStatus.COMPLETED, running.get(1, java.util.concurrent.TimeUnit.SECONDS).status)
    }

    @Test fun targetNotificationRunsAfterSnapshotPublicationOutsideOwnerLockAndCannotFailCommand() {
        val initial = Message(MessageId("notify"), ConversationId("c"), RunId("r"), Message.ASSISTANT_ROLE,
            emptyList(), RunStatus.RUNNING)
        val call = ToolInvocation(ToolCallId("call"), "terminal_exec", "{}")
        val target = TerminalExecutionTarget("android", 10444)
        var notifications = 0
        val owner = RunExecutionOwner.issue(initial) { reported ->
            assertFalse(Thread.holdsLock(reported))
            assertEquals(target, reported.terminalTargets[call])
            notifications++
            throw IllegalStateException("observer failed")
        }
        owner.reportTerminalTarget(call, target)
        assertEquals(1, notifications)
        assertEquals(target, owner.terminalTargets[call])
    }

}
