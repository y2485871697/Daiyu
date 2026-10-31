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
        effect = self.source.index('LaunchedEffect(controller, conversationId, usageRevision)')
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
        self.assertIn('readUsage: ((ConversationId) -> RecordedUsageProjection)?', self.source)
        self.assertIn('controller.conversationTokenUsage(owner)', self.source)
        self.assertIn('val owner = conversationId ?: return@LaunchedEffect', self.source)
        io = self.source.index('withContext(Dispatchers.IO)')
        call = self.source.index('try { if (readUsage != null) readUsage(owner).conversationUsage else controller.conversationTokenUsage(owner) }')
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
        self.assertIn('conversationId == null || usage?.hasUsage == false', self.source)
        self.assertIn('Text("暂无用量"', self.source)
        self.assertIn('usage == null -> Text("用量读取失败"', self.source)
        self.assertIn('ConversationTokenUsageContent(usage)', self.source)
        self.assertIn('remember(controller, conversationId)', self.source)
        self.assertNotIn('LaunchedEffect(readUsage', self.source)
        self.assertNotIn('delay(', self.source)

    def test_original_production_calls_keep_visit_and_dismiss_identity(self):
        main = (SRC / 'MainActivity.kt').read_text()
        self.assertIn('key(visit.token) { ChatUsageDialog(controller, visit.conversation,', main)
        self.assertIn('onDismiss = { if (usageVisit === visit) usageVisit = null }', main)
        self.assertLess(self.source.index('readUsage: ((ConversationId)'), self.source.index('onDismiss: () -> Unit'))

    def test_total_value_matches_preview_neutral_role_without_changing_type_or_other_rows(self):
        presentation = (SRC / 'ui/components/UsagePresentation.kt').read_text()
        metric = presentation.split('internal fun UsageMetric(', 1)[1].split('@Composable', 1)[0]
        self.assertIn('color = if (total) colors.toolbar else colors.accent, lineHeight = 22.sp)', metric)
        self.assertIn('fontSize = if (total) 16.sp else 14.sp', metric)
        self.assertIn('fontWeight = if (total) FontWeight.SemiBold else FontWeight.Medium', metric)
        content = (SRC / 'ui/app/ConversationTokenUsageDialog.kt').read_text()
        self.assertIn('UsageMetric("累计用量", tokens(usage.totalTokens), total = true)', content)
        self.assertEqual(1, content.count('total = true'))
        regression = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/UsageCompactPresentationTest.kt').read_text()
        self.assertIn('fun testTotalUsesPreviewNeutralColorWithoutChangingMetricTypographyOrAccent()', regression)
        self.assertIn('assertEquals(neutral, total.color)', regression)
        self.assertIn('for (dark in listOf(false, true))', regression)
        self.assertIn('pureBlackEnabled = pureBlack', regression)
        self.assertIn('assertEquals(accent, row.color)', regression)

    def test_cache_heading_spacing_is_local_and_regular_rows_keep_twelve_dp(self):
        content = (SRC / 'ui/app/ConversationTokenUsageDialog.kt').read_text()
        content = content.split('internal fun ConversationTokenUsageContent(', 1)[1]
        self.assertIn('    Column {\n        Column(verticalArrangement = Arrangement.spacedBy(12.dp))', content)
        self.assertEqual(2, content.count('Arrangement.spacedBy(12.dp)'))
        self.assertIn('plain = true, compact = true', content)
        self.assertIn('.padding(top = 4.dp)', content)
        self.assertNotIn('.padding(top = 10.dp)', content)
        presentation = (SRC / 'ui/components/UsagePresentation.kt').read_text()
        action = presentation.split('internal fun UsageAction(', 1)[1].split('@Composable', 1)[0]
        self.assertIn('compact: Boolean = false', action)
        self.assertIn('if (plain && compact) 28.dp else if (plain) 40.dp else 44.dp', action)
        self.assertIn('vertical = if (plain && compact) 4.dp else 8.dp', action)
        regression = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/UsageCompactPresentationTest.kt').read_text()
        self.assertIn('fun testCacheHeadingHasCompactGapsWithoutChangingMetricSpacing()', regression)
        self.assertIn('for (parentSpacing in listOf(0.dp, 12.dp))', regression)
        self.assertIn('(heading.top - output.bottom) / pixelsPerDp', regression)
        self.assertIn('(cached.top - heading.bottom) / pixelsPerDp', regression)
        self.assertIn('(created.top - cached.top) / pixelsPerDp', regression)

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
