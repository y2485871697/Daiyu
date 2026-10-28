package app.daiyu.android

import org.junit.Assert.*
import org.junit.Test

class MarkdownTableWidthTest {
    @Test fun appendingUnrevealedRowsCannotChangeTheColumnWidthPolicy() {
        val first = MarkdownParser.parse("a | b\n--- | ---\nshort | short").single() as MarkdownBlock.Table
        val next = first.copy(rows = first.rows + listOf(listOf(listOf(MarkdownInline.Text("long ".repeat(80))), listOf(MarkdownInline.Text("tail")))))
        assertArrayEquals(MarkdownText.tableColumnWidthEms(first.header), MarkdownText.tableColumnWidthEms(next.header), 0f)
    }

    @Test fun samplingStopsBeforeLaterNodesAndCountsCodePoints() {
        var reads = 0
        val nodes = object : AbstractList<MarkdownInline>() {
            override val size = 2
            override fun get(index: Int): MarkdownInline {
                reads++
                check(index == 0)
                return MarkdownInline.Text("😀".repeat(100_000))
            }
        }
        assertEquals(32f, MarkdownText.tableCellWidthEm(nodes), 0f)
        assertEquals(1, reads)
    }

    @Test fun emptyNodesHaveABoundedVisitBudget() {
        var reads = 0
        val nodes = object : AbstractList<MarkdownInline>() {
            override val size = 1_000_000
            override fun get(index: Int): MarkdownInline { reads++; return MarkdownInline.Text("") }
        }
        assertEquals(0f, MarkdownText.tableCellWidthEm(nodes), 0f)
        assertEquals(128, reads)
    }

    @Test fun labelsStylesNewlinesAndCombiningMarksStayWidthHintsOnly() {
        fun width(text: String) = MarkdownText.tableCellWidthEm(listOf(MarkdownInline.Text(text)))
        assertTrue(width("汉".repeat(8)) > width("a".repeat(8)))
        assertEquals(width("e"), width("e\u0301"), 0.001f)
        assertEquals(width("汉汉"), width("汉汉\n汉"), 0.001f)
        val content = listOf(MarkdownInline.Emphasis(listOf(
            MarkdownInline.Link(listOf(MarkdownInline.Code("visible")), "https://example.test/" + "x".repeat(10_000)),
        ), bold = true, italic = false))
        assertEquals(width("visible"), MarkdownText.tableCellWidthEm(content), 0.001f)
    }
}
