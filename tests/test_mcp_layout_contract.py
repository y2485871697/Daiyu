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
        self.assertIn('if (busy || status != "能力默认可用；保存只更新服务器配置，不会执行工具。")', self.source)
        self.assertNotIn('已配置服务器（', self.source)
        self.assertNotIn('尚未添加服务器', self.source)

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
        for value in ('testLoopbackHttpCanBeAddedAndReopenedWithoutTokenOrNetworkAccess',
                      'testLoopbackDefaultTransportDiscoversLargeCatalogAndPersistsIt',
                      'java.net.ServerSocket(', 'val session = McpHttpSession(server)',
                      'policy.isCleartextTrafficPermitted("127.0.0.1")',
                      'assertEquals(0, managementOpens)',
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
