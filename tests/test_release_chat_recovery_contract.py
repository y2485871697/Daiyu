"""Release regression wiring; Android behavior remains an explicit cloud gate."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
CHAT = ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation'
DEVICE = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation'
class ReleaseChatRecoveryContract(unittest.TestCase):
    def test_completed_prefix_stays_in_its_parsed_coordinate_space(self):
        source = (CHAT / 'MarkdownTyping.kt').read_text()
        self.assertIn('val acceptsProjection = projection?.let { isMarkdownAppend(it.sources.take(requested.size), requested) } == true', source)
        self.assertIn('val prefixProjection = remember(projection, requested.size, acceptsProjection)', source)
        self.assertIn('projection?.takeIf { acceptsProjection }?.let { parsed ->', source)
        self.assertIn('if (requested.size >= parsed.sources.size) parsed', source)
        self.assertIn('else MarkdownProjection(parsed.sources.take(requested.size), parsed.blocks, parsed.parser)', source)
        self.assertNotIn('text.all { it in parsed.blocks }', source)
        self.assertIn('projection?.sources == sources.map { it.text }', source)
        test = (DEVICE / 'ChatResumeScrollTest.kt').read_text()
        self.assertIn('.assertCountEquals(18)', test)
        self.assertIn('releaseThird.count', test)
    def test_text_reparse_does_not_remove_stable_keyed_successors(self):
        source = (CHAT / 'MessageContent.kt').read_text()
        self.assertNotIn('textReady:', source)
        self.assertIn('precedingReady = reasoningReady(block.reported)', source)
        self.assertIn('key(block.workflowKey(index))', source)
        workflow = (CHAT / 'AssistantWorkflowContent.kt').read_text()
        self.assertIn('is MessageContentUi.Text -> "text:$itemId"', workflow)
        self.assertIn('is MessageContentUi.Reasoning -> "reasoning:${reported.itemId}"', workflow)
        self.assertIn('is MessageContentUi.Tool -> "tool:${step.toolCallId}"', workflow)
        self.assertIn('image.expectedOwner != image.reference.owner', source)
        test = (DEVICE / 'MessageMarkdownTest.kt').read_text()
        self.assertIn('"text updates must not restart the keyed image load", 1, requests.size', test)
        self.assertIn('testReorderedMixedTextKeepsEachPublishedBodyWithItsItemIdentity', test)
    def test_reasoning_sessions_rebaseline_only_at_visibility_boundaries(self):
        source = (CHAT / 'ReportedReasoningCard.kt').read_text()
        self.assertIn('val revealEnabled = !manuallyCollapsed && (!stopped || currentOverride == true) && hasReportedReasoningText(text)', source)
        self.assertIn('val reveal = key(revealEnabled)', source)
        self.assertIn('streaming = isThinking && revealEnabled', source)
        self.assertIn('enabled = revealEnabled', source)
        self.assertIn('allowBaselinePrefix = false', source)
        typing = (CHAT / 'MarkdownTyping.kt').read_text()
        self.assertIn('allowPrefix = sources == baseline && allowBaselinePrefix', typing)
        self.assertIn('allowBaselinePrefix || isMarkdownAppend(baseline.map { it.text }, it.sources)', typing)
        self.assertIn('!markdownTypingAppends(baseline, sources)', typing)
        self.assertIn('testHistoricalExpandAndUpdateAreSilent', (DEVICE / 'ReportedReasoningTypingTest.kt').read_text())
        self.assertIn('testSameLiveItemReplacementIsSilentAndLaterAppendStillTypes', (DEVICE / 'ReportedReasoningTypingTest.kt').read_text())
        self.assertIn('testColdFirstSummaryAndHiddenAppendReopenUseSilentBaselines', (DEVICE / 'ReportedReasoningTypingTest.kt').read_text())
        self.assertLess(source.index('val summaryDisplays'), source.index('AnimatedVisibility('))
        self.assertNotIn('withFrameNanos', source)
        self.assertIn('delay(100L)', source)
        test = (DEVICE / 'ReportedReasoningTypingTest.kt').read_text()
        self.assertIn('testMixedInnerCollapseAndReopenDoNotReplayButLaterAppendStillTypes', test)
        self.assertIn('assertEquals(paused, advances)', test)
        self.assertIn('await { visibleText().contains(next) }', test)
    def test_dialog_regressions_use_real_entry_and_joint_native_frames(self):
        device = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android'
        geometry = (device / 'WorkspaceNameDialogsActivityTest.kt').read_text()
        self.assertIn('awaitDialogEnter(node("prompt-input"))', geometry)
        self.assertIn('val (cancel, confirm) = awaitStableWindowFrame(view)', geometry)
        self.assertIn('else cancelNode.boundsInRoot to confirmNode.boundsInRoot', geometry)
        self.assertIn('assertEquals(cancel.top, confirm.top, 1f)', geometry)
        self.assertIn('assertTrue(cancel.right < confirm.left)', geometry)
        shortcut = (device / 'ComposerShortcutActivityTest.kt').read_text().split('fun testPastedReferenceAndShortcutUseVisibleCoordinates()', 1)[1].split('fun testCancel', 1)[0]
        self.assertIn('awaitDialogTouchReady(entry)', shortcut)
        self.assertEqual(1, shortcut.count('entry.performClick()'))
        self.assertIn('ComposerReferences(raw).text.isEmpty()', shortcut)
        self.assertIn('assertEquals("", projection.text)', shortcut)
        self.assertIn('assertEquals(0, fixture.opens.get())', shortcut)
    def test_answer_waits_for_outer_closure_and_stays_released(self):
        source = (CHAT / 'ChatMessageList.kt').read_text().split('private fun AssistantTextContent', 1)[1]
        self.assertIn('val ordered = rememberAssistantWorkflowContent(turn)', source)
        self.assertIn('val workReasoning = remember(ordered.steps) { ordered.steps.filterIsInstance<MessageContentUi.Reasoning>().map { it.reported } }', source)
        self.assertIn('val reasoningReady = turn.reasoningStopped || handoff.ready(workReasoning)', source)
        self.assertIn('var answerReleased by rememberSaveable(turn.listKey)', source)
        self.assertIn('mutableStateOf(work == null && reasoningReady && ordered.body.isNotEmpty())', source)
        self.assertIn('val answerPending = ordered.body.isNotEmpty()', source)
        self.assertNotIn('workReasoning.isEmpty()', source)
        self.assertIn('onDisclosureHandoffReady = { if (it) onAnswerReleased() }', source)
        work = (CHAT / 'WorkProcessCard.kt').read_text()
        self.assertIn('reasoningReady && fullyClosed && !contentMounted', work)
        self.assertIn('onDispose { contentMounted = false }', work)
        self.assertIn('val finished = finishing && reasoningReady', work)
        self.assertNotIn('work.running || currentOverride == true || fullyClosed', work)
        self.assertIn('val canReleaseWithoutWorkflow = work == null && reasoningReady', source)
        self.assertIn('onAnswerReleased = { if (reasoningReady && answerPending) answerReleased = true }', source)
        self.assertIn('if (ordered.body.isNotEmpty() && (answerReleased || canReleaseWithoutWorkflow))', source)
        self.assertIn('MessageContent(ordered.body, actions, imageLoader, markdown = true,', source)
        self.assertNotIn('answerReleased = false', source)
        ordered = (CHAT / 'AssistantWorkflowContent.kt').read_text()
        self.assertIn('val body = if (finalText.isBlank()) emptyList() else listOf(MessageContentUi.Text("final", finalText))', ordered)
        self.assertIn('return AssistantWorkflowContent(work, steps, content.drop(lastStep + 1))', ordered)
        android = (DEVICE / 'ReasoningWorkflowTest.kt').read_text()
        for name in ('testTerminalAutoCollapseAndManualReopenDoNotBlockTheAnswer',
                     'testLiveAnswerWaitsForOuterWorkflowExitWithoutWaitingForTurnEnd',
                     'testToolOnlyAnswerWaitsForOuterExitInBothContentPaths'):
            self.assertIn('fun ' + name, android)
        self.assertNotIn('testDefaultOpenWorkflowReleasesTheAnswerWhileTheTurnIsStillRunning', android)
        cases = android.split('fun testLiveAnswerWaitsForOuterWorkflowExitWithoutWaitingForTurnEnd', 1)[1].split(
            'fun testStopImmediatelyRemoves', 1)[0]
        self.assertIn('ui.mainClock.autoAdvance = false', cases)
        self.assertIn('ui.mainClock.advanceTimeBy(48)', cases)
        self.assertIn('assertEquals(RunStatus.RUNNING, source.status)', cases)
        self.assertIn('for (mixed in listOf(false, true))', cases)
        self.assertIn('androidx.compose.runtime.key(id)', cases)
        self.assertGreaterEqual(cases.count('ui.onNodeWithText("answer").assertDoesNotExist()'), 6)
        self.assertIn('hasTestTag("tool-step:$id") or hasText("answer")', cases)
        self.assertIn('assertFalse("Answer cannot mount before the tool exit ($id)", hasTool && hasAnswer)', cases)
if __name__ == '__main__':
    unittest.main()
