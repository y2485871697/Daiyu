"""Source wiring guards; real WebView and Activity regressions need Android execution."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/kotlin/app/daiyu/android'
ANDROID = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android'


class BrowserGestureResumeContractTest(unittest.TestCase):
    def test_browser_sheet_disables_drag_without_changing_explicit_close(self):
        source = (APP / 'browser/ported/ui/chat/StandardChatSheet.kt').read_text()
        self.assertIn('sheetGesturesEnabled = false,', source)
        self.assertIn('onDismissRequest = onDismiss,', source)
        self.assertIn('IconButton(onClick = onDismiss', source)
        self.assertIn('dragHandle = { CompactDragHandle() }', source)

    def test_webview_deltas_are_not_consumed_by_a_fake_nested_scroll_guard(self):
        source = (APP / 'browser/ported/ui/browser/BrowserSheet.kt').read_text()
        for token in ('webViewScrollGuard', 'NestedScrollConnection', 'NestedScrollSource', '.nestedScroll('):
            self.assertNotIn(token, source)
        self.assertIn('tabPool.visit.mount(it)', source)
        self.assertIn('tabPool.visit.unmount(it)', source)

    def test_automatic_reconnect_is_only_for_the_captured_human_target(self):
        main = (APP / 'MainActivity.kt').read_text()
        body = main.split('override fun onPostResume()', 1)[1].split('override fun onPause()', 1)[0]
        self.assertLess(body.index('val browserTarget = browserResumeTarget'), body.index('window.decorView.post'))
        self.assertLess(body.index('val browserEpoch = browserVisualEpoch'), body.index('window.decorView.post'))
        self.assertIn('if (browserTarget != null && browserTarget.owner == null)', body)
        self.assertIn('resumeBrowserVisualPage(browserTarget, browserEpoch)', body)
        self.assertNotIn('controller.openBrowserVisual(', body)
        self.assertNotIn('.restore()', body)

    def test_old_visit_is_still_revoked_on_both_background_callbacks(self):
        main = (APP / 'MainActivity.kt').read_text()
        for method, next_method in [('onPause', 'onStop'), ('onStop', 'onDestroy')]:
            body = main.split('override fun ' + method + '()', 1)[1].split('override fun ' + next_method + '()', 1)[0]
            self.assertIn('closeBrowserVisualVisit(clearTarget = false)', body)
            self.assertNotIn('retainedPage = "chat"', body)
        resume = main.split('private fun resumeBrowserVisualPage(', 1)[1].split('private fun resumeContextManagementPage(', 1)[0]
        for token in ('browserResumeTarget !== target', 'epoch != browserVisualEpoch', '!generationResumed()',
                      'selected?.id != target.conversationId', 'browserVisualVisit != null'):
            self.assertIn(token, resume)

    def test_runtime_regressions_check_real_scroll_and_fresh_visit(self):
        source = (ANDROID / 'BrowserPageLifecycleActivityTest.kt').read_text()
        for name in ('testHumanBrowserReconnectsAfterPauseWithFreshVisit',
                     'testTaskTargetDoesNotAutoReconnectOrFallBackToHuman',
                     'testLeavingBrowserWhilePausedCancelsAutomaticReconnect'):
            self.assertIn('fun ' + name + '()', source)
        self.assertIn('getDeclaredConstructor(String::class.java, WorkStepOwnerUi::class.java)', source)
        self.assertNotIn('declaredConstructors.single()', source)
        scroll = (ANDROID / 'BrowserScrollGestureActivityTest.kt').read_text()
        self.assertIn('android.webkit.WebView', scroll)
        self.assertIn('view.scrollY > 0', scroll)
        self.assertIn('swipeDown()', scroll)
        self.assertIn('assertEquals(0, dismissals)', scroll)

    def test_native_webview_swipes_use_real_input_timestamps_and_full_pointer_lifecycle(self):
        source = (ANDROID / 'BrowserScrollGestureActivityTest.kt').read_text()
        for token in ('view.getLocationOnScreen(location)', 'SystemClock.uptimeMillis()',
                      'InputDevice.SOURCE_TOUCHSCREEN', 'injectInputEvent(event, true)',
                      'MotionEvent.ACTION_DOWN', 'MotionEvent.ACTION_MOVE',
                      'MotionEvent.ACTION_UP', 'MotionEvent.ACTION_CANCEL', 'event.recycle()',
                      'SystemClock.sleep(minOf(16L, remaining))', 'elapsed.toFloat() / durationMillis', 'swipeDown(durationMillis = 100L)',
                      'view.scrollY < down', 'assertEquals(before, container.fetchSemanticsNode().boundsInRoot)'):
            self.assertIn(token, source)
        self.assertNotIn('performTouchInput', source)
