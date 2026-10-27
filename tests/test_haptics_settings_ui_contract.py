"""Source guards for the original haptics layout; pixel/device checks are separate."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCREEN = ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/settings/HapticsSettingsScreen.kt'


class HapticsSettingsUiContractTest(unittest.TestCase):
    def setUp(self):
        self.source = SCREEN.read_text()

    def test_original_compact_dialog_and_four_discrete_miuix_levels(self):
        for text in ('import top.yukonga.miuix.kmp.basic.Slider as IntensityPreviewSlider',
                     'WindowDialog(show = intensityDialog, title = "震动幅度"',
                     'val labels = listOf("默认", "低", "中", "高")',
                     'DaiyuIcons.Vibration, contentDescription = null, size = 32.dp',
                     'Modifier.padding(top = 6.dp, bottom = 16.dp)',
                     'valueRange = 0f..3f, steps = 2', 'showKeyPoints = true',
                     'keyPoints = HapticIntensity.entries.indices.map { it.toFloat() }',
                     'magnetThreshold = 0.18f', 'SliderDefaults.SliderHapticEffect.None'):
            self.assertIn(text, self.source)
        self.assertNotIn('androidx.compose.material3.Slider', self.source)
        self.assertNotIn('pointerInput', self.source)  # retain native slider accessibility
        dialog = self.source.split('WindowDialog(', 1)[1].split('private class HapticsPageOwner', 1)[0]
        self.assertNotIn('SettingsCard', dialog)
        self.assertNotIn('SettingsRow', dialog)

    def test_slider_uses_the_actual_done_button_palette_in_both_themes(self):
        for text in ('import app.daiyu.android.ui.screens.assistants.assistantPreviewPalette',
                     'val intensityAccent = assistantPreviewPalette().primary',
                     'foregroundColor = intensityAccent',
                     'keyPointColor = intensityAccent.copy(alpha = .30f)',
                     'keyPointForegroundColor = intensityAccent'):
            self.assertIn(text, self.source)
        palette = (ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/assistants/AssistantPreviewComponents.kt').read_text()
        self.assertIn('private val actions = resolveAppearanceActions(appearance, dark)', palette)
        self.assertIn('val primary = actions.primary', palette)
        button = palette.split('internal fun AssistantPreviewButton(', 1)[1]
        self.assertIn('primary -> colors.primary', button)

    def test_all_switches_share_a_centered_local_slot_without_changing_the_shared_control(self):
        for text in ('HapticsSwitchSlot("触感反馈", expanded = intensityDialog)',
                     'HapticsSwitchSlot("后台震动", expanded = expanded.takeIf { settings.backgroundOn })',
                     'HapticsSwitchSlot(title)',
                     'Row(Modifier.size(width = 72.dp, height = 48.dp)',
                     'horizontalArrangement = Arrangement.SpaceBetween',
                     'else Box(Modifier.size(16.dp))',
                     'Box(Modifier.size(48.dp).testTag("haptics-switch-slot:$title"), contentAlignment = Alignment.Center)',
                     'Switch(checked = checked, enabled = enabled, onCheckedChange = null'):
            self.assertIn(text, self.source)
        self.assertEqual(3, self.source.count('checkedTrackColor = DaiyuPalette.switchTrack'))

    def test_chevrons_follow_actual_expansion_and_precede_the_switch(self):
        self.assertIn('private fun HapticsSwitchSlot(', self.source)
        slot = self.source.split('private fun HapticsSwitchSlot(', 1)[1]
        self.assertIn('if (expanded != null)', slot)
        self.assertIn('DaiyuIcons.ChevronRight', slot)
        self.assertIn('Modifier.settingsDisclosureRotation(expanded)', slot)
        self.assertIn('contentDescription = if (expanded) "收起$title" else "展开$title"', slot)
        self.assertIn('size = 16.dp', slot)
        self.assertIn('Row(Modifier.size(width = 72.dp, height = 48.dp)', slot)
        self.assertIn('else Box(Modifier.size(16.dp))', slot)
        self.assertLess(slot.index('DaiyuIcon('), slot.index('Box(Modifier.size(48.dp)'))

    def test_only_explanatory_footnotes_are_removed(self):
        self.assertNotIn('关闭上级开关不会覆盖', self.source)
        self.assertNotIn('系统默认使用系统触感主题', self.source)
        for text in ('summary = labels[settings.intensity.ordinal]', '"未启用"',
                     '"正文、工具与推理过程"', '"正文、工具与推理完成"', 'SettingsNote(message, error = true)'):
            self.assertIn(text, self.source)

    def test_immediate_save_preview_and_revoked_owner_guards_are_preserved(self):
        for text in ('pageOwner.active && currentOwner()', 'pageOwner.active = false',
                     'observation.close(); handler.removeCallbacksAndMessages(null)',
                     'if (canAct() && next != runtime.store.read().intensity)',
                     'val saved = runtime.store.write(runtime.store.read().copy(intensity = next))',
                     'settings = runtime.store.read()', 'if (!saved) error = "设置写入失败，未保存"',
                     'onDismissRequest = { intensityDialog = false }',
                     'if (!old.touch && next.touch) session.touch()'):
            self.assertIn(text, self.source)
        self.assertEqual(1, self.source.count('session.preview(intensity)'))
        callback = self.source.split('onValueChange = { value ->', 1)[1].split('\n                    })', 1)[0]
        self.assertEqual(1, callback.count('preview(next)'))
        self.assertEqual(1, callback.count('runtime.store.write('))

    def test_background_expansion_only_changes_after_a_successful_save(self):
        self.assertIn('fun save(transform: HapticSettings.() -> HapticSettings): Boolean', self.source)
        save = self.source.split('fun save(', 1)[1].split('fun preview(', 1)[0]
        self.assertIn('if (!canAct()) return false', save)
        self.assertIn('error = "设置写入失败，未保存"\n            return false', save)
        self.assertIn('return true', save)
        self.assertIn('if (save { copy(background = true) }) expanded = true', self.source)
        self.assertIn('onCheckedChange = { if (save { copy(background = it) }) expanded = it }', self.source)
        self.assertNotIn('expanded = it; save', self.source)
