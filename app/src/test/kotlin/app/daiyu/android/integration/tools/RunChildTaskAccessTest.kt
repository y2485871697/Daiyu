package app.daiyu.android.integration.tools

import app.daiyu.android.delegation.SubAgentDescriptor
import app.daiyu.android.delegation.SubAgentTaskTier
import app.daiyu.android.domain.*
import app.daiyu.android.domain.delegation.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.domain.tools.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class RunChildTaskAccessTest {
    private val old = ChildTaskRecord(ChildRunOwner("conversation", "old-parent", "old-group", 2,
        "child", "child-message"), "old-message", "old-owner", 0, "old-profile", "input")
    private fun caller(conversation: String): RunExecutionOwner {
        var captured: RunExecutionOwner? = null
        val receipts = object : ReceiptBatchToolExecutor {
            override fun bindOwner(owner: RunExecutionOwner) { captured = owner }
            override fun execute(message: Message, invocations: List<ToolInvocation>, cancelled: () -> Boolean): List<ToolDispatchResult> = error("no tools")
        }
        val initial = Message(MessageId("new-message"), ConversationId(conversation), RunId("new-parent"),
            Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)
        ChatRunner(ModelPort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) { sink(ModelEvent.TextFinal("text", "done")) }
            override fun cancel() {}
        } }, object : ConversationStore { override fun save(message: Message) {} }, BatteryTool { error("no tools") },
            RunnerOptions(receiptBatchExecutor = receipts)) {}.run(initial, emptyList())
        return checkNotNull(captured)
    }
    private fun arguments(action: String = "continue") = JSONObject().put("parent_run_id", old.owner.parentRunId)
        .put("task_group_id", old.owner.taskGroupId).put("generation", old.owner.generation.toString())
        .put("child_run_id", old.owner.childRunId).put("assistant_message_id", old.owner.assistantMessageId)
        .put("parent_message_id", old.parentMessageId).put("parent_owner_id", old.parentOwnerId)
        .put("expected_revision", "0").put("action", action)
    private fun invoke(access: RunChildTaskAccess, owner: RunExecutionOwner, name: String, body: String,
        capability: Boolean = true): DispatchOutcome {
        val registry = ToolRegistry(); access.tools().forEach { registry.register(it) }
        val caps = SessionCapabilities("session", if (capability) setOf(RunChildTaskAccess.CAPABILITY) else emptySet())
        return ToolDispatcher(registry.snapshot(), caps, ToolLedger(InMemoryToolLedgerStore())).dispatchOwned(
            TaskAuthorization("session", owner.runId.value), listOf(ToolInvocation(ToolCallId("call"), name, body)), owner).single().outcome
    }
    @Test fun takeoverUsesTargetAndReturnsOneUpdatedSnapshotWithoutReadmission() {
        val task = old.copy(originalRole = ChildModelRole.REVIEW)
        var saved = task; var takeovers = 0; var resumes = 0
        val access = RunChildTaskAccess("conversation", { listOf(saved) }, { saved }, { emptyList() }, { "partial" },
            { resumes++; ChildTaskControlReceipt.STALE }, { error("no pause") }, { error("no stop") }, { setOf("next") },
            takeOver = { source, target ->
                assertEquals(task, source); takeovers++
                saved = saved.copy(assignedProfileId = target, revision = saved.revision + 1)
                ChildTaskControlReceipt.RESUMED
            })
        val result = invoke(access, caller("conversation"), "control_child_task", arguments().put("target_profile_id", "next").toString()) as DispatchOutcome.Confirmed
        val body = JSONObject(result.payload)
        assertEquals("next", body.getString("assigned_profile_id")); assertEquals(1L, body.getLong("expected_revision"))
        assertFalse(body.getBoolean("new_request")); assertEquals(1, takeovers); assertEquals(0, resumes)
        assertEquals(task.profileId, saved.profileId); assertEquals(task.originalRole, saved.originalRole)
    }
    @Test fun takeoverOnlyAcceptsContinueAndMapsRoleAndConfigurationFailures() {
        for ((receipt, expected) in listOf(ChildTaskControlReceipt.ROLE_MISMATCH to "child_takeover_role_mismatch",
            ChildTaskControlReceipt.CONFIGURATION_REQUIRED to "child_matching_profile_required")) {
            var called = 0
            val access = RunChildTaskAccess("conversation", { listOf(old) }, { old }, { emptyList() }, { "" },
                { error("no resume") }, { error("no pause") }, { error("no stop") }, { setOf("next") },
                takeOver = { _, _ -> called++; receipt })
            val result = invoke(access, caller("conversation"), "control_child_task", arguments().put("target_profile_id", "next").toString())
            assertEquals(expected, (result as DispatchOutcome.NotExecuted).code); assertEquals(1, called)
        }
    }

    @Test fun currentMetadataIsAtomicFreshAndNeverBackfillsHistoricalTasks() {
        val initial = listOf(SubAgentDescriptor(old.profileId, "审查", "implementation", SubAgentTaskTier.COMPLEX, "model", "model"))
        var current = initial
        var unavailable = false
        var metadataReads = 0
        val access = RunChildTaskAccess("conversation", { listOf(old) }, { old }, { emptyList() }, { "" },
            { error("no resume") }, { error("no pause") }, { error("no stop") },
            { error("IDs must come from the same metadata snapshot") },
            currentProfileMetadata = { metadataReads++; check(!unavailable); current }, initialProfiles = initial)
        val description = access.tools().first().definition.description
        assertTrue(description.contains(initial.single().toJson().toString()))
        current = listOf(initial.single().copy(name = "changed", taskTier = SubAgentTaskTier.SIMPLE))
        val owner = caller("conversation")
        val outcome = invoke(access, owner, "inspect_child_tasks", "{}") as DispatchOutcome.Confirmed
        val body = JSONObject(outcome.payload)
        assertTrue(body.getBoolean("current_configuration_available"))
        assertEquals(1, metadataReads)
        assertEquals(old.profileId, body.getJSONArray("available_profiles").getString(0))
        val actual = body.getJSONArray("available_profile_metadata").getJSONObject(0)
        assertEquals("changed", actual.getString("name")); assertEquals("simple", actual.getString("task_tier"))
        assertEquals("implementation", actual.getString("role"))
        val history = body.getJSONArray("tasks").getJSONObject(0)
        assertEquals(old.profileId, history.getString("profile_id"))
        assertEquals(old.owner.childRunId, history.getString("child_run_id"))
        assertEquals(old.owner.parentRunId, history.getString("parent_run_id"))
        assertFalse(history.has("role")); assertFalse(history.has("task_tier"))
        assertTrue(history.isNull("agent_name"))
        assertEquals(description, access.tools().first().definition.description)
        unavailable = true
        val failed = JSONObject((invoke(access, owner, "inspect_child_tasks", "{}") as DispatchOutcome.Confirmed).payload)
        assertEquals(2, metadataReads)
        assertFalse(failed.getBoolean("current_configuration_available"))
        assertEquals(0, failed.getJSONArray("available_profile_metadata").length())
        assertEquals(0, failed.getJSONArray("available_profiles").length())
        assertEquals(1, failed.getJSONArray("tasks").length())
    }

    @Test fun newParentCanInspectAndContinueOldPausedTaskWhenNewConfigurationIsUnavailable() {
        val resumes = AtomicInteger()
        val access = RunChildTaskAccess("conversation", { listOf(old) }, { old.takeIf { row -> row.owner == it } },
            { listOf(RetainedChildTask(old, false, false, false, ChildTaskPauseState.PAUSED)) }, { "progress" },
            { resumes.incrementAndGet(); assertEquals(old.owner, it.owner); ChildTaskControlReceipt.RESUMED },
            { ChildTaskControlReceipt.PENDING }, { ChildTaskStop.REQUESTED }, { error("configuration unavailable") })
        val owner = caller("conversation")
        val inspected = invoke(access, owner, "inspect_child_tasks", "{}") as DispatchOutcome.Confirmed
        val task = JSONObject(inspected.payload).getJSONArray("tasks").getJSONObject(0)
        assertEquals("PAUSED", task.getString("pause_state")); assertEquals("progress", task.getString("progress"))
        assertEquals(0, resumes.get())
        val result = invoke(access, owner, "control_child_task", arguments().toString()) as DispatchOutcome.Confirmed
        assertFalse(JSONObject(result.payload).getBoolean("new_request")); assertEquals(1, resumes.get())
    }
    @Test fun getFindsHistoricalTaskOutsideRecentWindowAndPagesWithoutResume() {
        val completed = old.copy(status = ChildTaskStatus.COMPLETED, text = "abcdefgh",
            display = ChildTaskDisplay("冻结审查", "historical-model", "历史模型"))
        val access = RunChildTaskAccess("conversation", { emptyList() }, { completed.takeIf { row -> row.owner == it } },
            { emptyList() }, { "partial" }, { error("must not resume") }, { error("must not pause") }, { error("must not stop") },
            { error("configuration unavailable") })
        val args = arguments().apply {
            remove("parent_message_id"); remove("parent_owner_id"); remove("expected_revision"); remove("action")
            put("offset", 2); put("max_chars", 3)
        }
        val result = invoke(access, caller("conversation"), "get_child_task", args.toString()) as DispatchOutcome.Confirmed
        val body = JSONObject(result.payload)
        assertEquals("冻结审查", body.getString("agent_name"))
        assertEquals(old.profileId, body.getString("profile_id"))
        assertEquals("cde", body.getString("text")); assertEquals(5, body.getInt("next_text_offset"))
        assertEquals("rti", body.getString("progress")); assertEquals("COMPLETED", body.getString("status"))
    }
    @Test fun stoppedTaskKeepsQueryableContentButCannotContinue() {
        val stopped = old.copy(status = ChildTaskStatus.UNKNOWN, detail = "child_result_unconfirmed")
        val access = RunChildTaskAccess("conversation", { listOf(stopped) }, { stopped.takeIf { row -> row.owner == it } },
            { listOf(RetainedChildTask(stopped, true, true, false)) }, { "retained partial" },
            { ChildTaskControlReceipt.STALE }, { ChildTaskControlReceipt.STALE }, { ChildTaskStop.ALREADY_SETTLED }, { emptySet() })
        val owner = caller("conversation")
        val inspected = invoke(access, owner, "inspect_child_tasks", "{}") as DispatchOutcome.Confirmed
        val task = JSONObject(inspected.payload).getJSONArray("tasks").getJSONObject(0)
        assertEquals("retained partial", task.getString("progress")); assertTrue(task.getBoolean("stop_requested"))
        assertTrue(task.getBoolean("settled")); assertFalse(task.getBoolean("live_state_available"))
        assertTrue(task.getBoolean("snapshot_available"))
        assertEquals("FINISHED", task.getString("pause_state")); assertEquals("FINISHED", task.getString("queue_state"))
        assertTrue(invoke(access, owner, "control_child_task", arguments().toString()) is DispatchOutcome.NotExecuted)
    }

    @Test fun controlRefusalsKeepTheirDistinctReasonsAndNeverClaimExecution() {
        val owner = caller("conversation")
        for ((outcome, expected) in listOf(ChildTaskStop.ALREADY_SETTLED to "child_task_already_settled",
                ChildTaskStop.NOT_FOUND to "child_task_not_found")) {
            val access = RunChildTaskAccess("conversation", { listOf(old) }, { old }, { emptyList() }, { "" },
                { ChildTaskControlReceipt.STALE }, { ChildTaskControlReceipt.STALE }, { outcome }, { emptySet() })
            val stopped = invoke(access, owner, "control_child_task", arguments("stop").toString()) as DispatchOutcome.NotExecuted
            assertEquals(expected, stopped.code)
            val paused = invoke(access, owner, "control_child_task", arguments("pause").toString()) as DispatchOutcome.NotExecuted
            assertEquals("child_control_unavailable", paused.code)
        }
    }

    @Test fun absentLiveObservationDoesNotClaimTransportDrainOrConfirmedJournal() {
        val running = old.copy(status = ChildTaskStatus.RUNNING)
        val access = RunChildTaskAccess("conversation", { listOf(running) }, { running.takeIf { row -> row.owner == it } },
            { emptyList() }, { "partial" }, { error("must not resume") }, { error("must not pause") }, { error("must not stop") }, { emptySet() })
        val owner = caller("conversation")
        val inspected = invoke(access, owner, "inspect_child_tasks", "{}") as DispatchOutcome.Confirmed
        val task = JSONObject(inspected.payload).getJSONArray("tasks").getJSONObject(0)
        assertFalse(task.getBoolean("live_state_available")); assertTrue(task.isNull("settled"))
        assertTrue(task.isNull("stop_requested")); assertTrue(task.isNull("journal_unconfirmed"))
        val args = arguments().apply { remove("parent_message_id"); remove("parent_owner_id"); remove("expected_revision"); remove("action") }
        val result = invoke(access, owner, "get_child_task", args.toString()) as DispatchOutcome.Confirmed
        val body = JSONObject(result.payload)
        assertFalse(body.getBoolean("live_state_available")); assertTrue(body.isNull("settled")); assertTrue(body.isNull("journal_unconfirmed"))
        assertEquals("RUNNING", body.getString("status")); assertEquals("partial", body.getString("progress"))
    }

    @Test fun wrongConversationIdentityCapabilityAndStoppedTasksCannotRestart() {
        val resumes = AtomicInteger()
        val access = RunChildTaskAccess("conversation", { listOf(old) }, { old.takeIf { row -> row.owner == it } },
            { emptyList() }, { "" }, { resumes.incrementAndGet(); ChildTaskControlReceipt.STALE },
            { ChildTaskControlReceipt.STALE }, { ChildTaskStop.ALREADY_SETTLED }, { emptySet() })
        assertTrue(invoke(access, caller("foreign"), "control_child_task", arguments().toString()) is DispatchOutcome.NotExecuted)
        assertTrue(invoke(access, caller("conversation"), "control_child_task", arguments().put("generation", "3").toString()) is DispatchOutcome.NotExecuted)
        assertTrue(invoke(access, caller("conversation"), "control_child_task", arguments().toString(), false) is DispatchOutcome.NotExecuted)
        assertEquals(0, resumes.get())
        assertTrue(invoke(access, caller("conversation"), "control_child_task", arguments().toString()) is DispatchOutcome.NotExecuted)
        assertEquals(1, resumes.get())
    }

    private fun queryArguments(name: String) = if (name == "inspect_child_tasks") JSONObject() else arguments().apply {
        remove("parent_message_id"); remove("parent_owner_id"); remove("expected_revision"); remove("action")
    }

    @Test fun readOnlyQueryLengthsClampBeforeIntConversionAndKeepDefaultsAndSmallPages() {
        val text = "result-".repeat(1300)
        val partial = "progress-".repeat(1100)
        val completed = old.copy(status = ChildTaskStatus.COMPLETED, text = text)
        val access = RunChildTaskAccess("conversation", { listOf(completed) },
            { completed.takeIf { row -> row.owner == it } }, { emptyList() }, { partial },
            { error("must not resume") }, { error("must not pause") }, { error("must not stop") }, { emptySet() })
        val owner = caller("conversation")
        for ((name, limit) in listOf("inspect_child_tasks" to 1024, "get_child_task" to 4096)) {
            val requests = listOf(null, 1L, 3L, limit.toLong(), limit + 1L, 6000L, 12000L, Int.MAX_VALUE + 1L, Long.MAX_VALUE)
            for (requested in requests) {
                val args = queryArguments(name).put("offset", 2)
                if (requested != null) args.put("max_chars", requested)
                val payload = JSONObject((invoke(access, owner, name, args.toString()) as DispatchOutcome.Confirmed).payload)
                val row = if (name == "inspect_child_tasks") payload.getJSONArray("tasks").getJSONObject(0) else payload
                val effective = requested?.coerceAtMost(limit.toLong())?.toInt() ?: limit
                assertEquals(text.substring(2, 2 + effective), row.getString("text"))
                assertEquals(partial.substring(2, 2 + effective), row.getString("progress"))
                assertEquals(2 + effective, row.getInt("next_text_offset"))
                assertEquals(2 + effective, row.getInt("next_progress_offset"))
                assertEquals("COMPLETED", row.getString("status"))
            }
        }
    }

    @Test fun oversizedReadOnlyQueryPagesReachTheEndWithoutSkippingText() {
        val text = "abcdefg".repeat(1300)
        val partial = "12345".repeat(1300)
        val completed = old.copy(status = ChildTaskStatus.COMPLETED, text = text)
        val access = RunChildTaskAccess("conversation", { listOf(completed) }, { completed }, { emptyList() }, { partial },
            { error("must not resume") }, { error("must not pause") }, { error("must not stop") }, { emptySet() })
        val owner = caller("conversation")
        for (name in listOf("inspect_child_tasks", "get_child_task")) {
            val result = StringBuilder(); val progress = StringBuilder()
            var offset = 0
            while (true) {
                val args = queryArguments(name).put("offset", offset).put("max_chars", 12000)
                val payload = JSONObject((invoke(access, owner, name, args.toString()) as DispatchOutcome.Confirmed).payload)
                val row = if (name == "inspect_child_tasks") payload.getJSONArray("tasks").getJSONObject(0) else payload
                result.append(row.getString("text")); progress.append(row.getString("progress"))
                if (row.isNull("next_text_offset") && row.isNull("next_progress_offset")) break
                val next = maxOf(row.optInt("next_text_offset", offset), row.optInt("next_progress_offset", offset))
                assertTrue(next > offset); offset = next
            }
            assertEquals(text, result.toString()); assertEquals(partial, progress.toString())
        }
    }

    @Test fun invalidReadOnlyQueryLengthsAndForeignCallersStillCannotReadTaskState() {
        val access = RunChildTaskAccess("conversation", { error("must not read records") },
            { error("must not read source") }, { error("must not read live state") }, { error("must not read progress") },
            { error("must not resume") }, { error("must not pause") }, { error("must not stop") }, { error("must not read profiles") })
        val owner = caller("conversation")
        for (name in listOf("inspect_child_tasks", "get_child_task")) {
            for (invalid in listOf("0", "-1", "1.5", "true", JSONObject.quote("12000"), "[]", "9223372036854775808")) {
                val base = queryArguments(name).toString().dropLast(1)
                val args = base + (if (base.length > 1) "," else "") + JSONObject.quote("max_chars") + ":" + invalid + "}"
                val rejected = invoke(access, owner, name, args) as DispatchOutcome.NotExecuted
                assertEquals("invalid_arguments", rejected.code)
            }
            val args = queryArguments(name).put("max_chars", Long.MAX_VALUE).toString()
            val foreign = invoke(access, caller("foreign"), name, args) as DispatchOutcome.NotExecuted
            assertEquals("child_conversation_mismatch", foreign.code)
            assertTrue(invoke(access, owner, name, args, capability = false) is DispatchOutcome.NotExecuted)
        }
    }

}
