"""Source-only guards; actual fill pixels and touch geometry run in androidTest."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/settings'
ANDROID = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android'


class ProviderVisualContractTest(unittest.TestCase):
    def test_anthropic_version_is_internal_and_has_no_visible_editor(self):
        source = (SRC / "ModelSettingsScreen.kt").read_text()
        self.assertNotIn('SettingsTextField("Anthropic 版本"', source)
        self.assertNotIn('留空使用 2023-06-01；格式 YYYY-MM-DD', source)
        self.assertIn('anthropicVersion: String = ""', source)
        self.assertIn('it.anthropicVersion == draft.anthropicVersion', source)
        activity = (ANDROID / "ActivityTest.kt").read_text()
        self.assertIn('testMessagesVersionIsHiddenAndPreservedWhenSavingAndReopening', activity)
        self.assertIn('for (version in listOf(null, "2023-06-02"))', activity)
        self.assertIn('assertEquals(saved, fixture.settings.load())', activity)
        for name in ("ActivityTest.kt", "ModelConnectionActivityTest.kt", "ProviderAuthenticationChoiceActivityTest.kt"):
            regression = (ANDROID / name).read_text()
            self.assertNotIn('editModel("Anthropic 版本"', regression)
            self.assertNotIn('modelField("Anthropic 版本"', regression)
            self.assertNotIn('reveal("settings-field:Anthropic 版本"', regression)

    def test_tabs_use_collaboration_secondary_colors_and_keep_selection_semantics(self):
        source = (SRC / 'ProviderSettingsComponents.kt').read_text()
        tabs = source.split('internal fun ProviderSettingsTabs(', 1)[1].split('internal fun ProviderProtocolFields(', 1)[0]
        for fragment in ('if (selected) ButtonDefaults.textButtonColorsPrimary()',
                         'val secondary = conversationAgentColors()',
                         'else ButtonDefaults.textButtonColors(color = secondary.soft, textColor = secondary.onSoft)',
                         '.background(if (enabled) buttonColors.color else buttonColors.disabledColor)',
                         'color = if (enabled) buttonColors.textColor else buttonColors.disabledTextColor',
                         'listOf("配置", "模型")', 'val selected = modelTab == (index == 1)',
                         '.selectableGroup()', '.weight(1f).heightIn(min = 48.dp)',
                         'RoundedCornerShape(14.dp)', '.testTag("settings-radio:$title")',
                         '.selectable(selected = selected, enabled = enabled, role = Role.Tab,',
                         'onClick = { onSelect(index == 1) }'):
            self.assertIn(fragment, tabs)
        self.assertIn('import app.daiyu.android.ui.components.subagent.conversationAgentColors', source)
        for forbidden in ('.border(', 'colors.surfaceVariant', 'colors.onSurface', 'Color('):
            self.assertNotIn(forbidden, tabs)

    def test_provider_actions_use_local_comfortable_sizes_and_haptic_button(self):
        source = (SRC / 'ProviderSettingsComponents.kt').read_text()
        helper = source.split('internal fun ProviderActionButton(', 1)[1]
        self.assertIn('import app.daiyu.android.ui.haptics.miuix.TextButton', source)
        self.assertIn('import androidx.compose.ui.platform.LocalDensity', source)
        self.assertIn('import androidx.compose.ui.unit.Dp', source)
        self.assertIn('val actionMinHeight: Dp = maxOf(', helper)
        self.assertIn('48.dp,', helper)
        self.assertIn('with(LocalDensity.current) { 22.sp.toDp() } + 24.dp', helper)

        for fragment in ('TextButton(', 'onClick = onClick', 'enabled = enabled', 'colors = colors',
                         'minHeight = actionMinHeight', 'PaddingValues(horizontal = 16.dp, vertical = 12.dp)',
                         'fontSize = 16.sp, lineHeight = 22.sp',
                         'modifier.fillMaxWidth().heightIn(min = actionMinHeight)',
                         'color = MiuixTheme.colorScheme.primary', 'textColor = MiuixTheme.colorScheme.onPrimary'):
            self.assertIn(fragment, helper)
        for forbidden in ('pointerInput', 'LocalDensity provides', 'CompositionLocalProvider', 'remember', '.height(', 'maxHeight =', 'requiredHeight', 'clickable(', 'alpha(', '.padding(vertical = 4.dp)'):
            self.assertNotIn(forbidden, helper)

    def test_only_four_provider_actions_use_the_local_helper(self):
        calls = []
        for path in SRC.glob('*.kt'):
            count = path.read_text().count('ProviderActionButton(')
            if path.name == 'ProviderSettingsComponents.kt':
                count -= 1
            calls.extend([path.name] * count)
        self.assertCountEqual(calls, ['ModelSettingsScreen.kt', 'ModelSettingsScreen.kt', 'ModelSettingsScreen.kt', 'ProviderBalanceFields.kt'])

    def test_connection_uses_primary_helper_only_on_provider_page(self):
        source = (SRC / 'ModelSettingsScreen.kt').read_text()
        block = source.split('if (testConnection != null) {', 1)[1].split('connection.status?.let', 1)[0]
        provider, normal = block.split('if (providerName != null) {', 1)[1].split('} else {', 1)
        self.assertIn('ProviderActionButton(', provider)
        self.assertNotIn('ProviderActionButton(', normal)
        self.assertIn('TextButton(', normal)
        self.assertIn('fillMaxWidth().padding(12.dp)', normal)
        self.assertIn('padding(horizontal = 12.dp, vertical = 8.dp)', provider)
        for branch in (provider, normal):
            self.assertIn('if (connection.running) "正在测试连接" else "测试连接"', branch)
            self.assertIn('enabled = enabled && !connection.running, onClick = connection.start', branch)
            self.assertIn('testTag("model-connection-test")', branch)

    def test_nonprovider_save_retains_its_existing_size_and_owner(self):
        source = (SRC / 'ModelSettingsScreen.kt').read_text()
        footer = source.split('item(key = "model_save")', 1)[1].split('if (onDelete != null)', 1)[0]
        self.assertIn('semantics { this[RetainedModelSaveAction] = submit }', footer)
        provider, normal = footer.split('if (providerName != null)', 1)[1].split('} else {', 1)
        self.assertIn('ProviderActionButton(', provider)
        self.assertIn('text = if (onOAuthLogin != null) "创建" else "保存配置"', provider)
        self.assertIn('onClick = submit', provider)
        self.assertIn('enabled = enabled', provider)
        self.assertIn('padding(horizontal = 20.dp, vertical = 4.dp)', provider)
        for fragment in ('TextButton(', 'text = "保存"', 'onClick = submit',
                         'colors = ButtonDefaults.textButtonColorsPrimary()',
                         'enabled = enabled',
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
                         'colors = deletionButtonColors()',
                         'testTag("provider-delete").padding(horizontal = 20.dp, vertical = 4.dp)'):
            self.assertIn(fragment, delete)

    def test_anthropic_keeps_ai_mark_in_the_same_fixed_slot(self):
        source = (SRC / 'ProviderDirectoryPage.kt').read_text()
        anthropic = source.split('SettingsRow("新增 Anthropic"', 1)[1].split('SettingsRow("新增豆包语音"', 1)[0]
        self.assertIn('Box(Modifier.size(24.dp).testTag("provider-add-anthropic-icon"), contentAlignment = Alignment.Center)', anthropic)
        self.assertIn('Text("AI")', anthropic)
        self.assertIn('onClick = if (enabled && selectedSlots == null) onAddAnthropic else null', anthropic)
        for tag in ('provider-add-icon', 'provider-add-speech-icon'):
            self.assertIn(f'Modifier.testTag("{tag}")', source)

    def test_android_regressions_keep_pixel_touch_and_business_assertions(self):
        actions = (ANDROID / 'ProviderSettingsLayoutActivityTest.kt').read_text()
        for fragment in ('listOf(false, true)', 'listOf(.9f, 1.3f)', 'key(dark, fontScale)',
                         'touch.height >= kotlin.math.round(48f * density)', 'assertHeightIsAtLeast(48.dp)',
                         'val bitmap = captureScreenNode(host)', 'bitmap.toPixelMap(startX = left, startY = top, width = width, height = height)',
                         'bounds.height, paintedRows.size.toFloat(), 2f', 'click(topPoint - hostBounds.topLeft)', 'click(bottomPoint - hostBounds.topLeft)',
                         'assertIsNotEnabled()', 'button.performTouchInput { click() }',
                         'paintedRows.last() - paintedRows.first() + 1', 'ProviderSettingsTheme {',
                         'val expected = if (index == titles.lastIndex) deleteFill else primary',
                         'assertEquals(16.sp, layout.layoutInput.style.fontSize)',
                         'assertEquals(22.sp, layout.layoutInput.style.lineHeight)',
                         'deleteText else onPrimary, layout.layoutInput.style.color)',
                         'layout.hasVisualOverflow', 'large font grows the fill',
                         'listOf("测试连接", "测试余额查询", "保存配置", "删除提供商")',
                         'testConfirmedFooterDeletionClearsCurrentProviderAndReturnsToDirectory',
                         'testNameIsSavedInsideConfigurationWithoutChangingModelOrCredentials'):
            self.assertIn(fragment, actions)
        directory = (ANDROID / 'ProviderAuthenticationChoiceActivityTest.kt').read_text()
        for fragment in ('listOf(false, true)', 'listOf(.9f, 1.3f)', 'key(dark, fontScale)',
                         'useUnmergedTree = true', 'icon.left', 'title.left', 'summary.left',
                         'row.center.y, icon.center.y', 'checkNotNull(iconWidth), icon.width',
                         'checkNotNull(iconHeight), icon.height', 'node("provider-add-speech").assertHasClickAction()',
                         'testAnthropicAddUsesInlineNameAndKeepsProviderDisabled'):
            self.assertIn(fragment, directory)


if __name__ == '__main__':
    unittest.main()
