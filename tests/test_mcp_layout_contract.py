"""MCP source wiring contracts; actual Compose layout/pixels are tested on Android."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/settings/McpSettingsScreen.kt'
ANDROID = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/McpSettingsScreenTest.kt'


class McpLayoutContractTest(unittest.TestCase):
    def setUp(self):
        self.source = SOURCE.read_text()
        self.form = self.source.split('if (editing) WindowDialog(', 1)[1].split('if (deleting', 1)[0]

    def test_list_restores_icon_count_and_empty_card(self):
        for text in ('IconButton(onClick = { edit(null) }', 'DaiyuIcons.Add, contentDescription = "添加服务器"',
                     '"mcp-settings-add"', '"服务器 · ${snapshot?.servers?.size ?: 0}"',
                     '"mcp-settings-empty"', '"还没有 MCP 服务器"', '"点击添加服务器"',
                     '"mcp-settings-empty-add"', 'RoundedCornerShape(18.dp)', 'background(conversationAgentColors().soft)'):
            self.assertIn(text, self.source)
        self.assertIn('if (snapshot?.servers?.isEmpty() == true)', self.source)
        self.assertIn('stateDescription = status', self.source)
        self.assertIn('if (selected == null && status.isNotBlank() && (busy || status != "能力默认可用；保存只更新服务器配置，不会执行工具。"))', self.source)
        self.assertNotIn('已配置服务器（', self.source)
        self.assertNotIn('尚未添加服务器', self.source)

    def test_detail_refresh_reuses_workspace_icon_and_preserves_owned_action(self):
        actions = self.source.split('SettingsScaffoldPage(title =', 1)[1].split('if (selected == null &&', 1)[0]
        for text in ('app.daiyu.android.ui.haptics.miuix.IconButton(',
                     'onClick = { snapshot?.let { refresh(it, selected) } }',
                     'enabled = !busy && snapshot != null',
                     'Modifier.size(48.dp).testTag("mcp-refresh:${selected.id}")',
                     'DaiyuIcons.Refresh, contentDescription = "刷新", size = 22.dp'):
            self.assertIn(text, actions)
        self.assertNotIn('TextButton(text = "刷新"', actions)

    def test_tool_switch_uses_existing_reference_track_without_changing_other_states(self):
        row = self.source.split('private fun McpToolRow(', 1)[1].split('private fun McpToolDescriptionSheet(', 1)[0]
        for text in ('SettingsSwitch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange',
                     'colors = daiyuSwitchColors().copy(',
                     'checkedThumbColor = MiuixTheme.colorScheme.onPrimary',
                     'checkedTrackColor = DaiyuPalette.switchTrack',
                     'checkedBorderColor = DaiyuPalette.switchTrack'):
            self.assertIn(text, row)
        self.assertNotIn('disabledChecked', row)
        self.assertNotIn('Color(0x', row)

    def test_detail_omits_explanatory_copy_without_removing_connection_or_empty_tools(self):
        detail = self.source.split('item(key = "server-settings")', 1)[1].split('if (editing)', 1)[0]
        settings = detail.split('item(key = "tools-heading")', 1)[0]
        self.assertNotIn('SettingsNote(', settings)
        for text in ('SettingsSectionTitle("服务器设置")', 'SettingsRow("连接设置", summary = selected.endpoint',
                     '"mcp-settings-edit"', 'SettingsNote("尚无工具，请刷新工具目录。")',
                     'SettingsRow("删除服务器"', '"mcp-settings-delete"'):
            self.assertIn(text, detail)
        self.assertNotIn('已保存；旧的本轮批准已失效，尚未连接服务器。', self.source)
        self.assertIn('status.isNotBlank()', self.source)
        self.assertIn('stateDescription = status', self.source)

    def test_tool_rows_are_single_line_with_independent_switch_and_description_sheet(self):
        row = self.source.split('private fun McpToolRow(', 1)[1].split('private fun McpToolDescriptionSheet(', 1)[0]
        self.assertNotIn('summary = tool.description', self.source)
        for text in ('maxLines = 1', 'TextOverflow.Ellipsis', 'heightIn(min = 64.dp)',
                     'DaiyuIcons.ChevronRight', 'SettingsSwitch(checked = checked',
                     '"mcp-tool-enabled:$serverId:$title"', 'onClick = onDescribe'):
            self.assertIn(text, row)
        self.assertLess(row.index('DaiyuIcons.ChevronRight'), row.index('SettingsSwitch('))
        sheet = self.source.split('private fun McpToolDescriptionSheet(', 1)[1]
        for text in ('ConversationAgentSheet(onDismiss, useDefaultHeight = true)', 'ConversationAgentHandle()',
                     'tool.description.ifBlank', 'Modifier.weight(1f)', '.verticalScroll(rememberScrollState())',
                     '"mcp-tool-description-title"', '"mcp-tool-description-done"'):
            self.assertIn(text, sheet)
        self.assertNotIn('maxLines', sheet)

    def test_bulk_controls_reuse_provider_count_text_style_and_persist_one_owned_snapshot(self):
        heading = self.source.split('private fun McpToolsHeading(', 1)[1].split('private fun McpToolRow(', 1)[0]
        for text in ('"全部开启"', '"全部关闭"', 'Modifier.alignByBaseline()', '.clickable(enabled = enabled, role = Role.Button',
                     '"mcp-tools-enable-all"', '"mcp-tools-disable-all"', 'padding(horizontal = 24.dp)'):
            self.assertIn(text, heading)
        self.assertNotIn('ConversationAgentButton', heading)
        self.assertNotIn('TextButton(', heading)
        for text in ('snapshot !== before', 'disabled == server.disabledTools', 'it.withPolicy(disabledTools = disabled)',
                     '}, owner, before)', 'selected.eligible(tool.name)'):
            self.assertIn(text, self.source)

    def test_add_refreshes_once_after_success_and_does_not_make_visiting_connect(self):
        save = self.source.split('fun save(remove: Boolean)', 1)[1].split('fun refresh(', 1)[0]
        self.assertIn('val added = !remove && before.servers.none { it.id == id }', save)
        self.assertEqual(1, save.count('managementFactory(store).refresh(saved, savedId, owner)'))
        self.assertLess(save.index('store.save(value, owner, before)'), save.index('managementFactory(store).refresh('))
        self.assertIn('if (result.isSuccess)', save)
        self.assertIn('if (added)', save)
        self.assertIn('服务器已保存，工具目录刷新失败；请点击刷新重试。', save)
        load = self.source.split('LaunchedEffect(owner)', 1)[1].split('DisposableEffect(owner)', 1)[0]
        self.assertNotIn('managementFactory', load)
        self.assertIn('if (sameConnection) old!!.disabledTools else emptySet()', save)

    def test_android_regression_covers_touch_long_text_switches_and_automatic_discovery(self):
        text = ANDROID.read_text()
        for value in ('testAddDiscoversOnceCompactRowsAndToolSwitchesPersistIndependentlyFromDescription',
                      'assertEquals(1, opens)', 'methods.count { it == "tools/list" }', 'assertIsOn()', 'assertIsOff()',
                      'performTouchInput { click() }', 'SemanticsActions.GetTextLayoutResult', 'isLineEllipsized(0)',
                      'SemanticsProperties.VerticalScrollAxisRange', '"mcp-tools-disable-all"',
                      '"mcp-tool-description-sheet"', 'assertEquals(1, managementOpens)',
                      'instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)',
                      'awaitToolState(true)', 'awaitToolState(false)'):
            self.assertIn(value, text)

    def test_form_uses_filled_rounded_placeholders_and_password_mask(self):
        self.assertEqual(3, self.form.count('McpConnectionField('))
        self.assertNotIn('OutlinedTextField(', self.form)
        for text in ('"名称"', '"服务器地址"', '"Bearer Token（可选）"', '"Bearer Token（留空保留）"',
                     'visualTransformation = PasswordVisualTransformation()', 'enabled = !busy'):
            self.assertIn(text, self.form)
        helper = self.source.split('private fun McpConnectionField(', 1)[1]
        for text in ('BasicTextField(', 'singleLine = true', 'RoundedCornerShape(16.dp)',
                     'background(colors.surfaceContainerHigh)', 'if (value.isEmpty()) Text(hint', 'contentDescription = hint'):
            self.assertIn(text, helper)

    def test_cancel_left_confirm_right_equal_width_height(self):
        actions = self.form.split('Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)', 1)[1]
        self.assertLess(actions.index('ConversationAgentButton("取消"'), actions.index('ConversationAgentButton(if (selected == null)'))
        self.assertEqual(2, actions.count('Modifier.weight(1f).fillMaxHeight()'))
        self.assertIn('Arrangement.spacedBy(8.dp)', actions)
        self.assertIn('"添加服务器" else "保存", primary = true', actions)
        self.assertIn('enabled = !busy && snapshot != null', actions)
        self.assertIn('{ save(false) }', actions)
        self.assertIn('editing = false; bearer = ""', actions)

    def test_buttons_reuse_reference_palette_without_global_theme_changes(self):
        self.assertIn('import app.daiyu.android.ui.components.subagent.ConversationAgentButton', self.source)
        self.assertNotIn('Color(0x', self.source)
        self.assertNotIn('ProviderSettingsTheme', self.source)
        self.assertNotIn('LocalConversationAgentStyle provides', self.source)

    def test_all_three_inputs_keep_managed_focus_and_ime_cursor(self):
        self.assertEqual(3, self.form.count('.managedInput()'))
        self.assertEqual(3, self.form.count('imeCursorBrush(colors.accent)'))
        self.assertIn('.verticalScroll(rememberScrollState())', self.form)

    def test_authorization_storage_refresh_and_revocation_remain_owned(self):
        for text in ('fun current() = owner.current() && ownerCurrent()', 'if (busy || !current()) return',
                     'store.save(value, owner, before)', 'owner.revoke()',
                     'val secret = if (bearer.isEmpty()) old?.bearer.orEmpty() else bearer',
                     'if (sameConnection) old!!.allowedTools else emptySet()',
                     'managementFactory(store).refresh(before, server.id, owner)',
                     'label = server?.label.orEmpty(); endpoint = server?.endpoint.orEmpty(); bearer = ""'):
            self.assertIn(text, self.source)

    def test_loopback_http_is_the_only_android_cleartext_exception(self):
        manifest = ET.parse(ROOT / 'app/src/main/AndroidManifest.xml').getroot()
        application = manifest.find('application')
        android = '{http://schemas.android.com/apk/res/android}'
        self.assertEqual('false', application.get(android + 'usesCleartextTraffic'))
        self.assertEqual('@xml/network_security_config', application.get(android + 'networkSecurityConfig'))
        config = ET.parse(ROOT / 'app/src/main/res/xml/network_security_config.xml').getroot()
        self.assertEqual('false', config.find('base-config').get('cleartextTrafficPermitted'))
        exceptions = config.findall('domain-config')
        self.assertEqual(1, len(exceptions))
        self.assertEqual('true', exceptions[0].get('cleartextTrafficPermitted'))
        domains = exceptions[0].findall('domain')
        self.assertEqual(1, len(domains))
        self.assertEqual('127.0.0.1', domains[0].text)
        self.assertEqual('false', domains[0].get('includeSubdomains'))

    def test_loopback_form_and_default_socket_transport_have_android_regressions(self):
        text = ANDROID.read_text()
        for value in ('testLoopbackHttpAddRefreshesOnceAndFailureKeepsConnectionForReopen',
                      'testLoopbackDefaultTransportDiscoversLargeCatalogAndPersistsIt',
                      'java.net.ServerSocket(', 'val session = McpHttpSession(server)',
                      'policy.isCleartextTrafficPermitted("127.0.0.1")',
                      'assertEquals(1, managementOpens)',
                      'http://127.0.0.1:8787/mcp'):
            self.assertIn(value, text)

    def test_android_coverage_includes_themes_geometry_color_and_both_entrypoints(self):
        text = ANDROID.read_text()
        for value in ('for (dark in listOf(false, true))', 'listOf("mcp-settings-empty-add", "mcp-settings-add")',
                      'left.right <= right.left', 'left.width - right.width', 'left.height - right.height',
                      'captureScreenNode(save).toPixelMap()', 'Color(if (dark) 0xFFB6A4C7 else 0xFF8E7C9E)',
                      'assertTrue(store.snapshot().servers.isEmpty())', 'activity.onBackPressedDispatcher.onBackPressed()',
                      'assertTrue(McpSettingsStore(context) { key }.snapshot().servers.isEmpty())',
                      'node("mcp-server-endpoint").performTextReplacement("https://fixture.invalid/mcp")',
                      'store.save(emptyList(), owner, beforeRevocation)', 'assertEquals(beforeRevocation.revision, afterRevocation.revision)', 'assertSame(beforeRevocation.stamp, afterRevocation.stamp)', 'before.catalog.zip(after.catalog)',
                      'for (y in listOf(.15f, .85f))',
                      '"Bearer Token（留空保留）"', 'assertEquals("synthetic-fixture-token", store.snapshot().servers.single().bearer)',
                      'assertEquals(0, fixture.opens.get())'):
            self.assertIn(value, text)


if __name__ == '__main__':
    unittest.main()
