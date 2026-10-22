"""Source-only guardrails; held-read Android regressions need an approved build."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'
DIALOG = SRC / 'ui/screens/chat/conversation/ChatUsageDialog.kt'


class ChatUsageReadyContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = DIALOG.read_text()
        cls.tests = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ChatUsageDialogTest.kt').read_text()

    def test_read_effect_is_registered_before_hidden_pending_gate(self):
        effect = self.source.index('LaunchedEffect(controller, conversationId)')
        gate = self.source.index('if (loading) {')
        dialog = self.source.index('UsageGlassDialog(')
        self.assertLess(effect, gate)
        self.assertLess(gate, dialog)
        pending = self.source[gate:dialog]
        self.assertIn('BackHandler(onBack = onDismiss)', pending)
        self.assertIn('return', pending)
        self.assertNotIn('Text(', pending)

    def test_loading_creates_neither_text_nor_empty_dialog_content(self):
        self.assertNotIn('正在读取', self.source)
        self.assertNotIn('loading ->', self.source)
        self.assertEqual(1, self.source.count('UsageGlassDialog('))
        self.assertIn('compact = true, adaptiveWidth = true', self.source)

    def test_reader_keeps_current_conversation_on_io_without_global_fallback(self):
        self.assertIn('readUsage: (ConversationId) -> RecordedUsageProjection', self.source)
        self.assertIn('RecordedUsageProjection(controller.recordedRequestUsage(owner))', self.source)
        self.assertIn('val owner = conversationId ?: return@LaunchedEffect', self.source)
        io = self.source.index('withContext(Dispatchers.IO)')
        call = self.source.index('try { readUsage(owner) }')
        ready = self.source.index('loading = false')
        self.assertLess(io, call)
        self.assertLess(call, ready)
        self.assertNotIn('recordedRequestUsage()', self.source)

    def test_cancellation_is_not_reported_as_failure(self):
        self.assertIn('import androidx.activity.compose.BackHandler', self.source)
        self.assertIn('catch (cancelled: CancellationException) { throw cancelled }', self.source)
        self.assertIn('catch (_: Exception) { null }', self.source)
        self.assertNotIn('NonCancellable', self.source)

    def test_real_empty_error_and_usage_keep_separate_terminal_states(self):
        self.assertIn('conversationId == null || usage?.combined?.requestSnapshotCount == 0L', self.source)
        self.assertIn('Text("暂无用量"', self.source)
        self.assertIn('usage == null -> Text("用量读取失败"', self.source)
        self.assertIn('ConversationTokenUsageContent(usage.conversationUsage)', self.source)
        self.assertIn('remember(controller, conversationId)', self.source)
        self.assertNotIn('LaunchedEffect(readUsage', self.source)
        self.assertNotIn('delay(', self.source)

    def test_original_production_calls_keep_visit_and_dismiss_identity(self):
        main = (SRC / 'MainActivity.kt').read_text()
        self.assertIn('key(visit.token) { ChatUsageDialog(controller, visit.conversation,', main)
        self.assertIn('onDismiss = { if (usageVisit === visit) usageVisit = null }', main)
        self.assertLess(self.source.index('readUsage: (ConversationId)'), self.source.index('onDismiss: () -> Unit'))

    def test_held_read_regressions_check_absence_before_awaiting_final_dialog(self):
        for name in ('testPendingReadDoesNotMountDialogUntilRealUsageIsReady',
                     'testBackDuringReadRevokesVisitAndLateSuccessOrFailureCannotReplaceNewDialog',
                     'testFailedReadShowsRealErrorInsteadOfEmptyUsageOrLoadingPlaceholder',
                     'testUsageIsConversationOnlyAndUnattachedOrEmptyChatNeverFallsBackToAll'):
            self.assertIn('fun ' + name, self.tests)
        self.assertIn('check(Looper.myLooper() != Looper.getMainLooper())', self.tests)
        self.assertIn('assertNoIntermediateDialog()', self.tests)
        self.assertIn('finally { release.countDown() }', self.tests)
        self.assertIn('for (fail in listOf(false, true))', self.tests)
        self.assertIn('key(visit)', self.tests)
        self.assertIn('assertEquals(before, fixture.database.exportBackup())', self.tests)
        self.assertIn('assertEquals(opens, fixture.opens.get())', self.tests)


if __name__ == '__main__':
    unittest.main()
