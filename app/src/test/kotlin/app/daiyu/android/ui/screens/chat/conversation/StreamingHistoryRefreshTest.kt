package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.domain.*
import org.junit.Assert.*
import org.junit.Test

class StreamingHistoryRefreshTest {
    private val owner = Message(MessageId("answer"), ConversationId("chat"), RunId("run"),
        Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)

    @Test fun thousandsOfDeltasRequireOnlyTheInitialHistoryRead() {
        val refresh = StreamingHistoryRefresh()
        assertTrue(refresh.needsRead(owner, true, 1, 1, false))
        repeat(2_000) { index ->
            val updated = owner.copy(blocks = listOf(MessageBlock.Text("body", "answer $index")),
                reportedReasoning = listOf(ReportedReasoning("thought", "thinking $index")))
            assertFalse(refresh.needsRead(updated, true, 1, 1, false))
        }
    }

    @Test fun terminalStopFailureAndLeavingTheLiveOwnerAlwaysReconcile() {
        for (status in RunStatus.entries.filter { it != RunStatus.RUNNING }) {
            val refresh = StreamingHistoryRefresh()
            refresh.needsRead(owner, true, 1, 1, false)
            assertTrue(refresh.needsRead(owner.copy(status = status), true, 1, 1, false))
        }
        val refresh = StreamingHistoryRefresh()
        refresh.needsRead(owner, true, 1, 1, false)
        assertTrue(refresh.needsRead(null, true, 1, 1, false))
        assertTrue(refresh.needsRead(owner, true, 1, 1, false))
    }

    @Test fun failedHistoryReadCanRetryOnTheNextUpdate() {
        val refresh = StreamingHistoryRefresh()
        assertTrue(refresh.needsRead(owner, true, 1, 1, false))
        refresh.invalidate()
        assertTrue(refresh.needsRead(owner, true, 1, 1, false))
        assertFalse(refresh.needsRead(owner, true, 1, 1, false))
    }

    @Test fun missingOwnerAndExplicitTailRefreshNeverSkip() {
        val refresh = StreamingHistoryRefresh()
        refresh.needsRead(owner, true, 1, 1, false)
        repeat(3) { assertTrue(refresh.needsRead(owner, false, 1, 1, false)) }
        assertTrue(refresh.needsRead(owner, true, 1, 1, true))
        assertFalse(refresh.needsRead(owner, true, 1, 1, false))
    }

    @Test fun editorDataAndExactOwnerChangesReconcile() {
        val refresh = StreamingHistoryRefresh()
        refresh.needsRead(owner, true, 1, 1, false)
        assertTrue(refresh.needsRead(owner, true, 2, 1, false))
        assertTrue(refresh.needsRead(owner, true, 2, 2, false))
        assertTrue(refresh.needsRead(owner.copy(runId = RunId("next")), true, 2, 2, false))
        assertTrue(refresh.needsRead(owner.copy(conversationId = ConversationId("other")), true, 2, 2, false))
        assertTrue(refresh.needsRead(owner.copy(id = MessageId("other")), true, 2, 2, false))
    }

    @Test fun supplementalUsersReconcileButAssistantProtocolSnapshotsDoNot() {
        val refresh = StreamingHistoryRefresh()
        refresh.needsRead(owner, true, 1, 1, false)
        val assistant = MessageHistorySegment(owner.id, Message.ASSISTANT_ROLE, emptyList())
        val reasoning = owner.copy(historySegments = listOf(assistant))
        assertFalse(refresh.needsRead(reasoning, true, 1, 1, false))
        val user = MessageHistorySegment(MessageId("supplement"), Message.USER_ROLE,
            listOf(MessageBlock.Text("body", "extra instruction")))
        val appended = owner.copy(historySegments = listOf(assistant, user))
        assertTrue(refresh.needsRead(appended, true, 1, 1, false))
        assertFalse(refresh.needsRead(appended.copy(blocks = listOf(MessageBlock.Text("body", "new text"))), true, 1, 1, false))
        assertTrue(refresh.needsRead(reasoning, true, 1, 1, false))
    }
    @Test fun inFlightReadAcceptsOnlyTheExactLiveRowChanging() {
        val historical = owner.copy(id = MessageId("old"), status = RunStatus.COMPLETED)
        val before = listOf(1L to historical, 2L to owner)
        val active = owner.copy(blocks = listOf(MessageBlock.Text("body", "latest")))
        val after = listOf(1L to historical, 2L to active)
        assertTrue(canOverlayLiveHistory(before, after, active))
        assertFalse(canOverlayLiveHistory(before, after, null))
        assertFalse(canOverlayLiveHistory(before, after + (3L to owner), active))
        assertFalse(canOverlayLiveHistory(before, listOf(1L to historical.copy(error = "changed"), 2L to active), active))
        assertFalse(canOverlayLiveHistory(before, listOf(1L to historical, 3L to active), active))
        assertFalse(canOverlayLiveHistory(before, after.reversed(), active))
        assertFalse(canOverlayLiveHistory(before, listOf(1L to historical, 2L to active.copy(runId = RunId("other"))), active))
    }

}
