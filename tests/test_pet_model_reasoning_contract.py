from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/kotlin/app/daiyu/android'
CORE = ROOT / 'core-domain/src/main/kotlin/app/daiyu/android/domain/pet'

class PetModelReasoningContract(unittest.TestCase):
    def test_pet_reuses_shared_protocols_and_does_not_reject_thinking(self):
        adapter = (APP / 'pet/AndroidPetModelPort.kt').read_text()
        self.assertIn('ModelFactory.create(settings)', adapter)
        self.assertIn('ReasoningRequestPolicy.freeze(', adapter)
        self.assertIn('ReasoningEffort.valueOf(frozenRequest.reasoningEffort.name)', adapter)
        self.assertIn('is ModelEvent.ReasoningDelta, is ModelEvent.ReasoningFinal -> Unit', adapter)
        self.assertIn('open(listOf(message), emptyList<ToolDefinition>())', adapter)
        self.assertIn('failure.userMessage', adapter)
        self.assertNotIn('failure.message', adapter)
        self.assertIn('contextWindowOverride = null', adapter)
        self.assertNotIn('Pet model does not yet support', adapter)

    def test_pet_button_keeps_model_arrow_and_shared_chat_picker(self):
        screen = (APP / 'ui/screens/settings/PetSettingsScreen.kt').read_text()
        self.assertLess(screen.index('testTag("pet-thinking-button")'), screen.index('testTag("pet-model-chevron")'))
        self.assertIn('ChatThinkingSpeedIcon(null,', screen)
        self.assertIn('SubAgentThinkingEffortPickerDialog(showThinking && enabled', screen)
        self.assertIn('mainChat = true', screen)
        self.assertIn('host.updateSettings(host.snapshot().pet.state.settings.copy(', screen)
        self.assertNotIn('reasoningPreference', screen)
        for label in ('上下文窗口', '上下文压缩', '下一次主请求生效'):
            self.assertNotIn(label, screen)

    def test_no_history_enters_prompt_or_grows_after_completion(self):
        prompt = (CORE / 'PetSpeech.kt').read_text().split('private fun jsonPetString')[0]
        self.assertNotIn('state.memories', prompt)
        self.assertNotIn('state.recentTasks', prompt)
        controller = (CORE / 'PetController.kt').read_text()
        self.assertNotIn('PetMemory(', controller)
        self.assertNotIn('PetTask(', controller)
        self.assertIn('reasoningEffort = state.settings.reasoningEffort', controller)
        self.assertIn('memories = emptyList(), recentTasks = emptyList()', controller)
        cupboard = (APP / 'pet/PetCupboardDialog.kt').read_text()
        for removed in ('发言手账', 'pet-cupboard-clear', 'state.memories', 'onClear'):
            self.assertNotIn(removed, cupboard)
