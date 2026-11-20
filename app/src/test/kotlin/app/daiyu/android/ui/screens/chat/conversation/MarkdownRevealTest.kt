package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.MarkdownParser
import androidx.compose.runtime.mutableStateOf
import org.junit.Assert.*
import org.junit.Test

class MarkdownRevealTest {
    private fun drain(state: MarkdownTypingState, target: MarkdownTypingTarget, time: Long = 1_000_000_000L,
        limit: Int = target.total, onAdvance: () -> Unit = {}): Int {
        var previous = state.visible(target)
        repeat(10_000) { frame ->
            val shown = state.advance(target, time + frame * 16_000_000L, limit, onAdvance)
            assertTrue("Frontier must stay within received admission", shown in previous..maxOf(previous, limit))
            if (shown == limit) return shown
            previous = shown
        }
        fail("Received output did not finish: $previous/$limit")
        return previous
    }

    private fun receiptPhaseFixture(): Pair<MarkdownTypingState, MarkdownTypingTarget> {
        val state = MarkdownTypingState(emptyList())
        state.update(emptyList(), 0, true, false)
        state.receive(listOf(source("x".repeat(5))), 1_697_000_000L)
        val first = state.update(listOf(part("x".repeat(5))), 0, true, false)!!
        assertEquals(5, drain(state, first, 1_800_000_000L))
        state.receive(listOf(source("x".repeat(13))), 2_177_000_000L)
        val next = state.update(listOf(part("x".repeat(13))), 0, true, false)!!
        assertEquals(5, next.start)
        return state to next
    }

    @Test fun completedTargetReceiptRetainsOnlyItsFirstActiveFraction() {
        for (repeatUpdate in listOf(false, true)) {
            val (state, initial) = receiptPhaseFixture()
            var target = initial
            if (repeatUpdate) {
                state.receive(listOf(source("x".repeat(13))), 2_200_000_000L)
                assertSame(target, state.update(null, null, true, false))
                target = state.update(listOf(part("x".repeat(13))), 0, false, true)!!
            }
            var callbacks = 0
            val shown = (32..59).map { frame ->
                val time = 2_208_000_000L + (frame - 32) * 16_000_000L
                val value = state.advance(target, time) { callbacks++ }
                val before = callbacks
                assertEquals(value, state.advance(target, time) { callbacks++ })
                assertEquals(before, callbacks)
                value
            }
            assertEquals(6, shown[0])
            assertEquals(6, shown[1])
            assertEquals(7, shown[2])
            assertEquals(12, shown[56 - 32])
            assertEquals(13, shown[57 - 32])
            assertEquals(13, shown.last())
            assertTrue(shown.zipWithNext().all { (a, b) -> b - a in 0..1 })
            assertEquals(8, callbacks)
        }
    }

    @Test fun holdingAnUnstartedReceiptNeverAccumulatesHeldTime() {
        fun trace(wait: Long): List<Int> {
            val (state, target) = receiptPhaseFixture()
            state.hold(target, 2_180_000_000L)
            state.hold(target, 2_180_000_000L + wait)
            assertEquals(5, state.visible(target))
            return (0..4).map { state.advance(target, 2_181_000_000L + wait + it * 16_000_000L) }
        }
        val expected = listOf(6, 6, 6, 6, 7)
        assertEquals(expected, trace(16_000_000L))
        assertEquals(expected, trace(60_000_000_000L))
    }

    @Test fun pauseAndPausedReceiptDiscardThePendingReceiptPhase() {
        for (pauseViaReceipt in listOf(false, true)) for (wait in listOf(1_000_000L, 60_000_000_000L)) {
            val (state, target) = receiptPhaseFixture()
            val resumedAt = 2_190_000_000L + wait
            if (pauseViaReceipt) {
                state.receive(listOf(source("x".repeat(13))), 2_190_000_000L, paused = true)
                state.receive(listOf(source("x".repeat(13))), resumedAt)
            } else state.pause(target)
            val shown = (0..4).map { state.advance(target, resumedAt + it * 16_000_000L) }
            // Paused receipt retains the active rate; explicit pause keeps its existing rate smoothing.
            val expected = if (pauseViaReceipt) listOf(6, 6, 7, 8, 8) else listOf(6, 6, 7, 7, 8)
            assertEquals(expected, shown)
            assertTrue(shown.zipWithNext().all { (a, b) -> b - a in 0..1 })
        }
    }

    @Test fun receiptPhaseClampsAndDiscardsWholeGraphemeCredit() {
        for (delay in listOf(0L, 60_000_000_000L)) {
            val state = MarkdownTypingState(emptyList())
            state.update(emptyList(), 0, true, false)
            val cluster = "e\u0301"
            state.receive(listOf(source(cluster.repeat(10))), 1_000_000_000L)
            state.receive(listOf(source(cluster.repeat(20))), 1_200_000_000L)
            val target = state.update(listOf(part(cluster.repeat(20))), 0, true, false)!!
            val time = 2_000_000_000L + delay
            assertEquals(2, state.advance(target, time))
            assertEquals(2, state.advance(target, time + 5_000_000L))
            assertEquals(4, state.advance(target, time + 10_000_000L))
            assertEquals(40, drain(state, target, time + 26_000_000L))
            val next = state.update(listOf(part(cluster.repeat(22))), 0, true, false)!!
            assertEquals(42, state.advance(next, time + 10_000_000_000L))
            assertEquals(42, state.advance(next, time + 10_005_000_000L))
            assertEquals(44, state.advance(next, time + 10_020_000_000L))
        }
    }

    @Test fun initialBaselineAndReplacementCannotBorrowAnOldReceiptPhase() {
        val cold = MarkdownTypingState(listOf(source("xxxxx")))
        cold.receive(listOf(source("x".repeat(13))), 1_000_000_000L)
        val first = cold.update(listOf(part("x".repeat(13))), 5, true, false)!!
        assertEquals(5, first.start)
        assertEquals(6, cold.advance(first, 2_000_000_000L))
        assertEquals(6, cold.advance(first, 2_011_000_000L))
        val (state, _) = receiptPhaseFixture()
        val replacement = "replacement"
        state.receive(listOf(source(replacement)), 2_190_000_000L)
        val replaced = state.update(listOf(part(replacement)), 0, true, false)!!
        assertEquals(replacement.length, state.visible(replaced))
        state.receive(listOf(source(replacement + "xx")), 2_200_000_000L)
        val next = state.update(listOf(part(replacement + "xx")), 0, true, false)!!
        assertEquals(replacement.length + 1, state.advance(next, 2_210_000_000L))
        assertEquals(replacement.length + 1, state.advance(next, 2_240_000_000L))
        assertEquals(replacement.length + 2, state.advance(next, 2_270_000_000L))
    }

    @Test fun streamingTypesInsideEveryReceivedChunkWithoutInventingOutput() {
        for (frameMillis in listOf(8L, 16L)) for (length in listOf(500, 5_000)) {
            val state = MarkdownTypingState(emptyList())
            state.update(emptyList(), 0, streaming = true, terminal = false)
            var time = 1_000_000_000L
            var previous = 0
            for (received in listOf(7, 83, length)) {
                val target = state.update(listOf(part("字".repeat(received))), 0, true, false)!!
                var partial = false
                var shown = previous
                repeat(maxOf(100, (received - previous) * 4)) {
                    time += frameMillis * 1_000_000L
                    val next = state.advance(target, time)
                    assertTrue(next in shown..received)
                    assertTrue("At most one complete BMP character per frame", next - shown in 0..1)
                    if (next in previous + 1 until received) partial = true
                    shown = next
                }
                assertTrue("Each received batch must actually type", partial)
                assertEquals(received, shown)
                var advances = 0
                assertEquals(received, state.advance(target, time + 10_000_000_000L) { advances++ })
                assertEquals("Time alone cannot fabricate received output", 0, advances)
                time += 10_000_000_000L
                previous = received
            }
        }
    }

