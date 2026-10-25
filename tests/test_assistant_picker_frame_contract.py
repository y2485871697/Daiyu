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
                    'assistant-item-visual-b', 'assistant-avatar-visual-a', 'assistant-new', 'assistant-done'):
            self.assertIn('"' + tag + '"', lookup)
        for token in ('nodes.forEach { assertSame(root, it.root) }', '!it.layoutInfo.isPlaced',
                      '!it.layoutInfo.coordinates.isAttached', 'geometry.map { it.boundsInRoot }',
                      'node.config[SemanticsActions.GetTextLayoutResult].action', 'results.single()',
                      'input.style.fontSize', 'input.style.lineHeight', 'input.density.fontScale',
                      'input.style.fontWeight', 'input.style.color', 'result.size.height',
                      'PickerFrame(bounds, types, density.get().density)'):
            self.assertIn(token, sample)
        for forbidden in ('fetchSemanticsNode', 'performSemanticsAction', 'ui.waitUntil', 'Thread.sleep',
                          'SystemClock.sleep', '84f', 'boundsInWindow', 'unclippedBoundsInRoot'):
            self.assertNotIn(forbidden, sample)
        self.assertIn('private data class TypeFrame(', text)
        self.assertIn('private data class PickerFrame(', text)

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
