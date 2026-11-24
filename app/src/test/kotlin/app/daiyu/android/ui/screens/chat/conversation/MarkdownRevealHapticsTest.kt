package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.domain.haptics.*
import org.junit.Assert.*
import org.junit.Test

class MarkdownRevealHapticsTest {
    @Test fun naturallyDrawingSummaryCanFinishButOpeningHistoryDoesNotRearm() {
        assertTrue(reasoningPaintFeedback(false, true, true, false))
        assertFalse(reasoningPaintFeedback(false, false, true, false))
        assertFalse(reasoningPaintFeedback(false, true, false, false))
        assertFalse(reasoningPaintFeedback(false, true, true, true))
    }
    @Test fun detachedProgressDistinguishesTerminalBodyFromEndedReasoning() {
        val done = app.daiyu.android.domain.RunStatus.COMPLETED
        val live = app.daiyu.android.domain.RunStatus.RUNNING
        assertTrue(DetachedLane.BODY.pulses(done)); assertTrue(DetachedLane.BODY.pulses(live))
        assertTrue(DetachedLane.ACTIVE_REASON.pulses(live)); assertFalse(DetachedLane.ACTIVE_REASON.pulses(done))
        assertFalse(DetachedLane.ENDED_REASON.pulses(live)); assertFalse(DetachedLane.ENDED_REASON.pulses(done))
    }

    @Test fun documentFeedbackOnlyOnNewPaintedContent() {
        val parser = me.rerere.bettertext.markdown.MarkdownStreamParser()
        val receipt = AnswerPaint("已有")
        fun draw(text: String) = receipt.record(parser.parse(text, streaming = true))
        assertEquals(0, draw("已有"))
        assertEquals(2, draw("已有新字"))
        assertEquals(0, draw("已有新字"))
        assertEquals(0, draw("已有新字\n"))
        assertEquals(0, draw("更正后的段落"))
        assertEquals(1, draw("更正后的段落。"))
        assertEquals(2, AnswerPaint().record(parser.parse("首字", streaming = true)))
    }

    @Test fun batchedDrawCountsNewGraphemesOnlyAndNeverReplaysHistory() {
        val ink = MarkdownInk(2)
        val boundaries = MarkdownTextBoundaries("历史😀abc更多")
        val bursts = mutableListOf<Int>()
        ink.onGlyphDrawn = { bursts.add(it) }
        ink.reportDraw(0, 2, boundaries)
        ink.reportDraw(0, 5, boundaries)
        ink.reportDraw(0, 9, boundaries)
        ink.reportDraw(0, 9, boundaries)
        assertEquals(listOf(2, 3), bursts)
    }

    @Test fun parsedBodyIsNotPaintedAndShorterSourceCannotSettleFinalInk() {
        val first = MarkdownTypingSource("body", "甲")
        val final = first.copy(text = "甲乙")
        val ink = MarkdownInk(1, first)
        assertNull(ink.resumePainted(first))
        ink.reportDraw(0, 1, MarkdownTextBoundaries(first.text))
        assertTrue(ink.hasPaintedGlyph)
        assertFalse(ink.covers(final, 2))
        assertSame(ink, ink.resumePainted(final))
        assertNull(ink.resumePainted(first))
        assertFalse(ink.covers(final, 2))
        ink.detached = true
        repeat(5) { ink.advanceDetached(final.text, MarkdownTextBoundaries(final.text), .05) }
        assertTrue(ink.covers(final, 2))
    }

    @Test fun detachedFramesSpendTheSameInkAndReturningDrawDoesNotReplay() {
        val text = "甲😀乙".repeat(40)
        val bounds = MarkdownTextBoundaries(text)
        val ink = MarkdownInk(0)
        ink.detached = true
        assertFalse(ink.advanceDetached(text, bounds, 0.0))
        assertEquals(0.0, ink.position, 0.0)
        assertTrue(ink.advanceDetached(text, bounds, .016))
        assertTrue(ink.position < text.length)
        repeat(160) { ink.advanceDetached(text, bounds, .016) }
        assertEquals(text.length.toDouble(), ink.position, 0.0)
        assertFalse(ink.advanceDetached(text, bounds, .016))
        ink.detached = false
        val draws = mutableListOf<Int>()
        ink.onGlyphDrawn = { draws.add(it) }
        ink.reportDraw(0, text.length, bounds)
        assertTrue(draws.isEmpty())
        ink.reportDraw(0, text.length + 1, MarkdownTextBoundaries(text + "新"))
        assertEquals(listOf(1), draws)
    }

    @Test fun removingLazyRowDoesNotRetireControlsWithUnspentInk() {
        val turn = ChatTurnUi.Assistant(id = "reply")
        val control = ReplyPresentationControl(turn.listKey, true)
        val lane = control.lane("body:reply", MarkdownParserIdentity(defaultMarkdownParser), 0L) { emptyList() }
        val ink = MarkdownInk(0)
        lane.inks["answer"] = ink
        ink.targetLength(3)
        control.setSettled(turn, true)
        control.reportPending(ink, false)
        assertTrue(control.pending(turn))
        control.pause(true); assertTrue(control.pauseRequested && control.pending(turn))
        control.pause(false); ink.detached = true
        repeat(20) { ink.advanceDetached("甲乙丙", MarkdownTextBoundaries("甲乙丙"), .016) }
        assertFalse(control.pending(turn))
    }

