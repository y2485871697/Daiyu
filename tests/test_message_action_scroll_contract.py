"""Keep visible history during non-navigation action reconciliation."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
ACTIVITY = ROOT / 'app/src/main/kotlin/app/daiyu/android/MainActivity.kt'
DEVICE = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/MessageActionScrollActivityTest.kt'


class MessageActionScrollContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = ACTIVITY.read_text()

    def test_delete_does_not_publish_an_empty_history_or_navigate(self):
        deleted = self.source.split('MessageActionResult.Deleted -> {', 1)[1].split('is MessageActionResult.Regenerated', 1)[0]
        self.assertIn('clearInlineMessageEditor(); refresh()', deleted)
        for forbidden in ('shown.clear()', 'select(', 'requestScroll(', 'jumpToBottom('):
            self.assertNotIn(forbidden, deleted)

    def test_uncertain_receipts_refresh_tail_without_emptying_visible_history(self):
        regenerated = self.source.split('RegenerationSendOutcome.UNKNOWN -> {', 1)[1].split('is MessageActionResult.Rejected', 1)[0]
        unknown = self.source.split('is MessageActionResult.Unknown -> {', 1)[1].split('fun currentChat()', 1)[0]
        for branch in (regenerated, unknown):
            self.assertIn('refreshMessageActionHistory()', branch)
            self.assertNotIn('shown.clear()', branch)
            self.assertNotIn('requestScroll(', branch)
        helper = self.source.split('private fun refreshMessageActionHistory()', 1)[1].split('private fun loadRefreshedHistory', 1)[0]
        self.assertIn('refreshHistoryTailConversation = selected?.id', helper)
        self.assertIn('refresh()', helper)
        self.assertNotIn('shown =', helper)

    def test_tail_reconciliation_keeps_first_boundary_and_existing_stale_result_fences(self):
        refresh = self.source.split('private fun loadRefreshedHistory(', 1)[1].split('private fun loadAcceptedHistory', 1)[0]
        self.assertIn('val reloadTail = refreshHistoryTailConversation == id', refresh)
        self.assertIn('if (reloadTail) readAcceptedWindow(id, first)', refresh)
        for guard in ('selected?.id != id', 'editorGeneration != generation',
                      'acceptedRequest != historyRequestSerial', 'content != chatState.contentRevision',
                      'data != controller.dataRevision', 'owner != loadedHistoryOwner(id)'):
            self.assertIn(guard, refresh)
        consume = refresh.index('if (reloadTail) refreshHistoryTailConversation = null')
        self.assertLess(refresh.index('if (rows == null)'), consume)
        self.assertLess(refresh.index('content != chatState.contentRevision'), consume)
        self.assertNotIn('jumpToBottom()', refresh)
        reader = self.source.split('private fun readAcceptedWindow(', 1)[1].split('private fun applyAcceptedHistory', 1)[0]
        self.assertIn('var cursor = Long.MAX_VALUE', reader)
        self.assertIn('if (cursor <= first) break', reader)
        self.assertIn('return rows.filter { it.first >= first }', reader)

    def test_intentional_branch_and_successful_regeneration_navigation_remains(self):
        self.assertIn('is MessageActionResult.Branch -> select(outcome.conversation)', self.source)
        accepted = self.source.split('if (outcome.outcome == RegenerationSendOutcome.ACCEPTED) {', 1)[1].split('else if', 1)[0]
        self.assertIn('select(outcome.conversation)', accepted)
        self.assertIn('scrollRequest = ChatScrollRequest(++scrollSerial)', self.source)

    def test_android_regressions_exercise_real_delete_delayed_io_and_new_terminal_tail(self):
        device = DEVICE.read_text()
        for token in ('testDeleteRetainsExpandedHistoryAndAnchorWhileReloadIsBlocked',
                      'testUncertainRefreshIncludesNewTerminalTailWithoutMovingAnchor',
                      'onDeleteTurn(', 'loadedWindowReader', 'release.await(',
                      'ChatMenuAction.LOAD_EARLIER', 'positionInRoot().y',
                      'assertEquals(before.scrollRequest, state().scrollRequest)',
                      'assertEquals(0, fixture.opens.get())'):
            self.assertIn(token, device)


if __name__ == '__main__':
    unittest.main()