    @Test fun streamingPauseAndTerminalHandoffRetainTheAdmittedFrontier() {
        val state = MarkdownTypingState(emptyList())
        state.update(emptyList(), 0, streaming = true, terminal = false)
        val parts = listOf(part("字".repeat(500)))
        val streaming = state.update(parts, 0, streaming = true, terminal = false)!!
        assertEquals(67, drain(state, streaming, limit = 67))
        state.pause(streaming)
        assertEquals(67, state.visible(streaming))
        assertEquals(67, state.advance(streaming, 11_000_000_000L, 67))
        val resumed = state.advance(streaming, 11_016_000_000L, 83)
        assertTrue("Resume starts at the current frontier, not at the whole admission", resumed in 68 until 83)
        val terminal = state.update(parts, 0, streaming = false, terminal = true, paceTerminal = true)!!
        assertEquals(resumed, terminal.start)
        assertEquals("Completion cannot bypass layout admission", resumed,
            state.advance(terminal, 21_000_000_000L, resumed))
        assertEquals(terminal.total, drain(state, terminal, 21_016_000_000L))
    }

    @Test fun queuedFrameSeesPauseBeforeRecompositionAndResumeDoesNotCatchUp() {
        val control = ReplyPresentationControl(null, initiallyLive = true)
        val playback = ReplyPlayback(control, runtimePaused = false)
        val currentPlayback = mutableStateOf<ReplyPlayback?>(playback)
        val currentCanAdvance = mutableStateOf(true)
        val state = MarkdownTypingState(listOf(source("")))
        state.update(listOf(part("")), 0, streaming = true, terminal = false)
        val target = state.update(listOf(part("字".repeat(200))), 0, streaming = false, terminal = true, paceTerminal = true)!!
        var admitted = 7
        var advances = 0
        val queuedFrame: (Long) -> Int = { nanos ->
            if (markdownTypingCanAdvance(currentCanAdvance.value, currentPlayback.value)) {
                state.advance(target, nanos, admitted) { advances++ }
            } else {
                state.pause(target)
                state.visible(target)
            }
        }
        assertEquals(7, drain(state, target, limit = 7))
        val before = advances
        control.pause(true)
        assertTrue(currentCanAdvance.value)
        assertSame(playback, currentPlayback.value)
        admitted = 19
        assertEquals(7, queuedFrame(11_116_000_000L))
        assertEquals(7, queuedFrame(21_116_000_000L))
        assertEquals(before, advances)
        control.pause(false)
        assertEquals(8, queuedFrame(31_116_000_000L))
        assertTrue("The next resumed frame must not rush the paused backlog", queuedFrame(31_132_000_000L) < 19)
    }

    @Test fun pausedAndLayoutBlockedTimeCannotChangeTheResumedPace() {
        fun resumed(delay: Long): List<Int> {
            val state = MarkdownTypingState(emptyList())
            state.update(emptyList(), 0, true, false)
            val target = state.update(listOf(part("字".repeat(500))), 0, true, false)!!
            state.advance(target, 1_000_000_000L)
            state.advance(target, 1_016_000_000L)
            state.pause(target)
            val appended = state.update(listOf(part("字".repeat(5_000))), 0, true, false)!!
            return (0..4).map { state.advance(appended, 1_032_000_000L + delay + it * 16_000_000L) }
        }
        val short = resumed(0L)
        assertEquals(short, resumed(60_000_000_000L))
        assertTrue(short.zipWithNext().all { (a, b) -> b - a in 0..1 })
        assertTrue(short.last() < 500)
    }

    @Test fun frameGatePreservesDisabledRuntimePausedAndExplicitFinishSemantics() {
        assertTrue(markdownTypingCanAdvance(true, null))
        assertFalse(markdownTypingCanAdvance(false, null))
        val control = ReplyPresentationControl(null, initiallyLive = true)
        val playback = ReplyPlayback(control, runtimePaused = false)
        assertTrue(markdownTypingCanAdvance(true, playback))
        assertFalse(markdownTypingCanAdvance(false, playback))
        control.pause(true)
        assertFalse(markdownTypingCanAdvance(true, playback))
        control.pause(false)
        assertTrue(markdownTypingCanAdvance(true, playback))
        val runtimePaused = playback.copy(runtimePaused = true)
        assertFalse(markdownTypingCanAdvance(true, runtimePaused))
        control.finish()
        assertTrue(markdownTypingCanAdvance(true, runtimePaused))
        assertFalse(markdownTypingCanAdvance(false, runtimePaused))
    }

    @Test fun finalBudgetWaitsForItsOwnPlacedSourceAndPendingLeaves() {
        val motion = MarkdownTypingState(emptyList())
        val target = motion.update(listOf(part("final")), 0, false, true)!!
        val own = MarkdownPaintPublication().apply { active = true }
        val unrelated = MarkdownPaintPublication().apply { active = true }
        val leaf = Any()
        assertFalse(own.settled(target))
        own.presented("final", source("old"))
        assertFalse(own.settled(target))
        own.setPending(leaf, true)
        own.presented("final", source("final"))
        assertFalse(own.settled(target))
        unrelated.setPending(Any(), true)
        own.setPending(leaf, false)
        assertTrue(own.settled(target))
        val appended = motion.update(listOf(part("final next")), 0, false, true)!!
        assertFalse(own.settled(appended))
        own.presented("final", source("final next"))
        assertTrue(own.settled(appended))
    }

    private fun source(text: String, id: String = "final") = MarkdownTypingSource(id, text)
    private fun part(text: String, id: String = "final") = MarkdownTypingPart(source(text, id), text.length)

    @Test fun extendingAnAlreadyDisplayedClusterCannotEraseItsBaseCharacter() {
        assertEquals(1, MarkdownTextBoundaries("e").end(1))
        assertEquals(2, MarkdownTextBoundaries("e\u0301tail").end(1))
        assertEquals("e\u0301", "e\u0301tail".take(MarkdownTextBoundaries("e\u0301tail").end(1)))
        assertEquals(0, MarkdownTextBoundaries("e\u0301tail").end(0))
    }

    @Test fun entityCollapseRemapsTheFrontierWithoutRevealingNewSuffixes() {
        val state = MarkdownTypingState(listOf(source("")))
        state.update(listOf(part("")), 0, true, false)
        val old = state.update(listOf(MarkdownTypingPart(source("&amp"), 4, "&amp")), 0, true, false)!!
        assertEquals(3, drain(state, old, limit = 3))
        val next = state.update(listOf(MarkdownTypingPart(source("&amp;XYZ"), 4, "&XYZ")), 0, true, false)!!
        assertEquals(1, next.start)
        assertEquals("&", next.parts.single().displayed.take(next.start))
    }

    @Test fun terminalLiteralRestorationPreservesTheAlreadyVisibleContent() {
        val raw = "**字"
        val state = MarkdownTypingState(listOf(source(raw)))
        state.update(listOf(MarkdownTypingPart(source(raw), 1, "字")), 1, true, false)
        val next = state.update(listOf(MarkdownTypingPart(source(raw), 3, raw)), 3, false, true)!!
        assertEquals(3, next.start)
    }

