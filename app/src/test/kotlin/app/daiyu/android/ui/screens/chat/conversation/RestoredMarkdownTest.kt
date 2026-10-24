package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.MarkdownBlock
import app.daiyu.android.MarkdownParser
import app.daiyu.android.domain.*
import org.junit.Assert.*
import org.junit.Test

class RestoredMarkdownTest {
    private fun message(vararg texts: String) = Message(MessageId("answer"), ConversationId("chat"), RunId("run"),
        Message.ASSISTANT_ROLE, texts.mapIndexed { i, text -> MessageBlock.Text("text-$i", text) }, RunStatus.COMPLETED)

    @Test fun restoredOrdinaryAndMixedBodiesHaveExactAstBeforeFirstLookup() {
        val original = message("**first**", "Second paragraph.")
        val cache = MarkdownProjectionCache()
        val calls = mutableListOf<String>()
        val parser: (String) -> List<MarkdownBlock> = { calls += it; MarkdownParser.parse(it) }
        prepareRestoredMarkdown(listOf(original), cache, parser)
        for (sources in listOf(listOf("**first**\n\nSecond paragraph."), listOf("**first**", "Second paragraph."))) {
            val ready = cache.getPrepared(sources, parser)!!
            assertEquals(sources, ready.sources)
            assertSame(parser, ready.parser)
            sources.forEach { assertEquals(MarkdownParser.parse(it), ready.blocks[it]) }
        }
        val firstCalls = calls.toList()
        prepareRestoredMarkdown(listOf(original), cache, parser)
        assertEquals(firstCalls, calls)
        assertNull(cache.getPrepared(listOf("**first** changed"), parser))
        assertNull(cache.getPrepared(listOf("**first**"), { MarkdownParser.parse(it) }))
        assertEquals(listOf("**first**", "Second paragraph."), original.blocks.filterIsInstance<MessageBlock.Text>().map { it.text })
        assertEquals(RunStatus.COMPLETED, original.status)
    }

    @Test fun completedHistoryIsExpandedButRunningAndUserBodiesAreNotPrepared() {
        val original = message("tail").copy(historySegments = listOf(
            MessageHistorySegment(MessageId("answer"), Message.ASSISTANT_ROLE,
                listOf(MessageBlock.Text("old", "**earlier**"))),
        ))
        val user = message("user input").copy(id = MessageId("user"), role = Message.USER_ROLE)
        val running = message("stream").copy(id = MessageId("live"), status = RunStatus.RUNNING)
        assertTrue(restoredMarkdownSources(listOf(original, user, running)).contains("**earlier**"))
        assertFalse(restoredMarkdownSources(listOf(original, user, running)).contains("user input"))
        assertFalse(restoredMarkdownSources(listOf(original, user, running)).contains("stream"))
    }

    @Test fun preparationKeepsTheNewestSourcesWithinTheExistingEntryAndCharacterBudgets() {
        val cache = MarkdownProjectionCache(maxEntries = 2, maxSourceChars = 5)
        assertEquals(listOf("bb", "aa"), cache.preparationSources(listOf("aa", "bb", "oversized", "aa")))
        val calls = mutableListOf<String>()
        val parser: (String) -> List<MarkdownBlock> = { calls += it; MarkdownParser.parse(it) }
        val messages = listOf(message("old"), message("bb").copy(id = MessageId("b")), message("aa").copy(id = MessageId("a")))
        prepareRestoredMarkdown(messages, cache, parser)
        assertEquals(listOf("bb", "aa"), calls)
        assertNull(cache.getPrepared(listOf("old"), parser))
        assertNotNull(cache.getPrepared(listOf("aa", "bb"), parser))
    }

    @Test fun cancellationDoesNotPublishTheInFlightRestorationSource() {
        val cache = MarkdownProjectionCache()
        val parser: (String) -> List<MarkdownBlock> = { MarkdownParser.parse(it) }
        var checks = 0
        try {
            prepareRestoredMarkdown(listOf(message("cancelled")), cache, parser) {
                if (++checks == 2) throw kotlinx.coroutines.CancellationException("closed")
            }
            fail("The cancelled parse must not publish")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        assertNull(cache.getBlocks("cancelled", parser))
    }
}
