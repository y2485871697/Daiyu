"""Production wiring for concrete reasoning defaults; runtime semantics have JVM tests."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'

class ReasoningDefaultsContractTest(unittest.TestCase):
    def test_display_and_child_freeze_share_concrete_text_resolution(self):
        ui = (SRC / 'ui/components/subagent/SubAgentThinkingPreference.kt').read_text()
        runtime = (SRC / 'delegation/SavedSubAgentRuntimeBindings.kt').read_text()
        self.assertIn('ReasoningEffort.textPreference(profile.reasoning, config.effectiveReasoningEffort, config.model)', ui)
        self.assertIn('ReasoningEffort.textPreference(profile.reasoning, model.defaultReasoning, model.modelId)', runtime)
        self.assertNotIn('profile.reasoning ?: ReasoningEffort.DEFAULT', ui + runtime)

    def test_new_chat_inherits_before_replacing_the_editor(self):
        select = (SRC / 'MainActivity.kt').read_text().split('internal fun select(conversation: Conversation?)', 1)[1]
        self.assertLess(select.index('reasoningPreference.inheritDraft(selected?.id?.value)'), select.index('selected = conversation'))
        self.assertIn('selectionReady && conversation == null', select)

    def test_branch_and_regeneration_copy_before_publishing_or_sending(self):
        actions = (SRC / 'ChatController.kt').read_text().split('internal fun messageAction(action:', 1)[1].split('internal fun skillManagement', 1)[0]
        self.assertIn('branchReasoning = reasoningPreference.snapshot(target.conversationId.value)', actions)
        self.assertLess(actions.index('reasoningPreference.retainAccepted(copy.id.value'), actions.index('MessageActionResult.Branch(copy)'))
        self.assertIn('sendPrepared(token, owner, text, actionRevision = revision)', actions)
        self.assertNotIn('retainAccepted(prepared.conversation.id.value', actions)
        self.assertIn('reasoningPreference.retainAccepted(failure.locator.conversationId.value', actions)

    def test_model_switch_restores_target_only_after_the_model_change(self):
        controller = (SRC / 'ChatController.kt').read_text()
        switch = controller.split('private fun switchChatReasoningModel(', 1)[1].split('internal fun selectProviderCatalogModel', 1)[0]
        self.assertIn('previousProvider == nextProvider && previousModel == nextModel', switch)
        self.assertIn('reasoningPreference.rememberedModel(nextProvider, nextModel)', switch)
        self.assertLess(switch.rindex('changeModel()'), switch.index('reasoningPreference.set(owner, next)'))
        self.assertLess(switch.index('reasoningPreference.set(owner, next)'), switch.index('reasoningPreference.rememberModel(previousProvider, previousModel, previous)'))
        self.assertIn('providers.catalogSnapshot(previous)', controller)

    def test_video_off_is_applied_each_round_before_final_payload_validation(self):
        request = (SRC / 'model/ResponsesModel.kt').read_text()
        add = request.index('if (videoPlan != null) app.daiyu.android.model.videos.VideoRequestPolicy.disableReasoning(request)')
        self.assertLess(add, request.index('ReasoningRequestPolicy.validate(request'))
        self.assertLess(add, request.index('videoPlan?.validateRequest(settings, request, input)'))
        guard = (SRC / 'ChatController.kt').read_text().split('if ((videoAttempt != null || physicalVideoHistory.inputs.isNotEmpty())', 1)[1].split('var videoPrepared', 1)[0]
        self.assertIn('ReasoningEffort.OFF', guard)
        self.assertNotIn('ReasoningEffort.DEFAULT', guard)

    def test_no_implicit_fallback_to_provider_default_for_native_off(self):
        policy = (SRC / 'model/ReasoningRequestPolicy.kt').read_text()
        self.assertIn('RikkaHubReasoning.chat(body,', policy)
        self.assertIn('RikkaHubReasoning.gemini(body,', policy)
        mapping = (SRC / 'model/RikkaHubReasoning.kt').read_text()
        self.assertIn('val wire = if (enabled) effort.wireValue else "none"', mapping)
        self.assertIn('thinking.put("thinkingBudget", 0)', mapping)
        self.assertIn('require(!hasReasoning(body))', policy)
        options = (SRC / 'model/ProviderRequestOptions.kt').read_text()
        self.assertIn('if (config.has("thinkingConfig")) ReasoningRequestPolicy.validate(body, protocol)', options)

    def test_mandatory_model_efforts_are_shared_by_main_display_picker_and_freeze(self):
        main = (SRC / 'MainActivity.kt').read_text()
        controller = (SRC / 'ChatController.kt').read_text()
        policy = (SRC / 'model/ReasoningRequestPolicy.kt').read_text()
        self.assertIn('ReasoningEffort.textChoices(confirmedReasoningModel)', main)
        self.assertIn('model = confirmedReasoningModel).displayName', main)
        self.assertIn('ReasoningRequestPolicy.freeze(provider, reasoningIntent)', controller)
        self.assertEqual(2, main.count('ReasoningRequestPolicy.requestModel(it.model, it.requestOptions)'))
        self.assertIn('reasoningPreference.retainAccepted(conversationId.value, reasoningIntent)', controller)
        self.assertIn('ReasoningEffort.textPreference(requested, model = model)', policy)
        self.assertIn('ReasoningEffort.textChoices(config.model)', (SRC / 'ui/components/subagent/SubAgentThinkingPreference.kt').read_text())
        for name in ('ResponsesModel.kt', 'NativeCompletionModel.kt'):
            self.assertIn('settings.reasoningOverride, request.opt("model") as? String)', (SRC / 'model' / name).read_text())

if __name__ == '__main__':
    unittest.main()