    @Test fun displayTextAndBudgetUseIdenticalCoordinatesForEveryBlockType() {
        for (source in listOf("", "text **bold**", "- [x] one\n- two", "> quote\n>\n> ---", "```\n\n```", "a | b\n--- | ---\nx |", "---", "# title")) {
            val blocks = MarkdownParser.parse(source)
            assertEquals(source, markdownBlocksLength(blocks), markdownBlocksDisplayText(blocks).length)
        }
    }

    @Test fun blockedBodyKeepsThePrecedingTextVisibleUntilItsAdmissionChanges() {
        val state = MarkdownTypingState(emptyList())
        state.update(emptyList(), 0, true, false)
        val target = state.update(listOf(part("note", "note"), part("x".repeat(500), "answer")), 0, false, true)!!
        assertEquals(4, drain(state, target, limit = 4))
        assertEquals(4, state.advance(target, 1_250_000_000L, 4))
        assertEquals(4, state.visible(target))
        assertEquals(0, target.limit("answer", state.visible(target)))
        state.pause(target)
        assertEquals(4, state.advance(target, 9_000_000_000L, 4))
        assertEquals(12, drain(state, target, 9_016_000_000L, 12))
        assertEquals(8, target.limit("answer", state.visible(target)))
        assertEquals(target.total, drain(state, target, 10_000_000_000L))
    }

    @Test fun existingMessagesAndRemountsAreVisibleImmediately() {
        for (streaming in listOf(false, true)) {
            val state = MarkdownTypingState(listOf(source("existing")))
            val target = state.update(listOf(part("existing")), 8, streaming, !streaming)!!
            assertEquals(8, target.start)
            assertEquals(8, target.limit("final", target.start))
        }
    }

    @Test fun resumedBaselineUsesCompletedPrefixBeforeTheLatestParseFinishes() {
        val prefix = "Streaming **body**.\n\nNew paragraph."
        val latest = prefix + "\n\nLatest paragraph."
        val parsedPrefix = MarkdownParser.parse(prefix)
        val projection = MarkdownProjection(listOf(prefix), mapOf(prefix to parsedPrefix), MarkdownParser::parse)
        val budget = markdownBlocksLength(parsedPrefix)
        assertEquals(budget, markdownTypingBaselineLength(projection, listOf(latest), allowPrefix = true))
        val state = MarkdownTypingState(listOf(source(latest)))
        state.receive(listOf(source(latest)), 1_000_000_000L)
        val prefixPart = MarkdownTypingPart(source(prefix), budget, markdownBlocksDisplayText(parsedPrefix))
        val target = state.update(listOf(prefixPart),
            markdownTypingBaselineLength(projection, listOf(latest), allowPrefix = true), true, false)!!
        assertEquals(budget, target.start)
        assertEquals(budget, target.limit("final", target.start))
        val parsedLatest = MarkdownParser.parse(latest)
        val latestProjection = MarkdownProjection(listOf(latest), mapOf(latest to parsedLatest), MarkdownParser::parse)
        val next = state.update(listOf(MarkdownTypingPart(source(latest), markdownBlocksLength(parsedLatest),
            markdownBlocksDisplayText(parsedLatest))),
            markdownTypingBaselineLength(latestProjection, listOf(latest), allowPrefix = true), true, false)!!
        assertEquals(budget, next.start)
        assertTrue(next.start < next.total)
        state.receive(listOf(source(latest)), 30_000_000_000L)
        assertEquals(budget + 1, state.advance(next, 31_000_000_000L))
        assertEquals(next.total, drain(state, next, 31_016_000_000L))
    }

    @Test fun completedPrefixCannotReplaceAnOlderFrozenBaselineOrUnrelatedText() {
        val text = "existing"
        val projection = MarkdownProjection(listOf(text), mapOf(text to MarkdownParser.parse(text)), MarkdownParser::parse)
        assertNull(markdownTypingBaselineLength(projection, listOf("existing new"), allowPrefix = false))
        assertNull(markdownTypingBaselineLength(projection, listOf("replacement"), allowPrefix = true))
        assertNull(markdownTypingBaselineLength(projection, emptyList(), allowPrefix = true))
        assertEquals(text.length, markdownTypingBaselineLength(projection, listOf(text), allowPrefix = false))
        assertNull(markdownTypingBaselineLength(null, listOf(text), allowPrefix = true))
    }

    @Test fun resumedMixedSourcesRetainEachCompletedPartBudget() {
        val texts = listOf("first", "second")
        val projection = MarkdownProjection(texts, texts.associateWith(MarkdownParser::parse), MarkdownParser::parse)
        val baseline = listOf("first", "second new", "third")
        assertEquals(11, markdownTypingBaselineLength(projection, baseline, allowPrefix = true))
        val state = MarkdownTypingState(baseline.mapIndexed { i, text -> source(text, "item-$i") })
        val parts = texts.mapIndexed { i, text -> part(text, "item-$i") }
        val target = state.update(parts, markdownTypingBaselineLength(projection, baseline, allowPrefix = true), true, false)!!
        assertEquals(5, target.limit("item-0", target.start))
        assertEquals(6, target.limit("item-1", target.start))
        assertEquals(0, target.limit("item-2", target.start))
        assertNull(markdownTypingBaselineLength(projection, listOf("changed", "second new"), allowPrefix = true))
    }

    @Test fun firstReconnectWindowIsNewOutputRatherThanAReplacement() {
        val text = "字".repeat(500)
        val window = app.daiyu.android.MarkdownSourceWindow(text, 0, text.length)
        val source = MarkdownTypingSource("answer", text, window)
        val state = MarkdownTypingState(emptyList())
        state.update(emptyList(), 0, streaming = false, terminal = false, live = true)
        val answer = state.update(listOf(MarkdownTypingPart(source, text.length)), 0,
            streaming = false, terminal = true, paceTerminal = true)!!
        assertEquals(0, answer.start)
        assertEquals(0, state.visible(answer))
        assertTrue(state.advance(answer, 1_000_000_000L) in 1 until text.length)
        assertEquals(text.length, drain(state, answer, 1_016_000_000L))
    }

    @Test fun reconnectWindowCompletionKeepsThePartiallyRevealedBudget() {
        val text = "字".repeat(500)
        val window = app.daiyu.android.MarkdownSourceWindow(text, 20, 400, streaming = true)
        val source = MarkdownTypingSource("answer", window.displaySource(), window)
        val state = MarkdownTypingState(emptyList())
        state.update(emptyList(), 0, streaming = true, terminal = false)
        val running = state.update(listOf(MarkdownTypingPart(source, source.text.length)), 0,
            streaming = true, terminal = false)!!
        val visible = state.advance(running, 1_000_000_000L, 37)
        assertTrue(visible in 1 until running.total)
        val completed = source.copy(window = window.copy(streaming = false))
        assertFalse("The parser must still invalidate its streaming AST", markdownWindowsAppend(listOf(window), listOf(completed.window)))
        val terminal = state.update(listOf(MarkdownTypingPart(completed, completed.text.length)), 0,
            streaming = false, terminal = true, paceTerminal = true)!!
        assertEquals(visible, terminal.start)
        assertEquals("Completion cannot bypass the admitted frontier", visible,
            state.advance(terminal, 1_100_000_000L, visible))
        assertEquals(terminal.total, drain(state, terminal, 1_116_000_000L))
    }

    @Test fun changedReconnectRangesOrContentRemainReplacements() {
        val window = app.daiyu.android.MarkdownSourceWindow("same text", 0, 9, streaming = true)
        val before = MarkdownTypingSource("answer", window.displaySource(), window)
        assertFalse(markdownTypingAppends(listOf(before), listOf(before.copy(window = window.copy(start = 1)))))
        assertFalse(markdownTypingAppends(listOf(before), listOf(before.copy(window = window.copy(end = 8)))))
        assertFalse(markdownTypingAppends(listOf(before), listOf(before.copy(text = "replacement"))))
        assertFalse(markdownTypingAppends(listOf(before), listOf(before.copy(window = null))))
    }

