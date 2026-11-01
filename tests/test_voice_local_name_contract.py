"""Personal voice names are local metadata, never cloud mutations or extra training."""
from pathlib import Path
import unittest
from test_voice_creation_cards_contract import block
ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/app/daiyu/android"

class VoiceLocalNameContractTest(unittest.TestCase):
    def test_cloud_name_sync_is_removed_from_production(self):
        production = "\n".join(p.read_text() for p in SRC.rglob("*.kt"))
        for removed in ('nameSyncPending', 'voiceNameSyncFailure', 'trySyncName',
                        'suspend fun syncName(', 'AliasResourcePack', '名称待同步', '重试名称同步',
                        '名称已同步到云端', '名称同步失败'):
            self.assertNotIn(removed, production)
        self.assertFalse((SRC / "voice/account/DoubaoVoiceAliases.kt").exists())

    def test_rename_is_scoped_local_storage_only(self):
        store = (SRC / "voice/account/PersonalVoices.kt").read_text()
        rename = block(store, '@Synchronized fun renameLocal(')
        for token in ('val displayName = name.trim()', 'displayName.isNotBlank()',
                      'it.account == voice.account && it.id == voice.id',
                      'current.copy(name = displayName)', 'preserveName = false'):
            self.assertIn(token, rename)
        for forbidden in ('request(', 'transport', 'begin(', 'SpeechProviderConfig', 'voice_clone', 'ak:', 'sk:'):
            self.assertNotIn(forbidden, rename)
        self.assertIn('name = if (preserveName) old?.name ?: voice.name else voice.name', store)

    def test_existing_voice_menu_wires_a_local_text_dialog(self):
        panel = (SRC / "VoiceAccountPanel.kt").read_text()
        for token in ('"RENAME" to "修改名称"', '"RENAME" -> renameVoice = latestVoice',
                      'key(voice.account, voice.id)', 'VoiceTextDialog("修改名称", voice.name,',
                      'resources.voices.renameLocal(voice, value)', 'onDismiss = { renameVoice = null }'):
            self.assertIn(token, panel)
        dialog = panel.split('VoiceTextDialog("修改名称", voice.name,', 1)[1].split('confirmation?.let', 1)[0]
        for forbidden in ('speech!!', 'preferences.load', 'previewGuard', 'ask(', 'resources.voices.create('):
            self.assertNotIn(forbidden, dialog)

    def test_creation_import_and_status_query_do_not_append_name_requests(self):
        panel = (SRC / "VoiceAccountPanel.kt").read_text()
        query = panel.split('"QUERY" ->', 1)[1].split('"RENAME" ->', 1)[0]
        self.assertIn('resources.voices.refresh(latestVoice, speech!!)', query)
        self.assertEqual(1, panel.count('resources.voices.importExisting('))
        self.assertEqual(1, panel.count('resources.voices.create('))
        self.assertIn('AccountField("声音名称", name, busy)', panel)
        self.assertIn('AccountField("备注名称（可空）", name, busy)', panel)
        self.assertIn('summary = voiceStatus(voice), showChevron = false', panel)

    def test_catalog_queries_and_initial_alias_defaults_remain(self):
        store = (SRC / "voice/account/PersonalVoices.kt").read_text()
        catalog = (SRC / "voice/account/DoubaoVoiceCatalog.kt").read_text()
        self.assertIn('DoubaoVoiceCatalog(transport).list(ak, sk, project)', store)
        self.assertIn('val voice = local ?: Voice(slot.id, slot.name, account.fingerprint)', store)
        self.assertIn('find(slot.id, config)?.name ?: slot.name', store)
        self.assertIn('BatchListMegaTTSTrainStatus', catalog)

    def test_legacy_pending_and_restart_and_late_query_have_behavior_tests(self):
        source = (ROOT / 'app/src/test/kotlin/app/daiyu/android/voice/account/PersonalVoiceLocalNameTest.kt').read_text()
        for name in ('legacyPendingAndNonpendingNamesSurviveLoadAndRestartWithoutRequests',
                     'renameOnlyPersistsLocalNameAndRetiresLegacyPendingField',
                     'localRenameIsScopedToExactAccountAndSpeaker',
                     'lateStatusQueryCannotOverwriteANewerLocalName',
                     'importingWithRemarkUsesOnlyTheExistingVoiceQuery',
                     'creatingOnlyUsesTheRequestedTrainingCallAndKeepsLocalName'):
            self.assertIn('fun ' + name + '()', source)

    def test_android_fixture_preserves_legacy_json_without_retry_or_cloud_writes(self):
        source = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/VoiceQuotaSelectionActivityTest.kt').read_text()
        for name in ('testImportWithRemarkOnlyNamesTheLocalRecord',
                     'testLegacyPendingNameCanBeRenamedLocallyWithoutCredentialsOrRequests',
                     'testLegacyPendingDoesNotChangeTheOrdinaryStatusQuery'):
            self.assertIn('fun ' + name + '()', source)
        self.assertNotIn('.nameSyncPending', source)
        self.assertNotIn('"AliasResourcePack" ->', source)
