package app.daiyu.android.timeline

import app.daiyu.android.domain.*
import org.junit.Assert.*
import org.junit.Test

class ChatTimelineCodecTest {
    private val anchor = TimelineAnchor("conversation", "run", "message", 0)
    private val identity = SummaryModelIdentity("summary-provider", "actual-summary-model", "RESPONSES")
    private fun compression() = ChatTimelineEntry.Compression("archive", anchor, 123L,
        "完整摘要\n仍未完成：任务甲\n已完成：任务乙", identity, 8, 400L, 30L)

    @Test fun completeSummaryAndActualCompressionBindingSurviveRoundTrip() {
        val entry = compression()
        assertEquals(entry, ChatTimelineStore.decode(entry.id, entry.created, ChatTimelineStore.encode(entry)))
    }
    @Test fun legacyUnknownModelAndUnknownTokenCountsStayUnknown() {
        val entry = compression().copy(model = null, beforeTokens = null, afterTokens = null)
        val decoded = ChatTimelineStore.decode(entry.id, entry.created, ChatTimelineStore.encode(entry))
        assertEquals(entry, decoded)
        assertNull((decoded as ChatTimelineEntry.Compression).model)
    }
    @Test fun everyReconnectStatusKeepsItsOwnErrorAndElapsedDuration() {
        for (status in listOf("running", "succeeded", "failed", "stopped")) {
            val entry = ChatTimelineEntry.Reconnect("reconnect-$status", anchor, 456L,
                status, 60_123L, "HTTP 503\n原始错误详情", 3, "actual-main-model")
            assertEquals(entry, ChatTimelineStore.decode(entry.id, entry.created, ChatTimelineStore.encode(entry)))
        }
    }
    @Test fun segmentAnchorUsesTheSameLengthPrefixedTurnKeyAndSeparatesOwners() {
        val first = Message(MessageId("message"), ConversationId("conversation"), RunId("run"),
            "assistant", emptyList(), RunStatus.COMPLETED)
        val other = first.copy(runId = RunId("other"))
        val history = listOf(first, other, first)
        assertEquals(anchor, TimelineAnchor.at(history, 0))
        assertEquals(anchor.copy(segment = 1), TimelineAnchor.at(history, 2))
        assertEquals("turn:12:conversation3:run7:message1:0", anchor.listKey)
        assertNotEquals(anchor.listKey, TimelineAnchor.at(history, 1).listKey)
        assertNotEquals(anchor.listKey, TimelineAnchor.at(history, 2).listKey)
    }
    @Test fun malformedElapsedCountsAndStatusAreRejectedRatherThanCoerced() {
        val reconnect = ChatTimelineEntry.Reconnect("reconnect", anchor, 0L, "stopped", 0L, "error", 1, "model")
        for ((key, value) in listOf("elapsed" to -1L, "elapsed" to 1.5, "elapsed" to "1",
            "round" to -1, "segment" to (Int.MAX_VALUE.toLong() + 1), "status" to "success")) {
            rejects { ChatTimelineStore.decode(reconnect.id, 0L, ChatTimelineStore.encode(reconnect).put(key, value)) }
        }
        rejects { ChatTimelineStore.decode("archive", -1L, ChatTimelineStore.encode(compression())) }
        assertEquals(0, (ChatTimelineStore.decode("archive", 0L,
            ChatTimelineStore.encode(compression()).put("messages", 0)) as ChatTimelineEntry.Compression).messages)
        rejects { ChatTimelineStore.decode("archive", 0L, ChatTimelineStore.encode(compression()).put("messages", 50_001)) }
    }
    @Test fun unknownPayloadAndModelFieldsAreNotAcceptedIntoNeutralBackup() {
        rejects { ChatTimelineStore.decode("archive", 0L, ChatTimelineStore.encode(compression()).put("api_key", "secret")) }
        val row = ChatTimelineStore.encode(compression())
        row.getJSONObject("model").put("api_key", "secret")
        rejects { ChatTimelineStore.decode("archive", 0L, row) }
    }

    @Test fun contextLimitKeepsExactStopPositionBudgetAndAgentLabel() {
        val entry = ChatTimelineEntry.ContextLimit("context-limit:one", anchor.copy(segment = 2), 789L,
            280_000L, 272_000L, 270_000L, "子代理 · review")
        assertEquals("context_limit", ChatTimelineStore.kind(entry))
        assertEquals(entry, ChatTimelineStore.decode(entry.id, entry.created, ChatTimelineStore.encode(entry)))
        for ((key, value) in listOf("estimated" to 270_000L, "budget" to 0L, "window" to 260_000L,
            "estimated" to "280000", "estimated" to 1.2)) {
            rejects { ChatTimelineStore.decode(entry.id, entry.created, ChatTimelineStore.encode(entry).put(key, value)) }
        }
    }
    private fun rejects(block: () -> Unit) {
        try { block(); fail("invalid display evidence accepted") } catch (_: IllegalArgumentException) { }
    }
}