    @Test fun newBodyAnimatesAndNewTargetsKeepProgress() {
        val state = MarkdownTypingState(listOf(source("")))
        state.update(listOf(part("")), 0, true, false)
        val target = state.update(listOf(part("x".repeat(200))), 0, true, false)!!
        assertEquals(0, target.start)
        val shown = state.advance(target, 1_000_000_000, 37)
        assertTrue(shown in 1 until target.total)
        val next = state.update(listOf(part("x".repeat(300))), 0, true, false)!!
        assertEquals(shown, next.start)
        assertEquals(target.start, state.advance(target, 1_200_000_000))
        assertTrue(state.advance(next, 1_150_000_000) >= shown)
    }

    @Test fun aReasoningBlockedPrefixStaysOpenUntilTheTerminalAnswerIsAdmitted() {
        val state = MarkdownTypingState(emptyList())
        state.update(emptyList(), 0, streaming = true, terminal = false)
        state.update(emptyList(), 0, streaming = false, terminal = false)
        val admitted = state.update(listOf(part("answer")), 0, streaming = false, terminal = true)!!
        assertEquals(0, admitted.start)
        state.advance(admitted, 1_000_000_000)
        assertEquals(6, drain(state, admitted, 1_016_000_000L))
    }

    @Test fun liveBodyNeedNotObserveAStreamingPhaseToRevealItsFirstAnswer() {
        val state = MarkdownTypingState(emptyList())
        state.update(emptyList(), 0, streaming = false, terminal = false, live = true)
        val answer = state.update(listOf(part("x".repeat(500))), 0, streaming = false, terminal = true)!!
        assertEquals(0, answer.start)
        var advances = 0
        assertTrue(state.advance(answer, 1_000_000_000L) { advances++ } in 1 until answer.total)
        assertEquals(answer.total, drain(state, answer, 1_016_000_000L) { advances++ })
        assertTrue("New terminal output must have multiple visible advances", advances > 1)
        val completed = advances
        assertEquals(answer.total, state.advance(answer, 2_000_000_000L) { advances++ })
        assertEquals(completed, advances)
        val edited = state.update(listOf(part("x".repeat(501))), 0, streaming = false, terminal = true)!!
        assertEquals("Terminal appends must type their unseen suffix", 500, edited.start)
        assertEquals(501, drain(state, edited, 20_000_000_000L))
    }

    @Test fun emptyTerminalCannotCloseALiveBodyBeforeItsFirstAnswer() {
        val state = MarkdownTypingState(emptyList())
        state.update(emptyList(), 0, streaming = true, terminal = false)
        state.update(emptyList(), 0, streaming = false, terminal = true)
        val answer = state.update(listOf(part("answer")), 0, streaming = false, terminal = true)!!
        assertEquals(0, answer.start)
    }

    @Test fun liveEvidenceSurvivesAnUnavailableProjectionAndDoesNotReplayHistory() {
        val live = MarkdownTypingState(emptyList())
        live.update(null, null, streaming = false, terminal = false, live = true)
        val answer = live.update(listOf(part("answer")), 0, streaming = false, terminal = true)!!
        assertEquals(0, answer.start)
        val history = MarkdownTypingState(emptyList())
        history.update(emptyList(), 0, streaming = false, terminal = true)
        val unseen = history.update(listOf(part("answer")), 0, streaming = false, terminal = true)!!
        assertEquals("An empty baseline has no displayed suffix", 0, unseen.start)
        assertEquals(6, drain(history, unseen))
    }

    @Test fun terminalBodyFollowsAdmittedChunksWithoutASyntheticDrainDeadline() {
        for (terminal in listOf(false, true)) {
            val state = MarkdownTypingState(emptyList())
            state.update(emptyList(), 0, streaming = false, terminal = false, live = true)
            val answer = state.update(listOf(part("字".repeat(500))), 0, streaming = false, terminal = terminal,
                paceTerminal = true)!!
            var advances = 0
            assertEquals(17, drain(state, answer, limit = 17) { advances++ })
            state.pause(answer)
            assertEquals(17, state.visible(answer))
            assertEquals(17, state.advance(answer, 11_000_000_000L, 17) { advances++ })
            assertTrue(state.advance(answer, 11_016_000_000L, 83) { advances++ } in 18 until 83)
            assertEquals(83, drain(state, answer, 11_032_000_000L, 83) { advances++ })
            assertEquals(answer.total, drain(state, answer, 12_000_000_000L) { advances++ })
            assertTrue(advances > 3)
            state.update(answer.parts, 0, streaming = false, terminal = true, paceTerminal = true)
            val edited = state.update(listOf(part("字".repeat(501))), 0, streaming = false, terminal = true,
                paceTerminal = true)!!
            assertEquals("A newly received terminal append keeps the current frontier", 500, edited.start)
            assertEquals(501, drain(state, edited, 20_000_000_000L))
        }
    }

    @Test fun lateTerminalTargetsDrainToTheirExactEnd() {
        val state = MarkdownTypingState(listOf(source("")))
        state.update(listOf(part("")), 0, true, false)
        val first = state.update(listOf(part("x".repeat(200))), 0, true, false)!!
        state.advance(first, 1_000_000_000, 37)
        state.update(null, null, false, true)
        val terminal = state.update(listOf(part("x".repeat(300))), 0, false, true)!!
        assertTrue(terminal.start < terminal.total)
        state.advance(terminal, 2_000_000_000)
        assertEquals(300, drain(state, terminal, 2_016_000_000L))
        val edited = state.update(listOf(part("x".repeat(301))), 0, false, true)!!
        assertEquals(300, edited.start)
        assertEquals(301, drain(state, edited, 20_000_000_000L))
    }

    @Test fun replacementsAndReordersNeverBorrowProgress() {
        assertFalse(markdownTypingAppends(listOf(source("a", "one"), source("b", "two")),
            listOf(source("b", "two"), source("a", "one"))))
        val state = MarkdownTypingState(listOf(source("")))
        state.update(listOf(part("aaaa")), 0, true, false)
        val replacement = state.update(listOf(part("new")), 0, true, false)!!
        assertEquals(3, replacement.start)
        val mixed = MarkdownTypingState(listOf(source("", "final")))
        val next = mixed.update(listOf(part("first", "item-1"), part("second", "item-2")), 0, true, false)!!
        assertEquals(3, next.limit("item-1", 3))
        assertEquals(0, next.limit("item-2", 3))
        assertEquals(2, next.limit("item-2", 7))
    }

    @Test fun saturatedReceiptsNeverAdvanceMoreThanOneCharacterPerFrame() {
        fun shown(frame: Long): Int {
            val state = MarkdownTypingState(listOf(source("")))
            val target = state.update(listOf(part("x".repeat(10_000))), 0, true, false)!!
            var time = 1_000_000_000L
            var visible = state.advance(target, time)
            while (time < 1_060_000_000L) {
                time = minOf(time + frame, 1_060_000_000L)
                val next = state.advance(target, time)
                assertTrue(next - visible in 0..1)
                visible = next
            }
            return visible
        }
        val slow = shown(16_666_667)
        val fast = shown(8_333_333)
        assertTrue(slow in 1 until 10_000)
        assertTrue("Higher refresh can admit more characters but never a larger frame", fast > slow)
    }

