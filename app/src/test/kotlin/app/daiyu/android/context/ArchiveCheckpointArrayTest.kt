package app.daiyu.android.context

import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.storage.RunCheckpointCodec
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class ArchiveCheckpointArrayTest {
    private fun row(text: String = "中文🙂") = Message(MessageId("m"), ConversationId("c"), RunId("r"),
        Message.ASSISTANT_ROLE, listOf(MessageBlock.Text("text", text)), RunStatus.RUNNING,
        createdAt = 123L)

    private fun compare(rows: List<Message>) {
        val old = JSONArray(String(ArchiveCodec.encode(rows), Charsets.UTF_8)).toString()
        val next = ArchiveCodec.checkpointArray(rows).toString()
        assertEquals(old, next)
        assertArrayEquals(old.toByteArray(Charsets.UTF_8), next.toByteArray(Charsets.UTF_8))
    }

    @Test fun directTreeMatchesTheOldWireRoundTripForNestedFieldsAndUnicode() {
        for (text in listOf("", "ascii", "中文🙂e\u0301", "\u0000\n\t\r\"\\/\u2028\u2029", "a\uD800b", "a\uDC00b", "\uD800\uD800", "\uDC00\uD800")) {
            compare(listOf(row(text).copy(error = text, protocolItems = listOf(text), blocks = listOf(
                MessageBlock.Text("part", text), MessageBlock.Tool(ToolInvocation(ToolCallId("tool"), "lookup", text), text)))))
            val value = row(text)
            compare(listOf(value.copy(historySegments = listOf(MessageHistorySegment(
                value.id, value.role, value.blocks, value.protocolItems)))))
        }
        compare(emptyList())
    }

    @Test fun sharedBuilderPreservesAllArchiveValidationFailures() {
        val value = row()
        for (invalid in listOf(listOf(value, value), listOf(value.copy(blocks = listOf(
            MessageBlock.Tool(ToolInvocation(ToolCallId("same"), "a", "{}")),
            MessageBlock.Tool(ToolInvocation(ToolCallId("same"), "b", "{}"))))),
            listOf(value.copy(blocks = listOf(MessageBlock.Text("t", "x".repeat(4 * 1024 * 1024 + 1))))))) {
            val old = runCatching { ArchiveCodec.encode(invalid) }.exceptionOrNull()
            val next = runCatching { ArchiveCodec.checkpointArray(invalid) }.exceptionOrNull()
            assertNotNull(old)
            assertEquals(old!!::class, next!!::class)
            assertEquals(old.message, next.message)
        }
    }

    @Test fun checkpointKeepsStrictDecodeRecoveryAndSizeCeiling() {
        assertTrue(RunCheckpointCodec.MAX_BYTES.toLong() <= ArchiveCodec.MAX_ARCHIVE_BYTES)
        val value = RunCheckpoint(row(), emptyList(), ExecutionPhase.STREAMING, owner = "owner", revision = 1)
        val restored = RunCheckpointCodec.decode(RunCheckpointCodec.encode(value))
        assertEquals(value, restored)
        assertTrue(runCatching { RunCheckpointCodec.decode(RunCheckpointCodec.encode(value) + "[]") }.isFailure)
        RunCheckpointCodec.checkEncodedSize(RunCheckpointCodec.MAX_BYTES - RunCheckpointCodec.RECOVERY_RESERVE, true)
        assertTrue(runCatching { RunCheckpointCodec.checkEncodedSize(
            RunCheckpointCodec.MAX_BYTES - RunCheckpointCodec.RECOVERY_RESERVE + 1, true) }.isFailure)
        RunCheckpointCodec.checkEncodedSize(RunCheckpointCodec.MAX_BYTES, false)
    }
}
