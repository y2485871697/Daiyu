"""Active-search regression must start one accepted run, not click a disabled send action."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class SearchHostStartContractTest(unittest.TestCase):
    def test_waits_for_enabled_send_and_positive_entry_without_resending(self):
        source = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/SearchHostActivityTest.kt').read_text()
        body = source.split('fun testSearchNavigationDoesNotPauseStopOrResendActiveRun()', 1)[1]
        ready = body.index('hasTestTag("chat-send") and isEnabled()')
        send = body.index('node("chat-send").performClick()')
        entered = body.index('ui.waitUntil(5_000) { session.entered.count == 0L }')
        self.assertLess(ready, send)
        self.assertLess(send, entered)
        self.assertLess(entered, body.index('openMessages()'))
        self.assertEqual(1, body.count('node("chat-send").performClick()'))
        self.assertNotIn('session.entered.await(', body)
        self.assertLess(body.index('try {'), send)
        self.assertIn('finally { session.release.countDown() }', body)
        for assertion in ('assertFalse(fixture.controller.runState(owner)!!.pauseRequested)',
                          'assertEquals(1L, session.cancelled.count)', 'assertEquals(1, fixture.opens.get())'):
            self.assertIn(assertion, body)
