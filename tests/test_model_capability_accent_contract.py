"""Source guards for model capability tags; rendered colors await Android validation."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SETTINGS = ROOT / "app/src/main/kotlin/app/daiyu/android/ui/screens/settings"


class ModelCapabilityAccentContractTest(unittest.TestCase):
    def setUp(self):
        self.source = (SETTINGS / "LocalCatalogModelsScreen.kt").read_text()
        self.tags = self.source.split("labels.forEach { label ->", 1)[1]

    def test_every_capability_tag_uses_the_primary_fill_and_matching_text(self):
        self.assertIn("color = MiuixTheme.colorScheme.onPrimary", self.tags)
        self.assertIn(".background(MiuixTheme.colorScheme.primary)", self.tags)
        for old_or_fixed_color in ("surfaceVariant", "onSurface", "Color(", ".copy(alpha"):
            self.assertNotIn(old_or_fixed_color, self.tags)

    def test_all_labels_keep_their_conditions_and_compact_noninteractive_layout(self):
        for fragment in ('"上下文未知"', '"支持视觉".takeIf { form?.vision == true }',
                         '"原生搜索".takeIf { form?.nativeSearch == true }',
                         'if (selected) DaiyuIcon(DaiyuIcons.Check, "当前模型")',
                         "horizontalArrangement = Arrangement.spacedBy(6.dp)",
                         "verticalArrangement = Arrangement.spacedBy(4.dp)"):
            self.assertIn(fragment, self.source)
        for fragment in ("style = MiuixTheme.textStyles.body2", "RoundedCornerShape(6.dp)",
                         ".padding(horizontal = 7.dp, vertical = 2.dp)"):
            self.assertIn(fragment, self.tags)
        self.assertNotIn(".clickable", self.tags)
        labels = self.source.split("val labels = listOfNotNull(", 1)[1].split("labels.forEach", 1)[0]
        for removed in ('"推理"', '"当前"', '"视觉"', '"搜索"', 'form?.reasoning', 'selected'):
            self.assertNotIn(removed, labels)

    def test_tag_tokens_match_connection_button_and_local_provider_accent(self):
        helper = (SETTINGS / "ProviderSettingsComponents.kt").read_text().split(
            "internal fun ProviderActionButton(", 1)[1]
        self.assertIn("color = MiuixTheme.colorScheme.primary", helper)
        self.assertIn("textColor = MiuixTheme.colorScheme.onPrimary", helper)
        theme = (SETTINGS / "ProviderSettingsTheme.kt").read_text()
        self.assertIn("val accent = resolveAppearanceActions(LocalAppearanceSettings.current, LocalAppearanceDark.current)", theme)
        self.assertIn("primary = accent.primary, onPrimary = accent.onPrimary", theme)


if __name__ == "__main__":
    unittest.main()
