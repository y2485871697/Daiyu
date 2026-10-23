"""Source-wiring guards; device behavior is covered by the deferred Android tests."""
from pathlib import Path
import xml.etree.ElementTree as ET
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
        complete = self.host.index('if (state.alive && currentEnabled) state.commitBack(scope) { currentBack() }', flow)
        cleanup = self.host.index('finally {', complete)
        self.assertLess(flow, complete)
        self.assertIn('state.reset(owner)', self.host[cleanup:cleanup + 100])
        self.assertIn('if (!state.alive || !currentEnabled || state.transitionBlocked || state.committing) throw CancellationException()', self.host)
        self.assertIn('BackEventCompat.EDGE_RIGHT', self.host)

    def test_route_preferences_and_lifecycle_revoke_the_old_gesture_not_page_state(self):
        self.assertIn('remember(routeKey, swipeDismissEnabled, predictiveBackEnabled, resumed, layers, backTargetKey)', self.host)
        self.assertIn('onDispose { state.alive = false; state.reset() }', self.host)
        self.assertIn('if (owner != null && this.owner !== owner) return', self.host)
        self.assertIn('val progress by key(state)', self.host)
        self.assertNotIn('key(predictiveBackEnabled)', self.host)

    def test_swipe_is_full_page_and_lets_children_arbitrate_before_claiming(self):
        for guard in ('canNavigateBack && swipeDismissEnabled && resumed && !imeVisible',
                      'if (state.active || state.transitionBlocked || state.committing || state.dispatching) return@awaitEachGesture', 'event.changes.size != 1',
                      'if (change.id != down.id) break', 'if (consumedMovement) break',
                      'change.position == change.previousPosition',
                      'viewConfiguration.longPressTimeoutMillis',
                      'dy > viewConfiguration.touchSlop', 'dx <= dy * 1.5f',
                      'if (claimed) PointerEventPass.Initial else PointerEventPass.Final', 'LayoutDirection.Ltr'):
            self.assertIn(guard, self.host)
        for obsolete in ('edgeWidth', 'fromEdge', 'down.isConsumed',
                         'change.id != down.id || change.isConsumed'):
            self.assertNotIn(obsolete, self.host)
        self.assertNotIn('systemGestureExclusion', self.host)

    def test_predictive_platform_opt_in_is_explicit_and_scoped_to_main_activity(self):
        manifest = ET.parse(ROOT / 'app/src/main/AndroidManifest.xml').getroot()
        android = '{http://schemas.android.com/apk/res/android}'
        application = manifest.find('application')
        main = next(e for e in application.findall('activity') if e.get(android + 'name') == '.MainActivity')
        self.assertEqual('true', main.get(android + 'enableOnBackInvokedCallback'))
        self.assertIsNone(application.get(android + 'enableOnBackInvokedCallback'))
        self.assertNotIn('HiddenApiBypass', self.host)

    def test_android_regressions_cover_real_clickable_rows_and_competing_controls(self):
        test = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/navigation/PageBackGesturesActivityTest.kt').read_text()
        for method in ('testMiddleOfPageSwipeNavigatesWithPredictionDisabled',
                       'testClickableSettingRowYieldsItsPressToPageSwipeWithoutFiringClick',
                       'testVerticalSettingListScrollStaysWithTheList',
                       'testSliderMovementStaysWithTheControl',
                       'testLongPressTextSelectionDoesNotBecomePageBack',
                       'testDisablingSwipeCancelsAnInFlightDrag',
                       'testPointerCancellationBeyondThresholdDoesNotNavigate',
                       'testHorizontalChildHasFirstRefusalOverPageBack',
                       'testPredictiveProgressCancellationCommitAndLowApiFallback',
                       'testPreviousPagePreviewCancelAndCommittedDeparture',
                       'testLocalPreviewSuppressionUnregistersWhenNestedHandlerLeaves',
                       'testHorizontalSwipeCommitsTheRecordedDepartureWithPredictionDisabled',
                       'testHorizontalSwipeWithPredictionEnabledKeepsItsCommitUntilDispatch'):
            self.assertIn('fun ' + method + '(', test)
        for control in ('LazyColumn(', '.clickable { rowClicks++ }', 'material.Slider(', 'BasicTextField('):
            self.assertIn(control, test)

    def test_each_swipe_samples_current_width_and_only_dispatches_on_committed_up(self):
        gesture = self.host.index('awaitEachGesture {')
        width = self.host.index('val gestureWidth = size.width', gesture)
        down = self.host.index('val down = awaitFirstDown', gesture)
        self.assertLess(down, width)
        self.assertIn('maxOf(72.dp.toPx(), gestureWidth * 0.30f)', self.host)
        self.assertIn('if (dx >= threshold && state.alive) {', self.host)
        self.assertIn('state.dispatchBack(scope) { currentBack() }', self.host)
        self.assertIn('if (claimed && change.changedToUp()) {\n                                    change.consume()', self.host)
        self.assertNotIn('retainedPage =', self.host)

    def test_previous_page_is_readonly_recorded_content_not_a_second_composition(self):
        layers = (APP / 'ui/navigation/PageBackPreviewLayers.kt').read_text()
        self.assertIn('frame.layer.record { this@drawWithContent.drawContent() }', self.host)
        self.assertIn('drawLayer(target.layer)', self.host)
        self.assertIn('drawLayer(departure.frame.layer)', self.host)
        self.assertIn('if (target != null && state.previewAllowed) progress else 0f', self.host)
        self.assertIn('it.size == size', layers)
        self.assertIn('frames.size > 8', layers)
        self.assertIn('context.releaseGraphicsLayer(frame.layer)', layers)
        self.assertIn('onDispose { layers.close() }', self.host)
        self.assertEqual(1, self.host.count('{ content() }'))
        for tag in ('page-back-target', 'page-back-departure'):
            self.assertIn('testTag("' + tag + '")', self.host)

    def test_preview_matches_root_return_target_and_invalidates_sensitive_context(self):
        for wiring in ('backTargetKey = pageBackPreviewTarget()', 'previewScopeKey = selected?.id',
                       '"terminal" -> terminalReturnPage', '"providers" -> providersReturnPage',
                       '"usage" -> usageReturnPage', '"assistant-edit" -> assistantEditReturnPage'):
            self.assertIn(wiring, self.main)
        self.assertIn('themeKey, configuration, insetsKey, density, layoutDirection, resumed, previewScopeKey', self.host)
        self.assertIn('exit.animateTo(1f, tween(180))', self.host)
        self.assertIn('if (layers.currentKey == routeKey) layers.abandon(request)', self.host)

    def test_nested_preview_owners_unregister_and_commit_is_guarded(self):
        for guard in ('state.previewOwners.add(previewOwner)', 'state.previewOwners.remove(previewOwner)',
                      'previewOwners.none { it.enabled && !it.previewEnabled }',
                      'if (!alive || (transitionBlocked && swipeCommit == null) || committing) return', 'committing = true',
                      'departing || (state.previewAllowed && !state.active && progress > 0f)'):
            self.assertIn(guard, self.host)
        for file, guard in (
            ('VoiceSettingsPanel.kt', 'previewEnabled = child == null && !choosingSection'),
            ('ProductionVoiceAccountPage.kt', 'previewEnabled = false'),
            ('VoiceAccountPanel.kt', 'previewEnabled = false'),
            ('ui/SubAgentSettingsScreen.kt', 'previewEnabled = false'),
            ('ui/screens/settings/SettingsHomeScreen.kt', 'previewEnabled = false'),
            ('ui/screens/settings/PetSettingsScreen.kt', 'previewEnabled = false'),
            ('ui/screens/settings/LinuxRootfsPanel.kt', 'previewEnabled = false'),
            ('ui/screens/settings/McpSettingsScreen.kt', 'previewEnabled = !editing && selectedId == null'),
            ('ui/screens/settings/ProviderCatalogSettingsScreen.kt', 'backPreviewEnabled = false')):
            self.assertIn(guard, (APP / file).read_text())

    def test_existing_persistent_keys_are_unchanged(self):
        store = (APP / 'data/model/AppearanceSettingsStore.kt').read_text(encoding='utf-8')
        for key in ('swipe_dismiss', 'predictive_back'):
            self.assertIn(f'getBoolean("{key}"', store)
            self.assertIn(f'putBoolean("{key}"', store)
        self.assertIn('.commit()', store)


if __name__ == '__main__':
    unittest.main()
