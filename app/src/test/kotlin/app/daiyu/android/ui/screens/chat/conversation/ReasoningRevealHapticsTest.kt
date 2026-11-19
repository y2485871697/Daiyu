package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.domain.haptics.*
import org.junit.Assert.*
import org.junit.Test

class ReasoningRevealHapticsTest {
    private fun part(id: String, text: String) = MarkdownTypingPart(MarkdownTypingSource(id, text), text.length)

    @Test fun anObservedEmptyThoughtRevealsItsReceivedChunkOnAdmission() {
        val motion = MarkdownTypingState(listOf(MarkdownTypingSource("reasoning-r", "")))
        motion.update(listOf(part("reasoning-r", "")), 0, streaming = true, terminal = false)
        val text = "思考".repeat(100)
        val target = checkNotNull(motion.update(listOf(part("reasoning-r", text)), 0, true, false))
        var ticks = 0
        assertEquals(text.length, motion.advance(target, 1_000_000_000L) { ticks++ })
        assertEquals(text.length, motion.advance(target, 4_000_000_000L) { ticks++ })
        assertEquals(1, ticks)
    }

    @Test fun bodyAndTwoThoughtsShareTheExistingGlobalThrottle() {
        var now = 1_000L
        val requests = mutableListOf<HapticRequest>()
        val engine = HapticEngine(HapticClock { now })
        val owner = engine.attach("chat", object : HapticPort {
            override fun play(request: HapticRequest): HapticDelivery {
                requests += request
                return HapticDelivery.PLAYED
            }
            override fun cancel() = Unit
        }, resumed = true)
        val ids = listOf("body", "reasoning-a", "reasoning-b")
        val streams = ids.map { id -> MarkdownTypingState(listOf(MarkdownTypingSource(id, ""))) }
        fun receive(length: Int) {
            streams.forEachIndexed { index, motion ->
                val target = checkNotNull(motion.update(listOf(part(ids[index], "文".repeat(length))), 0, true, false))
                assertEquals(length, motion.advance(target, now * 1_000_000L) { engine.visibleAdvance(owner) })
            }
        }
        receive(20)
        assertEquals(1, requests.size)
        now = 1_016L
        receive(40)
        assertEquals(1, requests.size)
        now = 1_032L
        engine.backgroundOutput(owner, 36, reasoning = true)
        assertEquals(1, requests.size)
        receive(60)
        assertEquals(2, requests.size)
        now = 4_000L
        streams.forEach { motion ->
            val target = checkNotNull(motion.target)
            motion.advance(target, now * 1_000_000L) { engine.visibleAdvance(owner) }
        }
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.kind == HapticKind.TICK })
    }

    @Test fun disclosureRebaselineSkipsHiddenHistoryAndRevealsOnlyLaterAppend() {
        val accumulated = "隐藏思考".repeat(40)
        var ticks = 0
        val sources = listOf(MarkdownTypingSource("r", accumulated))
        val reopened = MarkdownTypingState(sources)
        val baseline = checkNotNull(reopened.update(listOf(part("r", accumulated)), accumulated.length, true, false))
        assertEquals(accumulated.length, reopened.advance(baseline, 1_000_000_000L) { ticks++ })
        assertEquals(0, ticks)
        val next = checkNotNull(reopened.update(listOf(part("r", accumulated + "新".repeat(100))), accumulated.length, true, false))
        assertEquals(accumulated.length + 100, reopened.advance(next, 1_016_000_000L) { ticks++ })
        assertEquals(accumulated.length + 100, reopened.advance(next, 4_000_000_000L) { ticks++ })
        assertEquals(1, ticks)
    }
}
