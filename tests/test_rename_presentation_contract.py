"""Scoped rename sheet, settings presentation and precise cloud smoke contracts."""
from pathlib import Path
import json
import os
import subprocess
import sys
import tempfile
import unittest

from test_record_tool_tests_scope import recorder

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/kotlin/app/daiyu/android'


def source(relative):
    return (APP / relative).read_text()


class RenamePresentationContractTest(unittest.TestCase):
    def test_workspace_and_controlled_rename_share_one_sheet(self):
        shared = source('ui/components/WorkspaceNameDialog.kt')
        self.assertIn('var value by remember(initial)', shared)
        self.assertIn('NameInputSheet(title, value, hint, confirmText', shared)
        self.assertEqual(1, shared.count('ChatSelectionSheet('))
        for token in ('showCloseButton = false', 'dismissEnabled = enabled()', 'useLabelAsPlaceholder = true',
                      'secondary = true', 'primary = true', 'if (enabled() && confirmEnabled) onConfirm()',
                      'onValueChange = { if (enabled()) onValueChange(it) }'):
            self.assertIn(token, shared)
        self.assertEqual(2, shared.count('Modifier.weight(1f).heightIn(min = 48.dp)'))
        controlled = source('ui/components/SubAgentPresetNameDialog.kt')
        self.assertIn('NameInputSheet(title, name, fieldHint, "保存"', controlled)
        self.assertIn('onNameChange(it.take(80))', controlled)
        self.assertIn('onConfirm = onSave, onDismiss = onDismiss, confirmEnabled = saveEnabled', controlled)
        self.assertNotIn('WindowDialog', controlled)
        self.assertNotIn('renameStyle', controlled)

    def test_only_conversation_rename_opts_in_and_one_shot_guard_is_retained(self):
        activity = source('MainActivity.kt')
        rename = activity.split('prompt = InputPrompt("重命名", current.title,', 1)[1].split('\n        }\n    }', 1)[0]
        for token in ('nameStyle = true', 'val latest = retainedConversation(id)', 'controller.database.rename(latest.id, value)',
                      'selected = retainedConversation(id)', 'updatePane(); refresh()'):
            self.assertIn(token, rename)
        self.assertEqual(1, activity.count('nameStyle = true'))
        self.assertIn('nameStyle = current.nameStyle', activity)
        guard = activity.split('private fun confirmInputPrompt(', 1)[1].split('private fun confirmPrompt(', 1)[0]
        self.assertIn('activityUsable() && prompt === expected', guard)
        self.assertIn('prompt = null; expected.action(value)', guard)
        dialog = source('ui/components/DaiyuDialogs.kt')
        self.assertIn('if (nameStyle)', dialog)
        self.assertIn('WorkspaceNameDialog(title, initial, hint, confirmText, onConfirm, onDismiss)', dialog)

    def test_group_and_agent_names_keep_persistence_and_share_creation_style(self):
        settings = source('ui/SubAgentSettingsScreen.kt')
        self.assertIn('title = if (add) "添加子代理组" else "重命名", name = name', settings)
        self.assertIn('title = "重命名", fieldHint = "名称", name = name', settings)
        self.assertIn('repository.renamePreset(requireNotNull(rename).id, name.trim())', settings)
        self.assertIn('editor.updateProfile(profile.id) { it.copy(name = name.trim()) }', settings)
        self.assertIn('is ConversationSubAgentPreferences.WriteResult.Saved) rename = null', settings)

    def test_role_glyphs_and_all_setting_icons_reuse_collaboration_accent(self):
        roles = source('ui/components/SubAgentRoleIcon.kt')
        for role, glyph in (('implementation', 'Build'), ('review', 'FactCheck'), ('image_generation', 'Image'), ('video_generation', 'Videocam')):
            self.assertIn(f'"{role}" -> Icons.Rounded.{glyph}', roles)
        self.assertIn('tint = conversationAgentColors().accent', roles)
        self.assertIn('slotSize: Dp = 24.dp', roles)
        detail = source('ui/SubAgentSettingsScreen.kt').split('private fun SubAgentPresetDetail(', 1)[1]
        self.assertIn('SubAgentRoleIcon(profile, slotSize = 22.dp)', detail)
        self.assertNotIn('Icon(when (profile.role)', detail)
        self.assertIn('Icon(Icons.Rounded.DeleteOutline, null, tint = accent)', detail)
        self.assertIn('val accent = app.daiyu.android.ui.components.subagent.conversationAgentColors().accent', detail)
        self.assertIn('"${profile.name}更多操作", tint = accent', detail)
        row = source('ui/components/SubAgentSettingRow.kt')
        self.assertNotIn('daiyuIconColor', row)
        self.assertIn('val accent = conversationAgentColors().accent', row)
        self.assertEqual(2, row.count('tint = accent.copy(alpha = alpha)'))
        speed = source('ui/components/SubAgentGptSpeedButton.kt')
        self.assertIn('tint = chatColors.accent.copy(alpha = if (usable) 1f else .38f)', speed)

    def test_model_provider_and_speed_use_separate_vertical_space(self):
        profile = source('ui/components/SubAgentProfileRow.kt').split('if (settings)', 1)[1].split('} else if', 1)[0]
        self.assertIn('badge = config?.providerName, stackedValue = true', profile)
        self.assertIn('SubAgentGptSpeedButton(profile, presentationProviders, usable, settings = true)', profile)
        self.assertNotIn('trailing =', profile.split('Box(Modifier.fillMaxWidth())', 1)[0])
        self.assertLess(profile.index('SubAgentGptSpeedButton('), profile.index('SubAgentSettingRow("职责"'))
        self.assertIn('if (currentUsable) { modelPicker = true }', profile)
        self.assertIn('if (currentUsable && canThink) { thinkingPicker = true }', profile)
        row = source('ui/components/SubAgentSettingRow.kt')
        block = row.split('if (stackedValue) {', 1)[1].split('\n        } else {', 1)[0]
        self.assertIn('Column(Modifier.weight(1f)', block)
        self.assertIn('testTag("subagent-setting-model-name")', block)
        self.assertIn('testTag("subagent-setting-model-provider")', block)
        self.assertEqual(2, block.count('maxLines = 1'))
        self.assertLess(block.index('Text(value'), block.index('Text(badge'))
        self.assertIn('Arrangement.spacedBy(8.dp, Alignment.End)', row)
        self.assertIn('if (stackedValue) Modifier else Modifier.widthIn', row)

    def test_settings_speed_row_reuses_binding_and_confirmed_save_without_changing_chat_tag(self):
        speed = source('ui/components/SubAgentGptSpeedButton.kt')
        self.assertIn('settings: Boolean = false', speed)
        self.assertIn('providers.singleOrNull', speed)
        self.assertIn('provider?.models?.singleOrNull', speed)
        self.assertIn('model?.takeIf { supportsGptSpeedBinding(provider, it) }', speed)
        self.assertIn('profile.gptSpeedForModel(fallback = boundModel.defaultGptSpeed)', speed)
        block = speed.split('if (settings) {', 1)[1].split('val normalColor', 1)[0]
        self.assertIn('SubAgentSettingRow("GPT 速度", subAgentGptSpeedDisplayName(mode), Icons.Rounded.Speed', block)
        self.assertIn('enabled = usable, onClick = cycleSpeed', block)
        self.assertIn('testTag("subagent-gpt-speed-${profile.id}")', block)
        self.assertIn('return', block)
        self.assertEqual(1, speed.count('editor?.cycleGptSpeed(profile.id, profile.providerId, profile.modelId, expected = profile)'))
        self.assertIn('if (result !is ConversationSubAgentPreferences.WriteResult.Saved) return@launch', speed)
        self.assertIn('if (!settings) {', speed)
        self.assertIn('role = Role.Button, onLongClick = null, onClick = cycleSpeed)', speed)
        self.assertIn('"GPT 速度 · "', speed)
        self.assertIn('heightIn(min = 24.dp)', speed)

    def test_preset_menu_keeps_only_rename_and_delete_without_removing_add_flow(self):
        detail = source('ui/SubAgentSettingsScreen.kt').split('private fun SubAgentPresetDetail(', 1)[1]
        menu = detail.split('if (editable) SubAgentDropdownMenu(', 1)[1].split('SubAgentProfileRow(', 1)[0]
        self.assertEqual(2, menu.count('DropdownMenuItem('))
        self.assertIn('Text("重命名")', menu)
        self.assertIn('Text("删除", color = MaterialTheme.colorScheme.error)', menu)
        self.assertNotIn('Text("配置")', menu)
        self.assertNotIn('SubAgentProfileDraftSession.open(editor, profile)', detail)
        self.assertIn('SubAgentProfileDraftSession.open(editor)?.let { addedDraft = it }', detail)
        self.assertIn('addedDraft?.let { session ->', detail)
        self.assertIn('SubAgentProfileConfigDialog(session, editor, providers', detail)
        card = source('ui/components/SubAgentProfileRow.kt')
        self.assertIn('SubAgentProfileDraftSession.open(editor, profile)', card)

    def test_exact_smoke_scope_and_near_matches(self):
        target = recorder.RENAME_PRESENTATION_PLATFORM_CLASS
        command, scope = recorder.command_for('python3', dict(SMOKE_MODE='smoke', SMOKE_TEST_CLASSES=target))
        self.assertEqual('rename-presentation-targeted', scope)
        self.assertEqual(['python3', '-B', '-m', 'unittest', *recorder.RENAME_PRESENTATION_MODULES, '-v'], command)
        for classes in ('', target + ',other', target + ',' + target, ' ' + target, target + ' '):
            self.assertEqual('full', recorder.command_for('python3', dict(SMOKE_MODE='smoke', SMOKE_TEST_CLASSES=classes))[1])
        for mode in ('compile', 'release', 'daily', 'candidate'):
            self.assertEqual('full', recorder.command_for('python3', dict(SMOKE_MODE=mode, SMOKE_TEST_CLASSES=target))[1])

    def test_inventory_discovers_only_the_exact_six_device_methods(self):
        script = (ROOT / 'scripts/smoke_boot.sh').read_text().split("target_spec=$(python3 - <<'PYCOUNT'\n", 1)[1].split('\nPYCOUNT', 1)[0]
        fixture = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/RenamePresentationActivityTest.kt'
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'smoke').mkdir()
            path = root / fixture.relative_to(ROOT)
            path.parent.mkdir(parents=True)
            path.write_text(fixture.read_text())
            result = subprocess.run([sys.executable, '-c', script], cwd=root,
                env=dict(os.environ, SMOKE_MODE='smoke', SMOKE_TEST_CLASSES=recorder.RENAME_PRESENTATION_PLATFORM_CLASS,
                         SMOKE_SCRIPT_DIR=str(ROOT / 'scripts'), SMOKE_SHARD_COUNT='1', SMOKE_SHARD_INDEX='0'),
                text=True, capture_output=True)
            self.assertEqual(0, result.returncode, result.stderr)
            inventory = json.loads((root / 'smoke/instrumentation-shards.json').read_text())
            self.assertEqual('targeted', inventory['discovery_scope'])
            selection = json.loads((root / 'smoke/selection.json').read_text())
            self.assertEqual(6, selection['expected_tests'])
            self.assertEqual([recorder.RENAME_PRESENTATION_PLATFORM_CLASS], selection['selected_classes'])
