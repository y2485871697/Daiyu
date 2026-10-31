package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.MarkdownParser
import org.junit.Assert.*
import org.junit.Test

class MarkdownRevealTest {
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
        state.advance(old, 1_000_000_000)
        assertEquals(3, state.advance(old, 1_050_000_000))
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

    @Test fun blockedBodyKeepsThePrecedingTextVisibleAndResetsTheDrainClock() {
        val state = MarkdownTypingState(emptyList())
        state.update(emptyList(), 0, true, false)
        val target = state.update(listOf(part("note", "note"), part("x".repeat(500), "answer")), 0, false, true)!!
        state.advance(target, 1_000_000_000L, 4)
        assertEquals(4, state.advance(target, 1_250_000_000L, 4))
        assertEquals(4, state.visible(target))
        assertEquals(0, target.limit("answer", state.visible(target)))
        state.pause(target)
        assertEquals(4, state.advance(target, 9_000_000_000L))
        val partial = state.advance(target, 9_050_000_000L)
        assertTrue(partial in 5 until target.total)
        assertEquals(target.total, state.advance(target, 9_250_000_000L))
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

    @Test fun newBodyAnimatesAndNewTargetsKeepProgress() {
        val state = MarkdownTypingState(listOf(source("")))
        state.update(listOf(part("")), 0, true, false)
        val target = state.update(listOf(part("x".repeat(200))), 0, true, false)!!
        assertEquals(0, target.start)
        state.advance(target, 1_000_000_000)
        val shown = state.advance(target, 1_100_000_000)
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
        assertEquals(6, state.advance(admitted, 1_250_000_000))
    }

    @Test fun lateTerminalTargetsDrainToTheirExactEnd() {
        val state = MarkdownTypingState(listOf(source("")))
        state.update(listOf(part("")), 0, true, false)
        val first = state.update(listOf(part("x".repeat(200))), 0, true, false)!!
        state.advance(first, 1_000_000_000)
        state.advance(first, 1_050_000_000)
        state.update(null, null, false, true)
        val terminal = state.update(listOf(part("x".repeat(300))), 0, false, true)!!
        assertTrue(terminal.start < terminal.total)
        state.advance(terminal, 2_000_000_000)
        assertEquals(300, state.advance(terminal, 2_250_000_000))
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

    @Test fun clockSpeedDoesNotDependOnRefreshRate() {
        fun shown(frame: Long): Int {
            val state = MarkdownTypingState(listOf(source("")))
            val target = state.update(listOf(part("x".repeat(100))), 0, true, false)!!
            var time = 1_000_000_000L
            var visible = state.advance(target, time)
            while (time < 1_200_000_000L) {
                time = minOf(time + frame, 1_200_000_000L)
                visible = state.advance(target, time)
            }
            return visible
        }
        assertTrue(kotlin.math.abs(shown(16_666_667) - shown(8_333_333)) <= 3)
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

}
