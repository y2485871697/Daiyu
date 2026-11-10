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

    def test_chat_return_refresh_only_invalidates_changed_configuration(self):
        reload = self.main.split('    private fun reloadConfirmedModelLabel(', 1)[1].split('    private fun refreshAutomaticCompression()', 1)[0]
        self.assertIn('retainBalanceForProvider ?: confirmedGptSpeed?.editor?.slotId', reload)
        self.assertIn('controller.providerBalances.cached(balanceProvider) !== balanceRuntime', reload)
        self.assertIn('modelLabelRead === request && selected?.id == conversation && editorGeneration == generation', reload)
        self.assertIn('if (it is kotlinx.coroutines.CancellationException) throw it', reload)
        self.assertIn('!previous.hasSameConfiguration(next)', reload)
        self.assertIn('if (previous != next) confirmedGptSpeed = next', reload)
        self.assertIn('if (configurationChanged) contextModelRevision++', reload)
        self.assertIn('if (presentationChanged)', reload)
        self.assertLess(reload.index('balanceRuntime = saved?.third'), reload.index('publishChat(rowsChanged = false)'))

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
        self.assertNotIn('animateFloatAsState', self.host)
        self.assertIn('var visualProgress by mutableFloatStateOf(0f)', self.host)
        self.assertIn('var gestureVisualActive by mutableStateOf(false)', self.host)
        self.assertIn('val value = state.visualProgress', self.host)
        self.assertIn('settle.animateTo(0f, spring', self.host)
        self.assertNotIn('key(predictiveBackEnabled)', self.host)

    def test_drag_motion_stays_in_layer_properties_and_cancel_keeps_a_settle_spring(self):
        self.assertNotIn('animateFloatAsState', self.host)
        self.assertIn('visualProgress = nextProgress', self.host)
        self.assertIn('val value = state.visualProgress', self.host)
        self.assertIn('drawRect(Color.Black, alpha = 0.5f * (1f - state.visualProgress))', self.host)
        self.assertIn('state.gestureVisualActive', self.host)
        self.assertIn('settle.animateTo(0f, spring', self.host)
        self.assertIn('if (settleJob === kotlinx.coroutines.currentCoroutineContext()[Job])', self.host)

    def test_swipe_is_full_page_and_lets_children_arbitrate_before_claiming(self):
        for guard in ('canNavigateBack && swipeDismissEnabled && resumed && !imeVisible',
                      'if (state.active || entering || state.committing || state.dispatching) return@awaitEachGesture', 'event.changes.size != 1',
                      'if (change.id != down.id) break', 'if (consumedMovement) break',
                      'change.position == change.previousPosition',
                      'viewConfiguration.longPressTimeoutMillis',
                      'dy > viewConfiguration.touchSlop', 'dx <= dy * 1.5f',
                      'if (claimed || interruptingReturn) PointerEventPass.Initial else PointerEventPass.Final', 'LayoutDirection.Ltr'):
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
        self.assertIn('VelocityTracker().also { it.addPointerInputChange(down) }', self.host)
        self.assertIn('velocity.addPointerInputChange(change)', self.host)
        self.assertNotIn('velocity.addPosition(change.uptimeMillis, change.position)', self.host)
        self.assertIn('if (commit && state.alive) state.dispatchBack(screensPerSecond) { currentBack() }', self.host)
        self.assertIn('state.dispatchBack(screensPerSecond) { currentBack() }', self.host)
        handoff = self.host.split('fun dispatchBack(velocity: Float = 0f, back:', 1)[1].split('private var owner:', 1)[0]
        self.assertIn('try { synchronousTrace("daiyu.back.custom-up-dispatch") { back() } }', handoff)
        self.assertIn('finally { swipeCommit = null; dispatching = false }', handoff)
        self.assertNotIn('withFrameNanos', handoff)
        self.assertIn('if (claimed && change.changedToUp()) {\n                                    change.consume()', self.host)
        self.assertNotIn('retainedPage =', self.host)

    def test_content_key_only_controls_the_live_composition(self):
        self.assertIn('contentKey: Any = routeKey,', self.host)
        self.assertIn('key(contentKey) {', self.host)
        self.assertNotIn('key(routeKey) {', self.host)
        self.assertIn('layers.transition(visualRouteKey, visualBackTargetKey, animateEnter = animateBackEnabled && resumed, size = viewport, contentKey = contentKey)', self.host)
        self.assertIn('layers.frame(visualRouteKey, sourceKey = if (visualRouteKey == routeKey) contentKey else visualRouteKey, contentKey = contentKey)', self.host)
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
        self.assertIn('if (captureFromContent) drawContent()', self.host)
        source = self.host.split('internal fun Modifier.pageBackPreviewSource()', 1)[1].split('internal val LocalPageBackGesture', 1)[0]
        self.assertIn('LocalPageBackPreviewFrame.current ?: return this', source)
        self.assertIn('frame.layer.record { this@drawWithContent.drawContent() }', source)
        self.assertIn('drawLayer(frame.layer)', source)
        drawer = (APP / 'ui/components/ConversationSidePaneScaffold.kt').read_text()
        self.assertIn('import app.daiyu.android.ui.navigation.pageBackPreviewSource', drawer)
        self.assertIn('.captureForTopBar(backdrop).pageBackPreviewSource()) { content() }', drawer)
        test = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/navigation/PageBackStackActivityTest.kt').read_text()
        self.assertIn('fun testDrawerOverlayPaintedBeforeRouteChangeNeverPollutesChatBackPreview()', test)
        self.assertLess(test.index('mainThread { drawerPainted = true }'), test.index('changeScreen("chat-sidebar")', test.index('fun testDrawerOverlayPaintedBeforeRouteChange')))

    def test_previous_page_is_readonly_recorded_content_not_a_second_composition(self):
        layers = (APP / 'ui/navigation/PageBackPreviewLayers.kt').read_text()
        self.assertNotIn('placeWithLayer', self.host)
        self.assertIn('frame.layer.record { this@drawWithContent.drawContent() }', self.host)
        self.assertIn('drawPreview(target)', self.host)
        self.assertIn('drawPreview(departure.frame)', self.host)
        self.assertIn('if (target != null && state.previewAllowed) state.visualProgress else 0f', self.host)
        self.assertIn('it.size == size', layers)
        self.assertIn('it.canDrawPreview', layers)
        self.assertIn('frame.layer.toImageBitmap()', layers)
        self.assertIn('scope.launch(start = CoroutineStart.UNDISPATCHED)', layers)
        enter = layers.split('    fun enter(key: Any, contentKey: Any = key): Departure? {', 1)[1].split('    fun transition(', 1)[0]
        self.assertIn('if (currentKey != key) frames[currentKey]?.let {', enter)
        self.assertIn('JankDiagnostics.section("daiyu.back-preview=route-change") { freeze(it) }', enter)
        self.assertLess(enter.index('freeze(it)'), enter.index('currentKey = key'))
        self.assertIn('if (frame.releaseRequested && frame.captures.isEmpty()) context.releaseGraphicsLayer(frame.layer)', layers)
        self.assertIn('val previous = frames.remove(key)', layers)
        self.assertIn('previous?.let { release(it) }', layers)
        self.assertNotIn('drawLayer(target.layer)', self.host)
        self.assertNotIn('drawLayer(departure.frame.layer)', self.host)
        self.assertIn('frames.size > 8', layers)
        self.assertIn('context.releaseGraphicsLayer(frame.layer)', layers)
        self.assertIn('onDispose { layers.close() }', self.host)
        self.assertEqual(1, self.host.count('{ content() }'))
        surface = self.host.split('key(if (retainedRootContent == null) contentKey else RetainedRootSurface) {', 1)[1].split('testTag("page-back-departure-scrim")', 1)[0]
        self.assertIn('testTag("page-back-surface")', surface)
        self.assertIn('frame.recorded(IntSize(size.width.toInt(), size.height.toInt()))', surface)
        self.assertIn('frame.layer.record { this@drawWithContent.drawContent() }', surface)
        self.assertIn('drawLayer(frame.layer)', surface)
        self.assertIn('key(previewScopeKey, retainedRootScopeKey)', surface)
        self.assertNotIn('key(layers, previewScopeKey, retainedRootScopeKey)', surface)
        self.assertIn('if (retainedRootContent != null && retainedRootVisible) null else departure.targetImage', surface)
        self.assertIn('if (held != null) drawImage(held)', surface)
        self.assertIn('drawContent()', surface)
        self.assertEqual(1, surface.count('{ content() }'))
        self.assertNotIn('drawPreview(target)', surface)
        for tag in ('page-back-target', 'page-back-departure'):
            self.assertIn('testTag("' + tag + '")', self.host)

    def test_prepared_pixels_and_single_retained_root_have_explicit_guards(self):
        layers = (APP / 'ui/navigation/PageBackPreviewLayers.kt').read_text()
        retained = (APP / 'ui/navigation/PageRetainedContent.kt').read_text()
        self.assertNotIn('retainedDisplayList', layers + self.host)
        preview = self.host.split('private fun DrawScope.drawPreview(', 1)[1]
        self.assertNotIn('drawLayer(', preview)
        self.assertIn('if (image != null) drawImage(image)', preview)
        for guard in ('frame.readyGeneration = generation', 'frame.size == size',
                      'previewEpoch == epoch', 'frames[frame.key] === frame',
                      '!frame.releaseRequested', 'frame.hasFreshImage',
                      'withContext(NonCancellable) { snapshot(frame) }',
                      'frame.captures.toList().forEach { it.cancel() }'):
            self.assertIn(guard, layers)
        self.assertEqual(2, self.host.count('frame.recorded(IntSize(size.width.toInt(), size.height.toInt()))'))
        self.assertIn('layers.setActive(resumed)', self.host)
        self.assertIn('var ready: ImageBitmap? = null', layers)
        self.assertIn('!frame.frozen && previewEpoch == epoch', layers)
        self.assertNotIn('if (frame.frozen) frame.image = image', layers)
        freeze = layers.split('private fun freeze(frame: Frame)', 1)[1].split('private fun capture(', 1)[0]
        self.assertNotIn('capture(frame)', freeze)
        self.assertIn('frame.image = frame.ready?.takeIf { frame.readySize == frame.size }', freeze)
        self.assertIn('frame.captures.any { !it.isCompleted }', layers)
        self.assertIn('previewEpoch != epoch || frame.generation != generation', layers)
        self.assertIn('if (frame.ready == null) withFrameNanos { } else delay(64)', layers)
        self.assertIn('retainedRootKey = "chat"', self.main)
        self.assertEqual(1, self.main.count('chatPageState.SaveableStateProvider('))
        self.assertIn('backHandlerEnabled = routeActive', self.main)
        self.assertIn('Lifecycle.State.CREATED', retained)
        self.assertIn('if (visible) placeable.place(0, 0)', retained)
        self.assertIn('if (!visible && (!visited || !resumed)) return', retained)
        self.assertIn('Modifier.clearAndSetSemantics {}', retained)
        self.assertNotIn('delay(', retained)

    def test_dynamic_navigation_locals_track_only_their_readers(self):
        retained = (APP / 'ui/navigation/PageRetainedContent.kt').read_text()
        stack = (APP / 'ui/navigation/PageBackStack.kt').read_text()
        for name, source in (('LocalPageContentActive', retained),
                             ('LocalPageBackGesture', self.host),
                             ('LocalPageBackStackPath', stack),
                             ('LocalPageBackPreviewFrame', self.host)):
            declaration = next(line for line in source.splitlines() if f'val {name} =' in line)
            self.assertIn('compositionLocalOf', declaration)
            self.assertNotIn('staticCompositionLocalOf', declaration)
        self.assertIn('compositionLocalOf { true }', retained)
        self.assertIn('compositionLocalOf<PageBackGestureState?> { null }', self.host)
        self.assertIn('compositionLocalOf<PageBackStackPath?> { null }', stack)
        self.assertIn('compositionLocalOf<PageBackPreviewLayers.Frame?> { null }', self.host)
        test = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/navigation/PageRetainedContentActivityTest.kt').read_text()
        self.assertIn('fun testDynamicNavigationLocalsOnlyRecomposeTheirSeparateConsumerScopes()', test)
        for probe in ('ActiveLocalProbe', 'GestureLocalProbe', 'StackLocalProbe',
                      'PreviewLocalProbe', 'UnreadLocalProbe'):
            self.assertIn(f'private fun {probe}()', test)
        self.assertIn('before[scope], localCompositions[scope]', test)
        self.assertIn('localCompositions[scope] > before[scope]', test)

    def test_release_sync_traces_have_fixed_names_and_do_not_wrap_suspension(self):
        helper = (APP / 'diagnostics/SynchronousTrace.kt').read_text()
        self.assertIn('Trace.beginSection(name)', helper)
        self.assertIn('return try { block() } finally {', helper)
        self.assertIn('if (tracing) try { Trace.endSection() } catch (_: Throwable)', helper)
        self.assertIn('BackPerformance.recorder.complete(sample)', helper)
        self.assertNotIn('BuildConfig', helper)
        self.assertNotIn('JANK_DIAGNOSTICS', helper)
        self.assertNotIn('Log.', helper)
        layers = (APP / 'ui/navigation/PageBackPreviewLayers.kt').read_text()
        retained = (APP / 'ui/navigation/PageRetainedContent.kt').read_text()
        chat = (APP / 'ui/screens/chat/conversation/ChatMessageList.kt').read_text()
        for name in ('custom-up-dispatch', 'callback-commit', 'business-handler', 'reset'):
            self.assertIn(f'synchronousTrace("daiyu.back.{name}")', self.host)
        prepare = layers.split('    fun prepare(', 1)[1].split('    fun abandon(', 1)[0]
        self.assertIn('synchronousTrace("daiyu.back.prepare")', prepare)
        self.assertIn('freeze(frame)', prepare)
        self.assertNotIn('launch', prepare)
        self.assertNotIn('withFrameNanos', prepare)
        freeze = layers.split('private fun freeze(frame: Frame)', 1)[1].split('private fun capture(', 1)[0]
        self.assertIn('if (frame.frozen) return', freeze)
        for name in ('lifecycle-set', 'lifecycle-close'):
            self.assertIn(f'synchronousTrace("daiyu.retained.{name}")', retained)
        self.assertIn('synchronousTrace("daiyu.chat.retained-compose-entry") {}', self.host)
        self.assertNotIn('try { retainedRootContent(active)', self.host)
        for name in ('position-seed', 'restore-lifecycle', 'restore-clearance'):
            self.assertIn(f'synchronousTrace("daiyu.chat.{name}")', chat)
        clearance = chat.split('    fun correctFollowClearance()', 1)[1].split('    val requestedIndex', 1)[0]
        self.assertNotIn('withFrameNanos', clearance)
        self.assertNotIn('scrollToItem', clearance)
        self.assertIn('remeasurement.forceRemeasure()', clearance)

    def test_continuous_redraw_uses_throttled_same_size_last_good_pixels(self):
        layers = (APP / 'ui/navigation/PageBackPreviewLayers.kt').read_text()
        schedule = layers.split('private fun schedule(frame: Frame)', 1)[1].split('fun beginGesture(', 1)[0]
        self.assertIn('frame.warmup?.isActive == true || frame.captures.any { !it.isCompleted }', schedule)
        self.assertNotIn('frame.warmup?.cancel()', schedule)
        self.assertLess(schedule.index('frame.warmup = null'), schedule.index('capture(frame)'))
        capture = layers.split('private fun capture(frame: Frame)', 1)[1].split('fun frame(', 1)[0]
        self.assertNotIn('frame.generation == generation', capture)
        self.assertIn('image.width == size.width && image.height == size.height', capture)
        test = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/navigation/PageBackStackActivityTest.kt').read_text()
        self.assertIn('fun testContinuousRedrawStillPreparesAndRefreshesPixels()', test)
        retained = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/navigation/PageRetainedContentActivityTest.kt').read_text()
        for method in ('testRealChatOrdinaryReturnDrawsLiveContentWithoutRemount',
                       'testRealChatSwipeReturnDrawsLiveContentWithoutRemount'):
            self.assertIn('fun ' + method + '()', retained)
        for content in ('ChatConversationLayers(', 'ChatMessageList(', 'ChatComposer(',
                        'liveDraws', 'originalMessage', 'page-back-departure'):
            self.assertIn(content, retained)

    def test_returning_visit_keeps_only_compatible_prepared_pixels_for_rapid_back(self):
        layers = (APP / 'ui/navigation/PageBackPreviewLayers.kt').read_text()
        replacement = layers.split('fun frame(key: Any', 1)[1].split('private fun release(', 1)[0]
        for guard in ('!previous.releaseRequested', 'previous.sourceKey == sourceKey',
                      'previous.contentKey == contentKey', 'previous.readySize == previous.size',
                      'it.width == previous.size.width && it.height == previous.size.height',
                      'frame.ready = image', 'context.createGraphicsLayer()', 'previous?.let { release(it) }'):
            self.assertIn(guard, replacement)
        self.assertLess(replacement.index('frame.ready = image'), replacement.index('release(it)'))
        self.assertNotIn('frame.generation =', replacement)
        self.assertNotIn('frame.readyGeneration =', replacement)
        schedule = layers.split('private fun schedule(frame: Frame)', 1)[1].split('fun beginGesture(', 1)[0]
        self.assertIn('frame.generation == 0L', schedule)
        test = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/navigation/PageBackStackActivityTest.kt').read_text()
        self.assertIn('fun testReturningVisitInheritsOnlyMatchingPreparedPixelsNotItsLayerOrGeneration()', test)
        retained = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/navigation/PageRetainedContentActivityTest.kt').read_text()
        for method in ('testRealChatRapidNestedSwipeReturnsKeepBothAnimations',
                       'testRealChatRapidNestedSwipeReturnsKeepBothAnimationsInRtl',
                       'testRealChatRapidTopLevelSwipeReturnsKeepBothAnimations'):
            self.assertIn('fun ' + method + '()', retained)
        for assertion in ('second down hits the running departure', 'second gesture immediately owns visible motion',
                          'second departure owns blue settings pixels, not yellow child'):
            self.assertIn(assertion, retained)

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
        self.assertIn('themeKey, configuration, insetsKey, density, layoutDirection, previewScopeKey', self.host)
        self.assertIn('initialVelocity = pageBackInitialVelocity(departure.progress, departure.velocity)', self.host)
        self.assertIn('if (stack.routeKey(routeKey) == visualRouteKey) layers.abandon(request)', self.host)

    def test_zero_progress_returns_follow_the_departing_visits_exact_predecessor(self):
        layers = (APP / 'ui/navigation/PageBackPreviewLayers.kt').read_text()
        for wiring in ('frame.backTargetKey = visualBackTargetKey',
                       'frame.returnDirection = state.returnDirection',
                       'frame.animateReturn = animateBackEnabled && resumed && state.previewAllowed'):
            self.assertIn(wiring, self.host)
        for wiring in ('val previous = frames[currentKey]?.takeIf { currentKey != key }',
                       'it.animateReturn && it.backTargetKey == key && it.size != IntSize.Zero',
                       'Departure(it, key, 0f, predictive = false, direction = it.returnDirection, targetImage = frames[key]?.image)'):
            self.assertIn(wiring, layers)
        enter = layers.split('fun enter(key: Any, contentKey: Any = key): Departure?', 1)[1].split('fun freezeCurrent()', 1)[0]
        self.assertLess(enter.index('departure?.takeIf'), enter.index('previous?.takeIf'))
        for forbidden in ('back()', 'dispatchBack', 'launch', 'predictiveBackEnabled', 'swipeDismissEnabled'):
            self.assertNotIn(forbidden, enter)

    def test_button_returns_use_original_500ms_curve_and_keep_gesture_spring(self):
        self.assertIn('import top.yukonga.miuix.kmp.nav.runtime.NavProgrammaticEasing', self.host)
        self.assertIn('if (departure.progress == 0f) exit.animateTo(1f, tween(durationMillis = 500, easing = NavProgrammaticEasing))', self.host)
        self.assertIn('else exit.animateTo(1f, spring(dampingRatio = 1f, stiffness = 146f, visibilityThreshold = 0.0025f),', self.host)
        gradle = (ROOT / 'app/build.gradle.kts').read_text()
        self.assertIn('implementation("top.yukonga.miuix.kmp:miuix-nav-android:0.9.4-rc01")', gradle)

    def test_overlay_routes_and_failed_capture_do_not_animate_a_blank_parent(self):
        self.assertIn('animateBackEnabled = retainedPage != "assistant-edit" && retainedPage != "history-search"', self.main)
        self.assertIn('"conversation-search", "message-search" -> searchPage?.returnPage', self.main)
        self.assertIn('"history-search" -> null', self.main)
        self.assertIn('val showDeparture = departure != null && departing && departure.frame.canDrawPreview && !departure.forwardSkipped && state.previewAllowed', self.host)
        effect = self.host.split('LaunchedEffect(departure, departure?.forwardSkipped)', 1)[1].split('SideEffect { layers.trim', 1)[0]
        self.assertNotIn('.join()', effect)
        self.assertIn('if (departure.frame.canDrawPreview && !departure.forwardSkipped)', effect)
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
                       'entry.child = active && routeKey != null', 'entry.path = path', 'entry.target = target'):
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
        self.assertIn('BackHandler(previewEnabled = selectedSlots == null)', route)

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
                      'activeDeparture || (state.previewAllowed && !state.active && state.gestureVisualActive)'):
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
        self.assertLess(stack.index('checkNotNull(parent).registry.freezeCurrent()'), stack.index('    CompositionLocalProvider'))
        for token in ('attached(it) && it.path == entry.parentPath', 'registry?.entries?.remove(entry)',
                      'entry.child = active && routeKey != null', 'PageBackRouteKey(root, path)', 'ancestorKeys'):
            self.assertIn(token, stack)
        self.assertNotIn('key(routeKey)', stack)
        self.assertIn('stack.freezeCurrent = { layers.freezeCurrent() }', self.host)

    def test_drag_preview_alpha_avoids_a_fullscreen_offscreen_buffer(self):
        start = self.host.index('if (target != null && state.previewAllowed && state.gestureVisualActive)')
        end = self.host.index('.testTag("page-back-scrim")', start)
        target_layer = self.host[start:end]
        self.assertIn('alpha = 1f - 0.1f * (1f - value)', target_layer)
        self.assertIn('compositingStrategy = CompositingStrategy.ModulateAlpha', target_layer)
        self.assertIn('drawPreview(target)', target_layer)
        self.assertNotIn('drawContent()', target_layer)

    def test_original_miuix_motion_is_full_width_with_parallax_and_independent_dimming(self):
        for token in ('kotlin.math.round(state.direction * size.width * visibleProgress)',
                      'size.width * 0.25f * (1f - value)', 'alpha = 1f - 0.1f * (1f - value)',
                      'drawRect(Color.Black, alpha = 0.5f * (1f - state.visualProgress))', 'absoluteSquircleClip',
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


    def test_release_motion_carries_velocity_without_restarting_from_zero(self):
        self.assertIn('state.dispatchBack(screensPerSecond) { currentBack() }', self.host)
        self.assertIn('SwipeCommit(progress, predictive, direction, velocity)', self.host)
        self.assertIn('value, predictive, direction, velocity, previewAllowed, back ->', self.host)
        self.assertIn('layers.prepare(frame, visualBackTargetKey, value, predictive, direction, velocity)', self.host)
        self.assertIn('Animatable(if (departure?.forward == false) departure.progress else 0f)', self.host)
        self.assertIn('val value = exit.value', self.host)
        self.assertNotIn('departure.progress + (1f - departure.progress) * exit.value', self.host)

    def test_completion_clock_is_owned_by_prepared_request_not_destination_effect(self):
        layers = (APP / 'ui/navigation/PageBackPreviewLayers.kt').read_text()
        motion = (APP / 'ui/navigation/PageBackMotion.kt').read_text()
        prepare = layers.split('fun prepare(', 1)[1].split('fun abandon(', 1)[0]
        self.assertIn('motion = PageBackCompletionMotion(scope, progress, velocity)', prepare)
        self.assertIn('val animation = Animatable(progress)', motion)
        self.assertIn('scope.launch(start = CoroutineStart.UNDISPATCHED)', motion)
        self.assertIn('initialVelocity = pageBackInitialVelocity(progress, velocity)', motion)
        self.assertIn('suspend fun awaitCompletion() = job.join()', motion)
        self.assertIn('departure?.motion?.animation ?: Animatable', self.host)
        effect = self.host.split('LaunchedEffect(departure, departure?.forwardSkipped)', 1)[1].split('SideEffect { layers.trim', 1)[0]
        self.assertIn('if (departure.motion != null)', effect)
        self.assertIn('departure.motion.awaitCompletion()', effect)
        self.assertIn('} else if (!departure.forwardSkipped)', effect)
        self.assertIn('onDispose { departure?.motion?.cancel() }', self.host)
        claim = self.host.split('val claimSwipe by rememberUpdatedState {', 1)[1].split('CompositionLocalProvider', 1)[0]
        self.assertIn('departure?.motion?.cancel()', claim)
        abandon = layers.split('fun abandon(', 1)[1].split('fun trim(', 1)[0]
        self.assertIn('if (pending === departure)', abandon)
        self.assertIn('departure.motion?.cancel()', abandon)
        self.assertIn('if (accepted == null) departure?.motion?.cancel()', layers)
        for operation in ('fun clearPreviews()', 'fun close()'):
            self.assertIn('pending?.motion?.cancel()', layers.split(operation, 1)[1])
        for forbidden in ('System.nanoTime', 'SystemClock', 'delay(', 'withFrameNanos'):
            self.assertNotIn(forbidden, motion)
        android = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/navigation/PageBackGesturesActivityTest.kt').read_text()
        for name in ('testReleaseVelocityContinuesIntoDepartureInRtl',
                     'testVetoedCommitDoesNotPublishCompletionAndNextSwipeCanReturn',
                     'testAsyncPopDoesNotAdoptAnAbandonedGestureClock'):
            self.assertIn('fun ' + name + '()', android)
        self.assertNotIn('the spring t=0 keeps the release position', android)
        self.assertIn('predictive completion advances on the first positive-time frame', android)
        regression = android.split('private fun assertReleaseClockContinuity(', 1)[1].split('fun testVetoedCommit', 1)[0]
        first = regression.split('release position stays continuous', 1)[1].split('val next =', 1)[0]
        self.assertEqual(1, first.count('advanceTimeByFrame()'))
        self.assertIn('delta > remaining * .08f', regression)
        self.assertIn('fun edge(bounds: androidx.compose.ui.geometry.Rect) = if (rtl) bounds.right else bounds.left', regression)
        for sample in ('edge(released)', 'edge(start)', 'edge(next)', 'edge(initial)'):
            self.assertIn(sample, regression)
        predictive = android.split('val release = ui.onNodeWithTag("page-back-surface")', 1)[1].split('predictive release position stays continuous', 1)[0]
        self.assertIn('mainThread { activity.onBackPressedDispatcher.onBackPressed() }', predictive)
        self.assertNotIn('advanceTimeByFrame()', predictive)
        jvm = (ROOT / 'app/src/test/kotlin/app/daiyu/android/ui/navigation/PageBackMotionTest.kt').read_text()
        self.assertIn('BroadcastFrameClock()', jvm)
        self.assertIn('commit registers a frame without destination composition', jvm)
        self.assertIn('first positive time must preserve momentum', jvm)
        self.assertIn('abandonedMotionCannotAdvanceOrAffectTheNextRequest', jvm)

    def test_gesture_pins_prepared_pixels_before_motion_and_unlocks_at_rest(self):
        layers = (APP / 'ui/navigation/PageBackPreviewLayers.kt').read_text()
        begin = layers.split('fun beginGesture(frame: Frame): Boolean {', 1)[1].split('fun resume(', 1)[0]
        self.assertNotIn('hasFreshImage', begin)
        self.assertIn('freeze(frame)', begin)
        self.assertIn('return frame.canDrawPreview', begin)
        self.assertIn('if (frame.frozen) return', layers)
        self.assertIn('gesturePreviewReady = beginPreview()', self.host)
        self.assertIn('beginPreview = { currentTarget != null && layers.beginGesture(frame) }', self.host)
        self.assertIn('if (!previewAllowed) frame.animateReturn = false', self.host)
        self.assertIn('state.previewAllowed && (state.active || state.gestureVisualActive) -> frame.image', self.host)
        self.assertIn('departure != null && showDeparture && !departure.forward ->', self.host)
        self.assertIn('if (retainedRootContent != null && retainedRootVisible) null else departure.targetImage', self.host)
        self.assertIn('layers.resume(frame)', self.host)
        self.assertIn('state.restorePreview()', self.host)

    def test_continuity_has_native_velocity_pending_copy_and_pixel_regressions(self):
        base = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/navigation'
        gestures = (base / 'PageBackGesturesActivityTest.kt').read_text()
        stack = (base / 'PageBackStackActivityTest.kt').read_text()
        for name in ('testReleaseVelocityContinuesIntoDeparture', 'testPinnedPixelsSurviveContentUpdateUntilCommit', 'testCancelledGestureRestoresCurrentLivePixels', 'testPointerProgressUpdatesDoNotRecomposePageContentPerFrame'):
            self.assertIn('fun ' + name + '()', gestures)
        self.assertIn('afterFirstFrame <= beforeGesture + 1', gestures)
        self.assertIn('afterFirstFrame, pageCompositions', gestures)
        for name in ('testReturnAdvancesWhileCaptureIsPending', 'testColdReturnDoesNotWaitOrEnableLatePreview', 'testFrozenPreviewRejectsSameGenerationLateCopy'):
            self.assertIn('fun ' + name + '()', stack)
        self.assertIn('assertFalse(gate.isCompleted)', stack)
        self.assertIn('same-generation late copy cannot replace the visible image', stack)


if __name__ == '__main__':
    unittest.main()
