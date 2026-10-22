"""Source wiring contracts; the companion Compose regression source is not executed here."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'
UI = SRC / 'ui/components'


class SubAgentCollaborationContractTest(unittest.TestCase):
    def test_only_eligible_gpt_card_has_divider_and_speed(self):
        row = (UI / 'SubAgentProfileRow.kt').read_text()
        block = row.split('if (showGptSpeed) {', 1)[1].split('\n                    }', 1)[0]
        self.assertIn('HorizontalDivider(Modifier.testTag("subagent-gpt-divider-${profile.id}")', block)
        self.assertIn('SubAgentGptSpeedButton(', block)
        self.assertEqual(1, row.count('HorizontalDivider('))
        source = (SRC / 'delegation/SavedCatalogSubAgentModelSource.kt').read_text()
        self.assertIn('protocol == ProviderProtocol.RESPONSES && GptServiceTier.isGptSpeedModel(record.id)', source)

    def test_speed_cycles_inline_and_draft_remains_uncommitted(self):
        form = (UI / 'SubAgentProfileConfigDialog.kt').read_text()
        speed = form.split('if (canEditSpeed) SubAgentSettingRow("GPT 速度"', 1)[1].split('SubAgentSettingRow("职责"', 1)[0]
        self.assertIn('session.edit { profile ->', speed)
        self.assertIn('profile.gptSpeedForModel().next()', speed)
        self.assertIn('divider = true', speed)
        self.assertNotIn('choices =', speed)
        self.assertNotIn('Picker', speed)
        modes = (SRC / 'model/GptServiceTier.kt').read_text().split('enum class GptSpeedMode {', 1)[1].split(';', 1)[0]
        self.assertLess(modes.index('NORMAL'), modes.index('FAST'))
        self.assertLess(modes.index('FAST'), modes.index('ULTRA_FAST'))
        self.assertIn('"标准"', (UI / 'SubAgentThinkingSpeedAnimation.kt').read_text())

    def test_role_indicators_are_not_buttons(self):
        icon = (UI / 'SubAgentRoleIcon.kt').read_text()
        for role in ('implementation', 'review', 'image_generation', 'video_generation'):
            self.assertIn(f'"{role}" -> Icons.Rounded.', icon)
        for removed in ('clickable', 'onClick', 'IconButton', 'Role.Button'):
            self.assertNotIn(removed, icon)
        dialog = (UI / 'ConversationCollaborationDialog.kt').read_text()
        self.assertNotIn('已应用：', dialog)
        self.assertNotIn('onAuthorizeSavedModel', dialog)
        self.assertNotIn('subagent-authorize-', dialog)

    def test_role_and_tier_use_neutral_fields_not_thinking_fill(self):
        row = (UI / 'SubAgentSettingRow.kt').read_text()
        self.assertIn('if (thinking) colors.soft else colors.field', row)
        self.assertIn('.heightIn(min = 48.dp)', row)
        self.assertIn('fontSize = 14.sp, lineHeight = 22.sp', row)
        self.assertIn('if (divider) HorizontalDivider', row)

    def test_form_and_footer_use_available_space_not_fixed_footer_budget(self):
        form = (UI / 'SubAgentProfileConfigDialog.kt').read_text()
        self.assertIn('.weight(1f, fill = false).heightIn(max = 520.dp).testTag("subagent-config-body").verticalScroll', form)
        self.assertNotIn('profileDialogScrollableBody', form)
        self.assertIn('.height(IntrinsicSize.Min)', form)
        style = (UI / 'subagent/ConversationAgentStyle.kt').read_text()
        self.assertIn('defaultMinSize(minHeight = 48.dp)', style)
        speed = (UI / 'SubAgentGptSpeedButton.kt').read_text()
        clickable = speed.index('.combinedClickable(')
        padding = speed.index('.then(if (chatStyle) Modifier.padding(horizontal = 6.dp, vertical = 4.dp)')
        self.assertLess(clickable, padding)
        self.assertIn('minWidth = 48.dp, minHeight = 48.dp', speed)
        self.assertIn('Box(Modifier.size(48.dp).then', style)
        field = (UI / 'subagent/ConversationAgentForm.kt').read_text()
        self.assertIn('.heightIn(min = 48.dp).background(colors.field', field)

    def test_choice_content_has_bounded_scroll_without_column_scope_weight(self):
        form = (UI / 'SubAgentProfileConfigDialog.kt').read_text()
        choices = form.split('SubAgentChoiceWindow(title = when (field)', 1)[1]
        body = choices.split('options.forEach', 1)[0]
        self.assertIn('Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll', body)
        self.assertNotIn('.weight(', body)
        window = (UI / 'subagent/ConversationAgentChoice.kt').read_text()
        self.assertIn('content: @Composable () -> Unit', window)
        self.assertIn('Box(Modifier.padding(6.dp)) { content() }', window)

    def test_existing_model_picker_is_shared_with_identity_safe_display(self):
        picker = (UI / 'subagent/SubAgentModelPicker.kt').read_text()
        self.assertIn('ChatModelPicker(', picker)
        self.assertIn('ModelCatalogEntryUi(it.id, it.displayName, it.apiModelId)', picker)
        self.assertIn('onSelectOtherProvider = { provider, model -> select(provider, model) }', picker)
        self.assertNotIn('AlertDialog', picker)
        self.assertFalse((UI / 'subagent/ConversationSubAgentModels.kt').exists())
        shared = (SRC / 'ui/screens/chat/conversation/ChatModelPicker.kt').read_text()
        self.assertIn('ModelBrandIcon(model.apiModelId', shared)
        self.assertIn('it.apiModelId.contains(search, true)', shared)
        self.assertIn('model.id == state.current', shared)
        self.assertIn('onSelect(model.id)', shared)

    def test_obsolete_approval_backend_is_removed(self):
        binding = (SRC / 'delegation/SavedSubAgentRuntimeBindings.kt').read_text()
        for stale in ('approvals', 'authorizePrimaryModel', 'authorizeSavedModel', 'loadSettings'):
            self.assertNotIn(stale, binding)
        controller = (SRC / 'ChatController.kt').read_text()
        main = (SRC / 'MainActivity.kt').read_text()
        self.assertNotIn('bindChildSavedModel', controller)
        self.assertNotIn('childAuthorizationEpoch', main)
        for path in ('MainActivity.kt', 'ChatController.kt', 'ui/components/ConversationCollaborationDialog.kt'):
            source = (SRC / path).read_text()
            for stale in ('authorizePrimaryModel', 'authorizeSavedModel', 'onAuthorizeSavedModel', 'revokeChildSavedModel'):
                self.assertNotIn(stale, source)
        self.assertIn('val saved = ModelFactory.snapshot(resolveSavedModel(profile.providerId, profile.modelId))', binding)
        self.assertIn('require(saved.model == model.modelId)', binding)
        self.assertIn('profile.role == "review"', binding)

    def test_both_new_request_paths_freeze_and_check_without_mutating_old_definitions(self):
        controller = (SRC / 'ChatController.kt').read_text()
        self.assertEqual(2, controller.count('freezeSavedChildModels(key) { provider ->'))
        self.assertIn('models.isCurrent()', controller)
        self.assertIn('childPreparation.isCurrent()', controller)
        direct = controller.split('private fun directChildTaskPinned(', 1)[1].split('private fun pruneBackgroundRuns()', 1)[0]
        self.assertLess(direct.index('models.isCurrent()'), direct.index('database.send('))
        self.assertIn('Rejected("child_model_configuration_changed")', direct)
        prepared = controller.split('private fun sendPrepared(', 1)[1]
        self.assertLess(prepared.index('childPreparation.isCurrent()'), prepared.index('val mayCommit=synchronized(this)'))
        failure = prepared.split('childPreparation.isCurrent()', 1)[1].split('if (!runCatching { providers.isCurrentActive', 1)[0]
        self.assertIn('closeUnacceptedTools(tools)', failure)
        self.assertIn('return null', failure)
        helper = controller.split('private fun freezeSavedChildModels', 1)[1].split('internal fun savedSubAgentProviders', 1)[0]
        for check in ('ownerState(key)', 'snapshot(key) == config', 'providers.snapshot().revision == registryRevision', 'it == revision'):
            self.assertIn(check, helper)
        binding = (SRC / 'delegation/SavedSubAgentRuntimeBindings.kt').read_text()
        create = binding.split('ChildTaskDisplay(binding.profile.name', 1)[1].split('\n', 1)[0]
        self.assertIn('runModel(binding.child)', create)
        self.assertNotIn('resolveSavedModel', create)


if __name__ == '__main__':
    unittest.main()
