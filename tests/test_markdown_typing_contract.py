"""检查 Markdown 逐字显示的源码连接。"""
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
        self.assertIn("ChatTurnMapper.withStreamingOwner(if (!remapTurns) chatState.turns.takeIf { chatState.conversationKey == selected?.id?.value }.orEmpty() else", host)
        self.assertIn("val state = controlOwner?.let { controller.runState(it) }", host)
        self.assertIn("controlOwner, phase = state?.phase ?: ExecutionPhase.IDLE, pauseRequested = state?.pauseRequested == true,", host)
        self.assertIn("stopRequested = controlOwner?.let(controller::runStopping) == true", host)
        mapper = (BASE / "ChatTurnMapper.kt").read_text().split("fun withStreamingOwner", 1)[1].split("/**", 1)[0]
        self.assertIn("val displayed = RunningTerminalTargets.project(turns, owner, terminalTargets)", mapper)
        for token in ["displayed.indexOfLast", "ownerMessageId == live.id.value", "live.conversationId.value, live.runId.value, turn.id", "listKey(turn.segmentIndex)", "val streaming = if (!stopRequested && (phase == null || phase == ExecutionPhase.STREAMING)) last else -1", "turn.copy(isStreaming = index == streaming, workProcess = work, stopRequested = stopped)"]:
            self.assertIn(token, mapper)

    def test_turn_settlement_uses_all_ordered_reasoning_paths(self):
        assistant = self.chat.split('internal fun AssistantTurn(', 1)[1].split(
            'private fun AssistantTextContent(', 1)[0]
        self.assertIn('ordered.steps.filterIsInstance<MessageContentUi.Reasoning>().map { it.reported }', assistant)
        self.assertIn('handoff.ready(reportedReasoning)', assistant)
        self.assertIn('visualScope?.handoff(turn.listKey, reportedReasoning)', assistant)
        self.assertNotIn('handoff.ready(turn.reportedReasoning)', assistant)

    def test_full_sources_still_project_once_not_per_frame(self):
        self.assertIn("val projection = rememberMarkdownProjection(sources, streaming = turn.status == RunStatus.RUNNING, windows = bodySources.map { it.window })", self.chat)
        self.assertIn("val reveal = rememberMarkdownReveal(bodySources, projection, streaming = turn.isStreaming,", self.chat)
        self.assertIn("onVisibleAdvance = onVisibleAdvance, enabled = ordered.leading.isNotEmpty() || reasoningReady, blockedSources = blockedSources,", self.chat)
        self.assertIn("live = turn.status == RunStatus.RUNNING || ordered.pending.isNotEmpty()", self.chat)
        callback = self.typing.split("withFrameNanos { frameNanos ->", 1)[1].split("\n            }", 1)[0]
        self.assertIn('shown = when { !markdownTypingCanAdvance(currentCanAdvance, currentPlayback) -> { motion.pause(current) motion.visible(current) } textClearance?.held == true || currentAwaitPaintAdmission && !publication.ready -> { motion.hold(current, frameNanos) motion.visible(current) } else -> motion.advance(current, frameNanos, end) { currentAdvance() } }', " ".join(callback.split()))
        self.assertEqual(1, callback.count("motion.advance("))
        self.assertEqual(1, callback.count("motion.pause("))
        self.assertEqual(1, callback.count("motion.hold("))
        self.assertIn("value = MarkdownTypingFrame(current, shown)", callback)
        self.assertLess(callback.index("motion.advance("), callback.index("value = MarkdownTypingFrame("))
        self.assertNotIn("shown = withFrameNanos", self.typing)
        hold = self.reveal.split("fun hold(expected: MarkdownTypingTarget, frameNanos: Long) {", 1)[1].split("\n    }", 1)[0]
        self.assertIn("if (target !== expected) return", hold)
        self.assertIn("if (lastFrame != 0L) lastFrame = frameNanos", hold)
        self.assertIn("arrivalFrame = 0L", hold)
        for forbidden in ("position =", "rate =", "desiredRate =", "resuming =", "receiptNanos =", "receiptRate =", "receivedEnd ="):
            self.assertNotIn(forbidden, hold)
        self.assertIn("awaitPaintAdmission: Boolean = false", self.typing)
        self.assertIn("val currentAwaitPaintAdmission by rememberUpdatedState(awaitPaintAdmission)", self.typing)
        self.assertIn('presentationLane = "body:${turn.listKey}", awaitPaintAdmission = true', self.chat)
        self.assertIn("val clearance = LocalStreamingTextClearance.current\n"
                      "    val textClearance by rememberUpdatedState(clearance)", self.typing)
        self.assertIn("target, canAdvance, advanceLimit", self.typing)
        self.assertIn("val currentCanAdvance by rememberUpdatedState(canAdvance)", self.typing)
        self.assertIn("val currentPlayback by rememberUpdatedState(playback)", self.typing)
        self.assertIn("canAdvance && playback?.paused != true", self.typing)
        for source in [self.typing, self.reveal]:
            for forbidden in ["MarkdownParser.parse", "MarkdownText.render(", "substring(0,", "Thread.sleep", "delay("]:
                self.assertNotIn(forbidden, source)
        self.assertIn("while (shown < end)", self.typing)

    def test_mount_baseline_and_part_identity_are_not_text_guesses(self):
        for token in [
            "val retainedLane = remember(playback?.control, presentationLane, identity, resetEpoch) {\n"
            "        presentationLane?.let { slot -> playback?.control?.lane(slot, identity, resetEpoch) { initialBaseline ?: sources } }\n"
            "    }",
            "val baseline = remember(identity, resetEpoch, retainedLane) { retainedLane?.baseline ?: (initialBaseline ?: sources).toList() }",
            "MarkdownTypingSource(it.itemId, it.text, it.markdownWindow)",
            "rememberMarkdownProjection(baseline.map { it.text },",
            "val motion = remember(identity, resetEpoch, retainedLane) { retainedLane?.motion ?: MarkdownTypingState(baseline) }",
        ]:
            self.assertIn(token, self.typing)
        control = (BASE / "ReplyPresentationControl.kt").read_text()
        for token in [
            "private data class LaneKey(val slot: Any, val parser: MarkdownParserIdentity, val epoch: Long)",
            "private val lanes = mutableMapOf<LaneKey, ReplyTypingLane>()",
            "fun lane(slot: Any, parser: MarkdownParserIdentity, epoch: Long, baseline: () -> List<MarkdownTypingSource>): ReplyTypingLane =\n"
            "        lanes.getOrPut(LaneKey(slot, parser, epoch)) { ReplyTypingLane(baseline().toList()) }",
            "internal class ReplyTypingLane(val baseline: List<MarkdownTypingSource>) {\n"
            "    val motion = MarkdownTypingState(baseline)\n"
            "}",
        ]:
            self.assertIn(token, control)
        workflow = (BASE / "AssistantWorkflowContent.kt").read_text()
        fallback = workflow.split('    val narration = mutableSetOf<String>()', 1)[1].split(
            '\ninternal fun MessageContentUi.workflowKey', 1)[0]
        self.assertIn('val body = finalContent.ifEmpty {', fallback)
        self.assertIn('segment.narrationId ?: "workflow-narration:$index"', fallback)
        self.assertIn('pending = body', fallback)
        self.assertIn('is MessageContentUi.Text -> "text:$itemId"', workflow)
        assistant = self.chat.split('private fun AssistantTextContent(', 1)[1].split(
            '\n@Composable\ninternal fun UserMessageText', 1)[0]
        self.assertIn('val textBody = rememberAssistantTextBody(emptySet(), bodySources, projection, reveal, generationComplete)', assistant)
        text_body = self.chat.split('internal fun rememberAssistantTextBody(', 1)[1].split('private fun AssistantWorkflow(', 1)[0]
        for token in ('rememberUpdatedState(projection)', 'rememberUpdatedState(reveal)',
                      'RevealedMessageMarkdown(displayed, accepted?.blocksAt(index),',
                      'generationComplete = currentComplete.value && displayed == text.text, deferBlockReveal = true,'):
            self.assertIn(token, text_body)
        self.assertEqual(1, assistant.count('val textBody = rememberAssistantTextBody('))
        self.assertEqual(2, assistant.count('textBody = textBody'))
        self.assertIn('MessageContent(ordered.leading, actions, imageLoader, markdown = true, textBody = textBody)', assistant)
        self.assertIn('WorkflowNarrationContent(steps, plainText, textSources,', self.chat)
        self.assertIn("ProjectedMessageMarkdown(text, blocks, presented, reveal.limit(partId), generationComplete, sourceWindow = sourceWindow)", self.ui)
        content = (BASE / "MessageContent.kt").read_text()
        self.assertIn('key(block.workflowKey(index))', content)
        self.assertIn("textBody: @Composable (MessageContentUi.Text, Modifier)", content)
        self.assertIn("textBody(block, Modifier.testTag", content)
        self.assertIn("UserMessageText(text.text, modifier)", self.chat)

    def test_projection_ready_and_reveal_settled_are_separate_current_turn_gates(self):
        self.assertIn("SideEffect { onContentReady(contentReady) }", self.chat)
        self.assertIn("val revealSettled = reveal.settled", self.chat)
        self.assertIn("SideEffect { onRevealSettled(revealSettled) }", self.chat)
        self.assertEqual(1, self.chat.count("revealedTurns[it.listKey] == it"))
        self.assertIn("completedVisibleTurns(visibleKeys, currentAssistants, readyTurns, revealedTurns)", self.chat)
        completed = (APP / "ui/screens/chat/conversation/CompletedVisibleTurns.kt").read_text()
        self.assertIn("revealed[it.listKey] == it", completed)
        self.assertEqual(1, self.chat.count("readyTurns[it.listKey] == it"))
        self.assertIn("ready[it.listKey] == it", completed)
        self.assertIn("onContentReady(false); onRevealSettled(false)", self.chat)
        self.assertIn("target.parts.map { it.source } == sources", self.typing)
        self.assertIn("baselineLength != null", self.typing)
        self.assertIn("target.parts == parts", self.typing)

    def test_message_actions_wait_for_the_exact_body_and_all_reconnect_reveals(self):
        assistant = self.chat.split("internal fun AssistantTurn(", 1)[1].split(
            "private fun AssistantTextContent(", 1)[0]
        gate = assistant.split("if (showActions &&", 1)[1].split("{", 1)[0]
        for condition in ("actionTurn.status != RunStatus.RUNNING", "generationComplete", "revealSettled"):
            self.assertIn(condition, gate)
        reconnect = (BASE / "ReconnectedAssistantTurn.kt").read_text()
        self.assertIn("val actionsReady = parts.parts.all { ready[it.turn.listKey] == it && settled[it.turn.listKey] == it }", reconnect)
        self.assertIn("showActions = index == parts.parts.lastIndex && actionsReady, actionTurn = turn", reconnect)

    def test_received_frontier_and_stale_target_guards_are_present(self):
        for token in ["if (target !== expected)", "val end = maxOf(previous, limit.coerceIn(0, expected.total))", "position = minOf(frameEnd.toDouble(), position + rate * seconds)",
                      "if (live || streaming) { seenLive = true; closed = false }", "if (parts == null || baselineLength == null) return target",
                      "previous[index].id == next[index].id", "next[index].text.startsWith(previous[index].text)"]:
            self.assertIn(token, self.reveal)

    def test_full_annotation_is_remembered_independently_of_visible_prefix(self):
        inline = self.ui.split("private fun MarkdownInlineText(", 1)[1].split("private fun MarkdownBlocks", 1)[0]
        self.assertIn("remember(content, linkColor, openLink)", inline)
        self.assertIn("boundaries.end(limit)", inline)
        self.assertIn("val visible = remember(body, end)", inline)
        self.assertIn("body.subSequence(0, end)", inline)
        self.assertLess(inline.index("boundaries.end(limit)"), inline.index("val visible = remember(body, end)"))
        self.assertNotIn("remember(content, linkColor, openLink, visibleCharacters)", inline)
        for token in ["BreakIterator.getCharacterInstance(Locale.ROOT)", "characters.following(limit)", "isHighSurrogate()", "isLowSurrogate()"]:
            self.assertIn(token, self.reveal)

    def test_table_width_sampling_is_bounded_and_font_scaled_without_intrinsic(self):
        table = self.ui.split("private fun MarkdownTable(", 1)[1].split("private fun headingSize", 1)[0]
        self.assertNotIn("renderInline", table)
        self.assertNotIn("IntrinsicSize", self.ui)
        for token in ["MarkdownText.tableColumnWidthEms(table.widthHeader ?: table.header)", "style.fontSize.toDp()", "remember(columnEms, em)",
                      "table.header.size > 32", "horizontalScroll(rememberScrollState())", "Row(Modifier.drawWithCache", "rememberMarkdownVisibility(visibility, offsets[column], lengths[column])"]:
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
                      "stripAtxClosingHashes(cancel.slice(line, title.groups[2]?.range?.first ?: line.length), cancel)", "separators.all { cancel.step();", "repeat(after - i) { offset -> cancel.step();"]:
            self.assertIn(token, self.parser)

    def test_link_prepass_reuses_the_budgeted_destination_scan_and_safety_boundary(self):
        self.assertNotIn("parenthesisEnds", self.parser)
        self.assertIn("val destination = destinations[i]", self.parser)
        self.assertEqual(1, self.parser.count("linkDestination(source, p + 1, work, cancel)"))
        for token in ["if (work.exhausted) return listOf(mappedText(source, cancel))", "destinations[open] = destination",
                      "p = destination.second", "MarkdownText.isSafeLink(destination.first)", "else append(i, destination.second)"]:
            self.assertIn(token, self.parser)

    def test_window_identity_reaches_request_cache_typing_and_leaf(self):
        for token in ('MarkdownProjectionRequest(sources, requestedWindows)',
                      'markdownWindowsAppend(it.windows, requestedWindows)', 'sourceWindow = sourceWindow'):
            self.assertIn(token, self.ui)
        for token in ('windowMarkdownBlocks(window, parser)', 'windowBlocks[window]',
                      'markdownWindowsAppend(it.windows, snapshot.windows)'):
            self.assertIn(token, self.projection)
        self.assertIn('projection?.windows.orEmpty() == sources.map { it.window }', self.typing)
        self.assertIn('source.window == window && state.value', self.typing)
        self.assertIn('windows: List<MarkdownSourceWindow?>', self.cache)
        self.assertIn('projection.windows.filterNotNull()', self.cache)
        self.assertIn('sourceWindow = accepted?.windows?.getOrNull(index) ?: text.markdownWindow', self.chat)

    def test_reconnect_window_keeps_original_streaming_phase_in_its_identity(self):
        source = (ROOT / 'app/src/main/kotlin/app/daiyu/android/MarkdownSource.kt').read_text()
        windows = (ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation/MarkdownWindowProjection.kt').read_text()
        reconnect = (ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation/ReconnectParts.kt').read_text()
        self.assertIn('val streaming: Boolean = false', source)
        self.assertEqual(2, reconnect.count('turn.status == app.daiyu.android.domain.RunStatus.RUNNING'))
        self.assertIn('val streaming = window.streaming || parser === streamingMarkdownParser', windows)
        self.assertIn('val parser: MarkdownParserIdentity, val streaming: Boolean', windows)
        self.assertIn('MarkdownDocuments.get(window.source, parser, streaming)', windows)
        self.assertIn('MarkdownDocuments.put(window.source, parser, streaming, parsed)', windows)
        self.assertIn('before.streaming == after.streaming', self.projection)

    def test_executable_regression_sources_exist_without_claiming_they_ran(self):
        test = (ROOT / "app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/MessageMarkdownTest.kt").read_text()
        for name in ["testStreamingRevealUsesCachedAstAndFollowsReceivedUnicodeText", "testNewMixedTextUsesTurnMountBaselineAndLiveRemountDoesNotReplay",
                     "testCompletedReadWaitsForCurrentReceivedTextPublicationWithCachedProjection", "testTableWidthHintsFollowFontScaleWithoutTruncatingLongCellContent"]:
            self.assertIn(name, test)
        unit = (ROOT / "app/src/test/kotlin/app/daiyu/android/ui/screens/chat/conversation/MarkdownRevealTest.kt").read_text()
        for name in ["lateTerminalTargetsDrainToTheirExactEnd", "receivedFrontierDoesNotDependOnRefreshRate", "everyPrefixIsSurrogateAndCombiningBoundarySafe", "replacementsAndReordersNeverBorrowProgress"]:
            self.assertIn(name, unit)


    def test_raw_receipts_drive_rate_before_parser_admission(self):
        self.assertIn("remember(motion, sources, playback?.paused)", self.typing)
        self.assertIn("staticCompositionLocalOf<() -> Long> { System::nanoTime }", self.typing)
        self.assertIn("val receiptClock = LocalMarkdownReceiptClock.current", self.typing)
        self.assertIn("motion.receive(sources, receiptClock(), paused = playback?.paused == true)", self.typing)
        self.assertLess(self.typing.index("motion.receive(sources,"), self.typing.index("motion.update(parts,"))
        self.assertIn("receiptRate = added / seconds", self.reveal)
        self.assertIn("if (observesReceipts) desiredRate = receiptRate.takeIf { it > 0.0 } ?: rate", self.reveal)
        self.assertIn("desiredRate = arrived / arrivalSeconds", self.reveal)
        self.assertNotIn("desiredRate = (end - position)", self.reveal)


if __name__ == "__main__":
    unittest.main()
