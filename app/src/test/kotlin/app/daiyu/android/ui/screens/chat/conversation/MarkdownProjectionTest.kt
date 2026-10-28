package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.MarkdownParser
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class MarkdownProjectionTest {
    @Test fun unchangedSourcesReuseThePreviousAstEvenWhenTheWholeProjectionExceedsTheCacheBudget() = runBlocking {
        val calls = mutableListOf<String>()
        val parser: (String) -> List<app.daiyu.android.MarkdownBlock> = { calls += it; MarkdownParser.parse(it) }
        val cache = MarkdownProjectionCache(maxSourceChars = 2)
        val first = projectMarkdown(listOf("AAAA", "BBB"), parser, cache)
        val next = projectMarkdown(listOf("AAAA", "BBBx"), parser, cache, first)
        assertEquals(1, calls.count { it == "AAAA" })
        assertSame(first.blocks["AAAA"], next.blocks["AAAA"])
        assertEquals(MarkdownParser.parse("BBBx"), next.blocks["BBBx"])
    }

    @Test fun newMixedSourcesDoNotCancelAnAcceptedSnapshot() = runBlocking {
        val requests = Channel<List<String>>(Channel.UNLIMITED)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val published = Channel<MarkdownProjection>(Channel.UNLIMITED)
        val parser: (String) -> List<app.daiyu.android.MarkdownBlock> = { source ->
            if (source == "first") { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
            MarkdownParser.parse(source)
        }
        val job = launch { collectMarkdownProjections(requests.receiveAsFlow(), parser) { published.trySend(it) } }
        try {
            requests.send(listOf("first"))
            withTimeout(5_000) { while (entered.count != 0L) yield() }
            requests.send(listOf("first", "second"))
            requests.send(listOf("first", "second", "third"))
            yield(); release.countDown()
            assertEquals(listOf("first"), withTimeout(5_000) { published.receive() }.sources)
            var latest = withTimeout(5_000) { published.receive() }
            while (latest.sources.size < 3) latest = withTimeout(5_000) { published.receive() }
            assertEquals(listOf("first", "second", "third"), latest.sources)
        } finally { release.countDown(); requests.close(); job.cancelAndJoin() }
    }

    @Test fun sameTextTerminalParseCannotReuseProvisionalCacheEntries() = runBlocking {
        val cache = MarkdownProjectionCache()
        for (source in listOf("正文\n-", "**未闭合", "`code")) {
            val pending = projectMarkdown(listOf(source), streamingMarkdownParser, cache)
            assertEquals(MarkdownParser.parseStreaming(source), pending.blocks.getValue(source))
            assertNull(cache.get(listOf(source), defaultMarkdownParser))
            assertNull(cache.getPrepared(listOf(source), defaultMarkdownParser))
            val terminal = projectMarkdown(listOf(source), defaultMarkdownParser, cache, pending)
            assertEquals(MarkdownParser.parse(source), terminal.blocks.getValue(source))
            assertNotEquals(pending.blocks.getValue(source), terminal.blocks.getValue(source))
            assertSame(pending.blocks.getValue(source), cache.get(listOf(source), streamingMarkdownParser)!!.blocks.getValue(source))
        }
    }

    @Test fun finalWarmCacheCannotReplaceTheStreamingAstForIdenticalText() = runBlocking {
        val cache = MarkdownProjectionCache()
        val source = "**未闭合"
        val terminal = projectMarkdown(listOf(source), defaultMarkdownParser, cache)
        val pending = projectMarkdown(listOf(source), streamingMarkdownParser, cache)
        assertNotSame(terminal, pending)
        assertEquals(MarkdownParser.parseStreaming(source), pending.blocks.getValue(source))
        assertSame(terminal.blocks.getValue(source), projectMarkdown(listOf(source), defaultMarkdownParser, cache).blocks.getValue(source))
    }

    @Test fun appendedPendingBodyRetainsThePublishedSourceAndAstTogether() {
        val source = "First **paragraph**.\n\nSecond paragraph."
        val blocks = MarkdownParser.parse(source)
        val published = markdownBodySnapshot(source, blocks, null)!!
        val appended = source + "\n\nThird paragraph."
        assertSame(published, markdownBodySnapshot(appended, null, published))
        assertEquals(source, published.text)
        assertSame(blocks, published.blocks)
        val completed = markdownBodySnapshot(appended, MarkdownParser.parse(appended), published)!!
        assertEquals(appended, completed.text)
        assertEquals(MarkdownParser.parse(appended), completed.blocks)
    }

    @Test fun initialReplacedTruncatedOrClearedBodiesNeverBorrowOldMarkdown() {
        val published = MarkdownBodySnapshot("**published**", MarkdownParser.parse("**published**"))
        assertNull(markdownBodySnapshot("initial", null, null))
        for (source in listOf("**replacement**", "**pub", "")) {
            assertNull(markdownBodySnapshot(source, null, published))
        }
        val empty = markdownBodySnapshot("", emptyList(), published)!!
        assertEquals("", empty.text)
        assertTrue(empty.blocks.isEmpty())
        assertNull(markdownBodySnapshot("next", null, null))
    }

    @Test fun parsesOffCallerThreadAndRetainsSourceIdentityAndSyntax() = runBlocking {
        val caller = Thread.currentThread()
        val seen = mutableListOf<String>()
        val sources = listOf("## heading", "```kotlin\nval x = 1\n```", "## heading")
        val parser = { source: String ->
            assertNotSame(caller, Thread.currentThread())
            seen += source
            MarkdownParser.parse(source)
        }
        val result = projectMarkdown(sources, parser)
        assertEquals(sources, result.sources)
        assertSame(parser, result.parser)
        assertEquals(sources.distinct(), seen)
        sources.forEach { assertEquals(MarkdownParser.parse(it), result.blocks[it]) }
    }

    @Test fun cancelledQueuedSnapshotsNeverParseAndLatestStillCompletes() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val seen = java.util.concurrent.CopyOnWriteArrayList<String>()
        val parser = { source: String ->
            seen += source
            if (source == "old") {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            MarkdownParser.parse(source)
        }
        val old = async(start = CoroutineStart.UNDISPATCHED) { projectMarkdown(listOf("old"), parser) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val obsolete = async(start = CoroutineStart.UNDISPATCHED) { projectMarkdown(listOf("obsolete"), parser) }
            obsolete.cancel()
            val latest = async(start = CoroutineStart.UNDISPATCHED) { projectMarkdown(listOf("**latest**"), parser) }
            release.countDown()
            obsolete.join()
            assertEquals(listOf("**latest**"), latest.await().sources)
            assertEquals(listOf("old", "**latest**"), seen)
            assertEquals(listOf("old"), old.await().sources)
        } finally { release.countDown(); old.cancelAndJoin() }
    }

    @Test fun shortColdProjectionCompletesWhileUnrelatedBulkParseIsBlocked() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val bulk = "x".repeat(INTERACTIVE_MARKDOWN_CHARS.toInt() + 1)
        val source = "Cold **answer**."
        val parser = { text: String ->
            if (text == bulk) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            MarkdownParser.parse(text)
        }
        val cache = MarkdownProjectionCache()
        val old = async(start = CoroutineStart.UNDISPATCHED) { projectMarkdown(listOf(bulk), parser, cache) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val latest = kotlinx.coroutines.withTimeout(2_000) { projectMarkdown(listOf(source), parser, cache) }
            assertEquals(1L, release.count)
            assertEquals(listOf(source), latest.sources)
            assertEquals(MarkdownParser.parse(source), latest.blocks[source])
        } finally { release.countDown(); old.cancelAndJoin() }
    }

    @Test fun emptyMixedBodyDoesNotInvokeParser() = runBlocking {
        val result = projectMarkdown(emptyList(), parser = { error("no text") })
        assertTrue(result.blocks.isEmpty())
    }
    @Test fun streamingAppendsPublishTheRunningSnapshotAndOnlyTheLatestPendingOne() = runBlocking {
        withTimeout(5_000) {
            val requests = Channel<List<String>>(Channel.UNLIMITED)
            val entered = Channel<String>(Channel.UNLIMITED)
            val published = Channel<MarkdownProjection>(Channel.UNLIMITED)
            val release = CountDownLatch(1)
            val seen = java.util.concurrent.CopyOnWriteArrayList<String>()
            val parser = { text: String ->
                seen += text
                if (text == "first") {
                    entered.trySend(text)
                    check(release.await(5, TimeUnit.SECONDS))
                }
                MarkdownParser.parse(text)
            }
            val job = launch {
                collectMarkdownProjections(requests.receiveAsFlow(), parser, MarkdownProjectionCache()) { published.trySend(it) }
            }
            try {
                requests.send(listOf("first"))
                assertEquals("first", entered.receive())
                repeat(200) { requests.send(listOf("first" + "x".repeat(it + 1))) }
                yield()
                release.countDown()
                val first = published.receive()
                assertEquals(listOf("first"), first.sources)
                assertEquals(MarkdownParser.parse("first"), first.blocks["first"])
                val latest = published.receive()
                assertEquals(listOf("first" + "x".repeat(200)), latest.sources)
                assertEquals(2, seen.size)
                // Keep the request stream open: progress must not wait for generation to finish.
                requests.send(listOf("first" + "x".repeat(201)))
                assertEquals(listOf("first" + "x".repeat(201)), published.receive().sources)
                requests.close()
                job.join()
            } finally { release.countDown(); job.cancelAndJoin(); requests.close() }
        }
    }

    @Test fun replacementCancelsTheOldSnapshotWithoutPublishingItsLateCompletion() = runBlocking {
        withTimeout(5_000) {
            val requests = Channel<List<String>>(Channel.UNLIMITED)
            val entered = Channel<Unit>(Channel.UNLIMITED)
            val published = Channel<MarkdownProjection>(Channel.UNLIMITED)
            val release = CountDownLatch(1)
            val parser = { text: String ->
                if (text == "old") {
                    entered.trySend(Unit)
                    check(release.await(5, TimeUnit.SECONDS))
                }
                MarkdownParser.parse(text)
            }
            val job = launch {
                collectMarkdownProjections(requests.receiveAsFlow(), parser, MarkdownProjectionCache()) { published.trySend(it) }
            }
            try {
                requests.send(listOf("old")); entered.receive()
                requests.send(listOf("replacement")); yield()
                release.countDown()
                assertEquals(listOf("replacement"), published.receive().sources)
                requests.close(); job.join()
                assertTrue(published.tryReceive().isFailure)
            } finally { release.countDown(); job.cancelAndJoin(); requests.close() }
        }
    }

    @Test fun appendCompatibilityRejectsReplacementDeletionAndReordering() {
        assertTrue(isMarkdownAppend(listOf("first", "second"), listOf("first", "second appended")))
        assertFalse(isMarkdownAppend(listOf("first", "second"), listOf("second", "first")))
        assertFalse(isMarkdownAppend(listOf("first", "second"), listOf("first")))
        assertFalse(isMarkdownAppend(listOf("first"), listOf("replacement")))
        assertTrue(isMarkdownAppend(listOf("first"), listOf("first", "new part")))
    }

}
