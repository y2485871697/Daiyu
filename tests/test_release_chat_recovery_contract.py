"""Release regression wiring; Android behavior remains an explicit cloud gate."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
CHAT = ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation'
DEVICE = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation'
class ReleaseChatRecoveryContract(unittest.TestCase):
    def test_completed_prefix_stays_in_its_parsed_coordinate_space(self):
        source = (CHAT / 'MarkdownTyping.kt').read_text()
        self.assertIn('val displayed = parsed.sources.take(requested.size)', source)
        self.assertIn('if (isMarkdownAppend(displayed, requested)) MarkdownProjection(displayed, parsed.blocks, parsed.parser)', source)
        self.assertNotIn('text.all { it in parsed.blocks }', source)
        self.assertIn('projection?.sources == sources.map { it.text }', source)
        test = (DEVICE / 'ChatResumeScrollTest.kt').read_text()
        self.assertIn('.assertCountEquals(18)', test)
        self.assertIn('releaseThird.count', test)
    def test_text_reparse_does_not_remove_stable_keyed_successors(self):
        source = (CHAT / 'MessageContent.kt').read_text()
        self.assertNotIn('textReady:', source)
        self.assertIn('precedingReady = reasoningReady(block.reported)', source)
        self.assertIn('"text:${block.itemId}"', source)
        self.assertIn('image.expectedOwner != image.reference.owner', source)
        test = (DEVICE / 'MessageMarkdownTest.kt').read_text()
        self.assertIn('"text updates must not restart the keyed image load", 1, requests.size', test)
        self.assertIn('testReorderedMixedTextKeepsEachPublishedBodyWithItsItemIdentity', test)
    def test_reasoning_sessions_rebaseline_only_at_visibility_boundaries(self):
        source = (CHAT / 'ReportedReasoningCard.kt').read_text()
        self.assertIn('val revealEnabled = !manuallyCollapsed && hasReportedReasoningText(text)', source)
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
    def test_legacy_answer_waits_for_workflow_exit_and_stays_released(self):
        source = (CHAT / 'ChatMessageList.kt').read_text().split('private fun AssistantTextContent', 1)[1]
        self.assertIn('onDisclosureHandoffReady = { ready ->', source)
        work = (CHAT / 'WorkProcessCard.kt').read_text()
        self.assertIn('sequence.ready(reasoning) && (expandedOverride == true || fullyClosed)', work)
        self.assertIn('val canReleaseWithoutWorkflow = reasoningReady && (work == null || workReasoning.isEmpty())', source)
        self.assertIn('answerReleased || canReleaseWithoutWorkflow', source)
        self.assertIn('if (ready && reasoningReady && turn.finalText.isNotBlank()) answerReleased = true', source)
        self.assertIn('testExplicitlyOpenLegacyWorkflowDoesNotBlockItsNewAnswer', (DEVICE / 'ReasoningWorkflowTest.kt').read_text())
if __name__ == '__main__':
    unittest.main()
