package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.MarkdownParser
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MarkdownProjectionTest {
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

    @Test fun emptyMixedBodyDoesNotInvokeParser() = runBlocking {
        val result = projectMarkdown(emptyList()) { error("no text") }
        assertTrue(result.blocks.isEmpty())
    }
}
