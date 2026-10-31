"""Source wiring contracts; pixel and persistence behavior need Android execution."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'
SETTINGS = SRC / 'ui/screens/settings'


class SettingsSwitchBusyContractTest(unittest.TestCase):
    def test_busy_locks_row_but_does_not_override_switch_availability(self):
        text = (SETTINGS / 'SettingsUiComponents.kt').read_text()
        row = text.split('internal fun SettingsSwitchRow(', 1)[1].split('internal fun SettingsRadioRow(', 1)[0]
        self.assertIn('busy: Boolean = false', row)
        self.assertIn('enabled = enabled && !busy', row)
        switch = row.split('        Switch(', 1)[1]
        self.assertIn('enabled = enabled,', switch)
        self.assertIn('onCheckedChange = null', switch)
        self.assertNotIn('enabled = true', row)
        self.assertNotIn('disabledChecked', row)

    def test_compression_load_and_save_have_distinct_presentation(self):
        text = (SETTINGS / 'CompressionSettingsScreen.kt').read_text()
        self.assertIn('mutableStateOf(false)', text.split('var saving', 1)[1].split('\n', 1)[0])
        self.assertIn('submit(it, save = false)', text)
        self.assertEqual(text.count('save = true'), 2)
        self.assertIn('saving = save', text)
        self.assertIn('enabled = !busy || saving, saving = busy && saving', text)
        self.assertEqual(text.count('saving = false'), 3)

    def test_compression_form_passes_save_lock_to_both_switches_and_other_actions(self):
        text = (SETTINGS / 'CompressionSettingsForm.kt').read_text()
        self.assertIn('saving: Boolean = false', text)
        self.assertEqual(text.count('busy = saving'), 3)
        self.assertIn('"compression-protocol", enabled', text)
        self.assertIn('selectedIndex = selected, busy = saving', text)
        self.assertIn('if (enabled && !saving) onChooseModel else null', text)

    def test_compression_confirmation_owner_and_failure_guards_stay(self):
        text = (SETTINGS / 'CompressionSettingsScreen.kt').read_text()
        self.assertIn('if (!page.active || !currentOwner()) return@post', text)
        self.assertIn('if (!editor.isCurrent(completed)) return@onSuccess', text)
        self.assertGreaterEqual(text.count('page.active && currentOwner() && !busy'), 2)
        self.assertIn('worker.shutdown()', text)
        self.assertIn('editor.revoke()', text)
        self.assertIn('不会自动重试', text)
        self.assertNotIn('draft = requested', text)

    def test_title_load_stays_disabled_and_saving_is_separate(self):
        host = (SRC / 'title/TitleModelSettingsScreen.kt').read_text()
        panel = (SRC / 'title/TitleModelSettingsPanel.kt').read_text()
        self.assertIn('saving = saved', host)
        self.assertIn('saving = false', host)
        self.assertIn('busy = busy, saving = saving', host)
        self.assertIn('settings.enabled, !busy || saving', panel)
        self.assertIn('busy = busy && saving', panel)
        self.assertIn('onClick = if (busy) null else onChooseModel', panel)

    def test_independent_model_and_pet_preserve_initial_load_and_closed_host(self):
        text = (SETTINGS / 'IndependentModelScreen.kt').read_text()
        self.assertEqual(text.count('saving = true'), 2)
        self.assertEqual(text.count('finally { busy = false; saving = false }'), 2)
        load = text.split('LaunchedEffect(store, slot)', 1)[1].split('fun applyPetModel', 1)[0]
        self.assertNotIn('saving = true', load)
        self.assertIn('controls.enabled, !controls.busy || controls.saving', text)
        self.assertIn('busy = controls.saving', text)
        pet = (SETTINGS / 'PetSettingsScreen.kt').read_text()
        self.assertIn('enabled && (!model.busy || model.saving)', pet)
        self.assertIn('busy = model.saving', pet)
        self.assertIn('enabled = !snapshot.closed', pet)

    def test_voice_transient_work_uses_busy_without_changing_its_guards(self):
        text = (SRC / 'VoiceSettingsPanel.kt').read_text()
        calls = re.findall(r'SettingsSwitchRow\([^\n]+', text)
        self.assertEqual(len(calls), 6)
        self.assertEqual(sum('busy = busy' in call for call in calls), 5)
        self.assertIn('if (!current() || busy) return', text)

    def test_android_regressions_cover_pending_pixels_lock_load_failure_and_no_retry(self):
        text = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/screens/settings/SettingsSwitchBusyAppearanceTest.kt').read_text()
        self.assertEqual(len(re.findall(r'fun test\w+\(', text)), 5)
        self.assertIn('listOf(false, true)) for (checked in listOf(false, true))', text)
        self.assertIn('differentPixels(before, captureScreenNode(untouched))', text)
        self.assertIn('StoreGate(fail = true)', text)
        self.assertIn('StoreGate(openingsToSkip = 1)', text)
        self.assertIn('assertEquals(previousCalls + 2, calls.get())', text)
        self.assertIn('original.addSuppressed(caught)', text)
        self.assertIn('assertNull(editor.prepareLoad())', text)
        self.assertIn('assertEquals(previousCalls + 1, calls.get())', text)
        self.assertIn('finally { saving.release.countDown() }', text)
        self.assertNotRegex(text, r'Thread\.sleep|delay\(')


if __name__ == '__main__':
    unittest.main()
