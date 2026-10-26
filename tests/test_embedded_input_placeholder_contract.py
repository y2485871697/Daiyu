"""Only filled, internally stacked field titles become placeholders."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / "app/src/main/kotlin/app/daiyu/android/ui"


class EmbeddedInputPlaceholderContractTest(unittest.TestCase):
    def test_settings_only_compact_internal_titles_become_placeholders(self):
        source = (UI / "screens/settings/SettingsUiComponents.kt").read_text()
        field = source.split("internal fun SettingsTextField(", 1)[1].split("internal fun SettingsNote(", 1)[0]
        self.assertIn('val placeholder = hint.ifEmpty { if (compact || embedded) label else "" }', field)
        self.assertIn('if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, style = MiuixTheme.textStyles.body1,', field)
        compact, regular = field.split('    if (compact) {', 1)[1].split('    } else {', 1)
        self.assertNotIn('Text(label,', compact)
        self.assertIn('Column(Modifier.fillMaxWidth().padding(16.dp)', regular)
        self.assertIn('Text(label, style = MiuixTheme.textStyles.body2,', regular)
        self.assertEqual(2, field.count('if (password) visibilityButton()'))
        for retained in ('TextFieldValue(value)', 'key(password && !secretVisible)', 'Lifecycle.Event.ON_PAUSE',
                         'focusRequester.requestFocus()', 'InputDismissal.inProgress', 'PasswordVisualTransformation()',
                         'settings-secret-visibility:$label', 'fieldFocused = it.isFocused',
                         'minLines = if (multiline && !embedded) 4 else 1', 'maxLines = if (multiline) 8 else 1'):
            self.assertIn(retained, field)

    def test_eta_outlined_floating_labels_are_not_converted(self):
        field = (UI / "components/EtaFormTextField.kt").read_text()
        self.assertIn('label = { Text(hint) }', field)
        self.assertNotIn('placeholder = {', field)
        for state in ('focused', 'unfocused', 'disabled'):
            self.assertIn(f'{state}LabelColor', field)
        self.assertIn('contentDescription = hint', field)

    def test_catalog_internal_miuix_titles_use_placeholder_mode(self):
        field = (UI / "screens/settings/CatalogModelEditorDialog.kt").read_text().split('private fun CatalogModelField(', 1)[1]
        self.assertIn('useLabelAsPlaceholder = true', field)
        self.assertIn('contentDescription = label', field)
        self.assertIn('keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction)', field)

    def test_subagent_notched_floating_labels_are_preserved(self):
        field = (UI / "components/subagent/ConversationAgentForm.kt").read_text().split('internal fun ConversationAgentField(', 1)[1]
        for retained in ('halfLabelHeight', 'labelSize', 'ClipOp.Difference', '.offset(y = -halfLabelHeight)',
                         'LocalLayoutDirection.current == LayoutDirection.Rtl', 'cursorBrush = imeCursorBrush(colors.accent)'):
            self.assertIn(retained, field)
        self.assertNotIn('if (value.isEmpty()) Text(label,', field)

    def test_assistant_external_titles_and_original_action_condition_are_preserved(self):
        source = (UI / 'screens/assistants/AssistantPreviewComponents.kt').read_text()
        field = source.split('internal fun AssistantPreviewField(', 1)[1].split('internal fun AssistantPreviewSwitch(', 1)[0]
        for retained in ('placeholder: String = "",', 'if (label.isNotBlank()) Row(', 'Text(label,',
                         'fontSize = (13f * s).sp, lineHeight = (20f * s).sp', 'labelAction?.invoke()',
                         'if (value.isEmpty() && placeholder.isNotEmpty())'):
            self.assertIn(retained, field)
        self.assertNotIn('placeholder: String = label,', field)

    def test_assistant_editor_keeps_original_example_placeholders(self):
        source = (UI / 'screens/assistants/AssistantEditScreen.kt').read_text()
        self.assertIn('placeholder = stringResource(R.string.assistant_description_placeholder)', source)
        self.assertIn('placeholder = stringResource(R.string.assistant_memory_placeholder)', source)
        self.assertIn('.isolateEditorScroll(scrollIsolation)', source)

    def test_other_material_outlined_forms_keep_original_labels(self):
        fields = {
            'screens/settings/SkillManagementScreen.kt': ('https://github.com/owner/repo',),
            'components/SubAgentParallelLimitRow.kt': ('0 为不限，或输入正整数',),
            'screens/conversation/ChildTaskCommandDialog.kt': ('已绑定的子模型配置 ID', '本次指令'),
            'screens/chat/conversation/InlineMessageEdit.kt': ('编辑消息',),
        }
        for path, titles in fields.items():
            source = (UI / path).read_text()
            for title in titles:
                with self.subTest(path=path, title=title):
                    self.assertRegex(source, r'label = \{ (?:androidx\.compose\.material3\.)?Text\("' + re.escape(title) + r'"\) \}')
            self.assertNotIn('placeholder = {', source)
        inline = (UI / 'screens/chat/conversation/InlineMessageEdit.kt').read_text()
        self.assertIn('保存后将重新生成，并删除后续内容及失效摘要', inline)
        child = (UI / 'screens/conversation/ChildTaskCommandDialog.kt').read_text()
        for retained in ('child-command-execution-warning', 'this[ChildTaskCommandDialogAction] = confirm', 'enabled = valid && !busy && !consumed'):
            self.assertIn(retained, child)

    def test_approval_outlined_labels_keep_their_original_constraints(self):
        constraints = {
            'PlatformActionApprovalHost.kt': (6, ('小时 0–23（本地时间）', '分钟 0–59', '计时秒数 1–86400', '绝对音量级数（非百分比；须在设备实际范围内）')),
            'GenerationApprovalHost.kt': (4, ('原始 prompt（不改写）', 'size（原值；当前客户端不支持，非空将阻止确认）', '视频 duration（原值；当前不支持）', 'maxPolls：0–32，仅轮询原任务，不重发 POST')),
            'ShizukuApprovalHost.kt': (3, ('精确 UID：0 或 2000（必选，无默认值）', '本次精确 shell 命令', '超时毫秒（1–120000）')),
        }
        for path, (count, labels) in constraints.items():
            source = (UI / path).read_text()
            with self.subTest(path=path):
                self.assertEqual(count, source.count('label = { Text('))
                self.assertNotIn('placeholder = { Text(', source)
                for label in labels:
                    self.assertIn(f'label = {{ Text("{label}") }}', source)


if __name__ == '__main__':
    unittest.main()
