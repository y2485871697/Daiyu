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
    @get:org.junit.Rule val liveOwners = LiveRunnerOwners()
    private val old = ChildTaskRecord(ChildRunOwner("conversation", "old-parent", "old-group", 2,
        "child", "child-message"), "old-message", "old-owner", 0, "old-profile", "input")
    private fun caller(conversation: String) = liveOwners.issue(Message(MessageId("new-message"),
        ConversationId(conversation), RunId("new-parent"), Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING))
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
        val outcome = invoke(access, owner, "list_subagents", "{}") as DispatchOutcome.Confirmed
        val body = JSONObject(outcome.payload)
        assertTrue(body.getBoolean("current_configuration_available"))
        assertEquals(1, metadataReads)
        assertEquals(old.profileId, body.getJSONArray("available_profiles").getString(0))
        val actual = body.getJSONArray("available_profile_metadata").getJSONObject(0)
        assertEquals("changed", actual.getString("name")); assertEquals("simple", actual.getString("task_tier"))
        assertEquals("implementation", actual.getString("role"))
        assertFalse(body.has("tasks")); assertFalse(body.has("total_count"))
        val overview = JSONObject((invoke(access, owner, "inspect_child_tasks", "{}") as DispatchOutcome.Confirmed).payload)
        assertEquals(1, metadataReads); assertFalse(overview.has("available_profiles"))
        val history = overview.getJSONArray("tasks").getJSONObject(0)
        assertEquals(old.profileId, history.getString("profile_id"))
        assertEquals(old.owner.childRunId, history.getString("child_run_id"))
        assertEquals(old.owner.parentRunId, history.getString("parent_run_id"))
        assertFalse(history.has("role")); assertFalse(history.has("task_tier"))
        assertTrue(history.isNull("agent_name"))
        assertEquals(description, access.tools().first().definition.description)
        unavailable = true
        val failed = JSONObject((invoke(access, owner, "list_subagents", "{}") as DispatchOutcome.Confirmed).payload)
        assertEquals(2, metadataReads)
        assertFalse(failed.getBoolean("current_configuration_available"))
        assertEquals(0, failed.getJSONArray("available_profile_metadata").length())
        assertEquals(0, failed.getJSONArray("available_profiles").length())
        assertFalse(failed.has("tasks"))
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
        assertEquals("PAUSED", task.getString("pause_state")); assertFalse(task.has("progress"))
        val detailed = invoke(access, owner, "get_child_task", queryArguments().toString()) as DispatchOutcome.Confirmed
        assertEquals("progress", JSONObject(detailed.payload).getString("progress"))
        assertEquals(0, resumes.get())
        val result = invoke(access, owner, "control_child_task", arguments().toString()) as DispatchOutcome.Confirmed
        assertFalse(JSONObject(result.payload).getBoolean("new_request")); assertEquals(1, resumes.get())
    }
    @Test fun getFindsHistoricalTaskOutsideRecentWindowAndPagesWithoutResume() {
        val completed = old.copy(status = ChildTaskStatus.COMPLETED, text = "abcdefgh",
            display = ChildTaskDisplay("冻结审查", "historical-model", "历史模型"))
        val recent = (1..20).map { index -> old.copy(owner = old.owner.copy(childRunId = "recent-$index")) }
        val access = RunChildTaskAccess("conversation", { recent }, { completed.takeIf { row -> row.owner == it } },
            { emptyList() }, { "partial" }, { error("must not resume") }, { error("must not pause") }, { error("must not stop") },
            { error("configuration unavailable") })
        val owner = caller("conversation")
        val overview = JSONObject((invoke(access, owner, "inspect_child_tasks", "{}") as DispatchOutcome.Confirmed).payload)
            .getJSONArray("tasks")
        assertEquals(20, overview.length())
        assertTrue((0 until overview.length()).none { overview.getJSONObject(it).getString("child_run_id") == old.owner.childRunId })
        val args = queryArguments().put("offset", 2).put("max_chars", 3)
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
        assertFalse(task.has("progress")); assertFalse(task.has("detail")); assertTrue(task.getBoolean("stop_requested"))
        val detailed = JSONObject((invoke(access, owner, "get_child_task", queryArguments().toString()) as DispatchOutcome.Confirmed).payload)
        assertEquals("retained partial", detailed.getString("progress"))
        assertEquals("child_result_unconfirmed", detailed.getString("detail"))
        assertTrue(task.getBoolean("settled")); assertFalse(task.getBoolean("live_state_available"))
        assertTrue(task.getBoolean("snapshot_available"))
        assertEquals("FINISHED", task.getString("pause_state")); assertEquals("FINISHED", task.getString("queue_state"))
        assertTrue(invoke(access, owner, "control_child_task", arguments().toString()) is DispatchOutcome.NotExecuted)
    }

    @Test fun controlRefusalsKeepTheirDistinctReasonsAndNeverClaimExecution() {
        val owner = caller("conversation")
        for ((outcome, expected) in listOf(ChildTaskStop.ALREADY_SETTLED to "child_task_already_settled",
                ChildTaskStop.NOT_FOUND to "child_owner_handle_unavailable")) {
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
        assertFalse(body.getBoolean("live_state_available")); assertEquals("continue_independent_work_no_replay", body.getString("parent_next_step"))
            assertTrue(body.isNull("settled")); assertTrue(body.isNull("journal_unconfirmed"))
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

    private fun queryArguments() = arguments().apply {
        remove("parent_message_id"); remove("parent_owner_id"); remove("expected_revision"); remove("action")
    }

    @Test fun overviewReturnsCountsAndIdentityWithoutReadingConfigurationOrTaskContent() {
        val completed = old.copy(status = ChildTaskStatus.COMPLETED, text = "RESULT_BODY_ONLY",
            prompt = "PROMPT_BODY_ONLY", originalRole = ChildModelRole.REVIEW)
        val failed = old.copy(owner = old.owner.copy(childRunId = "failed-child"),
            status = ChildTaskStatus.FAILED, detail = "DETAIL_ONLY")
        val access = RunChildTaskAccess("conversation", { listOf(completed, failed) }, { error("overview must not read source") },
            { emptyList() }, { error("overview must not read progress") },
            { error("must not resume") }, { error("must not pause") }, { error("must not stop") }, { error("overview must not read configuration") })
        val definition = access.tools().first { it.definition.name == "inspect_child_tasks" }.definition
        assertTrue(definition.description.contains("get_child_task"))
        assertFalse(definition.description.contains("max_chars"))
        val payload = (invoke(access, caller("conversation"), "inspect_child_tasks", "{}") as DispatchOutcome.Confirmed).payload
        val body = JSONObject(payload)
        assertFalse(body.has("current_configuration_available")); assertFalse(body.has("available_profiles"))
        assertFalse(body.has("available_profile_metadata")); assertEquals(2, body.getInt("total_count"))
        assertEquals(1, body.getJSONObject("status_counts").getInt("COMPLETED"))
        assertEquals(1, body.getJSONObject("status_counts").getInt("FAILED"))
        assertFalse(body.getBoolean("task_identities_truncated"))
        val task = body.getJSONArray("tasks").getJSONObject(0)
        assertEquals("COMPLETED", task.getString("status")); assertEquals("REVIEW", task.getString("original_role"))
        assertEquals(old.owner.conversationId, task.getString("conversation_id"))
        assertEquals(old.owner.parentRunId, task.getString("parent_run_id"))
        assertEquals(old.owner.taskGroupId, task.getString("task_group_id"))
        assertEquals(old.owner.generation, task.getLong("generation"))
        assertEquals(old.owner.childRunId, task.getString("child_run_id"))
        assertEquals(old.owner.assistantMessageId, task.getString("assistant_message_id"))
        assertEquals(old.parentMessageId, task.getString("parent_message_id"))
        assertEquals(old.parentOwnerId, task.getString("parent_owner_id"))
        assertEquals(old.revision, task.getLong("expected_revision"))
        assertEquals(old.profileId, task.getString("profile_id"))
        assertEquals(old.assignedProfileId, task.getString("assigned_profile_id"))
        val tasks = body.getJSONArray("tasks")
        assertEquals(2, tasks.length())
        assertEquals("FAILED", tasks.getJSONObject(1).getString("status"))
        for (index in 0 until tasks.length())
            for (key in listOf("prompt", "text", "progress", "detail", "offset", "next_text_offset", "next_progress_offset"))
                assertFalse(key, tasks.getJSONObject(index).has(key))
        for (content in listOf("RESULT_BODY_ONLY", "PROMPT_BODY_ONLY", "DETAIL_ONLY")) assertFalse(payload.contains(content))
    }

    @Test fun overviewRejectsResultPagingAndSingleTaskArgumentsBeforeReadingAnyState() {
        val access = RunChildTaskAccess("conversation", { error("must not read records") },
            { error("must not read source") }, { error("must not read live state") }, { error("must not read progress") },
            { error("must not resume") }, { error("must not pause") }, { error("must not stop") }, { error("must not read profiles") })
        val owner = caller("conversation")
        for (name in listOf("list_subagents", "inspect_child_tasks"))
            for (args in listOf("""{"offset":0}""", """{"max_chars":12000}""", """{"child_run_id":"child"}""")) {
                val rejected = invoke(access, owner, name, args) as DispatchOutcome.NotExecuted
                assertEquals("invalid_arguments", rejected.code)
            }
    }

    @Test fun preciseReadLengthsClampBeforeIntConversionAndKeepDefaultsAndSmallPages() {
        val text = "result-".repeat(1300)
        val partial = "progress-".repeat(1100)
        val completed = old.copy(status = ChildTaskStatus.COMPLETED, text = text)
        val access = RunChildTaskAccess("conversation", { listOf(completed) },
            { completed.takeIf { row -> row.owner == it } }, { emptyList() }, { partial },
            { error("must not resume") }, { error("must not pause") }, { error("must not stop") }, { emptySet() })
        val owner = caller("conversation")
        val limit = 4096
        val requests = listOf(null, 1L, 3L, limit.toLong(), limit + 1L, 6000L, 12000L, Int.MAX_VALUE + 1L, Long.MAX_VALUE)
        for (requested in requests) {
            val args = queryArguments().put("offset", 2)
            if (requested != null) args.put("max_chars", requested)
            val row = JSONObject((invoke(access, owner, "get_child_task", args.toString()) as DispatchOutcome.Confirmed).payload)
            val effective = requested?.coerceAtMost(limit.toLong())?.toInt() ?: limit
            assertEquals(text.substring(2, 2 + effective), row.getString("text"))
            assertEquals(partial.substring(2, 2 + effective), row.getString("progress"))
            assertEquals(2 + effective, row.getInt("next_text_offset"))
            assertEquals(2 + effective, row.getInt("next_progress_offset"))
            assertEquals("COMPLETED", row.getString("status"))
        }
    }

    @Test fun oversizedPreciseReadPagesReachTheEndWithoutSkippingText() {
        val text = "abcdefg".repeat(1300)
        val partial = "12345".repeat(1300)
        val completed = old.copy(status = ChildTaskStatus.COMPLETED, text = text)
        val access = RunChildTaskAccess("conversation", { listOf(completed) }, { completed }, { emptyList() }, { partial },
            { error("must not resume") }, { error("must not pause") }, { error("must not stop") }, { emptySet() })
        val owner = caller("conversation")
        val result = StringBuilder(); val progress = StringBuilder()
        var offset = 0
        while (true) {
            val args = queryArguments().put("offset", offset).put("max_chars", 12000)
            val row = JSONObject((invoke(access, owner, "get_child_task", args.toString()) as DispatchOutcome.Confirmed).payload)
            result.append(row.getString("text")); progress.append(row.getString("progress"))
            if (row.isNull("next_text_offset") && row.isNull("next_progress_offset")) break
            val next = maxOf(row.optInt("next_text_offset", offset), row.optInt("next_progress_offset", offset))
            assertTrue(next > offset); offset = next
        }
        assertEquals(text, result.toString()); assertEquals(partial, progress.toString())
    }

    @Test fun invalidPreciseReadLengthsAndForeignCallersStillCannotReadTaskState() {
        val access = RunChildTaskAccess("conversation", { error("must not read records") },
            { error("must not read source") }, { error("must not read live state") }, { error("must not read progress") },
            { error("must not resume") }, { error("must not pause") }, { error("must not stop") }, { error("must not read profiles") })
        val owner = caller("conversation")
        for (invalid in listOf("0", "-1", "1.5", "true", JSONObject.quote("12000"), "[]", "9223372036854775808")) {
            val base = queryArguments().toString().dropLast(1)
            val args = base + "," + JSONObject.quote("max_chars") + ":" + invalid + "}"
            val rejected = invoke(access, owner, "get_child_task", args) as DispatchOutcome.NotExecuted
            assertEquals("invalid_arguments", rejected.code)
        }
        for (name in listOf("list_subagents", "inspect_child_tasks", "get_child_task")) {
            val args = if (name == "get_child_task") queryArguments().put("max_chars", Long.MAX_VALUE).toString() else "{}"
            val foreign = invoke(access, caller("foreign"), name, args) as DispatchOutcome.NotExecuted
            assertEquals("child_conversation_mismatch", foreign.code)
            assertTrue(invoke(access, owner, name, args, capability = false) is DispatchOutcome.NotExecuted)
        }
    }

    @Test fun configurationQueryNeverReadsTasksOrRunsThem() {
        var profiles = setOf("review", "implementation")
        val access = RunChildTaskAccess("conversation", { error("no records") }, { error("no source") },
            { error("no live") }, { error("no progress") }, { error("no resume") }, { error("no pause") },
            { error("no stop") }, { profiles }, recordedStatuses = { error("no statuses") })
        val owner = caller("conversation")
        val first = JSONObject((invoke(access, owner, "list_subagents", "{}") as DispatchOutcome.Confirmed).payload)
        assertEquals(2, first.getJSONArray("available_profiles").length())
        assertFalse(first.has("tasks")); assertFalse(first.has("total_count"))
        profiles = emptySet()
        val second = JSONObject((invoke(access, owner, "list_subagents", "{}") as DispatchOutcome.Confirmed).payload)
        assertTrue(second.getBoolean("current_configuration_available"))
        assertEquals(0, second.getJSONArray("available_profiles").length())
    }

    @Test fun overviewCountsAllTwentyFiveTasksAndPreservesOffWindowFailures() {
        val recent = (0 until 20).map { index -> old.copy(owner = old.owner.copy(childRunId = "recent-$index"),
            status = ChildTaskStatus.COMPLETED, text = "private result") }
        val statuses = recent.associate { it.owner to it.status }.toMutableMap()
        repeat(5) { statuses[old.owner.copy(childRunId = "older-$it")] =
            if (it == 4) ChildTaskStatus.FAILED else ChildTaskStatus.COMPLETED }
        val foreign = old.copy(owner = old.owner.copy(conversationId = "foreign", childRunId = "private-other"))
        statuses[foreign.owner] = ChildTaskStatus.FAILED
        val access = RunChildTaskAccess("conversation", { listOf(foreign) + recent }, { error("no source") },
            { emptyList() }, { error("no progress") }, { error("no resume") }, { error("no pause") },
            { error("no stop") }, { error("no configuration") }, recordedStatuses = { statuses })
        val body = JSONObject((invoke(access, caller("conversation"), "inspect_child_tasks", "{}") as DispatchOutcome.Confirmed).payload)
        assertEquals(25, body.getInt("total_count")); assertTrue(body.getBoolean("task_identities_truncated"))
        assertEquals(24, body.getJSONObject("status_counts").getInt("COMPLETED"))
        assertEquals(1, body.getJSONObject("status_counts").getInt("FAILED"))
        assertEquals(20, body.getJSONArray("tasks").length())
        assertFalse(body.toString().contains("private-other")); assertFalse(body.toString().contains("private result"))
    }

    @Test fun overviewDeduplicatesLiveTasksAndUsesObservedLifecycle() {
        val recorded = old.copy(status = ChildTaskStatus.COMPLETED, text = "private result")
        var state = RetainedChildTask(old.copy(status = ChildTaskStatus.RUNNING), false, false, false, ChildTaskPauseState.PAUSED)
        val access = RunChildTaskAccess("conversation", { listOf(recorded) }, { error("no source") },
            { listOf(state, state) }, { error("no progress") }, { error("no resume") }, { error("no pause") },
            { error("no stop") }, { error("no configuration") }, recordedStatuses = { mapOf(recorded.owner to recorded.status) })
        fun summary() = JSONObject((invoke(access, caller("conversation"), "inspect_child_tasks", "{}") as DispatchOutcome.Confirmed).payload)
        assertEquals(1, summary().getInt("total_count"))
        assertEquals(1, summary().getJSONObject("status_counts").getInt("PAUSED"))
        state = state.copy(pauseState = ChildTaskPauseState.ACTIVE)
        assertEquals(1, summary().getJSONObject("status_counts").getInt("RUNNING"))
        state = state.copy(record = recorded, settled = true)
        assertEquals(1, summary().getJSONObject("status_counts").getInt("COMPLETED"))
        state = state.copy(journalUnconfirmed = true)
        assertEquals(1, summary().getJSONObject("status_counts").getInt("UNKNOWN"))
    }
    @Test fun historicalUnknownWithoutHandleStaysReadableAndStopNeverClaimsSuccess() {
        val historical = old.copy(status = ChildTaskStatus.UNKNOWN, detail = "child_result_unconfirmed")
        var stops = 0
        val access = RunChildTaskAccess("conversation", { listOf(historical) }, { historical.takeIf { row -> row.owner == it } },
            { emptyList() }, { "saved partial" }, { error("must not resume") }, { error("must not pause") },
            { stops++; ChildTaskStop.NOT_FOUND }, { emptySet() })
        val owner = caller("conversation")
        for (body in listOf(
            JSONObject((invoke(access, owner, "inspect_child_tasks", "{}") as DispatchOutcome.Confirmed).payload).getJSONArray("tasks").getJSONObject(0),
            JSONObject((invoke(access, owner, "get_child_task", queryArguments().toString()) as DispatchOutcome.Confirmed).payload))) {
            assertEquals("child_owner_handle_unavailable", body.getString("control_unavailable_reason"))
            assertEquals(0, body.getJSONArray("available_actions").length())
            assertTrue(body.getBoolean("execution_unconfirmed"))
            assertFalse(body.getBoolean("live_state_available"))
            assertEquals("continue_independent_work_no_replay", body.getString("parent_next_step"))
            assertTrue(body.isNull("settled")); assertTrue(body.isNull("journal_unconfirmed"))
            assertEquals("UNKNOWN", body.getString("status"))
        }
        val stopped = invoke(access, owner, "control_child_task", arguments("stop").toString()) as DispatchOutcome.NotExecuted
        assertEquals("child_owner_handle_unavailable", stopped.code); assertEquals(1, stops)
        assertEquals(ChildTaskStatus.UNKNOWN, historical.status)
        assertEquals("child_result_unconfirmed", historical.detail)
        val detailed = JSONObject((invoke(access, owner, "get_child_task", queryArguments().toString()) as DispatchOutcome.Confirmed).payload)
        assertEquals("", detailed.getString("text"))
        assertEquals("saved partial", detailed.getString("progress"))
    }

    @Test fun staleRunningJournalDoesNotAdvertiseRunnableWork() {
        val stale = old.copy(status = ChildTaskStatus.RUNNING)
        val access = RunChildTaskAccess("conversation", { listOf(stale) }, { stale }, { emptyList() }, { "" },
            { error("no resume") }, { error("no pause") }, { error("no stop") }, { emptySet() })
        val summary = JSONObject((invoke(access, caller("conversation"), "inspect_child_tasks", "{}") as DispatchOutcome.Confirmed).payload)
        assertEquals(1, summary.getJSONObject("status_counts").getInt("UNKNOWN"))
        val row = summary.getJSONArray("tasks").getJSONObject(0)
        assertEquals("RUNNING", row.getString("status")) // Preserve journal evidence.
        assertEquals("continue_independent_work_no_replay", row.getString("parent_next_step"))
    }

    @Test fun missingHandleDoesNotEraseAConfirmedHistoricalResult() {
        val completed = old.copy(status = ChildTaskStatus.COMPLETED, text = "done")
        val access = RunChildTaskAccess("conversation", { listOf(completed) }, { completed }, { emptyList() }, { "" },
            { error("must not resume") }, { error("must not pause") }, { error("must not stop") }, { emptySet() })
        val body = JSONObject((invoke(access, caller("conversation"), "get_child_task", queryArguments().toString()) as DispatchOutcome.Confirmed).payload)
        assertEquals(0, body.getJSONArray("available_actions").length())
        assertFalse(body.getBoolean("execution_unconfirmed"))
        assertEquals("COMPLETED", body.getString("status")); assertEquals("done", body.getString("text"))
    }

    @Test fun liveOldParentStillReceivesExactlyOneStopRequestWithoutClaimingDrain() {
        val running = old.copy(status = ChildTaskStatus.RUNNING)
        var stops = 0
        val access = RunChildTaskAccess("conversation", { listOf(running) }, { running },
            { listOf(RetainedChildTask(running, false, false, false)) }, { "" },
            { error("must not resume") }, { error("must not pause") }, { stops++; ChildTaskStop.REQUESTED }, { emptySet() })
        val result = invoke(access, caller("conversation"), "control_child_task", arguments("stop").toString()) as DispatchOutcome.Confirmed
        val body = JSONObject(result.payload)
        assertEquals("REQUESTED", body.getString("outcome")); assertEquals(1, stops)
        assertFalse(body.getBoolean("new_request")); assertFalse(body.has("settled"))
    }

    @Test fun detailedFailureCanBePagedWhileItsShortSummaryIsAlwaysAvailable() {
        val detail = "服务限制请求（HTTP 429）\n\n" + "中文诊断".repeat(1500)
        val task = old.copy(status = ChildTaskStatus.FAILED, detail = detail)
        val access = RunChildTaskAccess("conversation", { listOf(task) }, { task }, { emptyList() }, { "" },
            { error("no resume") }, { error("no pause") }, { error("no stop") }, { emptySet() })
        val owner = caller("conversation")
        var offset = 0; val combined = StringBuilder()
        do {
            val body = JSONObject((invoke(access, owner, "get_child_task", queryArguments().put("offset", offset).put("max_chars", 4096).toString()) as DispatchOutcome.Confirmed).payload)
            assertEquals("服务限制请求（HTTP 429）", body.getString("error_summary"))
            combined.append(body.getString("detail"))
            offset = if (body.isNull("next_detail_offset")) -1 else body.getInt("next_detail_offset")
        } while (offset >= 0)
        assertEquals(detail, combined.toString())
    }

}
