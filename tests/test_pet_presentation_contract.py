"""Retained original maid presentation and stable sprite placement."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
PET = ROOT / 'app/src/main/kotlin/app/daiyu/android/pet'


class PetPresentationContractTest(unittest.TestCase):
    def test_original_bubble_has_exact_scaled_cap_and_unscaled_shape(self):
        text = (PET / 'PetSpeechBubbleView.kt').read_text()
        for fragment in ('top.yukonga.miuix.kmp.basic.Text', '(13f * scale).sp', 'FontWeight.Medium',
                         '.padding(bottom = 6.dp)', '.widthIn(max = 220.dp * scale)',
                         '.background(Color.White, RoundedCornerShape(12.dp))',
                         '.border(2.dp, Color(0xFF3B82F6), RoundedCornerShape(12.dp))',
                         '.padding(horizontal = 10.dp, vertical = 8.dp)', 'Color(0xFF1E293B)',
                         'delay(2600)', 'tween(durationMillis = 500)', 'indication = null'):
            self.assertIn(fragment, text)
        panel = (PET / 'PetViewFactory.kt').read_text().split('internal class PetPanel', 1)[1].split('internal class PetSpriteView', 1)[0]
        self.assertNotIn('TextView(context)', panel)
        self.assertIn('LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)', panel)

    def test_in_app_places_in_layout_and_overlay_gates_first_draw(self):
        panel = (PET / 'PetViewFactory.kt').read_text().split('internal class PetPanel', 1)[1].split('internal class PetSpriteView', 1)[0]
        self.assertIn('render(controller.snapshot())', panel)
        layout = panel.split('override fun onLayout', 1)[1].split('override fun render', 1)[0]
        self.assertLess(layout.index('placeInApp()'), layout.index('alpha = 1f'))
        self.assertNotIn('post {', panel)
        overlay = (PET / 'PetOverlayHost.kt').read_text()
        self.assertIn('alpha = 0f', overlay)
        self.assertIn('addOnPreDrawListener', overlay)
        self.assertIn('if (changed) { alpha = 0f; schedulePlacementUpdate(this); false }', overlay)
        self.assertIn('FLAG_LAYOUT_NO_LIMITS', overlay)
        self.assertIn('FLAG_LAYOUT_IN_SCREEN', overlay)
        self.assertNotIn('constrainPetOverlayPosition', overlay)

    def test_overlay_submits_placement_after_traversal_and_fences_retired_roots(self):
        text = (PET / 'PetOverlayHost.kt').read_text()
        gate = text.split('viewTreeObserver.addOnPreDrawListener', 1)[1].split('private fun schedulePlacementUpdate', 1)[0]
        self.assertIn('schedulePlacementUpdate(this)', gate)
        self.assertNotIn('update();', gate)
        posted = text.split('private fun schedulePlacementUpdate', 1)[1].split('private fun windowOriginMatches', 1)[0]
        for fragment in ('if (current.placementUpdatePending) return', 'current.placementUpdatePending = true',
                         'ui.post {', 'current.placementUpdatePending = false',
                         '!closed && !owner.cleanupPending && view === current', 'update()'):
            self.assertIn(fragment, posted)
        self.assertLess(posted.index('ui.post {'), posted.index('update()'))
        self.assertNotIn('alpha = 1f', posted)

    def test_sprite_anchor_is_saved_instead_of_bubble_window_origin(self):
        geometry = (PET / 'PetPlacement.kt').read_text()
        self.assertIn('spriteX - (contentWidth - petSide).coerceAtLeast(0) / 2', geometry)
        self.assertIn('spriteY - (contentHeight - petSide).coerceAtLeast(0)', geometry)
        self.assertIn('controller.setPosition(anchorX, anchorY)', (PET / 'PetOverlayHost.kt').read_text())
        panel = (PET / 'PetViewFactory.kt').read_text()
        self.assertIn('controller.setPosition(next.spriteX, next.spriteY)', panel)
        self.assertIn('ui.postDelayed(tick, 188)', panel)
        self.assertIn('isFilterBitmap = false', panel)

    def test_checks_only_runs_pet_packages_and_never_builds_apk(self):
        workflow = (ROOT / '.github/workflows/pet-presentation-checks.yml').read_text()
        self.assertIn(":app:testDebugUnitTest --tests 'app.daiyu.android.pet.*'", workflow)
        self.assertIn(":core-domain:test --tests 'app.daiyu.android.domain.pet.*'", workflow)
        self.assertIn('checks_only.init.gradle', workflow)
        self.assertIn('app/build/test-results/testDebugUnitTest/', workflow)
        self.assertIn('core-domain/build/test-results/test/', workflow)
        self.assertNotIn('assemble', workflow)
        self.assertNotIn('discover', workflow)

    def test_overlay_waits_for_applied_window_origin_not_requested_params(self):
        text = (PET / 'PetOverlayHost.kt').read_text()
        gate = text.split('viewTreeObserver.addOnPreDrawListener', 1)[1].split('private fun windowOriginMatches', 1)[0]
        self.assertIn('else if (!windowOriginMatches(this, next))', gate)
        self.assertIn('postInvalidateOnAnimation()', gate)
        self.assertLess(gate.index('windowOriginMatches(this, next)'), gate.index('alpha = 1f'))
        applied = text.split('private fun windowOriginMatches', 1)[1].split('private fun placement', 1)[0]
        self.assertIn('current.getLocationOnScreen(screen)', applied)
        self.assertIn('current.getLocationInWindow(local)', applied)
        self.assertIn('screen[0] - local[0] == target.x', applied)
        self.assertIn('screen[1] - local[1] == target.y', applied)
        tests = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/pet/PetPresentationTest.kt').read_text()
        overlay = tests.split('fun testRealOverlayFirstDrawSpeechGrowthAndDismissalPreserveAnchor', 1)[1]
        self.assertIn('repeat(3)', overlay)
        self.assertIn('visibleFrames.all { it == expected }', overlay)

    def test_overlay_frame_sampler_stops_at_acceptance_without_espresso_idle(self):
        text = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/pet/PetPresentationTest.kt').read_text()
        sampler = text.split('private fun stable(expectedSpeech: String? = null): Rect', 1)[1].split('override fun cleanupFixture', 1)[0]
        self.assertIn('java.util.concurrent.CountDownLatch(1)', sampler)
        self.assertIn('java.util.concurrent.TimeUnit.SECONDS.toNanos(5)', sampler)
        self.assertIn('frameSignals.tryAcquire(remaining, java.util.concurrent.TimeUnit.NANOSECONDS)', sampler)
        self.assertIn('if (completed.count != 0L) ui.mainClock.advanceTimeByFrame()', sampler)
        self.assertIn('frameSignals.release()', sampler)
        self.assertIn('lastFrameState.get()', sampler)
        accepted = sampler.split('if (next == previous && accepted.compareAndSet(null, next))', 1)[1].split('previous = next', 1)[0]
        self.assertLess(accepted.index('active = false'), accepted.index('completed.countDown()'))
        self.assertLess(accepted.index('removeFrameCallback(callback)'), accepted.index('completed.countDown()'))
        for value in ('rootX: Int', 'rootY: Int', 'width: Int', 'height: Int', 'spriteLeft: Int', 'spriteTop: Int'):
            self.assertIn(value, sampler)
        self.assertNotIn('ui.waitUntil', sampler)
        self.assertNotIn('Thread.sleep', sampler)
        self.assertIn('failure.get()?.let { throw it }', sampler)
        self.assertIn('finally {', sampler)
        self.assertIn('removeOnDrawListener(listener)', sampler)

    def test_real_overlay_uses_native_draw_and_exact_live_text_not_global_idle(self):
        text = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/pet/PetPresentationTest.kt').read_text()
        overlay = text.split('fun testRealOverlayFirstDrawSpeechGrowthAndDismissalPreserveAnchor', 1)[1]
        self.assertNotIn('mainThread {', overlay)
        self.assertNotIn('speak(', overlay)
        self.assertIn('instrumentation.runOnMainSync', overlay)
        self.assertIn('stable(text)', overlay)
        self.assertIn('repeat(3)', overlay)
        self.assertIn('visibleFrames.all { it == expected }', overlay)
        speech = text.split('private fun overlaySpeechReady', 1)[1].split('private fun stable', 1)[0]
        for fragment in ('bubble.visibility == View.GONE', 'SemanticsProperties.Text', 'it.text == text',
                         'semanticsOwner', 'unmergedRootSemanticsNode', 'node.layoutInfo.isPlaced',
                         'node.layoutInfo.coordinates.isAttached', 'node.boundsInRoot.width > 0f'):
            self.assertIn(fragment, speech)
        sampler = text.split('private fun stable', 1)[1].split('override fun cleanupFixture', 1)[0]
        self.assertIn('overlaySpeechReady(expectedSpeech)', sampler)
        self.assertIn('ui.mainClock.advanceTimeByFrame()', sampler)
