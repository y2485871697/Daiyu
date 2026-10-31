"""Source contracts; Android rendering and interaction await the approved batch."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCREEN = (ROOT / "app/src/main/kotlin/app/daiyu/android/ui/screens/settings/AppearanceSettingsScreen.kt").read_text()
BLUR = SCREEN.split("if (choosingBlur)", 1)[1].split("SettingsScaffoldPage(title", 1)[0]


class AppearanceBlurSheetContractTest(unittest.TestCase):
    def test_blur_uses_shared_glass_bottom_sheet_without_an_extra_close_action(self):
        self.assertNotIn("colorEditor?.let", SCREEN)
        self.assertIn("ChatSelectionSheet(", BLUR)
        self.assertIn('title = "顶栏模糊样式"', BLUR)
        self.assertIn("showCloseButton = false", BLUR)
        self.assertNotIn("DaiyuChoiceSheet", SCREEN)
        for primitive in ("WindowDialog", "DialogProperties", "dialogEnterMotion", "RoundedCornerShape"):
            self.assertNotIn(primitive, BLUR.split("Column(", 1)[0])

    def test_choices_use_voice_style_highlights_and_a_scrollable_group(self):
        self.assertIn(".verticalScroll(rememberScrollState())", BLUR)
        self.assertIn(".selectableGroup()", BLUR)
        self.assertIn("blurStyles.forEachIndexed", BLUR)
        self.assertIn('SettingsRadioRow(title, selected = state.topBarBlurStyle.ordinal == index)', BLUR)
        shared = (ROOT / "app/src/main/kotlin/app/daiyu/android/ui/screens/settings/SettingsUiComponents.kt").read_text()
        self.assertIn("if (LocalBottomSheetContent.current)", shared)
        self.assertIn("OptionSelectionItem(title, selected, enabled = enabled, summary = summary", shared)
        row = (ROOT / "app/src/main/kotlin/app/daiyu/android/ui/components/OptionSelectionSheet.kt").read_text()
        for fragment in ('usagePalette()', 'RoundedCornerShape(12.dp)', '48.dp else 56.dp',
                         'colors.selectionContainer', 'colors.selectionContent',
                         'colors.accent', 'colors.onPrimary', 'Modifier.size(22.dp)',
                         '14.sp else 16.sp', '20.sp else 24.sp', 'Role.RadioButton'):
            self.assertIn(fragment, row)
        self.assertIn('testTag("settings-radio:$title")', shared)
        self.assertIn("selected = state.topBarBlurStyle.ordinal == index", BLUR)
        self.assertIn("onChange(state.copy(topBarBlurStyle = AppearanceTopBarBlurStyle.entries[index]))", BLUR)
        self.assertEqual(1, BLUR.count("onChange("))

    def test_no_cancel_or_description_and_outside_back_dismiss_does_not_save(self):
        for fragment in ('footer =', 'AssistantPreviewButton', 'choice-sheet-cancel',
                         'subtitle =', 'summary =', '"取消"'):
            self.assertNotIn(fragment, BLUR)
        self.assertIn("onDismiss = { choosingBlur = false }", BLUR)
        self.assertEqual(1, BLUR.count("choosingBlur = false"))
        self.assertIn('listOf("高斯模糊", "渐进模糊")', SCREEN)
        self.assertIn("val blurSupported = isRuntimeShaderSupported()", SCREEN)
        self.assertIn("if (blurSupported)", SCREEN)
        self.assertIn("if (state.blurEnabled)", SCREEN)


if __name__ == "__main__":
    unittest.main()
