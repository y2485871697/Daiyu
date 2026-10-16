package app.daiyu.android.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageHistoryTest {
    private val conversation = ConversationId("conversation")
    private val run = RunId("run")
    private val ownerId = MessageId("owner")

    private fun row(
        id: String,
        role: String,
        blocks: List<MessageBlock> = emptyList(),
        status: RunStatus = RunStatus.RUNNING,
        error: String? = "old-error",
        protocol: List<String> = emptyList(),
        history: List<MessageHistorySegment> = emptyList()
    ) = Message(MessageId(id), conversation, run, role, blocks, status, error, protocol, history)

    private fun segment(id: String, role: String, text: String, protocol: List<String> = emptyList()) =
        MessageHistorySegment(MessageId(id), role, listOf(MessageBlock.Text(id, text)), protocol)

    @Test fun expansionUsesSegmentOrderAndSkipsValidatedStandaloneSupplements() {
        val supplement = row("supplement", "user", listOf(MessageBlock.Text("supplement", "question")), RunStatus.COMPLETED, null)
        val owner = row(
            id = "owner",
            role = "assistant",
            blocks = listOf(MessageBlock.Text("legacy", "ignored")),
            history = listOf(
                segment("owner", "assistant", "first", listOf("protocol-1")),
                segment("supplement", "user", "question"),
                MessageHistorySegment(
                    MessageId("owner"), "assistant",
                    listOf(MessageBlock.Tool(ToolInvocation(ToolCallId("call"), "tool", "{}"), "result")),
                    listOf("protocol-2")
                )
            )
        )

        val expanded = MessageHistory.expand(listOf(supplement, owner))
        assertEquals(3, expanded.size)
        assertEquals(listOf("assistant", "user", "assistant"), expanded.map { it.role })
        assertEquals(listOf("owner", "supplement", "owner"), expanded.map { it.id.value })
        assertEquals(listOf("first", "question", "result"), expanded.map { message ->
            message.blocks.single().let { block ->
                when (block) {
                    is MessageBlock.Text -> block.text
                    is MessageBlock.Tool -> block.result
                    is MessageBlock.Image -> error("Text/tool fixture unexpectedly contains an image")
                }
            }
        })
        assertEquals(listOf(listOf("protocol-1"), emptyList<String>(), listOf("protocol-2")), expanded.map { it.protocolItems })
        assertTrue(expanded.all { it.historySegments.isEmpty() })
        assertEquals(RunStatus.COMPLETED, expanded[1].status)
        assertEquals(null, expanded[1].error)
    }

    @Test fun emptySegmentsReturnLegacyHistoryWithoutRewritingRows() {
        val user = row("user", "user", status = RunStatus.COMPLETED, error = null)
        val assistant = row("assistant", "assistant", status = RunStatus.COMPLETED, error = null)
        val history = listOf(user, assistant)
        assertSame(history, MessageHistory.expand(history))
    }

    @Test fun expansionRetainsNonOwnerRowsInTheirOriginalPositions() {
        val before = row("before", "user", status = RunStatus.COMPLETED, error = null)
        val after = row("after", "user", status = RunStatus.COMPLETED, error = null)
        val owner = row("owner", "assistant", history = listOf(segment("owner", "assistant", "answer")))
        val expanded = MessageHistory.expand(listOf(before, owner, after))
        assertEquals(listOf("before", "owner", "after"), expanded.map { it.id.value })
    }

    @Test(expected = IllegalArgumentException::class)
    fun ownerMustBeAssistant() {
        MessageHistory.expand(listOf(row("owner", "user", history = listOf(segment("owner", "assistant", "bad")))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun assistantSegmentMustUseOwnerIdentity() {
        MessageHistory.expand(listOf(row("owner", "assistant", history = listOf(segment("other", "assistant", "bad")))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun userSegmentCannotUseOwnerIdentity() {
        MessageHistory.expand(listOf(row("owner", "assistant", history = listOf(segment("owner", "user", "bad")))))
    }

    @Test fun userSegmentMayBeExpandedWhenItsStandaloneRowWasNotLoaded() {
        val owner = row("owner", "assistant", history = listOf(segment("user", "user", "segment")))
        val expanded = MessageHistory.expand(listOf(owner))
        assertEquals(listOf("user"), expanded.map { it.id.value })
        assertEquals("segment", (expanded.single().blocks.single() as MessageBlock.Text).text)
        assertEquals(RunStatus.COMPLETED, expanded.single().status)
        assertEquals(null, expanded.single().error)
    }

    @Test(expected = IllegalArgumentException::class)
    fun supplementMustMatchItsStandaloneUserRow() {
        val owner = row("owner", "assistant", history = listOf(segment("user", "user", "segment")))
        val user = row("user", "user", listOf(MessageBlock.Text("u", "different")), RunStatus.COMPLETED, null)
        MessageHistory.expand(listOf(owner, user))
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateOwnerIdentityIncludingLegacyRowIsRejected() {
        val owner = row("owner", "assistant", history = listOf(segment("owner", "assistant", "one")))
        val legacy = row("owner", "assistant", status = RunStatus.COMPLETED, error = null)
        MessageHistory.expand(listOf(owner, legacy))
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateOwnerIdentityIsRejected() {
        val first = row("owner", "assistant", history = listOf(segment("owner", "assistant", "one")))
        val second = row("owner", "assistant", history = listOf(segment("owner", "assistant", "two")))
        MessageHistory.expand(listOf(first, second))
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateUserSegmentIdentityIsRejected() {
        val first = row("one", "assistant", history = listOf(segment("user", "user", "one")))
        val second = row("two", "assistant", history = listOf(segment("user", "user", "two")))
        val user = row("user", "user", listOf(MessageBlock.Text("user", "one")), RunStatus.COMPLETED, null)
        MessageHistory.expand(listOf(first, second, user))
    }
}
