"""Event boundaries for real Android regressions; this suite does not execute Android."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android'

class FullRegressionEventBoundariesContractTest(unittest.TestCase):
    def test_model_search_fixture_registers_catalog_before_activity_launch(self):
        source = (ANDROID / 'ResponsesSearchSettingsActivityTest.kt').read_text()
        fixture = source.split('override fun createFixture()', 1)[1].split('private fun awaitLocalCatalogReady()', 1)[0]
        self.assertIn('super.createFixture().also', fixture)
        self.assertIn('it.settings.modelCatalogSnapshot()', fixture)
        for token in ('loadForRun().requestOptions.openAiWebSearchEnabled',
                      'assertFalse(fixture.settings.load().requestOptions.openAiWebSearchEnabled)',
                      'assertDraft("search settings unsent")'):
            self.assertIn(token, source)

    def test_oauth_success_checks_the_complete_label_and_registration_boundary(self):
        source = (ANDROID / 'ProviderOAuthCreationActivityTest.kt').read_text()
        self.assertIn('.assertTextEquals("重新登录 OpenAI")', source)
        self.assertIn('awaitLoginButton("重新登录 OpenAI", hits)', source)
        self.assertIn('assertEquals(before, fixture.controller.providers.snapshot())', source)

    def test_long_press_releases_the_same_chat_root_without_losing_pointer_oracle(self):
        source = (ANDROID / 'RunControlActivityTest.kt').read_text()
        test = source.split('fun testOtherConversationCannotControlOwnerAndLongClickStopsWithoutPause()', 1)[1].split('fun testGestureOwnerCannotRetargetNextRunBeforeRelease()', 1)[0]
        self.assertIn('isRoot() and hasAnyDescendant(hasTestTag("chat-draft"))', test)
        self.assertIn('performTouchInput { down(center) }', test)
        self.assertIn('root.performTouchInput { up() }', test)
        self.assertIn('session.cancelled.await(5, TimeUnit.SECONDS)', test)
        self.assertIn('assertEquals(RunStatus.STOPPED, saved.status)', test)

    def test_video_result_waits_for_exactly_one_picker_launch(self):
        source = (ANDROID / 'ui/screens/chat/attachments/VideoAttachmentsUiTest.kt').read_text()
        test = source.split('fun testCompactComposerBlockedVideoNeverSendsCaptionAndConfirmationFreezesText()', 1)[1].split('private fun pick(', 1)[0]
        self.assertLess(test.index('picker.launches.size == 1'), test.index('picker.result(picker.launches.single())'))
        self.assertIn('ui.mainClock.advanceTimeByFrame()', test)
        self.assertIn('assertEquals("first exact text", handed?.text)', test)
        self.assertIn('assertEquals("first exact text", frozenEditor?.text)', test)

    def test_ordinary_gui_fallback_observes_real_model_entry(self):
        source = (ANDROID / 'GuiMainActivityTest.kt').read_text()
        test = source.split('fun testInvalidFullParametersNeverReserveOrSaveAndOrdinarySendRemainsOrdinary()', 1)[1].split('fun testDisabledServiceRejects', 1)[0]
        for token in ('assertTrue(it())', 'ordinary.entered.count == 0L && fixture.opens.get() == 1',
                      'awaitTextSendResult(activity)', 'assertNull(fixture.controller.guiEntry())',
                      'assertTrue(fixture.database.conversations().isEmpty())'):
            self.assertIn(token, test)

    def test_timeline_samples_each_actual_line_without_relaxing_pixel_geometry(self):
        source = (ANDROID / 'ui/screens/chat/conversation/ChatTimelineDividerTest.kt').read_text()
        for token in ('minOf(maxWidth, 360.dp)', 'Modifier.fillMaxWidth().statusBarsPadding()', 'listOf("left", "right")',
                      'timeline-line-$side:contrast', 'lineBounds.center.x - rowBounds.left',
                      '.5.dp.roundToPx()', 'stripe.size', 'stripe.any { distance(it, expected) < .035f }',
                      'distance(previous, background) + .04f'):
            self.assertIn(token, source)
        self.assertNotIn('pixels.width - 1 - inset', source)

if __name__ == '__main__':
    unittest.main()
