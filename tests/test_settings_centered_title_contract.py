"""Settings title wiring only; pixel geometry is covered by Android tests."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'app/src/main/kotlin/app/daiyu/android'


def read(path):
    return (SOURCE / path).read_text()


class SettingsCenteredTitleContract(unittest.TestCase):
    def test_settings_list_and_custom_content_both_select_linux_style(self):
        settings = read('ui/screens/settings/SettingsScaffold.kt')
        self.assertIn('compactTitle: Boolean = true', settings)
        self.assertIn('compactTitle = compactTitle', settings)
        self.assertIn('content = content', settings)
        custom = settings.split('internal fun SettingsScaffold(', 1)[1]
        self.assertIn('compactTitle = true', custom)

    def test_generic_scaffolds_keep_non_settings_defaults_and_forward_the_choice(self):
        source = read('ui/components/MiuixScaffoldPage.kt')
        for name in ('MiuixScaffoldPage', 'MiuixScaffold'):
            function = source.split(f'fun {name}(', 1)[1].split('\n@Composable', 1)[0]
            self.assertIn('compactTitle: Boolean = false', function)
            self.assertIn('compactTitle = compactTitle', function)
            self.assertIn('navigationIcon = { MiuixBackButton(onClick = onBack) }', function)
            self.assertIn('actions = actions', function)
        adaptive = read('ui/components/AdaptiveTopAppBar.kt')
        self.assertIn('if (compactTitle || rememberIsWideScreen())', adaptive)
        self.assertIn('SmallTopAppBar(', adaptive)

    def test_direct_settings_routes_use_the_same_compact_title(self):
        for path in ('ui/screens/settings/SettingsHomeScreen.kt',
                     'ui/screens/settings/WorkspaceSettingsScreen.kt',
                     'ui/screens/stats/UsageStatsScreen.kt',
                     'VoicePersonalScaffold.kt', 'ui/GenerationSettingsHost.kt'):
            with self.subTest(path=path):
                self.assertIn('compactTitle = true', read(path))
        self.assertIn('if (session == null) SettingsScaffoldPage(', read('ui/screens/shizuku/ShizukuScreen.kt'))
        self.assertNotIn('compactTitle = integratedLinux', read('ui/screens/settings/WorkspaceSettingsScreen.kt'))

    def test_loading_and_unavailable_settings_do_not_revert_to_large_titles(self):
        main = read('MainActivity.kt')
        self.assertIn('onBack = { retainedPage = "backup" }, compactTitle = true)', main)
        self.assertIn('title = if (route == "chat-workspace") "工作区" else "Linux 工具环境", compactTitle = true, settingsStyle = true)', main)
        loading = main.split('@Composable private fun TerminalLoadingPage(', 1)[1].split('private fun openLinuxSettings', 1)[0]
        self.assertIn('compactTitle: Boolean = false', loading)
        self.assertIn('compactTitle = compactTitle', loading)

    def test_screenshot_pages_inherit_the_setting_without_replacing_actions(self):
        for name in ('AppearanceSettingsScreen.kt', 'SkillManagementScreen.kt'):
            page = read('ui/screens/settings/' + name)
            self.assertIn('SettingsScaffoldPage(', page)
            self.assertNotIn('compactTitle = false', page)
        skills = read('ui/screens/settings/SkillManagementScreen.kt')
        self.assertIn('title = "技能"', skills)
        self.assertIn('onBack = onBack', skills)
        self.assertIn('if (engineeringUi) IconButton(onClick = controller::refresh', skills)


if __name__ == '__main__':
    unittest.main()
