"""Voice names use a separate metadata operation, never a second paid clone."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/app/daiyu/android"
class VoiceNameSyncContractTest(unittest.TestCase):
    def test_pending_is_durable_before_upload_and_old_indexes_default_false(self):
        source = (SRC / "voice/account/PersonalVoices.kt").read_text()
        self.assertIn('val nameSyncPending: Boolean = false', source)
        self.assertIn('.put("nameSyncPending", v.nameSyncPending)', source)
        self.assertIn('j.optBoolean("nameSyncPending")', source)
        create = source[source.index('suspend fun create('):source.index('private suspend fun request(')]
        self.assertLess(create.index('nameSyncPending = id.startsWith("S_")'), create.index('request("voice_clone"'))
        self.assertLess(create.index('save(pending, false)'), create.index('request("voice_clone"'))

    def test_alias_is_separate_and_scoped_to_original_exact_resource(self):
        source = (SRC / "voice/account/DoubaoVoiceAliases.kt").read_text()
        for token in ('"ResourcePacksStatus"', '"AliasResourcePack"', 'Version=2025-05-20',
                      'pack.optString("TrainID") != id', 'check(matched == null)',
                      '.put("TrainID", id).put("InstanceNumber", instance).put("Alias", name)',
                      'resource.optString("Alias") == name', 'listOf(ak, sk)'):
            self.assertIn(token, source)
        for forbidden in ('voice_clone', 'X-Api-Key', '10029', 'Thread.sleep', 'delay(', 'retry('):
            self.assertNotIn(forbidden, source)

    def test_panel_uses_saved_account_credentials_and_separates_feedback(self):
        panel = (SRC / "VoiceAccountPanel.kt").read_text()
        helper = panel[panel.index('suspend fun syncName('):panel.index('fun beginFlow(')]
        self.assertIn('resources.preferences.load(voice.account)', helper)
        self.assertIn('saved?.keyId.orEmpty()', helper)
        self.assertIn('saved?.secret.orEmpty()', helper)
        self.assertIn('saved?.project.orEmpty()', helper)
        self.assertIn('catch (cancelled: CancellationException) { throw cancelled }', helper)
        self.assertNotIn('resources.voices.create(', helper)
        self.assertEqual(1, panel.count('resources.voices.create('))
        self.assertIn('声音已提交，名称同步失败', panel)
        self.assertIn(' · 名称待同步', panel)

    def test_existing_query_action_retries_metadata_without_reupload(self):
        panel = (SRC / "VoiceAccountPanel.kt").read_text()
        query = panel[panel.index('"QUERY" -> execute'):panel.index('"REMOVE" -> if')]
        self.assertNotIn('ask(', query)
        self.assertIn('重试名称同步', panel)
        self.assertIn('if (!latestVoice.nameSyncPending || latestVoice.uncertain || !latestVoice.tts)', query)
        self.assertIn('resources.voices.refresh(latestVoice, speech!!)', query)
        self.assertIn('it.nameSyncPending && !it.uncertain', query)
        self.assertIn('trySyncName(it)', query)
        for forbidden in ('create(', 'readSample(', 'Sample.of(', 'postpaid ='):
            self.assertNotIn(forbidden, query)

    def test_backend_does_not_conflate_name_and_training_status(self):
        source = (SRC / "voice/account/PersonalVoices.kt").read_text()
        operation = source[source.index('suspend fun syncName('):source.index('suspend fun refresh(')]
        for token in ('account.fingerprint == voice.account', 'if (!current.nameSyncPending) return',
                      'require(!current.uncertain)', 'DoubaoVoiceAliases(transport).rename(',
                      'takeIf { it.name == current.name }', 'it.copy(nameSyncPending = false)',
                      'finally { finish(voice.account, voice.id) }'):
            self.assertIn(token, operation)
        for forbidden in ('status =', 'uncertain =', 'copy(name =', 'voice_clone', 'removeLocal('):
            self.assertNotIn(forbidden, operation)

    def test_android_fixture_covers_pending_menu_failure_and_zero_side_effects(self):
        test = (ROOT / "app/src/androidTest/kotlin/app/daiyu/android/VoiceQuotaSelectionActivityTest.kt").read_text()
        case = test.split('fun testPendingNameShowsRetryWithoutImplicitRequestsOrRecreation()', 1)[1].split('fun testRecordHasIconActions', 1)[0]
        for token in ('namePending = true', 'settings-radio:重试名称同步', 'assertCountEquals(2)',
                      'assertTrue(voice.nameSyncPending)', 'assertFalse(voice.uncertain)', 'assertNoSideEffects()'):
            self.assertIn(token, case)
