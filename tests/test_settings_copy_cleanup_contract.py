"""Scoped contracts for the requested settings and statistics copy cleanup."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/kotlin/app/daiyu/android"


def source(relative):
    return (MAIN / relative).read_text()


class SettingsCopyCleanupContractTest(unittest.TestCase):
    def test_subagent_intro_removed_without_hiding_directory_status_or_card_summary(self):
        screen = source("ui/SubAgentSettingsScreen.kt")
        self.assertNotIn("预设组可供不同会话复制使用；修改预设不会改变已应用的会话。", screen)
        self.assertIn("SubAgentPresetDirectoryStatus(directory)", screen)
        self.assertIn("SubAgentPresetCard(preset, editable", screen)
        card = source("ui/components/SubAgentPresetCard.kt")
        for text in ("个子代理", "自动委派已开启", "自动委派已关闭", "正在读取子代理组…", "重试读取子代理组"):
            self.assertIn(text, card)

    def test_title_intro_removed_while_model_selection_and_status_remain(self):
        screen = source("title/TitleModelSettingsPanel.kt")
        self.assertNotIn("为新会话自动生成简短标题。", screen)
        self.assertNotIn('item("title-model-intro")', screen)
        for text in ('item("title-model-status")', 'SettingsSwitchRow("使用自定义模型"',
                     'summary = if (settings.enabled) modelLabel ?: "选择模型" else "当前使用的模型"', "else onDisable()", "else onChooseModel"):
            self.assertIn(text, screen)

    def test_task_surface_both_static_notes_removed_while_selection_and_failures_remain(self):
        screen = source("ui/screens/settings/TaskSurfaceSettingsPanel.kt")
        for text in ("仅影响之后开始的任务，当前任务不变。选择执行位置不等于授权；具体操作仍需相应权限和确认。",
                     "后台不可用时不会执行，也不会自动切到前台。操作代鱼自身时使用前台，但不改变这里的设置。"):
            self.assertNotIn(text, screen)
        for text in ("TaskSurfacePreference.entries", '"前台执行"', '"后台执行"', '"每次运行询问"',
                     "selected = draft == value", "saveSelection(value)", "if (failed) item", "if (busy) item"):
            self.assertIn(text, screen)

    def test_backup_static_copy_removed_without_removing_dynamic_feedback(self):
        screen = source("ui/screens/settings/BackupScreen.kt")
        self.assertNotIn("备份文件可能包含个人数据，请妥善保管。导入前请确认来源可信。", screen)
        for text in ('if (busy) SettingsNote("处理中")', "SettingsNote(statusText)",
                     "SettingsNote(errorText, error = true)", '"包含完整 Linux 环境"'):
            self.assertIn(text, screen)

        self.assertNotIn("保存当前数据备份", screen)
        self.assertNotIn("从备份文件恢复数据", screen)

    def test_model_usage_empty_hint_removed_without_hiding_filter_empty_feedback(self):
        screen = source("ui/screens/stats/UsageStatsScreen.kt")
        pane = screen.split("private fun ModelUsagePane(", 1)[1].split("private fun ", 1)[0]
        self.assertNotIn("R.string.stats_model_empty", pane)
        self.assertIn("if (startBound.isSet || endBound.isSet) Text(", pane)
        self.assertIn("stringResource(R.string.stats_model_filter_empty)", pane)
        for text in ('"usage-model-total"', '"usage-model-coverage"', "ModelUsageFilterCard(", "UsageTokenRows("):
            self.assertIn(text, pane)


if __name__ == "__main__":
    unittest.main()
