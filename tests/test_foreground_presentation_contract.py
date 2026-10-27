"""Pause/resume wiring; Android frame and callback assertions run in the approved batch."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'

class ForegroundPresentationContract(unittest.TestCase):
    def test_graphics_pool_survives_pause_while_gestures_do_not(self):
        host = (SRC / 'ui/navigation/PageBackGestures.kt').read_text()
        keys = host.split('val layers = remember(', 1)[1].split(') {', 1)[0]
        self.assertNotIn('resumed', keys)
        for actual_change in ('graphics', 'themeKey', 'configuration', 'insetsKey', 'density', 'layoutDirection', 'previewScopeKey'):
            self.assertIn(actual_change, keys)
        self.assertIn('predictiveBackEnabled, resumed, layers', host)
        self.assertIn('Lifecycle.Event.ON_PAUSE) layers.clearPreviews()', host)
        self.assertIn('onDispose { layers.close() }', host)
        self.assertIn('key(contentKey)', host)

    def test_suspension_clears_previews_not_the_live_graphics_layer(self):
        source = (SRC / 'ui/navigation/PageBackPreviewLayers.kt').read_text()
        body = source.split('fun clearPreviews()', 1)[1].split('fun close()', 1)[0]
        for kept in ('previewEpoch++', 'pending = null', 'it.capture?.cancel()', 'it.image = null', 'it.holdingForChild = false'):
            self.assertIn(kept, body)
        for destroyed in ('release(', 'releaseGraphicsLayer', 'frames.clear()', 'closed = true'):
            self.assertNotIn(destroyed, body)
        self.assertIn('previewEpoch == epoch', source)

    def test_pause_stop_and_resume_revoke_authority_without_clearing_draft(self):
        source = (SRC / 'MainActivity.kt').read_text()
        suspend = source.split('private fun suspendMessageActionEditor()', 1)[1].split('private fun prepareMessageActionSnapshot()', 1)[0]
        authority = suspend.split('private fun invalidateMessageActionEditor()', 1)[0]
        self.assertNotIn('inlineMessageEditor = null', authority)
        self.assertIn('messageActionHost.invalidate()', authority)
        self.assertIn('messageActionVisit = Any()', authority)
        for first, last in (('onPause()', 'onStop()'), ('onStop()', 'onDestroy()')):
            body = source.split('override fun ' + first, 1)[1].split('override fun ' + last, 1)[0]
            self.assertIn('suspendMessageActionEditor()', body)
            self.assertNotIn('invalidateMessageActionEditor()', body)
        resume = source.split('override fun onResume()', 1)[1].split('private fun applyPreferredRefreshRate()', 1)[0]
        self.assertIn('suspendMessageActionEditor()', resume)
        self.assertIn('prepareMessageActionSnapshot()', resume)
        self.assertNotIn('onEditUser(', resume)
        self.assertIn('messageActionVisit === renderedActionVisit', source)

    def test_same_conversation_data_refresh_keeps_edit_text_but_not_old_revision_authority(self):
        source = (SRC / 'MainActivity.kt').read_text()
        self.assertIn('val retainedMessageEdit = inlineMessageEditor?.takeIf { it.target.conversationId == latest?.id }', source)
        self.assertIn('inlineMessageEditor = retainedMessageEdit?.copy(visit = Any())', source)
        current = source.split('private fun messageEditTargetCurrent', 1)[1].split('private fun invalidateMessageActionEditor', 1)[0]
        self.assertIn('snapshot.matchesRendered', current)
        self.assertIn('editor.target == (snapshot.renderedTargets[editor.key] ?: snapshot.targets[editor.key])', current)
        self.assertIn('editingMessageBlocked = messageEditBlocked', source)
        self.assertIn('编辑已保留，请复制后重新编辑', source)
        models = (SRC / 'ui/screens/chat/conversation/ChatScreenModels.kt').read_text()
        self.assertIn('enabled && !mainContextCompressing && !editingMessageBlocked && !imageSendBlocked', models)

    def test_refresh_rate_is_window_owned_not_pause_owned(self):
        source = (SRC / 'MainActivity.kt').read_text()
        pause = source.split('override fun onPause()', 1)[1].split('override fun onStop()', 1)[0]
        self.assertNotIn('window.attributes =', pause)
        self.assertNotIn('previousPreferredRefreshRate = null', pause)
        resume = source.split('override fun onResume()', 1)[1].split('private fun applyPreferredRefreshRate()', 1)[0]
        self.assertNotIn('refreshRateRequestRelinquished = false', resume)
        destroy = source.split('override fun onDestroy()', 1)[1].split('private fun pageBackPreviewTarget()', 1)[0]
        self.assertIn('releasePreferredRefreshRate()', destroy)
        apply = source.split('private fun applyPreferredRefreshRate()', 1)[1].split('private fun releasePreferredRefreshRate()', 1)[0]
        self.assertIn('attributes.preferredRefreshRate == rate', apply)
        self.assertIn('refreshRateRequestRelinquished = true', apply)

    def test_browser_remount_reuses_presentation_but_not_execution_visit(self):
        main = (SRC / 'MainActivity.kt').read_text()
        target = main.split('private class BrowserResumeTarget', 1)[1].split('private fun ', 1)[0]
        self.assertIn('var presentation = app.daiyu.android.browser.BrowserSheetUiState()', target)
        self.assertIn('uiState = browserResumeTarget?.presentation', main)
        self.assertIn('closeBrowserVisualVisit(clearTarget = false)', main)
        self.assertIn('browserResumeTarget?.presentation = app.daiyu.android.browser.BrowserSheetUiState()', main)
        visual = (SRC / 'ui/screens/browser/BrowserVisualScreen.kt').read_text()
        self.assertIn('val presentation = uiState ?: remember { BrowserSheetUiState() }', visual)
        self.assertIn('BrowserSheet(pool, onBack, presentation)', visual)
        sheet = (SRC / 'browser/ported/ui/browser/BrowserSheet.kt').read_text()
        self.assertIn('uiState.displayedAddress(selectedTabId, currentURL)', sheet)
        self.assertIn('uiState.synchronize(selectedTabId, currentURL)', sheet)
        self.assertIn('uiState = uiState', sheet)
        self.assertIn('if (urlInput.text.isEmpty())', sheet)
        history = (SRC / 'browser/ported/ui/browser/BrowserHistorySheet.kt').read_text()
        self.assertIn('var searchQuery by uiState::historyQuery', history)

    def test_browser_presentation_is_memory_only_and_cannot_replay_commands(self):
        state = (SRC / 'browser/BrowserSheetUiState.kt').read_text()
        for forbidden in ('rememberSaveable', 'Bundle', 'SharedPreferences', 'BrowserVisualVisit', 'loadURL(', 'reload(', 'lease', 'token'):
            self.assertNotIn(forbidden, state.split('internal class BrowserSheetUiState', 1)[1])
        for kept in ('TextFieldValue', 'private var dirty', 'if (selectedTab == null) return', 'historyQuery'):
            self.assertIn(kept, state)

    def test_already_selected_launcher_does_not_repeat_pm_mutation(self):
        source = (ROOT / 'core-domain/src/main/kotlin/app/daiyu/android/domain/appearance/LauncherIconSwitcher.kt').read_text()
        self.assertIn('if (before.getValue(target).state != IconComponentState.ENABLED)', source)
        self.assertIn('names.forEach { components.validate(it); before[it] = components.snapshot(it) }', source)
        self.assertIn('IconComponentState.DISABLED_USER', source)

if __name__ == '__main__':
    unittest.main()
