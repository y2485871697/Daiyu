from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
CHAT = 'app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation/'

class ReasoningFlowStabilityContractTest(unittest.TestCase):
    def read(self, name):
        return (ROOT / (CHAT + name)).read_text()

    def test_contiguous_exact_owners_keep_their_group_through_terminal_states(self):
        s = self.read('ChatTurnMapper.kt')
        for requirement in ('previous.message.status == message.status',
                            'previous.message.role == message.role && previous.owner == owner',
                            'previous.keys.last() !in boundariesAfter',
                            'owner.listKey(segment) !in boundariesAfter',
                            '(it.blockOffset ?: 0) + offset',
                            'sourceListKeys = source.keys, sourceSegmentIndices = source.segments'):
            self.assertIn(requirement, s)
        self.assertIn('toolReceipt(original, block)', s)
        self.assertNotIn('ownerHasWorkflow', s)

    def test_compact_workflow_does_not_switch_to_bare_rows_for_a_tail(self):
        s = self.read('ChatTurnMapper.kt')
        self.assertNotIn('val orderedTail', s)
        self.assertIn('val hasWorkflow = hasTools', s)
        self.assertIn('message.blocks.none { it is MessageBlock.Image ||', s)

    def test_disclosure_follows_the_flow_phase_not_each_thought(self):
        work = self.read('WorkProcessCard.kt')
        self.assertIn('rememberSaveable(ownerKey, finished, stopped)', work)
        self.assertIn('val finished = (!work.running || answerPending) && reasoningReady', work)
        self.assertIn('val expanded = currentOverride ?: (!stopped && !finished)', work)
        self.assertIn('rememberSaveable(step.owner, step.toolCallId) { mutableStateOf(false) }', work)
        self.assertIn('AnimatedVisibility(visibility)', work)
        self.assertIn('val fullyClosed = visibility.isIdle && !visibility.currentState && !visibility.targetState', work)
        self.assertIn('val manuallyClosed = currentOverride == false && fullyClosed', work)
        card = self.read('ReportedReasoningCard.kt')
        self.assertIn('visibility.isIdle && !visibility.currentState && !visibility.targetState', card)
        self.assertNotIn('rememberSaveable(ownerKey, isThinking)', card)
        self.assertIn('key(ownerKey, summary.itemId)', card)
        self.assertNotIn('key(ownerKey, summary.itemId, disclosureExpanded)', card)

    def test_reasoning_has_no_empty_loading_body_and_keeps_its_parser_owner(self):
        s = self.read('ReportedReasoningCard.kt')
        self.assertIn('val streamingProjection = remember(ownerKey, summary.itemId)', s)
        self.assertIn('display.projection?.sources?.any(::hasReportedReasoningText) == true', s)
        self.assertIn('it.projection?.sources?.any(::hasReportedReasoningText) == true', s)

    def test_resume_epoch_does_not_reset_handoff_or_close_a_blocked_prefix(self):
        chat = self.read('ChatMessageList.kt')
        self.assertIn('remember(turn.listKey) { ReasoningHandoff(turn.reportedReasoning) }', chat)
        self.assertNotIn('remember(turn.listKey, revealResetEpoch) { ReasoningHandoff', chat)
        self.assertIn('turn.status != RunStatus.RUNNING && prefix.size == turn.content.size', self.read('MarkdownTyping.kt'))

    def test_completed_thought_has_a_check_without_tool_success_fabrication(self):
        s = self.read('ReportedReasoningCard.kt')
        self.assertIn('val complete = reported.activeSinceNanos == null', s)
        self.assertIn('if (complete) Icon(DaiyuIcons.Check', s)
        self.assertNotIn('WorkStepState.OK', s)

    def test_timeline_and_compression_async_changes_reproject_exact_aliases(self):
        main = (ROOT / 'app/src/main/kotlin/app/daiyu/android/MainActivity.kt').read_text()
        for requirement in ('val remapTurns = receiptsReady && (rowsChanged || workflowBoundaries != oldBoundaries)',
                            'boundariesAfter = workflowBoundaries',
                            'compressionActivitiesByAnchor(visibleCompressions, visibleTimeline)'):
            self.assertIn(requirement, main)
        models = self.read('ChatScreenModels.kt')
        self.assertIn('listKey in it.sourceListKeys', models)
        self.assertIn('segmentIndex in it.sourceSegmentIndices', models)

    def test_reply_actions_use_exact_tail_but_delete_and_copy_cover_the_whole_group(self):
        models = self.read('ChatScreenModels.kt')
        self.assertIn('val actionKey: String get() = sourceListKeys.lastOrNull() ?: listKey', models)
        self.assertIn('this is Assistant && key in sourceListKeys', models)
        self.assertIn('MessageActionBar(turn.actionKey, actions', self.read('ChatMessageList.kt'))
        main = (ROOT / 'app/src/main/kotlin/app/daiyu/android/MainActivity.kt').read_text()
        self.assertIn('messageCallbacks.onDeleteTurn(firstSourceKey)', main)
        self.assertIn('ChatTurnMapper.sourceMessages(turn, shown.map { it.second })', main)
        self.assertIn('first.copy(blocks = sources.flatMap { it.blocks })', main)
        self.assertIn('firstOrNull { it.matchesKey(id) }?.finalText', main)

if __name__ == '__main__':
    unittest.main()
