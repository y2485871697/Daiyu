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
        self.assertIn('if (changed) { update(); false }', overlay)
        self.assertIn('FLAG_LAYOUT_NO_LIMITS', overlay)
        self.assertIn('FLAG_LAYOUT_IN_SCREEN', overlay)
        self.assertNotIn('constrainPetOverlayPosition', overlay)

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
