"""Source contracts for the requested frontend-only Linux backup switch."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCREEN = ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/settings/BackupScreen.kt'


class BackupLinuxOptionContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = SCREEN.read_text()
        cls.row = cls.source.split('SettingsSwitchRow(', 1)[1].split('SettingsRow("导出数据"', 1)[0]

    def test_option_is_in_action_card_immediately_above_export_and_import(self):
        card = self.source.index('SettingsCard {')
        option = self.source.index('SettingsSwitchRow("包含完整 Linux 环境"')
        export = self.source.index('SettingsRow("导出数据"')
        importing = self.source.index('SettingsRow("导入数据"')
        self.assertLess(card, option)
        self.assertLess(option, export)
        self.assertLess(export, importing)
        self.assertEqual(1, self.source.count('SettingsSwitchRow('))

    def test_option_has_only_title_and_switch_without_description(self):
        self.assertIn('"包含完整 Linux 环境"', self.row)
        self.assertIn('tag = "backup-include-full-linux"', self.row)
        self.assertNotIn('summary', self.row)
        self.assertNotIn('SettingsNote', self.row)
        self.assertNotIn('leading', self.row)
        self.assertNotIn('Text(', self.row)

    def test_local_presentation_state_is_saveable_default_off(self):
        self.assertIn('var includeFullLinuxEnvironment by rememberSaveable { mutableStateOf(false) }', self.source)
        self.assertIn('checked = includeFullLinuxEnvironment', self.row)
        self.assertIn('{ includeFullLinuxEnvironment = it }', self.row)
        self.assertNotIn('SharedPreferences', self.source)
        self.assertNotIn('LaunchedEffect', self.source)

    def test_busy_lock_and_existing_callbacks_are_unchanged(self):
        self.assertIn('enabled = !busy', self.row)
        export = self.source.split('SettingsRow("导出数据"', 1)[1].split('SettingsRow("导入数据"', 1)[0]
        importing = self.source.split('SettingsRow("导入数据"', 1)[1]
        self.assertIn('tag = "backup-export"', export)
        self.assertIn('onClick = if (busy) null else onExportComplete', export)
        self.assertIn('tag = "backup-import"', importing)
        self.assertIn('onClick = if (busy) null else onImport', importing)
        if 'showChevron =' in self.source:
            components = SCREEN.with_name('SettingsUiComponents.kt').read_text()
            self.assertIn('showChevron: Boolean', components)
        self.assertIn('onImport: () -> Unit = onImportComplete', self.source)
        self.assertNotIn('onExportComplete(', self.row)
        self.assertNotIn('onImport(', self.row)

    def test_no_backend_or_host_scope_is_added_to_page(self):
        self.assertIn('onExportComplete: () -> Unit', self.source)
        self.assertNotIn('includeFullLinuxEnvironment:', self.source)
        self.assertNotIn('NeutralBackupService', self.source)
        self.assertNotIn('LinuxEnvironment', self.source.replace('includeFullLinuxEnvironment', 'localOption'))
        self.assertNotIn('java.io.', self.source)

    def test_android_regressions_cover_order_no_description_and_zero_data_actions(self):
        tests = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/BackupScreenTest.kt').read_text()
        for name in ('testLinuxOptionIsAboveExportWithoutDescriptionAndOnlyChangesLocalState',
                     'testLinuxOptionDoesNotReplaceExistingExportOrImportCallbacks',
                     'testBusyStateDisablesLinuxOptionAndKeepsDataActionsUnclickable'):
            self.assertIn('fun ' + name, tests)
        self.assertIn('option.assertTextEquals("包含完整 Linux 环境")', tests)
        self.assertIn('optionBounds.bottom <= exportBounds.top', tests)
        self.assertIn('exportBounds.bottom <= importBounds.top', tests)
        self.assertIn('AppearanceThemeMode.LIGHT, AppearanceThemeMode.DARK', tests)
        self.assertIn('option.performClick().assertIsOn()', tests)
        self.assertIn('option.performClick().assertIsOff()', tests)
        self.assertIn('assertEquals(0, exports.get())', tests)
        self.assertIn('assertEquals(0, imports.get())', tests)
        self.assertIn('assertEquals(before, fixture.database.exportBackup())', tests)
        self.assertIn('assertEquals(opens, fixture.opens.get())', tests)


if __name__ == '__main__':
    unittest.main()
