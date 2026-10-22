"""Source wiring checks only; Android position assertions live in androidTest."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'app/src/main/kotlin/app/daiyu/android'


def source(path):
    return (SOURCE / path).read_text()


class PagePositionContract(unittest.TestCase):
    def test_rootfs_reloads_current_directory_without_clearing_visible_rows(self):
        panel = source('ui/screens/settings/LinuxRootfsPanel.kt')
        pause = panel.split('Lifecycle.Event.ON_PAUSE)', 1)[1].split('\n', 1)[0]
        self.assertIn('request?.set(true)', pause)
        self.assertNotIn('listing = null', pause)
        self.assertIn('Lifecycle.Event.ON_RESUME) load()', panel)

    def test_shared_position_is_separate_from_loading_and_revoked_data(self):
        page = source('ui/screens/settings/WorkspaceSettingsScreen.kt')
        self.assertIn('listState = if (showShared && (!loaded || restoringDirectory)) loadingListState else listState', page)
        self.assertIn('listing = null; shownTree = null', page)
        self.assertIn('callbackEpoch = epoch.incrementAndGet()', page)
        self.assertIn('if (tree != null && tree in values) browse(tree, restoring = true)', page)
        self.assertIn('listing = result; shownTree = uri; lastTree = uri', page)
        self.assertNotIn('lastTree = uri; request', page)

    def test_gateway_keeps_view_but_renews_save_admission(self):
        host = source('MainActivity.kt')
        stop = host.split('override fun onStop() {', 1)[1].split('override fun onDestroy()', 1)[0]
        self.assertNotIn('retainedPage = "settings"', stop)
        self.assertIn('gatewayOwner?.revoke()', stop)
        self.assertIn('gatewayReloadPending = true; gatewayReplacementAuthorized = false', host)
        self.assertIn('gatewayReloadPending) loadSessionGateway(restore = true)', host)
        self.assertIn('if (owner != null && value != null) {\n                            SessionGatewaySettingsScreen', host)
        self.assertIn('oldEditor == editor && oldEditing == editing', host)
        self.assertIn('gatewayOwner !== owner', host)
        self.assertIn('gatewayOwner === owner && !gatewayReloadPending', host)

    def test_history_search_keeps_navigation_but_expires_old_callbacks(self):
        host = source('MainActivity.kt')
        pause = host.split('override fun onPause() {', 1)[1].split('override fun onStop()', 1)[0]
        self.assertNotIn('searchPage = null', pause)
        self.assertIn('historySearchEpoch++', pause)
        self.assertIn('epoch == historySearchEpoch && searchPage === page', host)
        dialog = source('ui/app/SearchHistoryDialog.kt')
        self.assertIn('visit === renderedVisit && currentOwner()', dialog)
        self.assertIn('if (!resumed) requests.cancel()', dialog)
        self.assertIn('now.generation == generation', dialog)
        self.assertIn('LaunchedEffect(request, query, resumed, renderedVisit)', dialog)

    def test_history_search_restores_fresh_pages_before_exposing_retained_position(self):
        dialog = source('ui/app/SearchHistoryDialog.kt')
        self.assertIn('state = if (useResultsPosition) listState else loadingListState', dialog)
        self.assertIn('snapshot.results.size < renderRestoreCount && snapshot.nextCursor != null', dialog)
        self.assertIn('val snapshot = requests.state.collectAsState().value', dialog)
        self.assertIn('val renderResumed = resumed', dialog)
        self.assertIn('val renderRestoring = restoring', dialog)
        self.assertIn('val renderRestoreCount = restoreCount', dialog)
        self.assertIn('renderResumed && !renderRestoring && phase == SearchPhase.Results', dialog)
        restore = dialog.split('LaunchedEffect(request, renderResumed', 1)[1].split('fun cancelSearch()', 1)[0]
        self.assertIn('snapshot.generation == requests.state.value.generation', restore)
        self.assertNotIn('val now = requests.state.value', restore)
        self.assertIn('requests.loadMore()', dialog)
        self.assertIn('renderRestoring && snapshot.appendPhase == SearchAppendPhase.ReadError', dialog)
        self.assertIn('renderRestoring && snapshot.appendPhase == SearchAppendPhase.Cancelled', dialog)
        self.assertIn('restoreCount = maxOf(restoreCount, requests.state.value.results.size)', dialog)


if __name__ == '__main__':
    unittest.main()
