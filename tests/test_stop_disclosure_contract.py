from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
CHAT = ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation'

class StopDisclosureContractTest(unittest.TestCase):
    def test_stopped_thinking_is_retained_in_the_workflow_even_without_a_tool(self):
        source = (CHAT / 'ChatTurnMapper.kt').read_text()
        self.assertIn('message.status == RunStatus.STOPPED && message.reportedReasoning.isNotEmpty()', source)
        self.assertIn('stopRequested && live != null && turn.ownerMessageId == live.id.value', source)
        self.assertIn('!stopRequested && (phase == null || phase == ExecutionPhase.STREAMING)', source)

    def test_stop_resets_disclosure_and_visibility_without_waiting_for_reveal(self):
        card = (CHAT / 'ReportedReasoningCard.kt').read_text()
        for marker in ('rememberSaveable(ownerKey, stopped)', 'remember(ownerKey, stopped) { MutableTransitionState(expanded) }',
                       '(!stopped || currentOverride == true)', 'val handoffReady = stopped ||',
                       '?: (!stopped && (isThinking || observedLive && !settled))'):
            self.assertIn(marker, card)
        chat = (CHAT / 'ChatMessageList.kt').read_text()
        self.assertIn('!turn.reasoningStopped && !handoff.ready(leadingReasoning)', chat)
        self.assertGreaterEqual(chat.count('stopped = turn.reasoningStopped'), 3)

    def test_workflow_lifetime_is_independent_of_each_thought_and_leaf_details(self):
        work = (CHAT / 'WorkProcessCard.kt').read_text()
        for marker in ('val finished = !work.running && reasoningReady', 'rememberSaveable(ownerKey, finished, stopped)',
                       'val expanded = currentOverride ?: (!stopped && !finished)',
                       'reasoningReady && (work.running || currentOverride == true || fullyClosed)',
                       'rememberSaveable(step.owner, step.toolCallId) { mutableStateOf(false) }'):
            self.assertIn(marker, work)

    def test_stopped_divider_never_installs_a_click_action_or_error_sheet(self):
        divider = (CHAT / 'ChatTimelineDivider.kt').read_text()
        self.assertIn('is ChatTimelineEntry.Stopped -> "已停止"', divider)
        self.assertIn('if (entry is ChatTimelineEntry.Stopped) Modifier else Modifier.clickable', divider)
        self.assertIn('entry !is ChatTimelineEntry.ContextLimit && entry !is ChatTimelineEntry.Stopped', divider)
        self.assertIn('if (showDetails && hasDetails)', divider)
        self.assertIn('"stopped" -> "已停止重连"', divider)

    def test_projection_uses_final_physical_owner_and_retains_real_reconnect_evidence(self):
        source = (ROOT / 'app/src/main/kotlin/app/daiyu/android/timeline/StoppedTimeline.kt').read_text()
        for marker in ('MessageHistory.expandWithSources(messages)', 'sourcePhysicalIndex', 'lastByOwner[owner]',
                       'entry.status in setOf("running", "stopped")', 'ownerByAnchor[entry.anchor.listKey] in stoppedOwners'):
            self.assertIn(marker, source)
        self.assertNotIn('SQLite', source)
        store = (ROOT / 'app/src/main/kotlin/app/daiyu/android/timeline/ChatTimelineStore.kt').read_text()
        self.assertEqual(2, store.count('is ChatTimelineEntry.Stopped -> error("display_only_timeline_entry")'))
        self.assertNotIn('"kind", "stopped"', store)

    def test_main_projects_stop_boundaries_before_mapping_from_the_same_message_snapshot(self):
        source = (ROOT / 'app/src/main/kotlin/app/daiyu/android/MainActivity.kt').read_text()
        begin = source.index('val visibleTimeline = app.daiyu.android.timeline.withStoppedRuns(')
        end = source.index('turns = ChatTurnMapper.withStreamingOwner', begin)
        before_map = source[begin:end]
        self.assertIn('shown.map { it.second }, timelineEntries.takeIf', before_map)
        self.assertIn('val workflowBoundaries = visibleTimeline.filterNot', before_map)
        self.assertIn('timeline = visibleTimeline', before_map)

if __name__ == '__main__': unittest.main()
