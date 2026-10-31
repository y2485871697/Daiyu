"""Source-wiring contracts for back gestures, browser layouts and locale resources; not Android instrumentation coverage."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
TEST = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android'
NAV = ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/navigation/PageBackGestures.kt'

class R62BatchContract(unittest.TestCase):
    def test_commit_lock_uses_surviving_host_scope_and_keeps_one_frame_guard(self):
        source = NAV.read_text()
        state = source.split('private class PageBackGestureState(', 1)[1].split('private class PageBackCallbackSession', 1)[0]
        for token in ('private val scope: kotlinx.coroutines.CoroutineScope', 'fun commitBack(back: () -> Unit)',
                      'committing = true', 'scope.launch { try { withFrameNanos { } } finally { committing = false } }'):
            self.assertIn(token, state)
        handler, host = source.split('internal fun PageBackHandler', 1)[1].split('internal fun PageBackGestureHost', 1)
        self.assertNotIn('rememberCoroutineScope()', handler)
        self.assertIn('activeState.commitBack { currentBack() }', handler)
        self.assertIn('val scope = rememberCoroutineScope()', host)
        self.assertIn('PageBackGestureState(predictiveBackEnabled && Build.VERSION.SDK_INT >= 34, resumed, if (layoutDirection == LayoutDirection.Ltr) 1f else -1f, scope)', host)
        method = (TEST / 'ui/navigation/PageBackGesturesActivityTest.kt').read_text().split('fun testSwipeCommitsOnceThroughTheInnermostHandlerAndTheSwitchAppliesImmediately()', 1)[1].split('fun testShort', 1)[0]
        self.assertIn('assertEquals(1, nestedCount)', method)
        self.assertEqual(3, method.count('assertEquals(1, backCount)'))
        self.assertNotIn('Build.VERSION', method)

    def test_browser_rects_and_authority_remain_exact_after_stable_window_frames(self):
        source = (TEST / 'BrowserVisualLayoutTest.kt').read_text()
        method = source.split('fun testBusyTabKeepsItsTitleAndCloseSlotGeometryWithoutCloseAuthority()', 1)[1].split('@OptIn', 1)[0]
        self.assertLess(method.index('awaitBrowserWindowStable(chip)'), method.index('val chipBounds'))
        self.assertEqual(4, method.count('awaitBrowserWindowStable(chip)'))
        self.assertEqual(3, method.count('assertEquals(chipBounds, chip.fetchSemanticsNode().boundsInRoot)'))
        self.assertEqual(2, method.count('assertEquals(titleBounds, title.fetchSemanticsNode().boundsInRoot)'))
        for token in ('assertEquals(0, closes)', 'assertEquals(1, closes)', 'close.assertDoesNotExist()', 'listOf(.9f, 1.3f)', 'assertEquals(0, fixture.opens.get())'):
            self.assertIn(token, method)
        for forbidden in ('scrollTo', 'Thread.sleep', 'SystemClock.sleep'):
            self.assertNotIn(forbidden, method)

    def test_popup_and_locale_keep_original_assertions_with_correct_window_and_resources(self):
        source = (TEST / 'BrowserVisualLayoutTest.kt').read_text()
        method = source.split('fun testTaskBrowserKeepsOriginalPopupWithoutExtraDescriptionsOrTaskButtons()', 1)[1].split('fun testBusyTabKeeps', 1)[0]
        self.assertIn('awaitBrowserWindowStable(node("browser-visual-page"))', method)
        self.assertLess(method.index('awaitBrowserWindowStable(userAgent, requireImeHidden = true)'), method.index('userAgent.assertIsDisplayed()'))
        self.assertNotIn('scrollTo', method)
        helper = source.split('private fun awaitBrowserWindowStable', 1)[1].split('private class StateVisit', 1)[0]
        self.assertIn('awaitNodeWindowFocus(target)', helper)
        sample = helper.split('awaitStableWindowFrame(view)', 1)[1]
        self.assertIn('Triple(bounds, view.width to view.height, position.toList())', sample)
        for forbidden in ('ui.', 'fetchSemanticsNode', 'runOnIdle'):
            self.assertNotIn(forbidden, sample)
        empty = source.split('fun testEmptySheetShowsOriginalStructureWithoutCreatingBrowser()', 1)[1].split('fun testTaskBrowserKeeps', 1)[0]
        self.assertIn('LocalResources provides context.resources', empty)
        self.assertIn('assertTextEquals("没有打开的标签页")', empty)
        for forbidden in ('Locale.setDefault', 'setApplicationLocales', 'updateConfiguration'):
            self.assertNotIn(forbidden, empty)

if __name__ == '__main__':
    unittest.main()
