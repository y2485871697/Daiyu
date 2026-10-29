package app.daiyu.android.domain.tools

import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.RunExecutionOwner
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

class ToolDispatcherTest {
    private val writeSchema = ToolSchema(listOf(ArgumentField("target", ArgumentType.Text()), ArgumentField("body", ArgumentType.Text())))
    private fun effect(target: String = "a", body: String = "hello") = WriteEffect("notes", "replace", target, body)
    private fun write(name: String = "write_note", execute: () -> ExecutionReceipt) = object : RegisteredTool {
        override val definition = ToolDefinition(name, 1, "Replace a note", "notes.write", ToolEffect.WRITE, writeSchema)
        override fun prepare(arguments: ToolArguments) = PreparedTool(
            effect(arguments.text("target").trim().lowercase(), arguments.text("body")), execute)
    }
    private fun read(name: String = "read_note", kind: ToolEffect = ToolEffect.READ_ONLY, execute: () -> ExecutionReceipt) = object : RegisteredTool {
        override val definition = ToolDefinition(name, 1, "Read a note", "notes.read", kind, ToolSchema())
        override fun prepare(arguments: ToolArguments) = PreparedTool(execute = execute)
    }
    private fun call(id: String = "call", name: String = "write_note", args: String = "{\"target\":\"a\",\"body\":\"hello\"}") =
        ToolInvocation(ToolCallId(id), name, args)
    private fun task(writes: Set<WriteEffect> = setOf(effect()), taskId: String = "task", session: String = "session") = TaskAuthorization(session, taskId, writes)
    private fun dispatcher(store: ToolLedgerStore = InMemoryToolLedgerStore(), vararg tools: RegisteredTool): ToolDispatcher {
        val registry = ToolRegistry(); tools.forEach { registry.register(it) }
        return ToolDispatcher(registry.snapshot(), SessionCapabilities("session", setOf("notes.read", "notes.write")), ToolLedger(store))
    }
    private fun outcome(dispatcher: ToolDispatcher, task: TaskAuthorization = task(), call: ToolInvocation = call()) = dispatcher.dispatch(task, listOf(call)).single().outcome
    private fun code(outcome: DispatchOutcome) = (outcome as DispatchOutcome.NotExecuted).code

    @Test fun adapterExceptionPreservesSafeCategoryStopsBatchAndNeverReplays() {
        var executions = 0; var reads = 0
        val dispatcher = dispatcher(InMemoryToolLedgerStore(),
            write { executions++; throw java.io.IOException("private-command-secret") },
            read { reads++; ExecutionReceipt.Confirmed("read") })
        val result = dispatcher.dispatch(task(), listOf(call(), call("read", "read_note", "{}")))
        assertEquals("adapter_result_unconfirmed_io", (result[0].outcome as DispatchOutcome.Unknown).code)
        assertEquals("batch_stopped_before_execution", code(result[1].outcome))
        assertTrue(outcome(dispatcher, call = call("new-id")) is DispatchOutcome.Unknown)
        assertEquals(1, executions); assertEquals(0, reads)
        assertFalse(result.toString().contains("private-command-secret"))
    }

    @Test fun approvalExceptionIsNotExecutedAndDoesNotEnterAdapter() {
        val owner = RunExecutionOwner.issue(Message(MessageId("m"), ConversationId("c"), RunId("task"),
            Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING))
        var executions = 0
        val tool = object : RegisteredTool {
            override val definition = ToolDefinition("write_note", 1, "consent", "notes.write", ToolEffect.WRITE, writeSchema)
            override fun prepare(arguments: ToolArguments) = PreparedTool.approvedWrite(effect(),
                approve = { throw SecurityException("private-token") }, admit = { null },
                execute = { executions++; ExecutionReceipt.Confirmed("written") })
        }
        val result = dispatcher(InMemoryToolLedgerStore(), tool).dispatchOwned(task(emptySet()), listOf(call()), owner)
        assertEquals("write_approval_failed_security", code(result.single().outcome))
        assertEquals(0, executions); assertFalse(result.toString().contains("private-token"))
    }

