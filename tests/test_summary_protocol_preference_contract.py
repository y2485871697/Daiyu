import unittest
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1] / "app/src/main/kotlin/app/daiyu/android"

class SummaryProtocolPreferenceContract(unittest.TestCase):
    def test_ui_is_derived_from_the_saved_preference_and_uses_the_existing_save_owner(self):
        form = (ROOT / "ui/screens/settings/CompressionSettingsForm.kt").read_text()
        screen = (ROOT / "ui/screens/settings/CompressionSettingsScreen.kt").read_text()
        self.assertIn("protocols.indexOf(draft.summaryProtocol)", form)
        self.assertIn("onSave(draft.copy(summaryProtocol = protocols[index]))", form)
        self.assertNotIn("onSelect = {}", form)
        self.assertIn("summaryProtocol = settings.summaryProtocol", screen)
        self.assertIn("requested.compression()", screen)

    def test_busy_locks_the_choices_without_disposing_the_open_sheet(self):
        text = (ROOT / "ui/screens/settings/ProviderSettingsComponents.kt").read_text()
        self.assertIn("rememberUpdatedState(enabled && !busy)", text)
        self.assertIn("if (expanded && enabled) OptionSelectionSheet", text)
        self.assertIn("selected = index == selectedIndex, enabled = enabled && !busy", text)
        self.assertNotIn("DisposableEffect(lifecycle, tag, enabled, busy)", text)

    def test_automatic_main_and_custom_paths_receive_the_frozen_protocol(self):
        text = (ROOT / "context/ContextPreparationCoordinator.kt").read_text()
        self.assertIn("models.resolve(binding, summaryUsage, settings.summaryProtocol)", text)
        self.assertIn("summaryUsage, settings.summaryProtocol)", text)
        prep = (ROOT / "context/ProductionContextPreparation.kt").read_text()
        self.assertIn("chatConfiguration(snapshot, window, protocol)", prep)
        self.assertIn("provider.resolve(binding, usage, protocol)", prep)
        self.assertIn("settings.summaryModel, settings.summaryProtocol", prep)

    def test_manual_dialog_takes_the_saved_default_but_keeps_its_explicit_selection(self):
        text = (ROOT / "ui/screens/chat/context/ContextManagementPanel.kt").read_text()
        self.assertIn("state?.snapshot?.savedConfiguration?.summaryProtocol", text)
        self.assertIn("ContextManagementSelection(if (draft.customModel) binding else null, draft.protocol)", text)
        self.assertNotIn("ProductionCompressionSettingsStore", text)

    def test_summary_transformation_is_opt_in_and_reuses_the_existing_protocol_rules(self):
        text = (ROOT / "context/CatalogSummaryExecutionPolicy.kt").read_text()
        self.assertIn("if (protocol == null) this else", text)
        self.assertIn("ManualSummaryTargets.forSummary(provider.asModelSettings(), protocol)", text)
        resolver = (ROOT / "context/ProviderSummaryModel.kt").read_text()
        self.assertIn("(lookup(binding) ?: return null).withSummaryProtocol(protocol)", resolver)

if __name__ == "__main__": unittest.main()
