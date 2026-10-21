package app.daiyu.android.context

import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.domain.context.CompressionPolicy
import org.json.JSONObject

@Suppress("DEPRECATION")
class TokenTailCompressionTest : InstrumentationTestCase() {
    private fun message(index: Int, role: String = "assistant", blocks: List<MessageBlock> =
        listOf(MessageBlock.Text("text", "a".repeat(400)))) = Message(MessageId("m-$index"),
        ConversationId("owner"), RunId("run-$index"), role, blocks, RunStatus.COMPLETED)

    fun testTokenPricesAndBilledLocalConversionMatchOriginalConstants() {
        assertEquals(0L, TokenTailCompression.textTokens(""))
        assertEquals(2L, TokenTailCompression.textTokens("中"))
        assertEquals(1L, TokenTailCompression.textTokens("abcd"))
        assertEquals(103L, TokenTailCompression.messageTokens(message(0)))
        assertEquals(100L, TokenTailCompression.localBudget(100, null, 200))
        assertEquals(50L, TokenTailCompression.localBudget(100, 200, 100))
        assertEquals(150L, TokenTailCompression.localBudget(100, 1, 1000))
        assertEquals(1L, TokenTailCompression.localBudget(100, 100000, 1))
    }

    fun testSixteenPercentTailAndHalfFallbackAndOverflowKeepOriginalTail() {
        val history = (0..9).map { message(it) }
        assertEquals(8, TokenTailCompression.selectStart(history, 1000))
        assertEquals(5, TokenTailCompression.selectStart(history, 100000))
        assertEquals(9, TokenTailCompression.selectStart(history, 100000, overflow = true))
        assertEquals(0, TokenTailCompression.selectStart(history, null))
        assertEquals(0, TokenTailCompression.selectStart(history.take(1), 1000))
    }

    fun testIncompleteToolBatchKeepsEveryFollowingMessageAndOpaqueKeepsLatestUnit() {
        val history = (0..9).map { message(it) }.toMutableList()
        history[5] = message(5, blocks = listOf(MessageBlock.Tool(ToolInvocation(ToolCallId("call"), "tool", "{}"))))
        assertTrue(TokenTailCompression.isSafeCut(history, 5))
        assertFalse(TokenTailCompression.isSafeCut(history, 6))
        assertEquals(5, TokenTailCompression.selectStart(history, 1000))
        val opaque = (0..9).map { message(it) }.toMutableList()
        opaque[0] = opaque[0].copy(protocolItems = listOf("""{"type":"reasoning","encrypted_content":"opaque"}"""))
        assertEquals(9, TokenTailCompression.selectStart(opaque, 1000))
        opaque[8] = opaque[8].copy(role = "user")
        assertEquals(8, TokenTailCompression.selectStart(opaque, 1000))
    }

    fun testArchiveEnvelopePreservesPhysicalOwnerAndLegacyArrays() {
        val owner = message(1).copy(historySegments = listOf(
            MessageHistorySegment(MessageId("m-1"), "assistant", listOf(MessageBlock.Text("a", "first"))),
            MessageHistorySegment(MessageId("segment-u"), "user", listOf(MessageBlock.Text("u", "next"))),
            MessageHistorySegment(MessageId("m-1"), "assistant", listOf(MessageBlock.Text("text", "a".repeat(400))))))
        val physical = listOf(message(0, "user"), owner)
        val encoded = ArchiveCodec.encodeDocument(physical, 2)
        val decoded = ArchiveCodec.decodeDocument(encoded)
        assertEquals(physical, decoded.messages)
        assertEquals(2, decoded.logicalPrefixCount)
        assertEquals(physical, ArchiveCodec.decode(encoded))
        assertNull(ArchiveCodec.decodeDocument(ArchiveCodec.encode(physical)).logicalPrefixCount)
        assertEquals(listOf(0, 1, 1, 1), MessageHistory.expandWithSources(physical).map { it.sourcePhysicalIndex })
        for (bad in listOf(JSONObject(String(encoded, Charsets.UTF_8)).put("logical_prefix_count", 0),
            JSONObject(String(encoded, Charsets.UTF_8)).put("logical_prefix_count", 1.5),
            JSONObject(String(encoded, Charsets.UTF_8)).put("version", 1),
            JSONObject(String(encoded, Charsets.UTF_8)).put("unknown", true))) {
            try { ArchiveCodec.decodeDocument(bad.toString().toByteArray()); fail("invalid envelope") }
            catch (_: IllegalArgumentException) { }
        }
    }

    fun testProductionTriggerUsesFullWindowWhileSendBudgetKeepsReserve() {
        val policy = CompressionPolicy(true, 10000, reserveTokens = 2000, tokenTail = true)
        assertFalse(policy.shouldCompress(7999)); assertTrue(policy.shouldCompress(8000))
        assertTrue(policy.fitsSendBudget(8000)); assertFalse(policy.fitsSendBudget(8001))
        assertTrue(CompressionPolicy(true, 10000, reserveTokens = 2000).shouldCompress(6400))
    }
}