    @Test fun freshConsentIsExactAndNeverMutatesTheRunGrantOrRepeatsOnReplay() {
        val owner = RunExecutionOwner.issue(Message(MessageId("m"), ConversationId("c"), RunId("task"),
            Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING))
        val original = task(emptySet())
        var approvals = 0
        var executions = 0
        var grant: TaskAuthorization? = task(setOf(effect()))
        val tool = object : RegisteredTool {
            override val definition = ToolDefinition("write_note", 1, "consent", "notes.write", ToolEffect.WRITE, writeSchema)
            override fun prepare(arguments: ToolArguments) = PreparedTool.approvedWrite(effect(),
                approve = { context -> assertSame(original, context.authorization); approvals++; grant },
                admit = { context -> assertSame(original, context.authorization); null },
                execute = { context ->
                    assertSame(grant, context.authorization)
                    assertEquals(setOf(effect()), context.authorization.allowedWrites)
                    executions++; ExecutionReceipt.Confirmed("written")
                })
        }
        val dispatcher = dispatcher(InMemoryToolLedgerStore(), tool)
        fun dispatch(id: String) = dispatcher.dispatchOwned(original, listOf(call(id)), owner).single().outcome
        grant = null
        assertEquals("write_approval_denied", code(dispatch("denied")))
        grant = task(setOf(effect(), effect("other")))
        assertEquals("write_approval_mismatch", code(dispatch("broad")))
        grant = task(setOf(effect()))
        assertTrue(dispatch("approved") is DispatchOutcome.Confirmed)
        assertTrue((dispatch("new-call") as DispatchOutcome.Confirmed).replayed)
        assertEquals(3, approvals); assertEquals(1, executions)
        assertTrue(original.allowedWrites.isEmpty())
    }

    @Test fun consentCancellationAndUnknownNeverExecuteOrApproveAgain() {
        val owner = RunExecutionOwner.issue(Message(MessageId("m"), ConversationId("c"), RunId("task"),
            Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING))
        var cancelled = false
        var approvals = 0
        var executions = 0
        var cancelOnApproval = true
        val tool = object : RegisteredTool {
            override val definition = ToolDefinition("write_note", 1, "consent", "notes.write", ToolEffect.WRITE, writeSchema)
            override fun prepare(arguments: ToolArguments) = PreparedTool.approvedWrite(effect(),
                approve = { approvals++; cancelled = cancelOnApproval; task(setOf(effect())) },
                admit = { null }, execute = { executions++; ExecutionReceipt.Unknown("sent") })
        }
        val dispatcher = dispatcher(InMemoryToolLedgerStore(), tool)
        fun dispatch(id: String) = dispatcher.dispatchOwned(task(emptySet()), listOf(call(id)), owner) { cancelled }.single().outcome
        assertEquals("cancelled_before_execution", code(dispatch("cancel")))
        assertEquals(0, executions)
        cancelled = false; cancelOnApproval = false
        assertTrue(dispatch("unknown") is DispatchOutcome.Unknown)
        assertTrue(dispatch("retry-new-id") is DispatchOutcome.Unknown)
        assertEquals(2, approvals); assertEquals(1, executions)
    }

    @Test fun capabilityNeverGrantsTaskWritesAndGrantIsExact() {
        var executions = 0
        val tool = write { executions++; ExecutionReceipt.Confirmed("ok") }
        val dispatcher = dispatcher(InMemoryToolLedgerStore(), tool)
        assertEquals("task_write_not_authorized", code(outcome(dispatcher, task(emptySet()))))
        assertEquals("task_write_not_authorized", code(outcome(dispatcher, task(setOf(effect("b"))))))
        assertEquals("task_write_not_authorized", code(outcome(dispatcher, task(setOf(effect(body = "other"))))))
        assertEquals("task_session_mismatch", code(outcome(dispatcher, task(session = "other"))))
        assertEquals(0, executions)
        assertTrue(outcome(dispatcher) is DispatchOutcome.Confirmed)
        assertEquals(1, executions)
    }

