package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.MarkdownBlock
import app.daiyu.android.MarkdownParser
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MarkdownProjectionCacheTest {
    private val parser: (String) -> List<MarkdownBlock> = MarkdownParser::parse
    private fun projection(vararg sources: String) = MarkdownProjection(sources.toList(),
        sources.associateWith(parser), parser)

    @Test fun exactCompletedProjectionReturnsWithoutReparsing() = runBlocking {
        val cache = MarkdownProjectionCache()
        val calls = AtomicInteger()
        val parse: (String) -> List<MarkdownBlock> = { calls.incrementAndGet(); parser(it) }
        val sources = listOf("**first**", "second")
        val first = projectMarkdown(sources, parse, cache)
        val returned = projectMarkdown(sources, parse, cache)
        assertEquals(2, calls.get())
        assertEquals(sources, returned.sources)
        assertSame(first.blocks, returned.blocks)
        assertSame(parse, returned.parser)
        assertNull(cache.get(sources.reversed(), parse))
        assertNull(cache.get(listOf("**first** appended", "second"), parse))
        assertNull(cache.get(listOf("**fir"), parse))
    }

    @Test fun cachedDisplayCoordinatesNeverWalkTheAstOnTheConsumerThread() = runBlocking {
        val reads = AtomicInteger()
        val parse: (String) -> List<MarkdownBlock> = { source ->
            val blocks = parser(source)
            object : List<MarkdownBlock> by blocks {
                override fun iterator(): Iterator<MarkdownBlock> { reads.incrementAndGet(); return blocks.iterator() }
            }
        }
        val cache = MarkdownProjectionCache()
        val source = "Stable **paragraph**.\n\nH1 | H2\n--- | ---\na | b\n\nTail"
        val projected = projectMarkdown(listOf(source), parse, cache)
        val before = reads.get()
        repeat(100) {
            val prepared = cache.getPrepared(listOf(source), parse)!!
            assertSame(projected.displayText.getValue(source), prepared.displayText.getValue(source))
            assertEquals(projected.displayText.getValue(source).length,
                markdownTypingBaselineLength(prepared, listOf(source), allowPrefix = false))
        }
        assertEquals("Cache lookup and baseline preparation must not traverse the AST", before, reads.get())
    }

    @Test fun parserIdentityDoesNotBorrowAnEqualParsersAst() {
        class EqualParser : (String) -> List<MarkdownBlock> {
            override fun invoke(source: String) = parser(source)
            override fun equals(other: Any?) = other is EqualParser
            override fun hashCode() = 1
        }
        val cache = MarkdownProjectionCache()
        val first = EqualParser()
        val second = EqualParser()
        assertEquals(MarkdownParserIdentity(first), MarkdownParserIdentity(first))
        assertNotEquals(MarkdownParserIdentity(first), MarkdownParserIdentity(second))
        val value = MarkdownProjection(listOf("**body**"), mapOf("**body**" to first("**body**")), first)
        val replacement = MarkdownProjection(value.sources, value.blocks, second)
        assertNotEquals("produceState must publish a distinct parser even when parser.equals is true", value, replacement)
        cache.put(value)
        assertNotNull(cache.get(value.sources, first))
        assertNull(cache.get(value.sources, second))
        assertSame(value.blocks["**body**"], cache.getBlocks("**body**", first))
        assertNull(cache.getBlocks("**body**", second))
    }

    @Test fun entryLimitEvictsLeastRecentlyUsedProjection() {
        val cache = MarkdownProjectionCache(maxEntries = 2, maxSourceChars = 100)
        cache.put(projection("a")); cache.put(projection("b"))
        assertNotNull(cache.get(listOf("a"), parser))
        cache.put(projection("c"))
        assertNull(cache.get(listOf("b"), parser))
        assertNotNull(cache.get(listOf("a"), parser))
        assertNotNull(cache.get(listOf("c"), parser))
    }

    @Test fun sourceBudgetCountsReplacementOnceAndRejectsOversizedProjection() {
        val cache = MarkdownProjectionCache(maxEntries = 10, maxSourceChars = 5)
        cache.put(projection("aa")); cache.put(projection("aa")); cache.put(projection("bbb"))
        assertNotNull(cache.get(listOf("aa"), parser))
        cache.put(projection("cc"))
        assertNull(cache.get(listOf("bbb"), parser))
        cache.put(projection("123456"))
        assertNull(cache.get(listOf("123456"), parser))
        assertNotNull(cache.get(listOf("aa"), parser))
        assertNotNull(cache.get(listOf("cc"), parser))
    }

    @Test fun retainedSourceListIsASnapshot() {
        val cache = MarkdownProjectionCache()
        val sources = mutableListOf("original")
        cache.put(MarkdownProjection(sources, mapOf("original" to parser("original")), parser))
        sources[0] = "replacement"
        assertEquals(listOf("original"), cache.get(listOf("original"), parser)!!.sources)
        assertNull(cache.get(sources, parser))
    }

    @Test fun cancelledOrFailedParseCannotPublishIntoCache() = runBlocking {
        val cache = MarkdownProjectionCache()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocked: (String) -> List<MarkdownBlock> = {
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); parser(it)
        }
        val pending = async(start = CoroutineStart.UNDISPATCHED) { projectMarkdown(listOf("blocked"), blocked, cache) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            pending.cancel()
            release.countDown()
            pending.join()
            assertNull(cache.get(listOf("blocked"), blocked))
        } finally { release.countDown(); pending.cancelAndJoin() }
        val failed: (String) -> List<MarkdownBlock> = { error("parse failed") }
        try {
            projectMarkdown(listOf("failed"), failed, cache)
            fail("A failed parse must remain a failure")
        } catch (expected: IllegalStateException) {
            assertEquals("parse failed", expected.message)
        }
        assertNull(cache.get(listOf("failed"), failed))
    }

    @Test fun appendedMixedSourcesReuseOnlyExactCompletedSegments() = runBlocking {
        val cache = MarkdownProjectionCache()
        val calls = mutableListOf<String>()
        val parse: (String) -> List<MarkdownBlock> = { calls += it; parser(it) }
        val first = projectMarkdown(listOf("a"), parse, cache)
        val second = projectMarkdown(listOf("a", "b"), parse, cache)
        val third = projectMarkdown(listOf("a", "b", "c"), parse, cache)
        assertEquals(listOf("a", "b", "c"), calls)
        assertSame(first.blocks["a"], second.blocks["a"])
        assertSame(second.blocks["b"], third.blocks["b"])
        projectMarkdown(listOf("a changed", "b", "c"), parse, cache)
        assertEquals(listOf("a", "b", "c", "a changed"), calls)
        assertNotNull(cache.get(listOf("a", "b", "c"), parse))
    }

    @Test fun segmentLookupUsesParserIdentityAndTheExistingLruBudget() {
        val cache = MarkdownProjectionCache(maxEntries = 2, maxSourceChars = 4)
        val other: (String) -> List<MarkdownBlock> = { parser(it) }
        cache.put(projection("aa")); cache.put(projection("bb"))
        assertNull(cache.getBlocks("aa", other))
        assertNotNull(cache.getBlocks("aa", parser))
        cache.put(projection("cc"))
        assertNull(cache.getBlocks("bb", parser))
        assertNotNull(cache.getBlocks("aa", parser))
        assertNotNull(cache.getBlocks("cc", parser))
        cache.put(projection("oversized"))
        assertNull(cache.getBlocks("oversized", parser))
    }

    @Test fun failureDoesNotCacheFreshSegmentsFromAnIncompleteProjection() = runBlocking {
        val cache = MarkdownProjectionCache()
        val calls = mutableListOf<String>()
        val parse: (String) -> List<MarkdownBlock> = {
            calls += it
            check(it != "fail") { "parse failed" }
            parser(it)
        }
        projectMarkdown(listOf("retained"), parse, cache)
        try {
            projectMarkdown(listOf("retained", "fresh", "fail"), parse, cache)
            fail("The incomplete projection must fail")
        } catch (expected: IllegalStateException) {
            assertEquals("parse failed", expected.message)
        }
        assertEquals(listOf("retained", "fresh", "fail"), calls)
        assertNotNull(cache.getBlocks("retained", parse))
        assertNull(cache.getBlocks("fresh", parse))
        assertNull(cache.get(listOf("retained", "fresh", "fail"), parse))
    }


    @Test fun cancellationDoesNotPublishFreshSegmentsBeforeTheWholeProjectionCompletes() = runBlocking {
        val cache = MarkdownProjectionCache()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val parse: (String) -> List<MarkdownBlock> = {
            calls.incrementAndGet()
            if (it == "blocked") {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            parser(it)
        }
        val sources = listOf("fresh", "blocked", "unseen")
        val pending = async(start = CoroutineStart.UNDISPATCHED) { projectMarkdown(sources, parse, cache) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            pending.cancel()
            release.countDown()
            pending.join()
            assertEquals(2, calls.get())
            assertNull(cache.get(sources, parse))
            assertNull(cache.getBlocks("fresh", parse))
            assertNull(cache.getBlocks("blocked", parse))
        } finally { release.countDown(); pending.cancelAndJoin() }
        projectMarkdown(sources, parse, cache)
        assertEquals(5, calls.get())
    }

}
