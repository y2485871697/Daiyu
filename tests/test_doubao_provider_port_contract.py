"""Source-only wiring checks. JVM and Android behavior await the approved build batch."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
K = ROOT / 'app/src/main/kotlin/app/daiyu/android'


def source(name):
    return (K / name).read_text()


class DoubaoProviderPortContractTest(unittest.TestCase):
    def test_speech_entry_has_gated_callback_not_a_placeholder(self):
        ui = source('ui/screens/settings/ProviderDirectoryPage.kt')
        row = ui.split('SettingsRow("新增豆包语音"', 1)[1].split('item("provider-count")', 1)[0]
        self.assertIn('onClick = if (enabled) onAddSpeech else null', row)
        host = source('ui/screens/settings/ProviderManagementRoute.kt')
        self.assertIn('onAddSpeech = { if (current(p) && enabled) creating = NewProviderDraft(ProviderProtocol.CHAT_COMPLETIONS, speechOnly = true) }', host)
        self.assertIn('providerName = "豆包语音"', host)
        self.assertIn('model = "seed-tts-2.0", providerEnabled = true', host)

    def test_registration_saves_credentials_and_both_local_presets_before_publish(self):
        body = source('storage/ProviderRepository.kt').split('fun registerConfigured(', 1)[1].split('fun rename(', 1)[0]
        self.assertLess(body.index('resolveStore(id).save('), body.index('old.copy(providers'))
        self.assertNotIn('activeSlotId =', body)
        self.assertIn('allowUnselectedModel = true', body)
        save = source('storage/ModelSettings.kt').split('if (connectionChanged) {', 1)[1].split('} else {', 1)[0]
        self.assertIn('DoubaoSpeech.catalogModels.map', save)
        self.assertIn('CatalogModelParameters.newModel(RemoteModel(id, label))', save)
        self.assertIn('modelCatalog.replaceRegisteredProvider(initialModels)', save)
        self.assertIn('if (speechOnly) modelCatalog.register(initialModels)', save)
        self.assertLess(save.index('catalogReplaced = true'), save.index('modelCatalog.register(initialModels)'))
        presets = source('voice/providers/DoubaoSpeech.kt')
        self.assertIn('"seed-tts-2.0" to "豆包语音合成 2.0"', presets)
        self.assertIn('"seed-audio-1.0" to "豆包音频生成 1.0"', presets)

    def test_speech_catalog_does_not_use_remote_models_or_prove_authentication(self):
        service = source('model/catalog/ModelCatalogService.kt')
        fetch = service.split('fun fetchDraft(', 1)[1]
        self.assertLess(fetch.index('DoubaoSpeech.catalogModels'), fetch.index('catalogRequest(settings)'))
        probe = service.split('internal fun testDraft(', 1)[1].split('fun fetchDraft(', 1)[0]
        self.assertIn('DoubaoSpeechConnection(speechTransport', probe)
        self.assertIn('.test(settings, isCancelled)', probe)
        self.assertIn('else ModelConnectionResult.Connected(fetchDraft', probe)

    def test_real_probe_is_single_synthesis_with_headers_not_chat(self):
        probe = source('voice/providers/DoubaoSpeechConnection.kt')
        self.assertEqual(1, probe.count('transport.execute('))
        self.assertIn('DoubaoSpeech.request(SpeechProviderConfig(SpeechEngine.DOUBAO', probe)
        self.assertIn('"你好", DoubaoVoices.DEFAULT_ID', probe)
        self.assertIn('settings.customHeaders.encode()', probe)
        self.assertIn('DoubaoSpeech.decodeResponse(', probe)
        for forbidden in ('/models', '/responses', 'AudioRecord', 'MediaPlayer', 'retry('):
            self.assertNotIn(forbidden, probe)

    def test_predicate_cancellation_reaches_cancellable_transport(self):
        probe = source('voice/providers/DoubaoSpeechConnection.kt')
        self.assertIn('if (worker.isInterrupted || isCancelled()) throw CancellationException', probe)
        self.assertIn('synthesis.cancel(); break', probe)
        self.assertIn('finally { cancellation.cancel() }', probe)
        self.assertIn('invokeOnCancellation { call.cancel() }', source('voice/network/OkHttpVoiceTransport.kt'))
        self.assertIn('RemoteModelFailure.CANCELLED', probe)

    def test_speech_success_never_claims_catalog_or_playback(self):
        ui = source('ui/screens/settings/SettingsConnectionTest.kt')
        self.assertIn('ModelConnectionResult.SpeechConnected -> "语音合成连接成功；未播放音频或验证语音识别"', ui)
        self.assertIn('测试会合成“你好”，可能产生语音服务用量；不会播放或录音。', source('ui/screens/settings/ModelSettingsScreen.kt'))

    def test_chat_and_subagent_directories_exclude_speech_but_speech_keeps_catalog(self):
        ui = source('ui/screens/settings/SettingsModelSource.kt')
        self.assertIn('(!includeSpeech && app.daiyu.android.voice.providers.DoubaoSpeech.isOpenspeech(endpoint))', ui)
        self.assertIn('includeSpeech = true)', ui)
        for file in ('MainActivity.kt', 'ChatController.kt'):
            self.assertIn('if (app.daiyu.android.voice.providers.DoubaoSpeech.isOpenspeech(endpoint)) null', source(file))
        validate = source('storage/ModelSettings.kt').split('fun validate() {', 1)[1].split('fun validateConnection()', 1)[0]
        self.assertIn('豆包语音提供商不能用于聊天', validate)

    def test_official_base_is_not_silently_retargetable(self):
        self.assertIn('enabled = enabled && !speechOnly && draft.oauth == null, compact = true)', source('ui/screens/settings/ModelSettingsScreen.kt'))
        endpoint = source('voice/providers/DoubaoSpeech.kt').split('fun validateBaseUrl(', 1)[1].split('fun request(', 1)[0]
        self.assertIn('uri.port in setOf(-1, 443)', endpoint)
        self.assertIn('uri.rawPath.orEmpty() in setOf("", "/")', endpoint)
        self.assertIn('DoubaoSpeech.validateBaseUrl(endpoint)', source('storage/ModelSettings.kt'))

    def test_voice_protocol_stays_separate_from_chat_enum_and_asr(self):
        self.assertNotIn('DOUBAO', source('model/ProviderSnapshot.kt').split('enum class ProviderProtocol', 1)[1].split('class UnsupportedProviderException', 1)[0])
        probe = source('voice/providers/DoubaoSpeechConnection.kt')
        self.assertNotIn('asrKey', probe)
        self.assertNotIn('duplexKey', probe)

    def test_behavior_test_sources_cover_single_request_cancel_and_create(self):
        test = (ROOT / 'app/src/test/kotlin/app/daiyu/android/voice/providers/DoubaoProviderConnectionTest.kt').read_text()
        self.assertIn('cancellationRevokesInflightTransportWithoutRetry', test)
        self.assertIn('assertEquals(1, requests.get())', test)
        activity = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ProviderAuthenticationChoiceActivityTest.kt').read_text()
        self.assertIn('testDoubaoAddRegistersPresetsWithoutChangingChatOrOpeningModel', activity)
        self.assertIn('testPausingUnsavedDoubaoProviderRetainsDraftWithoutRegistering', activity)
        self.assertNotIn('"provider-add-speech").assertHasNoClickAction()', activity)


if __name__ == '__main__':
    unittest.main()
