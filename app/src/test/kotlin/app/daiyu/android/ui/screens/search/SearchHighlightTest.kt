package app.daiyu.android.ui.screens.search

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import app.daiyu.android.ui.retainedSearchSnippet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchHighlightTest {
    private val style = SpanStyle(fontWeight = FontWeight.Bold)

    @Test fun highlightsEveryChineseOccurrenceOnlyInBody() {
        val result = highlightSearchBody("用户", "用好工具，再用一次", "用", style)
        assertEquals("用户 · 用好工具，再用一次", result.text)
        assertEquals(listOf(5 to 6, 11 to 12), result.spanStyles.map { it.start to it.end })
        assertTrue(result.spanStyles.all { it.item == style })
    }

    @Test fun usesLiteralCaseInsensitiveMatchingAndTrimsQuery() {
        val result = highlightSearchBody("助手", "a.* A.* aX", "  A.*  ", style)
        assertEquals("助手 · a.* A.* aX", result.text)
        assertEquals(listOf(5 to 8, 9 to 12), result.spanStyles.map { it.start to it.end })
    }

    @Test fun preservesUtf16OffsetsAroundEmoji() {
        val result = highlightSearchBody("助手", "😀用😀用", "用", style)
        assertEquals(listOf(7 to 8, 10 to 11), result.spanStyles.map { it.start to it.end })
    }

    @Test fun blankOrAbsentQueryKeepsTextWithoutSpans() {
        for (query in listOf("", "  ", "absent", "longer than the body")) {
            val result = highlightSearchBody("用户", "正文", query, style)
            assertEquals("用户 · 正文", result.text)
            assertTrue(result.spanStyles.isEmpty())
        }
    }

    @Test fun keepsMatchContextInsteadOfLongMessageOpening() {
        val opening = "无关开头".repeat(100)
        val before = "附近正文".repeat(8)
        val after = "后续内容".repeat(30)
        val text = opening + before + "关键词" + after
        val snippet = retainedSearchSnippet(text, opening.length + before.length, 3)
        assertEquals(before.takeLast(12) + "关键词" + after.take(80), snippet)
        assertTrue(!snippet.contains("无关开头"))
        val highlighted = highlightSearchBody("助手", snippet, "关键词", style)
        assertEquals(listOf(17 to 20), highlighted.spanStyles.map { it.start to it.end })
    }

    @Test fun flattensLineBreaksWithoutMovingLiteralMatchSpans() {
        val body = "前\r\n\n\t用好\n工具，再用一次"
        val result = highlightSearchBody("助手", body, "用", style)
        assertEquals("助手 · 前    用好 工具，再用一次", result.text)
        assertEquals(listOf(10 to 11, 17 to 18), result.spanStyles.map { it.start to it.end })
        val phrase = highlightSearchBody("助手", "前用\n工具后", "用\n工具", style)
        assertEquals("助手 · 前用 工具后", phrase.text)
        assertEquals(listOf(6 to 10), phrase.spanStyles.map { it.start to it.end })
    }

    @Test fun contextWindowsNeverSplitSurrogatePairsAndKeepWholeLongQuery() {
        val before = "😀".repeat(40)
        val after = "😀".repeat(100)
        val query = "[A.*]".repeat(30)
        val text = before + query + after
        val snippet = retainedSearchSnippet(text, before.length, query.length)
        assertEquals("😀".repeat(12) + query + "😀".repeat(80), snippet)
        assertTrue(!snippet.first().isLowSurrogate())
        assertTrue(!snippet.last().isHighSurrogate())
        assertEquals("关键词", retainedSearchSnippet("关键词", 0, 3))
    }
}