    @Test fun everyPrefixIsSurrogateAndCombiningBoundarySafe() {
        val text = "A😀𠮷e\u0301Z"
        val boundaries = MarkdownTextBoundaries(text)
        for (limit in 0..text.length) {
            val end = boundaries.end(limit)
            assertTrue(end in limit..text.length)
            assertFalse(end in 1 until text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate())
            assertFalse(end < text.length && text[end] == '\u0301')
        }
        assertEquals(listOf(0, 1, 3, 3, 5, 5, 7, 7, 8), (0..text.length).map { boundaries.end(it) })
        assertEquals(text.length, boundaries.end(Int.MAX_VALUE))
    }

    @Test fun astBudgetExcludesSyntaxAndIncludesEmptyStructures() {
        assertEquals(4, markdownBlocksLength(MarkdownParser.parse("**😀ok**")))
        assertEquals(1, markdownBlocksLength(MarkdownParser.parse("---")))
        assertEquals(1, markdownBlocksLength(MarkdownParser.parse("```\n```")))
        assertEquals(0, markdownBlocksLength(MarkdownParser.parse("")))
    }
    @Test fun wideTableCellOffsetsPreserveTheLastGroupWhenARowIsAppended() {
        val header = (1..33).joinToString("|") { "h" }
        val separator = (1..33).joinToString("|") { "---" }
        val row = (1..33).joinToString("|") { "r" }
        val old = MarkdownParser.parse("$header\n$separator\n$row").single() as app.daiyu.android.MarkdownBlock.Table
        val next = MarkdownParser.parse("$header\n$separator\n$row\n$row").single() as app.daiyu.android.MarkdownBlock.Table
        val before = markdownTableCellOffsets(old)
        val after = markdownTableCellOffsets(next)
        assertEquals(before, after.take(before.size))
        assertEquals(32, after[0][32])
        assertEquals(65, after[1][32])
        assertEquals(98, after[2][32])
        assertTrue(66 > after[1][32])
        assertTrue(66 <= after[2][0])
    }


    @Test fun explicitPresentationFinishRevealsOnlyTheCurrentTarget() {
        val state = MarkdownTypingState(emptyList())
        state.update(emptyList(), 0, streaming = false, terminal = false, live = true)
        val old = state.update(listOf(part("字".repeat(100))), 0, streaming = false,
            terminal = true, paceTerminal = true)!!
        state.advance(old, 1_000_000_000L, 37)
        val current = state.update(listOf(part("字".repeat(200))), 0, streaming = false,
            terminal = true, paceTerminal = true)!!
        val before = state.visible(current)
        state.finish(old)
        assertEquals(before, state.visible(current))
        assertEquals(current.total, state.finish(current))
        assertEquals(current.total, state.visible(current))
        assertEquals(current.total, state.advance(current, 20_000_000_000L))
    }

    @Test fun paintAdmissionBlocksOnlyItsOwnerAndCanReleaseWithoutAnotherTarget() {
        val publication = MarkdownPaintPublication()
        val first = Any()
        val second = Any()
        assertTrue(publication.ready)
        publication.setPending(first, true)
        publication.setPending(second, true)
        assertFalse(publication.ready)
        publication.setPending(first, false)
        assertFalse(publication.ready)
        publication.setPending(second, false)
        assertTrue(publication.ready)
    }


    @Test fun rawReceiptRateSurvivesMergedAndDelayedParsing() {
        fun reveal(parseDelay: Long, repeatReceipt: Boolean): List<Int> {
            val state = MarkdownTypingState(emptyList())
            state.update(emptyList(), 0, true, false)
            for (count in 1..3) state.receive(listOf(source("字".repeat(count * 100))), count * 1_000_000_000L)
            if (repeatReceipt) state.receive(listOf(source("字".repeat(300))), 90_000_000_000L)
            val target = state.update(listOf(part("字".repeat(300))), 0, true, false)!!
            val time = 10_000_000_000L + parseDelay
            return (0..10).map { frame -> state.advance(target, time + frame * 16_000_000L) }
        }
        val immediate = reveal(0L, false)
        assertEquals(1, immediate.first())
        assertEquals("Saturated receipt rates admit one character per frame", 11, immediate.last())
        assertEquals(immediate, reveal(30_000_000_000L, true))
    }

    @Test fun rawReceiptIntervalsChangeThePaceWithoutRevealingTheWholeBatch() {
        fun frontier(interval: Long): Int {
            val state = MarkdownTypingState(emptyList())
            state.update(emptyList(), 0, true, false)
            state.receive(listOf(source("字".repeat(100))), 1_000_000_000L)
            state.receive(listOf(source("字".repeat(200))), 1_000_000_000L + interval)
            val target = state.update(listOf(part("字".repeat(200))), 0, true, false)!!
            assertEquals(1, state.advance(target, 10_000_000_000L))
            return state.advance(target, 10_016_000_000L)
        }
        val slow = frontier(20_000_000_000L)
        val fast = frontier(10_000_000L)
        assertTrue(slow in 1..3)
        assertTrue("New receipt rate must affect the actual frame frontier", fast > slow)
        assertTrue("A received batch must not jump straight to its end", fast < 200)
    }

    @Test fun rawReceiptPauseFreezesSamplingAndDoesNotChargePausedTime() {
        fun resumed(delay: Long): List<Int> {
            val state = MarkdownTypingState(emptyList())
            state.update(emptyList(), 0, true, false)
            state.receive(listOf(source("字".repeat(100))), 1_000_000_000L)
            state.receive(listOf(source("字".repeat(200))), 2_000_000_000L)
            val target = state.update(listOf(part("字".repeat(200))), 0, true, false)!!
            state.advance(target, 3_000_000_000L)
            state.advance(target, 3_016_000_000L)
            state.pause(target)
            val before = state.visible(target)
            state.receive(listOf(source("字".repeat(300))), 4_000_000_000L + delay, paused = true)
            state.receive(listOf(source("字".repeat(400))), 5_000_000_000L + delay)
            val next = state.update(listOf(part("字".repeat(400))), 0, true, false)!!
            assertEquals(before, next.start)
            return (0..10).map { frame -> state.advance(next, 6_000_000_000L + delay + frame * 16_000_000L) - before }
        }
        val normal = resumed(0)
        assertEquals(1, normal.first())
        assertEquals(11, normal.last())
        assertEquals(normal, resumed(60_000_000_000L))
    }

    @Test fun aSingleRawTerminalBatchBootstrapsAndDrains() {
        val state = MarkdownTypingState(emptyList())
        state.update(emptyList(), 0, false, false, live = true)
        state.receive(listOf(source("字".repeat(500))), 1_000_000_000L)
        val target = state.update(listOf(part("字".repeat(500))), 0, false, true, paceTerminal = true)!!
        assertEquals(1, state.advance(target, 2_000_000_000L))
        assertTrue(state.advance(target, 2_016_000_000L) in 2 until 500)
        assertEquals(500, drain(state, target, 2_032_000_000L))
    }

    @Test fun layoutPauseDoesNotResetRawReceiptIntervals() {
        fun reveal(blocked: Boolean): List<Int> {
            val state = MarkdownTypingState(emptyList())
            state.update(emptyList(), 0, true, false)
            state.receive(listOf(source("字".repeat(100))), 1_000_000_000L)
            state.receive(listOf(source("字".repeat(200))), 2_000_000_000L)
            val old = state.update(listOf(part("字".repeat(200))), 0, true, false)!!
            if (blocked) state.pause(old)
            state.receive(listOf(source("字".repeat(400))), 6_000_000_000L)
            val next = state.update(listOf(part("字".repeat(400))), 0, true, false)!!
            state.pause(next) // Compare rates from the same restart phase.
            return (0..10).map { state.advance(next, 7_000_000_000L + it * 16_000_000L) }
        }
        assertEquals(reveal(false), reveal(true))
    }

