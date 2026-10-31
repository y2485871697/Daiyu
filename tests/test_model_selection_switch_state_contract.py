"""Model selection and explicit switch requests have separate production entry points."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / 'app/src/main/kotlin/app/daiyu/android'
UI = MAIN / 'ui/screens/settings'


class ModelSelectionSwitchStateContract(unittest.TestCase):
    def test_compression_model_selection_keeps_both_switches(self):
        screen = (UI / 'CompressionSettingsScreen.kt').read_text()
        self.assertIn('configureSummary = draft.configureSummary || enableSummarySelection', screen)
        self.assertNotIn('configureSummary = true).compression()', screen)
        choose = screen.split('onChooseModel = {', 1)[1].split('onEnableSummary = {', 1)
        self.assertIn('enableSummarySelection = false', choose[0])
        self.assertIn('enableSummarySelection = true', choose[1])
        form = (UI / 'CompressionSettingsForm.kt').read_text()
        self.assertIn('if (it && !hasSummarySelection) onEnableSummary()', form)
        self.assertIn('onSave(draft.copy(automatic = it))', form)

    def test_disabled_compression_selection_retains_the_confirmed_binding(self):
        screen = (UI / 'CompressionSettingsScreen.kt').read_text()
        saved = screen.split('is CompressionSettingsEditor.Outcome.Saved -> {', 1)[1].split('is CompressionSettingsEditor.Outcome.Failed', 1)[0]
        self.assertIn('(outcome.snapshot.settings.summaryModel ?: selectedBinding)', saved)
        self.assertIn('submit(it, save = true, selectedBinding = selectedBinding)', screen)
        self.assertIn('hasSummarySelection = retainedSummaryBinding != null', screen)

    def test_title_separates_choose_from_explicit_enable_and_persists_the_intent(self):
        panel = (MAIN / 'title/TitleModelSettingsPanel.kt').read_text()
        self.assertIn('if (requested) onEnableModel() else onDisable()', panel)
        screen = (MAIN / 'title/TitleModelSettingsScreen.kt').read_text()
        self.assertIn('selectionEnabled = settings.enabled; picker = true', screen)
        self.assertIn('selectionEnabled = true; picker = true', screen)
        self.assertIn('enabled = selectionEnabled', screen)
        editor = (MAIN / 'title/TitleModelSettingsEditor.kt').read_text()
        self.assertIn('it.save(profile(), enabled = enabled,', editor)

    def test_vision_preserves_switch_state_without_changing_pet_selection(self):
        screen = (UI / 'IndependentModelScreen.kt').read_text()
        self.assertIn('onChoose = { if (!busy) { selectionEnabled = record?.enabled == true; picker = true } }', screen)
        self.assertIn('if (it) { selectionEnabled = true; picker = true } else disable()', screen)
        self.assertIn('val enabled = slot == IndependentModelSettings.Slot.PET || selectionEnabled', screen)
        self.assertIn('store.save(slot, settings, assistantId, enabled, images)', screen)
        self.assertLess(screen.index('val enabled = slot'), screen.index('source.withModel(choice)'))

    def test_dismissal_does_not_save_or_enable(self):
        for path, callback in [
            (UI / 'CompressionSettingsScreen.kt', 'onDismiss = { choosingModel = false }'),
            (UI / 'IndependentModelScreen.kt', 'onDismiss = { picker = false }'),
            (MAIN / 'title/TitleModelSettingsScreen.kt', 'onDismiss = { picker = false }'),
        ]:
            self.assertIn(callback, path.read_text())


if __name__ == '__main__':
    unittest.main()
