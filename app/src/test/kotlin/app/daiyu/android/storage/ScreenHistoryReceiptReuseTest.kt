package app.daiyu.android.storage

import app.daiyu.android.domain.*
import org.junit.Assert.*
import org.junit.Test

class ScreenHistoryReceiptReuseTest {
    private fun tool(arguments: String = "{}", result: String = "pixels", id: String = "screen") =
        MessageBlock.Tool(ToolInvocation(ToolCallId(id), "screen", arguments), result)

    @Test fun exactProofIsReusedAcrossTextChangesWithoutRetainingTheText() {
        val cache = ScreenHistoryReceiptReuse()
        val items = listOf("call", "receipt")
        var proofs = 0
        repeat(100) { n ->
            assertTrue(cache.validate(items, listOf(tool(), MessageBlock.Text("body", "delta-$n")), 1) { proofs++; true })
        }
        assertEquals(1, proofs)
        assertEquals(1L, cache.validationCalls)
        assertEquals(99L, cache.hits)
        assertEquals(1, cache.retainedEntries)
    }

    @Test fun everyProtocolToolAndIndexChangeMustRunThePredicateAgain() {
        val cache = ScreenHistoryReceiptReuse()
        val items = listOf("Aa", "receipt")
        assertEquals("Aa".hashCode(), "BB".hashCode())
        assertTrue(cache.validate(items, listOf(tool()), 1) { true })
        val changes = listOf(
            Triple(listOf("BB", "receipt"), listOf(tool()), 1),
            Triple(items.reversed(), listOf(tool()), 1),
            Triple(items + "duplicate", listOf(tool()), 1),
            Triple(items, listOf(tool(arguments = "{ }")), 1),
            Triple(items, listOf(tool(result = "pixelz")), 1),
            Triple(items, listOf(tool(id = "other")), 1),
            Triple(items, listOf(tool().copy(invocation = tool().invocation.copy(name = "other"))), 1),
            Triple(items, listOf(tool(), tool()), 1),
            Triple(items, emptyList(), 1),
            Triple(items, listOf(tool()), 0))
        changes.forEach { (protocol, blocks, index) ->
            var called = false
            assertFalse(cache.validate(protocol, blocks, index) { called = true; false })
            assertTrue(called)
        }
        assertEquals(1, cache.retainedEntries)
        assertEquals(11L, cache.validationCalls)
    }

    @Test fun mutableCallerListsCannotMutateTheRetainedProofKey() {
        val cache = ScreenHistoryReceiptReuse()
        val items = mutableListOf("call", "receipt")
        val blocks = mutableListOf<MessageBlock>(tool())
        assertTrue(cache.validate(items, blocks, 1) { true })
        items[1] = "changed"
        assertFalse(cache.validate(items, blocks, 1) { false })
        items[1] = "receipt"
        blocks[0] = tool(result = "changed")
        assertFalse(cache.validate(items, blocks, 1) { false })
        assertTrue(cache.validate(listOf("call", "receipt"), listOf(tool()), 1) { error("exact old proof must survive") })
    }

    @Test fun falseAndThrowingPredicatesNeverBecomeProofs() {
        val cache = ScreenHistoryReceiptReuse()
        repeat(2) { assertFalse(cache.validate(listOf("x"), emptyList(), 0) { false }) }
        assertThrows(IllegalStateException::class.java) {
            cache.validate(listOf("x"), emptyList(), 0) { error("fixture") }
        }
        assertEquals(0, cache.retainedEntries)
        assertTrue(cache.validate(listOf("x"), emptyList(), 0) { true })
        assertEquals(4L, cache.validationCalls)
    }

    @Test fun characterAndEntryBudgetsFallBackWithoutRejectingValidInputs() {
        val cache = ScreenHistoryReceiptReuse(maxCharacters = 6, maxEntries = 2)
        assertTrue(cache.validate(listOf("aaa"), emptyList(), 0) { true })
        assertTrue(cache.validate(listOf("bbb"), emptyList(), 0) { true })
        assertEquals(6L, cache.retainedCharacters)
        assertTrue(cache.validate(listOf("cc"), emptyList(), 0) { true })
        assertEquals(2, cache.retainedEntries)
        assertEquals(5L, cache.retainedCharacters)
        repeat(2) { assertTrue(cache.validate(listOf("1234567"), emptyList(), 0) { true }) }
        assertEquals(5L, cache.retainedCharacters)
        assertEquals(5L, cache.validationCalls)
        cache.clear()
        assertEquals(0, cache.retainedEntries)
        assertEquals(0L, cache.retainedCharacters)
        assertTrue(cache.validate(listOf("cc"), emptyList(), 0) { true })
        assertEquals(6L, cache.validationCalls)
    }

    @Test fun clearingTheExistingLeaseContextAlsoDropsItsReceiptProofs() {
        val reuse = CommitMessageDecodeReuse()
        assertTrue(reuse.historyReceipts.validate(listOf("x"), emptyList(), 0) { true })
        reuse.clear()
        assertEquals(0, reuse.historyReceipts.retainedEntries)
        assertTrue(reuse.historyReceipts.validate(listOf("x"), emptyList(), 0) { true })
        reuse.close()
        assertEquals(0, reuse.historyReceipts.retainedEntries)
        assertEquals(0L, reuse.historyReceipts.retainedCharacters)
    }
}
