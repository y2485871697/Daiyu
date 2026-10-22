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
}
