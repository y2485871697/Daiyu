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
            self.assertIn('direction = activeState.returnDirection', part)
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
        self.assertIn('remember(visualRouteKey, swipeDismissEnabled, predictiveBackEnabled, resumed, layers, visualBackTargetKey)', self.host)
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

    def test_horizontal_intent_is_not_cancelled_by_minor_vertical_jitter(self):
        self.assertIn('dy > viewConfiguration.touchSlop && dx <= dy * 1.5f', self.host)
        self.assertNotIn('if (dy > viewConfiguration.touchSlop || dx < -viewConfiguration.touchSlop)', self.host)
        regressions = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/navigation/PageBackGesturesActivityTest.kt').read_text()
        self.assertIn('fun testHorizontalDominantFirstMoveSurvivesVerticalJitterAcrossTouchSlop()', regressions)
        self.assertIn('start + Offset(slop * 8f, verticalDrift)', regressions)
        self.assertIn('for (predictionEnabled in listOf(false, true))', regressions)
        self.assertIn('for (verticalDirection in listOf(-1f, 1f))', regressions)

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
        self.assertIn('gestureWidth * 0.5f + viewConfiguration.touchSlop', self.host)
        self.assertIn('screensPerSecond >= 1f -> true', self.host)
        self.assertIn('screensPerSecond <= -1f -> false', self.host)
        self.assertIn('if (commit && state.alive) state.dispatchBack { currentBack() }', self.host)
        self.assertIn('state.dispatchBack { currentBack() }', self.host)
        handoff = self.host.split('fun dispatchBack(back:', 1)[1].split('private var owner:', 1)[0]
        self.assertIn('try { back() }', handoff)
        self.assertIn('finally { swipeCommit = null; dispatching = false }', handoff)
        self.assertNotIn('withFrameNanos', handoff)
        self.assertIn('if (claimed && change.changedToUp()) {\n                                    change.consume()', self.host)
        self.assertNotIn('retainedPage =', self.host)

    def test_content_key_only_controls_the_live_composition(self):
        self.assertIn('contentKey: Any = routeKey,', self.host)
        self.assertIn('key(contentKey) {', self.host)
        self.assertNotIn('key(routeKey) {', self.host)
        self.assertIn('layers.transition(visualRouteKey, visualBackTargetKey, animateEnter = animateBackEnabled && resumed, size = viewport)', self.host)
        self.assertIn('layers.frame(visualRouteKey, sourceKey = if (visualRouteKey == routeKey) contentKey else visualRouteKey)', self.host)
        self.assertIn('remember(visualRouteKey, swipeDismissEnabled, predictiveBackEnabled, resumed, layers, visualBackTargetKey)', self.host)
        self.assertIn('routeKey = if (retainedPage == "chat" && sidebarVisible) "chat-sidebar" else retainedPage,', self.main)
        self.assertIn('contentKey = when (retainedPage) {', self.main)
        self.assertIn('"assistant-edit" -> assistantEditReturnPage', self.main)
        self.assertIn('"context-management" -> "chat"', self.main)
        regressions = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/navigation/PageBackGesturesActivityTest.kt').read_text()
        for method in ('testExplicitContentKeyKeepsLiveCompositionUntilContentActuallyChanges',
                       'testDefaultContentKeyStillFollowsRouteKey'):
            self.assertIn('fun ' + method + '()', regressions)

    def test_chat_preview_records_drawer_content_not_its_overlay_before_open_settles(self):
        self.assertIn('captureFromContent = retainedPage == "chat" && !sidebarVisible', self.main)
        self.assertIn('captureFromContent: Boolean = false', self.host)
        self.assertIn('LocalPageBackPreviewFrame provides frame.takeIf { captureFromContent }', self.host)
        self.assertIn('if (captureFromContent) placeable.place(x = 0, y = 0)', self.host)
        source = self.host.split('internal fun Modifier.pageBackPreviewSource()', 1)[1].split('private val LocalPageBackGesture', 1)[0]
        self.assertIn('LocalPageBackPreviewFrame.current ?: return this', source)
        self.assertIn('placeable.placeWithLayer(x = 0, y = 0, layer = frame.layer)', source)
        drawer = (APP / 'ui/components/ConversationSidePaneScaffold.kt').read_text()
        self.assertIn('import app.daiyu.android.ui.navigation.pageBackPreviewSource', drawer)
        self.assertIn('.captureForTopBar(backdrop).pageBackPreviewSource()) { content() }', drawer)
        test = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/navigation/PageBackStackActivityTest.kt').read_text()
        self.assertIn('fun testDrawerOverlayPaintedBeforeRouteChangeNeverPollutesChatBackPreview()', test)
        self.assertLess(test.index('mainThread { drawerPainted = true }'), test.index('changeScreen("chat-sidebar")', test.index('fun testDrawerOverlayPaintedBeforeRouteChange')))

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
        surface = self.host.split('key(contentKey) {', 1)[1].split('testTag("page-back-departure-scrim")', 1)[0]
        self.assertIn('testTag("page-back-surface")', surface)
        self.assertIn('frame.size = IntSize(placeable.width, placeable.height)', surface)
        self.assertIn('placeable.placeWithLayer(x = 0, y = 0, layer = frame.layer)', surface)
        self.assertNotIn('frame.layer.record', surface)
        self.assertIn('if (held != null) drawImage(held)', surface)
        self.assertIn('drawContent()', surface)
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
                       '"usage" -> usageReturnPage',
                       '"assistant-edit" -> if (assistantEditReturnPage == "chat" && assistantEditReturnToSidebar)',
                       '"chat-sidebar" else assistantEditReturnPage',
                       '"generation-settings" -> "settings"',
                       '"legacy-library" -> "backup"'):
            self.assertIn(wiring, target)
        self.assertIn('themeKey, configuration, insetsKey, density, layoutDirection, resumed, previewScopeKey', self.host)
        self.assertIn('exit.animateTo(1f, spring(dampingRatio = 1f, stiffness = 146f, visibilityThreshold = 0.0025f))', self.host)
        self.assertIn('if (stack.routeKey(routeKey) == visualRouteKey) layers.abandon(request)', self.host)

    def test_zero_progress_returns_follow_the_departing_visits_exact_predecessor(self):
        layers = (APP / 'ui/navigation/PageBackPreviewLayers.kt').read_text()
        for wiring in ('frame.backTargetKey = visualBackTargetKey',
                       'frame.returnDirection = state.returnDirection',
                       'frame.animateReturn = animateBackEnabled && resumed && state.previewAllowed'):
            self.assertIn(wiring, self.host)
        for wiring in ('val previous = frames[currentKey]?.takeIf { currentKey != key }',
                       'it.animateReturn && it.backTargetKey == key && it.size != IntSize.Zero',
                       'Departure(it, key, 0f, predictive = false, direction = it.returnDirection)'):
            self.assertIn(wiring, layers)
        enter = layers.split('fun enter(key: Any): Departure?', 1)[1].split('fun freezeCurrent()', 1)[0]
        self.assertLess(enter.index('departure?.takeIf'), enter.index('previous?.takeIf'))
        for forbidden in ('back()', 'dispatchBack', 'launch', 'predictiveBackEnabled', 'swipeDismissEnabled'):
            self.assertNotIn(forbidden, enter)

    def test_button_returns_use_original_500ms_curve_and_keep_gesture_spring(self):
        self.assertIn('import top.yukonga.miuix.kmp.nav.runtime.NavProgrammaticEasing', self.host)
        self.assertIn('if (departure.progress == 0f) exit.animateTo(1f, tween(durationMillis = 500, easing = NavProgrammaticEasing))', self.host)
        self.assertIn('else exit.animateTo(1f, spring(dampingRatio = 1f, stiffness = 146f, visibilityThreshold = 0.0025f))', self.host)
        gradle = (ROOT / 'app/build.gradle.kts').read_text()
        self.assertIn('implementation("top.yukonga.miuix.kmp:miuix-nav-android:0.9.4-rc01")', gradle)

    def test_overlay_routes_and_failed_capture_do_not_animate_a_blank_parent(self):
        self.assertIn('animateBackEnabled = retainedPage != "assistant-edit" && retainedPage != "history-search"', self.main)
        self.assertIn('"conversation-search", "message-search" -> searchPage?.returnPage', self.main)
        self.assertIn('"history-search" -> null', self.main)
        self.assertIn('val showDeparture = departure != null && departing && departure.frame.image != null && !departure.forwardSkipped && state.previewAllowed', self.host)
        effect = self.host.split('LaunchedEffect(departure, departure?.forwardSkipped)', 1)[1].split('SideEffect { layers.trim', 1)[0]
        self.assertLess(effect.index('departure.frame.capture?.join()'), effect.index('if (departure.frame.image != null && !departure.forwardSkipped)'))
        self.assertIn('if (departure != null && showDeparture && !departure.forward)', self.host)
        layers = (APP / 'ui/navigation/PageBackPreviewLayers.kt').read_text()
        self.assertIn('it.target == key && it.frame === previous', layers)

    def test_button_return_motion_has_deferred_root_nested_system_rtl_and_suppression_regressions(self):
        test = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/navigation/PageBackStackActivityTest.kt').read_text()
        for method in ('testReturnButtonAnimatesWithBothGesturePreferencesDisabled',
                       'testOrdinarySystemBackAnimatesWithoutPredictiveProgress',
                       'testReturnButtonUsesRtlDirection',
                       'testNestedReturnButtonsAnimateOneLevelWithoutRemountingController',
                       'testPreviewSuppressionAlsoSuppressesOrdinaryReturnDeparture',
                       'testNonPageRouteDoesNotAnimateUnderlyingContent'):
            self.assertIn('fun ' + method + '(', test)
        self.assertIn('val parallax = initial.width * .25f * (1f - progress)', test)
        self.assertIn('progress > 0f && progress < 1f', test)
        self.assertIn('private fun awaitDepartureCapture()', test)
        self.assertIn('ui.onAllNodesWithTag("page-back-departure").fetchSemanticsNodes().isNotEmpty()', test)
        self.assertIn('ui.onNodeWithTag("page-return-button").performClick()', test)
        self.assertIn('assertEquals(0, disposed)', test)

    def test_local_visual_routes_keep_ancestors_and_cancel_never_adds_a_pop(self):
        stack = (APP / 'ui/navigation/PageBackStack.kt').read_text()
        for wiring in ('fun attached(', 'parentPath', 'freezeCurrent()',
                       'entry.child = routeKey != null', 'entry.path = path', 'entry.target = target'):
            self.assertIn(wiring, stack)
        self.assertIn('stack.ancestorKeys(routeKey, backTargetKey)', self.host)
        self.assertIn('predictiveGeneration', self.host)
        test = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/navigation/PageBackStackActivityTest.kt').read_text()
        for wiring in ('testBothPredictionEdgesRevealClosedChatAtOriginalQuarterHalfAndThreeQuarterProgress',
                       'testLocalTwoLevelStackFreezesRealPredecessorsWithoutRemountingController',
                       'testHorizontalIntermediateFramesUseOriginalDimmingAndCancelWithoutPop',
                       'progress in (fraction - .1f)..(fraction - .02f + .005f)',
                       'assertEquals(1, localPage)', 'assertEquals(1, backCount)'):
            self.assertIn(wiring, test)

    def test_provider_directory_primary_visit_keeps_its_own_return_chain(self):
        route = (APP / 'ui/screens/settings/ProviderManagementRoute.kt').read_text()
        self.assertIn('if (success && initialSlot == ProviderRegistry.PRIMARY && d.editor.slotId == ProviderRegistry.PRIMARY)', route)
        self.assertIn('if (initialSlot == ProviderRegistry.PRIMARY && d.editor.slotId == ProviderRegistry.PRIMARY)', route)
        self.assertIn('BackHandler(previewEnabled = !loading || initialSlot == ProviderRegistry.PRIMARY)', route)

    def test_provider_detail_reserves_visual_route_before_async_loading(self):
        route = (APP / 'ui/screens/settings/ProviderManagementRoute.kt').read_text()
        opening = route[route.index('    fun open(slot:'):route.index('    fun mutate(')]
        self.assertLess(opening.index('openingSlot = slot'), opening.index('scope.launch'))
        self.assertIn('(detail != null || openingSlot != null) && initialSlot != ProviderRegistry.PRIMARY', route)
        self.assertIn('"provider:${detail?.editor?.slotId ?: openingSlot}"', route)
        self.assertIn('openingSlot = null', route[route.index('fun closeDetail()'):route.index('    fun refresh()')])
        self.assertIn('onFailure = { openingSlot = null;', opening)
        visual = route[route.index('    PageBackStack(when {'):route.index('        BackHandler(previewEnabled')]
        self.assertLess(visual.index('creating != null'), visual.index('(detail != null || openingSlot != null)'))
        self.assertLess(visual.index('(detail != null || openingSlot != null)'), visual.index('authentication ->'))
        test = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/navigation/PageBackStackActivityTest.kt').read_text()
        self.assertIn('testLoadingAndReadyDetailShareOneRouteAndPreserveDirectoryPredecessor', test)
        self.assertIn('localLoading = true; localPage = 1', test)

    def test_nested_preview_owners_unregister_and_commit_is_guarded(self):
        for guard in ('state.previewOwners.add(previewOwner)', 'state.previewOwners.remove(previewOwner)',
                      'previewOwners.none { it.enabled && !it.previewEnabled }',
                      'if (!alive || (transitionBlocked && swipeCommit == null) || committing) return', 'committing = true',
                      'activeDeparture || (state.previewAllowed && !state.active && progress > 0f)'):
            self.assertIn(guard, self.host)
        for file in ('VoiceSettingsPanel.kt', 'VoiceAccountPanel.kt', 'ui/SubAgentSettingsScreen.kt',
                     'ui/screens/settings/SettingsHomeScreen.kt',
                     'ui/screens/settings/McpSettingsScreen.kt', 'ui/screens/settings/ProviderManagementRoute.kt',
                     'ui/screens/settings/ProviderCatalogSettingsScreen.kt'):
            self.assertIn('PageBackStack(', (APP / file).read_text())
        self.assertIn('previewEnabled = !choosingSection', (APP / 'VoiceSettingsPanel.kt').read_text())
        self.assertIn('previewEnabled = !editing', (APP / 'ui/screens/settings/McpSettingsScreen.kt').read_text())
        self.assertIn('previewEnabled = false', (APP / 'ui/screens/settings/LinuxRootfsPanel.kt').read_text())

    def test_local_stack_freezes_before_replacing_content_and_validates_ancestors(self):
        stack = (APP / 'ui/navigation/PageBackStack.kt').read_text()
        self.assertLess(stack.index('parent.registry.freezeCurrent()'), stack.index('    CompositionLocalProvider'))
        for token in ('attached(it) && it.path == entry.parentPath', 'entries.remove(entry)',
                      'entry.child = routeKey != null', 'PageBackRouteKey(root, path)', 'ancestorKeys'):
            self.assertIn(token, stack)
        self.assertNotIn('key(routeKey)', stack)
        self.assertIn('stack.freezeCurrent = { layers.freezeCurrent() }', self.host)

    def test_original_miuix_motion_is_full_width_with_parallax_and_independent_dimming(self):
        for token in ('kotlin.math.round(state.direction * size.width * visibleProgress)',
                      'size.width * 0.25f * (1f - progress)', 'alpha = 1f - 0.1f * (1f - progress)',
                      'drawRect(Color.Black, alpha = 0.5f * (1f - progress))', 'absoluteSquircleClip',
                      'POSITION_BOTTOM_LEFT', 'page-back-scrim', 'page-back-departure-scrim',
                      'state.predictiveGeneration != predictiveGeneration'):
            self.assertIn(token, self.host)
        for obsolete in ('0.12f', '0.06f', 'scaleX =', 'scaleY =', 'tween(180)'):
            self.assertNotIn(obsolete, self.host)

    def test_existing_persistent_keys_are_unchanged(self):
        store = (APP / 'data/model/AppearanceSettingsStore.kt').read_text(encoding='utf-8')
        for key in ('swipe_dismiss', 'predictive_back'):
            self.assertIn(f'getBoolean("{key}"', store)
            self.assertIn(f'putBoolean("{key}"', store)
        self.assertIn('.commit()', store)


if __name__ == '__main__':
    unittest.main()
