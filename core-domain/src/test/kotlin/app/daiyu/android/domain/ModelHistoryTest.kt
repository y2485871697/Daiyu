package app.daiyu.android.domain

import app.daiyu.android.domain.context.ContextRevision
import app.daiyu.android.domain.context.compressiblePrefix
import app.daiyu.android.domain.context.isCompleteContextPrefix
import app.daiyu.android.domain.context.estimateTokens
import org.junit.Assert.*
import org.junit.Test

class ModelHistoryTest {
    private val tool = MessageBlock.Tool(ToolInvocation(ToolCallId("old-call"), "run_child_models", "{}"))
    private val old = Message(MessageId("old"), ConversationId("c"), RunId("old-run"),
        Message.ASSISTANT_ROLE, listOf(tool), RunStatus.FAILED, "工具结果未知；禁止重放",
        protocolItems = listOf("opaque-provider-snapshot"))

    @Test fun terminalLegacyNullAndExplicitUnknownShareIdempotentReadOnlyProjection() {
        for (status in RunStatus.values().filter { it != RunStatus.RUNNING }) {
            val original = listOf(old.copy(status = status))
            val revision = ContextRevision.digest(original)
            val projected = ModelHistory.project(original)
            ModelHistory.requireResolvedTools(original)
            assertEquals(RunStatus.COMPLETED, projected.single().status)
            assertEquals(tool.copy(result = ModelHistory.UNKNOWN_TOOL_RESULT), projected.single().blocks.single())
            assertTrue(projected.single().protocolItems.isEmpty())
            assertEquals(projected, ModelHistory.project(projected))
            assertNull((original.single().blocks.single() as MessageBlock.Tool).result)
            assertEquals(status, original.single().status)
            assertEquals(revision, ContextRevision.digest(original))
            assertTrue(estimateTokens(original) >= ModelHistory.UNKNOWN_TOOL_RESULT.toByteArray().size)
        }
    }

    @Test fun activePendingIsNeitherSealedNorAdmitted() {
        val running = listOf(old.copy(status = RunStatus.RUNNING))
        assertEquals(running, ModelHistory.project(running))
        assertNotNull(runCatching { ModelHistory.requireResolvedTools(running) }.exceptionOrNull())
    }

    @Test fun normalCompletedProtocolAndConfirmedNegativePayloadsAreUnchanged() {
        for (payload in listOf("{\"ok\":false,\"error\":\"denied\"}",
            "{\"ok\":false,\"executed\":false,\"error\":\"not_allowed\"}")) {
            val confirmed = old.copy(status = RunStatus.COMPLETED, error = null,
                blocks = listOf(tool.copy(result = payload)))
            assertEquals(listOf(confirmed), ModelHistory.project(listOf(confirmed)))
        }
    }

    @Test fun mixedSegmentProjectionKeepsOrderAndOnlyDropsAffectedOpaqueSnapshot() {
        val confirmed = tool.copy(invocation = tool.invocation.copy(id = ToolCallId("known")), result = "denied")
        val first = MessageHistorySegment(old.id, Message.ASSISTANT_ROLE, listOf(confirmed), listOf("known-opaque"))
        val user = MessageHistorySegment(MessageId("supplement"), Message.USER_ROLE,
            listOf(MessageBlock.Text("u", "continue")))
        val last = MessageHistorySegment(old.id, Message.ASSISTANT_ROLE, listOf(tool), old.protocolItems)
        val owner = old.copy(blocks = listOf(confirmed, tool), historySegments = listOf(first, user, last))
        val projected = ModelHistory.project(listOf(owner)).single()
        assertEquals(first, projected.historySegments[0])
        assertEquals(user, projected.historySegments[1])
        assertTrue(projected.historySegments[2].protocolItems.isEmpty())
        assertEquals(projected.blocks, projected.historySegments.filter { it.role == Message.ASSISTANT_ROLE }.flatMap { it.blocks })
        val expanded = MessageHistory.expand(listOf(projected))
        assertEquals(listOf("assistant", "user", "assistant"), expanded.map { it.role })
        assertEquals(listOf("denied", ModelHistory.UNKNOWN_TOOL_RESULT), expanded.flatMap { it.blocks }
            .filterIsInstance<MessageBlock.Tool>().map { it.result })
        assertNull((owner.historySegments.last().blocks.single() as MessageBlock.Tool).result)
    }

    @Test fun projectionDoesNotAuthorizeCompressionOfFailedOriginals() {
        val user = old.copy(id = MessageId("u"), role = Message.USER_ROLE, status = RunStatus.COMPLETED,
            blocks = listOf(MessageBlock.Text("u", "question")), protocolItems = emptyList(), error = null)
        val rows = listOf(user, old)
        assertEquals(0, compressiblePrefix(rows, 1))
        assertFalse(isCompleteContextPrefix(rows, rows.size))
    }

    @Test fun normalFollowUpRunnerReceivesClosedHistoryWithoutExecutingOldCalls() {
        val next = old.copy(id = MessageId("next"), runId = RunId("next-run"),
            blocks = emptyList(), status = RunStatus.RUNNING, error = null, protocolItems = emptyList())
        val laterUser = next.copy(id = MessageId("later-user"), role = Message.USER_ROLE,
            status = RunStatus.COMPLETED, blocks = listOf(MessageBlock.Text("q", "normal later question")))
        val laterAnswer = laterUser.copy(id = MessageId("later-answer"), role = Message.ASSISTANT_ROLE,
            blocks = listOf(MessageBlock.Text("a", "normal later answer")))
        val history = listOf(old, laterUser, laterAnswer)
        var opens = 0
        val runner = ChatRunner(ModelPort { supplied ->
            opens++
            assertEquals(ModelHistory.project(history), supplied)
            object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                    assertTrue(outputs.isEmpty())
                    sink(ModelEvent.TextFinal("new", "done"))
                }
                override fun cancel() {}
            }
        }, object : ConversationStore { override fun save(message: Message) {} },
            BatteryTool { error("historical calls must never execute") }) {}
        assertEquals(RunStatus.COMPLETED, runner.run(next, history).status)
        assertEquals(1, opens)
        assertNull((old.blocks.single() as MessageBlock.Tool).result)
    }

    @Test fun runnerRejectsActivePendingBeforeOpeningModel() {
        var opens = 0
        val runner = ChatRunner(ModelPort { opens++; error("must not open") },
            object : ConversationStore { override fun save(message: Message) {} }, BatteryTool { error("must not execute") }) {}
        val next = old.copy(id = MessageId("next"), runId = RunId("new-run"), status = RunStatus.RUNNING,
            blocks = emptyList(), protocolItems = emptyList())
        assertEquals(RunStatus.FAILED, runner.run(next, listOf(old.copy(status = RunStatus.RUNNING))).status)
        assertEquals(0, opens)
    }

    @Test fun sealingReportedUnknownSegmentNeverDropsItsCallOrChangesItsMeaning() {
        val ended = old.copy(blocks = listOf(tool.copy(result = ModelHistory.UNKNOWN_TOOL_RESULT)),
            protocolItems = emptyList())
        val segments = RunnerHistorySegments(ended)
        val sealed = segments.sealSession(ended, emptyList())
        assertEquals(ended.blocks, sealed.historySegments.flatMap { it.blocks })
        assertEquals(RunStatus.FAILED, sealed.status)
    }
}