    @Test fun capabilitiesGrantsSchemaAndCatalogAreFrozen() {
        val enabled = mutableSetOf("notes.write")
        val grants = mutableSetOf(effect())
        val choices = mutableSetOf("a")
        val fields = mutableListOf(ArgumentField("target", ArgumentType.Text(choices = choices)))
        val schema = ToolSchema(fields)
        val session = SessionCapabilities("session", enabled)
        val authorization = TaskAuthorization("session", "task", grants)
        enabled.clear(); grants.clear(); choices.add("b"); fields.clear()
        assertTrue("notes.write" in session.enabled); assertTrue(effect() in authorization.allowedWrites)
        assertEquals("a", schema.parse("{\"target\":\"a\"}").text("target"))
        try { schema.parse("{\"target\":\"b\"}"); fail() } catch (_: IllegalArgumentException) {}
        var executions = 0
        val registry = ToolRegistry().register(write { executions++; ExecutionReceipt.Confirmed("ok") })
        val catalog = registry.snapshot()
        registry.register(read { executions++; ExecutionReceipt.Confirmed("read") })
        assertEquals(1, catalog.definitions.size)
        val dispatcher = ToolDispatcher(catalog, session, ToolLedger(InMemoryToolLedgerStore()))
        assertTrue(outcome(dispatcher, authorization) is DispatchOutcome.Confirmed)
        assertEquals("unknown_tool", code(outcome(dispatcher, authorization, call("other", "read_note", "{}"))))
        val disabled = ToolDispatcher(catalog, SessionCapabilities("session", emptySet()), ToolLedger(InMemoryToolLedgerStore()))
        assertEquals("capability_disabled", code(outcome(disabled)))
        assertEquals(1, executions)
    }

    @Test fun wholeBatchPrevalidationHasNoPartialExecution() {
        var executions = 0
        val store = InMemoryToolLedgerStore()
        val dispatcher = dispatcher(store, write { executions++; ExecutionReceipt.Confirmed("ok") }, read { executions++; ExecutionReceipt.Confirmed("read") })
        val first = call("read", "read_note", "{}")
        val badCalls = listOf(call("bad", "unknown", "{}"), call("bad", args = "{\"target\":\"a\"}"),
            call("bad", args = "{\"target\":\"b\",\"body\":\"hello\"}"))
        for (bad in badCalls) {
            val results = dispatcher.dispatch(task(), listOf(first, bad))
            assertTrue(results.all { it.outcome is DispatchOutcome.NotExecuted })
        }
        assertEquals(0, executions)
        assertTrue(store.transaction { it.isEmpty() })
    }

    @Test fun askUserIsSingleBatchBarrierAndNeverImplicitlyRegistered() {
        var executions = 0
        val ask = read("ask_user", ToolEffect.USER_BARRIER) { executions++; ExecutionReceipt.Confirmed("user_answer") }
        val dispatcher = dispatcher(InMemoryToolLedgerStore(), ask, read { executions++; ExecutionReceipt.Confirmed("read") })
        val mixed = dispatcher.dispatch(task(emptySet()), listOf(call("ask", "ask_user", "{}"), call("read", "read_note", "{}")))
        assertTrue(mixed.all { code(it.outcome) == "ask_user_batch_barrier" })
        assertEquals(0, executions)
        assertEquals("user_barrier_requires_start",
            code(outcome(dispatcher, task(emptySet()), call("ask", "ask_user", "{}"))))
        assertEquals(0, executions)
        val empty = ToolDispatcher(ToolRegistry().snapshot(), SessionCapabilities("session", setOf("notes.read")), ToolLedger(InMemoryToolLedgerStore()))
        assertEquals("unknown_tool", code(outcome(empty, task(), call("ask", "ask_user", "{}"))))
    }

    @Test fun duplicateIdsAndEquivalentWritesInSameBatchAreRejected() {
        var executions = 0
        val dispatcher = dispatcher(InMemoryToolLedgerStore(), write { executions++; ExecutionReceipt.Confirmed("ok") })
        assertTrue(dispatcher.dispatch(task(), listOf(call(), call())).all { code(it.outcome) == "duplicate_call_id" })
        assertTrue(dispatcher.dispatch(task(), listOf(call("one"), call("two"))).all { code(it.outcome) == "duplicate_batch_effect" })
        assertEquals(0, executions)
    }

    @Test fun unknownSurvivesNewDispatcherNewIdAndEquivalentAdapter() {
        var executions = 0
        val store = InMemoryToolLedgerStore()
        val original = write { executions++; throw IllegalStateException("synthetic-secret") }
        val alternative = write("write_note_http") { executions++; ExecutionReceipt.Confirmed("must not run") }
        val dispatcher = dispatcher(store, original, alternative)
        val first = outcome(dispatcher) as DispatchOutcome.Unknown
        assertEquals("adapter_result_unconfirmed_invalid_state", first.code)
        val restored = dispatcher(store, original, alternative)
        assertTrue(outcome(restored) is DispatchOutcome.Unknown)
        val equivalent = call("new-id", "write_note_http", "{ \"body\" : \"hello\", \"target\" : \" A \" }")
        assertTrue(outcome(restored, task(), equivalent) is DispatchOutcome.Unknown)
        val newSession = ToolDispatcher(ToolRegistry().register(alternative).snapshot(), SessionCapabilities("new-session", setOf("notes.write")), ToolLedger(store))
        assertTrue(outcome(newSession, TaskAuthorization("new-session", "new-task", setOf(effect())), equivalent) is DispatchOutcome.Unknown)
        assertEquals(1, executions)
    }

