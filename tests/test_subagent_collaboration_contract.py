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
        self.assertIn('Modifier.padding(top = 8.dp, bottom = 4.dp)', block)
        self.assertIn('testTag("subagent-card-${profile.id}")', row)
        source = (SRC / 'delegation/SavedCatalogSubAgentModelSource.kt').read_text()
        self.assertIn('protocol == ProviderProtocol.RESPONSES && GptServiceTier.isGptSpeedModel(record.id)', source)

    def test_chat_card_compacts_model_content_without_shrinking_action_rows(self):
        row = (UI / 'SubAgentProfileRow.kt').read_text()
        chat = row.split('} else if (LocalConversationAgentStyle.current) {', 1)[1].split('\n        } else {', 1)[0]
        self.assertIn('Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp))', chat)
        self.assertEqual(2, chat.count('.heightIn(min = 48.dp)'))
        self.assertIn('modifier = Modifier.size(48.dp)', chat)
        model = chat.split('contentDescription = "${profile.name}模型"', 1)[1]
        self.assertIn('.padding(top = 0.dp, bottom = 12.dp)', model)
        self.assertLess(model.index('.combinedClickable('), model.index('.padding(top = 0.dp'))
        self.assertNotIn('.offset(', chat)
        self.assertNotIn('.padding(vertical = 7.dp)', chat)

    def test_chat_hint_and_add_share_a_compact_centered_row_without_stacked_padding(self):
        dialog = (UI / 'ConversationCollaborationDialog.kt').read_text()
        row = dialog.split('Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("subagent-collaboration-add-row")', 1)[1].split('addedDraft?.let', 1)[0]
        self.assertIn('verticalAlignment = Alignment.CenterVertically', row)
        self.assertIn('PlatformTextStyle(includeFontPadding = false)', row)
        self.assertIn('LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)', row)
        self.assertIn('Modifier.weight(1f).testTag("subagent-collaboration-hint")', row)
        self.assertIn('Modifier.size(48.dp).testTag("subagent-collaboration-add")', row)
        self.assertNotIn('padding(vertical = 7.dp)', dialog)
        self.assertNotIn('.offset(', row)
        self.assertIn('if (panelEnabled && panelEditor.enabled) SubAgentProfileDraftSession.open(panelEditor)', row)
        self.assertIn('.heightIn(min = 48.dp).testTag("subagent-collaboration-add-row")', dialog)
        self.assertIn('.padding(bottom = if (panelEditor != null) 0.dp else 8.dp)', dialog)
        self.assertIn('padding(top = if (panelEditor != null) 8.dp else 10.dp)', dialog)
        self.assertIn('ConversationAgentSheet(onDismiss, useDefaultHeight = true,', dialog)
        self.assertIn('Column(Modifier.fillMaxSize()', dialog)
        self.assertIn('Column(Modifier.fillMaxWidth().weight(1f).testTag("subagent-collaboration-body")', dialog)
        for old in ('360.dp', 'fill = emptyState', 'val emptyState', 'appWindowSize'):
            self.assertNotIn(old, dialog)
        body, footer = dialog.split('HorizontalDivider(Modifier.padding(top = if (panelEditor != null)', 1)
        self.assertIn('.verticalScroll(', body)
        self.assertIn('testTag("subagent-collaboration-footer")', footer)
        self.assertNotIn('verticalScroll(', footer)

    def test_android_height_and_footer_regression_covers_short_and_long_content(self):
        test = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/BottomSheetEdgeActivityTest.kt').read_text()
        test = test.split('fun testCollaborationSheetMatchesAssistantHeightAndPinsFooterAcrossContentStates()', 1)[1]
        test = test.split('fun testAssistantUsageChoiceAndModelEditorShareFloatingInsets()', 1)[0]
        for required in ('rawBounds(node("assistant-sheet").fetchSemanticsNode()).height',
                         'listOf(0, 1, 2, 3, 4, 5)', 'listOf(1f, .8f)', 'fontScale = 1.3f',
                         'expectedHeight, rawBounds(surface).height, 2f)',
                         '16.dp.toPx()', '"Scrolling never moves the footer"',
                         '"Group 19"', '"subagent-card-worker-7"', 'assertEquals(before, prefs.all)',
                         'assertEquals(0, fixture.opens.get())'):
            self.assertIn(required, test)

    def test_speed_cycles_inline_and_draft_remains_uncommitted(self):
        form = (UI / 'SubAgentProfileConfigDialog.kt').read_text()
        speed = form.split('if (canEditSpeed) SubAgentSettingRow("GPT 速度"', 1)[1].split('SubAgentSettingRow("职责"', 1)[0]
        self.assertIn('session.edit { profile ->', speed)
        self.assertIn('profile.gptSpeedForModel().next()', speed)
        self.assertNotIn('divider =', speed)
        self.assertNotIn('choices =', speed)
        self.assertNotIn('Picker', speed)
        modes = (SRC / 'model/GptServiceTier.kt').read_text().split('enum class GptSpeedMode {', 1)[1].split(';', 1)[0]
        self.assertLess(modes.index('NORMAL'), modes.index('FAST'))
        self.assertLess(modes.index('FAST'), modes.index('ULTRA_FAST'))
        self.assertIn('"标准"', (UI / 'SubAgentThinkingSpeedAnimation.kt').read_text())

    def test_role_indicators_are_not_buttons(self):
        icon = (UI / 'SubAgentRoleIcon.kt').read_text()
        for role, glyph in (('implementation', 'Build'), ('review', 'FactCheck'),
                            ('image_generation', 'Image'), ('video_generation', 'Videocam')):
            self.assertIn(f'"{role}" -> Icons.Rounded.{glyph}', icon)
        for removed in ('clickable', 'onClick', 'IconButton', 'Role.Button'):
            self.assertNotIn(removed, icon)
        dialog = (UI / 'ConversationCollaborationDialog.kt').read_text()
        self.assertNotIn('已应用：', dialog)
        self.assertNotIn('onAuthorizeSavedModel', dialog)
        self.assertNotIn('subagent-authorize-', dialog)

    def test_new_form_rows_are_compact_and_use_the_model_icon_accent(self):
        row = (UI / 'SubAgentSettingRow.kt').read_text()
        chat, legacy = row.split('        return\n    }', 1)
        self.assertIn('.heightIn(min = 48.dp)', chat)
        self.assertIn('Icon(icon, null, Modifier.size(20.dp), tint = colors.accent)', chat)
        self.assertIn('Text(label, Modifier.widthIn(min = 72.dp, max = 100.dp)', chat)
        self.assertIn('Column(Modifier.weight(1f), horizontalAlignment = Alignment.End)', chat)
        self.assertIn('textAlign = TextAlign.End', chat)
        self.assertIn('Icons.AutoMirrored.Rounded.KeyboardArrowRight', chat)
        self.assertIn('if (label != "模型" && !badge.isNullOrBlank()) Text(badge', chat)
        self.assertIn('badge: String? = null', row)
        self.assertIn('if (!badge.isNullOrBlank()) Surface', legacy)
        self.assertIn('trailing?.invoke()', chat)
        self.assertIn('enabled = enabled, role = Role.Button, onClick = onClick, onLongClick = onLongClick', chat)
        self.assertIn('indication = null', chat)
        self.assertIn('if (divider) HorizontalDivider', chat)
        for removed in ('background(', '.border(', 'daiyuIconColor(', 'val thinking', 'if (!speed)'):
            self.assertNotIn(removed, chat)
        self.assertIn('daiyuIconColor(icon)', legacy)

    def test_new_form_keeps_sheet_centered_title_and_transparent_floating_labels(self):
        form = (UI / 'subagent/ConversationAgentForm.kt').read_text()
        self.assertIn('ConversationAgentSheet(onDismiss, modifier = Modifier.heightIn(max = maximumHeight))', form)
        self.assertIn('ConversationAgentHandle()', form)
        self.assertIn('textAlign = TextAlign.Center', form)
        field = form.split('@Composable internal fun ConversationAgentField', 1)[1]
        self.assertIn('Box(Modifier.fillMaxWidth().padding(top = halfLabelHeight, bottom = 8.dp))', field)
        self.assertIn('.offset(y = -halfLabelHeight)', field)
        self.assertIn('.onSizeChanged { labelSize = it }', field)
        self.assertIn('clipOp = ClipOp.Difference', field)
        self.assertIn('LocalLayoutDirection.current == LayoutDirection.Rtl', field)
        self.assertIn('cursorBrush = imeCursorBrush(colors.accent)', field)
        self.assertIn('enabled = enabled, singleLine = true, keyboardOptions = keyboardOptions', field)
        self.assertNotIn('.background(', field)
        self.assertNotIn('Color.White', field)
        shared = (UI / 'SubAgentProfileConfigDialog.kt').read_text()
        self.assertIn('ConversationAgentSwitch(draft.enabled, usable)', shared)
        for label in ('模型', 'GPT 速度', '职责', '任务分工', '思考深度', '默认分辨率'):
            self.assertIn(f'SubAgentSettingRow("{label}"', shared)
        self.assertIn('choices = "role"', shared)
        self.assertIn('choices = "tier"', shared)
        self.assertIn('if (canEditSpeed)', shared)
        self.assertIn('if (draft.supportsTaskTier)', shared)

    def test_config_body_has_no_extra_dividers_and_enabled_uses_the_label_column(self):
        form = (UI / 'SubAgentProfileConfigDialog.kt').read_text()
        body = form.split('SubAgentConfigurationWindow(title =', 1)[1].split('SubAgentDraftDialogActions(ready', 1)[0]
        self.assertNotIn('divider =', body)
        self.assertNotIn('HorizontalDivider(', body)
        self.assertIn('.padding(start = 30.dp, top = 8.dp, bottom = 8.dp)', body)
        self.assertIn('Text("启用", Modifier.weight(1f)', body)
        self.assertIn('fontSize = 14.sp, lineHeight = 22.sp, color = conversationAgentColors().text', body)
        self.assertIn('ConversationAgentSwitch(draft.enabled, usable)', body)
        self.assertIn('active -> interact { session.edit { it.copy(enabled = active) } }', body)
        footer = form.split('private fun SubAgentDraftDialogActions', 1)[1]
        self.assertEqual(1, footer.count('HorizontalDivider('))

    def test_config_fields_center_editable_content_and_keep_only_the_outline(self):
        field = (UI / 'subagent/ConversationAgentForm.kt').read_text().split('@Composable internal fun ConversationAgentField', 1)[1]
        self.assertIn('decorationBox = { editor ->', field)
        self.assertIn('Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 10.dp)', field)
        self.assertIn('contentAlignment = Alignment.CenterStart', field)
        self.assertIn('Box(Modifier.testTag("subagent-field-editor:$label")) { editor() }', field)
        self.assertEqual(1, field.count('drawRoundRect('))
        self.assertIn('drawRoundRect(colors.line', field)
        self.assertNotIn('colors.field', field)
        self.assertNotIn('.background(', field)
        self.assertIn('clipOp = ClipOp.Difference', field)
        self.assertIn('LocalLayoutDirection.current == LayoutDirection.Rtl', field)

    def test_form_and_footer_use_available_space_not_fixed_footer_budget(self):
        form = (UI / 'SubAgentProfileConfigDialog.kt').read_text()
        self.assertIn('.weight(1f, fill = false).heightIn(max = 520.dp).testTag("subagent-config-body").verticalScroll', form)
        self.assertNotIn('profileDialogScrollableBody', form)
        self.assertIn('.height(IntrinsicSize.Min)', form)
        style = (UI / 'subagent/ConversationAgentStyle.kt').read_text()
        self.assertIn('defaultMinSize(minHeight = 48.dp)', style)
        self.assertIn('Box(Modifier.size(48.dp).then', style)
        field = (UI / 'subagent/ConversationAgentForm.kt').read_text()
        self.assertIn('.heightIn(min = 48.dp)', field)
        self.assertNotIn('drawRoundRect(colors.field', field)
        self.assertIn('clipOp = ClipOp.Difference', field)

    def test_chat_speed_is_a_compact_tag_without_shrinking_settings_controls(self):
        speed = (UI / 'SubAgentGptSpeedButton.kt').read_text()
        chat, settings = speed.split('Row(modifier = (if (chatStyle)', 1)[1].split('.semantics(mergeDescendants', 1)[0].split('else modifier', 1)
        self.assertIn('heightIn(min = 24.dp)', chat)
        self.assertNotIn('48.dp', chat)
        self.assertIn('clip(chipShape).background(chatColors.soft).border(1.dp, chatColors.line, chipShape)', chat)
        self.assertIn('SubAgentGptSpeedMinTouchSize', settings)
        self.assertIn('private val SubAgentGptSpeedMinTouchSize = 48.dp', speed)
        self.assertIn('fontSize = if (chatStyle) 10.sp', speed)
        self.assertIn('lineHeight = if (chatStyle) 16.sp', speed)
        clickable = speed.index('.combinedClickable(')
        padding = speed.index('.then(if (chatStyle) Modifier.padding(horizontal = 8.dp, vertical = 3.dp)')
        self.assertLess(clickable, padding)
        self.assertIn('editor?.cycleGptSpeed(profile.id, profile.providerId, profile.modelId, expected = profile)', speed)
        self.assertIn('if (!eligible) return', speed)

    def test_passive_role_uses_a_small_glyph_not_a_button_sized_slot(self):
        role = (UI / 'SubAgentRoleIcon.kt').read_text()
        self.assertIn('Box(Modifier.size(24.dp)', role)
        self.assertIn('Modifier.size(16.dp).testTag("subagent-role-glyph-${profile.id}")', role)
        self.assertNotIn('size(40.dp)', role)
        self.assertNotIn('size(22.dp)', role)

    def test_choice_content_has_bounded_scroll_without_column_scope_weight(self):
        form = (UI / 'SubAgentProfileConfigDialog.kt').read_text()
        choices = form.split('SubAgentChoiceWindow(title = when (field)', 1)[1]
        body = choices.split('options.forEach', 1)[0]
        self.assertIn('Column(Modifier.fillMaxWidth())', body)
        self.assertNotIn('.verticalScroll(', body)
        self.assertNotIn('.weight(', body)
        window = (UI / 'subagent/ConversationAgentChoice.kt').read_text()
        self.assertIn('content: @Composable () -> Unit', window)
        self.assertIn('OptionSelectionSheet(title, onDismiss) { content() }', window)
        sheet = (UI / 'OptionSelectionSheet.kt').read_text()
        self.assertIn('.verticalScroll(rememberScrollState()).selectableGroup()', sheet)
        self.assertIn('ChatSelectionSheet(title = title', sheet)

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
        create = binding.split('val original = ChildModelDefinition(', 1)[1]
        self.assertIn('runModel(binding.child)', create)
        self.assertIn('child_profile_removed_or_role_changed', create)
        self.assertNotIn('else original', create)
        self.assertLess(create.index('val selected = latestModel(binding.profile.id)'), create.index('check(latestConfig() == next)'))
        self.assertNotIn('resolveSavedModel', create)


if __name__ == '__main__':
    unittest.main()
