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
        cls.host, surface = cls.source.split("internal fun ChatGlassMenuSurface(", 1)
        cls.surface, _, cls.shadows = surface.partition("private fun ChatMenuShadows(")

    def test_production_passes_motion_without_clipping_shadow_in_parent(self):
        self.assertIn("motion = motion", self.host)
        self.assertIn("rememberPopupMotion(menu.expanded())", self.host)
        self.assertIn("0.15f + 0.85f * motion.fraction.value", self.host)
        self.assertIn("TransformOrigin(1f, 0f)", self.host)
        self.assertNotIn("alpha =", self.host)
        self.assertNotIn(".etaMenuClipReveal(", self.host)

    def test_shadows_are_non_measuring_siblings_outside_body_alpha_and_reveal(self):
        self.assertIn("Box(modifier.width(IntrinsicSize.Max), propagateMinConstraints = true)", self.surface)
        self.assertIn("ChatMenuShadows(motion, dark, Modifier.matchParentSize())", self.surface)
        self.assertLess(self.surface.index("ChatMenuShadows("), self.surface.index("Column(Modifier"))
        self.assertNotIn(".dropShadow(", self.surface)
        self.assertEqual(2, self.shadows.count(".dropShadow(shape) {"))
        self.assertLess(self.shadows.index(".graphicsLayer {"), self.shadows.index(".dropShadow(shape) {"))
        self.assertNotIn(".etaMenuClipReveal", self.shadows)
        self.assertLess(self.surface.index(".graphicsLayer {"), self.surface.index(".etaMenuClipReveal("))
        self.assertLess(self.surface.index(".etaMenuClipReveal("), self.surface.index(".clip(shape).then(blur)"))
        self.assertNotIn("ModulateAlpha", self.source)

    def test_shadow_caches_one_outline_and_reveals_only_in_the_graphics_layer(self):
        self.assertIn("if (motion?.visibleState?.targetState == false) 1f", self.source)
        self.assertIn("val shape = remember { RoundedCornerShape(19.dp) }", self.shadows)
        self.assertIn(".graphicsLayer {", self.shadows)
        prefix, layer_and_shadows = self.shadows.split(".graphicsLayer {", 1)
        layer, shadows = layer_and_shadows.split(".dropShadow(", 1)
        self.assertIn("scaleY = menuRevealFraction(motion)", layer)
        self.assertIn("transformOrigin = TransformOrigin(.5f, 0f)", layer)
        self.assertNotIn("menuRevealFraction(", prefix)
        self.assertNotIn(".fraction.value", prefix)
        self.assertNotIn(".alpha.value", prefix)
        self.assertNotIn("alpha =", layer)
        for token in ["GenericShape", "shadowShape", "val revealFraction =", "size.height *", "LocalDensity"]:
            self.assertNotIn(token, self.shadows)
        self.assertNotIn("menuRevealFraction(", shadows)
        self.assertIn("fractionProgress = { menuRevealFraction(motion) }", self.surface)

    def test_shadow_opacity_is_read_by_each_draw_block_not_composition(self):
        self.assertIn("motion: PopupMotion? = null", self.surface)
        self.assertNotIn("val opacity =", self.shadows)
        self.assertNotIn(".copy(alpha", self.shadows)
        self.assertEqual(2, self.shadows.count("alpha = (motion?.alpha?.value ?: 1f).coerceIn(0f, 1f)"))
        for token in ["35.dp else 34.dp", "14.dp else 12.dp", "6.dp.toPx()", "2.dp.toPx()",
                      "0x55000000", "0x164C3A6B", "0x22000000", "0x05342048"]:
            self.assertIn(token, self.shadows)
        self.assertIn("delay(PopupExitRetentionMillis)", self.host)
        self.assertIn("else Modifier.clearAndSetSemantics {}", self.host)
        self.assertIn("surface.place(x, y)", self.host)

    def test_body_does_not_subscribe_to_animation_in_composition(self):
        for token in ["val revealFraction =", "val opacity =", "motion?.fraction?.value",
                      "remember(shape, revealFraction", "val shadowShape ="]:
            self.assertNotIn(token, self.surface.split("private fun menuRevealFraction", 1)[0])
        self.assertIn(".graphicsLayer { alpha = (motion?.alpha?.value ?: 1f).coerceIn(0f, 1f) }", self.surface)
        self.assertIn("fractionProgress = { menuRevealFraction(motion) }", self.surface)
        # Real backdrop sampling and the original visible decoration remain on the body.
        for token in ["Modifier.textureBlur(", "blurRadius = 24f", "saturation = 1.35f",
                      ".clip(shape).then(blur)", "Path.combine(PathOperation.Difference", ".verticalScroll("]:
            self.assertIn(token, self.surface)

    def test_android_regression_covers_frame_updates_with_real_backdrop_and_stable_body(self):
        test = PIXELS.read_text()
        for token in ["testAnimationFramesKeepMenuContentCompositionAndMeasurementStable",
                      "rememberTopBarBackdrop()", "captureForTopBar(backdrop)",
                      "contentCompositions.incrementAndGet()", "contentMeasures.incrementAndGet()",
                      "expectedCompositions, contentCompositions.get()", "expectedMeasures, contentMeasures.get()",
                      "listOf(.2f to .4f, .45f to .7f, .8f to .9f, 1f to 1f, 1f to .5f)"]:
            self.assertIn(token, test)

    def test_pixel_regression_covers_intermediate_alpha_exit_and_reopen(self):
        test = PIXELS.read_text()
        for token in ["testOutsideShadowSurvivesPartialRevealAndAlphaThroughReopenAndExitInBothThemes",
                      "listOf(false, true)", "Frame(.25f, .5f)", "Frame(.999f, .999f)",
                      "Frame(1f, .999f)", "Frame(.75f, .7f, false)", "Frame(0f, 0f, false)",
                      '"outside shadow missing:', '"hidden menu leaves no shadow:',
                      '"body still reveals from the top:', '"shadow must not reveal the full-height menu:', '"revealed body is visible:',
                      '"shadow is continuous at full opacity:', "motion = motion",
                      "bounds.left - hostBounds.left - 3f * density"]:
            self.assertIn(token, test)


if __name__ == "__main__":
    unittest.main()
