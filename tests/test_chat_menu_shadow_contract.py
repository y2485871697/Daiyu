"""Source guards for the production anchored menu's animated shadow layers."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation/ChatMenuLayer.kt"
PIXELS = ROOT / "app/src/androidTest/kotlin/app/daiyu/android/ChatGlassMenuSurfaceTest.kt"


class ChatMenuShadowContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = SOURCE.read_text()
        cls.host, cls.surface = cls.source.split("internal fun ChatGlassMenuSurface(", 1)

    def test_production_passes_motion_without_clipping_shadow_in_parent(self):
        self.assertIn("motion = motion", self.host)
        self.assertIn("rememberPopupMotion(menu.expanded())", self.host)
        self.assertIn("0.15f + 0.85f * motion.fraction.value", self.host)
        self.assertIn("TransformOrigin(1f, 0f)", self.host)
        self.assertNotIn("alpha =", self.host)
        self.assertNotIn(".etaMenuClipReveal(", self.host)

    def test_both_shadows_precede_body_alpha_and_reveal(self):
        chain = self.surface[self.surface.index("Column(modifier"):]
        self.assertEqual(2, chain.count(".dropShadow(shadowShape,"))
        self.assertLess(chain.rindex(".dropShadow("), chain.index(".graphicsLayer { alpha = opacity }"))
        self.assertLess(chain.index(".graphicsLayer { alpha = opacity }"), chain.index(".etaMenuClipReveal("))
        self.assertLess(chain.index(".etaMenuClipReveal("), chain.index(".clip(shape).then(blur)"))
        self.assertNotIn("ModulateAlpha", self.source)

    def test_shadow_outline_tracks_reveal_and_retains_exit_outline(self):
        for token in ["if (motion?.visibleState?.targetState == false) 1f",
                      "remember(shape, revealFraction, radius)", "if (revealFraction == 1f) shape",
                      "GenericShape { size, _ ->", "if (revealFraction > 0f)",
                      "size.height * revealFraction", "CornerRadius(radius)",
                      "fractionProgress = { revealFraction }"]:
            self.assertIn(token, self.surface)

    def test_shadow_opacity_uses_existing_transition_and_static_surface_stays_visible(self):
        self.assertIn("motion: PopupMotion? = null", self.surface)
        self.assertIn("val opacity = (motion?.alpha?.value ?: 1f).coerceIn(0f, 1f)", self.surface)
        self.assertIn("broadShadow.copy(alpha = broadShadow.alpha * opacity)", self.surface)
        self.assertIn("nearShadow.copy(alpha = nearShadow.alpha * opacity)", self.surface)
        self.assertIn("delay(PopupExitRetentionMillis)", self.host)
        self.assertIn("else Modifier.clearAndSetSemantics {}", self.host)
        self.assertIn("surface.place(x, y)", self.host)

    def test_pixel_regression_covers_intermediate_alpha_exit_and_reopen(self):
        test = PIXELS.read_text()
        for token in ["testOutsideShadowSurvivesPartialRevealAndAlphaThroughReopenAndExitInBothThemes",
                      "listOf(false, true)", "Frame(.25f, .5f)", "Frame(.999f, .999f)",
                      "Frame(1f, .999f)", "Frame(.75f, .7f, false)", "Frame(0f, 0f, false)",
                      '"outside shadow missing:', '"hidden menu leaves no shadow:',
                      '"body still reveals from the top:', '"revealed body is visible:',
                      '"shadow is continuous at full opacity:', "motion = motion",
                      "bounds.left - hostBounds.left - 3f * density"]:
            self.assertIn(token, test)


if __name__ == "__main__":
    unittest.main()
