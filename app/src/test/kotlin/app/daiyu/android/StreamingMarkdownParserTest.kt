package app.daiyu.android

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class StreamingMarkdownParserTest {
    private fun text(nodes: List<MarkdownInline>): String = nodes.joinToString("") { node -> when (node) {
        is MarkdownInline.Text -> node.text
        is MarkdownInline.Code -> node.text
        is MarkdownInline.Emphasis -> text(node.content)
        is MarkdownInline.Strike -> text(node.content)
        is MarkdownInline.Link -> text(node.content)
        MarkdownInline.Break -> "\n"
    } }
    private fun paragraph(source: String) = (MarkdownParser.parseStreaming(source).single() as MarkdownBlock.Paragraph).content

    @Test fun listMarkerPrefixesNeverPromoteThePreviousParagraph() {
        for (indent in 0..3) for (marker in listOf("-", "--", "---", "=", "===", "- ", "-\t")) {
            val source = "正文\n" + " ".repeat(indent) + marker
            val blocks = MarkdownParser.parseStreaming(source)
            assertEquals(source, listOf(MarkdownBlock.Paragraph(listOf(MarkdownInline.Text("正文")))), blocks)
        }
        val blocks = MarkdownParser.parseStreaming("正文\n- 条目")
        assertTrue(blocks.first() is MarkdownBlock.Paragraph)
        assertTrue(blocks.last() is MarkdownBlock.Items)
    }

    @Test fun confirmedSetextAndRulesRetainTheirOriginalMeaning() {
        for (marker in listOf("---", "===")) {
            val source = "标题\n$marker"
            assertTrue(MarkdownParser.parse(source).single() is MarkdownBlock.Heading)
            assertEquals(MarkdownParser.parse(source), MarkdownParser.parseStreaming("$source\n"))
        }
        assertEquals(listOf(MarkdownBlock.Rule), MarkdownParser.parseStreaming("---\n"))
        assertTrue(MarkdownParser.parseStreaming("正文\n\n---\n").last() is MarkdownBlock.Rule)
        assertTrue(MarkdownParser.parseStreaming("## 标题").single() is MarkdownBlock.Heading)
    }

    @Test fun nestedContainersOnlyDelayTheirUnfinishedTail() {
        val quote = MarkdownParser.parseStreaming("> 正文\n> -").single() as MarkdownBlock.Quote
        assertEquals(listOf(MarkdownBlock.Paragraph(listOf(MarkdownInline.Text("正文")))), quote.blocks)
        val list = MarkdownParser.parseStreaming("- 正文\n  -").single() as MarkdownBlock.Items
        assertTrue(list.items.single().blocks.single() is MarkdownBlock.Paragraph)
        val before = MarkdownParser.parseStreaming("> **未闭合\n\n正文").first() as MarkdownBlock.Quote
        assertEquals("**未闭合", text((before.blocks.single() as MarkdownBlock.Paragraph).content))
    }

    @Test fun strongPrefixesHideBothOpeningAndPartialClosingMarkers() {
        val source = "**粗体**"
        for (end in 1..source.length) {
            val nodes = paragraph(source.take(end))
            assertFalse("prefix=$end", text(nodes).contains('*'))
            if (end >= 3) assertTrue(nodes.single() is MarkdownInline.Emphasis)
        }
        assertTrue((paragraph("**粗体*").single() as MarkdownInline.Emphasis).bold)
        assertEquals("**未闭合", text((MarkdownParser.parse("**未闭合").single() as MarkdownBlock.Paragraph).content))
    }

    @Test fun nestedEmphasisStrikeEscapesAndIntrawordUnderscoresRemainContextual() {
        for (source in listOf("*斜体", "__粗体", "***双重", "**粗体 *斜体", "~~删除")) {
            assertFalse(source, text(paragraph(source)).any { it in "*_~" })
        }
        assertEquals("foo_bar_baz", text(paragraph("foo_bar_baz")))
        assertEquals("**字面**", text(paragraph("\\*\\*字面\\*\\*")))
        assertEquals("2 * 3", text(paragraph("2 * 3")))
        assertEquals("**先前未闭合", text((MarkdownParser.parseStreaming("**先前未闭合\n\n新段落").first() as MarkdownBlock.Paragraph).content))
    }

    @Test fun temporaryInlineCodeNeverParsesItsPayloadAsEmphasis() {
        val nodes = paragraph("说明 `code **字面")
        assertEquals("说明 code **字面", text(nodes))
        assertTrue(nodes.last() is MarkdownInline.Code)
        assertEquals("a`b", text(paragraph("``a`b")))
        assertEquals("code", text(paragraph("`code`")))
        assertEquals("`code", text((MarkdownParser.parse("`code").single() as MarkdownBlock.Paragraph).content))
    }

    @Test fun partialClosingFencesStayBufferedAndDoNotPretendToBeClosed() {
        for (marker in listOf("```", "~~~")) {
            for (partial in 1..2) {
                val source = "$marker\n**literal**\n" + marker.take(partial)
                val code = MarkdownParser.parseStreaming(source).single() as MarkdownBlock.Code
                assertEquals("**literal**\n", code.text)
                assertFalse(code.closed)
                assertTrue((MarkdownParser.parse(source).single() as MarkdownBlock.Code).text.endsWith(marker.take(partial)))
            }
            val code = MarkdownParser.parseStreaming("$marker\n**literal**\n$marker").single() as MarkdownBlock.Code
            assertEquals("**literal**\n", code.text)
            assertTrue(code.closed)
        }
    }

    @Test fun aShortFenceBecomesLiteralWhenItsLineIsConfirmed() {
        val code = MarkdownParser.parseStreaming("````\ntext\n```\n").single() as MarkdownBlock.Code
        assertEquals("text\n```\n", code.text)
        assertFalse(code.closed)
        val indented = MarkdownParser.parseStreaming("    **literal**").single() as MarkdownBlock.Code
        assertEquals("**literal**", indented.text)
    }

    @Test fun finalTableCellUsesTheSamePendingInlineSemantics() {
        val table = MarkdownParser.parseStreaming("| a | b |\n| --- | --- |\n| x | **粗体").single() as MarkdownBlock.Table
        assertEquals("粗体", text(table.rows.single().last()))
        assertTrue(table.rows.single().last().single() is MarkdownInline.Emphasis)
    }

    @Test fun finalParserAndStoredSourceAreNeverChangedByProvisionalParses() {
        for (source in listOf("正文\n-", "**未闭合", "`code", "```\n**字面**\n``")) {
            val exact = MarkdownParser.parse(source)
            MarkdownParser.parseStreaming(source)
            assertEquals(source, exact, MarkdownParser.parse(source))
        }
    }

    @Test fun containerLineEndingsConfirmSetextAndShortFencePayloads() {
        for (source in listOf("> 标题\n> ---\n", "- 标题\n  ---\n", "> ```\n> text\n> ``\n")) {
            assertEquals(source, MarkdownParser.parse(source), MarkdownParser.parseStreaming(source))
        }
        for (source in listOf("**旧段\n# 新标题", "- **旧项\n- 新项")) {
            assertEquals(source, MarkdownParser.parse(source), MarkdownParser.parseStreaming(source))
        }
    }

    @Test fun pendingInlineCodeTakesPriorityOverTheLinkWorkBudget() {
        val payload = "[a](".repeat(100)
        val nodes = paragraph("`$payload")
        assertEquals(listOf(MarkdownInline.Code(payload)), nodes)
    }

    @Test fun trailingIntrawordUnderscoresAreLiteralAtEveryPrefix() {
        for (source in listOf("foo_", "foo__", "foo_bar", "foo_bar_", "foo_bar_baz")) {
            assertEquals(source, text(paragraph(source)))
        }
    }

    @Test fun tableHeaderAndDelimiterStayBufferedUntilTheirLineIsConfirmed() {
        for (source in listOf("| a | b |", "| a | b |\n", "| a | b |\n| --- | ---", "| a | b |\n| --- | --- |")) {
            assertTrue(source, MarkdownParser.parseStreaming(source).isEmpty())
        }
        assertTrue(MarkdownParser.parseStreaming("| a | b |\n| --- | --- |\n").single() is MarkdownBlock.Table)
        assertTrue(MarkdownParser.parseStreaming("| a | b |\nnot a table").single() is MarkdownBlock.Paragraph)
        assertTrue(MarkdownParser.parseStreaming("| a | b |\n\n").single() is MarkdownBlock.Paragraph)
    }

    @Test fun pendingLinksNeverPublishTheirRawAddressAndDoNotConsumeCodePayload() {
        for (tail in listOf("[", "[site", "[site]", "[site](", "[site](https://example.test", "[site](https://a/(b)")) {
            assertEquals(tail, "prefix ", text(paragraph("prefix $tail")))
        }
        val complete = "[site](https://example.test)"
        assertEquals("site", text(paragraph(complete)))
        assertEquals("[x](javascript:alert(1))", text(paragraph("[x](javascript:alert(1))")))
        assertEquals("[raw](", text(paragraph("`[raw](")))
        assertEquals("[literal]", text(paragraph("\\[literal\\]")))
        assertEquals("[literal]", text(paragraph("[literal] text")).substringBefore(" text"))
    }

    @Test fun streamingWorkHonorsCancellation() {
        var checks = 0
        try {
            MarkdownParser.parseStreaming("**" + "字".repeat(50_000)) {
                if (++checks == 3) throw CancellationException("cancel streaming parse")
            }
            fail("expected cancellation")
        } catch (_: CancellationException) { assertEquals(3, checks) }
    }
    @Test fun pendingLinkBufferUsesTheSameTitleAndAngleRulesAsFinalParsing() {
        for (source in listOf("[x](https://e.test \"(\")", "[x](<https://e.test/(>)", "[x](https://e.test/`)", "[x](https://e.test \"`\")")) {
            assertEquals(source, MarkdownParser.parse(source), MarkdownParser.parseStreaming(source))
        }
        for (tail in listOf("[x](https://e.test \"a) b", "[x](<https://e.test/(")) {
            assertEquals(tail, "prefix ", text(paragraph("prefix $tail")))
        }
        val html = "prefix <span title=\"[\"> tail"
        assertEquals(html, text(paragraph(html)))
    }

    @Test fun unfinishedInlineCodePipesDoNotStartATableCandidate() {
        assertEquals(listOf(MarkdownInline.Code("a|b")), paragraph("`a|b"))
        assertEquals(listOf(MarkdownInline.Code("a|b")), paragraph("``a|b"))
    }

}
