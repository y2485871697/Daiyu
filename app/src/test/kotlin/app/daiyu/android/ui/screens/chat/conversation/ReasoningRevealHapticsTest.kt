package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.domain.haptics.*
import org.junit.Assert.*
import org.junit.Test

class ReasoningRevealHapticsTest {
    private fun part(id: String, text: String) = MarkdownTypingPart(MarkdownTypingSource(id, text), text.length)

    @Test fun anObservedEmptyThoughtTypesItsFirstChunk() {
        val motion = MarkdownTypingState(listOf(MarkdownTypingSource("reasoning-r", "")))
        motion.update(listOf(part("reasoning-r", "")), 0, streaming = true, terminal = false)
        val text = "思考".repeat(100)
        val target = checkNotNull(motion.update(listOf(part("reasoning-r", text)), 0, true, false))
        var ticks = 0
        assertEquals(0, motion.advance(target, 1_000_000_000L) { ticks++ })
        val visible = motion.advance(target, 1_016_000_000L) { ticks++ }
        assertTrue(visible in 1 until text.length)
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
        val streams = listOf("body", "reasoning-a", "reasoning-b").map { id ->
            val motion = MarkdownTypingState(listOf(MarkdownTypingSource(id, "")))
            motion to checkNotNull(motion.update(listOf(part(id, "文".repeat(200))), 0, true, false))
        }
        streams.forEach { (motion, target) -> motion.advance(target, now * 1_000_000L) { engine.visibleAdvance(owner) } }
        listOf(1_016L, 1_047L, 1_048L).forEach { frame ->
            now = frame
            engine.backgroundOutput(owner, 36, reasoning = true)
            streams.forEach { (motion, target) -> motion.advance(target, now * 1_000_000L) { engine.visibleAdvance(owner) } }
        }
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.kind == HapticKind.TICK })
    }

    @Test fun disclosureRebaselineSkipsHiddenHistoryAndTypesOnlyLaterAppend() {
        val accumulated = "隐藏思考".repeat(40)
        var ticks = 0
        val sources = listOf(MarkdownTypingSource("r", accumulated))
        val reopened = MarkdownTypingState(sources)
        val baseline = checkNotNull(reopened.update(listOf(part("r", accumulated)), accumulated.length, true, false))
        assertEquals(accumulated.length, reopened.advance(baseline, 1_000_000_000L) { ticks++ })
        assertEquals(0, ticks)
        val next = checkNotNull(reopened.update(listOf(part("r", accumulated + "新".repeat(100))), accumulated.length, true, false))
        reopened.advance(next, 1_016_000_000L) { ticks++ }
        reopened.advance(next, 1_048_000_000L) { ticks++ }
        assertTrue(ticks > 0)
    }
}
