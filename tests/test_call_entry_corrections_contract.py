"""Entry/host wiring guards; pure policy behavior is exercised by CallEntryPolicyTest."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'
CHAT = SRC / 'ui/screens/chat/conversation'


class CallEntryCorrectionsContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.main = (SRC / 'MainActivity.kt').read_text()
        cls.composer = (CHAT / 'ChatComposer.kt').read_text()
        cls.button = (CHAT / 'SpeechMorphButton.kt').read_text()
        cls.policy = (CHAT / 'CallEntryPolicy.kt').read_text()

    def test_entry_and_hangup_share_freeze_then_immediate_stop(self):
        entry = self.main.split('onVoice = voice@{', 1)[1].split('onRegenerate =', 1)[0]
        self.assertIn('callEntryAction(shownCall != null, shownCall?.presentation == DoubaoCallPresentation.CLOSING)', entry)
        self.assertIn('CallEntryAction.HANG_UP -> { closeDoubaoCall(shownCall!!.sessionKey); return@voice }', entry)
        self.assertIn('CallEntryAction.IGNORE_CLOSING -> return@voice', entry)
        self.assertNotIn('.expand(', entry)
        self.assertLess(entry.index('CallEntryAction.IGNORE_CLOSING'), entry.index('VoiceSettingsPreferences(applicationContext).enabled(feature)'))
        self.assertLess(entry.index('closeDoubaoCall('), entry.index('chatVoiceConfigurationProblem('))
        close = self.main.split('private fun closeDoubaoCall(key: Long)', 1)[1].split('private fun doubaoCallActions()', 1)[0]
        self.assertIn('if (doubaoCallPresentation.beginClose(key, SystemClock.elapsedRealtime()))', close)
        self.assertLess(close.index('beginClose('), close.index('remoteVoice.stopDuplex(key)'))
        actions = self.main.split('private fun doubaoCallActions()', 1)[1].split('@Composable private fun projectedVoiceComposer()', 1)[0]
        self.assertIn('onHangUp = { if (current()) closeDoubaoCall(key) }', actions)
        self.assertIn('onExpand = { if (current()) doubaoCallPresentation.expand(key) }', actions)
        completion = actions.split('onCloseAnimationFinished =', 1)[1]
        self.assertIn('finishClose(completedKey)', completion)
        self.assertNotIn('stopDuplex', completion)

    def test_long_press_has_activity_recheck_without_invalidation_and_no_disabled_closing(self):
        self.assertIn('onVoiceLongPress = { admitVoiceModePicker() && currentChat() }', self.main)
        admission = self.main.split('private fun admitVoiceModePicker(): Boolean', 1)[1].split('private fun closeDoubaoCall', 1)[0]
        self.assertIn('voiceModePickerAdmitted(doubaoCallPresentation.state != null, remoteVoice.duplexActive)', admission)
        self.assertIn('if (!admitted) voiceConfigurationToast("请先挂断")', admission)
        for forbidden in ('invalidate', 'stopDuplex', '.clear(', 'voiceEntryMode ='):
            self.assertNotIn(forbidden, admission)
        button = self.composer.split('SpeechMorphButton(', 1)[1]
        self.assertIn('enabled = callUi != null || voiceMenuEnabled,', button)
        self.assertIn('val admitted = actions.onVoiceLongPress()', button)
        self.assertIn('if (admitted && callUi == null) voiceModes = true', button)
        self.assertIn('ChatVoiceModeMenu(voiceModes && voiceMenuEnabled && callUi == null,', self.composer)
        screen = (CHAT / 'ChatScreen.kt').read_text()
        self.assertRegex(screen, r'val onVoiceLongPress:\s*\(\)\s*->\s*Boolean\s*=\s*\{\s*true\s*\}')
        selection = self.main.split('// A previously opened picker', 1)[1].split('ChatMenuAction.NEW_CHAT', 1)[0]
        self.assertLess(selection.index('if (admitVoiceModePicker())'), selection.index('voiceEntryMode = action'))

    def test_shared_motion_is_read_in_layer_not_per_frame_composition(self):
        self.assertIn('callMotion: DoubaoCallMotionState? = null', self.composer)
        self.assertIn('callMotion = callMotion,', self.composer)
        self.assertIn('callMotion: DoubaoCallMotionState? = null', self.button)
        before, layer = self.button.split('.graphicsLayer {', 1)
        self.assertNotIn('callMotion?.progress?.value', before)
        self.assertIn('val progress = callMotion?.progress?.value ?: 0f', layer)
        self.assertIn('maxOf(diameter.value / 20.dp, callEntryScale(progress))', layer)
        self.assertIn('rotationZ = callEntryRotation(progress)', layer)
        self.assertIn('animate = generating || listening || showPressFeedback || callMotion?.transforming == true', layer)
        self.assertIn('indicatorSize = 20.dp', layer)
        self.assertNotIn('Animatable(', self.button)  # No independent call animation clock.

    def test_remote_status_is_filtered_before_dictation_notice_not_by_backend_active_flag(self):
        status = self.main.split('val remoteStatus by remoteVoice.status.collectAsStateWithLifecycle()', 1)[1].split('val state by dictationHost.state', 1)[0]
        self.assertIn('val remoteCallSnapshot by remoteVoice.duplexSnapshot.collectAsStateWithLifecycle()', status)
        self.assertIn('remoteDictationNoticeStatus(remoteStatus, remoteCallSnapshot != null)', status)
        self.assertIn('status = dictationNoticeStatus), false)', status)
        self.assertNotIn('status = remoteStatus), false)', status)
        self.assertNotIn('duplexActive', status)
        self.assertIn('Text(remoteStatus,', status)  # Engineering status remains available.
        self.assertIn('status.trimStart().startsWith("双工")', self.policy)
        self.assertLess(self.policy.index('status.trimStart().startsWith("双工未启动：")'), self.policy.index('status.trimStart().startsWith("双工")'))
        notice = (SRC / 'ui/components/DictationStatusNotice.kt').read_text()
        self.assertIn('Toast.makeText(context, state.status, Toast.LENGTH_SHORT)', notice)
        self.assertIn('DictationStatusNotice(state, engineeringUi)', self.main)
        self.assertIn('private fun toast(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }', self.main)

    def test_boolean_device_callers_and_blocked_gesture_cases_are_present(self):
        device = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ChatVoiceModeMenuActivityTest.kt').read_text()
        workspace = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ConversationWorkspaceActivityTest.kt').read_text()
        self.assertIn('onVoiceLongPress = longPressAdmission ?: { events += "long"; true }', device)
        self.assertIn('onVoiceLongPress = { voiceLongPresses++; true }', workspace)
        self.assertIn('testFalseActivityAdmissionDoesNotOpenPickerWhenComposerHasNoCallUiYet', device)
        self.assertIn('testTerminalAndClosingLongPressStillReachesAdmissionWithoutOpeningPicker', device)
        self.assertIn('show(longPressAdmission = { events += "blocked"; false })', device)
        self.assertIn('assertEquals(listOf("blocked"), events.toList())', device)

    def test_producer_evidence_still_has_realtime_status_and_dictation_errors(self):
        host = (SRC / 'ActivityRemoteVoiceHost.kt').read_text()
        collector = host.split('next.state.collect { value ->', 1)[1].split('next.start {', 1)[0]
        self.assertIn('DuplexPhase.FAILED -> "双工失败，已停止；检查独立配置后手动重试"', collector)
        self.assertIn('else -> "双工 ${value.phase}', collector)
        self.assertIn('豆包听写未启动：检查独立语音设置或音频占用', host)


if __name__ == '__main__':
    unittest.main()
