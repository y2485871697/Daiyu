"""Window-owned non-editing focus ordering; Android execution remains the oracle."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / 'app/src/main/kotlin/app/daiyu/android'
TEST = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android'


class R49BatchContract(unittest.TestCase):
    def test_focus_order_is_held_by_window_until_real_child_focus_or_final_dispose(self):
        source = (MAIN / 'ui/components/ManagedInput.kt').read_text()
        clear = source.split('InputEntry(view, { state.focused }', 1)[1].split('DisposableEffect(entry)', 1)[0]
        self.assertNotIn('descendantFocusability', clear)
        self.assertIn('if (state.focused) focus.clearFocus(force = true)', clear)
        host = source.split('private class InputWindow', 1)[1]
        for token in ['if (restingFocusOrder != null) return',
                      'restingFocusOrder = root.descendantFocusability',
                      'root.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS',
                      'next != null && next !== decor && next.rootView === decor',
                      'decor?.findFocus() === next',
                      'if (clearing == 0 && next != null',
                      'clearing++', 'clearing--',
                      'removeOnGlobalFocusChangeListener(focusTransfer)',
                      'root.descendantFocusability = previous']:
            self.assertIn(token, host)
        dismiss = host.split('private fun dismiss(entry:', 1)[1].split('fun remove(entry:', 1)[0]
        self.assertLess(dismiss.index('holdRestingFocus()'), dismiss.index('InputDismissal.run { entry.clear() }'))
        self.assertIn('if (clearing == 0 && (decor?.isFocused != true || entries.any { it.focused() })) releaseRestingFocus()', dismiss)
        remove = host.split('fun remove(entry:', 1)[1]
        self.assertLess(remove.index('if (entries.isEmpty())'), remove.index('releaseRestingFocus()'))
        self.assertNotIn('Lifecycle.Event.ON_RESUME', source)
        self.assertNotIn('FOCUS_BLOCK_DESCENDANTS', source)

    def test_directory_uses_production_dismissal_before_recording_all_return_frames(self):
        source = (TEST / 'ProviderDirectoryResumeGeometryActivityTest.kt').read_text()
        body = source.split('fun testRealBackgroundResume', 1)[1]
        setup, returned = body.split('val preDraw', 1)
        for token in ['realTap(row("provider-search"))', 'visible = true',
                      'realTap(ui.onNodeWithText("新增提供商"))', 'visible = false',
                      'assertTrue(activity.window.decorView.isFocused)']:
            self.assertIn(token, setup)
        for forbidden in ['directoryFocusManager', 'FOCUS_BEFORE_DESCENDANTS',
                          'provider-directory-rest-focus', '.focusable()']:
            self.assertNotIn(forbidden, source)
        for forbidden in ['dismissIme(', 'clearFocus(', 'requestFocus(']:
            self.assertNotIn(forbidden, returned)
        for token in ['frames.count { !it.gateReleased && !it.actionable } >= 2',
                      'frames.count { it.gateReleased && it.actionable } >= 2',
                      'assertEquals("return frame=$frame", 0, frame.imeBottom)',
                      'assertEquals("return frame=$frame search focus", false, frame.searchFocused)',
                      'assertBounds(before, frame.geometry)',
                      'Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT']:
            self.assertIn(token, returned)


if __name__ == '__main__':
    unittest.main()
