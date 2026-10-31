"""Source wiring only; Android/JVM behavior is verified in the later unified batch."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'


def source(name):
    return (SRC / name).read_text()


class VoiceSettingsParityContractTest(unittest.TestCase):
    def test_preview_click_uses_default_permission_and_retains_frozen_reference_guards(self):
        text = source('VoiceSettingsPreview.kt')
        for old in ('var consent by remember', 'else consent = frozen', 'consent?.let', '仅授权本次试听'):
            self.assertNotIn(old, text)
        self.assertIn('val frozen = personalPreview ?: runtime.prepareSpeech(profile)\n                        play(frozen)', text)
        self.assertIn('if (initialize) DaiyuConfirmDialog("启用系统语音"', text)
        play = text.split('fun play(frozen: FrozenVoiceSpeech)', 1)[1].split('DisposableEffect(host, lifecycle)', 1)[0]
        for guard in ('!current() || !allowed', '!runtime.current(frozen)', 'val token = ++request',
                      'request == token && current() && allowed && runtime.current(ready)',
                      'lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)',
                      'host.speak(host.owner, "settings-preview", sampleText)'):
            self.assertIn(guard, play)
        self.assertLess(play.index('runtime.authorizeReference(frozen)'), play.index('runtime.current(ready)'))
        self.assertLess(play.index('runtime.current(ready)'), play.index('host.speak('))

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
        for shared in ('ChatModelPicker(', 'ChatModelPickerState(', 'ChatModelProviderUi('):
            self.assertIn(shared, dialog)
        self.assertNotIn('WindowDialog(', dialog)
        self.assertNotIn('voice-catalog-open:', dialog)
        self.assertIn('ModelCatalogEntryUi(it.speechPickerId, it.name, apiModelId = it.model)', dialog)
        self.assertIn('current = selected?.speechPickerId.orEmpty()', dialog)
        self.assertIn('it.connection.provider.slotId == provider && it.speechPickerId == model', dialog)
        self.assertIn('if (identity.isBlank()) "model:$model" else "local:$identity"', dialog)
        model_source = source('ui/screens/settings/SettingsModelSource.kt')
        speech = model_source.split('fun speechChoices()', 1)[1].split('fun providerName(', 1)[0]
        self.assertIn('SpeechModelCapabilities.builtInModels', speech)
        self.assertIn('.filter { SpeechModelCapabilities.readAloud(it.selection.record.id) }', speech)
        self.assertIn('if (SpeechModelCapabilities.readAloud(current)', speech)
        self.assertIn('require(SpeechModelCapabilities.readAloud(value.model))', speech)
        self.assertIn('catalog.revision == cached.selection.expectedRevision', speech)
        self.assertIn('it == cached.selection.record', speech)
        self.assertNotIn('save(', speech)
        self.assertIn('voice = initial.voice.takeIf', dialog)

    def test_selection_only_voice_dialogs_share_sheet_without_close_buttons(self):
        text = source('VoiceSettingsDialogs.kt')
        for name in ('VoiceChoiceDialog', 'VoiceProviderDialog', 'VoiceToneDialog'):
            dialog = text.split(f'internal fun {name}(', 1)[1].split('\n@Composable', 1)[0]
            self.assertIn('ChatSelectionSheet(title, onDismiss = onDismiss, showCloseButton = false', dialog)
            self.assertNotIn('WindowDialog(', dialog)
            self.assertNotIn('TextButton(', dialog)
        tone = text.split('internal fun VoiceToneDialog(', 1)[1]
        self.assertIn('choices.distinctBy { it.first }', tone)
        self.assertIn('listOf("女声", "男声", "", "个人声音")', tone)
        self.assertIn('selected = id == initial', tone)
        panel = source('VoiceSettingsPanel.kt')
        self.assertIn('"VOICE" -> VoiceToneDialog(', panel)
        self.assertIn('"DUPLEX_VOICE" -> VoiceToneDialog(', panel)
        advanced = panel.split('"ASR_ADVANCED" -> ChatSelectionSheet(', 1)[1].split('"ASR_RESOURCE"', 1)[0]
        self.assertIn('showCloseButton = false', advanced)
        editor = text.split('internal fun VoiceTextDialog(', 1)[1].split('\n@Composable', 1)[0]
        self.assertIn('MiuixDialogActions("保存", onCancel = onDismiss', editor)

    def test_clone_account_uses_compact_secret_eye_and_explicit_theme_pill_save(self):
        panel = source('VoiceSettingsPanel.kt')
        account = panel.split('if (child == "DOUBAO_ACCOUNT") {', 1)[1].split('val pageTitle =', 1)[0]
        field, button = account.split('VoiceAccountSaveButton("保存账户", "voice-account-save"', 1)
        self.assertIn('SettingsTextField("声音复刻 API Key"', field)
        self.assertIn('password = true, enabled = !busy, compact = true', field)
        self.assertIn('{ if (!busy) accountKeyDraft = it }', field)
        for required in ('enabled = !busy && accountKeyDraft.isNotBlank()',
                         'runtime.saveAccount(value)', 'key = accountKeyDraft.trim()'):
            self.assertIn(required, button)
        self.assertNotIn('SettingsRow("保存账户"', account)
        self.assertNotIn('save(draft', account)
        self.assertIn('child = null; accountKeyDraft = ""', button)
        self.assertIn('账户未保存，请检查密钥后重试。', button)
        self.assertIn('SettingsRow("打开豆包控制台"', button)

    def test_doubao_without_account_opens_browsing_home_and_only_then_requests_credentials(self):
        panel = source('VoiceSettingsPanel.kt')
        entry = panel.split('"PERSONAL" -> item', 1)[1].split('SettingsRow("MiMo 语音"', 1)[0]
        self.assertIn('if (selected != null && runtime.accountCurrent(selected)) accountPage = selected', entry)
        self.assertIn('else child = "DOUBAO_VOICES"', entry)
        self.assertNotIn('else child = "DOUBAO_ACCOUNT"', entry)
        empty_route = panel.split('if (child == "DOUBAO_VOICES") {', 1)[1].split('if (child == "DOUBAO_ACCOUNT")', 1)[0]
        self.assertIn('VoicePersonalEmptyPage(isCurrent = ::current', empty_route)
        self.assertIn('child = "DOUBAO_ACCOUNT"', empty_route)
        self.assertNotIn('ProductionVoiceAccountPage(', empty_route)
        empty = source('VoicePersonalScaffold.kt').split('internal fun VoicePersonalEmptyPage(', 1)[1]
        for marker in ('"我的声音"', '"还没有声音"', '"先配置账户，再添加自己的声音。"',
                       '"voice-account-configure"', 'VoiceAccountSettingsSheet(',
                       'isEnabled = { it == "ACCOUNT" && isCurrent() }'):
            self.assertIn(marker, empty)
        self.assertNotIn('runtime.', empty)
        self.assertNotIn('preview', empty)
        self.assertNotIn('VoiceConfiguration(', empty)
        choice = source('VoiceSettingsDialogs.kt').split('internal fun VoiceChoiceDialog(', 1)[1].split('internal fun VoiceModelDialog(', 1)[0]
        self.assertIn('enabled = isEnabled(id)', choice)
        self.assertIn('if (isEnabled(id)) onSelect(id)', choice)

    def test_doubao_add_is_bottom_end_and_home_keeps_settings_menu(self):
        scaffold = source('VoicePersonalScaffold.kt').split('internal fun VoicePersonalEmptyPage(', 1)[0]
        self.assertIn('SettingsScaffoldPage(title, onBack, compactTitle = true, actions = {', scaffold)
        self.assertIn('testTag("voice-account-menu")', scaffold)
        self.assertIn('DaiyuIcon(DaiyuIcons.Settings, "声音设置")', scaffold)
        actions, bottom = scaffold.split('if (onAdd != null) IconButton', 1)
        self.assertNotIn('testTag("voice-account-add")', actions)
        for marker in ('Alignment.BottomEnd', '.navigationBarsPadding()', 'testTag("voice-account-add")',
                       'minHeight = 56.dp, minWidth = 56.dp', 'backgroundColor = colors.primary'):
            self.assertIn(marker, bottom)
        leaf = source('VoiceAccountPanel.kt')
        self.assertIn('VoicePersonalScaffold(title, ::back', leaf)
        self.assertIn('if (addMenu) VoiceAddSheet(', leaf)
        menu = source('VoicePersonalMenus.kt')
        self.assertIn('VoiceActionItem("CREATE", "用录音制作声音", DaiyuIcons.Voice)', menu)
        self.assertIn('VoiceActionItem("IMPORT", "导入已有声音", DaiyuIcons.File)', menu)
        self.assertIn('if (page == "LIST")', leaf)

    def test_saved_doubao_account_editor_uses_eye_pill_and_returns_real_new_snapshot(self):
        leaf = source('VoiceAccountPanel.kt')
        account = leaf.split('if (page == "ACCOUNT") item', 1)[1].split('if (page == "SYNC")', 1)[0]
        for marker in ('password = true, enabled = !busy, compact = true', 'VoiceAccountSaveButton("保存账户", "voice-account-save"', 'onAccountKeyChange?.invoke(key)'):
            self.assertIn(marker, account)
        self.assertNotIn('SettingsRow("保存账户"', account)
        page = source('ProductionVoiceAccountPage.kt')
        saved = page.split('onAccountKeyChange = { key ->', 1)[1].split('stopPreviewStrictly = {', 1)[0]
        self.assertLess(saved.index('runtime.saveAccount('), saved.index('runtime.accountConfigurations.value[frozen.engine]'))
        self.assertIn('runtime.accountCurrent(updated) && onAccountSaved != null', saved)
        self.assertIn('active.set(false); preview.close(); onAccountSaved(updated)', saved)
        panel = source('VoiceSettingsPanel.kt')
        self.assertIn('accountPage == accountConfiguration && runtime.accountCurrent(updated)', panel)
        self.assertIn('LaunchedEffect(personalAccounts, lifecycleState)', panel)
        self.assertIn('updated != opened && runtime.accountCurrent(updated)) accountPage = updated', panel)

    def test_doubao_slot_and_recording_details_match_existing_flows_without_new_requests(self):
        leaf = source('VoiceAccountPanel.kt')
        self.assertIn('personalVoiceSlots(creationCandidates ?: voices, creating = true, postpaid = false)', leaf)
        self.assertIn('val importSlots = importCandidates.filter { it.canImport }', leaf)
        self.assertIn('personalVoiceSlotTitle(voice, index)', leaf)
        self.assertIn('voiceSlotSummary(voice)', leaf)
        self.assertIn('.sortedWith(compareBy<PersonalVoices.Voice> { it.unused }.thenBy { it.name })', leaf)
        self.assertIn('if (voice.name == voice.id) "音色名额 ${index + 1}" else voice.name', leaf)
        self.assertIn('OpenableColumns.DISPLAY_NAME', leaf)
        self.assertIn('LaunchedEffect(sampleUri, lifecycleState, frozenConfiguration.revision)', leaf)
        self.assertIn('if (current() && sampleUri == uri) sampleName = label', leaf)
        self.assertIn('summary = if (sampleUri == null) null else sampleName', leaf)
        self.assertIn('首次正式合成可能产生音色费用', leaf)
        self.assertIn('未正式使用的音色可能七天后删除', leaf)
        project = leaf.split('if (projectPicker) ChatSelectionSheet(', 1)[1].split('voiceMenu?.let', 1)[0]
        self.assertIn('showCloseButton = false', project)
        self.assertIn('item.name.takeIf { it != item.displayName }', project)
        self.assertNotIn('TextButton("关闭"', project)
        self.assertIn('复制已复刻成功的音色 ID', leaf)
        self.assertIn('resources.voices.importPurchased(speech!!, a, s, p)', leaf)
        self.assertIn('resources.voices.create(speech!!, sample, n, slot, confirmed = true)', leaf)
        self.assertIn('resources.voices.importExisting(speech!!, id, n)', leaf)
        self.assertIn('action != "REMOVE" || !voice.uncertain', leaf)
        self.assertIn('仅移除本机记录，不删除云端音色或任务', leaf)

    def test_mimo_preview_editor_has_its_own_shared_card_and_keeps_text_pipeline(self):
        panel = source('VoiceSettingsPanel.kt')
        mimo = panel.split('} else if (child == "MIMO") {', 1)[1].split('} else when (section)', 1)[0]
        editor = mimo.split('item { VoiceSettingsCard(Modifier.testTag("voice-mimo-preview-card")) {', 1)[1].split('} }', 1)[0]
        self.assertIn('SettingsTextField("试听文字", sampleText, { sampleText = it.take(300) }, multiline = true)', editor)
        self.assertEqual(1, mimo.count('SettingsTextField("试听文字"'))
        self.assertIn('var sampleText by remember { mutableStateOf("你好，这是我的声音。") }', panel)
        self.assertIn('!busy && preview != null && sampleText.isNotBlank()', mimo)
        self.assertIn('::current, installPreviewRevoke, sampleText, configuration = preview?.configuration', mimo)
        self.assertNotIn('.background(', editor)
        self.assertNotIn('runtime.', editor)

    def test_reference_voice_save_uses_theme_pill_without_changing_import_boundary(self):
        panel = source('VoiceSettingsPanel.kt')
        button = panel.split('TextButton("保存参考声音"', 1)[1].split('SettingsTextField("试听文字"', 1)[0]
        for required in ('fillMaxWidth().testTag("voice-sample-save")',
                         'minHeight = 48.dp, cornerRadius = 24.dp',
                         'colors = ButtonDefaults.textButtonColorsPrimary()',
                         'enabled = !busy && selectedSample != null',
                         'selection.configuration == mimoConnection()',
                         'runtime.personal.import(context, selection.value, selection.name, selection.configuration.referenceOwner())',
                         'selectedSample = null', '录音未保存，请检查名称及 MP3 / WAV 文件。'):
            self.assertIn(required, button)
        self.assertNotIn('SettingsRow("保存参考声音"', panel)
        self.assertNotIn('runtime.saveAccount', button)
        self.assertNotIn('remote.', button)
        self.assertIn('SettingsNote("支持 MP3 / WAV，参考录音不超过 7.5 MB。")', panel)

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
        self.assertIn('val toolbarEnabled = state.enabled && !editing', composer)
        self.assertIn('val voiceMenuEnabled = toolbarEnabled && state.voiceModes.isNotEmpty()', composer)
        self.assertIn('onClick = { if (state.voiceMode in state.voiceModes) actions.onVoice() }', composer)
        self.assertNotIn('state.voiceModes.single()', composer)
        main = source('MainActivity.kt')
        self.assertIn('if (!VoiceSettingsPreferences(applicationContext).enabled(feature)) return@voice', main)
        self.assertIn('remoteVoice.startDuplex', main)
        self.assertIn('cfg.dictation == "SYSTEM" -> UniversalRecognitionConfiguration.Offline', main)


if __name__ == '__main__':
    unittest.main()
