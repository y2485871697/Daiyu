"""Source wiring only; pixel, touch and scrolling regressions run in the Android batch."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / "app/src/main/kotlin/app/daiyu/android/ui"

class FloatingPageActionsContract(unittest.TestCase):
    def test_overlay_measures_the_entire_action_layer_without_paint_or_touch_blocker(self):
        text = (UI / "layout/FloatingPageActionsLayout.kt").read_text()
        for fragment in ("Box(modifier.fillMaxSize())", "Alignment.BottomCenter", "onSizeChanged",
                         "actionsHeight = it.height", "actionsHeight.toDp()", "content(bottomSpacerMinHeight)"):
            self.assertIn(fragment, text)
        for forbidden in (".background(", ".border(", "Surface(", ".clickable(", "pointerInput", "48.dp"):
            self.assertNotIn(forbidden, text)

    def test_both_page_entries_use_full_height_content_and_measured_tail_clearance(self):
        for filename in ("ProviderDirectoryPage.kt", "LocalCatalogModelsScreen.kt"):
            text = (UI / "screens/settings" / filename).read_text()
            with self.subTest(filename=filename):
                self.assertIn("FloatingPageActionsLayout(", text)
                self.assertIn("content = { bottomSpacerMinHeight ->", text)
                self.assertIn("bottomSpacerMinHeight = bottomSpacerMinHeight", text)
                self.assertNotIn("Column(Modifier.fillMaxSize().background(settingsPageBackground()))", text)
                self.assertNotIn("Column(modifier.fillMaxSize().background(settingsPageBackground()))", text)
                self.assertIn(".navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)", text)
                self.assertIn("colors = deletionButtonColors()", text)
                self.assertIn("color = accents.secondaryContainer", text)

    def test_cancel_fill_is_precomposited_only_on_both_floating_pages(self):
        for filename in ("ProviderDirectoryPage.kt", "LocalCatalogModelsScreen.kt"):
            text = (UI / "screens/settings" / filename).read_text()
            cancel = text.split('TextButton(text = "取消"', 1)[1].split('TextButton(text = "删除"', 1)[0]
            with self.subTest(filename=filename):
                self.assertIn("color = accents.secondaryContainer.compositeOver(settingsPageBackground())", cancel)
                self.assertIn("textColor = accents.onSecondary", cancel)
                self.assertIn("import androidx.compose.ui.graphics.compositeOver", text)
                self.assertNotIn("copy(alpha = 1", cancel)
                self.assertNotIn(".background(", cancel)
        android = ROOT / "app/src/androidTest/kotlin/app/daiyu/android/FloatingPageActionsActivityTest.kt"
        for entry in ("testCancelFillIsOpaqueButKeepsItsSoftColorAcrossUnderlyingContent", "Color.Green",
                      "pureBlackEnabled", "expectedFill", "uncompositedFill", "compositeOver(settingsPageBackground())"):
            self.assertIn(entry, android.read_text())

    def test_clearance_reuses_the_existing_scrollable_spacer_without_double_navigation_inset(self):
        settings = (UI / "screens/settings/SettingsScaffold.kt").read_text()
        scaffold = (UI / "components/MiuixScaffoldPage.kt").read_text()
        for text in (settings, scaffold):
            self.assertIn("bottomSpacerMinHeight: Dp = 0.dp", text)
        self.assertIn("bottomSpacerMinHeight = bottomSpacerMinHeight", settings)
        self.assertIn("MiuixPageBottomSpacer(Modifier.heightIn(min = bottomSpacerMinHeight))", scaffold)
        self.assertIn('item(key = "bottom_spacer")', scaffold)
        self.assertIn(".height(24.dp)", scaffold)
        self.assertIn(".navigationBarsPadding()", scaffold)

    def test_android_regressions_cover_pixels_pass_through_and_tail_reachability(self):
        android = ROOT / "app/src/androidTest/kotlin/app/daiyu/android"
        text = (android / "FloatingPageActionsActivityTest.kt").read_text()
        for entry in ("testTransparentGapsPassThroughAndTrackActualFooterHeight", "Color.Magenta", "toPixelMap",
                      "performTouchInput", "backgroundClicks", "fontScale", "navigationBarsPadding"):
            self.assertIn(entry, text)
        for name, token in (("ProviderDirectorySelectionActivityTest.kt", "testFloatingSelectionCanScrollLastProviderAboveButtons"),
                            ("ModelBulkSelectionActivityTest.kt", "testFloatingSelectionCanScrollLastModelAboveButtons")):
            self.assertIn(token, (android / name).read_text())

if __name__ == "__main__":
    unittest.main()
