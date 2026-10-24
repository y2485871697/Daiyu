"""Source contracts only; native-window input requires Android regression."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/IntegrationSettingsActivityTest.kt'


class ChatPickerResumeFocusContractTest(unittest.TestCase):
    def body(self):
        text = SOURCE.read_text(encoding='utf-8')
        return text.split('fun testPausedChatModelPickerRevokesOldSelectionAndCannotBorrowResumedOwner()', 1)[1].split('fun testDismissedChatModelPickerCannotReviveFromPendingCatalog()', 1)[0]

    def test_first_open_waits_for_the_clicked_nodes_native_window_and_enabled_state(self):
        body = self.body()
        focus = 'awaitNodeWindowFocus(modelButton)'
        click = 'modelButton.assertIsDisplayed().assertIsEnabled().performClick()'
        self.assertEqual(1, body.count(focus))
        self.assertEqual(1, body.count(click))
        self.assertEqual(1, body.count('node("chat-model").performClick()'))
        self.assertLess(body.index('val modelButton = node("chat-model")'), body.index(focus))
        self.assertLess(body.index(focus), body.index('ui.waitUntil(5_000)'))
        self.assertIn('it.getOrNull(SemanticsProperties.Disabled) == null', body)
        self.assertLess(body.index('SemanticsProperties.Disabled'), body.index(click))
        self.assertLess(body.index(click), body.index('realPauseResume(activity)'))
        for forbidden in ['Thread.sleep', 'SystemClock.sleep', 'advanceTimeBy', 'performSemanticsAction', 'repeat(']:
            self.assertNotIn(forbidden, body)

    def test_pause_revocation_and_stale_capability_oracles_remain(self):
        body = self.body()
        self.assertIn('stale = retainedChatPickerSelection()', body)
        self.assertEqual(2, body.count('mainThread { stale() }'))
        self.assertEqual(2, body.count('assertEquals(before, fixture.settings.load())'))
        self.assertIn('assertFalse(fixture.controller.dataBusy)', body)
        self.assertIn('node("chat-model-picker").assertDoesNotExist()', body)
        self.assertIn('node("chat-model-picker").assertExists()', body)
        self.assertIn('assertChatPickerOffline(calls)', body)


if __name__ == '__main__':
    unittest.main()
