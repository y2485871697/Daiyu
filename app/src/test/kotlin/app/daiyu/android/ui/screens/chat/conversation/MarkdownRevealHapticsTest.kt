package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.domain.haptics.*
import org.junit.Assert.*
import org.junit.Test

class MarkdownRevealHapticsTest {
    private class Fixture {
        var now = 1000L
        val requests = mutableListOf<HapticRequest>()
        val engine = HapticEngine(HapticClock { now })
        val owner = engine.attach("c", object : HapticPort {
            override fun play(request: HapticRequest): HapticDelivery {
                requests += request
                return HapticDelivery.PLAYED
            }
            override fun cancel() = Unit
        }, resumed = true)
        val motion = MarkdownTypingState(listOf(MarkdownTypingSource("final", "")))
        var advances = 0
        fun target(text: String, streaming: Boolean = true, terminal: Boolean = false) =
            checkNotNull(motion.update(listOf(MarkdownTypingPart(MarkdownTypingSource("final", text), text.length)),
                0, streaming, terminal))
        fun frame(target: MarkdownTypingTarget, millis: Long): Int {
            now = millis
            return motion.advance(target, millis * 1_000_000L) { advances++; engine.visibleAdvance(owner) }
        }
    }

    @Test fun frameGrowthUsesExistingGlobalThrottleWithoutNetworkDoubleTicks() {
        val f = Fixture()
        val target = f.target("x".repeat(200))
        f.engine.backgroundOutput(f.owner, 36)
        assertTrue(f.requests.isEmpty())
        assertEquals(0, f.frame(target, 1000))
        assertTrue(f.requests.isEmpty())
        assertEquals(0, f.advances)
        f.frame(target, 1016); assertEquals(1, f.requests.size)
        f.frame(target, 1047); assertEquals(1, f.requests.size)
        f.frame(target, 1048); assertEquals(2, f.requests.size)
        assertEquals(3, f.advances)
        assertEquals(2, f.requests.size)
        assertTrue(f.requests.all { it.kind == HapticKind.TICK })
        f.engine.drain()
        assertEquals(2, f.requests.size)
    }

    @Test fun terminalDrainUsesExactExistingTwoHundredFiftyMillis() {
        val f = Fixture()
        val streaming = f.target("x".repeat(200))
        f.frame(streaming, 1000); f.frame(streaming, 1050)
        val drain = f.target("x".repeat(220), streaming = false, terminal = true)
        val before = f.advances
        assertTrue(f.frame(drain, 1100) < 220)
        assertTrue(f.frame(drain, 1349) < 220)
        assertEquals(220, f.frame(drain, 1350))
        assertTrue(f.advances > before)
        val done = f.advances
        f.frame(drain, 1400)
        assertEquals(done, f.advances)
    }

    @Test fun historyReplacementStaleTargetAndResumeCatchupDoNotNotify() {
        val f = Fixture()
        val first = f.target("x".repeat(200))
        f.frame(first, 1000); f.frame(first, 1050)
        val before = f.advances
        val replacement = f.target("replacement")
        f.frame(first, 1100); f.frame(replacement, 1150)
        assertEquals(before, f.advances)
        val history = MarkdownTypingState(listOf(MarkdownTypingSource("final", "already shown")))
        val target = history.update(listOf(MarkdownTypingPart(MarkdownTypingSource("final", "already shown"), 13)), 13, true, false)!!
        history.advance(target, 1_000_000_000) { fail("history/resume baseline must stay silent") }
    }

    @Test fun liveSettingsGateAndIntensityAreNotDuplicatedInTheRenderer() {
        val f = Fixture(); val target = f.target("x".repeat(500))
        f.frame(target, 1000); f.frame(target, 1050)
        val before = f.requests.size
        f.engine.updateSettings(HapticSettings(generation = false))
        f.frame(target, 1100); assertEquals(before, f.requests.size)
        f.engine.updateSettings(HapticSettings(touch = false))
        f.frame(target, 1150); assertEquals(before, f.requests.size)
        f.engine.updateSettings(HapticSettings(intensity = HapticIntensity.HIGH))
        f.frame(target, 1200)
        assertEquals(HapticIntensity.HIGH, f.requests.last().intensity)
        assertEquals(before + 1, f.requests.size)
    }

    @Test fun hiddenPausedBackgroundAndDetachedCannotPlayVisibleTicks() {
        val f = Fixture(); val target = f.target("x".repeat(500))
        f.frame(target, 1000)
        f.engine.lifecycle(f.owner, resumed = true, visible = false)
        f.frame(target, 1050)
        f.engine.lifecycle(f.owner, resumed = false, visible = true)
        f.frame(target, 1100)
        f.engine.lifecycle(f.owner, resumed = true, visible = true)
        f.engine.setForeground(false); f.frame(target, 1150)
        f.engine.setForeground(true); f.engine.detach(f.owner); f.frame(target, 1200)
        assertEquals(0, f.requests.size)
    }
}
