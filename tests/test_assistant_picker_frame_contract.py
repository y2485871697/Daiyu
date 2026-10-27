"""Source guards; actual picker geometry still requires the exact-SHA Android run."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/AssistantPickerVisualTest.kt'


class AssistantPickerFrameContractTest(unittest.TestCase):
    def test_all_geometry_and_text_are_sampled_in_one_stable_native_frame(self):
        text = SOURCE.read_text()
        helper = text.split('private fun pickerFrame(): PickerFrame {', 1)[1].split('fun testPickerKeeps', 1)[0]
        lookup, sample = helper.split('return awaitStableWindowFrame(view) {', 1)
        for tag in ('assistant-sheet', 'assistant-search', 'assistant-list', 'assistant-item-visual-a',
                    'assistant-item-visual-b', 'assistant-avatar-visual-a', 'assistant-new', 'assistant-done', 'assistant-list-meta'):
            self.assertIn('"' + tag + '"', lookup)
        for token in ('nodes.forEach { assertSame(root, it.root) }', '!it.layoutInfo.isPlaced',
                      '!it.layoutInfo.coordinates.isAttached', 'geometry.map { it.boundsInRoot }',
                      'node.config[SemanticsActions.GetTextLayoutResult].action', 'results.single()',
                      'input.style.fontSize', 'input.style.lineHeight', 'input.density.fontScale',
                      'input.style.fontWeight', 'input.style.color', 'result.size.height', 'node.boundsInRoot', 'result.firstBaseline',
                      'PickerFrame(bounds, types, density.get().density)'):
            self.assertIn(token, sample)
        for forbidden in ('fetchSemanticsNode', 'performSemanticsAction', 'ui.waitUntil', 'Thread.sleep',
                          'SystemClock.sleep', '84f', 'boundsInWindow', 'unclippedBoundsInRoot'):
            self.assertNotIn(forbidden, sample)
        self.assertIn('private data class TypeFrame(', text)
        self.assertIn('private data class PickerFrame(', text)

    def test_meta_labels_share_a_baseline_and_symmetric_insets(self):
        picker = (ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/assistants/AssistantPickerDialogs.kt').read_text()
        meta = picker.split('testTag("assistant-list-meta")', 1)[1].split('LazyColumn(', 1)[0]
        self.assertIn('.padding(horizontal = 6.dp * s, vertical = 8.dp * s)', meta)
        self.assertIn('verticalAlignment = Alignment.CenterVertically', meta)
        self.assertIn('horizontalArrangement = Arrangement.SpaceBetween', meta)
        self.assertEqual(2, meta.count('Modifier.alignByBaseline()'))
        self.assertEqual(2, meta.count('fontSize = (11f * s).sp, lineHeight = (16f * s).sp'))
        visual = SOURCE.read_text()
        for oracle in ('val meta = frame.bounds[8]', 'text(R.string.assistant_list_hint)',
                       'assertEquals(search.bottom, meta.top, .51f)',
                       'assertEquals(meta.bottom, list.top, .51f)',
                       'assertEquals(6f, dp(count.bounds.left - meta.left), .6f)',
                       'assertEquals(6f, dp(meta.right - hint.bounds.right), .6f)',
                       'minOf(count.bounds.top, hint.bounds.top) - meta.top',
                       'meta.bottom - maxOf(count.bounds.bottom, hint.bounds.bottom)',
                       'count.bounds.top + count.firstBaseline, hint.bounds.top + hint.firstBaseline'):
            self.assertIn(oracle, visual)

    def test_original_geometry_type_and_color_oracles_consume_only_the_snapshot(self):
        body = SOURCE.read_text().split('fun testPickerKeepsReadableSpacingAndOnlyAppliesFontScaleOnce()', 1)[1].split(
            'fun testActionsKeepPreviewGeometry', 1)[0]
        self.assertEqual(1, body.count('val frame = pickerFrame()'))
        for forbidden in ('layout(', 'bounds(', 'fetchSemanticsNode', 'performSemanticsAction', 'Thread.sleep'):
            self.assertNotIn(forbidden, body)
        for oracle in ('for (dark in listOf(false, true)) for (scale in listOf(1f, .8f))',
                       'fun dp(px: Float) = px / frame.density',
                       'assertEquals(84f, dp(search.top - sheet.top), .8f)',
                       'assertTrue(dp(sheet.height) in 300f..520.5f)',
                       'assertEquals(44f, dp(search.height), dp(1f) + .01f)',
                       'assertEquals(76f, dp(first.height), .6f)',
                       'assertEquals(first.center.y, avatar.center.y, .51f)',
                       'assertEquals(.9f, sample.fontScale, .001f)',
                       'assertEquals(FontWeight(650)', 'assertEquals(FontWeight(550)',
                       'Color(0xFFE0DCE8) else Color(0xFF36333F)',
                       'Color(0xFF9C92A8) else Color(0xFF968B9F)',
                       'Color(0xFF261F30) else Color.White'):
            self.assertIn(oracle, body)


if __name__ == '__main__':
    unittest.main()
