"""Focused wiring regressions for the recorded R56 candidate failures."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
TEST = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android'


class R59BatchContract(unittest.TestCase):
    def test_voice_account_entry_uses_the_real_text_option(self):
        source = (TEST / 'P32ProductionEntryActivityTest.kt').read_text()
        method = source.split('fun testSettingsAccountManagementIsReachableAndEntryDoesNotSynthesize()', 1)[1]
        self.assertIn('ui.onNodeWithText("我的声音").scrollToVisible().performClick()', method)
        self.assertNotIn('settings-radio:我的声音', method)
        for token in ('node("voice-section-select").performClick()', 'node("voice-account-open").performClick()',
                      'node("voice-account").assertExists()', 'assertEquals(0, fixture.opens.get())',
                      'assertNull(fixture.controller.textSendState())'):
            self.assertIn(token, method)

    def test_browser_empty_state_has_a_local_simplified_chinese_resource_premise(self):
        source = (TEST / 'BrowserVisualLayoutTest.kt').read_text()
        method = source.split('fun testEmptySheetShowsOriginalStructureWithoutCreatingBrowser()', 1)[1].split('fun testSlot', 1)[0]
        for token in ('Configuration(activity.resources.configuration)', 'Locale.forLanguageTag("zh-Hans-CN")',
                      'activity.createConfigurationContext(configuration)',
                      'assertEquals("没有打开的标签页", context.getString(R.string.om_browser_no_tab_open))',
                      'LocalContext provides context', 'LocalConfiguration provides context.resources.configuration',
                      'LocalResources provides context.resources',
                      'BrowserVisualScreen(null, { false }, { closed++ })',
                      'assertTextEquals("没有打开的标签页")', 'assertEquals(0, fixture.opens.get())'):
            self.assertIn(token, method)
        self.assertLess(method.index('LocalContext provides context'), method.index('BrowserVisualScreen('))
        for global_change in ('Locale.setDefault', 'updateConfiguration', 'setApplicationLocales'):
            self.assertNotIn(global_change, method)

    def test_navigation_fixture_freezes_page_identity_and_keeps_original_oracles(self):
        source = (TEST / 'ui/navigation/PageBackGesturesActivityTest.kt').read_text()
        for token in ('val renderedRoute = route', 'PageBackGestureHost(renderedRoute,',
                      'backTargetKey = if (renderedRoute == "test-page")',
                      'if (renderedRoute == "previous-page") Color.Red else Color.Blue',
                      'Text(renderedRoute)',
                      'assertDimmedPreviousPage(backdrop, .6f, "recorded previous page must be red and dimmed")',
                      '(alpha + previewBackground.red * (1f - alpha)) * light',
                      'actual.red, .025f', 'actual.green, .025f', 'actual.blue, .025f',
                      'assertEquals(1, nestedCount)', 'assertEquals(1, backCount)',
                      'fun testStateChangeRejectsAnInFlightPredictionAndNextGestureStillCommits()',
                      'mainThread { swipe = false }'):
            self.assertIn(token, source)
        helper = source.split('private fun swipeBack(', 1)[1].split('fun testSwipeCommits', 1)[0]
        self.assertIn('0.02f', helper)
        self.assertIn('0.80f', helper)
        self.assertIn('400)', helper)

    def test_visual_failure_retains_screen_oracle_and_collects_independent_channels(self):
        source = (TEST / 'ui/screens/chat/conversation/ToolOperationCardTest.kt').read_text()
        method = source.split('fun testCommandAndShortLogKeepLavenderFramesAndMonospace()', 1)[1].split('fun testLongOutput', 1)[0]
        for token in ('val pixels = captureScreenNode(frame).toPixelMap()',
                      'val actual = pixels[pixels.width / 2, sampleY]',
                      'assertEquals(sample, expected.red, actual.red, .025f)',
                      'assertEquals(sample, expected.green, actual.green, .025f)',
                      'assertEquals(sample, expected.blue, actual.blue, .025f)',
                      'capture("screen-root") { captureScreenNode(root).asAndroidBitmap() }',
                      'capture("compose-root")',
                      'android.view.PixelCopy.request(activity.window, bitmap',
                      '"visual-diagnostics"', 'capture("display-after")'):
            self.assertIn(token, method)
        script = (ROOT / 'scripts/smoke_boot.sh').read_text()
        trap = script.split('collect_diagnostics() {', 1)[1].split('trap collect_diagnostics EXIT', 1)[0]
        self.assertIn('timeout --kill-after=5 20 adb pull /sdcard/Android/data/app.daiyu.android/files/visual-diagnostics', trap)
        self.assertIn("FATAL EXCEPTION|Fatal signal [0-9]+|GL errors!", script)


if __name__ == '__main__':
    unittest.main()
