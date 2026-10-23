"""Chat voice feedback source contracts; Android execution awaits the approved batch."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'
CHAT = SRC / 'ui/screens/chat/conversation'


class ChatVoiceFeedbackContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.main = (SRC / 'MainActivity.kt').read_text()
        cls.button = (CHAT / 'SpeechMorphButton.kt').read_text()

    def test_missing_configuration_uses_short_toast_without_changing_shared_toasts(self):
        self.assertIn('private fun voiceConfigurationToast(text: String) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }', self.main)
        self.assertIn('private fun toast(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }', self.main)
        for label in ('请先配置语音输入', '请先配置语音对话', '请先配置豆包实时通话', '请先下载离线语音包'):
            self.assertIn(label, self.main)

    def test_preflight_reuses_conversation_profile_and_existing_speech_validation(self):
        check = self.main.split('private fun chatVoiceConfigurationProblem(', 1)[1].split('private fun startUniversalVoice(', 1)[0]
        self.assertIn('mode == ChatMenuAction.VOICE_CHAT', check)
        self.assertIn('voiceRuntime.prepareSpeech(VoiceProfile.CONVERSATION).configuration', check)
        self.assertIn('else voiceRuntime.snapshot()', check)
        self.assertIn('catch (_: Exception) { return missing }', check)
        self.assertIn('config.duplexKey.isBlank()', check)
        self.assertIn('config.asrKey.isBlank()', check)
        self.assertIn('config.asrResource !in app.daiyu.android.voice.providers.DoubaoAsrProtocol.resources', check)
        self.assertIn('OfflineSpeechPack.state.value.ready', check)
        self.assertIn('if (mode == ChatMenuAction.VOICE_CHAT && offlineReady) return null', check)
        for forbidden in ('remoteVoice.', 'stopCascadeVoice(', 'invalidate', 'retainedPage =', 'VoiceConversationDialog', 'startCapture', 'authorizeReference'):
            self.assertNotIn(forbidden, check)

    def test_preflight_returns_before_mutation_dialog_or_audio_after_owner_recheck(self):
        action = self.main.split('onVoice = voice@{', 1)[1].split('onRegenerate =', 1)[0]
        owner = action.index('if (!currentChat()) return@voice')
        preflight = action.index('chatVoiceConfigurationProblem(voiceEntryMode)?.let { voiceConfigurationToast(it); return@voice }')
        self.assertLess(owner, preflight)
        for downstream in ('requestDictation()', 'stopCascadeVoice()', 'remoteVoice.startDuplex', 'voiceConversationDialogOwner ='):
            self.assertLess(preflight, action.index(downstream))
        self.assertIn('if (voiceEntryMode != ChatMenuAction.VOICE_DOUBAO || !remoteVoice.duplexActive)', action)
        self.assertIn('if (remoteVoice.duplexActive) remoteVoice.invalidate()', action)

    def test_missing_offline_pack_stays_in_chat(self):
        request = self.main.split('fun requestDictation()', 1)[1].split('fun openContextPage()', 1)[0]
        self.assertIn('else voiceConfigurationToast("请先下载离线语音包")', request)
        self.assertNotRegex(request, r'retainedPage\s*=(?!=)')

    def test_press_interaction_drives_spring_size_and_existing_morph_animation(self):
        for fragment in ('val interactionSource = remember { MutableInteractionSource() }',
                         'val pressed by interactionSource.collectIsPressedAsState()',
                         'val showPressFeedback = enabled && (pressed || tapFeedback)',
                         'if (listening || showPressFeedback) 32.dp else 20.dp',
                         'spring(dampingRatio = 0.65f)',
                         'interactionSource = interactionSource,',
                         'animate = generating || listening || showPressFeedback'):
            self.assertIn(fragment, self.button)
        self.assertIn('Modifier.testTag("chat-voice-indicator")', self.button)
        self.assertIn('.testTag("chat-voice").size(32.dp).clip(CircleShape)', self.button)
        self.assertIn('onLongClick = onLongClick,', self.button)
        self.assertNotIn('performHapticFeedback', self.button)
        self.assertNotIn('pointerInput', self.button)
        for fragment in ('remember(enabled) { mutableIntStateOf(0) }', 'LaunchedEffect(tapSequence, enabled)',
                         'tapFeedback = true', 'delay(180)', 'tapFeedback = false', 'tapSequence++; onClick()'):
            self.assertIn(fragment, self.button)

    def test_device_sources_cover_real_entry_side_effects_and_press_release_cancel(self):
        entries = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ChatVoiceEntryFeedbackActivityTest.kt').read_text()
        for fragment in ('retainedControls(activity).onVoice()', 'voiceConversationDialogOwner',
                         'voiceRequestEpoch', 'host.status.value', 'assertEquals(0, fixture.opens.get())',
                         'VoiceProfile.CONVERSATION', 'remote-voice-status', 'voice-universal-control',
                         'testConfiguredProfilesPassLocalPreflightWithoutStartingAudio'):
            self.assertIn(fragment, entries)
        gestures = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ChatVoiceModeMenuActivityTest.kt').read_text()
        for fragment in ('testVoicePressEnlargesAndReleaseRestoresWithoutMovingHitTarget',
                         'testCancelledVoicePressRestoresWithoutClickOrMenu',
                         'testQuickVoiceClickStillEnlargesBeforeReturningToIdle',
                         'chat-voice-indicator', 'down(center)', 'cancel()',
                         'ui.mainClock.autoAdvance = false', 'ui.mainClock.advanceTimeByFrame()',
                         'node("chat-voice").assertIsNotEnabled()', 'pressedWidth > idleWidth',
                         'assertEquals(hitBounds,', 'assertTrue(events.isEmpty())'):
            self.assertIn(fragment, gestures)


if __name__ == '__main__':
    unittest.main()