    @Test fun terminalFeedbackFollowsTheOnceLiveReplyAndCannotBeRearmedByHistory() {
        val live = ChatTurnUi.Assistant("answer", status = app.daiyu.android.domain.RunStatus.RUNNING)
        val done = live.copy(status = app.daiyu.android.domain.RunStatus.COMPLETED, finalText = "正文")
        val control = ReplyPresentationControl(live.listKey, true)
        control.updateFeedback(live)
        control.updateFeedback(done)
        assertTrue(control.permitsTerminalFeedback(done))
        assertFalse(control.permitsTerminalFeedback(done.copy(listKey = "older")))
        control.pause(true); assertFalse(control.permitsTerminalFeedback(done)); control.pause(false)
        control.reportPending(Any(), true) // stale lane survives the final draw
        control.setSettled(done, true) // no parent recomposition between settlement and motor callback
        assertFalse(control.permitsTerminalFeedback(done))
        control.reportPending(Any(), true)
        assertFalse(control.permitsTerminalFeedback(done))
        val historical = ReplyPresentationControl(done.listKey, false)
        historical.reportPending(Any(), true); historical.updateFeedback(done)
        assertFalse(historical.permitsTerminalFeedback(done))
    }

    @Test fun slowCharactersPulseSeparatelyAndFastOutputCannotBuildUpDebt() {
        var time = 0L
        var next: Pair<Runnable, Long>? = null
        val pulses = mutableListOf<Long>()
        val pacer = ReplyCharacterTicks({ time }, { r, at -> assertNull(next); next = r to at }, { next = null }) { pulses += time }
        fun fire() { val (r, at) = checkNotNull(next); next = null; time = maxOf(time, at); r.run() }
        pacer.offer(1); fire(); time = 120; pacer.offer(1); fire()
        time = 250; repeat(300) { pacer.offer(10) }; fire(); fire(); fire(); assertNull(next)
        assertEquals(listOf(0L, 120L, 250L, 300L, 350L), pulses)
        pacer.offer(20); time += 500; fire(); assertNull(next); assertEquals(5, pulses.size)
        pacer.offer(2); pacer.close(); assertNull(next)
    }

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
        fun frame(target: MarkdownTypingTarget, millis: Long, limit: Int = target.total): Int {
            now = millis
            return motion.advance(target, millis * 1_000_000L, limit) { advances++; engine.visibleAdvance(owner) }
        }
    }

    @Test fun frameGrowthUsesExistingGlobalThrottleWithoutNetworkDoubleTicks() {
        val f = Fixture()
        val target = f.target("x".repeat(200))
        f.engine.backgroundOutput(f.owner, 36)
        assertTrue(f.requests.isEmpty())
        assertEquals(0, f.frame(target, 1000, 0))
        assertTrue(f.requests.isEmpty())
        assertEquals(0, f.advances)
        f.frame(target, 1016); assertEquals(1, f.requests.size)
        f.frame(target, 1032); assertEquals(1, f.requests.size)
        f.frame(target, 1048); assertEquals(2, f.requests.size)
        assertEquals(3, f.advances)
        assertEquals(2, f.requests.size)
        assertTrue(f.requests.all { it.kind == HapticKind.TICK })
        f.engine.drain()
        assertEquals(2, f.requests.size)
    }

    @Test fun terminalArrivalTypesWithOneFeedbackPerVisibleFrame() {
        val f = Fixture()
        val streaming = f.target("x".repeat(200))
        assertEquals(1, f.frame(streaming, 1000))
        val terminal = f.target("x".repeat(220), streaming = false, terminal = true)
        var shown = f.motion.visible(terminal)
        var time = 1016L
        while (shown < terminal.total && time < 5000L) {
            val before = f.advances
            val next = f.frame(terminal, time)
            assertEquals(before + if (next > shown) 1 else 0, f.advances)
            assertTrue(next in shown..terminal.total)
            shown = next
            time += 16
        }
        assertEquals(terminal.total, shown)
        assertTrue(f.advances > 1)
        val done = f.requests.size
        val advances = f.advances
        assertEquals(terminal.total, f.frame(terminal, time + 1000))
        assertEquals(advances, f.advances)
        f.engine.drain()
        assertEquals(done, f.requests.size)
    }

    @Test fun terminalVisibleChunksUseTheSameGlobalThrottleAndSettings() {
        val f = Fixture()
        f.motion.update(emptyList(), 0, streaming = false, terminal = false, live = true)
        val target = f.motion.update(listOf(MarkdownTypingPart(MarkdownTypingSource("final", "字".repeat(500)), 500)),
            0, streaming = false, terminal = true, paceTerminal = true)!!
        f.frame(target, 1000, 0)
        var admitted = 0
        for (time in 1016L..1320L step 16L) f.frame(target, time, ++admitted)
        val before = f.requests.size
        assertTrue(f.motion.visible(target) in 1..admitted)
        for (time in 1336L..2000L step 16L) f.frame(target, time, ++admitted)
        assertTrue(f.requests.size > before + 10)
        assertTrue(f.motion.visible(target) in 1..admitted)
        assertTrue(f.requests.all { it.kind == HapticKind.TICK })
        f.engine.updateSettings(HapticSettings(generation = false))
        val muted = f.requests.size
        for (time in 2016L..2200L step 16L) f.frame(target, time, ++admitted)
        assertEquals(muted, f.requests.size)
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
        f.frame(target, 1000, 0); f.frame(target, 1050)
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
        f.frame(target, 1000, 0)
        f.engine.lifecycle(f.owner, resumed = true, visible = false)
        f.frame(target, 1050, 1)
        f.engine.lifecycle(f.owner, resumed = false, visible = true)
        f.frame(target, 1100, 2)
        f.engine.lifecycle(f.owner, resumed = true, visible = true)
        f.engine.setForeground(false); f.frame(target, 1150, 3)
        f.engine.setForeground(true); f.engine.detach(f.owner); f.frame(target, 1200, 4)
        assertEquals(0, f.requests.size)
    }
}
