package app.daiyu.android.context

import app.daiyu.android.domain.*
import org.junit.Assert.*
import org.junit.Test

class SummaryCheckpointTest {
    private fun checkpoint() = SummaryCheckpoint.marker + "\n" +
        SummaryCheckpoint.sections.joinToString("\n") { "## $it\n- factual evidence" }
    @Test fun exactEightSectionsAndNestedOrFencedHeadings() {
        val text = checkpoint()
        assertEquals(text, SummaryCheckpoint.validate(text))
        assertNotNull(SummaryCheckpoint.validate(text.replace("- factual evidence", "- fact\n### Current Work")))
        assertNotNull(SummaryCheckpoint.validate(text.replace("- factual evidence", "- fact\n```\n## not a real section\n```")))
        assertNotNull(SummaryCheckpoint.validate("```markdown\n$text\n```"))
    }
    @Test fun missingEmptyDuplicateOutOfOrderAndUnclosedFencesFail() {
        val text = checkpoint()
        SummaryCheckpoint.sections.forEach { title ->
            assertNull(SummaryCheckpoint.validate(text.replace("## $title\n- factual evidence", "")))
            assertNull(SummaryCheckpoint.validate(text.replace("## $title\n- factual evidence", "## $title\n")))
        }
        assertNull(SummaryCheckpoint.validate(text + "\n## Current Work\nextra"))
        assertNull(SummaryCheckpoint.validate(text.replace("## Current Work", "## Next Step")))
        assertNull(SummaryCheckpoint.validate(text + "\n```\nunclosed"))
        assertNull(SummaryCheckpoint.validate("extra preface\n$text"))
    }
    @Test fun expandedDuplicateMessageIdentityDoesNotPassThroughArchiveCodec() {
        val message = Message(MessageId("repeated"), ConversationId("c"), RunId("r"), "assistant",
            listOf(MessageBlock.Text("text", "fact")), RunStatus.COMPLETED)
        val projected = SummaryEvidence.messages(listOf(message, message))
        assertEquals(2, projected.size)
        assertEquals(0, org.json.JSONObject(projected[0]).getInt("sequence"))
        assertEquals(1, org.json.JSONObject(projected[1]).getInt("sequence"))
        assertEquals(message, message.copy())
    }
    @Test fun hiddenReasoningAndInlineMediaAreNotSentAsSummaryEvidence() {
        val message = Message(MessageId("a"), ConversationId("c"), RunId("r"), "assistant",
            listOf(MessageBlock.Tool(ToolInvocation(ToolCallId("call"), "inspect",
                """{"image":"data:image/png;base64,SECRET","text":"visible"}"""), """{"result":"kept","thinking":"HIDDEN"}""")),
            RunStatus.COMPLETED, protocolItems = listOf("""{"type":"reasoning","encrypted_content":"ENCRYPTED"}"""))
        val text = SummaryEvidence.messages(listOf(message)).single()
        assertTrue(text.contains("visible") && text.contains("kept") && text.contains("call"))
        assertFalse(text.contains("SECRET") || text.contains("HIDDEN") || text.contains("ENCRYPTED"))
    }
}