    @Test fun firstRawBatchReceivedWhilePausedCanStillDrainAfterResume() {
        val state = MarkdownTypingState(emptyList())
        state.update(emptyList(), 0, false, false, live = true)
        val sources = listOf(source("字".repeat(500)))
        state.receive(sources, 1_000_000_000L, paused = true)
        val paused = state.update(listOf(part(sources.single().text)), 0, false, true, paceTerminal = true)!!
        state.pause(paused)
        assertEquals(0, state.visible(paused))
        state.receive(sources, 61_000_000_000L)
        assertEquals(1, state.advance(paused, 62_000_000_000L))
        assertTrue(state.advance(paused, 62_016_000_000L) in 2 until 500)
        assertEquals(500, drain(state, paused, 62_032_000_000L))
    }

    @Test fun firstLiveAppendBootstrapsFromItsOwnBatchRatherThanHistoryPrefix() {
        val prefix = "prefix "
        val state = MarkdownTypingState(listOf(source(prefix)))
        state.receive(listOf(source(prefix)), 1_000_000_000L)
        state.update(listOf(part(prefix)), prefix.length, true, false)
        val text = prefix + "字".repeat(500)
        state.receive(listOf(source(text)), 2_000_000_000L)
        val next = state.update(listOf(part(text)), prefix.length, false, true, paceTerminal = true)!!
        assertEquals(prefix.length, next.start)
        assertEquals(prefix.length + 1, state.advance(next, 3_000_000_000L))
        assertEquals(prefix.length + 2, state.advance(next, 3_016_000_000L))
        assertEquals(text.length, drain(state, next, 3_032_000_000L))
    }

    @Test fun firstLiveAppendAroundPauseBootstrapsFromTheBatchNotTheHistoricalPrefix() {
        fun resumed(prefixLength: Int, receivedWhilePaused: Boolean, delay: Long): List<Int> {
            val prefix = "旧".repeat(prefixLength)
            val state = MarkdownTypingState(listOf(source(prefix)))
            state.receive(listOf(source(prefix)), 1_000_000_000L)
            val initial = state.update(listOf(part(prefix)), prefix.length, true, false)!!
            state.receive(listOf(source(prefix)), 2_000_000_000L, paused = true)
            state.pause(initial)
            val text = prefix + "字".repeat(500)
            if (receivedWhilePaused) state.receive(listOf(source(text)), 3_000_000_000L + delay, paused = true)
            val paused = state.update(listOf(part(if (receivedWhilePaused) text else prefix)), prefix.length,
                false, receivedWhilePaused, paceTerminal = true)!!
            assertEquals("Receiving while paused cannot move the visible frontier", prefix.length, state.visible(paused))
            state.pause(paused)
            state.receive(listOf(source(text)), 4_000_000_000L + delay)
            val next = state.update(listOf(part(text)), prefix.length, false, true, paceTerminal = true)!!
            assertEquals(prefix.length, next.start)
            val frames = (0..2).map { state.advance(next, 5_000_000_000L + delay + it * 16_000_000L) - prefix.length }
            assertEquals(1, frames.first())
            assertEquals("The next frame admits one more character", 2, frames[1])
            assertEquals(text.length, drain(state, next, 5_048_000_000L + delay))
            return frames
        }
        val ordinary = resumed(7, false, 0)
        for (prefix in listOf(7, 5_000)) for (duringPause in listOf(false, true)) {
            assertEquals("Historical prefix size must not set the first live rate", ordinary, resumed(prefix, duringPause, 0))
            assertEquals("Paused time must not buy catch-up", ordinary, resumed(prefix, duringPause, 60_000_000_000L))
        }
    }

    @Test fun pauseBeforeParsingPreservesAnAlreadyMeasuredRawReceiptRate() {
        val prefix = "prefix "
        val state = MarkdownTypingState(listOf(source(prefix)))
        state.receive(listOf(source(prefix + "字".repeat(100))), 1_000_000_000L)
        state.receive(listOf(source(prefix + "字".repeat(200))), 2_000_000_000L)
        state.receive(listOf(source(prefix + "字".repeat(300))), 3_000_000_000L, paused = true)
        state.receive(listOf(source(prefix + "字".repeat(400))), 64_000_000_000L)
        val next = state.update(listOf(part(prefix + "字".repeat(400))), prefix.length, true, false)!!
        val frames = (0..10).map { state.advance(next, 65_000_000_000L + it * 16_000_000L) - prefix.length }
        assertEquals(1, frames.first())
        assertEquals("Sampled rates still obey the visible-frame cap", 11, frames.last())
    }
    @Test fun holdPreservesFractionalPhaseWithoutBuyingRestartCharacters() {
        fun trace(interval: Long, wait: Long): List<Int> {
            val prefix = "prefix "
            val state = MarkdownTypingState(listOf(source(prefix)))
            state.update(listOf(part(prefix)), prefix.length, true, false)
            state.receive(listOf(source(prefix + "字".repeat(100))), 1_000_000_000L)
            state.receive(listOf(source(prefix + "字".repeat(200))), 1_000_000_000L + interval)
            val target = state.update(listOf(part(prefix + "字".repeat(200))), prefix.length, true, false)!!
            state.pause(target) // Start equally before comparing later holds.
            var time = 10_000_000_000L
            if (wait > 0) {
                state.hold(target, time - 16_000_000L)
                assertEquals(prefix.length, state.visible(target))
            }
            assertEquals(prefix.length + 1, state.advance(target, time))
            time += 5_000_000L
            state.advance(target, time)
            return buildList {
                repeat(12) {
                    val before = state.visible(target)
                    if (wait > 0) {
                        time += wait
                        state.hold(target, time)
                        assertEquals(before, state.visible(target))
                    }
                    time += 1_000_000L
                    val shown = state.advance(target, time)
                    assertTrue(shown in before..target.total)
                    assertEquals(prefix.length, target.start)
                    add(shown - prefix.length)
                }
            }
        }
        val slow = trace(1_000_000_000L, 0)
        val fast = trace(10_000_000L, 0)
        for (interval in listOf(1_000_000_000L, 10_000_000L)) {
            val uninterrupted = trace(interval, 0)
            assertEquals(uninterrupted, trace(interval, 16_000_000L))
            assertEquals(uninterrupted, trace(interval, 60_000_000_000L))
        }
        assertTrue("Subcharacter releases must not each invent +1", slow.zipWithNext().any { (a, b) -> a == b })
        assertTrue("Hold must retain measured receipt-rate separation", fast.last() > slow.last())
        assertTrue(fast.last() < 200)
    }


