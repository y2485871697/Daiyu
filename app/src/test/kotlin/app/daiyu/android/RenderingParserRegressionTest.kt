package app.daiyu.android

import org.junit.Assert.*
import org.junit.Test

class RenderingParserRegressionTest {
    private fun t(s: String) = MarkdownInline.Text(s)
    private fun b(vararg nodes: MarkdownInline) = MarkdownInline.Emphasis(nodes.toList(), true, false)
    private fun e(vararg nodes: MarkdownInline) = MarkdownInline.Emphasis(nodes.toList(), false, true)
    private fun plain(nodes: List<MarkdownInline>): String = nodes.joinToString("") { when (it) {
        is MarkdownInline.Text -> it.text
        is MarkdownInline.Code -> it.text
        is MarkdownInline.Emphasis -> plain(it.content)
        is MarkdownInline.Strike -> plain(it.content)
        is MarkdownInline.Link -> plain(it.content)
        MarkdownInline.Break -> "\n"
    } }
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
    @Test fun cjkPunctuationDoesNotExposePairedEmphasisMarkers() {
        val examples = listOf(
            "**没有可靠证据证明是真的，但也不宜断言都是骗局。**从公开报道能确认的是",
            "**有人报告看见不明物体，不等于看见外星飞船。**央视资料记载",
            "**“通过测谎”不能证明故事真实。**确有媒体报道",
            "**所谓现场异常，也不是外星人的专属证据。**例如",
            "中文**“重点”**继续",
            "中文**（重点）**继续",
            "日本語**「重点。」**続き",
            "한국어**“중요。”**계속",
            "𠀀**“重点。”**𠀁",
        )
        for (source in examples) {
            val expected = source.replace("**", "")
            val completed = (MarkdownParser.parse(source).single() as MarkdownBlock.Paragraph).content
            val streaming = (MarkdownParser.parseStreaming(source).single() as MarkdownBlock.Paragraph).content
            assertEquals(source, expected, plain(completed))
            assertEquals(source, expected, plain(streaming))
            assertTrue(source, completed.any { it is MarkdownInline.Emphasis && it.bold })
        }
    }

    @Test fun cjkCompatibilityPreservesExplicitLiteralAndNonCjkBoundaries() {
        val escaped = "\\*\\*重点。\\*\\*后文"
        assertEquals("**重点。**后文", plain(MarkdownParser.inline(escaped)))
        assertFalse(MarkdownParser.inline(escaped).any { it is MarkdownInline.Emphasis })
        assertEquals(listOf(MarkdownInline.Code("**重点。**后文")),
            MarkdownParser.inline("`**重点。**后文`"))
        for (source in listOf("foo**(bar)**baz", "中文__“重点”__继续", "** 重点 **", "2 * 3 = 6")) {
            assertFalse(source, MarkdownParser.inline(source).any { it is MarkdownInline.Emphasis })
            assertEquals(source, source, plain(MarkdownParser.inline(source)))
        }
    }

    @Test fun boundedLongRunsAndNestedPartialClosersDoNotLeakMatchedStars() {
        assertEquals(listOf(b(b(t("a")))), MarkdownParser.inline("****a****"))
        assertEquals(listOf(e(t("a "), e(e(t("b")), t(" c")))), MarkdownParser.inline("*a **b* c**"))
        for (source in listOf("****a****", "*a **b* c**", "*****a*****")) {
            assertEquals(source, plain(MarkdownParser.inline(source)),
                plain((MarkdownParser.parseStreaming(source).single() as MarkdownBlock.Paragraph).content))
        }
        assertEquals("**a b c", plain(MarkdownParser.inline("****a* b* c")))
        for (count in 2..5) for (closing in 0 until count) {
            val source = "*".repeat(count) + "a" + "*".repeat(closing)
            val full = MarkdownParser.inline("*".repeat(count) + "a" + "*".repeat(count))
            assertEquals(source, full, (MarkdownParser.parseStreaming(source).single() as MarkdownBlock.Paragraph).content)
        }
        val atBudget = "*".repeat(48) + "a" + "*".repeat(48)
        assertEquals("a", plain(MarkdownParser.inline(atBudget)))
        for (count in listOf(49, 65)) {
            val overBudget = "*".repeat(count) + "a" + "*".repeat(count)
            assertEquals(overBudget, plain(MarkdownParser.inline(overBudget)))
        }
    }

}
