import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / "app/src/main/kotlin/app/daiyu/android/ui"
HELP = "仅用于摘要。默认 Chat Completions；模型需要时选 Responses。Anthropic / Codex 不受此项影响。"


class CompressionProtocolHelpContract(unittest.TestCase):
    def setUp(self):
        self.form = (UI / "screens/settings/CompressionSettingsForm.kt").read_text()
        self.dialog = (UI / "screens/chat/context/CompressionConversationDialog.kt").read_text()
        path = UI / "components/CompressionProtocolHelp.kt"
        self.help = path.read_text() if path.exists() else ""

    def test_settings_removes_only_the_requested_fixed_descriptions(self):
        self.assertNotIn("实测达到窗口 80% 时压缩", self.form)
        self.assertNotIn(HELP, self.form)
        self.assertIn('summary = "关闭时沿用对话模型。"', self.form)
        self.assertIn('onSave(draft.copy(automatic = it))', self.form)

    def test_settings_places_the_shared_help_under_the_protocol(self):
        protocol = self.form.index('ProviderChoiceRow("请求接口"')
        helper = self.form.index('tag = "compression-protocol-help"')
        model = self.form.index('item("compression-profile")')
        self.assertLess(protocol, helper)
        self.assertLess(helper, model)

    def test_dialog_and_settings_share_the_same_component_and_body(self):
        self.assertIn('CompressionProtocolHelp(expanded = showHelp', self.dialog)
        self.assertIn('tag = "compression-dialog-help"', self.dialog)
        self.assertNotIn(HELP, self.dialog)
        self.assertEqual(1, self.help.count(HELP))
        self.assertIn('Text("接口怎么选"', self.help)
        self.assertIn('heightIn(min = 44.dp)', self.help)
        self.assertIn('fontSize = 12.sp', self.help)
        self.assertIn('Modifier.testTag("$tag-text")', self.help)

    def test_disclosure_starts_closed_and_reuses_existing_motion(self):
        for caller in [self.form, self.dialog]:
            self.assertLess(caller.index('remember { mutableStateOf(false) }'),
                            caller.index('item("compression-protocol")' if caller == self.form else 'item("help")'))
            self.assertIn('onToggle = { showHelp = !showHelp }', caller)
        self.assertIn('SettingsExpandableContent(expanded = expanded)', self.help)
        self.assertIn('settingsDisclosureRotation(expanded)', self.help)
        for action in ['onSave', 'onChange', 'onConfirm', 'onChooseModel', 'ProviderProtocol']:
            self.assertNotIn(action, self.help)

    def test_protocol_placeholder_and_model_selection_are_unchanged(self):
        self.assertIn('selectedIndex = -1, onSelect = {}', self.form)
        self.assertIn('"compression-endpoint:chat-completions", "compression-endpoint:responses"', self.form)
        self.assertIn('if (it && !hasSummarySelection) onEnableSummary()', self.form)
        self.assertIn('"当前使用的模型"', self.form)


if __name__ == "__main__":
    unittest.main()
