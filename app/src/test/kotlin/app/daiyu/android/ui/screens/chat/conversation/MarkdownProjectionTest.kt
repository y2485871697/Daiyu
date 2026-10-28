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
        assertFalse(isMarkdownAppend(listOf("first"), listOf("first", "new part")))
    }

}
