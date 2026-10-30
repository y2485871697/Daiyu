package app.daiyu.android.timeline

import app.daiyu.android.domain.*
import org.junit.Assert.*
import org.junit.Test

class StoppedTimelineTest {
    private fun message(status: RunStatus = RunStatus.STOPPED, id: String = "a") = Message(
        MessageId(id), ConversationId("c"), RunId("r"), Message.ASSISTANT_ROLE, emptyList(), status, createdAt = 12L)
    private fun reconnect(anchor: TimelineAnchor, status: String = "stopped", id: String = "reconnect") =
        ChatTimelineEntry.Reconnect(id, anchor, 1L, status, 100L, "real error", 0, "model")
    private fun parts(owner: Message) = owner.copy(historySegments = listOf(
        MessageHistorySegment(owner.id, "assistant", listOf(MessageBlock.Text("t", "first"))),
        MessageHistorySegment(MessageId("supplement"), "user", listOf(MessageBlock.Text("u", "more"))),
        MessageHistorySegment(owner.id, "assistant", emptyList(), reportedReasoning = listOf(ReportedReasoning("r1", "thinking")))))

    @Test fun anEmptyStoppedOwnerStillHasExactlyOneStableDivider() {
        val owner = message()
        val entry = withStoppedRuns(listOf(owner), emptyList()).single() as ChatTimelineEntry.Stopped
        assertEquals(TimelineAnchor("c", "r", "a", 0), entry.anchor)
        assertEquals(12L, entry.created)
        assertEquals(listOf(entry), withStoppedRuns(listOf(owner), listOf(entry)))
        assertEquals(listOf(entry), withStoppedRuns(listOf(message(RunStatus.COMPLETED, "earlier"), owner), emptyList()))
    }
    @Test fun aLateRunningReconnectCannotOutliveItsDurablyStoppedOwner() {
        val owner = message()
        val event = reconnect(TimelineAnchor.at(listOf(owner), 0), "running")
        val timeline = withStoppedRuns(listOf(owner), listOf(event))
        assertEquals(1, timeline.size)
        assertTrue(timeline.single() is ChatTimelineEntry.Stopped)
    }
    @Test fun everyNonStoppedStatusKeepsItsIndependentReconnectEvidence() {
        for (status in RunStatus.entries.filter { it != RunStatus.STOPPED }) {
            val owner = message(status)
            val event = reconnect(TimelineAnchor.at(listOf(owner), 0))
            assertEquals(listOf(event), withStoppedRuns(listOf(owner), listOf(event)))
        }
    }
    @Test fun stoppedReconnectAtAnEarlierSegmentIsReplacedOnlyForItsPhysicalOwner() {
        val owner = parts(message())
        val firstAnchor = TimelineAnchor.at(MessageHistory.expand(listOf(owner)), 0)
        val stopped = reconnect(firstAnchor)
        val other = reconnect(firstAnchor.copy(runId = "another-run"), id = "other")
        val succeeded = reconnect(firstAnchor, "succeeded", "success")
        val failed = reconnect(firstAnchor, "failed", "failed")
        val result = withStoppedRuns(listOf(owner), listOf(stopped, other, succeeded, failed))
        assertFalse(stopped in result)
        assertTrue(result.containsAll(listOf(other, succeeded, failed)))
        val stop = result.filterIsInstance<ChatTimelineEntry.Stopped>().single()
        assertEquals(firstAnchor.copy(segment = 1), stop.anchor)
    }
    @Test fun aTrailingSupplementBelongsBeforeTheStopDividerEvenWithoutAnAssistantTail() {
        val owner = parts(message()).let { it.copy(historySegments = it.historySegments.dropLast(1)) }
        val stop = withStoppedRuns(listOf(owner), emptyList()).single()
        assertEquals(TimelineAnchor("c", "r", "supplement", 0), stop.anchor)
    }
    @Test fun theFinalSourceIsNotLostToAdjacentWorkflowMerging() {
        val owner = parts(message())
        val timeline = withStoppedRuns(listOf(owner), emptyList())
        val turns = app.daiyu.android.ui.screens.chat.conversation.ChatTurnMapper.map(listOf(owner),
            boundariesAfter = timeline.filterNot { it is ChatTimelineEntry.Stopped }.mapTo(hashSetOf()) { it.anchor.listKey })
        assertTrue(turns.any { it.matchesKey(timeline.single().anchor.listKey) })
        assertEquals(timeline, withStoppedRuns(listOf(owner), timeline))
    }
    @Test fun aStopNeverSplitsAnExistingThoughtAndToolWorkflowIntoTwoCards() {
        val owner = message().copy(historySegments = listOf(
            MessageHistorySegment(MessageId("a"), "assistant", listOf(MessageBlock.Tool(
                ToolInvocation(ToolCallId("tool"), "lookup", "{}"), "done"))),
            MessageHistorySegment(MessageId("a"), "assistant", emptyList(),
                reportedReasoning = listOf(ReportedReasoning("thought", "stopped summary", blockOffset = 0)))))
        val timeline = withStoppedRuns(listOf(owner), emptyList())
        val turns = app.daiyu.android.ui.screens.chat.conversation.ChatTurnMapper.map(listOf(owner),
            boundariesAfter = timeline.filterNot { it is ChatTimelineEntry.Stopped }.mapTo(hashSetOf()) { it.anchor.listKey })
        assertEquals(1, turns.size)
        val turn = turns.single() as app.daiyu.android.ui.screens.chat.conversation.ChatTurnUi.Assistant
        assertEquals(1, turn.workProcess!!.segments.flatMap { it.steps }.size)
        assertEquals("stopped summary", turn.workProcess.segments.mapNotNull { it.reasoning }.single().text)
        assertTrue(timeline.single().anchor.listKey in turn.sourceListKeys)
        assertNotEquals(turn.listKey, timeline.single().anchor.listKey)
    }
    @Test fun twoOwnersAndConversationsNeverShareAStopOrReplaceEachOthersEvents() {
        val first = message()
        val second = first.copy(conversationId = ConversationId("other-conversation"))
        val result = withStoppedRuns(listOf(first, second), emptyList())
        assertEquals(2, result.size)
        assertEquals(2, result.map { it.id }.toSet().size)
    }
    @Test fun compressionAndContextLimitEvidenceRemainUnchanged() {
        val owner = message()
        val anchor = TimelineAnchor.at(listOf(owner), 0)
        val compression = ChatTimelineEntry.Compression("comp", anchor, 1, "summary", null, 2, null, null)
        val limit = ChatTimelineEntry.ContextLimit("limit", anchor, 2, 12, 10, 8)
        val result = withStoppedRuns(listOf(owner), listOf(compression, limit))
        assertEquals(listOf(compression, limit), result.take(2))
        assertTrue(result.last() is ChatTimelineEntry.Stopped)
    }
    @Test fun displayOnlyStopsNeverAddANewBackupOrDatabaseWireKind() {
        val stop = withStoppedRuns(listOf(message()), emptyList()).single()
        try { ChatTimelineStore.kind(stop); fail("display stop was persistable") } catch (_: IllegalStateException) { }
        try { ChatTimelineStore.encode(stop); fail("display stop was encodable") } catch (_: IllegalStateException) { }
    }
}
