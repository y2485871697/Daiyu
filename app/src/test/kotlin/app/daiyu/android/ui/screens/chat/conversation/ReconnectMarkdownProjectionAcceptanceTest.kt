package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.MarkdownBlock
import app.daiyu.android.MarkdownInline
import app.daiyu.android.MarkdownParser
import app.daiyu.android.MarkdownSourceWindow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ReconnectMarkdownProjectionAcceptanceTest {
    private fun inline(nodes: List<MarkdownInline>): String = nodes.joinToString("") { when (it) {
        is MarkdownInline.Text -> it.text
        is MarkdownInline.Code -> it.text
        is MarkdownInline.Emphasis -> inline(it.content)
        is MarkdownInline.Strike -> inline(it.content)
        is MarkdownInline.Link -> inline(it.content)
        MarkdownInline.Break -> "\n"
    } }
    private fun text(blocks: List<MarkdownBlock>): String = blocks.joinToString("") { when (it) {
        is MarkdownBlock.Paragraph -> inline(it.content)
        is MarkdownBlock.Heading -> inline(it.content)
        is MarkdownBlock.Code -> it.text
        is MarkdownBlock.Quote -> text(it.blocks)
        is MarkdownBlock.Items -> it.items.joinToString("") { item -> text(item.blocks) }
        is MarkdownBlock.Table -> (if (it.headerVisible) listOf(it.header) else emptyList()).plus(it.rows)
            .joinToString("") { row -> row.joinToString("") { cell -> inline(cell) } }
        MarkdownBlock.Rule -> ""
    } }
    private fun leaves(blocks: List<MarkdownBlock>): List<MarkdownInline> = blocks.flatMap { when (it) {
        is MarkdownBlock.Paragraph -> it.content
        is MarkdownBlock.Heading -> it.content
        is MarkdownBlock.Quote -> leaves(it.blocks)
        is MarkdownBlock.Items -> it.items.flatMap { item -> leaves(item.blocks) }
        is MarkdownBlock.Table -> (it.header + it.rows.flatten()).flatten()
        else -> emptyList()
    } }
    private fun noBrokenSurrogates(value: String) {
        value.forEachIndexed { index, char ->
            if (char.isHighSurrogate()) assertTrue(value.getOrNull(index + 1)?.isLowSurrogate() == true)
            if (char.isLowSurrogate()) assertTrue(value.getOrNull(index - 1)?.isHighSurrogate() == true)
        }
    }

    @Test fun mappedParserKeepsTheExistingGrammarAndEverySplitConservesVisiblePayload() {
        val sources = listOf(
            "# heading **粗体**\n\nplain  \nnext\\! [标签😀](https://example.test/a)",
            "```kotlin\nval a = \"😀\"\nprintln(a)\n```\n\nend",
            "> - **first😀**\n>   continuation\n> - `second`\n\n---\n\n> ***nested***",
            "| [标签](https://example.test) | `code` |\n| :--- | ---: |\n| 左😀右 | **bold** |\n| &amp; | &#x1f600; |",
            "a\\*b &amp; c &#x1f600; d\r\nnext\rlast\n\n    indented\n    code",
            "**bold *italic*** and **bold***italic* and ~~strike~~",
            "****a**** and *a **b* c**",
            "****a* b* c",
            "**结论不是骗局。**从公开报道开始\n\n- **“通过测谎”不能证明真实。**确有报道",
            "中文**“重点”**继续与\\*\\*字面星号\\*\\*",
        )
        for (source in sources) {
            val document = MarkdownParser.parseDocument(source)
            assertEquals(source, MarkdownParser.parse(source), document.blocks)
            val full = text(document.blocks)
            for (cut in 0..source.length) {
                val before = text(document.window(0, cut))
                val after = text(document.window(cut, source.length))
                assertEquals("cut=$cut source=$source", full, before + after)
                noBrokenSurrogates(before)
                noBrokenSurrogates(after)
            }
        }
    }

    @Test fun partiallyMatchedLongOpenersKeepEveryUnmatchedSourceOrigin() {
        val document = MarkdownParser.parseDocument("****a* b* c")
        assertEquals("**a b c", text(document.blocks))
        assertEquals("*", text(document.window(0, 1)))
        assertEquals("*", text(document.window(1, 2)))
    }

    @Test fun linksAndEmphasisKeepTheirCompleteOriginalContext() {
        val source = "**[左😀右](https://example.test/path)**"
        val cut = source.indexOf("右")
        val document = MarkdownParser.parseDocument(source)
        for (blocks in listOf(document.window(0, cut), document.window(cut, source.length))) {
            val emphasis = leaves(blocks).single() as MarkdownInline.Emphasis
            assertTrue(emphasis.bold)
            val link = emphasis.content.single() as MarkdownInline.Link
            assertEquals("https://example.test/path", link.destination)
        }
        assertEquals("左😀右", text(document.window(0, cut)) + text(document.window(cut, source.length)))
        val destinationCut = source.indexOf("path") + 2
        assertTrue(document.window(destinationCut, source.length).isEmpty())
    }

    @Test fun codeFragmentsRetainLanguageWholeBlockActionsAndInteriorNewline() {
        val source = "```python\nprint(1)\nprint(2)\n```"
        val cut = source.indexOf("print(2)")
        val document = MarkdownParser.parseDocument(source)
        val before = document.window(0, cut).single() as MarkdownBlock.Code
        val after = document.window(cut, source.length).single() as MarkdownBlock.Code
        assertEquals("python", before.language)
        assertEquals("python", after.language)
        assertEquals("print(1)\nprint(2)\n", before.exportText)
        assertEquals(before.exportText, after.exportText)
        assertEquals("print(1)\n", markdownCodeDisplayText(before, before.text))
        assertEquals("print(2)", markdownCodeDisplayText(after, after.text))
    }

    @Test fun tableContinuationsKeepColumnWidthsButNeverRepeatTheHeader() {
        val source = "| A long header | B |\n| :--- | ---: |\n| left | [right](https://example.test) |"
        val document = MarkdownParser.parseDocument(source)
        val cut = source.indexOf("right") + 2
        val original = document.blocks.single() as MarkdownBlock.Table
        val before = document.window(0, cut).single() as MarkdownBlock.Table
        val after = document.window(cut, source.length).single() as MarkdownBlock.Table
        assertTrue(before.headerVisible)
        assertFalse(after.headerVisible)
        assertEquals(original.header, after.widthHeader)
        assertEquals(original.alignment, after.alignment)
        assertEquals("ght", text(listOf(after)))
        assertEquals("https://example.test", (after.rows.single()[1].single() as MarkdownInline.Link).destination)
        assertEquals(0, markdownTableCellOffsets(after)[1][0])
    }

    @Test fun continuedListItemDoesNotInventAnotherMarkerOrRenumberLaterItems() {
        val source = "3. **first second**\n4. next"
        val cut = source.indexOf("second")
        val document = MarkdownParser.parseDocument(source)
        val before = document.window(0, cut).single() as MarkdownBlock.Items
        val after = document.window(cut, source.length).single() as MarkdownBlock.Items
        assertTrue(before.items.single().showMarker)
        assertFalse(after.items.first().showMarker)
        assertTrue(after.items.last().showMarker)
        assertEquals(3, after.start)
    }

    @Test fun identicalFragmentsCannotBorrowAnotherWindowsSyntaxOrCacheEntry() = runBlocking {
        val code = "```sh\nx\n```"
        val link = "[x](https://example.test)"
        val windows = listOf(MarkdownSourceWindow(code, code.indexOf('x'), code.indexOf('x') + 1),
            MarkdownSourceWindow(link, 1, 2))
        val cache = MarkdownProjectionCache()
        val projection = projectMarkdown(listOf("x", "x"), cache = cache, windows = windows)
        assertTrue(projection.blocksAt(0)!!.single() is MarkdownBlock.Code)
        assertTrue((projection.blocksAt(1)!!.single() as MarkdownBlock.Paragraph).content.single() is MarkdownInline.Link)
        assertEquals("x", projection.displayTextAt(0))
        assertEquals("x", projection.displayTextAt(1))
        assertNotNull(cache.get(listOf("x", "x"), defaultMarkdownParser, windows))
        assertNull(cache.getPrepared(listOf("x"), defaultMarkdownParser))
        assertNull(markdownBodySnapshot("x", null, MarkdownBodySnapshot("x", projection.blocksAt(0)!!, windows[0]), windows[1]))
    }
    @Test fun typingAndBaselineRejectSameTextFromDifferentSyntaxWindows() = runBlocking {
        val source = "**x**"
        val first = MarkdownSourceWindow(source, 2, 3)
        val unrelated = MarkdownSourceWindow("[x](https://example.test)", 1, 2)
        val projection = projectMarkdown(listOf("x"), windows = listOf(first))
        assertFalse(markdownTypingAppends(listOf(MarkdownTypingSource("same", "x", first)),
            listOf(MarkdownTypingSource("same", "x", unrelated))))
        assertNull(markdownTypingBaselineLength(projection, listOf("x"), allowPrefix = true, windows = listOf(unrelated)))
        assertEquals(1, markdownTypingBaselineLength(projection, listOf("x"), allowPrefix = false, windows = listOf(first)))
        val appended = first.copy(source = "$source suffix")
        assertTrue(markdownTypingAppends(listOf(MarkdownTypingSource("same", "x", first)),
            listOf(MarkdownTypingSource("same", "x", appended))))
    }

}
