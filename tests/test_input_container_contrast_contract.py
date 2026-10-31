"""Source wiring guards; pixel and theme assertions live in FilledInputColorTest."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / "app/src/main/kotlin/app/daiyu/android/ui"


class InputContainerContrastContractTest(unittest.TestCase):
    def test_filled_forms_reuse_the_existing_raised_surface_token(self):
        source = (UI / "components/DaiyuFormTextField.kt").read_text()
        helper = source.split("internal fun daiyuInputContainerColor()", 1)[1].split("/**", 1)[0]
        self.assertIn("return colors.surfaceContainerHigh", helper)
        self.assertNotIn("lerp(", helper)
        self.assertNotIn("Color(0x", helper)
        self.assertIn("val container = daiyuInputContainerColor()", source)
        for state in ("focused", "unfocused", "disabled"):
            self.assertIn(f"{state}ContainerColor = container", source)

    def test_regular_compact_and_embedded_settings_share_the_same_fill(self):
        source = (UI / "screens/settings/SettingsUiComponents.kt").read_text()
        field = source.split("internal fun SettingsTextField(", 1)[1].split("internal fun SettingsNote(", 1)[0]
        self.assertIn("val container = daiyuInputContainerColor()", field)
        self.assertEqual(2, field.count(".background(container)"))
        self.assertNotIn(".background(MiuixTheme.colorScheme.surfaceVariant)", field)
        self.assertIn("minLines = if (multiline && !embedded) 4 else 1", field)
        self.assertIn("maxLines = if (multiline) 8 else 1", field)
        self.assertIn(".heightIn(min = 48.dp)", field)

    def test_compact_dialogs_share_the_fill_without_changing_input_behaviour(self):
        source = (UI / "components/CompactDialogTextField.kt").read_text()
        self.assertIn("val container = daiyuInputContainerColor()", source)
        self.assertIn(".background(container)", source)
        self.assertNotIn(".background(colors.surfaceVariant)", source)
        for token in (".managedInput()", "heightIn(min = 48.dp)", "singleLine = true", "imeCursorBrush(colors.primary)"):
            self.assertIn(token, source)


if __name__ == "__main__":
    unittest.main()
