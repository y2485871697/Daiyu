package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.domain.RunStatus
import org.junit.Assert.*
import org.junit.Test

class CompletedVisibleTurnsTest {
    @Test fun scrollingReadsOnlyVisibleKeysAndKeepsTheExactCompletionGates() {
        val done = ChatTurnUi.Assistant("done", finalText = "displayed")
        val running = ChatTurnUi.Assistant("running", status = RunStatus.RUNNING)
        val pending = ChatTurnUi.Assistant("pending")
        val stale = ChatTurnUi.Assistant("stale", finalText = "latest")
        val data = (0..500).associate { "old-$it" to ChatTurnUi.Assistant("old-$it") } +
            listOf(done, running, pending, stale).associateBy { it.listKey }
        var lookups = 0
        val indexed = object : Map<String, ChatTurnUi.Assistant> by data {
            override val entries: Set<Map.Entry<String, ChatTurnUi.Assistant>> get() = error("No history traversal during scrolling")
            override val values: Collection<ChatTurnUi.Assistant> get() = error("No history traversal during scrolling")
            override fun get(key: String): ChatTurnUi.Assistant? { lookups++; return data[key] }
        }
        val ready = mapOf("done" to done, "running" to running, "stale" to stale.copy(finalText = "old"))
        val revealed = mapOf("done" to done, "running" to running, "pending" to pending, "stale" to stale)
        val visible = setOf<Any>("done", "running", "pending", "stale", "unknown")
        repeat(300) { assertEquals(listOf(done), completedVisibleTurns(visible, indexed, ready, revealed)) }
        assertEquals(300 * visible.size, lookups)
        assertTrue(completedVisibleTurns(emptySet(), indexed, ready, revealed).isEmpty())
        assertEquals(300 * visible.size, lookups)
    }
}
