"""Guards the shared form wiring; Android tests exercise placeholder transitions."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / "app/src/main/kotlin/app/daiyu/android/ui"


class EmbeddedInputPlaceholderContractTest(unittest.TestCase):
    def test_settings_fields_use_body_sized_in_field_placeholders(self):
        source = (UI / "screens/settings/SettingsUiComponents.kt").read_text()
        field = source.split("internal fun SettingsTextField(", 1)[1].split("internal fun SettingsNote(", 1)[0]
        self.assertNotIn("labelContent", field)
        self.assertIn('if (value.isEmpty()) Text(hint.ifEmpty { label }, style = MiuixTheme.textStyles.body1,', field)
        self.assertIn('.semantics { contentDescription = label }', field)
        self.assertEqual(2, field.count('Box(Modifier.weight(1f)) { field() }'))
        self.assertEqual(2, field.count('if (password) visibilityButton()'))
        self.assertIn('minLines = if (multiline && !embedded) 4 else 1', field)
        self.assertIn('maxLines = if (multiline) 8 else 1', field)
        for retained in ('TextFieldValue(value)', 'key(password && !secretVisible)', 'Lifecycle.Event.ON_PAUSE',
                         'focusRequester.requestFocus()', 'InputDismissal.inProgress', 'PasswordVisualTransformation()',
                         'settings-secret-visibility:$label', 'fieldFocused = it.isFocused'):
            self.assertIn(retained, field)

    def test_eta_forms_do_not_keep_a_floating_label(self):
        field = (UI / "components/EtaFormTextField.kt").read_text()
        self.assertNotIn('label = {', field)
        self.assertIn('placeholder = { Text(hint, style = MiuixTheme.textStyles.body1) }', field)
        for state in ('focused', 'unfocused', 'disabled'):
            self.assertIn(f'{state}PlaceholderColor = colors.onSurfaceVariantSummary', field)
        self.assertIn('contentDescription = hint', field)

    def test_catalog_reuses_miuix_placeholder_mode(self):
        field = (UI / "screens/settings/CatalogModelEditorDialog.kt").read_text().split('private fun CatalogModelField(', 1)[1]
        self.assertIn('useLabelAsPlaceholder = true', field)
        self.assertNotIn('useLabelAsPlaceholder = false', field)
        self.assertIn('contentDescription = label', field)
        self.assertIn('keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction)', field)

    def test_active_subagent_fields_keep_complete_outlines_and_accessible_names(self):
        field = (UI / "components/subagent/ConversationAgentForm.kt").read_text().split('internal fun ConversationAgentField(', 1)[1]
        self.assertIn('if (value.isEmpty()) Text(label, style = textStyle', field)
        self.assertIn('textStyle = textStyle,', field)
        self.assertIn('contentDescription = label', field)
        self.assertEqual(1, field.count('drawRoundRect('))
        for removed in ('labelSize', 'halfLabelHeight', 'ClipOp.Difference', '.offset(y ='):
            self.assertNotIn(removed, field)
        self.assertIn('contentAlignment = Alignment.CenterStart', field)
        self.assertIn('TextAlign.Start', field)

    def test_assistant_fields_remove_titles_without_losing_the_action_slot(self):
        source = (UI / 'screens/assistants/AssistantPreviewComponents.kt').read_text()
        field = source.split('internal fun AssistantPreviewField(', 1)[1].split('internal fun AssistantPreviewSwitch(', 1)[0]
        self.assertIn('placeholder: String = label,', field)
        self.assertNotIn('Text(label,', field)
        self.assertNotIn('if (label.isNotBlank())', field)
        self.assertIn('if (labelAction != null) Row(', field)
        self.assertIn('labelAction()', field)
        self.assertIn('contentDescription = label', field)
        self.assertIn('if (value.isEmpty() && placeholder.isNotEmpty())', field)
        self.assertIn('fontSize = textStyle.fontSize, lineHeight = textStyle.lineHeight,', field)
        self.assertNotIn('value.isBlank()', field)

    def test_assistant_editor_uses_field_titles_not_the_old_example_hints(self):
        source = (UI / 'screens/assistants/AssistantEditScreen.kt').read_text()
        self.assertNotIn('placeholder = stringResource(R.string.assistant_description_placeholder)', source)
        self.assertNotIn('placeholder = stringResource(R.string.assistant_memory_placeholder)', source)
        for tag in ('name', 'description', 'prompt', 'memory'):
            self.assertIn(f'testTag("assistant-edit-{tag}")', source)
        self.assertIn('.isolateEditorScroll(scrollIsolation)', source)


    def test_remaining_ordinary_forms_use_placeholders_and_keep_accessible_names(self):
        fields = {
            "screens/settings/SkillManagementScreen.kt": ("https://github.com/owner/repo",),
            "components/SubAgentParallelLimitRow.kt": ("0 为不限，或输入正整数",),
            "screens/conversation/ChildTaskCommandDialog.kt": ("已绑定的子模型配置 ID", "本次指令"),
            "screens/chat/conversation/InlineMessageEdit.kt": ("编辑消息",),
        }
        for path, titles in fields.items():
            source = (UI / path).read_text()
            for title in titles:
                with self.subTest(path=path, title=title):
                    self.assertNotIn(f'label = {{ Text("{title}") }}', source)
                    self.assertNotIn(f'label = {{ androidx.compose.material3.Text("{title}") }}', source)
                    self.assertRegex(source, rf'placeholder = \{{ (?:androidx\.compose\.material3\.)?Text\("{title}"\) \}}')
                    self.assertIn(f'contentDescription = "{title}"', source)
        inline = (UI / "screens/chat/conversation/InlineMessageEdit.kt").read_text()
        self.assertIn('supportingText = { androidx.compose.material3.Text("保存后将重新生成，并删除后续内容及失效摘要") }', inline)
        child = (UI / "screens/conversation/ChildTaskCommandDialog.kt").read_text()
        for retained in ('child-command-execution-warning', 'this[ChildTaskCommandDialogAction] = confirm', 'enabled = valid && !busy && !consumed'):
            self.assertIn(retained, child)


    def test_approval_parameter_titles_do_not_hide_safety_constraints_after_input(self):
        constraints = {
            "PlatformActionApprovalHost.kt": (6, ("0–23（本地时间）", "0–59", "1–86400", "非百分比；须在设备实际范围内")),
            "GenerationApprovalHost.kt": (4, ("不改写", "原值；当前客户端不支持，非空将阻止确认", "原值；当前不支持", "0–32，仅轮询原任务，不重发 POST")),
            "ShizukuApprovalHost.kt": (3, ("0 或 2000（必选，无默认值）", "1–120000")),
        }
        for path, (count, hints) in constraints.items():
            source = (UI / path).read_text()
            with self.subTest(path=path):
                self.assertNotIn('label = { Text(', source)
                self.assertEqual(count, source.count('placeholder = { Text('))
                self.assertEqual(count, source.count('.semantics { contentDescription = '))
                for hint in hints:
                    self.assertIn(f'supportingText = {{ Text("{hint}") }}', source)


if __name__ == '__main__':
    unittest.main()
