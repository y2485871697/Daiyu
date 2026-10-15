package app.daiyu.android.domain.context

import app.daiyu.android.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.security.MessageDigest

/** 05 tests against the real 03 domain types, without Android/JSON substitutes. */
class ContextHistoryPolicyTest {
    private val conversation = ConversationId("history-context")
    private fun text(value: String) = listOf(MessageBlock.Text("item", value))
    private fun row(id: String, role: String, run: String = "r1", value: String = id) =
        Message(MessageId(id), conversation, RunId(run), role, text(value), RunStatus.COMPLETED)
    private fun owner(): Message {
        val m = row("a", "assistant", value = "display only")
        return m.copy(historySegments = listOf(
            MessageHistorySegment(m.id, "assistant", text("authoritative first"), listOf("provider-item-1")),
            MessageHistorySegment(MessageId("extra"), "user", text("supplement")),
            MessageHistorySegment(m.id, "assistant", text("authoritative last"), listOf("provider-item-2"))
        ))
    }
    private fun supplement() = row("extra", "user", value = "supplement")
    private fun turn(n: Int) = listOf(row("u$n", "user", "r$n"), row("a$n", "assistant", "r$n"))
    private fun rejects(block: () -> Unit) {
        try { block(); fail("must reject") } catch (_: IllegalArgumentException) { }
    }

    @Test fun estimateUsesAuthoritativeOrderAndCountsStandaloneSupplementOnce() {
        val owner = owner()
        val physical = listOf(row("u", "user"), owner, supplement())
        val expanded = MessageHistory.expand(physical)
        assertEquals(4, expanded.size)
        assertEquals(estimateTokens(expanded), estimateTokens(physical))
        assertEquals(estimateTokens(physical), estimateTokens(listOf(physical[0], owner)))
        assertEquals(estimateTokens(physical), estimateTokens(listOf(physical[0], owner.copy(blocks = text("ignored".repeat(10000))), supplement())))
        assertNotEquals(estimateTokens(physical), estimateTokens(physical.map { it.copy(historySegments = emptyList()) }))
    }

    @Test fun revisionIncludesSegmentOrderIdentityRoleBlocksAndProtocol() {
        val owner = owner()
        val base = ContextRevision.digest(listOf(owner))
        val segments = owner.historySegments
        val changes = listOf(
            segments.reversed(),
            segments.mapIndexed { i, s -> if (i == 1) s.copy(messageId = MessageId("other")) else s },
            segments.mapIndexed { i, s -> if (i == 0) s.copy(role = "user") else s },
            segments.mapIndexed { i, s -> if (i == 0) s.copy(blocks = text("changed")) else s },
            segments.mapIndexed { i, s -> if (i == 2) s.copy(protocolItems = listOf("changed-provider-item")) else s }
        )
        changes.forEach { assertNotEquals(base, ContextRevision.digest(listOf(owner.copy(historySegments = it)))) }
        assertNotEquals(base, ContextRevision.digest(listOf(owner.copy(historySegments = emptyList()))))
    }

    @Test fun emptySegmentsKeepFrozenLegacyDigestAlgorithm() {
        val messages = turn(1)
        // Independent frozen pre-history framing; zero segment counts must never be appended.
        val hash = MessageDigest.getInstance("SHA-256")
        fun field(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            hash.update(ByteBuffer.allocate(4).putInt(bytes.size).array()); hash.update(bytes)
        }
        messages.forEach { m ->
            field(m.id.value); field(m.conversationId.value); field(m.runId.value); field(m.role)
            field("COMPLETED"); field("null"); field(""); field("1")
            field("text"); field("item"); field((m.blocks.single() as MessageBlock.Text).text)
            field("0")
        }
        val expected = hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(expected, ContextRevision.digest(messages))
    }

    @Test fun physicalPrefixClosesOwnersAndSupplementsInBothDirections() {
        val messages = listOf(row("u", "user"), owner(), supplement()) + turn(2) + turn(3)
        assertFalse(isHistoryClosedPrefix(messages, 2))
        assertTrue(isHistoryClosedPrefix(messages, 3))
        assertEquals(3, compressiblePrefix(messages, 2))
        assertEquals(5, compressiblePrefix(messages, 1))
        val userFirst = listOf(row("u", "user"), supplement(), owner()) + turn(2)
        assertFalse(isHistoryClosedPrefix(userFirst, 2))
        assertEquals(3, compressiblePrefix(userFirst, 1))
    }

    @Test fun noSafeCutOrIncompleteAuthoritativeToolReturnsZero() {
        val messages = listOf(row("u", "user"), owner(), supplement()) + turn(2)
        assertEquals(0, compressiblePrefix(messages, 2))
        val pending = owner().copy(historySegments = listOf(MessageHistorySegment(MessageId("a"), "assistant",
            listOf(MessageBlock.Tool(ToolInvocation(ToolCallId("provider-call"), "read", "{}"), null)))))
        val history = listOf(row("u", "user"), pending) + turn(2) + turn(3)
        assertEquals(0, compressiblePrefix(history, 1))
        assertFalse(isCompleteContextPrefix(history, 2))
    }

    @Test fun runClosureStillAppliesAndConflictingSupplementFailsClosed() {
        val messages = listOf(row("u", "user"), owner(), supplement()) + turn(2) +
            listOf(row("tail-user", "user"), row("tail-assistant", "assistant"))
        assertFalse(isCompleteContextPrefix(messages, 3))
        assertEquals(0, compressiblePrefix(messages, 1))
        val conflict = listOf(owner(), supplement().copy(blocks = text("stale")))
        rejects { estimateTokens(conflict) }
        rejects { compressiblePrefix(conflict, 1) }
        rejects { isHistoryClosedPrefix(conflict, 1) }
    }
}
