"""Source contracts; Kotlin and device behavior are verified by the cloud gate."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / 'app/src/main/kotlin/app/daiyu/android'
TEST = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android'


class CandidateWindowPublicationContract(unittest.TestCase):
    def test_video_disposes_only_its_own_publication_and_keeps_callback_fence(self):
        source = (MAIN / 'ui/screens/chat/attachments/VideoAttachments.kt').read_text()
        self.assertIn('onControls, onControlsDisposed)', source)
        self.assertIn('val published = VideoComposerControls(', source)
        self.assertIn('controls(published)', source)
        dispose = source.split('        controls(published)', 1)[1].split('    val colors', 1)[0]
        self.assertLess(dispose.index('live = false'), dispose.index('release(published)'))
        self.assertIn('else controls(null)', dispose)
        self.assertIn('live && canSelect && sameVideoVisit(state, host.state)', source)
        self.assertIn('live && canSubmit && sameVideoVisit(state, host.state)', source)
        host = (MAIN / 'MainActivity.kt').read_text()
        self.assertIn('if (videoComposerControls === disposed) videoComposerControls = null', host)
        self.assertIn('DisposableEffect(videoHost, this@MainActivity)', host)
        self.assertNotIn('DisposableEffect(videoHost, editorGeneration)', host)

    def test_input_attach_waits_for_natural_traversal_and_cancels_registration(self):
        source = (MAIN / 'ui/components/ManagedInput.kt').read_text()
        attach = source.split('private fun bindInput(', 1)[1].split('private object InputWindows', 1)[0]
        self.assertIn('ready = ViewTreeObserver.OnPreDrawListener', attach)
        self.assertEqual(1, attach.count('it.addOnPreDrawListener(ready)'))
        self.assertIn('if (host != null) return true', attach)
        self.assertNotIn('view.post(', attach)
        self.assertNotIn('invalidate()', attach)
        self.assertIn('disposed || !view.isAttachedToWindow', attach)
        self.assertIn('val window = explicitWindow ?: view.inputWindow() ?: return false', attach)
        self.assertIn('removeOnPreDrawListener(ready)', attach)
        self.assertIn('view.windowToken == null || window.decorView.windowToken != view.windowToken', attach)
        self.assertGreaterEqual(attach.count('stopWaiting()'), 4)
        self.assertIn('disposed = true', attach)
        self.assertIn('override fun onViewDetachedFromWindow(v: View) { detach() }', attach)
        self.assertIn('host?.remove(entry); host = null', attach)

    def test_home_uses_test_driver_with_same_intent_and_keeps_original_host(self):
        source = (TEST / 'ComposeActivityTestCase.kt').read_text()
        helper = source.split('protected fun restoreOriginalHost', 1)[1].split('protected fun awaitHostWindowFocus', 1)[0]
        self.assertIn('executeShellCommand("am start -n $component -f 0x30020000")', helper)
        self.assertIn('outputTask.get(5, java.util.concurrent.TimeUnit.SECONDS)', helper)
        self.assertIn('outputTask.cancel(true)', helper)
        self.assertNotIn(' -W', helper)
        self.assertNotIn('CLEAR_TASK', helper)
        for name in ['ManagedInputActivityTest.kt', 'ChatComposerImeActivityTest.kt']:
            test = (TEST / name).read_text()
            self.assertIn('restoreOriginalHost(original)', test)
            self.assertIn('awaitHostWindowFocus(original)', test)
            self.assertIn('stopped.await(5, java.util.concurrent.TimeUnit.SECONDS)', test)
        self.assertIn('check(currentHost === host)', source)
        self.assertIn('HOME must retain the original host', (TEST / 'ChatComposerImeActivityTest.kt').read_text())

    def test_voice_closes_retained_mode_menu_before_business_entry(self):
        source = (TEST / 'ComposeActivityTestCase.kt').read_text().split('protected fun openVoiceConversationPanel()', 1)[1].split('protected fun menu(', 1)[0]
        self.assertLess(source.index('assertIsSelected()'), source.index('Espresso.pressBack()'))
        self.assertLess(source.index('Espresso.pressBack()'), source.index('performSemanticsAction(SemanticsActions.OnClick)'))
        self.assertIn('node("voice-universal-control").assertDoesNotExist()', source)
        self.assertIn('awaitUniqueTag("voice-universal-control")', source)

    def test_footer_baseline_is_stable_without_hiding_after_scroll(self):
        source = (TEST / 'SubAgentCollaborationLayoutTest.kt').read_text().split('fun testProfileFormKeepsEqualFooter', 1)[1]
        self.assertIn('dismissIme(view.rootView)', source)
        self.assertLess(source.index('dismissIme(view.rootView)'), source.index('val (deleteBounds, confirmBounds, actionsBefore)'))
        after_scroll = source.split('val speed =', 1)[1]
        self.assertNotIn('dismissIme', after_scroll)
        self.assertIn('assertEquals("Pure scrolling must keep window and IME unchanged: $geometry", windowBefore, windowAfter)', source)
        self.assertIn('assertEquals(geometry, actionsBefore.top, after.top, 1f)', source)

    def test_pixels_keep_independent_stable_baseline_and_safe_viewport(self):
        managed = (TEST / 'ManagedInputActivityTest.kt').read_text()
        self.assertIn('unfocused.contentEquals(pixels(tag))', managed)
        self.assertNotIn('val stable = hidden.contentEquals(next)', managed)
        self.assertIn('hidden.contentEquals(unfocused)', managed)
        tool = (TEST / 'ui/screens/chat/conversation/ToolOperationCardTest.kt').read_text()
        self.assertIn('.systemBarsPadding().verticalScroll(rememberScrollState())', tool)
        self.assertIn('captureScreenNode(frame).toPixelMap()', tool)
        self.assertEqual(3, tool.split('fun testCommandAndShortLogKeepLavenderFramesAndMonospace', 1)[1].split('    fun ', 1)[0].count('.025f'))


if __name__ == '__main__':
    unittest.main()
