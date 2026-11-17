from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
CHAT = ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation'


class WorkTimelineConnectorContractTest(unittest.TestCase):
    def test_spacing_is_owned_inside_the_animated_entry_not_between_clipped_entries(self):
        source = (CHAT / 'MessageContent.kt').read_text()
        main = source[source.index('internal fun MessageContent('):source.index('private fun SpacedMessageEntry(')]
        self.assertNotIn('Arrangement.spacedBy(10.dp)', main)
        entry = source[source.index('private fun SpacedMessageEntry('):source.index('internal fun MessageImage(')]
        self.assertIn('entryBody(current.workflowKey(index)) {', entry)
        self.assertIn('val spacingAfter = if (lastIndex < lastVisibleIndex) 10.dp else 0.dp', entry)
        self.assertIn('WorkflowTimelineItem(current, lineMotion, spacingAfter)', entry)
        self.assertIn('lastVisibleIndex = visibleIndices.lastOrNull() ?: -1', main)
        self.assertIn('leadingConnector = connector.before', entry)
        self.assertIn('trailingConnector = connector.after', entry)
        self.assertIn('throughConnector = connector.through', entry)
        self.assertLess(entry.index('entryBody('), entry.index('WorkTimelineEntry('))

    def test_top_and_bottom_bridges_draw_inside_their_own_layout_bounds(self):
        source = (CHAT / 'WorkTimelineEntry.kt').read_text()
        self.assertIn('if (leadingConnector)', source)
        self.assertIn('if (throughConnector) drawLine(line, Offset(x, 0f), Offset(x, size.height * (motion.through?.fraction() ?: 1f))', source)
        self.assertIn('Offset(x, 0f), Offset(x, 17.dp.toPx() * (motion.incoming?.fraction() ?: 1f))', source)
        self.assertIn('val start = size.height - spacingAfter.toPx()', source)
        self.assertIn('if (end > start) drawLine(line, Offset(x, start), Offset(x, end)', source)
        self.assertIn('.padding(bottom = spacingAfter)', source)
        for name in ('WorkProcessCard.kt', 'ReportedReasoningCard.kt'):
            row = (CHAT / name).read_text()
            self.assertIn('Offset(5.dp.toPx(), 27.dp.toPx())', row)
            self.assertIn('Offset(5.dp.toPx(), minOf(size.height,', row)
            self.assertIn('lineMotion.outgoing?.fraction() ?: 1f', row)
            self.assertNotIn('size.height + 3.dp.toPx()', row)
        legacy = (CHAT / 'WorkProcessCard.kt').read_text()
        self.assertEqual(2, legacy.count('WorkTimelineEntry(leadingConnector ='))

    def test_workflow_only_connects_across_narration_with_one_text_gutter(self):
        source = (CHAT / 'MessageContent.kt').read_text()
        helper = (CHAT / 'WorkflowConnectors.kt').read_text()
        self.assertIn('workflowTimeline: Boolean = false', source)
        self.assertIn('workflowConnectors(visibleContent, workflowTimeline)', source)
        self.assertIn('showConnector = (connectors[index] ?: WorkflowConnector(false, false, false)).after', source)
        self.assertIn('through = continuous && !node && index > first && index < last', helper)
        host = (CHAT / 'ChatMessageList.kt').read_text()
        self.assertIn('workflowTimeline = true', host)
        renderer = host[host.index('internal fun rememberAssistantTextBody'):host.index('private fun AssistantWorkflow(')]
        self.assertNotIn('padding(start = 18.dp)', renderer)

    def test_only_user_content_opts_out_of_full_width_entries(self):
        host = (CHAT / 'ChatMessageList.kt').read_text()
        user = host[host.index('internal fun UserTurn('):host.index('internal fun AssistantTurn(')]
        self.assertIn('MessageContent(turn.content, actions, imageLoader, markdown = false, fillWidth = false,', user)
        self.assertIn('Modifier.widthIn(max = bubbleWidth)', user)
        self.assertIn('val bubbleWidth = maxWidth * 0.9f', user)
        self.assertEqual(1, host.count('fillWidth = false'))
        self.assertNotIn('IntrinsicSize', user)

    def test_width_option_reaches_the_wrapper_without_changing_timeline_defaults(self):
        source = (CHAT / 'MessageContent.kt').read_text()
        wrapper = (CHAT / 'WorkTimelineEntry.kt').read_text()
        self.assertIn('fillWidth: Boolean = true', source)
        self.assertIn('fillWidth: Boolean = true', wrapper)
        self.assertEqual(2, source.count('SpacedMessageEntry(index, content, (connectors[index] ?: WorkflowConnector(false, false, false)), entryBody, fillWidth'))
        self.assertIn('fillWidth = fillWidth,', source)
        self.assertIn('Modifier.then(if (fillWidth) Modifier.fillMaxWidth() else Modifier).drawBehind', wrapper)
        self.assertIn('WorkflowTimelineColumn(workflowTimeline) {', source)
        self.assertNotIn('IntrinsicSize', source)
        layout = (CHAT / 'WorkflowTimelineLayout.kt').read_text()
        self.assertIn('child.height, item.spacingAfter.roundToPx(), 17.dp.toPx(), 27.dp.toPx()', layout)
        self.assertIn('line?.incoming?.fraction() ?: 1f', layout)
        self.assertIn('entrance?.progress?.value ?: 0f', layout)
        self.assertIn('if (allocated > 0) extent = maxOf(extent, naturalTop + allocated)', layout)
        self.assertIn('child.placeRelative(0, top)', layout)
        self.assertIn('!prefixVisible -> 0', layout)
        self.assertIn('prefixVisible = prefixVisible && allocated >= child.height', layout)
        self.assertIn('releasedGap = maxOf(', layout)
        self.assertIn('it.consumeDisplayed(it.spacingAfter.roundToPx())', layout)
        self.assertIn('if (!LocalWorkflowTimelineLayout.current)', layout)
        self.assertNotIn('val arriving:', layout)
        self.assertNotIn('Animatable', layout)
        self.assertNotIn('animateContentSize', layout)
        self.assertIn('LocalWorkflowItemLayout.current?.appearance = appearance',
            (CHAT / 'WorkflowNodeMotion.kt').read_text())


    def test_motion_regression_requires_physical_extent_and_following_content(self):
        source = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/WorkflowNodeMotionTest.kt').read_text()
        chain = source[source.index('private fun verifyLiveChain('):source.index('fun testNewEdgesWaitForUpstreamIncoming(')]
        self.assertIn('val beforeSecondTool =', chain)
        self.assertIn('observe(listOf(t1, a, t2), beforeSecondTool)', chain)
        self.assertIn('testTag("motion-following")', chain)
        physical = source[source.index('private fun physicalSample('):source.index('private fun terminalInk(')]
        self.assertIn('ui.onNodeWithTag("motion-following").fetchSemanticsNode()', physical)
        self.assertNotIn('singleOrNull()', physical)
        self.assertNotIn('following?.let', physical)
        self.assertIn('fun testFirstFreshNodeReleasesHeightWithItsOwnEntrance()', source)
        self.assertIn('fun testQueuedNodePreventsFollowingNarrationFromReservingHeight()', source)

    def test_android_regression_samples_every_join_and_rejects_stray_end_segments(self):
        source = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/WorkTimelineConnectorTest.kt').read_text()
        for token in ('verify(legacy = false)', 'verify(legacy = true)', 'listOf(false, true)',
                      'rows.zipWithNext()', 'for (y in from..to)', 'raster.captureToImage()',
                      'first/last step must not have a stray connector',
                      'tool-step-header:connector-first', 'reported-reasoning-header:connector:reasoning:connector-thought'):
            self.assertIn(token, source)


if __name__ == '__main__':
    unittest.main()
