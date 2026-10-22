"""Wiring guards; actual window/IME behavior is verified by Android instrumentation."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
TEST = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android'


class ImeFrameContractTest(unittest.TestCase):
    def test_native_frames_are_unique_bounded_and_removed_on_failure(self):
        text = (TEST / 'ComposeActivityTestCase.kt').read_text()
        helper = text.split('protected fun <T : Any> awaitStableWindowFrame', 1)[1].split('protected fun awaitImeFrame', 1)[0]
        self.assertIn('override fun doFrame(frameTimeNanos: Long)', helper)
        self.assertIn('OnPreDrawListener', helper)
        self.assertIn('frameNanos != lastFrame', helper)
        self.assertIn('next != null && next == previous', helper)
        self.assertIn('stableFrames >= 2', helper)
        self.assertIn('ui.waitUntil(5_000)', helper)
        self.assertIn('finally {', helper)
        self.assertIn('active = false', helper)
        self.assertIn('removeFrameCallback(frameCallback)', helper)
        self.assertIn('removeOnPreDrawListener(listener)', helper)
        callback = helper.split('val listener = ', 1)[1].split('\n        try {', 1)[0]
        self.assertNotIn('fetchSemanticsNode()', callback)
        self.assertNotIn('waitForIdle()', callback)
        self.assertNotIn('frameTimeNanos()', helper)

    def test_composer_reads_geometry_with_insets_and_independent_keyboard_edge(self):
        text = (TEST / 'ChatComposerImeActivityTest.kt').read_text()
        sample = text.split('return awaitStableWindowFrame(view) {', 1)[1].split('private fun assertRealApi26ImeBinding', 1)[0]
        for fragment in ['ViewCompat.getRootWindowInsets(view)', 'decor.getLocationOnScreen(location)',
                         'bottom(surface), bottom(header)', 'checkNotNull(before).decorBottom - imeBottom',
                         'imeBottom > 0', 'imeBottom == 0']:
            self.assertIn(fragment, sample)
        self.assertNotIn('mainThread {', sample)
        self.assertNotIn('screenBottom(', text)
        self.assertIn('awaitImeFrame(node("chat-draft"), visible = true)', text)
        self.assertIn('repeat(2)', text)
        self.assertIn('KeyEvent.KEYCODE_BACK', text)
        self.assertIn('bottom < before.composerBottom - 48f * density', text)
        self.assertIn('before.headerBottom, shown.headerBottom, 2f', text)
        self.assertIn('before.composerBottom, restored.composerBottom, 2f', text)
        self.assertIn('assertEquals(database, fixture.database.exportBackup())', text)
        self.assertNotIn('showSoftInput(', text)
        pointer = text.split('repeat(2)', 1)[1].split('assertRealApi26ImeBinding()', 1)[0]
        self.assertIn('performTouchInput {', pointer)
        self.assertLess(pointer.index('advanceEventTime(android.view.ViewConfiguration.getDoubleTapTimeout().toLong() + 1)'),
                        pointer.index('click()'))
        self.assertNotIn('Thread.sleep', pointer)

    def test_voice_completes_show_hide_before_navigation_back(self):
        text = (TEST / 'VoiceSettingsStructureActivityTest.kt').read_text()
        body = text.split('fun testDoubaoSubpagesDoNotStartNetworkAndBackPreservesWizard()', 1)[1]
        show = body.index('awaitImeFrame(node("settings-field:声音名称"), visible = true)')
        navigate = body.index('reveal("settings-row:查找我的音色名额"')
        self.assertLess(show, navigate)
        self.assertEqual(2, body.count('awaitImeFrame(node("settings-field:AK"), visible = false)'))
        self.assertEqual(2, body.count('sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)'))
        self.assertIn('awaitUniqueTag("page:制作声音")', body)
        self.assertIn('assertTextContains("local draft")', body)
        self.assertIn('assertEquals(0, fixture.opens.get())', body)


if __name__ == '__main__':
    unittest.main()