    @Test fun knownResultReplayPreservesPayloadAndDoesNotExecute() {
        var executions = 0
        val store = InMemoryToolLedgerStore()
        val tool = write { executions++; ExecutionReceipt.Confirmed("negative_receipt", isError = true) }
        val dispatcher = dispatcher(store, tool)
        val first = outcome(dispatcher) as DispatchOutcome.Confirmed
        assertFalse(first.replayed); assertTrue(first.isError)
        val restored = dispatcher(store, tool)
        for (call in listOf(call(), call("new-id", args = "{\"body\":\"hello\",\"target\":\"a\"}"))) {
            val replay = outcome(restored, call = call) as DispatchOutcome.Confirmed
            assertEquals("negative_receipt", replay.payload); assertTrue(replay.isError); assertTrue(replay.replayed)
        }
        assertEquals("call_id_conflict", code(outcome(restored, task(taskId = "different-task"))))
        assertEquals("call_id_conflict", code(outcome(restored, task(setOf(effect(body = "changed"))), call(args = "{\"target\":\"a\",\"body\":\"changed\"}"))))
        assertEquals(1, executions)
    }

    @Test fun everyCachedConfirmedDeliveryIsReadmittedAfterEarlierBatchExecution() {
        for (reason in listOf("access_revoked", "run_closed", "epoch_changed", "throw")) {
            val store = InMemoryToolLedgerStore()
            val owner = RunExecutionOwner.issue(Message(MessageId("assistant"), ConversationId("conversation"),
                RunId("task"), Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING))
            val authorization = task(emptySet())
            var changed = false
            var cachedExecutions = 0
            var freshExecutions = 0
            val events = mutableListOf<String>()
            val cached = object : RegisteredTool {
                override val definition = ToolDefinition("cached_read", 1, "cached read", "notes.read",
                    ToolEffect.READ_ONLY, ToolSchema())
                override fun prepare(arguments: ToolArguments) = PreparedTool.contextual(
                    admit = { context ->
                        assertSame(owner, context.owner)
                        assertSame(authorization, context.authorization)
                        assertEquals("cached_read", context.invocation.name)
                        events.add("admit:${context.invocation.id.value}")
                        if (changed && reason == "throw") throw IllegalStateException("synthetic-private")
                        if (changed) ExecutionReceipt.NotExecuted(reason) else null
                    },
                    execute = { context ->
                        cachedExecutions++
                        ExecutionReceipt.Confirmed("synthetic-private:${context.invocation.id.value}", isError = true)
                    })
            }
            val first = read {
                events.add("execute:first")
                freshExecutions++
                changed = true
                ExecutionReceipt.Confirmed("public")
            }
            val dispatcher = dispatcher(store, first, cached)
            val cachedCalls = listOf(call("cached-b", "cached_read", "{}"), call("cached-c", "cached_read", "{}"))
            val originals = dispatcher.dispatchOwned(authorization, cachedCalls, owner)
            assertTrue(originals.all { (it.outcome as DispatchOutcome.Confirmed).isError })
            val originalEntries = store.transaction { it.toList() }
            events.clear()

            // All preflight admissions succeed. A's synchronous callback then invalidates B and C.
            val calls = listOf(call("first", "read_note", "{}")) + cachedCalls
            val results = dispatcher.dispatchOwned(authorization, calls, owner)
            assertEquals(calls.map { it.id }, results.map { it.callId })
            assertEquals(DispatchOutcome.Confirmed("public", false), results.first().outcome)
            val denialCode = if (reason == "throw") "context_admission_failed" else reason
            assertEquals(List(2) { DispatchOutcome.NotExecuted(denialCode) }, results.drop(1).map { it.outcome })
            assertEquals(listOf("admit:cached-b", "admit:cached-c", "execute:first", "admit:cached-b", "admit:cached-c"), events)
            assertEquals(1, freshExecutions)
            assertEquals(2, cachedExecutions)
            assertEquals(originalEntries, store.transaction { rows -> rows.filter { it.callId.startsWith("cached-") } })

            // A delivery denial must not replace a durable CONFIRMED receipt with NOT_EXECUTED.
            changed = false
            val restored = dispatcher.dispatchOwned(authorization, cachedCalls, owner)
            assertEquals(originals.map { (it.outcome as DispatchOutcome.Confirmed).copy(replayed = true) }, restored.map { it.outcome })
            assertEquals(2, cachedExecutions)
            assertEquals(originalEntries, store.transaction { rows -> rows.filter { it.callId.startsWith("cached-") } })
        }
    }

