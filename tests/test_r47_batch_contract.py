"""Source wiring for R46 follow-ups; Android assertions still require real execution."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / 'app/src/main/kotlin/app/daiyu/android'
TEST = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android'

class R47BatchContract(unittest.TestCase):
    def test_pending_back_uses_activity_dispatcher_and_dialog_keeps_window_back(self):
        text = (TEST / 'ChatUsageDialogTest.kt').read_text().split('fun testBackDuringRead', 1)[1]
        self.assertIn('activity.onBackPressedDispatcher.onBackPressed()', text)
        self.assertIn('hasEnabledCallbacks()', text)
        self.assertIn('dismissed.get() == 1', text)
        self.assertIn('awaitNodeWindowFocus(node("chat-usage-dialog"))', text)
        self.assertIn('instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)', text)

    def test_semantic_scroll_and_post_geometry_draw_fence(self):
        text = (TEST / 'ComposeActivityTestCase.kt').read_text()
        self.assertIn('targetNode.positionInRoot', text)
        self.assertIn('targetNode.size.width.toFloat()', text)
        capture = text.split('protected fun captureScreenNode', 1)[1]
        self.assertLess(capture.index('val bounds = awaitStableWindowFrame'), capture.index('registerFrameCommitCallback'))
        self.assertLess(capture.index('frame.await(2,'), capture.index('uiAutomation.takeScreenshot()'))

    def test_material_counterfactual_does_not_intervene_until_actual_is_saved(self):
        text = (TEST / 'ManagedInputActivityTest.kt').read_text()
        hidden = text.split('val hidden = pixels(tag)', 1)[1].split('realTap("managed-outside")', 1)[0]
        self.assertLess(hidden.index('hidden.contentEquals(pixels(tag))'), hidden.index('cursorlessTag.value = tag'))
        self.assertIn('hidden.contentEquals(cursorlessBaseline)', hidden)
        self.assertIn('assertEquals(before, materialState())', hidden)
        self.assertIn('finally { mainThread { cursorlessTag.value = null } }', hidden)
        self.assertIn('normalSamples.any { !it.contentEquals(transparent) }', text)
        self.assertIn('Restored production cursor must blink', text)
        self.assertIn('if (tag == "managed-first") assertTrue', text)

    def test_real_gesture_time_and_native_toolbar_close_keep_host(self):
        text = (TEST / 'ManagedInputActivityTest.kt').read_text()
        self.assertIn('MotionEvent.obtain(down, down + elapsed,', text)
        gesture = text.split('fun testLongPressAndDrag', 1)[1].split('fun testNativeFields', 1)[0]
        for token in ['onActionModeStarted', 'mode?.finish()', 'bounds.overlaps(outside)',
                      'assertSame(originalHost, activity)', 'assertSame(token, view.windowToken)',
                      'originalHost.window.callback === callback', 'awaitFieldIme("managed-first", visible = true)']:
            self.assertIn(token, gesture)
        self.assertNotIn('restoreOriginalHost(', gesture)
        self.assertNotIn('setContent', gesture)

    def test_provider_editing_normalization_is_only_before_recorder(self):
        text = (TEST / 'ProviderDirectoryResumeGeometryActivityTest.kt').read_text().split('fun testRealBackgroundResume', 1)[1]
        self.assertLess(text.index('directoryFocusManager.clearFocus(force = true)'), text.index('val before = bounds(tags)'))
        self.assertLess(text.index('dismissIme()'), text.index('val preDraw'))
        self.assertEqual(2, text.count('row("provider-search").assertIsNotFocused()'))
        returned = text.split('val preDraw', 1)[1]
        self.assertNotIn('dismissIme()', returned)
        self.assertNotIn('clearFocus', returned)
        self.assertIn('assertEquals("return frame=$frame", 0, frame.imeBottom)', returned)

    def test_diagnostics_are_debug_only_and_do_not_contain_user_values(self):
        text = (MAIN / 'ui/components/ManagedInput.kt').read_text()
        self.assertIn('if (app.daiyu.android.BuildConfig.DEBUG)', text)
        messages = [line for line in text.splitlines() if 'inputTrace(' in line and 'fun inputTrace' not in line]
        self.assertGreater(len(messages), 4)
        for line in messages:
            self.assertNotIn('.text', line)
            self.assertNotIn('selection', line)
            self.assertNotIn('windowToken}', line)

if __name__ == '__main__':
    unittest.main()
