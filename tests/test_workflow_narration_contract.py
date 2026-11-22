"""Wiring guards; actual frame/feedback behavior is exercised by composition tests."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation'


class WorkflowNarrationContract(unittest.TestCase):
    def test_pending_never_enters_either_reveal_owner(self):
        workflow = (BASE / 'AssistantWorkflowContent.kt').read_text()
        chat = (BASE / 'ChatMessageList.kt').read_text()
        self.assertIn('val split = lastStep + 1', workflow)
        self.assertIn('if (work.running) AssistantWorkflowContent(work, steps, emptyList(), pending = tail, leading = leading)', workflow)
        self.assertIn('if (work?.running == true) AssistantWorkflowContent(work, steps, emptyList(), narration, pending = body, leading = leadingContent)', workflow)
        self.assertIn('(ordered.leading + ordered.body).filterIsInstance<MessageContentUi.Text>().map { MarkdownTypingSource(it.itemId, it.text, it.markdownWindow) }', chat)
        self.assertIn('steps.filterIsInstance<MessageContentUi.Text>().map { MarkdownTypingSource(it.itemId, it.text, it.markdownWindow) }', chat)
        self.assertNotIn('ordered.pending.filterIsInstance', chat)
        self.assertIn('ordered.pending.isEmpty() && contentReady', chat)
        self.assertIn('live = turn.status == RunStatus.RUNNING || ordered.pending.isNotEmpty()', chat)

    def test_real_source_identity_and_all_trailing_text_items_are_retained(self):
        mapper = (BASE / 'ChatTurnMapper.kt').read_text()
        self.assertIn('WorkSegmentUi(narration = block.text, narrationId = block.itemId)', mapper)
        self.assertIn('val lastText = workflowFinalTextIndex(message)', mapper)
        self.assertIn('lastText >= 0 && index >= lastText', mapper)
        self.assertIn('finalContent = finalContent', mapper)
        self.assertNotIn('if (hasWorkflow && message.status == RunStatus.RUNNING) -1', mapper)

    def test_confirmed_narration_uses_existing_budget_and_actual_visible_callback(self):
        narration = (BASE / 'WorkflowNarration.kt').read_text()
        chat = (BASE / 'ChatMessageList.kt').read_text()
        self.assertIn('rememberMarkdownReveal(sources, projection', narration)
        self.assertIn('enabled = expanded && !stopped && !paused', narration)
        self.assertIn('onVisibleAdvance = onVisibleAdvance', narration)
        self.assertIn('allowBaselinePrefix = false, blockedSources = blocked', narration)
        self.assertIn('terminal = !running && projection?.sources == texts', narration)
        self.assertIn('val reveal = key(expanded, stopped)', narration)
        self.assertIn('!reasoningReady(item.reported)', narration)
        self.assertIn('currentReveal.value.visibility(text.itemId)', chat)
        self.assertIn('boundaries.end(visibility.value)', chat)
        self.assertIn('Text(displayed.take(end)', chat)
        self.assertNotIn('HapticEngine', narration)
        self.assertNotIn('TouchHaptics', narration)

    def test_received_entries_follow_composition_and_admission_survives_lazy_items(self):
        narration = (BASE / 'WorkflowNarration.kt').read_text()
        chat = (BASE / 'ChatMessageList.kt').read_text()
        content = (BASE / 'MessageContent.kt').read_text()
        typing = (BASE / 'MarkdownTyping.kt').read_text()
        self.assertIn('WorkflowVisualAdmissions(assistantTurns.keys, sentReplyKey)', chat)
        self.assertIn('val visualScope = workflowAdmissions.scope(turn.listKey, initialPositionPending)', chat)
        self.assertIn('LocalWorkflowVisualScope provides visualScope', chat)
        self.assertIn('workflowAdmissions.scope(turn.listKey, initialPositionPending).retainReplyControl(playback.control)', chat)
        self.assertIn('visualScope.replyPlayback(replyPlayback?.takeIf { it.control.matches(turn) })', chat)
        self.assertIn('admission = visualAdmission', chat)
        self.assertIn('entryVisible = narration.entryVisible', chat)
        self.assertIn('precedingReady && entryVisible(block.workflowKey(index))', content)
        self.assertIn('val playback = LocalReplyPlayback.current', narration)
        self.assertIn('val displayPaused = paused || playback?.paused == true', narration)
        self.assertIn('rememberMarkdownReveal(sources, projection, streaming = running && !displayPaused,', narration)
        self.assertIn('if (!paused && playback?.paused != true) admission?.admit(keys)', narration)
        self.assertIn('!this@WorkflowVisualAdmission.contains(item.workflowKey(index))', narration)
        self.assertIn('val entriesSettled = visualAdmission?.settled(steps) != false', chat)
        self.assertIn('(initialBaseline ?: sources).toList()', typing)
        tests = (ROOT / 'app/src/test/kotlin/app/daiyu/android/ui/screens/chat/conversation/WorkflowNarrationCompositionTest.kt').read_text()
        for method in ('newPlainWorkflowAdmitsReceivedToolsAndStopsNewAdmissionWhilePaused',
                       'newMarkdownWorkflowAdmitsReceivedToolsAndStopsNewAdmissionWhilePaused',
                       'restoredAndTerminalOwnersHaveSilentBaselinesAndSurviveLazyRemount',
                       'admittedToolsReleaseFollowingTextWithoutWaitingForLaterTools'):
            self.assertIn(method, tests)

    def test_tools_do_not_wait_for_synthetic_text_or_parser_frames(self):
        narration = (BASE / 'WorkflowNarration.kt').read_text()
        self.assertIn('SideEffect {', narration)
        self.assertIn('steps.mapIndexed { index, item -> item.workflowKey(index) }', narration)
        self.assertIn('if (!paused && playback?.paused != true) admission?.admit(keys)', narration)
        self.assertNotIn('withFrameNanos', narration)
        self.assertNotIn('presentedText', narration)
        self.assertNotIn('currentReveal.ready', narration)
        tests = (ROOT / 'app/src/test/kotlin/app/daiyu/android/ui/screens/chat/conversation/WorkflowNarrationCompositionTest.kt').read_text()
        for token in ('receivedToolsDoNotWaitForNarrationFramesOrParserCompletion',
                      'A newly received tool must wait for resume',
                      'Already admitted cards remain visible while paused'):
            self.assertIn(token, tests)

    def test_automatic_close_waits_for_narration_but_manual_close_can_finish(self):
        chat = (BASE / 'ChatMessageList.kt').read_text()
        card = (BASE / 'WorkProcessCard.kt').read_text()
        self.assertIn('workflowTextSettled = !disclosureExpanded || ((textSources.isEmpty() || settledPhase == textPhase) && entriesSettled)', chat)
        self.assertIn('if (narrationSettled) settledPhase = textPhase', chat)
        self.assertIn('finishing && reasoningReady && workflowTextSettled', card)
        self.assertIn('val expanded = currentOverride ?: (!stopped && !finished)', card)
        self.assertIn('fullyClosed && !contentMounted', card)
        self.assertIn('workflowTimeline = true', chat)

    def test_node_check_reuses_the_existing_robolectric_repository(self):
        workflow = (ROOT / '.github/workflows/chat-workflow-checks.yml').read_text()
        setup = workflow.split('          robolectric_init=()', 1)[1].split('          ./gradlew', 1)[0]
        self.assertIn('if [[ "$WORKFLOW_CHECK_SCOPE" == task-scroll || "$WORKFLOW_CHECK_SCOPE" == workflow-node-motion ]]; then', setup)
        self.assertIn("systemProperty 'robolectric.dependency.repo.url', 'https://maven-central.storage-download.googleapis.com/maven2'", setup)
        self.assertIn('robolectric_init=(-I "$RUNNER_TEMP/daiyu-robolectric-mirror.init.gradle")', setup)
        self.assertIn('          fi\n          if [[ "$WORKFLOW_CHECK_SCOPE" == task-scroll ]]; then\n            domain_tasks=(:core-domain:test --tests app.daiyu.android.domain.BottomFollowMotionTest)', setup)
        self.assertIn('-I scripts/cooperation/checks_only.init.gradle "${robolectric_init[@]}"', workflow)


if __name__ == '__main__':
    unittest.main()
