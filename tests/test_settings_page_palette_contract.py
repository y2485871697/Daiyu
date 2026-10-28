"""Settings-only fallback styling must not leak into terminal or approval pages."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'


def read(name):
    return (SRC / name).read_text()


class SettingsPagePaletteContractTest(unittest.TestCase):
    def test_shizuku_settings_and_run_approval_keep_separate_shells(self):
        page = read('ui/screens/shizuku/ShizukuScreen.kt')
        self.assertIn('if (session == null) SettingsScaffoldPage(', page)
        self.assertIn(') else MiuixScaffoldPage(', page)
        self.assertEqual(2, page.count('actions = actions, content = content,'))
        self.assertEqual(1, page.count('item("service")'))
        self.assertEqual(1, page.count('item("approval")'))
        settings_branch, approval_branch = page.split('if (session == null) SettingsScaffoldPage(', 1)[1].split(') else MiuixScaffoldPage(', 1)
        self.assertNotIn('compactTitle = false', settings_branch)
        self.assertNotIn('compactTitle = true', approval_branch.split('\n}', 1)[0])
        self.assertIn('compactTitle: Boolean = true', read('ui/screens/settings/SettingsScaffold.kt'))
        self.assertIn('compactTitle: Boolean = false', read('ui/components/MiuixScaffoldPage.kt'))

    def test_generation_and_library_fallbacks_use_settings_shell(self):
        host = read('ui/GenerationSettingsHost.kt')
        self.assertIn('SettingsScaffoldPage(title = "独立生成模型设置"', host)
        self.assertNotIn('MiuixScaffoldPage', host)
        main = read('MainActivity.kt')
        self.assertIn('else app.daiyu.android.ui.screens.settings.SettingsScaffoldPage("旧版兼容资料库"', main)

    def test_linux_and_workspace_opt_in_without_restyling_normal_terminal(self):
        main = read('MainActivity.kt')
        loading = main.split('@Composable private fun TerminalLoadingPage(', 1)[1].split('private fun openLinuxSettings(', 1)[0]
        self.assertIn('settingsStyle: Boolean = false', loading)
        self.assertIn('if (settingsStyle) app.daiyu.android.ui.screens.settings.SettingsScaffoldPage(', loading)
        self.assertIn(') else app.daiyu.android.ui.components.MiuixScaffoldPage(', loading)
        self.assertEqual(1, main.count('compactTitle = true, settingsStyle = true)'))
        terminal = main.split('"terminal" -> terminalPageOwner?.let', 1)[1].split('"linux-settings", "chat-workspace" ->', 1)[0]
        self.assertNotIn('settingsStyle', terminal)
        self.assertIn('closeTerminalPage()', terminal)

    def test_usage_changes_shell_not_chart_cards_or_palette(self):
        stats = read('ui/screens/stats/UsageStatsScreen.kt')
        self.assertIn('    SettingsScaffoldPage(', stats)
        self.assertIn('return@SettingsScaffoldPage', stats)
        self.assertIn('UsageSurface as Card', stats)
        self.assertIn('ChatHeatmap(', stats)
        self.assertNotIn('MiuixScaffoldPage', stats)


if __name__ == '__main__':
    unittest.main()
