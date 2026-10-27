"""Independent modal routes must not replace or horizontally animate their chat backdrop."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/kotlin/app/daiyu/android'


class ModalRouteBackgroundContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.main = (APP / 'MainActivity.kt').read_text()
        cls.host = cls.main.split('app.daiyu.android.ui.navigation.PageBackGestureHost(', 1)[1].split('previewScopeKey = selected?.id,', 1)[0]

    def test_all_chat_modal_routes_keep_the_same_live_composition(self):
        for route in ('context-management', 'history-search', 'browser'):
            self.assertIn('"' + route + '" -> "chat"', self.host)
        self.assertIn('"assistant-edit" -> assistantEditReturnPage', self.host)
        self.assertIn('else -> retainedPage', self.host)

    def test_all_independent_dialog_routes_skip_page_enter_and_return_motion(self):
        motion = self.host.split('animateBackEnabled = ', 1)[1].split('canNavigateBack =', 1)[0]
        for route in ('assistant-edit', 'history-search', 'context-management', 'browser'):
            self.assertIn('retainedPage != "' + route + '"', motion)
        self.assertIn('canNavigateBack = retainedPage != "chat"', self.host)
        self.assertIn('routeKey = if (retainedPage == "chat" && sidebarVisible) "chat-sidebar" else retainedPage', self.host)

    def test_real_menu_regressions_observe_intermediate_frames_and_live_draft_identity(self):
        source = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ModalRouteBackgroundActivityTest.kt').read_text()
        for token in ('ChatMenuAction.OPEN_BROWSER, "browser", "browser-visual-page", "browser-visual-back"',
                      'ChatMenuAction.SEARCH, "history-search", "search-history-dialog"',
                      'repeat(2)', 'repeat(40)', 'ui.mainClock.autoAdvance = false',
                      'entry.performTouchInput { click() }', '"page-enter-previous"',
                      '"page-back-departure"', 'boundsInRoot', 'originalDraftId',
                      'activity.onBackPressedDispatcher.onBackPressed()',
                      'assertEquals(before, fixture.database.exportBackup())',
                      'assertEquals(0, fixture.opens.get())'):
            self.assertIn(token, source)


if __name__ == '__main__':
    unittest.main()
