"""Creation cards and explicit, read-only cloud import selection contracts."""
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
        cls.imported = block(cls.panel, 'if (page == "IMPORT") item')
        cls.import_sheet = (block(cls.panel, 'importSlots.forEachIndexed')
                            if 'importSlots.forEachIndexed' in cls.panel else '')
        cls.sheet = (block(cls.panel, 'creationSlots.forEachIndexed')
                     if 'creationSlots.forEachIndexed' in cls.panel else '')

    def test_creation_replaces_inline_choices_with_one_shared_sheet_entry(self):
        self.assertIn('SettingsRow("选择音色名额"', self.row)
        self.assertNotIn('查找我的音色名额', self.first)
        self.assertNotIn('SettingsRadioRow(', self.first)
        self.assertNotIn('voice-create-slots-card', self.first)
        self.assertIn('OptionSelectionSheet(', self.panel)
        self.assertIn('OptionSelectionItem(', self.sheet)
        self.assertIn('onDismiss = { slotPicker = false; operation?.cancel() }', self.panel)
        self.assertIn('personalVoiceSlotTitle(voice, index)', self.sheet)
        self.assertIn('voiceSlotSummary(voice)', self.sheet)
        self.assertIn('personalVoiceSlotTitle(it.value, it.index)', self.row)

    def test_creation_refreshes_cloud_without_importing_and_keeps_unconfigured_fallback(self):
        self.assertIn('if (current() && !busy)', self.row)
        self.assertIn('if (resources.voices.state.value.any { it.account == account?.fingerprint &&', self.row)
        self.assertIn('else navigate("SYNC")', self.row)
        self.assertNotIn('&& it.canTrain', self.row)
        self.assertIn('personalVoiceSlots(creationCandidates ?: voices, creating = true, postpaid = false)', self.panel)
        self.assertIn('.filter { it.id.matches(Regex("S_[A-Za-z0-9_-]+")) }', self.panel)
        self.assertIn('val voices = allVoices.filter { it.account == account?.fingerprint }', self.panel)
        for required in ('creationCandidates = emptyList(); slotPicker = true',
                         'resources.voices.listTrainingSlots(speech!!, a, s, p)',
                         'currentCoroutineContext().ensureActive()',
                         'current() && page == "CREATE" && step == 0 && slotPicker',
                         'ak == a && sk == s && project == p', 'creationCandidates = fetched'):
            self.assertIn(required, self.row)

        for text in (self.row, self.sheet, block(self.panel, 'fun navigate(target: String)')):
            for effect in ('ask(', 'importPurchased(', 'importExisting(', '.save(', 'refreshProjects(', '.create(', '.load('):
                self.assertNotIn(effect, text)

    def test_selection_rechecks_owner_state_and_only_updates_the_creation_draft(self):
        for required in ('selected = !postpaid && slotId == voice.id', 'enabled = enabled && voice.canTrain',
                         'if (enabled && !busy && current() && page == "CREATE" && step == 0 &&',
                         'creationSlots.any { it.account == account?.fingerprint && it.id == voice.id && it.canTrain }',
                         'slotId = voice.id; manualSlotId = ""; postpaid = false; manualId = false; confirmation = null'):
            self.assertIn(required, self.sheet)
        self.assertIn('var slotPicker by remember(frozenConfiguration.revision)', self.panel)
        self.assertIn('slotPicker = false', block(self.panel, 'fun navigate(target: String)'))
        self.assertIn('slotPicker = false', block(self.panel, 'fun back()'))
        self.assertIn('slotPicker = false; importSlotPicker = false; confirmation = null; operation?.cancel(); preview.stop()', self.panel)
        self.assertIn('if (target == "SYNC" || target == "ACCOUNT") parentPage = page', self.panel)
        self.assertIn('page = parentPage', block(self.panel, 'fun back()'))

    def test_existing_clones_remain_in_list_and_warn_about_original_quota_overwrite(self):
        slots = self.panel.split('internal fun personalVoiceSlots(', 1)[1].split('internal fun personalVoiceSlotTitle(', 1)[0]
        self.assertIn('if (creating) !postpaid && it.id.startsWith("S_") else !it.unused', slots)
        self.assertNotIn('it.canTrain', slots)
        self.assertIn('compareBy<PersonalVoices.Voice> { it.unused }', slots)
        self.assertIn('已激活，无法再次制作', self.panel)
        final = block(self.panel, 'if (page == "CREATE" && step == 2)')
        self.assertIn('voiceSlotSummary(voice)', self.sheet)
        self.assertIn('resources.voices.create(speech!!, sample, n, slot, confirmed = true)', final)
        self.assertIn('再次制作消耗本名额次数并覆盖已有声音', self.panel)

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
        self.assertIn('选取我的名额', self.imported)
        self.assertIn('val importSlots = importCandidates.filter { it.canImport }', self.panel)

    def test_import_queries_cloud_on_each_explicit_open_and_only_selects_verified_clones(self):
        entry = self.imported.split('SettingsRow("选取我的名额"', 1)[-1].split('SettingsRow("备用方式', 1)[0]
        self.assertIn('importSlots.withIndex()', entry)
        self.assertIn('if (current() && !busy)', entry)
        self.assertIn('importCandidates = emptyList(); importSlotPicker = true', entry)
        self.assertIn('ak.isNotBlank() && sk.isNotBlank() && project.isNotBlank()', entry)
        self.assertEqual(1, entry.count('resources.voices.listImportable(speech!!, a, s, p)'))
        self.assertIn('currentCoroutineContext().ensureActive()', entry)
        self.assertIn('current() && page == "IMPORT" && importSlotPicker && feedback.visit == origin', entry)
        self.assertIn('ak == a && sk == s && project == p', entry)
        self.assertIn('importCandidates = fetched', entry)
        self.assertNotIn('resources.voices.state.value', entry)
        self.assertNotIn('SettingsRadioRow(', self.imported)
        self.assertNotIn('选择我的音色名额', self.imported)
        for text in (entry, self.import_sheet):
            for effect in ('importExisting(', 'importPurchased(', '.save(', 'refreshProjects(', '.create(', '.load('):
                self.assertNotIn(effect, text)
        self.assertIn('selected = existingId == voice.id', self.import_sheet)
        self.assertIn('enabled = enabled, cardStyle = true', self.import_sheet)
        self.assertIn('page == "IMPORT"', self.import_sheet)
        self.assertIn('importCandidates.any { it.account == account?.fingerprint && it.id == voice.id && it.canImport }', self.import_sheet)
        self.assertIn('existingId = voice.id; confirmation = null', self.import_sheet)
        self.assertNotIn('importSlotPicker = false', self.import_sheet)
        self.assertNotIn('canTrain', self.import_sheet)
        self.assertNotIn('name =', self.import_sheet)
        self.assertNotIn('voiceSlotSummary(', self.import_sheet)
        for marker in ('fun navigate(target:', 'fun back()'):
            self.assertIn('importSlotPicker = false', block(self.panel, marker))
        self.assertIn('var importSlotPicker by remember(frozenConfiguration.revision)', self.panel)
        self.assertIn('onDismiss = { importSlotPicker = false; operation?.cancel() }', self.panel)
        self.assertIn('云端暂无可导入的已复刻声音。', self.panel)
        self.assertIn('正在查询云端已复刻声音…', self.panel)

    def test_import_backend_is_read_only_and_final_import_rechecks_success(self):
        store = (SRC / 'voice/account/PersonalVoices.kt').read_text()
        self.assertIn('suspend fun listImportable(', store)
        query = block(store, 'suspend fun listImportable(')
        self.assertIn('DoubaoVoiceCatalog(transport).list(ak, sk, project)', query)
        self.assertIn('it.state in setOf("Success", "Active")', query)
        self.assertIn('val candidate = Voice(slot.id, slot.name, account.fingerprint', query)
        self.assertIn('request("get_voice", account.key, identity(candidate))', query)
        self.assertIn('verified.takeIf { it.canImport }', query)
        for effect in ('save(', 'persist(', 'begin(', 'voice_clone', 'state.value'):
            self.assertNotIn(effect, query)
        final = block(store, 'suspend fun importExisting(')
        self.assertLess(final.index('request("get_voice"'), final.index('require(result.canImport)'))
        self.assertLess(final.index('require(result.canImport)'), final.index('save(imported)'))
        self.assertNotIn('voice_clone', final)
        self.assertNotIn('切换为“全部”或“未复刻”', self.imported)

    def test_training_discovery_is_read_only_and_next_reuses_the_same_mapping(self):
        store = (SRC / 'voice/account/PersonalVoices.kt').read_text()
        discovery = block(store, 'suspend fun listTrainingSlots(')
        self.assertIn('DoubaoVoiceCatalog(transport).list(ak, sk, project)', discovery)
        self.assertIn('currentCoroutineContext().ensureActive()', discovery)
        self.assertIn('find(slot.id, config)', discovery)
        self.assertIn('catalogState = slot.state', discovery)
        self.assertIn('remaining = slot.remaining.takeIf { it >= 0 } ?: voice.remaining', discovery)
        for effect in ('save(', 'persist(', 'begin(', 'voice_clone', 'request(', 'importPurchased('):
            self.assertNotIn(effect, discovery)
        single = block(store, 'suspend fun findTrainingSlot(')
        self.assertIn('listTrainingSlots(config, ak, sk, project).firstOrNull { it.id == valid }', single)

    def test_quota_cards_opt_in_without_changing_other_choice_defaults(self):
        item = (SRC / 'ui/components/OptionSelectionSheet.kt').read_text()
        for token in ('cardStyle: Boolean = false', 'itemSpacing: Dp = 3.dp',
                      'RoundedCornerShape(16.dp)', 'RoundedCornerShape(12.dp)',
                      'cardStyle && selected -> SolidColor(colors.selectionContainer)', 'cardStyle -> Brush.linearGradient(colors.sheet)',
                      'Modifier.border(1.dp', 'colors.selectionContent', 'colors.edge',
                      'Arrangement.spacedBy(itemSpacing)', 'Role.RadioButton',
                      'selected = selected, enabled = enabled', 'heightIn(min = 48.dp)'):
            self.assertIn(token, item)
        self.assertEqual(2, self.panel.count('itemSpacing = 8.dp'))
        self.assertIn('cardStyle = true', self.sheet)
        self.assertIn('cardStyle = true', self.import_sheet)


if __name__ == '__main__':
    unittest.main()
