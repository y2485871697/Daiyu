"""R47 failure repairs; these source checks do not replace Android execution."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / 'app/src/main/kotlin/app/daiyu/android'
TEST = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android'


class R48BatchContract(unittest.TestCase):
    def test_compose_dismissal_has_a_native_non_editor_focus_destination(self):
        source = (MAIN / 'ui/components/ManagedInput.kt').read_text()
        clear = source.split('InputEntry(view, { state.focused }', 1)[1].split('DisposableEffect(entry)', 1)[0]
        for token in ['val root = view.rootView', 'root.isFocusableInTouchMode = true',
                      'root.requestFocus()']:
            self.assertIn(token, clear)
        self.assertLess(clear.index('root.requestFocus()'), clear.index('focus.clearFocus(force = true)'))
        self.assertNotIn('view.requestFocus()', clear)
        self.assertIn('root.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS', source)
        self.assertIn('root.descendantFocusability = previous', source)
        self.assertIn('if (state.focused) focus.clearFocus(force = true)', clear)
        self.assertIn('InputDismissal.run { clear() }', source)
        test = (TEST / 'ManagedInputActivityTest.kt').read_text()
        self.assertIn('inputView.rootView.isFocused', test)
        self.assertIn('assertEquals(5, selection.start)', test)
        self.assertIn('assertEquals(1, selection.end)', test)

    def test_compact_fill_is_system_bar_safe_and_projects_each_background_row(self):
        source = (TEST / 'ProviderSettingsLayoutActivityTest.kt').read_text()
        source = source.split('fun testCompactProviderActions', 1)[1].split('fun testConfirmedFooter', 1)[0]
        self.assertIn('Box(Modifier.fillMaxSize().systemBarsPadding())', source)
        self.assertIn('(0 until pixels.width).any { x -> sameColor(pixels[x, y]) }', source)
        for token in ['40f * density', '48.dp', '4f * density', '44f * density',
                      'paintedRows.last() - paintedRows.first() + 1', 'clicks.all { it == 2 }']:
            self.assertIn(token, source)

    def test_sheet_precondition_finishes_before_opening_and_clock_freeze(self):
        source = (TEST / 'SubAgentPresetActionsSheetTest.kt').read_text()
        setup = source.split('private fun installClosed()', 1)[1].split('private data class Geometry', 1)[0]
        for token in ['dismissIme(root)', 'awaitStableWindowFrame(root)', '!insets.isVisible(ime)',
                      'insets.getInsets(ime).bottom == 0']:
            self.assertIn(token, setup)
        body = source.split('fun testEachOpeningUsesOneEntryMotion', 1)[1].split('private fun assertClosed', 1)[0]
        self.assertLess(body.index('installClosed()'), body.index('autoAdvance = false'))
        self.assertIn('repeat(2)', body)
        self.assertIn('repeat(8)', body)
        self.assertIn('first.bounds.top - settled.bounds.top, 1f', body)

    def test_return_recorder_keeps_every_frame_and_only_prepares_focus_before_background(self):
        source = (TEST / 'ProviderDirectoryResumeGeometryActivityTest.kt').read_text()
        body = source.split('fun testRealBackgroundResume', 1)[1]
        self.assertLess(body.index('realTap(row("provider-search"))'), body.index('val preDraw'))
        returned = body.split('val preDraw', 1)[1]
        for forbidden in ['dismissIme(', 'clearFocus(', 'requestFocus(']:
            self.assertNotIn(forbidden, returned)
        self.assertIn('assertEquals("return frame=$frame", 0, frame.imeBottom)', returned)
        self.assertIn('search=${tagged["provider-search"]', returned)

    def test_tool_color_diagnostic_keeps_the_original_oracle(self):
        source = (TEST / 'ui/screens/chat/conversation/ToolOperationCardTest.kt').read_text()
        body = source.split('fun testCommandAndShortLog', 1)[1].split('fun testLongOutput', 1)[0]
        for token in ['tool-swatch:backdrop', 'tool-swatch:soft', 'tool-swatch:alpha', 'tool-swatch:opaque',
                      'captureScreenNode(root)', 'root.captureToImage()', 'ToolFrameDiagnostic',
                      'expected.red, actual.red, .025f', 'expected.green, actual.green, .025f',
                      'expected.blue, actual.blue, .025f']:
            self.assertIn(token, body)
        self.assertLess(body.index('val actual ='), body.index('root.captureToImage()'))
        self.assertIn('0x13BEA8D7 else 0x108A779A).compositeOver(backdrop)', body)


if __name__ == '__main__':
    unittest.main()
