package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.domain.haptics.*
import org.junit.Assert.*
import org.junit.Test

class ReasoningRevealHapticsTest {
    private fun part(id: String, text: String) = MarkdownTypingPart(MarkdownTypingSource(id, text), text.length)

    private fun drain(motion: MarkdownTypingState, target: MarkdownTypingTarget,
        afterMillis: Long, onAdvance: () -> Unit,
    ) {
        var shown = motion.visible(target)
        var time = afterMillis
        repeat(300) {
            if (shown == target.total) return
            time += 16
            val before = shown
            var callbacks = 0
            shown = motion.advance(target, time * 1_000_000L) {
                callbacks++
                onAdvance()
            }
            assertTrue(shown in before..target.total)
            assertEquals(if (shown > before) 1 else 0, callbacks)
        }
        assertEquals(target.total, shown)
    }

    @Test fun anObservedEmptyThoughtTypesItsReceivedChunkAfterAdmission() {
        val motion = MarkdownTypingState(listOf(MarkdownTypingSource("reasoning-r", "")))
        motion.update(listOf(part("reasoning-r", "")), 0, streaming = true, terminal = false)
        val text = "思考".repeat(100)
        val target = checkNotNull(motion.update(listOf(part("reasoning-r", text)), 0, true, false))
        var ticks = 0
        assertEquals(0, motion.visible(target))
        assertEquals(1, motion.advance(target, 1_000_000_000L) { ticks++ })
        assertEquals(1, ticks)
        val later = motion.advance(target, 4_000_000_000L) { ticks++ }
        assertTrue(later in 2 until text.length)
        assertEquals(2, ticks)
        drain(motion, target, 4000L) { ticks++ }
        assertEquals(text.length, motion.visible(target))
        assertTrue(ticks > 2)
        val done = ticks
        assertEquals(text.length, motion.advance(target, 10_000_000_000L) { ticks++ })
        assertEquals(done, ticks)
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
                val before = motion.visible(target)
                var callbacks = 0
                val shown = motion.advance(target, now * 1_000_000L) {
                    callbacks++
                    engine.visibleAdvance(owner)
                }
                assertTrue(shown in (before + 1) until length)
                assertEquals(1, callbacks)
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
            val before = motion.visible(target)
            val shown = motion.advance(target, now * 1_000_000L) { engine.visibleAdvance(owner) }
            assertTrue(shown in (before + 1) until target.total)
        }
        assertEquals(3, requests.size)
        for (time in 4016L..5000L step 16L) {
            now = time
            streams.forEach { motion ->
                val target = checkNotNull(motion.target)
                motion.advance(target, now * 1_000_000L) { engine.visibleAdvance(owner) }
            }
        }
        streams.forEach { motion ->
            val target = checkNotNull(motion.target)
            assertEquals(target.total, motion.visible(target))
        }
        val done = requests.size
        now = 6_000L
        streams.forEach { motion ->
            val target = checkNotNull(motion.target)
            assertEquals(target.total, motion.advance(target, now * 1_000_000L) {
                fail("Completed streams must not notify")
            })
        }
        engine.drain()
        assertEquals(done, requests.size)
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
        assertEquals(accumulated.length, reopened.advance(baseline, 1_008_000_000L) {
            fail("Reopened history must stay silent")
        })
        val next = checkNotNull(reopened.update(listOf(part("r", accumulated + "新".repeat(100))), accumulated.length, true, false))
        assertEquals(accumulated.length, reopened.visible(next))
        assertEquals(accumulated.length + 1, reopened.advance(next, 1_016_000_000L) { ticks++ })
        assertEquals(1, ticks)
        val later = reopened.advance(next, 4_000_000_000L) { ticks++ }
        assertTrue(later in (accumulated.length + 2) until next.total)
        assertEquals(2, ticks)
        drain(reopened, next, 4000L) { ticks++ }
        assertEquals(next.total, reopened.visible(next))
        assertTrue(ticks > 2)
        val done = ticks
        assertEquals(next.total, reopened.advance(next, 10_000_000_000L) { ticks++ })
        assertEquals(done, ticks)
    }
}
