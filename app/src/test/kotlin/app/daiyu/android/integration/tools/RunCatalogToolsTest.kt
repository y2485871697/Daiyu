package app.daiyu.android.integration.tools

import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.domain.tools.*
import app.daiyu.android.skills.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal fun runnerOwner(message: Message): RunExecutionOwner {
    var result: RunExecutionOwner? = null
    val receiver = object : ReceiptBatchToolExecutor {
        override fun bindOwner(owner: RunExecutionOwner) { result = owner }
        override fun execute(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean) =
            error("no tools expected")
    }
    val runner = ChatRunner(ModelPort { object : ModelSession {
        override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {}
        override fun cancel() {}
    } }, object : ConversationStore { override fun save(message: Message) {} },
        BatteryTool { error("no tools expected") }, RunnerOptions(receiptBatchExecutor = receiver)) {}
    check(runner.run(message, emptyList()).status == RunStatus.COMPLETED)
    return checkNotNull(result)
}

class RunCatalogToolsTest {
    private val owner = Message(MessageId("assistant"), ConversationId("conversation"), RunId("run"),
        "assistant", emptyList(), RunStatus.RUNNING)
    private fun ownedTools(registry: ToolRegistry, capabilities: SessionCapabilities, ledger: ToolLedger,
        conversation: ConversationId, run: RunId,
        capture: (SessionCapabilities, TaskAuthorization) -> SkillRunSession): RunCatalogTools =
        RunCatalogTools(registry, capabilities, ledger, conversation, run, capture = capture).apply { bindOwner(runnerOwner(owner)) }

