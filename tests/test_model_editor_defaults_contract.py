"""Local model editor and default-capability wiring; rendering is tested on Android."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/kotlin/app/daiyu/android"


class ModelEditorDefaultsContractTest(unittest.TestCase):
    def test_editor_has_two_single_line_switches_without_reset_action(self):
        source = (MAIN / "ui/screens/settings/CatalogModelEditorDialog.kt").read_text()
        for removed in ("恢复自动", "catalog-model-auto", "capabilitySummary", "手动：", "自动：不支持或未知"):
            self.assertNotIn(removed, source)
        switches = source.split('testTag("catalog-model-capabilities")', 1)[1].split("if (!isNew)", 1)[0]
        self.assertNotIn("summary =", switches)
        self.assertIn('SettingsSwitchRow("支持推理", value.reasoning, enabled)', switches)
        self.assertIn('SettingsSwitchRow("支持视觉", value.vision, enabled)', switches)
        row = (MAIN / "ui/screens/settings/SettingsUiComponents.kt").read_text().split("internal fun SettingsRow(", 1)[1]
        self.assertIn("verticalAlignment = Alignment.CenterVertically", row.split("internal fun SettingsSwitchRow", 1)[0])

    def test_new_edits_start_enabled_and_old_edits_resolve_saved_defaults(self):
        source = (MAIN / "model/catalog/CatalogModelEdit.kt").read_text()
        self.assertIn("val reasoning: Boolean = true", source)
        self.assertIn("val vision: Boolean = true", source)
        self.assertIn("record.settings.reasoningSupported", source)
        self.assertIn("record.settings.visionSupported", source)

    def test_runtime_projection_uses_same_defaults_and_preserves_explicit_false(self):
        source = (MAIN / "model/catalog/CatalogModelParameters.kt").read_text()
        for capability in ("reasoning", "vision"):
            self.assertIn(f"val {capability}Supported: Boolean get() = {capability}Override ?: true", source)
            self.assertIn(f"{capability}Override = {capability}Override", source)
        self.assertIn('optionalBoolean("reasoning_override")', source)
        self.assertIn('optionalBoolean("vision_override")', source)
        main_run = (MAIN / "storage/ModelSettings.kt").read_text().split("internal fun loadForRun(", 1)[1].split("internal fun checkCatalogConfiguration", 1)[0]
        self.assertIn("reasoningOverride = selected?.settings?.reasoningOverride", main_run)
        self.assertIn("visionOverride = selected?.settings?.visionOverride", main_run)
        self.assertIn("defaultVisionSupported = true", source)
        self.assertIn("defaultVisionSupported = true", main_run)
        snapshot = (MAIN / "model/ProviderSnapshot.kt").read_text()
        self.assertIn("imageInputSupported = settings.visionOverride ?: settings.defaultVisionSupported ?: imageInputSupported", snapshot)

    def test_append_only_import_uses_shared_model_defaults_not_name_heuristics(self):
        source = (MAIN / "model/catalog/CatalogModelParameters.kt").read_text()
        self.assertIn("fun newModel(remote: RemoteModel)", source)
        store = (MAIN / "model/catalog/SavedModelCatalogStore.kt").read_text()
        self.assertIn("filterNot { it.id in existingIds }", store)
        self.assertIn("createModel(remote)", store)


if __name__ == "__main__":
    unittest.main()
