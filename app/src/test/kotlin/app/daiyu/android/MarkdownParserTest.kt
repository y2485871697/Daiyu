package app.daiyu.android

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

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

    @Test fun thematicBreaksHaveTheSamePriorityAfterAListAsOutsideIt() {
        for (bullet in listOf("-", "*")) {
            val blocks = MarkdownParser.parse("$bullet a\n$bullet $bullet $bullet")
            assertEquals(2, blocks.size)
            assertTrue(blocks.first() is MarkdownBlock.Items)
            assertEquals(MarkdownBlock.Rule, blocks.last())
        }
        val nested = MarkdownParser.parse("- a\n  - - -").single() as MarkdownBlock.Items
        assertEquals(MarkdownBlock.Rule, nested.items.single().blocks.last())
    }

    @Test fun tabIndentedThematicBreaksAreAlwaysCode() {
        for (indent in listOf("\t", " \t", "  \t", "   \t")) for (marker in listOf("---", "***", "___")) {
            assertTrue("$indent$marker", MarkdownParser.parse("$indent$marker").single() is MarkdownBlock.Code)
        }
        val quoted = MarkdownParser.parse("> \t---").single() as MarkdownBlock.Quote
        assertTrue(quoted.blocks.single() is MarkdownBlock.Code)
        for (indent in 0..3) assertEquals(listOf(MarkdownBlock.Rule), MarkdownParser.parse(" ".repeat(indent) + "---"))
    }

    @Test fun lazyListContinuationOnlyExtendsParagraphs() {
        for (source in listOf("- # title\noutside", "- title\n  ---\noutside")) {
            val blocks = MarkdownParser.parse(source)
            assertEquals(source, 2, blocks.size)
            assertTrue(blocks.first() is MarkdownBlock.Items)
            assertEquals("outside", text((blocks.last() as MarkdownBlock.Paragraph).content))
        }
        val list = MarkdownParser.parse("- first\ncontinued").single() as MarkdownBlock.Items
        assertEquals("first\ncontinued", text((list.items.single().blocks.single() as MarkdownBlock.Paragraph).content))
    }

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

    @Test fun codeClosureIsExplicitAndDoesNotChangeBodyBytes() {
        val unclosed = MarkdownParser.parse("```sh\n  echo 代鱼\n").single() as MarkdownBlock.Code
        assertFalse(unclosed.closed)
        assertEquals("  echo 代鱼\n", unclosed.text)
        val closed = MarkdownParser.parse("```sh\n  echo 代鱼\n```\nAfter").first() as MarkdownBlock.Code
        assertTrue(closed.closed)
        assertEquals(unclosed.text, closed.text)
        assertFalse((MarkdownParser.parse("````sh\ncode\n```").single() as MarkdownBlock.Code).closed)
        assertTrue((MarkdownParser.parse("~~~sh\ncode\n~~~~").single() as MarkdownBlock.Code).closed)
        assertFalse((MarkdownParser.parse("~~~sh\ncode\n~~~ not closing").single() as MarkdownBlock.Code).closed)
        assertTrue((MarkdownParser.parse("    indented").single() as MarkdownBlock.Code).closed)
        val quote = MarkdownParser.parse("> ```sh\n> code\n> ```").single() as MarkdownBlock.Quote
        assertTrue((quote.blocks.single() as MarkdownBlock.Code).closed)
        val list = MarkdownParser.parse("- ```sh\n  code\n  ```").single() as MarkdownBlock.Items
        assertTrue((list.items.single().blocks.single() as MarkdownBlock.Code).closed)
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

    @Test fun atxClosingHashesKeepExistingWhitespaceSemantics() {
        for ((source, expected) in listOf(
            "# title ###" to "title", "# title \t### \t" to "title",
            "# title### \t" to "title### \t", "# title \t" to "title \t",
            "# ###" to "###", "# ### \t" to "### \t",
            "# title ### x \t" to "title ### x \t",
        )) assertEquals(source, expected, text((MarkdownParser.parse(source).single() as MarkdownBlock.Heading).content))
    }

    @Test(timeout = 10_000) fun longAtxSuffixesStayReadableWithoutBacktracking() {
        val spaces = " \t".repeat(50_000)
        val plain = "heading$spaces"
        assertEquals(plain, text((MarkdownParser.parse("# $plain").single() as MarkdownBlock.Heading).content))
        assertEquals("heading", text((MarkdownParser.parse("# heading$spaces${"#".repeat(50_000)}$spaces")
            .single() as MarkdownBlock.Heading).content))
    }

    private fun assertCooperativeCancellation(action: (() -> Unit) -> Unit) {
        val expected = CancellationException("parser-test")
        var checks = 0
        try {
            action { if (++checks == 3) throw expected }
            fail("Expected cancellation inside a parser traversal")
        } catch (actual: CancellationException) { assertSame(expected, actual) }
        assertEquals(3, checks)
    }

    @Test fun cancellationIsCooperativeInsideInlineAndBlockWork() {
        assertCooperativeCancellation { MarkdownParser.inline("x".repeat(30_000), it) }
        assertCooperativeCancellation { MarkdownParser.parse("_".repeat(30_000), it) }
        assertCooperativeCancellation { MarkdownParser.parse("# heading" + " \t".repeat(30_000), it) }
        assertCooperativeCancellation { MarkdownParser.parse("```\n" + "body\n".repeat(8_000) + "```", it) }
        assertCooperativeCancellation { MarkdownParser.parse("a | b\n--- | ---\n" + "x | y\n".repeat(6_000), it) }
    }

    @Test fun cancellationChecksAreLowFrequencyAndDoNotChangeSyntax() {
        val source = "x".repeat(100_000)
        var checks = 0
        assertEquals(MarkdownParser.inline(source), MarkdownParser.inline(source) { checks++ })
        assertTrue(checks >= 3)
        assertTrue(checks < 1_000)
        val markdown = "> ## title ###\n>\n> - **bold** [x](https://example.test)"
        assertEquals(MarkdownParser.parse(markdown), MarkdownParser.parse(markdown) {})
    }

    @Test fun quotedLinkTitlesDoNotParticipateInParenthesisPreflight() {
        for (title in listOf("\"unmatched (\"", "\"unmatched )\"", "'a \"quote\" and ('",
            "\"tick ` and (\"", "\"escaped \\\"quote\\\" and (\"", "'escaped \\'quote\\' and )'")) {
            val source = "[x](https://example.test $title)"
            val work = MarkdownParser.LinkWork(source.length)
            val link = MarkdownParser.inline(source, work).single() as MarkdownInline.Link
            assertEquals(title, "x", text(link.content))
            assertEquals("https://example.test", link.destination)
            assertFalse(work.exhausted)
            assertTrue(work.operations <= work.limit)
        }
    }

    @Test fun backticksInSeparateTitlesDoNotHideLabelsOrBecomeCode() {
        val source = "[a](https://a.test \"first `\") [b](https://b.test 'second `') `[hidden](https://hidden.test)`"
        val nodes = MarkdownParser.inline(source)
        assertEquals(listOf("https://a.test", "https://b.test"), nodes.filterIsInstance<MarkdownInline.Link>().map { it.destination })
        assertEquals(listOf(MarkdownInline.Code("[hidden](https://hidden.test)")), nodes.filterIsInstance<MarkdownInline.Code>())
        for (destination in listOf("javascript:alert(1)", "https://user@example.test")) {
            val rejected = "[**no**]($destination \"title ( ` \\\"quoted\\\"\")"
            assertEquals(listOf(MarkdownInline.Text(rejected)), MarkdownParser.inline(rejected))
        }
        assertTrue(MarkdownParser.inline("[x](https://example.test \"escaped \\\" only)").none { it is MarkdownInline.Link })
    }

}
