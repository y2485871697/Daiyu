package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.domain.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class ToolPresentationCacheTest {
    private fun tool(id: String, size: Int) = MessageBlock.Tool(
        ToolInvocation(ToolCallId(id), "screen", "{\"operation\":\"observe\"}"),
        "{\"ok\":true,\"data\":\"" + "x".repeat(size) + "\"}")
    private fun message(vararg blocks: MessageBlock) = Message(MessageId("m"), ConversationId("c"), RunId("r"),
        Message.ASSISTANT_ROLE, blocks.toList(), RunStatus.COMPLETED)

    @Test fun streamingUpdatesReuseHistoricalLargeToolsButReceiptsStayLive() {
        var builds = 0
        val cache = ToolPresentationCache({ name, args, result -> builds++; ToolCardPresentations.present(name, args, result) })
        val a = tool("a", 30_561); val b = tool("b", 11_387)
        var receipt = WorkStepState.OK
        val history = message(a, b)
        repeat(100) { index ->
            if (index == 50) receipt = WorkStepState.UNKNOWN
            val live = message(MessageBlock.Text("text", "short".repeat(index + 1))).copy(id = MessageId("next"), runId = RunId("next-run"), status = RunStatus.RUNNING)
            val turns = ChatTurnMapper.map(listOf(history, live), toolState = { _, _ -> receipt }, presentations = cache)
            val steps = (turns.first() as ChatTurnUi.Assistant).workProcess!!.segments.flatMap { it.steps }
            assertEquals(listOf(receipt, receipt), steps.map { it.state })
        }
        assertEquals("Unchanged results must not be reparsed on every live text update", 2, builds)
        ChatTurnMapper.map(listOf(message(a.copy(result = a.result + " "), b)), presentations = cache)
        assertEquals(3, builds)
    }

    @Test fun windowPruningScopeAndBudgetsReleaseOldPresentations() {
        var builds = 0
        val cache = ToolPresentationCache({ n, a, r -> builds++; ToolCardPresentations.present(n, a, r) }, maxEntries = 2, maxSourceChars = 200)
        val a = tool("a", 10); val b = tool("b", 20); val c = tool("c", 30)
        cache.scope("c")
        cache.present(a); cache.present(b); cache.present(a); assertEquals(2, builds)
        cache.present(c); cache.present(b); assertEquals(4, builds)
        cache.retain(listOf(message(a)))
        cache.present(c); assertEquals(5, builds)
        cache.scope("other"); cache.present(c); assertEquals(6, builds)
        cache.clear(); cache.present(c); assertEquals(7, builds)
        val large = tool("large", 1_000)
        cache.present(large); cache.present(large); assertEquals(9, builds)
    }

    @Test fun aggregateSourceBudgetEvictsEvenBelowTheEntryLimit() {
        var builds = 0
        val cache = ToolPresentationCache({ n, a, r -> builds++; ToolCardPresentations.present(n, a, r) },
            maxEntries = 10, maxSourceChars = 129)
        val a = tool("a", 10); val b = tool("b", 20)
        cache.present(a); cache.present(b); cache.present(b)
        assertEquals(2, builds)
        cache.present(a)
        assertEquals("The combined source budget must evict the oldest presentation", 3, builds)
        cache.retain(emptyList()); cache.present(a)
        assertEquals(4, builds)
    }

    @Test fun retainKeepsCurrentEntriesAndCachedMappingMatchesLiveUncachedMapping() {
        var builds = 0
        val cache = ToolPresentationCache({ n, a, r -> builds++; ToolCardPresentations.present(n, a, r) })
        val a = tool("a", 10); val b = tool("b", 20)
        cache.present(a); cache.present(b)
        cache.retain(listOf(message(a)))
        cache.present(a); assertEquals(2, builds)
        cache.present(b); assertEquals(3, builds)
        val input = listOf(message(a, b))
        for (state in WorkStepState.entries) {
            val activity = if (state == WorkStepState.PENDING) ToolActivityUi.STOPPING else ToolActivityUi.from(state)
            val receipt: (Message, MessageBlock.Tool) -> ToolReceiptUi? = { _, _ -> ToolReceiptUi(state, activity) }
            val dedicated: (Message, MessageBlock.Tool) -> String? = { _, block -> "dedicated:" + block.invocation.id.value }
            assertEquals(ChatTurnMapper.map(input, toolReceipt = receipt, dedicatedTool = dedicated),
                ChatTurnMapper.map(input, toolReceipt = receipt, dedicatedTool = dedicated, presentations = cache))
        }
        assertEquals("Only source presentation, not live state or activity, is reused", 3, builds)
        cache.scope("c"); cache.present(a); val afterScope = builds
        cache.scope("c"); cache.present(a); assertEquals(afterScope, builds)
        cache.scope(null); cache.present(a); assertEquals(afterScope + 1, builds)
    }

    @Test fun argumentsNamesAndNullResultsArePartOfTheExactPresentationKey() {
        var builds = 0
        val cache = ToolPresentationCache({ n, a, r -> builds++; ToolCardPresentations.present(n, a, r) })
        val original = tool("a", 1)
        cache.present(original); cache.present(original.copy(result = null))
        cache.present(original.copy(invocation = original.invocation.copy(name = "screen_action")))
        cache.present(original.copy(invocation = original.invocation.copy(arguments = "{}")))
        assertEquals(4, builds)
        cache.present(original); assertEquals(4, builds)
        cache.present(original.copy(invocation = original.invocation.copy(id = ToolCallId("other"))))
        assertEquals(4, builds)
        cache.present(original.copy(result = "")); assertEquals(5, builds)
        val aa = original.copy(result = "Aa"); val bb = original.copy(result = "BB")
        assertEquals(aa.result.hashCode(), bb.result.hashCode())
        cache.present(aa); cache.present(bb); assertEquals(7, builds)
    }
}
