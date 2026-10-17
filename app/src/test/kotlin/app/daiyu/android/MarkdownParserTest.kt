package app.daiyu.android

import org.junit.Assert.*
import org.junit.Test

/** Actual parser contracts, not snapshots of a mock renderer. No network or new dependencies. */
class MarkdownParserTest {
    private fun text(nodes: List<MarkdownInline>): String = nodes.joinToString("") { node -> when (node) {
        is MarkdownInline.Text -> node.text
        is MarkdownInline.Code -> node.text
        is MarkdownInline.Emphasis -> text(node.content)
        is MarkdownInline.Strike -> text(node.content)
        is MarkdownInline.Link -> text(node.content)
        MarkdownInline.Break -> "\n"
    } }

    @Test fun depthFallbackKeepsClosedAndUnclosedFencesEntirelyLiteral() {
        for (closed in listOf(true, false)) {
            val literal = "~~~\n[x](https://code.test)" + if (closed) "\n~~~" else ""
            val source = literal.lines().joinToString("\n") { "> ".repeat(24) + it }
            var blocks = MarkdownParser.parse(source)
            repeat(24) { blocks = (blocks.single() as MarkdownBlock.Quote).blocks }
            assertEquals(listOf(MarkdownBlock.Paragraph(listOf(MarkdownInline.Text(literal)))), blocks)
        }
    }

