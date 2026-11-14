package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.MarkdownBlock
import app.daiyu.android.domain.*
import app.daiyu.android.timeline.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ReconnectMarkdownRestoreAcceptanceTest {
    private val anchor = TimelineAnchor("c", "r", "a", 0)
    private fun event(offset: Int, id: String = "one") = ChatTimelineEntry.Reconnect(id, anchor, 1L,
        "running", 10L, "network", 0, "model", ReconnectPosition(offset.toLong()))
    private fun message(text: String, status: RunStatus = RunStatus.COMPLETED) = Message(
        MessageId("a"), ConversationId("c"), RunId("r"), Message.ASSISTANT_ROLE,
        listOf(MessageBlock.Text("body", text)), status)
    private fun parts(message: Message, events: List<ChatTimelineEntry.Reconnect>): ReconnectParts {
        val turn = ChatTurnMapper.map(listOf(message), reconnects = events).filterIsInstance<ChatTurnUi.Assistant>().single()
        return reconnectParts(turn, events)
    }

    @Test fun multipleBreakpointsKeepFullContextAndExactUnchangedRawCoordinates() = runBlocking {
        val text = "```sh\none😀two\nthree\n```"
        val cuts = listOf(text.indexOf("two"), text.indexOf("three") + 2)
        val events = cuts.mapIndexed { index, cut -> event(cut, "cut-$index") }
        val original = message(text)
        val result = parts(original, events)
        val items = result.parts.flatMap { it.turn.content }.filterIsInstance<MessageContentUi.Text>()
        assertEquals(text, items.joinToString("") { it.text })
        assertEquals(listOf(0) + cuts, items.map { it.markdownWindow!!.start })
        assertEquals(cuts + text.length, items.map { it.markdownWindow!!.end })
        assertTrue(items.all { it.markdownWindow!!.source == text })
        for (item in items) {
            val projection = projectMarkdown(listOf(item.text), windows = listOf(item.markdownWindow))
            assertTrue(projection.blocksAt(0)!!.single() is MarkdownBlock.Code)
        }
        assertEquals(events, result.parts.flatMap { it.after })
        assertEquals(listOf(MessageBlock.Text("body", text)), original.blocks)
    }

    @Test fun malformedHalfSurrogateCoordinateKeepsItsRawWindowWithoutRenderingHalfAGlyph() {
        val source = "A😀B"
        val result = parts(message(source), listOf(event(2)))
        val items = result.parts.flatMap { it.turn.content }.filterIsInstance<MessageContentUi.Text>()
        assertEquals(2, items.first().markdownWindow!!.end)
        assertEquals(2, items.last().markdownWindow!!.start)
        assertEquals("A", items.first().text)
        assertEquals("😀B", items.last().text)
        assertEquals(source, items.joinToString("") { it.text })
        assertEquals(2L, result.parts.first().after.single().position!!.offset)
    }

    @Test fun appendedClosingContextUpdatesBothWindowsWithoutChangingThePersistedCut() = runBlocking {
        val before = "**left right"
        val after = "$before**"
        val cut = before.indexOf("right")
        val event = event(cut)
        val cache = MarkdownProjectionCache()
        var old: MarkdownProjection? = null
        for (source in listOf(before, after)) {
            val item = parts(message(source, RunStatus.RUNNING), listOf(event)).parts.first().turn.content
                .filterIsInstance<MessageContentUi.Text>().single()
            val current = projectMarkdown(listOf(item.text), streamingMarkdownParser, cache, old, listOf(item.markdownWindow))
            assertEquals(cut, current.windows.single()!!.end)
            assertEquals(source, current.windows.single()!!.source)
            assertEquals("left ", current.displayTextAt(0))
            old = current
        }
        assertEquals(cut.toLong(), event.position!!.offset)
    }

    @Test fun frozenSegmentsUseTheOriginalSourcePhaseAndInvalidateWhenItFinishes() = runBlocking {
        val source = "**left right"
        val event = event(source.indexOf("right"))
        val cache = MarkdownProjectionCache()
        var previous: MarkdownProjection? = null
        var oldWindow: app.daiyu.android.MarkdownSourceWindow? = null
        for (status in listOf(RunStatus.RUNNING, RunStatus.COMPLETED)) {
            val frozen = parts(message(source, status), listOf(event)).parts.first().turn
            assertEquals(RunStatus.COMPLETED, frozen.status)
            val item = frozen.content.filterIsInstance<MessageContentUi.Text>().single()
            val window = item.markdownWindow!!
            assertEquals(status == RunStatus.RUNNING, window.streaming)
            val projection = projectMarkdown(listOf(item.text), defaultMarkdownParser, cache, previous, listOf(window))
            assertEquals(if (status == RunStatus.RUNNING) "left " else "**left ", projection.displayTextAt(0))
            oldWindow?.let { assertFalse(markdownWindowsAppend(listOf(it), listOf(window))) }
            oldWindow = window
            previous = projection
        }
    }

    @Test fun toolsReasoningAndTheLastEmptyLiveTailKeepTheirOwners() {
        val original = message("unused").copy(blocks = listOf(
            MessageBlock.Tool(ToolInvocation(ToolCallId("tool"), "search", "{}"), "done"),
            MessageBlock.Text("body", "**left right**")),
            reportedReasoning = listOf(ReportedReasoning("thought", "**thinking**", blockOffset = 0)))
        val cut = original.reportedReasoning.single().text.length + 1 + "**left ".length
        val result = parts(original, listOf(event(cut)))
        assertEquals(1, result.parts.flatMap { it.turn.content }.filterIsInstance<MessageContentUi.Tool>().size)
        val thought = result.parts.first().turn.content.filterIsInstance<MessageContentUi.Reasoning>().single()
        assertEquals("**thinking**", thought.markdownWindow!!.source)
        assertEquals(null, thought.reported.activeSinceNanos)
        assertTrue(result.parts.all { it.turn.actionKey == "c:r:a:0" || it.turn.ownerMessageId == "a" })
        val end = ReconnectPosition.size(original).toInt()
        val tail = parts(original, listOf(event(end))).parts.last().turn
        assertTrue(tail.finalContent.isEmpty())
        assertTrue(tail.content.isEmpty())
    }
}
