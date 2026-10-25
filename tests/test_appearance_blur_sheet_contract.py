"""Source contracts; Android rendering and interaction await the approved batch."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCREEN = (ROOT / "app/src/main/kotlin/app/daiyu/android/ui/screens/settings/AppearanceSettingsScreen.kt").read_text()
BLUR = SCREEN.split("if (choosingBlur)", 1)[1].split("if (scaleEditor)", 1)[0]


class AppearanceBlurSheetContractTest(unittest.TestCase):
    def test_blur_uses_shared_glass_bottom_sheet_without_an_extra_close_action(self):
        self.assertIn("ChatSelectionSheet(", BLUR)
        self.assertIn('title = "顶栏模糊样式"', BLUR)
        self.assertIn("showCloseButton = false", BLUR)
        self.assertNotIn("DaiyuChoiceSheet", SCREEN)
        for primitive in ("WindowDialog", "DialogProperties", "dialogEnterMotion", "RoundedCornerShape"):
            self.assertNotIn(primitive, BLUR)

    def test_choices_use_selected_radio_rows_and_a_scrollable_group(self):
        self.assertIn(".verticalScroll(rememberScrollState())", BLUR)
        self.assertIn(".selectableGroup()", BLUR)
        self.assertIn("blurStyles.forEachIndexed", BLUR)
        self.assertIn("SettingsRadioRow", BLUR)
        self.assertIn("selected = state.topBarBlurStyle.ordinal == index", BLUR)
        self.assertIn("onChange(state.copy(topBarBlurStyle = AppearanceTopBarBlurStyle.entries[index]))", BLUR)
        self.assertEqual(1, BLUR.count("onChange("))

    def test_cancel_is_a_shared_footer_action_and_dismiss_does_not_save(self):
        self.assertIn("footer = {", BLUR)
        self.assertIn('AssistantPreviewButton("取消", { choosingBlur = false }', BLUR)
        self.assertIn('testTag("choice-sheet-cancel")', BLUR)
        self.assertIn("onDismiss = { choosingBlur = false }", BLUR)
        self.assertIn('listOf("高斯模糊", "渐进模糊")', SCREEN)
        self.assertIn("val blurSupported = isRuntimeShaderSupported()", SCREEN)
        self.assertIn("if (blurSupported)", SCREEN)
        self.assertIn("if (state.blurEnabled)", SCREEN)


if __name__ == "__main__":
    unittest.main()
