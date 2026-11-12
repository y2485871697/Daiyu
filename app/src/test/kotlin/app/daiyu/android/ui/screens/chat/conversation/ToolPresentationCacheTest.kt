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

    @Test fun mountedHistoryLargerThanTheLruBudgetDoesNotReparseOnEveryStreamChunk() {
        var builds = 0
        val cache = ToolPresentationCache({ n, a, r -> builds++; ToolCardPresentations.present(n, a, r) },
            maxEntries = 2, maxSourceChars = 200, maxLargeEntries = 1)
        val blocks = (0 until 12).map { tool("tool-$it", 1_000 + it) }
        var receipt = WorkStepState.OK
        repeat(20) { index ->
            if (index == 10) receipt = WorkStepState.UNKNOWN
            val input = message(*(blocks + MessageBlock.Text("tail", "chunk-$index")).toTypedArray())
            val turn = ChatTurnMapper.map(listOf(input), toolState = { _, _ -> receipt },
                presentations = cache).single() as ChatTurnUi.Assistant
            assertEquals(List(blocks.size) { receipt }, turn.workProcess!!.segments.flatMap { it.steps }.map { it.state })
        }
        assertEquals(blocks.size, builds)
        val changed = blocks[0].copy(result = blocks[0].result + " ")
        ChatTurnMapper.map(listOf(message(*(listOf(changed) + blocks.drop(1)).toTypedArray())), presentations = cache)
        assertEquals(blocks.size + 1, builds)
        cache.retain(emptyList())
        cache.present(blocks[0])
        assertEquals("An unmounted source must not survive as an identity cache entry", blocks.size + 2, builds)
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
        cache.present(large); cache.present(large); assertEquals(8, builds)
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

    private fun visual(size: Int = 1_826_000): MessageBlock.Tool {
        val output = org.json.JSONObject().put("screen_read_mode", "visual_fallback")
            .put("node_tree_available", false).put("node_failure_code", "APPLICATION_WINDOW_NOT_ACTIVE_NOT_EXECUTED")
            .put("visual_actions_available", true).put("mime_type", "image/png").put("capture_id", "synthetic")
            .put("width", 1216).put("height", 2640).put("data_base64", "SYNTHETIC_IMAGE_SENTINEL" + "A".repeat(size)).toString()
        return tool("visual", 0).copy(result = output)
    }

    @Test fun screenshotSizedVisualReceiptIsClassifiedAndProjectedOnlyOnceWhileReceiptsStayLive() {
        var builds = 0
        val cache = ToolPresentationCache({ n, a, r -> builds++; ToolCardPresentations.present(n, a, r) })
        val block = visual()
        repeat(100) { index ->
            val input = message(block, MessageBlock.Text("tail", "stream-$index")).copy(status = RunStatus.RUNNING)
            val turn = ChatTurnMapper.map(listOf(input), presentations = cache,
                toolState = { _, _ -> if (index < 50) WorkStepState.OK else WorkStepState.UNKNOWN }).single() as ChatTurnUi.Assistant
            val step = turn.workProcess!!.segments.flatMap { it.steps }.single()
            assertTrue(step.hasScreenScreenshot)
            assertEquals(if (index < 50) WorkStepState.OK else WorkStepState.UNKNOWN, step.state)
        }
        assertEquals(1, builds)
        val projection = cache.present(block)
        val rich = projection.details.rich
        assertTrue(rich.documents.isEmpty())
        assertTrue(rich.technical.isEmpty())
        assertFalse((projection.toString() + rich.fields + rich.entries + rich.prose).contains("SYNTHETIC_IMAGE_SENTINEL"))
        val differentReference = String(block.result!!.toCharArray())
        assertNotSame(block.result, differentReference)
        cache.present(block.copy(result = differentReference)); assertEquals(2, builds)
        val ordinary = block.copy(result = "{\"nodes\":[]}")
        assertFalse(cache.present(ordinary).hasScreenScreenshot); assertEquals(3, builds)
    }

    @Test fun largeIdentityKeysRetainPruneScopeAndEvictWithoutReusingChangedArguments() {
        var builds = 0
        val cache = ToolPresentationCache({ n, a, r -> builds++; ToolCardPresentations.present(n, a, r) })
        val a = visual(262_145); val b = visual(262_146); val c = visual(262_147)
        cache.present(a); cache.present(b); cache.present(a); assertEquals(2, builds)
        cache.present(c); cache.present(a); assertEquals(3, builds)
        cache.present(b); assertEquals(4, builds)
        cache.retain(listOf(message(a)))
        cache.present(a); assertEquals(4, builds)
        val newArgs = String(a.invocation.arguments.toCharArray())
        cache.present(a.copy(invocation = a.invocation.copy(arguments = newArgs))); assertEquals(5, builds)
        cache.scope("other"); cache.present(a); assertEquals(6, builds)
        cache.clear(); cache.present(a); assertEquals(7, builds)
        cache.retain(emptyList()); cache.present(a); assertEquals(8, builds)
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
