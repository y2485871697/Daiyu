"""Wiring checks for child compression; these do not replace Android execution."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/kotlin/app/daiyu/android'
class ChildAutoCompressionContractTest(unittest.TestCase):
    def test_child_projection_is_automatic_and_only_uses_its_model_window(self):
        source = (APP / 'context/ProductionCompressionSettings.kt').read_text()
        child = source.split('internal fun childSettings(', 1)[1].split('\n}', 1)[0]
        self.assertIn('automatic = true', child)
        self.assertIn('contextWindowTokens = snapshot.contextWindowOverride?.toLong()', child)
        self.assertNotIn('?:', child)
        self.assertIn('val automatic: Boolean = false', source)
    def test_production_child_uses_projection_and_preserves_no_unknown_capacity_guess(self):
        source = (APP / 'context/ProductionContextPreparation.kt').read_text()
        child = source.split('fun childRuntime(', 1)[1].split('private fun requireWorker', 1)[0]
        self.assertIn('snapshot().childSettings(snapshot)', child)
        self.assertIn('if (policy.contextTokens == null) return null', child)
        self.assertIn('CatalogSummaryExecutionPolicy.childConfiguration(snapshot,', child)
        self.assertIn('settings.summaryModel', child)
        self.assertIn('automatic = true', child)
        self.assertNotIn('saved.contextWindowTokens', child)
        self.assertNotIn('frozen ?: configuration()', child)
        self.assertIn('val configuration = CatalogSummaryExecutionPolicy.childConfiguration', child)
        self.assertIn('ProviderSummaryModelResolver { configuration }.resolve(configuration.binding, usage)', child)
    def test_custom_summary_wins_and_missing_custom_never_switches_models(self):
        source = (APP / 'context/CatalogSummaryExecutionPolicy.kt').read_text()
        child = source.split('fun childConfiguration(', 1)[1].split('/** Project chat-only', 1)[0]
        self.assertIn('if (binding == null) chatConfiguration(snapshot, window)', child)
        self.assertIn('else requireNotNull(resolve(binding))', child)
        self.assertNotIn('?: chatConfiguration', child)
    def test_current_child_snapshot_and_per_request_usage_continue_through_existing_runtime(self):
        source = (APP / 'ChatController.kt').read_text()
        self.assertIn('application, childInitial, childProvider, childLabel)', source)
        self.assertIn('childUsage = checkNotNull(runtimeStore).childUsage, contextRuntime = childContext', source)
        run = (APP / 'context/ProductionRunContext.kt').read_text()
        self.assertIn('beforeClaim = { requests.admit(request) }', run)
        self.assertIn('nextModel = {', run)
        self.assertIn('model.rebind', run)
        binding = (APP / 'delegation/SavedSubAgentRuntimeBindings.kt').read_text()
        self.assertIn('resolveSavedModel(profile.providerId, profile.modelId)', binding)
if __name__ == '__main__': unittest.main()