    @Test fun notExecutedReceiptIsNotUnknownAndNewExplicitCallCanExecute() {
        var executions = 0
        val dispatcher = dispatcher(InMemoryToolLedgerStore(), write {
            executions++
            if (executions == 1) ExecutionReceipt.NotExecuted("pre_send_denied") else ExecutionReceipt.Confirmed("ok")
        })
        assertEquals("pre_send_denied", code(outcome(dispatcher)))
        assertEquals("pre_send_denied", code(outcome(dispatcher)))
        assertEquals(1, executions)
        assertTrue(outcome(dispatcher, call = call("explicit-new-id")) is DispatchOutcome.Confirmed)
        assertEquals(2, executions)
    }

    @Test fun persistenceFailureBeforeExecutionFailsClosed() {
        var executions = 0
        val failed = object : ToolLedgerStore {
            override fun <T> transaction(block: (MutableList<LedgerEntry>) -> T): T = throw IllegalStateException("secret")
        }
        val dispatcher = dispatcher(failed, write { executions++; ExecutionReceipt.Confirmed("ok") })
        assertEquals("ledger_unavailable", code(outcome(dispatcher)))
        assertEquals(0, executions)
    }

    @Test fun receiptPersistenceFailureLeavesDurableUnknownAndStopsRemainingBatch() {
        var executions = 0
        val durable = InMemoryToolLedgerStore()
        var transactions = 0
        val failSecond = object : ToolLedgerStore {
            override fun <T> transaction(block: (MutableList<LedgerEntry>) -> T): T {
                transactions++
                if (transactions == 2) throw IllegalStateException("receipt commit failed")
                return durable.transaction(block)
            }
        }
        val write = write { executions++; ExecutionReceipt.Confirmed("ok") }
        val read = read { executions++; ExecutionReceipt.Confirmed("read") }
        val result = dispatcher(failSecond, write, read).dispatch(task(), listOf(call(), call("later", "read_note", "{}")))
        assertEquals("receipt_persistence_failed", (result[0].outcome as DispatchOutcome.Unknown).code)
        assertTrue(result[1].outcome is DispatchOutcome.NotExecuted)
        assertEquals(1, executions)
        assertTrue(outcome(dispatcher(durable, write, read), call = call("new-id")) is DispatchOutcome.Unknown)
        assertEquals(1, executions)
    }

