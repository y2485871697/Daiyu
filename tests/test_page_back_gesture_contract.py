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
        self.assertIn('callback.isEnabled = enabled && state.resumed', self.host)
        self.assertIn('if (!activeState.predictiveEnabled) return', self.host)
        self.assertIn('BackHandler(enabled, onBack)', self.host)
        self.assertIn('val currentEnabled by rememberUpdatedState(enabled && state.resumed)', self.host)

    def test_predictive_completion_is_synchronous_and_cancellation_resets(self):
        handler = self.host.split('internal fun PageBackHandler(', 1)[1].split('internal fun PageBackGestureHost(', 1)[0]
        started = handler.split('override fun handleOnBackStarted(', 1)[1].split('override fun handleOnBackProgressed(', 1)[0]
        progressed = handler.split('override fun handleOnBackProgressed(', 1)[1].split('override fun handleOnBackCancelled(', 1)[0]
        cancelled = handler.split('override fun handleOnBackCancelled()', 1)[1].split('override fun handleOnBackPressed()', 1)[0]
        pressed = handler.split('override fun handleOnBackPressed()', 1)[1].split('    SideEffect {', 1)[0]
        for part in (started, progressed, cancelled, pressed):
            self.assertNotIn('launch', part)
            self.assertNotIn('events.collect', part)
        for part in (started, progressed):
            self.assertIn('activeState.alive', part)
            self.assertIn('!currentEnabled', part)
            self.assertIn('activeState.transitionBlocked', part)
            self.assertIn('activeState.committing', part)
            self.assertIn('session.rejected = true', part)
            self.assertIn('BackEventCompat.EDGE_RIGHT', part)
            self.assertNotIn('commitBack', part)
        self.assertIn('activeState !== currentState', progressed)
        self.assertIn('activeState.reset(owner)', progressed)
        self.assertIn('if (currentPreview) backEvent.progress else 0f', progressed)
        self.assertIn('session.clear()', cancelled)
        self.assertIn('gesture = null', self.host)
        self.assertNotIn('commitBack', cancelled)
        self.assertIn('if (!session.rejected && activeState.alive && currentEnabled)', pressed)
        self.assertEqual(1, pressed.count('activeState.commitBack { currentBack() }'))
        self.assertIn('finally {\n                    handleOnBackCancelled()', pressed)
        self.assertIn('val callback = remember(dispatcher)', handler)
        self.assertIn('val currentState by rememberUpdatedState(state)', handler)
        self.assertIn('val currentBack by rememberUpdatedState(onBack)', handler)
        registration = handler.split('DisposableEffect(dispatcher, lifecycleOwner, callback)', 1)[1]
        self.assertIn('dispatcher.addCallback(lifecycleOwner, callback)', registration)
        self.assertIn('onDispose { callback.handleOnBackCancelled(); callback.remove() }', registration)
        self.assertIn('DisposableEffect(state, callback)', handler)
        self.assertIn('onDispose { session.invalidate() }', handler)
        self.assertIn('if (!callback.isEnabled) session.invalidate()', handler)
        self.assertIn('val rejectCompletion = started || rejected', self.host)
        self.assertIn('rejected = rejectCompletion', self.host)
        self.assertNotIn('PredictiveBackHandler(', handler)

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
        self.assertIn('state.dispatchBack { currentBack() }', self.host)
        handoff = self.host.split('fun dispatchBack(back:', 1)[1].split('private var owner:', 1)[0]
        self.assertIn('try { back() }', handoff)
        self.assertIn('finally { swipeCommit = null; dispatching = false }', handoff)
        self.assertNotIn('withFrameNanos', handoff)
        self.assertIn('if (claimed && change.changedToUp()) {\n                                    change.consume()', self.host)
        self.assertNotIn('retainedPage =', self.host)

    def test_previous_page_is_readonly_recorded_content_not_a_second_composition(self):
        layers = (APP / 'ui/navigation/PageBackPreviewLayers.kt').read_text()
        self.assertIn('placeable.placeWithLayer(x = 0, y = 0, layer = frame.layer)', self.host)
        self.assertIn('target.image?.let { drawImage(it) }', self.host)
        self.assertIn('departure.frame.image?.let { drawImage(it) }', self.host)
        self.assertIn('if (target != null && state.previewAllowed) progress else 0f', self.host)
        self.assertIn('it.size == size', layers)
        self.assertIn('it.image != null', layers)
        self.assertIn('frame.layer.toImageBitmap()', layers)
        self.assertIn('scope.launch(start = CoroutineStart.UNDISPATCHED)', layers)
        self.assertLess(layers.index('frames[currentKey]?.let { freeze(it) }'), layers.index('currentKey = key'))
        self.assertIn('capture.invokeOnCompletion { context.releaseGraphicsLayer(frame.layer) }', layers)
        self.assertIn('frames.remove(key)?.let { release(it) }', layers)
        self.assertNotIn('drawLayer(target.layer)', self.host)
        self.assertNotIn('drawLayer(departure.frame.layer)', self.host)
        self.assertIn('frames.size > 8', layers)
        self.assertIn('context.releaseGraphicsLayer(frame.layer)', layers)
        self.assertIn('onDispose { layers.close() }', self.host)
        self.assertEqual(1, self.host.count('{ content() }'))
        surface = self.host.split('key(routeKey) {', 1)[1].split('if (departure != null && departing)', 1)[0]
        self.assertIn('testTag("page-back-surface")', surface)
        self.assertIn('frame.size = IntSize(placeable.width, placeable.height)', surface)
        self.assertIn('placeable.placeWithLayer(x = 0, y = 0, layer = frame.layer)', surface)
        self.assertNotIn('frame.layer.record', surface)
        self.assertNotIn('drawWithContent', surface)
        self.assertEqual(1, surface.count('{ content() }'))
        self.assertNotIn('target.image?.let { drawImage(it) }', surface)
        for tag in ('page-back-target', 'page-back-departure'):
            self.assertIn('testTag("' + tag + '")', self.host)

    def test_preview_matches_root_return_target_and_invalidates_sensitive_context(self):
        for wiring in ('backTargetKey = pageBackPreviewTarget()', 'previewScopeKey = selected?.id'):
            self.assertIn(wiring, self.main)
        target = self.main.split('private fun pageBackPreviewTarget(): String? = when (retainedPage) {', 1)[1]
        target = target.split('\n    }', 1)[0]
        for wiring in ('"terminal" -> terminalReturnPage', '"providers" -> providersReturnPage',
                       '"usage" -> usageReturnPage', '"assistant-edit" -> assistantEditReturnPage',
                       '"generation-settings" -> if (generationSettingsVisit != null) "settings" else "chat"',
                       '"legacy-library" -> if (legacyPage != null) "backup" else "chat"'):
            self.assertIn(wiring, target)
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