    @Test fun historicalRowRetainsItsActualBodyAndReasoningLanes() {
        val admissions = WorkflowVisualAdmissions(emptySet())
        val scope = admissions.scope("old-reply", baseline = false)
        val old = ReplyPresentationControl("old-reply", initiallyLive = true)
        val current = ReplyPlayback(old, runtimePaused = false)
        scope.retainReplyControl(old)
        assertSame(current, scope.replyPlayback(current))
        val parser = MarkdownParserIdentity(defaultMarkdownParser)
        for (slot in listOf<Any>("body:old-reply", listOf("reasoning", "old-reply", "reasoning-item"))) {
            val lane = old.lane(slot, parser, 0L) { emptyList() }
            val target = lane.motion.update(listOf(part("已经显示的历史正文")), 0, true, false)!!
            drain(lane.motion, target)
            lane.motion.pause(target)
            val retained = scope.replyPlayback(null)!!
            assertSame(old, retained.control)
            assertFalse(retained.paused)
            val remounted = retained.control.lane(slot, parser, 0L) { error("Remount must not replace the original baseline") }
            assertSame(lane, remounted)
            assertEquals(target.total, remounted.motion.visible(target))
            var advances = 0
            assertEquals(target.total, remounted.motion.advance(target, 90_000_000_000L) { advances++ })
            assertEquals("Completed history must not replay", 0, advances)
        }
        admissions.retain(setOf("old-reply", "new-reply"))
        val next = ReplyPlayback(ReplyPresentationControl("new-reply", true), runtimePaused = true)
        admissions.scope("new-reply", false).retainReplyControl(next.control)
        assertSame(next, admissions.scope("new-reply", false).replyPlayback(next))
        assertFalse("Another row's runtime pause must not propagate", scope.replyPlayback(null)!!.paused)
        admissions.retain(setOf("new-reply"))
        assertNull("Only data removal releases the owner", admissions.scope("old-reply", false).replyPlayback(null))
    }

    @Test fun historicalRemountKeepsTheFrontierAndTypesOnlyTheOffscreenSuffix() {
        val scope = WorkflowVisualScope(false)
        assertNull("Cold history must not acquire a live control", scope.replyPlayback(null))
        val control = ReplyPresentationControl("old-reply", true)
        scope.retainReplyControl(control)
        val parser = MarkdownParserIdentity(defaultMarkdownParser)
        val lane = control.lane("body:old-reply", parser, 0L) { emptyList() }
        val first = lane.motion.update(listOf(part("字".repeat(30))), 0, true, false)!!
        lane.motion.advance(first, 1_000_000_000L)
        lane.motion.advance(first, 1_016_000_000L)
        val frontier = lane.motion.visible(first)
        assertTrue(frontier in 1 until first.total)
        lane.motion.pause(first)
        val restored = scope.replyPlayback(null)!!.control.lane("body:old-reply", parser, 0L) {
            listOf(MarkdownTypingSource("wrong-baseline", "字".repeat(90)))
        }
        assertSame(lane, restored)
        val next = restored.motion.update(listOf(part("字".repeat(90))), 0, false, true, live = false, paceTerminal = true)!!
        assertEquals(frontier, restored.motion.visible(next))
        assertEquals("Offscreen time buys no catch-up", frontier + 1, restored.motion.advance(next, 90_000_000_000L))
        assertTrue(restored.motion.visible(next) < next.total)
        assertEquals(next.total, drain(restored.motion, next, time = 90_016_000_000L))
    }


    @Test fun missingRemountProjectionDoesNotMakeTheSavedTargetStartTheActualFrontier() {
        val motion = MarkdownTypingState(emptyList())
        val target = motion.update(listOf(part("字".repeat(80))), 0, true, false)!!
        motion.advance(target, 1_000_000_000L)
        motion.advance(target, 1_016_000_000L)
        val frontier = motion.visible(target)
        assertTrue(frontier > target.start)
        motion.pause(target)
        val retained = motion.update(null, null, true, false)!!
        assertSame(target, retained)
        assertEquals(frontier, motion.visible(retained))
        assertTrue("The original start is not a remount frontier", retained.start < frontier)
        val frame = mutableStateOf(motion.frame(retained))
        val budgets = MarkdownRevealBudgets(mutableStateOf<MarkdownTypingTarget?>(retained), frame)
        assertEquals(frontier, frame.value.visible)
        assertEquals(frontier, budgets.visibility(retained.parts.single().source.id).value)
    }


    @Test fun retainedTargetsIgnoreColdEmptyAndPrefixProjectionsWithoutReplaying() {
        val text = "字".repeat(500)
        for (partial in listOf(false, true)) for (coldParts in listOf(
            emptyList<MarkdownTypingPart>(), listOf(part("")), listOf(part(text.take(7))))) {
            val motion = MarkdownTypingState(emptyList())
            motion.update(emptyList(), 0, true, false)
            motion.receive(listOf(source(text.take(100))), 1_000_000_000L)
            motion.receive(listOf(source(text)), 5_000_000_000L)
            val running = motion.update(listOf(part(text)), 0, true, false)!!
            val frontier = drain(motion, running, 6_000_000_000L, limit = if (partial) 67 else text.length)
            motion.pause(running)
            val latest = text + if (partial) "后".repeat(500) else ""
            motion.receive(listOf(source(latest)), 60_000_000_000L, paused = true)
            val cold = motion.update(coldParts, 0, false, false, live = false, paceTerminal = true)!!
            assertSame("A stale projection must retain the accepted target", running, cold)
            assertEquals(frontier, motion.visible(cold))
            assertEquals(frontier, motion.frame(cold).visible)
            motion.pause(cold)
            motion.receive(listOf(source(latest)), 61_000_000_000L)
            val full = motion.update(listOf(part(latest)), 0, false, true, live = false, paceTerminal = true)!!
            assertEquals(frontier, motion.visible(full))
            var advances = 0
            val resumed = motion.advance(full, 62_000_000_000L) { advances++ }
            if (partial) {
                assertEquals("Offscreen and paused time buy no catch-up", frontier + 1, resumed)
                assertTrue(motion.advance(full, 62_016_000_000L) in resumed until full.total)
                assertEquals(full.total, drain(motion, full, 62_032_000_000L))
            } else {
                assertEquals(text.length, resumed)
                assertEquals("History must emit no replay advances", 0, advances)
            }
        }
    }

    @Test fun authoritativeSourceReplacementIsNotMistakenForAStaleProjection() {
        val full = "already displayed source"
        val replacements = listOf(listOf(source(full.take(7))), listOf(source("different")),
            listOf(MarkdownTypingSource("different-id", full)), emptyList())
        for (replacement in replacements) {
            val motion = MarkdownTypingState(emptyList())
            motion.receive(listOf(source(full)), 1_000_000_000L)
            val original = motion.update(listOf(part(full)), 0, true, false)!!
            drain(motion, original)
            motion.receive(replacement, 2_000_000_000L)
            val parts = replacement.map { MarkdownTypingPart(it, it.text.length, it.text) }
            val next = motion.update(parts, 0, false, true, live = false, paceTerminal = true)!!
            assertEquals(parts, next.parts)
            assertEquals(parts.sumOf { it.length }, motion.visible(next))
        }
    }

    @Test fun pagedTerminalHistoryHasASilentScopeWithoutResettingAnExistingLiveOwner() {
        val admissions = WorkflowVisualAdmissions(emptySet())
        val history = admissions.scope("paged-history", baseline = false, live = false)
        assertFalse("A paged completed thought is not a first live presentation",
            history.claimReasoning("paged-history", "thought"))
        val sources = listOf(source("already received"))
        assertEquals(sources, history.bodyBaseline("paged-history", sources, live = true))
        assertSame(history, admissions.scope("paged-history", baseline = false))
        val active = admissions.scope("active", baseline = false, live = true)
        assertSame("Terminal transition must retain the existing live scope",
            active, admissions.scope("active", baseline = false, live = false))
        assertTrue("A genuinely new live thought still receives its typewriter ticket",
            active.claimReasoning("active", "thought"))
        assertEquals(emptyList<MarkdownTypingSource>(), active.bodyBaseline("active", sources, live = true))
    }

