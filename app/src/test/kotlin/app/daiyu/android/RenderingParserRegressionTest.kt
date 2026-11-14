package app.daiyu.android

import org.junit.Assert.*
import org.junit.Test

class RenderingParserRegressionTest {
    private fun t(s: String) = MarkdownInline.Text(s)
    private fun b(vararg nodes: MarkdownInline) = MarkdownInline.Emphasis(nodes.toList(), true, false)
    private fun e(vararg nodes: MarkdownInline) = MarkdownInline.Emphasis(nodes.toList(), false, true)
    @Test fun tripleOpenersCloseInnerThenOuterInBothDirections() {
        for (mark in listOf("*", "_")) {
            assertEquals(listOf(b(e(t("a")), t(" b"))), MarkdownParser.inline("${mark.repeat(3)}a$mark b${mark.repeat(2)}"))
            assertEquals(listOf(e(b(t("a")), t(" b"))), MarkdownParser.inline("${mark.repeat(3)}a${mark.repeat(2)} b$mark"))
            assertEquals(listOf(t(mark.repeat(2)), e(t("a"))), MarkdownParser.inline("${mark.repeat(3)}a$mark"))
        }
        assertEquals(listOf(b(t("b")), e(t("i"))), MarkdownParser.inline("**b***i*"))
    }
    @Test fun streamingTripleSplitOnlyVirtuallyClosesUnfinishedOuter() {
        assertEquals(listOf(b(e(t("a")), t(" b"))),
            (MarkdownParser.parseStreaming("***a* b").single() as MarkdownBlock.Paragraph).content)
        assertEquals(listOf(e(b(t("a")), t(" b"))),
            (MarkdownParser.parseStreaming("***a** b").single() as MarkdownBlock.Paragraph).content)
    }
    @Test fun unicodeLettersAndCombiningMarksDoNotOpenIntrawordUnderscores() {
        for (source in listOf("a\u0301_b_", "\uD801\uDC00_b_", "x_foo_bar")) {
            assertFalse(source, MarkdownParser.inline(source).any { it is MarkdownInline.Emphasis })
        }
        assertTrue(MarkdownParser.inline("😀_b_").any { it is MarkdownInline.Emphasis })
    }
    @Test fun escapedImagePrefixLeavesALiteralExclamationThenALink() {
        assertEquals(listOf(t("!"), MarkdownInline.Link(listOf(t("x")), "https://e.test")),
            MarkdownParser.inline("\\![x](https://e.test)"))
        assertEquals(listOf(t("!")), (MarkdownParser.parseStreaming("\\![x").single() as MarkdownBlock.Paragraph).content)
        assertFalse(MarkdownParser.inline("![x](https://e.test)").any { it is MarkdownInline.Link })
    }
    @Test fun tailTableCellOwnsItsProvisionalCodeAndEmphasis() {
        fun table(row: String) = MarkdownParser.parseStreaming("| a | b |\n| --- | --- |\n$row").single() as MarkdownBlock.Table
        assertEquals(listOf(MarkdownInline.Code("x|y")), table("| `x|y").rows.single().first())
        assertEquals(listOf(b(t("x"))), table("| **x").rows.single().first())
        assertTrue(table("| **x |").rows.single().first().none { it is MarkdownInline.Emphasis })
        assertTrue(table("| **x\n").rows.single().first().none { it is MarkdownInline.Emphasis })
    }
    @Test fun lazyContinuationTracksCurrentContainerBlockNotAnyPreviousFence() {
        for (prefix in listOf("> a\n> ===", ">     a")) {
            val blocks = MarkdownParser.parse("$prefix\nb")
            assertEquals(2, blocks.size)
            assertTrue(blocks.first() is MarkdownBlock.Quote)
            assertEquals(listOf(t("b")), (blocks.last() as MarkdownBlock.Paragraph).content)
        }
        val quote = MarkdownParser.parse("> ```\n> ```\n> a\nb").single() as MarkdownBlock.Quote
        assertEquals(listOf(t("a\nb")), (quote.blocks.last() as MarkdownBlock.Paragraph).content)
        val list = MarkdownParser.parse("- ```\n  ```\n  a\nb").single() as MarkdownBlock.Items
        assertEquals(listOf(t("a\nb")), (list.items.single().blocks.last() as MarkdownBlock.Paragraph).content)
    }
    @Test fun standaloneSectionsEscapeOnlyOpenRootFences() {
        for (body in listOf("````text\nx\n```", "~~~text\nx", "```\nx\n```not-close", "    ```", "> ```\n> x", "- ```\n  x")) {
            val projected = MarkdownParser.appendStandaloneSection(body, "[source](https://e.test)")
            assertTrue(projected.startsWith(body))
            assertTrue(body, (MarkdownParser.parse(projected).last() as MarkdownBlock.Paragraph).content.single() is MarkdownInline.Link)
        }
    }
}
