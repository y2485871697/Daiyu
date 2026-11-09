"""The real MainActivity return path, not the Compose-only retained-chat harness."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/kotlin/app/daiyu/android'


def body(source, signature):
    start = source.index('{', source.index(signature)) + 1
    depth = 1
    for end in range(start, len(source)):
        depth += (source[end] == '{') - (source[end] == '}')
        if depth == 0:
            return source[start:end]
    raise AssertionError(f'unclosed function: {signature}')


class ChatReturnRefreshContractTest(unittest.TestCase):
    def setUp(self):
        self.main = (APP / 'MainActivity.kt').read_text()

    def test_return_keeps_cleanup_order_guards_and_only_the_final_refresh(self):
        returned = body(self.main, 'private fun returnToChat()')
        self.assertEqual('''
        dismissSidebarState()
        dismissChatModels(); modelDraft = null; editingSettings = null; editingCatalogIdentity = null; modelEditingOwner = null; modelError = null
        cancelRestorePreview(); prompt = null; searchPage = null; retainedPage = "chat"
        taskRecordsConversationId = null
        if (activityUsable()) refresh()
    ''', returned)
        self.assertEqual(1, returned.count('refresh()'))
        # Shared state-only helper is deliberately used by just these two entry points.
        self.assertEqual(3, self.main.count('dismissSidebarState()'))
        state = body(self.main, 'private fun dismissSidebarState()')
        self.assertEqual('''
        sidebarVisible = false; speechHost.setVisible(retainedPage == "chat")
        imageHost.setVisible(retainedPage == "chat", selected?.id?.value)
    ''', state)
        dismiss = body(self.main, 'private fun dismissSidebar()')
        self.assertEqual('''
        dismissSidebarState()
        if (activityUsable()) refresh()
    ''', dismiss)

    def test_fixed_sync_phases_wrap_original_entry_bodies_not_composables_or_io(self):
        refresh = body(self.main, 'private fun refresh()')
        publish = body(self.main, 'private fun publishChat(rowsChanged: Boolean,')
        self.assertTrue(refresh.strip().startswith('app.daiyu.android.diagnostics.synchronousTrace("daiyu.chat.refresh") {'))
        self.assertTrue(publish.strip().startswith('app.daiyu.android.diagnostics.synchronousTrace("daiyu.chat.publish") {'))
        self.assertEqual(1, self.main.count('synchronousTrace("daiyu.chat.refresh")'))
        self.assertEqual(1, self.main.count('synchronousTrace("daiyu.chat.publish")'))
        self.assertIn('if (!activityUsable()) return', refresh)
        self.assertIn('if (controller.dataBusy)', refresh)
        self.assertIn('cancelRestorePreview(); publishChat(); return', refresh)
        self.assertIn('if (!controller.ready || controller.problem?.startsWith("会话恢复失败") == true) { publishChat(); return }', refresh)
        self.assertIn('if (dataRevision != controller.dataRevision)', refresh)
        self.assertIn('if (titleRevision != controller.titleRevision)', refresh)
        self.assertIn('private fun publishChat() = publishChat(rowsChanged = true)', self.main)
        self.assertIn('RuntimeDiagnostics.measure(RuntimeDiagnostics.Stage.UI_PUBLISH)', publish)
        self.assertIn('if (rowsChanged) refreshTimeline()', publish)
        for block in (refresh, publish):
            self.assertNotIn('@Composable', block)
            self.assertNotIn('withContext(', block)
            self.assertNotIn('delay(', block)

    def test_real_main_regression_counts_sync_work_and_keeps_existing_scope(self):
        android = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/navigation/PageRetainedContentActivityTest.kt').read_text()
        regression = body(android, 'fun testRealMainChatReturnRefreshesOnceAndSidebarDismissStillRefreshesImmediately()')
        self.assertIn('openSettings()', regression)
        self.assertIn('activity.onBackPressedDispatcher.onBackPressed()', regression)
        self.assertIn('invokeHost(activity, "dismissSidebar")', regression)
        self.assertNotIn('setContent', regression)
        self.assertNotIn('assertRealChatReturn', regression)
        self.assertIn('runtime.enabled = false', regression)
        self.assertIn('recorder.frame(System.nanoTime(), 40_000_000L', regression)
        self.assertIn('1, refresh.getInt("count")', regression)
        self.assertIn('1, publish.getInt("count")', regression)
        self.assertLess(regression.index('action()'), regression.index('recorder.end('))
        self.assertLess(regression.index('recorder.end('), regression.index('runtime.export()'))
        flow = (ROOT / '.github/workflows/screen-observation-jank-checks.yml').read_text()
        page_scope = flow.split("if os.environ['SCREEN_JANK_CHECK_SCOPE'] == 'page-back':", 1)[1].split('if os.environ', 1)[0]
        self.assertIn('test_chat_return_refresh_contract.py', page_scope)
        for name in ('BackPerformanceRecorderTest', 'BackPerformanceRuntimeTest'):
            self.assertEqual(2, flow.count(name))
        self.assertIn('checks_only.init.gradle', flow)


if __name__ == '__main__':
    unittest.main()
