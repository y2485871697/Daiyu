"""Source-only guards; actual fill pixels and touch geometry run in androidTest."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/settings'
ANDROID = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android'


class ProviderVisualContractTest(unittest.TestCase):
    def test_compact_actions_use_local_explicit_sizes_and_haptic_button(self):
        source = (SRC / 'ProviderSettingsComponents.kt').read_text()
        helper = source.split('internal fun ProviderActionButton(', 1)[1]
        self.assertIn('import app.daiyu.android.ui.haptics.miuix.TextButton', source)
        for fragment in ('TextButton(', 'onClick = onClick', 'enabled = enabled', 'colors = colors',
                         'minHeight = 40.dp', 'PaddingValues(horizontal = 12.dp, vertical = 6.dp)',
                         'fontSize = 14.sp, lineHeight = 20.sp',
                         'modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 4.dp)'):
            self.assertIn(fragment, helper)
        for forbidden in ('pointerInput', 'LocalDensity', 'requiredHeight', 'clickable(', 'alpha('):
            self.assertNotIn(forbidden, helper)

    def test_only_three_provider_actions_use_the_compact_helper(self):
        calls = []
        for path in SRC.glob('*.kt'):
            count = path.read_text().count('ProviderActionButton(')
            if path.name == 'ProviderSettingsComponents.kt':
                count -= 1
            calls.extend([path.name] * count)
        self.assertCountEqual(calls, ['ModelSettingsScreen.kt', 'ModelSettingsScreen.kt', 'ProviderBalanceFields.kt'])

    def test_nonprovider_save_retains_its_existing_size_and_owner(self):
        source = (SRC / 'ModelSettingsScreen.kt').read_text()
        footer = source.split('item(key = "model_save")', 1)[1].split('if (onDelete != null)', 1)[0]
        self.assertIn('semantics { this[RetainedModelSaveAction] = submit }', footer)
        provider, normal = footer.split('if (providerName != null)', 1)[1].split('} else {', 1)
        self.assertIn('ProviderActionButton(', provider)
        self.assertIn('text = "保存配置"', provider)
        self.assertIn('onClick = submit', provider)
        self.assertIn('padding(horizontal = 20.dp, vertical = 4.dp)', provider)
        for fragment in ('TextButton(', 'text = "保存"', 'onClick = submit',
                         'colors = ButtonDefaults.textButtonColorsPrimary()',
                         'enabled = enabled && draft.headerFieldsValid && draft.bodyFieldsValid',
                         'padding(horizontal = 20.dp, vertical = 8.dp)', '.fillMaxWidth().heightIn(min = 48.dp)'):
            self.assertIn(fragment, normal)

    def test_balance_query_gate_callback_and_receipt_remain_intact(self):
        source = (SRC / 'ProviderBalanceFields.kt').read_text()
        self.assertIn('ProviderActionButton(text = if (test.running) "正在测试余额查询" else "测试余额查询"', source)
        self.assertIn('enabled = enabled && testBalance != null && valid && !test.running, onClick = test.start', source)
        self.assertIn('testTag("provider-balance-test").padding(horizontal = 12.dp, vertical = 8.dp)', source)
        self.assertIn('（仅本次测试，未保存）', source)
        self.assertIn('request === flag && currentEnabled && currentOwner == owner', source)

    def test_delete_keeps_color_cancel_and_owner_guards(self):
        source = (SRC / 'ModelSettingsScreen.kt').read_text()
        delete = source.split('if (onDelete != null) item(key = "provider_delete")', 1)[1]
        for fragment in ('ProviderActionButton(text = "删除提供商", enabled = enabled',
                         'if (latestEnabled && latestDraft === draft)',
                         'connection.cancel(); balance.cancel(); onDelete()',
                         'color = MiuixTheme.colorScheme.surfaceVariant', 'textColor = assistantPreviewPalette().danger',
                         'testTag("provider-delete").padding(horizontal = 20.dp, vertical = 4.dp)'):
            self.assertIn(fragment, delete)

    def test_anthropic_keeps_ai_mark_in_the_same_fixed_slot(self):
        source = (SRC / 'ProviderDirectoryPage.kt').read_text()
        anthropic = source.split('SettingsRow("新增 Anthropic"', 1)[1].split('SettingsRow("新增豆包语音"', 1)[0]
        self.assertIn('Box(Modifier.size(24.dp).testTag("provider-add-anthropic-icon"), contentAlignment = Alignment.Center)', anthropic)
        self.assertIn('Text("AI")', anthropic)
        self.assertIn('onClick = if (enabled) onAddAnthropic else null', anthropic)
        for tag in ('provider-add-icon', 'provider-add-speech-icon'):
            self.assertIn(f'Modifier.testTag("{tag}")', source)

    def test_android_regressions_keep_pixel_touch_and_business_assertions(self):
        actions = (ANDROID / 'ProviderSettingsLayoutActivityTest.kt').read_text()
        for fragment in ('listOf(false, true)', 'listOf(.9f, 1.3f)', 'key(dark, fontScale)',
                         'assertTouchHeightIsEqualTo(48.dp)', 'val bitmap = host.captureToImage()', 'bitmap.toPixelMap(startX = left, startY = top, width = width, height = height)',
                         '40f * density, paintedRows.size.toFloat(), 2f', 'click(topPoint - hostBounds.topLeft)', 'click(bottomPoint - hostBounds.topLeft)',
                         'assertIsNotEnabled()', 'button.performTouchInput { click() }',
                         'paintedRows.last() - paintedRows.first() + 1', 'outside.green',
                         'testConfirmedFooterDeletionClearsCurrentProviderAndReturnsToChat',
                         'testNameIsSavedInsideConfigurationWithoutChangingModelOrCredentials'):
            self.assertIn(fragment, actions)
        directory = (ANDROID / 'ProviderAuthenticationChoiceActivityTest.kt').read_text()
        for fragment in ('listOf(false, true)', 'listOf(.9f, 1.3f)', 'key(dark, fontScale)',
                         'useUnmergedTree = true', 'icon.left', 'title.left', 'summary.left',
                         'row.center.y, icon.center.y', 'checkNotNull(iconWidth), icon.width',
                         'checkNotNull(iconHeight), icon.height', 'node("provider-add-speech").assertHasNoClickAction()',
                         'testAnthropicAddUsesInlineNameAndKeepsProviderDisabled'):
            self.assertIn(fragment, directory)


if __name__ == '__main__':
    unittest.main()
