"""Wiring checks; real system-back and IME cases live in the Android suite."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/kotlin/app/daiyu/android/MainActivity.kt"


class RootExitConfirmationContractTest(unittest.TestCase):
    def setUp(self):
        self.main = MAIN.read_text(encoding="utf-8")

    def test_root_fallback_precedes_page_and_chat_handlers(self):
        root = self.main.index('androidx.activity.compose.BackHandler(enabled = retainedPage == "chat" && !sidebarVisible)')
        page = self.main.index('BackHandler(enabled = retainedPage != "chat")')
        self.assertLess(root, page)
        host = self.main.index('app.daiyu.android.ui.navigation.PageBackGestureHost(')
        self.assertLess(root, host)
        self.assertIn('handleRootBack { hideChatInputIme(focus, keyboard, view) }', self.main[root:host])
        self.assertNotIn('previewEnabled = false', self.main[root:page])

    def test_keyboard_dismissal_cannot_arm_exit(self):
        handler = self.main.split('private fun handleRootBack(hideIme: () -> Unit) {', 1)[1].split('private var sidebarVisibleState', 1)[0]
        ime = handler.index('isVisible(WindowInsetsCompat.Type.ime())')
        confirm = handler.index('rootExitConfirmation.shouldExit(SystemClock.elapsedRealtime())')
        self.assertIn('resetRootExitConfirmation()', handler[ime:confirm])
        self.assertIn('hideIme()', handler[ime:confirm])
        self.assertIn('return', handler[ime:confirm])

    def test_confirmation_uses_short_system_toast_and_activity_finish(self):
        handler = self.main.split('private fun handleRootBack(hideIme: () -> Unit) {', 1)[1].split('private var sidebarVisibleState', 1)[0]
        self.assertIn('再按一次返回退出应用', handler)
        self.assertIn('Toast.LENGTH_SHORT', handler)
        self.assertIn('finish()', handler)
        self.assertNotIn('exitProcess', handler)
        self.assertNotIn('finishAffinity', handler)
        self.assertIn('rootExitToast?.cancel()', handler)
        self.assertIn('Lifecycle.State.RESUMED', handler)

    def test_navigation_sidebar_and_background_clear_pending_exit(self):
        sidebar = self.main.split('private var sidebarVisible: Boolean', 1)[1].split('private class ContextManagementVisit', 1)[0]
        route = self.main.split('private var retainedPage: String', 1)[1].split('if (value != "browser")', 1)[0]
        pause = self.main.split('override fun onPause() {', 1)[1].split('cancelPaneConversationSelection()', 1)[0]
        for part in (sidebar, route, pause):
            self.assertIn('resetRootExitConfirmation()', part)

    def test_pending_press_is_activity_local_not_restored(self):
        self.assertIn('private val rootExitConfirmation = RootExitConfirmation()', self.main)
        state = self.main.split('override fun onSaveInstanceState(', 1)[1].split('override fun ', 1)[0]
        self.assertNotIn('rootExitConfirmation', state)


if __name__ == "__main__":
    unittest.main()
