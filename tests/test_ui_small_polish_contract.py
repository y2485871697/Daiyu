"""Narrow UI regressions for the requested icon, empty-state and status copy changes."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / "app/src/main/kotlin/app/daiyu/android/ui"

class SmallUiPolishContractTest(unittest.TestCase):
    def test_group_card_and_chat_entry_follow_the_done_button_primary(self):
        card = (UI / "components/SubAgentPresetCard.kt").read_text()
        top = (UI / "screens/chat/conversation/ChatTopBar.kt").read_text()
        button = (UI / "components/subagent/ConversationAgentStyle.kt").read_text()
        self.assertIn("primaryColor: Color = assistantPreviewPalette().primary", button)
        self.assertIn("tint = assistantPreviewPalette().primary", card)
        self.assertNotIn("daiyuIconColor(Icons.Rounded.AccountTree)", card)
        entry = next(line for line in top.splitlines() if "ChatPreviewIcons.SubAgents" in line)
        self.assertIn("tint = assistantPreviewPalette().primary", entry)
        self.assertIn("Modifier.size(40.dp)", entry)
        self.assertIn("ChatMenuAction.COLLABORATION", entry)

    def test_skill_empty_text_centers_only_inside_its_original_row(self):
        source = (UI / "screens/settings/SkillManagementScreen.kt").read_text()
        empty = source.split('item(key = "skills_empty")', 1)[1].split('} else if', 1)[0]
        self.assertIn('SkillSettingsCard', empty)
        self.assertIn('SettingsRow("尚未安装技能", textModifier = Modifier.wrapContentWidth(Alignment.CenterHorizontally))', empty)
        self.assertNotIn('fillMaxHeight', empty)
        self.assertNotIn('weight(', empty)
        self.assertIn('state != null && state.entries.isEmpty()', source)

    def test_mcp_success_clears_copy_without_removing_errors_or_save(self):
        source = (UI / "screens/settings/McpSettingsScreen.kt").read_text()
        saved = source.split('snapshot = saved; editing = false; deleting = false', 1)[1].split('if (added)', 1)[0]
        self.assertIn('status = ""', saved)
        self.assertIn('status.isNotBlank()', source)
        self.assertIn('store.save(value, owner, before)', source)
        self.assertIn('保存失败或页面/配置已过期；请重新打开设置。', source)
        self.assertIn('服务器已保存，工具目录刷新失败；请点击刷新重试。', source)
        self.assertIn('managementFactory(store).refresh(saved, savedId, owner)', source)

    def test_linux_ready_label_keeps_the_existing_state_guard(self):
        source = (UI / "screens/settings/LinuxSettingsScreen.kt").read_text()
        self.assertNotIn('环境文件已就绪', source)
        self.assertIn('installed && terminal?.linux?.state == "FILES_READY" && selected?.executionMode == mode && terminal.linux.distribution.equals(distribution, true) -> "环境已就绪"', source)

if __name__ == '__main__':
    unittest.main()
