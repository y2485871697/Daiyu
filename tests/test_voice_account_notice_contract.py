"""Wiring checks complement the executable account-feedback and catalog JVM tests."""
from pathlib import Path
import unittest
from test_voice_sync_layout_contract import body

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/app/daiyu/android"

class VoiceAccountNoticeContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.panel = (SRC / "VoiceAccountPanel.kt").read_text()
        cls.host = (SRC / "ProductionVoiceAccountPage.kt").read_text()

    def test_page_and_wizard_navigation_advance_notice_owner(self):
        for marker in ('fun navigate(target:', 'fun back()'):
            self.assertIn('clearPageFeedback()', body(self.panel, marker))
        for step in (1, 2):
            self.assertIn(f'clearPageFeedback(); step = {step}', self.panel)
        self.assertIn('feedback = feedback.nextVisit(); latestPageChanged()', self.panel)
        self.assertIn('preview.stop()', body(self.panel, 'fun clearPageFeedback()'))

    def test_async_messages_and_confirmation_are_owned_by_their_origin(self):
        action = body(self.panel, 'fun execute(failureNotice:')
        self.assertIn('feedback.visit == origin', action)
        self.assertIn('feedback.report(origin, message)', action)
        self.assertIn('operationContext.isActive && current()', action)
        self.assertIn('currentCoroutineContext().ensureActive()', action)
        self.assertIn('feedback.visit == pending.visit', self.panel)
        self.assertIn('catch (cancelled: CancellationException) { throw cancelled }', action)

    def test_host_uses_the_same_owner_for_both_sync_and_async_failures(self):
        self.assertIn('onPageChanged = { feedback = feedback.nextVisit() }', self.host)
        self.assertEqual(2, self.host.count('feedback.report(origin,'))
        self.assertIn('feedback.visit == origin) onBack()', self.host)

    def test_quota_read_does_not_report_unknown_training(self):
        sync = body(self.panel, 'if (page == "SYNC")')
        self.assertIn('名额读取失败', sync)
        self.assertIn('resources.voices.importPurchased(speech!!, a, s, p)', sync)
        self.assertNotIn('不要重复提交', sync)
        self.assertIn('制作结果未确认，请在原音色菜单查询状态，不要重复提交。', self.panel)

if __name__ == '__main__':
    unittest.main()
