"""Source-wiring guards; actual Compose frames remain deferred Android coverage."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
NAV = ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/navigation'


class PageEnterContractTest(unittest.TestCase):
    def setUp(self):
        self.host = (NAV / 'PageBackGestures.kt').read_text()
        self.layers = (NAV / 'PageBackPreviewLayers.kt').read_text()

    def test_entry_requires_an_exact_predecessor_and_gives_pop_priority(self):
        transition = self.layers.split('fun transition(', 1)[1].split('fun freezeCurrent()', 1)[0]
        self.assertLess(transition.index('val departure = enter(key)'), transition.index('if (!animateEnter'))
        self.assertIn('if (departure != null) return departure', transition)
        self.assertIn('previous.sourceKey != backTargetKey || previous.childChangeCaptured == false', transition)
        self.assertIn('val source = frames[previous.sourceKey]?.takeIf', transition)
        self.assertIn('it.animateReturn && size != IntSize.Zero && it.size == size', transition)
        self.assertIn('forward = true', transition)
        self.assertIn('val forward: Boolean = false', self.layers)
        self.assertIn('layers.transition(visualRouteKey, visualBackTargetKey, animateEnter = animateBackEnabled && resumed, size = viewport)', self.host)
        self.assertIn('frame.sourceKey = if (visualRouteKey == routeKey) contentKey else visualRouteKey', self.host)

    def test_nested_content_holds_only_its_outgoing_visit_and_cold_children_do_not_push(self):
        self.assertIn('it.childChangeCaptured = it.animateReturn && it.size != IntSize.Zero', self.layers)
        self.assertIn('it.holdingForChild = it.childChangeCaptured == true', self.layers)
        self.assertIn('if (held != null) drawImage(held)', self.host)
        self.assertIn('drawContent()', self.host)
        self.assertIn('var holdingForChild by mutableStateOf(false)', self.layers)
        self.assertIn('layers.frame(visualRouteKey, sourceKey = if (visualRouteKey == routeKey) contentKey else visualRouteKey)', self.host)

    def test_original_push_geometry_places_the_frozen_old_page_below_one_live_page(self):
        previous = self.host.index('testTag("page-enter-previous")')
        scrim = self.host.index('testTag("page-enter-scrim")')
        live = self.host.index('key(contentKey) {')
        self.assertLess(previous, scrim)
        self.assertLess(scrim, live)
        for token in ('translationX = -departure.direction * size.width * 0.25f * exit.value',
                      'alpha = 1f - 0.1f * exit.value',
                      'drawRect(Color.Black, alpha = 0.5f * exit.value)',
                      'kotlin.math.round(departure.direction * size.width * (1f - exit.value))',
                      '(showDeparture && departure?.forward == true)',
                      'if (departure != null && showDeparture && !departure.forward) Box('):
            self.assertIn(token, self.host)
        self.assertEqual(1, self.host.count('{ content() }'))
        self.assertNotIn('drawLayer(departure.frame.layer)', self.host)

    def test_entry_reuses_original_programmatic_curve_not_the_gesture_preference(self):
        self.assertIn('if (departure.progress == 0f) exit.animateTo(1f, tween(durationMillis = 500, easing = NavProgrammaticEasing))', self.host)
        self.assertIn('else exit.animateTo(1f, spring(dampingRatio = 1f, stiffness = 146f, visibilityThreshold = 0.0025f))', self.host)
        transition = self.layers.split('fun transition(', 1)[1].split('fun freezeCurrent()', 1)[0]
        for forbidden in ('swipeDismissEnabled', 'predictiveBackEnabled', 'back()', 'dispatchBack'):
            self.assertNotIn(forbidden, transition)

    def test_pending_capture_and_transition_input_have_explicit_gates(self):
        self.assertIn('departure.frame.capture?.join()', self.host)
        self.assertIn('if (departure.frame.image != null && !departure.forwardSkipped)', self.host)
        self.assertIn('departing && departure.frame.image != null && !departure.forwardSkipped && state.previewAllowed', self.host)
        self.assertIn('if (departure != null && showDeparture && departure.forward)', self.host)
        self.assertIn('departure.forwardSkipped = true', self.host)
        self.assertIn('departure?.forward == true && departing && !showDeparture', self.host)
        self.assertIn('LaunchedEffect(departure, departure?.forwardSkipped)', self.host)
        self.assertIn('var forwardSkipped by mutableStateOf(false)', self.layers)
        self.assertIn('if (frame.holdingForChild) frame.childChangeCaptured = false', self.host)
        self.assertIn('if (activeDeparture && departure?.forward == true) Box(', self.host)
        self.assertIn('testTag("page-enter-blocker").pointerInput(departure)', self.host)
        self.assertIn('state.transitionBlocked = activeDeparture ||', self.host)

    def test_frame_fixture_keeps_cold_clock_paused_and_samples_pixels_before_geometry(self):
        test = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/navigation/PageEnterActivityTest.kt').read_text()
        motion = test.split('private fun assertEntryMotion(', 1)[1].split('fun testEnteringPage', 1)[0]
        self.assertLess(motion.index('val pixels = captureScreenNode'), motion.index('val live ='))
        cold = test.split('fun testInitiallyMountedNestedPageHasNoInventedParentEntry()', 1)[1].split('fun testSidebar', 1)[0]
        self.assertLess(cold.index('ui.mainClock.autoAdvance = false'), cold.index('mount(local = true)'))
        for token in ('repeat(40)', 'first child covers blue settings', 'second child covers green first child',
                      'sidebar fixture was really drawn green', 'Build.VERSION.SDK_INT < 28'):
            self.assertIn(token, test)
        self.assertNotIn('.performClick()', test)

    def test_deferred_android_regressions_cover_entry_frames_and_exclusions(self):
        test = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/navigation/PageEnterActivityTest.kt').read_text()
        for method in ('testEnteringPageUsesOriginalMotionWithBothGesturesDisabled',
                       'testEnteringPageMirrorsTheOriginalMotionInRtl',
                       'testInitiallyMountedNestedPageHasNoInventedParentEntry',
                       'testNestedEntriesKeepOneLiveControllerAndDoNotReplayOnLoadingChanges',
                       'testSidebarContentAliasEntersARealPageAndEntryBlocksTouchThrough',
                       'testColdStartReplacementAndScopeChangeDoNotInventAnEntry',
                       'testDialogRoutesAndPreviewSuppressionDoNotMoveUnderlyingContent'):
            self.assertIn('fun ' + method + '(', test)
        for token in ('swipeDismissEnabled = false, predictiveBackEnabled = false',
                      'progress > 0f && progress < 1f', 'initial.width * .25f * progress',
                      'val alpha = 1f - .1f * progress', 'val light = 1f - .5f * progress',
                      'assertEquals(1, controllers)', 'assertEquals(0, disposed)',
                      'scopeId++; screen = "settings"', '.performTouchInput { click(',
                      'assertEquals(0, fixture.opens.get())'):
            self.assertIn(token, test)


if __name__ == '__main__':
    unittest.main()
