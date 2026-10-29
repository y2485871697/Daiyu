"""Source wiring checks only; JVM/Android execution is deliberately deferred for this batch."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/kotlin/app/daiyu/android"
BASE = APP / "ui/screens/chat/conversation"


class MarkdownTypingContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.parser = (APP / "MarkdownParser.kt").read_text()
        cls.projection = (BASE / "MarkdownProjection.kt").read_text()
        cls.cache = (BASE / "MarkdownProjectionCache.kt").read_text()
        cls.ui = (BASE / "MessageMarkdown.kt").read_text()
        cls.typing = (BASE / "MarkdownTyping.kt").read_text()
        cls.reveal = (BASE / "MarkdownReveal.kt").read_text()
        cls.chat = (BASE / "ChatMessageList.kt").read_text()

    def test_exact_runtime_phase_updates_even_when_rows_are_reused(self):
        host = (APP / "MainActivity.kt").read_text().split("private fun publishChat(rowsChanged: Boolean", 1)[1].split("private fun requestScroll", 1)[0]
        self.assertIn("ChatTurnMapper.withStreamingOwner(if (!remapTurns) chatState.turns else", host)
        self.assertIn("controlOwner?.takeIf { state?.phase == ExecutionPhase.STREAMING }", host)
        mapper = (BASE / "ChatTurnMapper.kt").read_text().split("fun withStreamingOwner", 1)[1].split("/**", 1)[0]
        for token in ["turns.indexOfLast", "ownerMessageId == live.id.value", "live.conversationId.value, live.runId.value, turn.id", "listKey(turn.segmentIndex)", "turn.copy(isStreaming = index == last)"]:
            self.assertIn(token, mapper)

    def test_full_sources_still_project_once_not_per_frame(self):
        self.assertIn("val projection = rememberMarkdownProjection(sources, streaming = turn.status == RunStatus.RUNNING)", self.chat)
        self.assertIn("val reveal = rememberMarkdownReveal(turn, projection, revealResetEpoch, onVisibleAdvance)", self.chat)
        self.assertIn("withFrameNanos { motion.advance(current, it) { currentAdvance() } }", self.typing)
        for source in [self.typing, self.reveal]:
            for forbidden in ["MarkdownParser.parse", "MarkdownText.render(", "substring(0,", "Thread.sleep", "delay("]:
                self.assertNotIn(forbidden, source)
        self.assertIn("while (shown < current.total)", self.typing)

    def test_mount_baseline_and_part_identity_are_not_text_guesses(self):
        for token in ["remember(identity, resetEpoch) { sources.toList() }", "MarkdownTypingSource(it.itemId, it.text)",
                      "rememberMarkdownProjection(baseline.map { it.text },", "remember(identity, resetEpoch) { MarkdownTypingState(baseline) }"]:
            self.assertIn(token, self.typing)
        self.assertIn('RevealedMessageMarkdown(turn.finalText, projection?.blocks?.get(turn.finalText), reveal, "final"', self.chat)
        self.assertIn('RevealedMessageMarkdown(text.text, projection?.blocks?.get(text.text), reveal, text.itemId', self.chat)
        self.assertIn("ProjectedMessageMarkdown(text, blocks, modifier, reveal.limit(partId), generationComplete)", self.ui)
        content = (BASE / "MessageContent.kt").read_text()
        self.assertIn("textBody: @Composable (MessageContentUi.Text, Modifier)", content)
        self.assertIn("textBody(block, Modifier.testTag", content)
        self.assertIn("UserMessageText(text.text, modifier)", self.chat)

    def test_projection_ready_and_reveal_settled_are_separate_current_turn_gates(self):
        self.assertIn("SideEffect { onContentReady(contentReady) }", self.chat)
        self.assertIn("val revealSettled = reveal.settled", self.chat)
        self.assertIn("SideEffect { onRevealSettled(revealSettled) }", self.chat)
        self.assertEqual(2, self.chat.count("revealedTurns[it.listKey] == it"))
        self.assertEqual(2, self.chat.count("readyTurns[it.listKey] == it"))
        self.assertIn("onContentReady(false); onRevealSettled(false)", self.chat)
        self.assertIn("target.parts.map { it.source } == sources", self.typing)
        self.assertIn("baselineLength != null", self.typing)
        self.assertIn("target.parts == parts", self.typing)

    def test_elapsed_time_drain_and_stale_target_guards_are_present(self):
        for token in ["if (target !== expected)", "frameNanos - lastFrame", "250_000_000.0",
                      "if (streaming) { seenStreaming = true; closed = false }", "if (parts == null || baselineLength == null) return target",
                      "previous[index].id == next[index].id", "next[index].text.startsWith(previous[index].text)"]:
            self.assertIn(token, self.reveal)

    def test_full_annotation_is_remembered_independently_of_visible_prefix(self):
        inline = self.ui.split("private fun MarkdownInlineText(", 1)[1].split("private fun MarkdownBlocks", 1)[0]
        self.assertIn("remember(content, linkColor, openLink)", inline)
        self.assertIn("body.subSequence(0, boundaries.end(visibleCharacters))", inline)
        self.assertNotIn("remember(content, linkColor, openLink, visibleCharacters)", inline)
        for token in ["BreakIterator.getCharacterInstance(Locale.ROOT)", "characters.following(limit)", "isHighSurrogate()", "isLowSurrogate()"]:
            self.assertIn(token, self.reveal)

    def test_table_width_sampling_is_bounded_and_font_scaled_without_intrinsic(self):
        table = self.ui.split("private fun MarkdownTable(", 1)[1].split("private fun headingSize", 1)[0]
        self.assertNotIn("renderInline", table)
        self.assertNotIn("IntrinsicSize", self.ui)
        for token in ["MarkdownText.tableColumnWidthEms(table.header)", "style.fontSize.toDp()", "remember(columnEms, em)",
                      "table.header.size > 32", "horizontalScroll(rememberScrollState())", "Row(Modifier.drawWithCache", "visibleCharacters - offsets[rowIndex][column]"]:
            self.assertIn(token, table)
        hints = (APP / "MarkdownText.kt").read_text().split("internal fun tableCellWidthEm", 1)[1].split("internal fun renderInline", 1)[0]
        self.assertIn("var charactersLeft = 32", hints)
        self.assertIn("var nodesLeft = 128", hints)
        self.assertNotIn("SpannableStringBuilder", hints)

    def test_segment_reuse_does_not_introduce_a_second_cache_budget(self):
        self.assertIn("fun getBlocks(source: String", self.cache)
        self.assertIn("entries.entries.lastOrNull", self.cache)
        self.assertIn("it.value.projection.parser === parser", self.cache)
        self.assertEqual(1, self.cache.count("LinkedHashMap<"))
        self.assertIn("cache.getBlocks(source, parser)", self.projection)
        self.assertIn("currentCoroutineContext().ensureActive()\n    cache.put(projection)", self.projection)

    def test_real_default_parser_checks_its_active_coroutine_without_identity_wrapping(self):
        for token in ["internal val defaultMarkdownParser:", "parser === defaultMarkdownParser",
                      "MarkdownParser.parse(source) { context.ensureActive() }", "Dispatchers.Default.limitedParallelism(1)"]:
            self.assertIn(token, self.projection)
        self.assertIn("{ defaultMarkdownParser }", self.ui)
        for token in ["private var remaining = 1024", "private fun isRule(line: String, cancel: ParseCheck)",
                      "stripAtxClosingHashes(title.groupValues[2], cancel)", "separators.all { cancel.step();", "repeat(after - i) { cancel.step();"]:
            self.assertIn(token, self.parser)

    def test_link_prepass_reuses_the_budgeted_destination_scan_and_safety_boundary(self):
        self.assertNotIn("parenthesisEnds", self.parser)
        self.assertIn("val destination = destinations[i]", self.parser)
        self.assertEqual(1, self.parser.count("linkDestination(source, p + 1, work, cancel)"))
        for token in ["if (work.exhausted) return listOf(MarkdownInline.Text(source))", "destinations[open] = destination",
                      "p = destination.second", "MarkdownText.isSafeLink(destination.first)", "plain.append(source, i, destination.second)"]:
            self.assertIn(token, self.parser)

    def test_executable_regression_sources_exist_without_claiming_they_ran(self):
        test = (ROOT / "app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/MessageMarkdownTest.kt").read_text()
        for name in ["testStreamingRevealUsesCachedAstWithoutPerFrameParsingAndDrainsUnicodeText", "testNewMixedTextUsesTurnMountBaselineAndLiveRemountDoesNotReplay",
                     "testCompletedReadWaitsForCurrentRevealToDrainEvenWithAnAlreadyCachedProjection", "testTableWidthHintsFollowFontScaleWithoutTruncatingLongCellContent"]:
            self.assertIn(name, test)
        unit = (ROOT / "app/src/test/kotlin/app/daiyu/android/ui/screens/chat/conversation/MarkdownRevealTest.kt").read_text()
        for name in ["lateTerminalTargetsDrainToTheirExactEnd", "clockSpeedDoesNotDependOnRefreshRate", "everyPrefixIsSurrogateAndCombiningBoundarySafe", "replacementsAndReordersNeverBorrowProgress"]:
            self.assertIn(name, unit)


if __name__ == "__main__":
    unittest.main()
