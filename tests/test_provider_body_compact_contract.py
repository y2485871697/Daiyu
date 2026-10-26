"""Source guards for the inline JSON editor; Android geometry is tested separately."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / "app/src/main/kotlin/app/daiyu/android/ui/screens/settings"


class ProviderBodyCompactContractTest(unittest.TestCase):
    def test_embedded_json_starts_at_one_line_without_changing_regular_editors(self):
        field = (UI / "SettingsUiComponents.kt").read_text().split("internal fun SettingsTextField(", 1)[1].split("internal fun SettingsNote(", 1)[0]
        self.assertIn("minLines = if (multiline && !embedded) 4 else 1", field)
        self.assertIn("singleLine = !multiline", field)
        self.assertIn("maxLines = if (multiline) 8 else 1", field)
        self.assertIn(".heightIn(min = 48.dp)", field)
        self.assertNotIn("requiredHeight(", field)

    def test_json_keeps_multiline_and_uses_the_embedded_field(self):
        source = (UI / "ProviderInlineEditors.kt").read_text()
        value = source.split('SettingsTextField(if (headers) "值" else "值（JSON）"', 1)[1].split("IconButton(", 1)[0]
        self.assertIn("multiline = !headers", value)
        self.assertIn("embedded = true", value)
        self.assertIn("it.copy(value = value)", value)
        self.assertNotIn("take(", value)
        self.assertNotIn("trim(", value)


if __name__ == "__main__":
    unittest.main()
