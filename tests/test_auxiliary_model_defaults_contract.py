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

    def test_auxiliary_vision_only_freezes_for_pictures_when_native_is_explicitly_off(self):
        controller = (MAIN / 'ChatController.kt').read_text()
        capture = controller.split('internal fun captureImageRoute(', 1)[1].split('internal fun independentConfigurationChanging', 1)[0]
        self.assertIn('main.imageInputSupported != false || !independentModels.visionSelection().enabled', capture)
        self.assertLess(capture.index('main.imageInputSupported != false'), capture.index('independentModels.freeze('))
        send = controller.split('val imageHistory =', 1)[1].split('var auxiliaryPort:', 1)[0]
        self.assertIn('actionRevision?.prefix ?: contextService.displayHistory(conversationId)', send)
        self.assertIn('provider, imageHistory + user)', send)
        self.assertIn('+ preparedContext.messages)', send)
        self.assertLess(controller.index('val preparedContext ='), controller.index('val imageHistory ='))
        self.assertIn('if (auxiliaryNeeded) independentModels.visionSelection()', send)
        self.assertIn('auxiliaryFrozen == null || independentModels.isCurrent(auxiliaryEpoch)', send)
        helper = (MAIN / 'model/auxiliary/AuxiliaryModelSelection.kt').read_text()
        self.assertIn('main.imageInputSupported == false && MessageHistory.expand(history)', helper)
        self.assertIn('row.blocks.any { it is MessageBlock.Image }', helper)
        self.assertIn('require(candidate.snapshot.imageInputSupported == true)', helper)

    def test_independent_picker_uses_selected_catalog_capability_and_updates_confirmed_label_first(self):
        source = (MAIN / 'ui/screens/settings/IndependentModelScreen.kt').read_text()
        self.assertIn('if (slot == IndependentModelSettings.Slot.VISION) choice.selection.record.settings.visionSupported', source)
        self.assertIn('else if (choice.key == selectedKey) record?.imageInput else null', source)
        self.assertIn('store.save(slot, settings, assistantId, true, images)', source)
        self.assertLess(source.index('record = saved'), source.index('source.remember(feature, choice)'))
        self.assertLess(source.index('label = choice.label'), source.index('source.remember(feature, choice)'))
        self.assertLess(source.index('selectedKey = choice.key'), source.index('source.remember(feature, choice)'))

    def test_saved_title_is_silent_but_refresh_and_failure_notices_remain(self):
        controller = (MAIN / 'ChatController.kt').read_text()
        self.assertNotIn('会话标题已保存', controller)
        self.assertIn('TitleOutcome.SAVED -> null', controller)
        self.assertIn('TitleOutcome.SAVED) titleRevision++', controller)
        self.assertIn('标题或用量保存未确认', controller)
        self.assertIn('独立标题配置不可用；聊天已保存', controller)
        activity = (MAIN / 'MainActivity.kt').read_text()
        self.assertIn('controller.consumeTitleNotice()?.let { toast(it) }', activity)

if __name__ == '__main__':
    unittest.main()
