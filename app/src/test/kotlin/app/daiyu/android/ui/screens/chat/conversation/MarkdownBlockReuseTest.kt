package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.MarkdownBlock
import app.daiyu.android.MarkdownParser
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.Assert.*
import org.junit.Test

class MarkdownBlockReuseTest {
    @Test fun appendingKeepsEveryUnchangedTopLevelBlockAndItsPreparedTextInputs() = runBlocking {
        val cache = MarkdownProjectionCache()
        val first = projectMarkdown(listOf("# Heading\n\nFirst **paragraph**.\n\nTail"), cache = cache)
        val next = projectMarkdown(listOf(first.sources.single() + " grows"), cache = cache, previous = first)
        val old = first.blocks.getValue(first.sources.single())
        val blocks = next.blocks.getValue(next.sources.single())
        assertSame(old[0], blocks[0]); assertSame(old[1], blocks[1])
        assertSame((old[1] as MarkdownBlock.Paragraph).content, (blocks[1] as MarkdownBlock.Paragraph).content)
        assertNotSame(old[2], blocks[2])
        assertEquals(MarkdownParser.parse(next.sources.single()), blocks)
        assertSame(blocks, cache.get(next.sources, next.parser)!!.blocks.getValue(next.sources.single()))
    }

    @Test fun wholeParseStillDecidesFencesTablesListsAndSetextStructure() = runBlocking {
        val cases = listOf(
            "Paragraph\n" to "---\n",
            "| A | B |\n" to "| --- | --- |\n| x | y |",
            "```kotlin\nval x = 1\n" to "```\nAfter",
            "- one\n" to "  - two\n",
            "> quote\n" to "> more\n",
            "[label](https://example.test" to ")",
        )
        for ((source, tail) in cases) {
            val cache = MarkdownProjectionCache()
            val first = projectMarkdown(listOf(source), cache = cache)
            val next = projectMarkdown(listOf(source + tail), cache = cache, previous = first)
            assertEquals(source + tail, next.sources.single())
            assertEquals(MarkdownParser.parse(source + tail), next.blocks.getValue(source + tail))
        }
    }

    @Test fun equalListsAreKeptButMovedOrChangedBlocksCannotBorrowWrongContent() {
        val first = MarkdownParser.parse("One\n\nTwo")
        assertSame(first, reuseMarkdownBlocks(first, MarkdownParser.parse("One\n\nTwo")))
        val next = reuseMarkdownBlocks(first, MarkdownParser.parse("Zero\n\nOne\n\nTwo"))
        assertEquals(MarkdownParser.parse("Zero\n\nOne\n\nTwo"), next)
        assertNotSame(first[0], next[1]); assertNotSame(first[1], next[2])
    }

    @Test fun replacementParserAndSourceCountChangesDoNotBorrowPreviousSession() = runBlocking {
        val first = projectMarkdown(listOf("old"), cache = MarkdownProjectionCache())
        val replaced = projectMarkdown(listOf("new"), cache = MarkdownProjectionCache(), previous = first)
        assertEquals(MarkdownParser.parse("new"), replaced.blocks["new"])
        val parser: (String) -> List<MarkdownBlock> = { MarkdownParser.parse(it) }
        val changed = projectMarkdown(first.sources, parser, MarkdownProjectionCache(), first)
        assertNotSame(first.blocks["old"], changed.blocks["old"])
        val mixed = projectMarkdown(listOf("old", "second"), cache = MarkdownProjectionCache(), previous = first)
        assertNotSame(first.blocks["old"], mixed.blocks["old"])
    }

    @Test fun realCollectorReusesOnlyItsOwnLastPublishedSnapshot() = runBlocking {
        val requests = kotlinx.coroutines.channels.Channel<List<String>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val results = kotlinx.coroutines.channels.Channel<MarkdownProjection>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val job = launch {
            collectMarkdownProjections(requests.receiveAsFlow(), cache = MarkdownProjectionCache()) { results.trySend(it) }
        }
        try {
            requests.send(listOf("First\n\nTail"))
            val first = kotlinx.coroutines.withTimeout(5_000) { results.receive() }
            requests.send(listOf("First\n\nTail grows"))
            val next = kotlinx.coroutines.withTimeout(5_000) { results.receive() }
            assertSame(first.blocks.getValue(first.sources.single()).first(), next.blocks.getValue(next.sources.single()).first())
            requests.send(listOf("Replacement"))
            val replaced = kotlinx.coroutines.withTimeout(5_000) { results.receive() }
            assertEquals(MarkdownParser.parse("Replacement"), replaced.blocks.getValue("Replacement"))
            requests.close(); job.join()
        } finally { requests.close(); job.cancel(); job.join() }
    }

    @Test fun duplicateSegmentsAndCachedResultsKeepCurrentSourceAstPairs() = runBlocking {
        val cache = MarkdownProjectionCache()
        val first = projectMarkdown(listOf("**same**", "**same**"), cache = cache)
        val nextSources = listOf("**same**\n\nNext", "**same**\n\nNext")
        projectMarkdown(nextSources, cache = cache)
        val next = projectMarkdown(nextSources, cache = cache, previous = first)
        assertEquals(1, next.blocks.size)
        val old = first.blocks.getValue("**same**")
        val blocks = next.blocks.getValue(nextSources.first())
        assertSame(old.first(), blocks.first())
        assertEquals(MarkdownParser.parse(nextSources.first()), blocks)
    }
}
