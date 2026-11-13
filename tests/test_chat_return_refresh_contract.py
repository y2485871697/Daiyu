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
        returned = body(self.main, 'private fun returnToChat(refreshState: Boolean)')
        self.assertEqual('''
        dismissSidebarState()
        dismissChatModels(); modelDraft = null; editingSettings = null; editingCatalogIdentity = null; modelEditingOwner = null; modelError = null
        cancelRestorePreview(); prompt = null; searchPage = null; retainedPage = "chat"
        if (activityUsable()) {
            // A recreated video editor can only adopt its retained receipt after becoming visible.
            if (refreshState || controller.videoSendState() != null) refresh() else publishChat(rowsChanged = false)
        }
    ''', returned)
        self.assertEqual(1, returned.count('refresh()'))
        self.assertIn('private fun returnToChat() = returnToChat(refreshState = true)', self.main)
        self.assertIn('private val listener: () -> Unit = { refresh() }', self.main)
        self.assertIn('"settings", "permission-check" -> returnToChat(refreshState = false)', self.main)
        self.assertIn('"permission-check" -> app.daiyu.android.ui.screens.settings.PermissionCheckScreen(\n                        onBack = { returnToChat(refreshState = false) }', self.main)
        self.assertIn('onBack = { returnToChat(refreshState = false) }, onRoute = { route -> openSettingsFromPage(route) }', self.main)
        self.assertIn('if (skillsReturnPage == "settings") retainedPage = "settings" else returnToChat(refreshState = false)', self.main)
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

    def test_rendered_revision_is_single_entry_deep_list_projection_not_authority(self):
        prepare = body(self.main, 'private fun prepareMessageActionSnapshot()')
        lookup = 'messageActionRenderedRevision.revision(owner, shown.map { it.second })'
        self.assertIn('private val messageActionRenderedRevision = MessageActionRenderedRevision(', self.main)
        self.assertIn(lookup, prepare)
        self.assertLess(prepare.index(lookup), prepare.index('if (messageActionKey == key'))
        callback = prepare.split('controller.loadMessageActionSnapshot(owner)', 1)[1]
        self.assertNotIn('messageActionRenderedRevision', callback)
        for guard in ('messageActionVisit === visit', 'messageActionLoading == key',
                      'activityUsable() && voiceForeground', 'selected?.id == key.conversation',
                      'editorGeneration == key.editor', 'controller.dataRevision == key.data',
                      'ContextRevision.digest(rows) == key.rows', 'candidate.matchesRendered(rows)'):
            self.assertIn(guard, callback)
        self.assertIn('snapshot.matchesRendered(shown.map { it.second })', self.main)
        self.assertIn('messageActionRenderedRevision.matches(it, shown.map { row -> row.second })', self.main)
        self.assertIn('snapshot.matchesRendered(rows)', self.main)
        self.assertEqual(1, self.main.count('synchronousTrace("daiyu.chat.action-match")'))
        self.assertEqual(1, self.main.count('synchronousTrace("daiyu.chat.action-revision")'))
        cold = self.main.split('private val messageActionRenderedRevision =', 1)[1].split('private fun clearInlineMessageEditor()', 1)[0]
        self.assertIn('ContextRevision.digest(rows)', cold)
        helper = (APP / 'MessageActionRenderedRevision.kt').read_text()
        self.assertIn('private var entry: Entry? = null', helper)
        self.assertIn('it.owner == owner && it.rows == rows', helper)
        self.assertIn('current.revision ?: digest(current.rows)', helper)
        self.assertIn('snapshot.rowRevisions.toMap()', helper)
        self.assertIn('it.snapshot === snapshot && it.revisions == revisions', helper)
        self.assertIn('matcher(snapshot, current.rows)', helper)
        for owner in ('message', 'segment'):
            for field in ('blocks', 'protocolItems', 'reportedReasoning'):
                self.assertIn(f'{field} = {owner}.{field}.toList()', helper)
        self.assertIn('historySegments = message.historySegments.map', helper)
        for forbidden in ('toByteArray(', 'MessageDigest', 'hashCode(', 'IdentityHashMap',
                          'serialize', 'database.', 'JSONObject', 'isCurrent', 'MessageActionTarget'):
            self.assertNotIn(forbidden, helper)
        self.assertIn('ContextRevision::digest', helper)

    def test_real_main_regression_counts_sync_work_and_keeps_existing_scope(self):
        android = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/navigation/PageRetainedContentActivityTest.kt').read_text()
        regression = body(android, 'fun testRealMainChatReturnReusesRowsAndSidebarDismissStillRefreshesImmediately()')
        self.assertIn('openSettings()', regression)
        self.assertIn('activity.onBackPressedDispatcher.onBackPressed()', regression)
        self.assertIn('invokeHost(activity, "dismissSidebar")', regression)
        self.assertNotIn('setContent', regression)
        self.assertNotIn('assertRealChatReturn', regression)
        self.assertIn('runtime.enabled = false', regression)
        self.assertIn('recorder.frame(System.nanoTime(), 40_000_000L', regression)
        self.assertIn('expectedRefresh, refreshCount', regression)
        self.assertIn('expectedRefresh = 0', regression)
        self.assertIn('before.turns, after.turns', regression)
        self.assertIn('before.contentRevision, after.contentRevision', regression)
        video = body(android, 'fun testPureChatReturnStillAdoptsVideoReceiptThatRequiredVisibleEditor()')
        self.assertIn('fixture.controller.videoSendState()', video)
        self.assertIn('invokeHost(activity, "settleRetainedVideoReceipt")', video)
        self.assertIn('activity.onBackPressedDispatcher.onBackPressed()', video)
        self.assertIn('navigation must take the business refresh fallback', video)
        self.assertIn('listOf("permission-check", "skills")', regression)
        self.assertIn('recordSynchronous(startWindow = true) { invokeHost(activity, "returnToChat") }', regression)
        self.assertIn('1, publish.getInt("count")', regression)
        self.assertLess(regression.index('action()'), regression.index('recorder.end('))
        self.assertLess(regression.index('recorder.end('), regression.index('runtime.export()'))
        flow = (ROOT / '.github/workflows/screen-observation-jank-checks.yml').read_text()
        page_scope = flow.split("if os.environ['SCREEN_JANK_CHECK_SCOPE'] == 'page-back':", 1)[1].split('if os.environ', 1)[0]
        self.assertIn('test_chat_return_refresh_contract.py', page_scope)
        self.assertIn('"MESSAGE_ACTION_REVISION"', regression)
        self.assertIn('"MESSAGE_ACTION_MATCH"', regression)
        self.assertIn('warm Compose binding must reuse the unchanged content match', regression)
        self.assertIn('fixture.database.send(user, assistant)', regression)
        self.assertIn('message action snapshot admitted before warm returns', regression)
        self.assertIn('recordSynchronous(startWindow = true) { invokeHost(activity, "refresh") }', regression)
        for name in ('BackPerformanceRecorderTest', 'BackPerformanceRuntimeTest', 'MessageActionRenderedRevisionTest'):
            self.assertEqual(2, flow.count(name))
        classes = flow.split('if [[ $SCREEN_JANK_CHECK_SCOPE == page-back ]]; then', 1)[1].split('\n          fi', 1)[0]
        expected = flow.split("if os.environ['SCREEN_JANK_CHECK_SCOPE'] == 'page-back':", 2)[2].split('suites =', 1)[0]
        self.assertIn('app.daiyu.android.MessageActionRenderedRevisionTest', classes)
        self.assertIn("'MessageActionRenderedRevisionTest'", expected)
        self.assertIn('checks_only.init.gradle', flow)


if __name__ == '__main__':
    unittest.main()
