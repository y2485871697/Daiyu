package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.timeline.ChatTimelineEntry
import app.daiyu.android.timeline.ReconnectPosition
import app.daiyu.android.timeline.TimelineAnchor
import org.junit.Assert.*
import org.junit.Test

class ReconnectIndexTest {
    private val owner = TimelineAnchor("c", "r", "a", 0)
    private fun turn(key: String, anchor: TimelineAnchor = owner, start: Long = 0L,
        text: String = "abcdef", aliases: List<String> = emptyList()): ChatTurnUi.Assistant {
        val content = listOf(MessageContentUi.Text("$key:text", text))
        return ChatTurnUi.Assistant(anchor.messageId, listKey = key, content = content,
            sourceListKeys = aliases, reconnectSource = ReconnectSourceUi(anchor, start, content))
    }
    private fun event(id: String, offset: Long?, anchor: TimelineAnchor = owner) =
        ChatTimelineEntry.Reconnect(id, anchor, 1L, "running", 10L, "network", 0, "model",
            offset?.let { ReconnectPosition(it) })
    private class CountingList<T>(private val source: List<T>) : AbstractList<T>() {
        var reads = 0
        override val size: Int get() = source.size
        override fun get(index: Int): T { reads++; return source[index] }
    }

    @Test fun ownerBucketsMatchOriginalGlobalPlacementAndDuplicateRules() {
        val other = owner.copy(runId = "other")
        val rows = listOf(turn("first", text = "abc"), ChatTurnUi.User("u", "request"),
            turn("second", owner.copy(segment = 8), 3L, "def"), turn("other", other),
            ChatTurnUi.Assistant("without-source"))
        val events = listOf(event("same-b", 3L, owner.copy(segment = 77)), event("end", 6L),
            event("shared", 2L), event("zero", 0L), event("same-a", 3L), event("tail", 4L),
            event("shared", 1L, other), event("other-only", 1L, other),
            event("legacy", null), event("negative", -1L), event("far", 99L))
        val placed = hashSetOf<String>()
        val expected = rows.filterIsInstance<ChatTurnUi.Assistant>().associate { row ->
            val parts = reconnectParts(row, events.filterNot { it.id in placed })
            placed += parts.placedIds
            row.listKey to parts
        }
        assertEquals(expected, reconnectPartsForTurns(rows, events))
        val duplicate = event("duplicate", 1L)
        assertEquals(listOf(duplicate, duplicate), reconnectPartsForTurns(listOf(turn("single")),
            listOf(duplicate, duplicate)).getValue("single").parts.flatMap { it.after })
    }

    @Test fun originalEventsAreReadOnceForDistinctOwners() {
        val anchors = List(32) { owner.copy(messageId = "a$it") }
        val rows = anchors.mapIndexed { i, anchor -> turn("turn-$i", anchor, text = "x") }
        val events = CountingList(anchors.mapIndexed { i, anchor -> event("event-$i", 1L, anchor) })
        val actual = reconnectPartsForTurns(rows, events)
        assertEquals(32, actual.size)
        assertTrue(actual.values.all { it.placedIds.size == 1 })
        assertEquals(events.size, events.reads)
    }

    @Test fun fallbackIndexMatchesAliasesInputOrderDuplicateIdsAndLastKeyWins() {
        val alias = owner.copy(segment = 7)
        val rows = listOf(turn("first", aliases = listOf(alias.listKey, alias.listKey)),
            turn("last", owner.copy(segment = 1), aliases = listOf(owner.copy(segment = 1).listKey)),
            turn("other", owner.copy(runId = "other"), aliases = listOf(alias.listKey)),
            ChatTurnUi.User("shadow", "request", listKey = "last"))
        val direct = event("direct", 999L, alias)
        val fallback = event("fallback", 999L, owner.copy(segment = 90))
        val timeline: List<ChatTimelineEntry> = listOf(direct, ChatTimelineEntry.Stopped("stopped", alias, 3L),
            event("legacy", null, owner.copy(segment = 90)), fallback, event("placed", 1L, alias), direct,
            event("overwritten", 1L, owner.copy(segment = 1)),
            ChatTimelineEntry.Stopped("non-reconnect", owner.copy(segment = 90), 4L))
        val expected = rows.associate { it.listKey to reconnectFallbackEntries(it, timeline, setOf("placed"), rows) }
        val counted = CountingList(timeline)
        val actual = reconnectFallbackEntriesForTurns(rows, counted, setOf("placed"))
        assertEquals(expected, actual)
        assertEquals(listOf(fallback), actual.getValue("last"))
        assertEquals(timeline.size, counted.reads)
    }

    @Test fun cursorPreservesEmptyItemsSurrogatesAndLiveTail() {
        val content = listOf(MessageContentUi.Text("empty-first", ""), MessageContentUi.Text("left", "A😀B"),
            MessageContentUi.Text("empty-middle", ""), MessageContentUi.Text("right", "CD"))
        val row = turn("cursor").copy(content = content, reconnectSource = ReconnectSourceUi(owner, 0L, content))
        val events = listOf(0L, 2L, 3L, 4L, 5L, 6L).mapIndexed { i, offset -> event("cut-$i", offset) }
        val actual = reconnectParts(row, events)
        assertEquals(listOf("", "A", "😀", "B", "C", "D", ""), actual.parts.map { part ->
            part.turn.content.filterIsInstance<MessageContentUi.Text>().joinToString("") { it.text }
        })
        assertTrue(actual.parts.last().turn.content.isEmpty())
        assertEquals(events.map { it.id }.toSet(), actual.placedIds)
    }
}
