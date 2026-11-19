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
                repeat(100) {
                    time += frameMillis * 1_000_000L
                    val next = state.advance(target, time)
                    assertTrue(next in shown..received)
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
        assertTrue(short.zipWithNext().all { (a, b) -> b - a <= 68 })
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
        assertEquals("Completed edits still use a silent baseline", 501, edited.start)
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
        assertEquals(6, history.update(listOf(part("answer")), 0, streaming = false, terminal = true)!!.start)
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
        assertEquals(301, edited.start)
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

    @Test fun receivedFrontierDoesNotDependOnRefreshRate() {
        fun shown(frame: Long): Int {
            val state = MarkdownTypingState(listOf(source("")))
            val target = state.update(listOf(part("x".repeat(10_000))), 0, true, false)!!
            var time = 1_000_000_000L
            var visible = state.advance(target, time)
            while (time < 1_060_000_000L) {
                time = minOf(time + frame, 1_060_000_000L)
                visible = state.advance(target, time)
            }
            return visible
        }
        val slow = shown(16_666_667)
        val fast = shown(8_333_333)
        assertTrue(slow in 1 until 10_000)
        assertEquals(slow.toDouble(), fast.toDouble(), 1.0)
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

}
