package app.daiyu.android.domain.usage

import app.daiyu.android.domain.*
import app.daiyu.android.domain.delegation.*
import app.daiyu.android.domain.runtime.*
import org.junit.Assert.*
import org.junit.Test

class AttributedUsageTest {
    private fun row(id: String = "request", origin: UsageOrigin = UsageOrigin.Main,
        value: ModelEvent.Usage = ModelEvent.Usage(10, 20, 5), conversation: String = "conversation") =
        AttributedRequestUsage(conversation, "parent", "message", if (origin is UsageOrigin.Child) origin.taskId else "parent",
            if (origin is UsageOrigin.Child) "child-message" else "message", origin,
            RequestCheckpoint(id, "attempt", "request-owner", RequestOperation.NEXT), value)
    private fun rejected(work: () -> Unit) {
        try { work(); fail("invalid attribution accepted") } catch (_: IllegalArgumentException) {}
    }
    @Test fun mainAndChildStaySeparateAndCacheIsNotExtraInput() {
        val summary = aggregateAttributedUsage(listOf(row(), row(origin = UsageOrigin.Child("child", "profile", "group"))))
        assertEquals(1L, summary.main.requestSnapshotCount); assertEquals(1L, summary.child.requestSnapshotCount)
        assertEquals(20L, summary.combined.input.reportedTokens)
        assertEquals(40L, summary.combined.output.reportedTokens); assertEquals(10L, summary.combined.cached.reportedTokens)
    }
    @Test fun noReportsDoesNotInventKnownZero() {
        val summary = aggregateAttributedUsage(emptyList())
        for (value in listOf(summary.main, summary.child, summary.combined)) {
            assertEquals(0L, value.requestSnapshotCount); assertNull(value.input.reportedTokens)
        }
    }
    @Test fun unknownFieldsAreIndependentOfKnownZero() {
        val summary = aggregateAttributedUsage(listOf(row(value = ModelEvent.Usage(null, 0)),
            row(origin = UsageOrigin.Child("child", "profile", "group"), value = ModelEvent.Usage(0, null, 0))))
        assertEquals(ReportedTokenSubtotal(0, 1, 1), summary.combined.input)
        assertEquals(ReportedTokenSubtotal(0, 1, 1), summary.combined.output)
        assertEquals(ReportedTokenSubtotal(0, 1, 1), summary.combined.cached)
    }
    @Test fun sameCountsInDifferentRequestsAreBothIncluded() {
        assertEquals(20L, aggregateAttributedUsage(listOf(row(), row("another"))).combined.input.reportedTokens)
    }
    @Test fun requestIdentityIsQualifiedByConversationRunAndOwner() {
        val first = row()
        val records = listOf(first, first.copy(conversationId = "another"),
            first.copy(parentRunId = "other-run", runId = "other-run"),
            first.copy(request = first.request.copy(owner = "other-owner")))
        assertEquals(4L, aggregateAttributedUsage(records).combined.requestSnapshotCount)
    }
    @Test fun duplicateRequestOrResumeSnapshotsMustBeReplacedBeforeAggregation() {
        val first = row()
        rejected { aggregateAttributedUsage(listOf(first, first.copy(request = first.request.copy(
            attemptId = "resume", operation = RequestOperation.RESUME), snapshot = ModelEvent.Usage(null, 0)))) }
    }
    @Test fun invalidParentOrChildIdentityIsRejected() {
        rejected { row().copy(runId = "not-parent") }
        rejected { row(origin = UsageOrigin.Child("child", "profile", "group")).copy(runId = "not-child") }
        rejected { row().copy(conversationId = "") }
    }
    @Test fun overflowIsNotWrapped() {
        try {
            aggregateAttributedUsage(listOf(row(value = ModelEvent.Usage(Long.MAX_VALUE, null)),
                row("another", value = ModelEvent.Usage(1, null))))
            fail("overflow accepted")
        } catch (_: ArithmeticException) {}
    }
    private fun task() = ChildTaskRecord(ChildRunOwner("conversation", "parent", "group", 1, "child", "child-message"),
        "parent-message", "parent-owner", 0, "review", "question")
    private fun checkpoint(): RunCheckpoint {
        val task = task(); val request = RequestCheckpoint("request", "attempt", "child-owner", RequestOperation.NEXT)
        val message = Message(MessageId(task.owner.assistantMessageId), ConversationId(task.owner.conversationId),
            RunId(task.owner.childRunId), Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)
        return RunCheckpoint(message, emptyList(), ExecutionPhase.STREAMING, owner = request.owner, revision = 1,
            request = request, usage = listOf(RequestUsageCheckpoint(request, ModelEvent.Usage(10, 20, 5))))
    }
    @Test fun childCheckpointUsesExistingRequestIdentityAndParentAttribution() {
        val cp = checkpoint(); val record = childRequestUsage(task(), cp).single()
        assertEquals(cp.request, record.request); assertEquals("parent", record.parentRunId)
        assertEquals("parent-message", record.parentMessageId); assertEquals("child", record.runId)
        assertEquals(UsageOrigin.Child("child", "review", "group"), record.origin)
    }
    @Test fun childEmptyReportsRemainEmpty() {
        assertTrue(childRequestUsage(task(), checkpoint().copy(usage = emptyList())).isEmpty())
    }
    @Test fun childForeignRunMessageConversationAndOwnerAreRejected() {
        val cp = checkpoint()
        for (message in listOf(cp.message.copy(runId = RunId("foreign")), cp.message.copy(id = MessageId("foreign")),
            cp.message.copy(conversationId = ConversationId("foreign")), cp.message.copy(role = Message.USER_ROLE))) {
            rejected { childRequestUsage(task(), cp.copy(message = message)) }
        }
        rejected { childRequestUsage(task(), cp.copy(owner = "foreign")) }
    }
    @Test fun childOpenAndDuplicateUsageAreRejected() {
        val cp = checkpoint(); val usage = cp.usage.single()
        rejected { childRequestUsage(task(), cp.copy(usage = listOf(usage, usage))) }
        rejected { childRequestUsage(task(), cp.copy(usage = listOf(usage.copy(request = usage.request.copy(operation = RequestOperation.OPEN))))) }
    }
    @Test fun childRequestLimitAndRevisionAreValidated() {
        val cp = checkpoint(); val usage = cp.usage.single()
        rejected { childRequestUsage(task(), cp.copy(revision = 0)) }
        rejected { childRequestUsage(task(), cp.copy(usage = (0..100).map { usage.copy(request = usage.request.copy(requestId = "r$it")) })) }
    }
    @Test fun childResumeKeepsSameRequestAndReplacesAllFields() {
        val cp = checkpoint(); val request = cp.request!!.copy(attemptId = "resumed", operation = RequestOperation.RESUME)
        val record = childRequestUsage(task(), cp.copy(request = request,
            usage = listOf(RequestUsageCheckpoint(request, ModelEvent.Usage(null, 0, 0))))).single()
        assertEquals("request", record.request.requestId); assertEquals("resumed", record.request.attemptId)
        assertNull(record.snapshot.inputTokens); assertEquals(0L, record.snapshot.outputTokens)
    }
}
