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
        self.assertIn('spacingAfter = if (lastIndex < content.lastIndex) 10.dp else 0.dp', entry)
        self.assertIn('leadingConnector = connector.before', entry)
        self.assertIn('trailingConnector = connector.after', entry)
        self.assertIn('throughConnector = connector.through', entry)
        self.assertLess(entry.index('entryBody('), entry.index('WorkTimelineEntry('))

    def test_top_and_bottom_bridges_draw_inside_their_own_layout_bounds(self):
        source = (CHAT / 'WorkTimelineEntry.kt').read_text()
        self.assertIn('if (leadingConnector)', source)
        self.assertIn('if (throughConnector) drawLine(line, Offset(x, 0f), Offset(x, size.height)', source)
        self.assertIn('Offset(x, 0f), Offset(x, 17.dp.toPx())', source)
        self.assertIn('Offset(x, size.height - spacingAfter.toPx()), Offset(x, size.height)', source)
        self.assertIn('.padding(bottom = spacingAfter)', source)
        for name in ('WorkProcessCard.kt', 'ReportedReasoningCard.kt'):
            row = (CHAT / name).read_text()
            self.assertIn('Offset(5.dp.toPx(), 27.dp.toPx())', row)
            self.assertIn('Offset(5.dp.toPx(), size.height)', row)
            self.assertNotIn('size.height + 3.dp.toPx()', row)
        legacy = (CHAT / 'WorkProcessCard.kt').read_text()
        self.assertEqual(2, legacy.count('WorkTimelineEntry(leadingConnector ='))

    def test_workflow_only_connects_across_narration_with_one_text_gutter(self):
        source = (CHAT / 'MessageContent.kt').read_text()
        helper = (CHAT / 'WorkflowConnectors.kt').read_text()
        self.assertIn('workflowTimeline: Boolean = false', source)
        self.assertIn('workflowConnectors(content, workflowTimeline)', source)
        self.assertIn('showConnector = connectors[index].after', source)
        self.assertIn('through = continuous && !node && index > first && index < last', helper)
        host = (CHAT / 'ChatMessageList.kt').read_text()
        self.assertIn('workflowTimeline = true', host)
        renderer = host[host.index('internal fun rememberAssistantTextBody'):host.index('private fun AssistantWorkflow(')]
        self.assertNotIn('padding(start = 18.dp)', renderer)

    def test_android_regression_samples_every_join_and_rejects_stray_end_segments(self):
        source = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/WorkTimelineConnectorTest.kt').read_text()
        for token in ('verify(legacy = false)', 'verify(legacy = true)', 'listOf(false, true)',
                      'rows.zipWithNext()', 'for (y in from..to)', 'raster.captureToImage()',
                      'first/last step must not have a stray connector',
                      'tool-step-header:connector-first', 'reported-reasoning-header:connector:reasoning:connector-thought'):
            self.assertIn(token, source)


if __name__ == '__main__':
    unittest.main()
