package app.daiyu.android.ui.screens.search

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
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
}
