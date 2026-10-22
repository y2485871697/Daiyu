"""Source wiring only; Android/JVM behavior is verified in the later unified batch."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'


def source(name):
    return (SRC / name).read_text()


class VoiceSettingsParityContractTest(unittest.TestCase):
    def test_five_original_groups_and_embedded_offline_page(self):
        text = source('VoiceSettingsPanel.kt')
        for key, title in [('RECOGNITION', '语音识别'), ('READ_ALOUD', '朗读'),
                           ('CONVERSATION', '语音对话'), ('REALTIME', '豆包实时通话'), ('PERSONAL', '我的声音')]:
            self.assertIn(f'"{key}" to "{title}"', text)
        self.assertIn('val profile = if (section == "CONVERSATION")', text)
        self.assertIn('OfflineSpeechSettingsScreen(context, onBack = ::leaveChild', text)
        self.assertNotIn('onOpenOfflineSpeech', text)

    def test_speech_picker_projects_builtin_and_cached_models_without_writing_chat_catalog(self):
        dialog = source('VoiceSettingsDialogs.kt').split('internal fun VoiceModelDialog(', 1)[1].split('internal fun VoiceProviderDialog(', 1)[0]
        self.assertIn('source.speechChoices()', dialog)
        self.assertIn('source.withSpeechModel(choice)', dialog)
        self.assertIn('groupBy { it.connection.provider.slotId }', dialog)
        self.assertNotIn('SettingsModelPicker(', dialog)
        model_source = source('ui/screens/settings/SettingsModelSource.kt')
        speech = model_source.split('fun speechChoices()', 1)[1].split('fun providerName(', 1)[0]
        self.assertIn('SpeechModelCapabilities.builtInModels', speech)
        self.assertIn('catalog.revision == cached.selection.expectedRevision', speech)
        self.assertIn('it == cached.selection.record', speech)
        self.assertNotIn('save(', speech)
        self.assertIn('voice = initial.voice.takeIf', dialog)

    def test_speech_and_account_tickets_recheck_configuration_before_loading_credentials(self):
        text = source('ui/screens/settings/SettingsModelSource.kt')
        speech = text.split('fun speechChoices()', 1)[1].split('fun <T> withSpeechModel(', 1)[0]
        providers = text.split('fun voiceProviders(', 1)[1].split('fun <T> withVoiceProvider(', 1)[0]
        for enumeration in (speech, providers):
            self.assertIn('readConfiguration { revision ->', enumeration)
            self.assertIn('SettingsVoiceProviderChoice(editor, provider.name, endpoint, revision)', enumeration)
        for marker, end, check in [
            ('fun <T> withSpeechModel(', 'private fun checkSpeechConnection(', 'choice.connection.configurationRevision'),
            ('fun <T> withVoiceProvider(', '/** Credentials exist', 'choice.configurationRevision'),
        ]:
            consume = text.split(marker, 1)[1].split(end, 1)[0]
            self.assertIn('readConfiguration { revision ->', consume)
            self.assertLess(consume.index(f'check(revision == {check})'), consume.index('store.load()'))
        ticket = text.split('internal data class SettingsVoiceProviderChoice(', 1)[1].split('internal data class SettingsSpeechModelChoice(', 1)[0]
        self.assertIn('val configurationRevision: Long,', ticket)
        self.assertNotIn('revision >= 0', speech + providers)

    def test_dirty_return_flushes_and_saving_failure_retains_both_profiles(self):
        panel = source('VoiceSettingsPanel.kt')
        leave = panel.split('fun leavePage(', 1)[1].split('fun leaveChild()', 1)[0]
        self.assertLess(leave.index('runtime.save(pending, target)'), leave.index('if (current()) after()'))
        self.assertIn('onSaved = { if (draft.instructions == pending.instructions)', panel)
        self.assertIn('multiline = true, enabled = !busy', panel)
        runtime = source('ControllerVoiceRuntime.kt')
        save = runtime.split('suspend fun save(', 1)[1].split('suspend fun saveAccount(', 1)[0]
        self.assertIn('failed = true', save)
        self.assertIn('check(vault.load().apiKey == encoded)', save)
        self.assertNotIn('VoiceProfileEnvelope(emptyMap()', save)

    def test_personal_accounts_do_not_replace_the_whole_playback_profile(self):
        page = source('ProductionVoiceAccountPage.kt')
        self.assertIn('if (standalone) runtime.accountCurrent(frozen)', page)
        self.assertIn('if (standalone) runtime.saveAccount', page)
        self.assertIn('val next = current.withSpeech(speech)', page)
        self.assertIn('setCloudEnabled(profile, true)', page)
        runtime = source('ControllerVoiceRuntime.kt')
        account_save = runtime.split('suspend fun saveAccount(', 1)[1].split('fun accountCurrent(', 1)[0]
        self.assertLess(account_save.index('failed = true'), account_save.index('invalidateSpeech()'))
        self.assertIn('publish(next); failed = false', account_save)

    def test_demo_is_not_a_billable_synthesis_or_an_acceptance_action(self):
        leaf = source('voice/account/PersonalVoicePreview.kt')
        self.assertIn('demoOnly = true', leaf)
        self.assertNotIn('voice.tts && voice.accepted', leaf)
        host = source('ActivityVoiceAccountPreviewHost.kt')
        self.assertIn('if (request.demoOnly) fetchVoiceResource(request.demoUrl, calls)', host)
        resource = source('VoiceResourceFetch.kt')
        self.assertIn('Request.Builder().url(url).get().build()', resource)
        self.assertIn('MAX_DEMO_BYTES = 8 * 1024 * 1024', resource)
        self.assertIn('output.size() + count <= MAX_DEMO_BYTES', resource)
        self.assertNotIn('Authorization', resource)
        self.assertNotIn('accept(', leaf)

    def test_voice_modes_are_filtered_and_rechecked_at_execution(self):
        menus = source('ui/screens/chat/conversation/ChatMenus.kt')
        self.assertIn('.filter { it.action in available }', menus)
        self.assertNotIn('entry.action != ChatMenuAction.VOICE_DOUBAO', menus)
        composer = source('ui/screens/chat/conversation/ChatComposer.kt')
        self.assertIn('state.enabled && state.voiceModes.isNotEmpty()', composer)
        self.assertIn('state.voiceMode !in state.voiceModes', composer)
        main = source('MainActivity.kt')
        self.assertIn('if (!VoiceSettingsPreferences(applicationContext).enabled(feature)) return@voice', main)
        self.assertIn('remoteVoice.startDuplex', main)
        self.assertIn('cfg.dictation == "SYSTEM" -> UniversalRecognitionConfiguration.Offline', main)


if __name__ == '__main__':
    unittest.main()
