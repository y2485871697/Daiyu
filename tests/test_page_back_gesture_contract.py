"""Source-wiring guards; device behavior is covered by the deferred Android tests."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/kotlin/app/daiyu/android'
HOST = APP / 'ui/navigation/PageBackGestures.kt'


class PageBackGestureContractTest(unittest.TestCase):
    def setUp(self):
        self.host = HOST.read_text(encoding='utf-8')
        self.main = (APP / 'MainActivity.kt').read_text(encoding='utf-8')

    def test_production_host_consumes_both_existing_preferences(self):
        for field in ('swipeDismissEnabled', 'predictiveBackEnabled'):
            self.assertIn(f'{field} = appearance.{field}', self.main)
        self.assertIn('dispatchBack = { onBackPressedDispatcher.onBackPressed() }', self.main)
        self.assertIn('canNavigateBack = retainedPage != "chat"', self.main)

    def test_root_registration_precedes_page_content_and_native_prompts_stay_outside(self):
        start = self.main.index('app.daiyu.android.ui.navigation.PageBackGestureHost(')
        handler = self.main.index('BackHandler(enabled = retainedPage != "chat")', start)
        route = self.main.index('\n                when (if (retainedPage == \"assistant-edit\") assistantEditReturnPage else retainedPage) {', handler)
        prompts = self.main.index('\n                TerminalTasksSurface()', route)
        self.assertLess(start, handler)
        self.assertLess(handler, route)
        self.assertIn('\n                }\n                TerminalTasksSurface()', self.main[route:prompts + 60])

    def test_nested_pages_share_the_same_wrapper_without_rewriting_their_back_actions(self):
        for file in ('VoiceSettingsPanel.kt', 'ProductionVoiceAccountPage.kt', 'VoiceAccountPanel.kt',
                     'ui/SubAgentSettingsScreen.kt', 'ui/screens/settings/SettingsHomeScreen.kt',
                     'ui/screens/settings/ProviderManagementRoute.kt', 'ui/screens/settings/LinuxRootfsPanel.kt'):
            self.assertIn('import app.daiyu.android.ui.navigation.PageBackHandler as BackHandler',
                          (APP / file).read_text(encoding='utf-8'))

    def test_dialog_and_menu_callbacks_keep_their_own_dispatch(self):
        for file in ('ui/screens/assistants/AvatarCropDialog.kt',
                     'ui/screens/chat/conversation/ChatMenuLayer.kt',
                     'vdapproval/VirtualDisplayApprovalScreen.kt'):
            self.assertIn('import androidx.activity.compose.BackHandler',
                          (APP / file).read_text(encoding='utf-8'))

    def test_prediction_runs_only_on_supported_resumed_pages(self):
        self.assertIn('predictiveBackEnabled && Build.VERSION.SDK_INT >= 34', self.host)
        self.assertIn('enabled && state.resumed && !predictive', self.host)
        self.assertIn('enabled && state.resumed && predictive', self.host)
        self.assertIn('BackHandler(enabled, onBack)', self.host)

    def test_predictive_completion_is_after_flow_collection_and_cancellation_resets(self):
        flow = self.host.index('events.collect { event ->')
        complete = self.host.index('if (state.alive && currentEnabled) currentBack()', flow)
        cleanup = self.host.index('finally {', complete)
        self.assertLess(flow, complete)
        self.assertIn('state.reset(owner)', self.host[cleanup:cleanup + 100])
        self.assertIn('if (!state.alive || !currentEnabled) throw CancellationException()', self.host)
        self.assertIn('BackEventCompat.EDGE_RIGHT', self.host)

    def test_route_preferences_and_lifecycle_revoke_the_old_gesture_not_page_state(self):
        self.assertIn('remember(routeKey, swipeDismissEnabled, predictiveBackEnabled, resumed)', self.host)
        self.assertIn('onDispose { state.alive = false; state.reset() }', self.host)
        self.assertIn('if (owner != null && this.owner !== owner) return', self.host)
        self.assertIn('val progress by key(state)', self.host)
        self.assertNotIn('key(predictiveBackEnabled)', self.host)

    def test_swipe_respects_ime_controls_pointer_count_and_axis_intent(self):
        for guard in ('canNavigateBack && swipeDismissEnabled && resumed && !imeVisible',
                      'fromEdge > edgeWidth || down.isConsumed || state.active',
                      'event.changes.size != 1', 'change.id != down.id || change.isConsumed',
                      'dy > viewConfiguration.touchSlop', 'dx <= dy * 1.5f',
                      'PointerEventPass.Main', 'LayoutDirection.Ltr'):
            self.assertIn(guard, self.host)
        self.assertNotIn('systemGestureExclusion', self.host)

    def test_each_swipe_samples_current_width_and_only_dispatches_on_committed_up(self):
        gesture = self.host.index('awaitEachGesture {')
        width = self.host.index('val gestureWidth = size.width', gesture)
        down = self.host.index('val down = awaitFirstDown', gesture)
        self.assertLess(down, width)
        self.assertIn('maxOf(72.dp.toPx(), gestureWidth * 0.30f)', self.host)
        self.assertIn('if (claimed && distance >= threshold && state.alive) currentBack()', self.host)
        self.assertNotIn('retainedPage =', self.host)

    def test_existing_persistent_keys_are_unchanged(self):
        store = (APP / 'data/model/AppearanceSettingsStore.kt').read_text(encoding='utf-8')
        for key in ('swipe_dismiss', 'predictive_back'):
            self.assertIn(f'getBoolean("{key}"', store)
            self.assertIn(f'putBoolean("{key}"', store)
        self.assertIn('.commit()', store)


if __name__ == '__main__':
    unittest.main()
