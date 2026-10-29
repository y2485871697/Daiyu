package app.daiyu.android.ui.screens.chat.conversation

import org.junit.Assert.*
import org.junit.Test

class ToolOutputPreviewTest {
    @Test fun shortBodiesStayIntactIncludingReportedEmptyOutput() {
        listOf("", "a\nb\nc", "x".repeat(240)).forEach { text ->
            assertEquals(ToolOutputPreview(text, false), toolOutputPreview(text))
        }
    }
    @Test fun longBodiesShowHeadAndLogsShowTailWithoutChangingSource() {
        val text = (1..400).joinToString("\n") { "line $it" }
        assertEquals("line 1\nline 2\nline 3…", toolOutputPreview(text).text)
        assertEquals("…line 398\nline 399\nline 400", toolOutputPreview(text, tail = true).text)
        assertTrue(toolOutputPreview("x".repeat(241)).truncated)
        assertTrue(toolOutputPreview("1\n2\n3\n4").truncated)
        assertEquals(400, text.lines().size)
        assertEquals(3, toolOutputPreview(text).text.lines().size)
        assertEquals(3, toolOutputPreview(text, tail = true).text.lines().size)
        assertTrue(toolOutputPreview(text + "\n…内容已截断", tail = true).text.endsWith("…内容已截断"))
    }
    @Test fun largeNodePayloadsKeepOnlyBoundedHeadOrTailInTheirPreview() {
        val text = "head\n" + "x".repeat(1_000_000) + "\ntail"
        val head = toolOutputPreview(text)
        val tail = toolOutputPreview(text, tail = true)
        assertTrue(head.truncated); assertTrue(tail.truncated)
        assertTrue(head.text.startsWith("head\n")); assertTrue(tail.text.endsWith("\ntail"))
        assertTrue(head.text.length <= 241); assertTrue(tail.text.length <= 241)
    }
    @Test fun operationIconsFollowProtocolNotTitlesOrOutputText() {
        assertNotEquals(toolOperationGlyph("list_files", "{}"), toolOperationGlyph("read_text_file", "{}"))
        assertNotEquals(toolOperationGlyph("screen", "{\"operation\":\"observe\"}"), toolOperationGlyph("screen", "{\"operation\":\"screenshot\"}"))
        assertEquals("tap", toolOperationGlyph("screen_action", "{\"operation\":\"tap\"}"))
        assertEquals("longPress", toolOperationGlyph("screen_action", "{\"operation\":\"long_press\"}"))
        assertEquals("webCamera", toolOperationGlyph("browser_read", "{\"action\":\"screenshot\"}"))
        assertEquals("swap", toolOperationGlyph("direct_child_task", "{\"kind\":\"REPLACE\"}"))
        assertEquals("generic", toolOperationGlyph("browser_read", "{\"action\":\"future\"}"))
        assertEquals("generic", toolOperationGlyph("screen_action", "broken"))
        assertEquals("generic", toolOperationGlyph("vendor_future", "{}"))
    }
}
