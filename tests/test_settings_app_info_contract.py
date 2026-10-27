"""Source guards for the settings App info subpage; Android tests cover navigation."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
SETTINGS = ROOT / "app/src/main/kotlin/app/daiyu/android/ui/screens/settings"


class SettingsAppInfoContractTest(unittest.TestCase):
    def setUp(self):
        self.catalog = (SETTINGS / "SettingsCatalog.kt").read_text()
        self.home = (SETTINGS / "SettingsHomeScreen.kt").read_text()

    def test_app_info_is_a_local_page_not_an_external_action(self):
        self.assertRegex(self.catalog, r"enum class SettingsLocalPage \{[^}]*\bAPP_INFO\b")
        self.assertRegex(self.catalog, r'SettingsEntry\("app_info", "APP 信息与更新"[\s\S]*?localPage = SettingsLocalPage.APP_INFO')

    def test_home_about_group_only_exposes_the_app_info_entry(self):
        visible = self.catalog.split("fun visibleGroups", 1)[1].split("val entries: List<SettingsEntry>", 1)[0]
        self.assertIn('group.id == "about"', visible)
        self.assertIn('it.id == "app_info"', visible)

    def test_all_original_about_entries_remain_in_the_subpage_inventory(self):
        about = self.catalog.split('SettingsGroup("about"', 1)[1].split("private val hiddenEntryIds", 1)[0]
        ids = re.findall(r'SettingsEntry\("([^"]+)"', about)
        self.assertEqual(ids, ["app_info", "updates", "telegram", "qq", "source", "relay", "relay_st", "relay_moyu", "donate"])
        self.assertRegex(self.catalog, r'val appInfoEntries[^\n]*')
        self.assertIn('filterNot { it.id == "app_info" }', self.catalog)

    def test_subpage_reuses_settings_style_and_retained_rows(self):
        self.assertIn('openedPage == SettingsLocalPage.APP_INFO', self.home)
        self.assertIn('SettingsScaffoldPage(title = "APP 信息与更新"', self.home)
        self.assertIn('SettingsHomeRows(SettingsCatalog.appInfoEntries', self.home)
        self.assertIn('BackHandler(onBack = localBack)', self.home)

    def test_update_result_is_owned_by_app_info_not_settings_home(self):
        self.assertIn('ownerCurrent = { visit.active && localPage == SettingsLocalPage.APP_INFO }', self.home)
        self.assertIn('"updates" -> release.check()', self.home)

    def test_actions_keep_current_page_lifecycle_and_donation_guards(self):
        self.assertIn('val actionPage = localPage', self.home)
        self.assertIn('localPage != actionPage', self.home)
        self.assertIn('Lifecycle.State.RESUMED', self.home)
        self.assertIn('"telegram", "qq", "relay_st", "relay_moyu" -> openRetainedSettingsLink(context, entry.id)', self.home)
        self.assertIn('onClick = if (entry.id == "donate") null else onClick', self.home)


if __name__ == "__main__":
    unittest.main()