    private val revision = "a".repeat(64)
    private val capabilities = SessionCapabilities("run", setOf(SkillRunSession.READ, SkillRunSession.RESOURCE))
    private val store = SkillStore(File("build/unused-skill-fixture"))
    private fun scope(auth: TaskAuthorization, body: String = "frozen body", permissions: Set<String> = capabilities.enabled) =
        SkillRunSession.create(auth, permissions, listOf(SkillRunSession.Entry("user:demo",
            CapturedSkill("demo", "Demo", revision, body, emptyMap(), body.length), store)))
    private fun call(id: String = "read", name: String = "skills_read", arguments: String =
        JSONObject().put("id", "user:demo").put("revision", revision).toString()) = ToolInvocation(ToolCallId(id), name, arguments)
    private fun confirmed(result: ToolDispatchResult) = result.outcome as DispatchOutcome.Confirmed
    private fun denied(block: () -> Unit) {
        try {block();fail("closed scope accepted")} catch (_: IllegalStateException) {}
    }
    @Test fun oneLazyCaptureSuppliesPromptAdvertisementDispatchAndReplay() {
        val captures = AtomicInteger();val ledger = InMemoryToolLedgerStore()
        lateinit var auth: TaskAuthorization;lateinit var snapshot: SkillRunSession
        val tools = ownedTools(ToolRegistry(), capabilities, ToolLedger(ledger), owner.conversationId, owner.runId) { caps, task ->
            assertSame(capabilities,caps);captures.incrementAndGet();auth=task;scope(task).also {snapshot=it}
        }
        assertEquals(0,captures.get())
        val definitions=tools.definitions
        assertEquals(setOf("skills_list","skills_read","skills_read_resource"),definitions.map {it.name}.toSet())
        assertTrue(definitions.none {it.sideEffect})
        assertTrue(definitions.single {it.name=="skills_list"}.description.contains(revision))
        assertSame(definitions,tools.definitions);assertEquals(1,captures.get())
        val first=confirmed(tools.execute(owner,listOf(call())){false}.single())
        assertFalse(first.isError);assertEquals("frozen body",JSONObject(first.payload).getString("content"))
        assertTrue(confirmed(tools.execute(owner,listOf(call())){false}.single()).replayed)
        assertEquals(1,ledger.transaction {it.toList()}.size)
        try {snapshot.read(TaskAuthorization(auth.sessionId,auth.taskId),"user:demo",revision);fail("copied identity accepted")}
        catch (_:IllegalArgumentException) {}
        tools.close();denied {snapshot.read(auth,"user:demo",revision)}
        assertTrue(tools.execute(owner,listOf(call("after"))){false}.single().outcome is DispatchOutcome.NotExecuted)
        denied {tools.definitions};assertEquals(1,captures.get())
    }
    @Test fun malformedWholeBatchAndWriteNamesCannotReserveOrConsumeReads() {
        val ledger=InMemoryToolLedgerStore();lateinit var snapshot:SkillRunSession;lateinit var auth:TaskAuthorization
        val body="x".repeat(128*1024)
        val tools=ownedTools(ToolRegistry(),capabilities,ToolLedger(ledger),owner.conversationId,owner.runId) {_,task ->
            auth=task;scope(task,body).also {snapshot=it}
        }
        tools.definitions
        for (bad in listOf(call("bad",arguments="{\"id\":\"user:demo\"}"),
            call("bad",name="skills_install_from_github",arguments="{}"),
            call("bad",name="skills_read_resource",arguments=JSONObject().put("id","user:demo").put("revision",revision).put("path","../x").toString()))) {
            assertTrue(tools.execute(owner,listOf(call(),bad)){false}.all {it.outcome is DispatchOutcome.NotExecuted})
            assertTrue(ledger.transaction {it.toList()}.isEmpty())
        }
        repeat(15) { assertEquals(body,snapshot.read(auth,"user:demo",revision)) }
        assertTrue(confirmed(tools.execute(owner,listOf(call())){false}.single()).isError) // shared budget is exhausted for another full body
        tools.close()
    }
    @Test fun preparationsNeverReadFilesAndEscapedReceiptHasIndependentLimit() {
        val body="\"".repeat(200*1024)
        val auth=TaskAuthorization("run","run")
        val snapshot=scope(auth,body)
        val tools=ScopedSkillTools.create(snapshot,auth)
        val read=tools.single {it.definition.name=="skills_read"}
        val args=read.definition.schema.parse(call().arguments)
        repeat(100) {read.prepare(args)}
        val result=read.prepare(args).execute() as ExecutionReceipt.Confirmed
        assertFalse(result.isError)
        val output=JSONObject(result.payload)
        assertEquals(body,output.getString("content"))
        assertTrue(result.payload.toByteArray().size>body.toByteArray().size)
        assertTrue(result.payload.toByteArray().size<2*1024*1024)
        snapshot.close()
    }
    @Test fun closeBeforePreparationNeverCaptures() {
        var captures=0
        val tools=ownedTools(ToolRegistry(),capabilities,ToolLedger(InMemoryToolLedgerStore()),owner.conversationId,owner.runId) {_,task ->captures++;scope(task)}
        tools.close();denied {tools.definitions};assertEquals(0,captures)
    }
    @Test fun closeDuringCaptureDoesNotWaitOrPublishLateScope() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val failure=AtomicReference<Throwable?>();lateinit var snapshot:SkillRunSession;lateinit var auth:TaskAuthorization
        val tools=ownedTools(ToolRegistry(),capabilities,ToolLedger(InMemoryToolLedgerStore()),owner.conversationId,owner.runId) {_,task ->
            auth=task;entered.countDown();check(release.await(5,TimeUnit.SECONDS));scope(task).also {snapshot=it}
        }
        val worker=Thread {try {tools.definitions;failure.set(AssertionError("revoked capture published"))} catch (_:IllegalStateException) {} catch(t:Throwable){failure.set(t)}}
        worker.start();assertTrue(entered.await(5,TimeUnit.SECONDS))
        val result = AtomicReference<RunCatalogCloseResult?>()
        try {
            tools.closeWithResult { result.set(it) }
            assertNull(result.get()) // Capture is still in flight: not drained yet.
        } finally {release.countDown()}
        worker.join(5000);assertFalse(worker.isAlive);failure.get()?.let {throw it}
        assertEquals(RunCatalogCloseResult.Drained, result.get())
        denied {snapshot.read(auth,"user:demo",revision)}
    }
    @Test fun failedCaptureCannotRecaptureAndWrongRunCannotDispatch() {
        var captures=0
        val tools=ownedTools(ToolRegistry(),capabilities,ToolLedger(InMemoryToolLedgerStore()),owner.conversationId,owner.runId) {_,_ ->captures++;error("capture_failed")}
        repeat(2) {denied {tools.definitions}}
        assertEquals(1,captures)
        try {tools.execute(owner.copy(runId=RunId("other")),listOf(call())){false};fail("foreign run accepted")}
        catch (_:IllegalArgumentException) {}
    }
    @Test fun disabledOrEmptySnapshotsDoNotAdvertiseAndResourcePermissionCannotBeAdded() {
        for (permissions in listOf(emptySet(),setOf(SkillRunSession.READ))) {
            val tools=ownedTools(ToolRegistry(),capabilities,ToolLedger(InMemoryToolLedgerStore()),owner.conversationId,owner.runId) {_,auth ->scope(auth,permissions=permissions)}
            assertEquals(if(permissions.isEmpty()) emptySet<String>() else setOf("skills_list","skills_read"),tools.definitions.map {it.name}.toSet())
            tools.close()
        }
    }
    @Test fun runnerCheckpointOwnerReachesPreparedAdapterWithOriginalCallAndAuthorization() {
        val call = ToolInvocation(ToolCallId("provider-call-17"), "owned_read", "{}")
        val checkpoints = mutableListOf<RunCheckpoint>()
        val contexts = mutableListOf<ToolExecutionContext>()
        lateinit var authorization: TaskAuthorization
        val registry = ToolRegistry().register(object : RegisteredTool {
            override val definition = app.daiyu.android.domain.tools.ToolDefinition(
                "owned_read", 1, "owner context test", SkillRunSession.READ, ToolEffect.READ_ONLY, ToolSchema(emptyList()))
            override fun prepare(arguments: ToolArguments) = PreparedTool.contextual { context ->
                assertTrue(checkpoints.isNotEmpty())
                assertEquals(context.owner.ownerId, checkpoints.last().owner)
                assertEquals(ToolDisposition.IN_FLIGHT, checkpoints.last().tools.single().disposition)
                assertSame(authorization, context.authorization)
                assertSame(call, context.invocation)
                assertTrue(context.owner.owns(owner))
                assertFalse(context.cancelled())
                contexts.add(context)
                ExecutionReceipt.Confirmed("owned-result")
            }
        })
        val tools = RunCatalogTools(registry, capabilities, ToolLedger(InMemoryToolLedgerStore()),
            owner.conversationId, owner.runId) { _, task -> authorization = task; scope(task) }
        var rounds = 0
        val model = object : ModelPort {
            override fun open(history: List<Message>): ModelSession = error("catalog route required")
            override fun open(history: List<Message>, definitions: List<app.daiyu.android.domain.ToolDefinition>): ModelSession {
                assertTrue(definitions.any { it.name == "owned_read" })
                assertTrue(checkpoints.isNotEmpty())
                return object : ModelSession {
                    override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                        if (rounds++ == 0) sink(ModelEvent.ToolReady(call))
                        else assertEquals(listOf(ToolOutput(call.id, "owned-result")), outputs)
                    }
                    override fun cancel() {}
                }
            }
        }
        val runtime = object : AtomicRuntimeStore {
            override fun commitCheckpoint(checkpoint: RunCheckpoint) { checkpoints.add(checkpoint) }
            override fun reconcileCheckpoint(checkpoint: RunCheckpoint): RunCheckpoint = error("no recovery")
        }
        val runner = ChatRunner(model, object : ConversationStore { override fun save(message: Message) {} }, tools,
            RunnerOptions(runtimeStore = runtime, receiptBatchExecutor = tools)) {}
        try {
            assertEquals(RunStatus.COMPLETED, runner.run(owner, emptyList()).status)
            val replay = tools.execute(owner, listOf(call)) { false }.single().outcome as DispatchOutcome.Confirmed
            assertTrue(replay.replayed)
            assertEquals(1, contexts.size)
            assertEquals(setOf(contexts.single().owner.ownerId), checkpoints.map { it.owner }.toSet())
            assertTrue(contexts.single().owner.ownerId.isNotBlank())
            try { tools.execute(owner.copy(id = MessageId("foreign-assistant")), listOf(call)) { false }; fail("foreign assistant") }
            catch (_: IllegalArgumentException) {}
            try { tools.bindOwner(runnerOwner(owner)); fail("second Runner owner accepted") }
            catch (_: IllegalStateException) {}
        } finally { tools.close() }
        assertTrue(contexts.single().cancelled())
    }

    @Test fun unboundCatalogCannotCaptureAndCloseCannotBeReopenedByBinding() {
        var captures = 0
        val tools = RunCatalogTools(ToolRegistry(), capabilities, ToolLedger(InMemoryToolLedgerStore()),
            owner.conversationId, owner.runId) { _, task -> captures++; scope(task) }
        denied { tools.definitions }
        assertEquals(0, captures)
        tools.close()
        denied { tools.bindOwner(runnerOwner(owner)) }
        assertEquals(0, captures)
    }

    @Test fun ownedUnknownReceiptCannotExecuteAgainThroughEitherDispatcherRoute() {
        var executions = 0
        val token = runnerOwner(owner)
        val authorization = TaskAuthorization("run", "run")
        val registry = ToolRegistry().register(object : RegisteredTool {
            override val definition = app.daiyu.android.domain.tools.ToolDefinition(
                "uncertain", 1, "uncertain read", "read", ToolEffect.READ_ONLY, ToolSchema(emptyList()))
            override fun prepare(arguments: ToolArguments) = PreparedTool.contextual {
                assertSame(token, it.owner); assertSame(authorization, it.authorization)
                executions++; ExecutionReceipt.Unknown("sent_unconfirmed")
            }
        })
        val dispatcher = ToolDispatcher(registry.snapshot(), SessionCapabilities("run", setOf("read")),
            ToolLedger(InMemoryToolLedgerStore()))
        val call = ToolInvocation(ToolCallId("provider-uncertain"), "uncertain", "{}")
        repeat(2) {
            assertTrue(dispatcher.dispatchOwned(authorization, listOf(call), token).single().outcome is DispatchOutcome.Unknown)
        }
        assertEquals(DispatchOutcome.NotExecuted("tool_execution_context_required"),
            dispatcher.dispatch(authorization, listOf(call)).single().outcome)
        assertEquals(1, executions)
    }

    @Test fun ownedWriteRequiresExactGrantBeforeReserveAndPreservesExecutionReferences() {
        val token = runnerOwner(owner)
        val effect = WriteEffect("fixture", "replace", "target", "complete desired bytes")
        val exact = TaskAuthorization("run", "run", setOf(effect))
        val call = ToolInvocation(ToolCallId("provider-write"), "owned_write", "{}")
        var executions = 0
        val registry = ToolRegistry().register(object : RegisteredTool {
            override val definition = app.daiyu.android.domain.tools.ToolDefinition(
                "owned_write", 1, "owned write", "write", ToolEffect.WRITE, ToolSchema(emptyList()))
            override fun prepare(arguments: ToolArguments) = PreparedTool.contextual(effect) {
                assertSame(token, it.owner); assertSame(exact, it.authorization); assertSame(call, it.invocation)
                executions++; ExecutionReceipt.Confirmed("done")
            }
        })
        val store = InMemoryToolLedgerStore()
        val dispatcher = ToolDispatcher(registry.snapshot(), SessionCapabilities("run", setOf("write")), ToolLedger(store))
        val unauthorized = listOf(TaskAuthorization("run", "run"),
            TaskAuthorization("run", "run", setOf(effect.copy(change = "different bytes"))))
        for (task in unauthorized) {
            assertEquals(DispatchOutcome.NotExecuted("task_write_not_authorized"),
                dispatcher.dispatchOwned(task, listOf(call), token).single().outcome)
        }
        assertEquals(DispatchOutcome.NotExecuted("tool_run_owner_mismatch"),
            dispatcher.dispatchOwned(TaskAuthorization("run", "other-task", setOf(effect)), listOf(call), token).single().outcome)
        assertTrue(store.transaction { it.toList() }.isEmpty()); assertEquals(0, executions)
        assertTrue(dispatcher.dispatchOwned(exact, listOf(call), token).single().outcome is DispatchOutcome.Confirmed)
        for (task in unauthorized) {
            assertEquals(DispatchOutcome.NotExecuted("task_write_not_authorized"),
                dispatcher.dispatchOwned(task, listOf(call), token).single().outcome)
            assertTrue(dispatcher.dispatch(task, listOf(call)).single().outcome is DispatchOutcome.NotExecuted)
        }
        assertTrue((dispatcher.dispatchOwned(exact, listOf(call), token).single().outcome as DispatchOutcome.Confirmed).replayed)
        assertEquals(1, executions)
    }

}
