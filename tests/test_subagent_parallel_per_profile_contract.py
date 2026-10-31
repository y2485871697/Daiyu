"""Source wiring checks for per-profile limits; JVM tests cover stored and runtime values."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/kotlin/app/daiyu/android'


class SubAgentParallelPerProfileContractTest(unittest.TestCase):
    def text(self, path):
        return (APP / path).read_text()

    def test_profile_owns_validated_and_serialized_limit(self):
        text = self.text('delegation/SubAgentProfile.kt')
        for value in ('val parallelLimit: Int? = null', 'parallelLimit == null || parallelLimit >= 0',
                      '.put("parallel_limit", parallelLimit ?: JSONObject.NULL)', 'parallelLimit(j)',
                      '!j.has("parallel_limit") || j.isNull("parallel_limit")', 'value is Int && value >= 0'):
            self.assertIn(value, text)

    def test_profile_value_precedes_legacy_read_fallback(self):
        text = self.text('delegation/ConversationSubAgentPreferences.kt')
        self.assertIn('fun parallelLimit(profileId: String, model: SubAgentParallelModel)', text)
        self.assertIn('profiles.singleOrNull { it.id == profileId }?.parallelLimit ?: parallelLimit(model)', text)
        self.assertIn('parallelLimits[model] ?: legacyParallelLimits[model.legacyKey()] ?: 1', text)

    def test_pool_identity_is_owner_and_profile_not_provider_model(self):
        text = self.text('delegation/ConversationSubAgentPreferences.kt')
        pool = text.split('fun poolKey(', 1)[1].split('/** Instances', 1)[0]
        self.assertIn('profileId: String', pool)
        self.assertIn('"subagent:v2:"', pool)
        self.assertIn('owner.value.toByteArray', pool)
        self.assertIn('profileId.toByteArray', pool)
        self.assertNotIn('model.providerId', pool)
        self.assertNotIn('model.apiModel', pool)
        decode = text.split('fun parallelLimitForPool(', 1)[1].split('fun poolKey(', 1)[0]
        self.assertIn('poolKey(owner, profileId) != key', decode)
        self.assertIn('val profile = profiles.singleOrNull { it.id == profileId } ?: return null', decode)
        self.assertIn('profile.parallelLimit ?: resolveApiModel(profile)', decode)

    def test_runtime_receives_profile_identity_and_limit(self):
        text = self.text('delegation/SavedSubAgentRuntimeBindings.kt')
        self.assertIn('frozen.poolKey(owner, binding.profile.id)', text)
        self.assertIn('frozen.parallelLimit(binding.profile.id, model)', text)
        self.assertNotIn('frozen.poolKey(owner, model)', text)
        controller = self.text('ChatController.kt')
        self.assertEqual(4, controller.count('childPoolLimit(') - 1)
        helper = controller.split('private fun childPoolLimit(', 1)[1].split('private fun freezeSavedChildModels(', 1)[0]
        self.assertIn('readSavedChildProviderModel(profile.providerId, profile.modelId).model', helper)

    def test_editing_one_profile_never_writes_shared_limit_map(self):
        text = self.text('ui/components/ConversationSubAgentEditor.kt')
        self.assertNotIn('parallelLimits =', text)
        self.assertIn('if (it.id == id) it.copy(parallelLimit = limit) else it', text)
        self.assertIn('old.parallelLimit(draft.id, parallelLimitChange.model)', text)
        self.assertIn('draft.copy(parallelLimit = parallelLimitChange?.value', text)
        self.assertIn('profile != expectedProfile || old.presetApplicationToken != expectedApplicationToken', text)
        self.assertIn('old.parallelLimit(id, binding) != expectedLimit', text)

    def test_new_profile_and_model_change_keep_independent_value(self):
        text = self.text('ui/components/ConversationSubAgentEditor.kt')
        self.assertIn('"子代理 $number", parallelLimit = 1)', text)
        self.assertIn('fun rememberedParallelLimit(profile: SubAgentProfile): Int? = profile.parallelLimit', text)
        self.assertIn('changeProfileModel(old, selection).copy(parallelLimit = limit)', text)
        self.assertNotIn('val limit = memory?.parallelLimit', text)
        defaults = self.text('delegation/ConversationSubAgentPreferences.kt')
        self.assertIn('SubAgentModelDefaults.confirmed(profile, previous, resolved, null)', defaults)

    def test_both_limit_inputs_drop_helper_copy_but_keep_zero_in_label(self):
        for path in ('ui/components/SubAgentProfileConfigDialog.kt', 'ui/components/SubAgentParallelLimitRow.kt'):
            text = self.text(path)
            self.assertNotIn('共用此上限', text)
            self.assertNotIn('调低不会取消正在执行的任务', text)
            self.assertIn('0 为不限', text)
        dialog = self.text('ui/components/SubAgentProfileConfigDialog.kt')
        self.assertIn('draft = draft.copy(parallelLimit = limit)', dialog)
        row = self.text('ui/components/SubAgentParallelLimitRow.kt')
        self.assertIn('ownerConfig.parallelLimit(profile.id,', row)


if __name__ == '__main__':
    unittest.main()
