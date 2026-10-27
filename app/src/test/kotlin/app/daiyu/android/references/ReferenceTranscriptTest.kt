package app.daiyu.android.references

import app.daiyu.android.domain.*
import java.nio.file.Files
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReferenceTranscriptTest {
    private val conversation = ConversationId("source")
    private fun message(id: String, run: String = "run", blocks: List<MessageBlock> = emptyList()) =
        Message(MessageId(id), conversation, RunId(run), "assistant", blocks, RunStatus.COMPLETED)
    private fun tool(result: String?) = MessageBlock.Tool(ToolInvocation(ToolCallId("repeated"), "read_file", "{\"path\":\"完整😀\"}"), result)
    private fun <T> directory(block: (File) -> T): T {
        val dir = Files.createTempDirectory("reference-transcript").toFile()
        try { return block(dir) } finally { dir.deleteRecursively() }
    }
    private fun reassemble(file: File, total: Long, limit: Int): String {
        val result = StringBuilder(); var offset = 0L
        do {
            val page = ReferenceTextPage.read(file, offset, limit, total)
            assertEquals(offset, page.offset)
            assertFalse(page.content.lastOrNull()?.isHighSurrogate() == true)
            result.append(page.content)
            offset = page.nextOffset ?: break
        } while (true)
        return result.toString()
    }
    @Test fun longToolArgumentsAndResultsReassembleVerbatimIncludingTail() = directory { dir ->
        val full = "原文😀".repeat(20000) + "TAIL-SENTINEL"
        val lengths = ReferenceTranscript.write(dir, listOf(message("owner", blocks = listOf(tool(full)))))
        val json = JSONObject(reassemble(File(dir, "tool-0-0"), lengths.getValue("tool-0-0"), 127))
        assertEquals(full, json.getString("result"))
        assertEquals("{\"path\":\"完整😀\"}", json.getString("arguments"))
        assertEquals("stored_verbatim", json.getString("completeness"))
        assertTrue(reassemble(File(dir, "transcript"), lengths.getValue("transcript"), 4000).contains("TAIL-SENTINEL"))
    }
    @Test fun ownerSegmentsAndSupplementAreInAuthoritativeOrderWithoutDuplicateUser() = directory { dir ->
        val user = Message(MessageId("supplement"), conversation, RunId("run"), "user", listOf(MessageBlock.Text("u", "补充原文")), RunStatus.COMPLETED)
        val owner = message("owner").copy(historySegments = listOf(
            MessageHistorySegment(MessageId("owner"), "assistant", listOf(tool("first-tool-result"))),
            MessageHistorySegment(user.id, "user", user.blocks),
            MessageHistorySegment(MessageId("owner"), "assistant", listOf(tool("last-tool-result"))),
        ))
        ReferenceTranscript.write(dir, listOf(owner, user))
        val transcript = File(dir, "transcript").readText()
        assertEquals(1, Regex("补充原文").findAll(transcript).count())
        assertTrue(transcript.indexOf("first-tool-result") < transcript.indexOf("补充原文"))
        assertTrue(transcript.indexOf("补充原文") < transcript.indexOf("last-tool-result"))
        assertEquals("first-tool-result", JSONObject(File(dir, "tool-0-0").readText()).getString("result"))
        assertEquals("last-tool-result", JSONObject(File(dir, "tool-2-0").readText()).getString("result"))
    }
    @Test fun nullResultIsNotFalselyReportedAsEmptyCompleteOutput() = directory { dir ->
        ReferenceTranscript.write(dir, listOf(message("owner", blocks = listOf(tool(null)))))
        val record = JSONObject(File(dir, "tool-0-0").readText())
        assertTrue(record.isNull("result"))
        assertEquals("result_not_recorded", record.getString("completeness"))
    }
    @Test fun everyUnicodeBoundaryIncludingSingleCharacterPagesHasNoGap() = directory { dir ->
        val text = "甲😀乙🚀末尾"
        val file = File(dir, "unicode").apply { writeText(text) }
        for (size in 1..text.length) assertEquals(text, reassemble(file, text.length.toLong(), size))
        val insideEmoji = ReferenceTextPage.read(file, 2, 1, text.length.toLong())
        assertEquals(1L, insideEmoji.offset)
        assertEquals("😀", insideEmoji.content)
        assertEquals("", ReferenceTextPage.read(file, text.length.toLong(), 1, text.length.toLong()).content)
    }
    @Test fun snapshotFilesDoNotChangeWhenOriginalObjectsAreReplaced() = directory { dir ->
        val history = mutableListOf(message("owner", blocks = listOf(tool("original"))))
        ReferenceTranscript.write(dir, history)
        history[0] = message("owner", blocks = listOf(tool("revised")))
        assertEquals("original", JSONObject(File(dir, "tool-0-0").readText()).getString("result"))
    }
}
