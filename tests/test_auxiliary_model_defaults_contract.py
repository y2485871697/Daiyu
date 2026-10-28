from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / 'app/src/main/kotlin/app/daiyu/android'

class AuxiliaryModelDefaultsContract(unittest.TestCase):
    def test_title_uses_the_accepted_request_snapshot_not_a_global_lookup(self):
        controller = (MAIN / 'ChatController.kt').read_text()
        self.assertIn('coordinator.prepare(saved.id, run, requestProvider)', controller)
        coordinator = (MAIN / 'title/TitleModelCoordinator.kt').read_text()
        self.assertIn('val provider = if (selected == null) currentProvider', coordinator)
        self.assertNotIn('TitlePreparation(TitleOutcome.DISABLED)', coordinator)
        self.assertNotIn('freezeActive', coordinator)
        self.assertNotIn('loadModel', coordinator)

    def test_both_settings_show_current_model_when_custom_is_off(self):
        self.assertIn('if (settings.enabled) modelLabel ?: "选择模型" else "当前使用的模型"',
            (MAIN / 'title/TitleModelSettingsPanel.kt').read_text())
        self.assertIn('if (draft.configureSummary) modelLabel ?: "选择模型" else "当前使用的模型"',
            (MAIN / 'ui/screens/settings/CompressionSettingsForm.kt').read_text())

    def test_running_manual_summary_receives_the_original_run_model(self):
        controller = (MAIN / 'ChatController.kt').read_text()
        self.assertIn('token.summaryPreparation, requestProvider,', controller)
        self.assertIn('it.runner === running && it.initial.id == original.id', controller)
        self.assertIn('capacity.sendBudgetTokens, currentProvider)', controller)
        worker = (MAIN / 'context/ContextManagementWorker.kt').read_text()
        self.assertIn('else selection.binding == null', worker)
        self.assertIn('if (frozenCurrent != null) chatModel(frozenCurrent, windowTokens, usage)', worker)
        self.assertIn('else requireNotNull(profiles.freeze(checkNotNull(target), usage))', worker)

    def test_default_title_retains_the_configuration_revision_and_revocation_checks(self):
        store = (MAIN / 'title/TitleModelSettingsStore.kt').read_text()
        self.assertIn('if (current.revision != revision) unavailable()', store)
        self.assertIn('if (current.enabled) unavailable()', store)
        self.assertIn('else if (resolve(binding) != frozen) unavailable()', store)
        coordinator = (MAIN / 'title/TitleModelCoordinator.kt').read_text()
        self.assertIn('settings.whileCurrent(job.binding, job.provider, job.configurationRevision)', coordinator)
        self.assertIn('if (closed || job.revoked)', coordinator)

if __name__ == '__main__':
    unittest.main()