    @Test fun atxSetextRulesAndLiteralHtml() {
        val headings = (1..6).joinToString("\n") { "${"#".repeat(it)} title $it ###" }
        val result = MarkdownParser.parse("$headings\n\nSetext\n======\n\n---\n\n<div onclick=\"bad()\">raw</div>")
        assertEquals((1..6).toList() + 1, result.filterIsInstance<MarkdownBlock.Heading>().map { it.level })
        assertEquals("title 1", text((result.first() as MarkdownBlock.Heading).content))
        assertTrue(result.any { it == MarkdownBlock.Rule })
        assertEquals("<div onclick=\"bad()\">raw</div>", text((result.last() as MarkdownBlock.Paragraph).content))
        assertTrue(MarkdownParser.parse("####### not a heading").single() is MarkdownBlock.Paragraph)
    }

    @Test fun nestedQuotesOrderedListsTasksAndContinuation() {
        val blocks = MarkdownParser.parse(
            "> ## Quoted\n>\n> 3. **first**\n>    - [x] nested\n>    - [ ] pending\n> 4. second\n>    continued\n\nAfter",
        )
        val quote = blocks.first() as MarkdownBlock.Quote
        assertTrue(quote.blocks.first() is MarkdownBlock.Heading)
        val list = quote.blocks[1] as MarkdownBlock.Items
        assertTrue(list.ordered)
        assertEquals(3, list.start)
        assertEquals(2, list.items.size)
        val nested = list.items[0].blocks[1] as MarkdownBlock.Items
        assertEquals(listOf(true, false), nested.items.map { it.checked })
        assertEquals("second\ncontinued", text((list.items[1].blocks.single() as MarkdownBlock.Paragraph).content))
        assertEquals("After", text((blocks.last() as MarkdownBlock.Paragraph).content))
    }

    @Test fun lazyQuoteParagraphsDoNotExtendUnclosedContainerCode() {
        val lazy = MarkdownParser.parse("> quoted\ncontinued").single() as MarkdownBlock.Quote
        assertEquals("quoted\ncontinued", text((lazy.blocks.single() as MarkdownBlock.Paragraph).content))
        for (source in listOf("> ```\n> code\n[real](https://example.test)",
            "- ```\n  code\n[real](https://example.test)")) {
            val blocks = MarkdownParser.parse(source)
            assertEquals(2, blocks.size)
            assertTrue((blocks.last() as MarkdownBlock.Paragraph).content.single() is MarkdownInline.Link)
        }
    }

    @Test fun tablesAlignNormalizeCellsAndRespectEscapesAndCodePipes() {
        val result = MarkdownParser.parse(
            "| left | center | right |\n| :--- | :---: | ---: |\n| a\\|b | `x|y` | **bold** |\n| short |\n| one | two | three | ignored |",
        ).single() as MarkdownBlock.Table
        assertEquals(listOf(MarkdownBlock.Alignment.LEFT, MarkdownBlock.Alignment.CENTER, MarkdownBlock.Alignment.RIGHT), result.alignment)
        assertEquals(listOf("a|b", "x|y", "bold"), result.rows[0].map(::text))
        assertTrue(result.rows[0][1].single() is MarkdownInline.Code)
        assertEquals(listOf("short", "", ""), result.rows[1].map(::text))
        assertEquals(3, result.rows[2].size)
        assertTrue(MarkdownParser.parse("a | b\n--- | not-a-delimiter").single() is MarkdownBlock.Paragraph)
    }

    @Test fun fencesAreLineDelimitedMayBeLongerOrUnclosedAndNeverContainTablesOrLinks() {
        val literal = "| a | b |\n| --- | --- |\n| [x](https://code.test) | **literal** |\n"
        val result = MarkdownParser.parse("````kotlin\n$literal```\nstill code\n````\n\n[real](https://example.test)")
        assertEquals(literal + "```\nstill code\n", (result[0] as MarkdownBlock.Code).text)
        assertEquals("kotlin", (result[0] as MarkdownBlock.Code).language)
        assertTrue((result[1] as MarkdownBlock.Paragraph).content.single() is MarkdownInline.Link)
        val unclosed = MarkdownParser.parse("~~~text\n$literal").single() as MarkdownBlock.Code
        assertEquals(literal, unclosed.text)
        val indented = MarkdownParser.parse("    [x](https://code.test)\n    | a | b |").single() as MarkdownBlock.Code
        assertEquals("[x](https://code.test)\n| a | b |", indented.text)
    }

    @Test fun inlineCompositionAndCodeRunsPreserveLiteralBoundaries() {
        val nodes = MarkdownParser.inline("***both*** **bold *italic*** ~~gone~~ _em_ foo_bar_baz \\*escaped\\* `` `literal` `` &amp; &#x1f600;")
        assertEquals("both bold italic gone em foo_bar_baz *escaped* `literal` & 😀", text(nodes))
        assertTrue(nodes.any { it is MarkdownInline.Strike })
        assertTrue(nodes.any { it is MarkdownInline.Code && it.text == "`literal`" })
        val both = nodes.first() as MarkdownInline.Emphasis
        assertTrue(both.italic)
        assertTrue((both.content.single() as MarkdownInline.Emphasis).bold)
        val mixed = MarkdownParser.inline("*italic **bold** tail* **strong *em*** **b***i*")
        assertEquals("italic bold tail strong em bi", text(mixed))
        val italic = mixed.first() as MarkdownInline.Emphasis
        assertTrue(italic.italic)
        assertTrue(italic.content.any { it is MarkdownInline.Emphasis && it.bold })
        assertEquals("**unclosed bold", text(MarkdownParser.inline("**unclosed **bold**")))
        val link = MarkdownParser.inline("[**bold** `a]b`](https://example.test/a_(b) \"title\")").single() as MarkdownInline.Link
        assertEquals("bold a]b", text(link.content))
        assertEquals("https://example.test/a_(b)", link.destination)
        assertTrue(link.content.any { it is MarkdownInline.Code })
        assertEquals("line\nnext\nlast", text(MarkdownParser.inline("line  \nnext\\\nlast")))
        assertEquals("ok then `unclosed", text(MarkdownParser.inline("`ok` then `unclosed")))
        assertEquals("`code", text(MarkdownParser.inline("\\``code`")))
    }

    @Test fun unsafeDestinationsStayLiteralAndBareUrlsAreNotImplicitCapabilities() {
        val unsafe = "[**no**](javascript:alert(1)) [x](https://user@example.test) <b>raw</b>"
        assertEquals(unsafe, text(MarkdownParser.inline(unsafe)))
        assertTrue(MarkdownParser.inline("https://example.test").single() is MarkdownInline.Text)
        assertTrue(MarkdownParser.inline("<https://example.test>").single() is MarkdownInline.Link)
        val literalCode = "| a | b | [x](https://code.test) **not bold**"
        assertEquals(listOf(MarkdownInline.Code(literalCode)), MarkdownParser.inline("`$literalCode`"))
    }

    @Test fun balancedMalformedDestinationsHaveOneLinearBudgetIncludingUnclosedTitles() {
        for (n in listOf(32, 512, 4_096)) {
            for (tail in listOf(
                " q" + ")".repeat(n),
                " ".repeat(n) + "q" + ")".repeat(n),
                " \"unterminated " + ")".repeat(n),
                " 'unterminated " + ")".repeat(n),
                " \"closed\" " + "\t".repeat(n) + "q" + ")".repeat(n),
            )) {
                val source = "[x](".repeat(n) + tail
                val work = MarkdownParser.LinkWork(source.length)
                assertEquals(listOf(MarkdownInline.Text(source)), MarkdownParser.inline(source, work))
                assertTrue("must exercise budget fallback at n=$n", work.exhausted)
                assertTrue(work.operations <= source.length.toLong() * 8 + 64)
            }
        }
        // Exhaustion retains the whole original, including UTF-16, entities and delimiters
        // which would otherwise have been decoded/styled before the malformed suffix.
        val source = "😀 &amp; **literal** \\* " + "[x](".repeat(512) + " q" + ")".repeat(512)
        assertEquals(listOf(MarkdownInline.Text(source)), MarkdownParser.inline(source))
    }

    @Test fun destinationBudgetKeepsLongValidUrlsWhitespaceAndTitlesAndLiteralFailures() {
        val url = "https://example.test/" + "a".repeat(8_192) + "(b)"
        val source = "[😀](<$url>" + " ".repeat(8_192) + "\"" + "title".repeat(4_096) +
            "\"" + "\t".repeat(8_192) + ")"
        val work = MarkdownParser.LinkWork(source.length)
        val link = MarkdownParser.inline(source, work).single() as MarkdownInline.Link
        assertEquals(url, link.destination)
        assertEquals("😀", text(link.content))
        assertTrue(!work.exhausted)
        assertTrue(work.operations <= work.limit)

        for (quote in listOf('"', '\'')) {
            val malformed = "[x](https://example.test $quote" + "title ".repeat(8_192) + ")"
            val failedWork = MarkdownParser.LinkWork(malformed.length)
            assertEquals(malformed, text(MarkdownParser.inline(malformed, failedWork)))
            assertTrue(!failedWork.exhausted) // one long failure is still ordinary linear work
            assertTrue(failedWork.operations >= malformed.length - 4L)
            assertTrue(failedWork.operations <= failedWork.limit)
        }
        val unsafe = "[😀](https://user@example.test/path \"title\")"
        assertEquals(unsafe, text(MarkdownParser.inline(unsafe)))
    }

    @Test fun wideSparseTableFallsBackBeforePaddingAndPreservesEveryExplicitRowAndColumn() {
        val width = 4_096
        val header = (0 until width).joinToString(" | ") { "h$it" }
        val delimiter = List(width) { "---" }.joinToString(" | ")
        // Includes an explicit out-of-header tail column: budget fallback must not apply
        // ordinary GFM truncation, and must not lose later rows after detecting overflow.
        val tail = (0..width).joinToString(" | ") { "tail-$it" }
        val source = "$header\n$delimiter\n" + "x |\n".repeat(4_096) + tail
        val blocks = MarkdownParser.parse("$source\n\nafter")
        assertEquals(2, blocks.size)
        assertEquals(MarkdownBlock.Code(source), blocks.first())
        assertEquals("after", text((blocks.last() as MarkdownBlock.Paragraph).content))
    }

    @Test fun tableProjectionAboveIntRangeStillFallsBackWithoutMultiplicationOverflow() {
        val width = 65_536
        val rows = 32_768
        assertTrue(width.toLong() * (rows + 1L) > Int.MAX_VALUE)
        val header = List(width) { "h" }.joinToString(" | ")
        val delimiter = List(width) { "---" }.joinToString(" | ")
        val source = "$header\n$delimiter\n" + "x |\n".repeat(rows) + "last | explicit-tail |"
        assertEquals(MarkdownBlock.Code(source), MarkdownParser.parse(source).single())
    }

    @Test fun tableTotalCellsAndImplicitPaddingHaveIndependentInclusiveLimits() {
        fun table(width: Int, rows: Int, sparse: Boolean): String {
            val header = List(width) { "h" }.joinToString(" | ")
            val delimiter = List(width) { "---" }.joinToString(" | ")
            val row = if (sparse) "x |" else List(width) { "x" }.joinToString(" | ")
            return "$header\n$delimiter\n" + List(rows) { row }.joinToString("\n")
        }
        val fullRows = (MarkdownParser.MAX_TABLE_CELLS / 128).toInt() - 1
        val full = MarkdownParser.parse(table(128, fullRows, false)).single() as MarkdownBlock.Table
        assertEquals(MarkdownParser.MAX_TABLE_CELLS, full.header.size.toLong() + full.rows.sumOf { it.size.toLong() })
        val denseOverflow = table(128, fullRows + 1, false)
        assertEquals(MarkdownBlock.Code(denseOverflow), MarkdownParser.parse(denseOverflow).single())

        val sparseRows = (MarkdownParser.MAX_TABLE_PADDING / 2).toInt()
        val sparse = MarkdownParser.parse(table(3, sparseRows, true)).single() as MarkdownBlock.Table
        assertEquals(sparseRows, sparse.rows.size)
        assertEquals(listOf("x", "", ""), sparse.rows.last().map(::text))
        val paddingOverflow = table(3, sparseRows + 1, true)
        assertEquals(MarkdownBlock.Code(paddingOverflow), MarkdownParser.parse(paddingOverflow).single())
    }

    @Test fun longAndMalformedStreamingInputRemainsReadableWithoutRecursiveBlowup() {
        val long = "😀 long ".repeat(10_000)
        assertEquals(long, text(MarkdownParser.inline(long)))
        for (source in listOf("[".repeat(30_000), "*".repeat(30_000), "`".repeat(30_000),
            "<".repeat(30_000), "&".repeat(30_000), "[x](".repeat(5_000), "**unclosed\n`unclosed")) {
            assertEquals(source, text(MarkdownParser.inline(source)))
        }
        val distinctUnclosedCodeRuns = (1..300).joinToString("x") { "`".repeat(it) }
        assertEquals(distinctUnclosedCodeRuns, text(MarkdownParser.inline(distinctUnclosedCodeRuns)))
        val ambiguous = "**open ".repeat(5_000) + "end**"
        assertEquals(ambiguous.replace("*", ""), text(MarkdownParser.inline(ambiguous)).replace("*", ""))
        val deep = "> ".repeat(100) + "nested"
        assertTrue(MarkdownParser.parse(deep).isNotEmpty())
        val table = MarkdownParser.parse("a | b\n--- | ---\n${"long".repeat(10_000)} | last").single() as MarkdownBlock.Table
        assertEquals(40_000, text(table.rows.single().first()).length)
    }
}
