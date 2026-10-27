"""Creation quota chooser and explicit-sync-only side-effect contracts."""
from pathlib import Path
import unittest
from test_voice_creation_cards_contract import block

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'


class VoiceQuotaSelectionContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.panel = (SRC / 'VoiceAccountPanel.kt').read_text()
        cls.first = block(cls.panel, 'if (page == "CREATE" && step == 0)')
        cls.row = block(cls.first, 'item { SettingsCard(Modifier.testTag("voice-create-sync-card"))')
        cls.sheet = (block(cls.panel, 'creationSlots.forEachIndexed')
                     if 'creationSlots.forEachIndexed' in cls.panel else '')

    def test_only_creation_replaces_inline_choices_with_one_shared_sheet_entry(self):
        self.assertIn('SettingsRow("选择音色名额"', self.row)
        self.assertNotIn('查找我的音色名额', self.first)
        self.assertNotIn('SettingsRadioRow(', self.first)
        self.assertNotIn('voice-create-slots-card', self.first)
        self.assertIn('OptionSelectionSheet(', self.panel)
        self.assertIn('OptionSelectionItem(', self.sheet)
        self.assertIn('onDismiss = { slotPicker = false }', self.panel)
        self.assertIn('personalVoiceSlotTitle(voice, index)', self.sheet)
        self.assertIn('voiceSlotSummary(voice)', self.sheet)
        self.assertIn('personalVoiceSlotTitle(it.value, it.index)', self.row)

    def test_no_usable_quota_goes_to_sync_without_fetching_or_saving(self):
        self.assertIn('if (current() && !busy)', self.row)
        self.assertIn('if (resources.voices.state.value.any { it.account == account?.fingerprint &&', self.row)
        self.assertIn('else navigate("SYNC")', self.row)
        self.assertIn('personalVoiceSlots(voices, creating = true, postpaid = false)', self.panel)
        self.assertIn('.filter { it.id.matches(Regex("S_[A-Za-z0-9_-]+")) }', self.panel)
        self.assertIn('val voices = allVoices.filter { it.account == account?.fingerprint }', self.panel)
        for text in (self.row, self.sheet, block(self.panel, 'fun navigate(target: String)')):
            for effect in ('execute(', 'ask(', 'importPurchased(', '.save(', 'refreshProjects(', '.create(', '.load('):
                self.assertNotIn(effect, text)

    def test_selection_rechecks_owner_state_and_only_updates_the_creation_draft(self):
        for required in ('selected = !postpaid && slotId == voice.id', 'enabled = enabled && voice.canTrain',
                         'if (enabled && !busy && current() && page == "CREATE" && step == 0 &&',
                         'resources.voices.state.value.any { it.account == account?.fingerprint && it.id == voice.id && it.canTrain }',
                         'slotId = voice.id; postpaid = false; confirmation = null; slotPicker = false'):
            self.assertIn(required, self.sheet)
        self.assertIn('var slotPicker by remember(frozenConfiguration.revision)', self.panel)
        self.assertIn('slotPicker = false', block(self.panel, 'fun navigate(target: String)'))
        self.assertIn('slotPicker = false', block(self.panel, 'fun back()'))
        self.assertIn('slotPicker = false; confirmation = null; operation?.cancel(); preview.stop()', self.panel)
        self.assertIn('if (target == "SYNC" || target == "ACCOUNT") parentPage = page', self.panel)
        self.assertIn('page = parentPage', block(self.panel, 'fun back()'))

    def test_sync_still_requires_explicit_read_and_save_actions(self):
        sync = block(self.panel, 'if (page == "SYNC")')
        read = block(sync, 'SettingsCard(Modifier.testTag("voice-sync-read-card"))')
        save = block(sync, 'SettingsCard(Modifier.testTag("voice-sync-storage-card"))')
        self.assertIn('SettingsRow("读取我的名额"', read)
        self.assertIn('ask(', read)
        self.assertEqual(1, read.count('resources.voices.importPurchased('))
        self.assertNotIn('.save(', read)
        self.assertLess(save.index('refreshProjects(a, s, preferred)'), save.index('resources.preferences.save('))
        self.assertNotIn('importPurchased(', save)
        imported = block(self.panel, 'if (page == "IMPORT") item')
        self.assertIn('查找我的音色名额', imported)
        self.assertIn('personalVoiceSlots(voices, creating = false, postpaid = postpaid)', imported)


if __name__ == '__main__':
    unittest.main()
