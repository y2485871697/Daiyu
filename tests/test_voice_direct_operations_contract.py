"""Direct account actions keep explicit consent and the existing execution fence."""
from pathlib import Path
import unittest
from test_voice_creation_cards_contract import block
ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'

class VoiceDirectOperationsContractTest(unittest.TestCase):
    def setUp(self):
        self.panel = (SRC / 'VoiceAccountPanel.kt').read_text()
        self.store = (SRC / 'voice/account/PersonalVoices.kt').read_text()

    def test_query_import_and_submit_use_the_existing_direct_executor(self):
        query = self.panel.split('"QUERY" ->', 1)[1].split('"REMOVE" ->', 1)[0]
        imported = block(self.panel, 'SettingsRow("导入声音",')
        create = block(self.panel, 'SettingsRow("确认上传并制作",')
        for action in (query, imported, create):
            self.assertIn('execute', action)
            self.assertNotIn('ask(', action)
            self.assertNotIn('scope.launch', action)
        self.assertIn('resources.voices.refresh(latestVoice, speech!!)', query)
        self.assertIn('resources.voices.importExisting(speech!!, id, n)', imported)
        self.assertIn('resources.voices.create(speech!!, sample, n, slot, confirmed = true)', create)
        self.assertIn('if (!enabled || !agreed || sampleUri == null) null', self.panel)
        self.assertIn('require(confirmed)', block(self.store, 'suspend fun create('))
        self.assertIn('"REMOVE" -> if (!latestVoice.uncertain) ask(', self.panel)
        self.assertIn('previewGuard.execute(::operationCurrent, { latestStrictStop() })', self.panel)

    def test_import_footer_is_removed_but_name_limit_and_safe_feedback_remain(self):
        self.assertNotIn('仅查询已有声音，不上传录音、不重新制作。备注名称最多 80 字。', self.panel)
        self.assertIn('name = it.take(80)', self.panel)
        imported = block(self.panel, 'SettingsRow("导入声音",')
        for token in ('val imported = resources.voices.importExisting(', 'trySyncName(it)',
                      '声音已导入，名称已同步到云端。', '声音已导入，名称同步失败',
                      'currentCoroutineContext().ensureActive()', 'check(current() && feedback.visit == origin)'):
            self.assertIn(token, imported)
        self.assertNotIn('resources.voices.create(', imported)

    def test_import_marks_supported_nonempty_remarks_before_persisting(self):
        imported = block(self.store, 'suspend fun importExisting(')
        self.assertIn('val displayName = name.trim()', imported)
        self.assertIn('DoubaoVoiceIdentity.isSlot(valid) && displayName.isNotBlank()', imported)
        self.assertIn('previous?.nameSyncPending == true', imported)
        self.assertLess(imported.index('require(result.canImport)'), imported.index('save(imported)'))
        self.assertNotIn('voice_clone', imported)
        self.assertIn('此自定义音色暂不支持云端改名', self.panel)

    def test_verified_import_is_accepted_and_saved_without_changing_creation_consent(self):
        imported = block(self.store, 'suspend fun importExisting(')
        self.assertLess(imported.index('require(result.canImport)'), imported.index('result.copy(accepted = true)'))
        self.assertLess(imported.index('result.copy(accepted = true)'), imported.index('save(imported)'))
        self.assertIn('return imported', imported)
        self.assertNotIn('accepted = true', block(self.store, 'suspend fun listImportable('))
        self.assertIn('voice != null && voice.tts && voice.accepted', self.store)
        self.assertIn('if (voice.tts && !voice.accepted)', self.panel)
        self.assertIn('require(confirmed)', block(self.store, 'suspend fun create('))

    def test_android_regressions_assert_direct_query_and_import_with_retry(self):
        test = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/VoiceQuotaSelectionActivityTest.kt').read_text()
        for name in ('testImportWithoutRemarkExecutesDirectlyWithoutAliasOrClone',
                     'testImportWithRemarkNamesRemoteResourceWithoutConfirmationOrClone',
                     'testImportAliasFailureKeepsVoiceAndDirectMenuRetryOnlyRenames'):
            self.assertIn('fun ' + name + '()', test)
        self.assertIn('ui.onNodeWithText("确认本次操作").assertDoesNotExist()', test)
        self.assertNotIn('ui.onNodeWithText("确认").performClick()', test)

if __name__ == '__main__':
    unittest.main()