    @Test fun lowReceiptRateKeepsFractionalProgressAcrossRefreshRates() {
        fun shown(frame: Long): Int {
            val state = MarkdownTypingState(emptyList())
            state.update(emptyList(), 0, true, false)
            state.receive(listOf(source("x")), 1_000_000_000L)
            state.receive(listOf(source("xx")), 1_100_000_000L)
            val target = state.update(listOf(part("x".repeat(100))), 0, true, false)!!
            var time = 2_000_000_000L
            var visible = state.advance(target, time)
            while (time < 3_000_000_000L) {
                time = minOf(time + frame, 3_000_000_000L)
                val next = state.advance(target, time)
                assertTrue(next - visible in 0..1)
                visible = next
            }
            return visible
        }
        val at30 = shown(33_333_333L)
        assertEquals(11.0, at30.toDouble(), 1.0)
        assertEquals(at30.toDouble(), shown(16_666_667L).toDouble(), 1.0)
        assertEquals(at30.toDouble(), shown(8_333_333L).toDouble(), 1.0)
    }

    @Test fun unicodeRevealAdmitsOnlyOneCompleteCharacterPerFrame() {
        val text = "A😀𠮷e\u0301👩‍💻Z"
        val boundaries = MarkdownTextBoundaries(text)
        val state = MarkdownTypingState(emptyList())
        val target = state.update(listOf(part(text)), 0, true, false)!!
        var previous = 0
        var time = 1_000_000_000L
        repeat(text.length * 8) {
            val shown = state.advance(target, time)
            assertTrue(shown >= previous)
            if (shown > previous) assertEquals(boundaries.next(previous), shown)
            assertEquals(shown, boundaries.floor(shown))
            previous = shown
            time += 16_000_000L
        }
        assertEquals(text.length, previous)
    }

    @Test fun sameFrameTargetChangeAndPauseCannotBuyAnotherCharacter() {
        val state = MarkdownTypingState(emptyList())
        val first = state.update(listOf(part("abc")), 0, true, false)!!
        assertEquals(1, state.advance(first, 1_000_000_000L))
        state.pause(first)
        val next = state.update(listOf(part("abcdef")), 0, false, true)!!
        assertEquals(1, state.advance(next, 1_000_000_000L))
        assertEquals(2, state.advance(next, 1_016_000_000L))
    }

    @Test fun admissionInsideACharacterWaitsForItsWholeBoundary() {
        for (text in listOf("😀x", "e\u0301x")) {
            val state = MarkdownTypingState(emptyList())
            val target = state.update(listOf(part(text)), 0, true, false)!!
            assertEquals(0, state.advance(target, 1_000_000_000L, 1))
            assertEquals(2, state.advance(target, 1_016_000_000L, 2))
            assertEquals(2, state.advance(target, 1_032_000_000L, 3))
            assertEquals(3, drain(state, target, 1_048_000_000L, limit = 3))
        }
    }

    @Test fun coldTerminalTargetTypesOnlyTheSuffixOutsideItsKnownBaseline() {
        val state = MarkdownTypingState(listOf(source("old")))
        val target = state.update(listOf(part("old😀new")), 3, false, true,
            live = false, paceTerminal = true)!!
        assertEquals(3, target.start)
        assertEquals(5, state.advance(target, 90_000_000_000L))
        assertEquals(target.total, drain(state, target, 90_016_000_000L))
    }


    @Test fun paintOnlyWaitingPreservesLowRateActiveTimeAt60And120Hz() {
        for (hz in listOf(60, 120)) {
            fun fixture(): Pair<MarkdownTypingState, MarkdownTypingTarget> {
                val state = MarkdownTypingState(emptyList())
                state.update(emptyList(), 0, true, false)
                state.receive(listOf(source("字".repeat(100))), 1_000_000_000L)
                state.receive(listOf(source("字".repeat(200))), 21_000_000_000L)
                return state to state.update(listOf(part("字".repeat(200))), 0, true, false)!!
            }
            val (state, target) = fixture()
            val (reference, referenceTarget) = fixture()
            val start = 30_000_000_000L
            var advances = 0
            var pendingPaint = true
            var waits = 0
            assertEquals(1, state.advance(target, start) { advances++ })
            assertEquals(1, reference.advance(referenceTarget, start))
            repeat(hz * 10 + hz / 4) { index ->
                val now = start + (index + 1L) * 1_000_000_000L / hz
                val expected = reference.advance(referenceTarget, now)
                val before = state.visible(target)
                val callbacks = advances
                // State-level simulation: a newly exposed character waits one frame for paint.
                val shown = if (pendingPaint) {
                    pendingPaint = false
                    waits++
                    state.visible(target).also { assertEquals(callbacks, advances) }
                } else state.advance(target, now) { advances++ }.also {
                    pendingPaint = it > before
                }
                assertTrue(shown - before in 0..1)
                assertTrue("Paint-only waiting must not discard active time", expected - shown in 0..1)
            }
            assertTrue(waits > 40)
            assertEquals(52, reference.visible(referenceTarget))
            assertEquals(reference.visible(referenceTarget), state.visible(target))
            assertEquals(state.visible(target), advances)
        }
    }

    @Test fun longPaintWaitIsClampedAndNeverBuysWholeCharacterCredit() {
        for (interval in listOf(20_000_000_000L, 10_000_000L)) {
            fun fixture(): Pair<MarkdownTypingState, MarkdownTypingTarget> {
                val state = MarkdownTypingState(emptyList())
                state.update(emptyList(), 0, true, false)
                state.receive(listOf(source("e\u0301".repeat(100))), 1_000_000_000L)
                state.receive(listOf(source("e\u0301".repeat(200))), 1_000_000_000L + interval)
                return state to state.update(listOf(part("e\u0301".repeat(200))), 0, true, false)!!
            }
            val (state, target) = fixture()
            val (bounded, boundedTarget) = fixture()
            val start = 30_000_000_000L
            assertEquals(2, state.advance(target, start))
            assertEquals(2, bounded.advance(boundedTarget, start))
            repeat(60) { assertEquals(2, state.visible(target)) }
            repeat(21) { frame ->
                val before = state.visible(target)
                val now = start + 60_000_000_000L + frame * 10_000_000L
                val shown = state.advance(target, now)
                assertEquals(bounded.advance(boundedTarget, start + 50_000_000L + frame * 10_000_000L), shown)
                assertTrue(shown == before || shown == target.next(before))
                assertEquals(0, shown % 2)
                assertEquals(shown, state.advance(target, now))
            }
            assertTrue(state.visible(target) < target.total)
        }
    }

    @Test fun pauseAndClearanceStillExcludeBlockedTimeBeforePaintRelease() {
        fun trace(paused: Boolean, delay: Long): List<Int> {
            val state = MarkdownTypingState(emptyList())
            state.update(emptyList(), 0, true, false)
            state.receive(listOf(source("字".repeat(100))), 1_000_000_000L)
            state.receive(listOf(source("字".repeat(200))), 11_000_000_000L)
            val target = state.update(listOf(part("字".repeat(200))), 0, true, false)!!
            val start = 30_000_000_000L
            assertEquals(1, state.advance(target, start))
            assertEquals(1, state.advance(target, start + 37_000_000L))
            for (now in listOf(start + 57_000_000L, start + 77_000_000L + delay)) {
                if (paused) state.pause(target) else state.hold(target, now)
                assertEquals(1, state.visible(target))
            }
            return (1..12).map { state.advance(target, start + 77_000_000L + delay + it * 9_000_000L) }
        }
        for (paused in listOf(false, true)) {
            val short = trace(paused, 0L)
            assertEquals(short, trace(paused, 60_000_000_000L))
            assertEquals(if (paused) 2 else 1, short.first())
            assertTrue(short.zipWithNext().all { (a, b) -> b - a in 0..1 })
        }
    }

}