    @Test fun concurrentEquivalentCallCannotPassDurableReservation() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var executions = 0
        val store = InMemoryToolLedgerStore()
        val tool = write {
            executions++; entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            ExecutionReceipt.Confirmed("ok")
        }
        val first = FutureTask { outcome(dispatcher(store, tool)) }
        val thread = Thread(first); thread.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(outcome(dispatcher(store, tool), call = call("concurrent")) is DispatchOutcome.Unknown)
        } finally { release.countDown(); thread.join(5000) }
        assertTrue(first.get(5, TimeUnit.SECONDS) is DispatchOutcome.Confirmed)
        assertEquals(1, executions)
    }

    @Test fun cancellationAndMalformedEffectContractNeverInvokeAdapter() {
        var executions = 0
        val tool = write { executions++; ExecutionReceipt.Confirmed("ok") }
        val dispatcher = dispatcher(InMemoryToolLedgerStore(), tool)
        val result = dispatcher.dispatch(task(), listOf(call()), cancelled = { true })
        assertEquals("cancelled_before_execution", code(result.single().outcome))
        val broken = object : RegisteredTool {
            override val definition = tool.definition
            override fun prepare(arguments: ToolArguments) = PreparedTool { executions++; ExecutionReceipt.Confirmed("wrong") }
        }
        assertEquals("effect_contract_violation", code(outcome(dispatcher(InMemoryToolLedgerStore(), broken))))
        assertEquals(0, executions)
    }

    @Test fun reservedUnknownBeforeCrashRequiresNoRecoveryResetOrRetry() {
        var executions = 0
        val store = InMemoryToolLedgerStore()
        val tool = write { executions++; ExecutionReceipt.Confirmed("must not run") }
        val invocation = call()
        val key = invocationKey(invocation, tool.definition, writeSchema.parse(invocation.arguments))
        val reserved = ToolLedger(store).reserve("session", "task", listOf(LedgerRequest("call", key, effect().key())))
        assertTrue(reserved is Reservation.Accepted)
        // Simulate death between durable pre-reservation and callback: no finish, no recovery mutation.
        assertTrue(outcome(dispatcher(store, tool)) is DispatchOutcome.Unknown)
        assertTrue(outcome(dispatcher(store, tool), call = call("new-id")) is DispatchOutcome.Unknown)
        assertEquals(0, executions)
    }

    @Test fun ledgerConflictOrUnknownRejectsEntireNewBatchBeforeReadExecution() {
        var writes = 0; var reads = 0
        val dispatcher = dispatcher(InMemoryToolLedgerStore(),
            write { writes++; ExecutionReceipt.Unknown("remote_pending") },
            read { reads++; ExecutionReceipt.Confirmed("read") })
        assertEquals("remote_pending", (outcome(dispatcher) as DispatchOutcome.Unknown).code)
        val protected = dispatcher.dispatch(task(), listOf(call("read", "read_note", "{}"), call("new-id")))
        assertTrue(protected[0].outcome is DispatchOutcome.NotExecuted)
        assertTrue(protected[1].outcome is DispatchOutcome.Unknown)
        val conflict = dispatcher.dispatch(task(setOf(effect(body = "other"))), listOf(
            call("read", "read_note", "{}"), call(args = "{\"target\":\"a\",\"body\":\"other\"}")))
        assertTrue(conflict.all { code(it.outcome) == "call_id_conflict" })
        assertEquals(1, writes); assertEquals(0, reads)
    }

    @Test fun corruptLedgerCannotSilentlyBecomeEmptyAndExecuteAgain() {
        var executions = 0
        val store = InMemoryToolLedgerStore()
        store.transaction { rows ->
            val entry = LedgerEntry("session", "duplicate", "task", "request", null, "owner")
            rows.add(entry); rows.add(entry)
        }
        assertEquals("ledger_unavailable", code(outcome(dispatcher(store, write { executions++; ExecutionReceipt.Confirmed("must not run") }))))
        assertEquals(0, executions)
        assertEquals(2, store.transaction { it.size })
    }

    @Test fun resultsRemainInRequestedOrderEvenWhenNamesRepeat() {
        var executions = 0
        val dispatcher = dispatcher(InMemoryToolLedgerStore(), read { ExecutionReceipt.Confirmed("sample-${++executions}") })
        val calls = listOf(call("one", "read_note", "{}"), call("two", "read_note", "{}"))
        val results = dispatcher.dispatch(task(emptySet()), calls)
        assertEquals(calls.map { it.id }, results.map { it.callId })
        assertEquals(listOf("sample-1", "sample-2"), results.map { (it.outcome as DispatchOutcome.Confirmed).payload })
    }
    @Test fun contextualPlansWithoutRunnerOwnerRejectWholeBatchBeforeReservation() {
        val store = InMemoryToolLedgerStore()
        var executions = 0
        val owned = object : RegisteredTool {
            override val definition = ToolDefinition("owned", 1, "owned read", "read", ToolEffect.READ_ONLY, ToolSchema(emptyList()))
            override fun prepare(arguments: ToolArguments) = PreparedTool.contextual {
                executions++; ExecutionReceipt.Confirmed("never")
            }
        }
        val plan = owned.prepare(owned.definition.schema.parse("{}"))
        try { plan.execute(); fail("context-free execution accepted") } catch (_: IllegalStateException) {}
        val plain = read { executions++; ExecutionReceipt.Confirmed("never") }
        val dispatcher = ToolDispatcher(ToolRegistry().register(plain).register(owned).snapshot(),
            SessionCapabilities("s", setOf("read", "notes.read")), ToolLedger(store))
        val result = dispatcher.dispatch(TaskAuthorization("s", "t"), listOf(
            ToolInvocation(ToolCallId("plain-call"), "read_note", "{}"),
            ToolInvocation(ToolCallId("owned-call"), "owned", "{}")))
        assertEquals(2, result.size)
        assertTrue(result.all { it.outcome == DispatchOutcome.NotExecuted("tool_execution_context_required") })
        assertEquals(0, executions)
        assertTrue(store.transaction { it.toList() }.isEmpty())
    }

}
