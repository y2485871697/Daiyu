"""Message read-aloud source wiring; Android execution belongs to the approved batch."""
from pathlib import Path
import unittest
import re

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'
CHAT = SRC / 'ui/screens/chat/conversation'


class MessageSpeechButtonContractTest(unittest.TestCase):
    def test_no_status_row_or_separate_stop_button(self):
        main = (SRC / 'MainActivity.kt').read_text()
        for old in ('VoiceExtras(', 'voice-host-status-', 'voice-host-stop-'):
            self.assertNotIn(old, main)
        self.assertIn('QuestionCleanup(turn.id)', main)

    def test_single_host_flow_projects_only_current_active_message(self):
        main = (SRC / 'MainActivity.kt').read_text()
        self.assertEqual(1, main.count('val messageSpeech by speechHost.state.collectAsStateWithLifecycle()'))
        for part in ('messageSpeech.requestId.takeIf', 'messageSpeech.owner == speechHost.owner && voiceHostIsActive(messageSpeech)',
                     'speechMessageId = speechMessageId'):
            self.assertIn(part, main)

    def test_projection_reaches_message_action_id_not_storage_owner(self):
        screen = (CHAT / 'ChatScreen.kt').read_text()
        messages = (CHAT / 'ChatMessageList.kt').read_text()
        for source in (screen, messages):
            self.assertIn('speechMessageId: String? = null', source)
        self.assertIn('speechMessageId = speechMessageId', screen)
        self.assertIn('speaking = speechMessageId == turn.listKey || speechMessageId == turn.id', messages)
        self.assertIn('showCopyAction = hasTextActions, speaking = speaking', messages)
        self.assertNotIn('speechMessageId == turn.ownerMessageId', messages)
        self.assertIn('speechMessageId == turn.listKey', messages)

    def test_same_button_switches_stop_before_start_only_gates(self):
        main = (SRC / 'MainActivity.kt').read_text()
        self.assertIn('onSpeak = speak@{ id ->', main)
        action = main.split('onSpeak = speak@{ id ->', 1)[1].split('onBranch = messageCallbacks.onBranch', 1)[0]
        for part in ('!activityUsable()', '!voiceForeground', 'retainedPage != "chat"', 'sidebarVisible',
                     'speechHost.owner != renderedSpeechOwner', 'editorGeneration != renderedEditorGeneration',
                     'playback.owner == renderedSpeechOwner && playback.requestId == id && voiceHostIsActive(playback)'):
            self.assertIn(part, action)
        stop = action.index('speechHost.stop(renderedSpeechOwner, id)')
        self.assertLess(stop, action.index('editorMatches() && controller.ready && !controller.dataBusy'))
        self.assertLess(stop, action.index('voiceHostText(chatState.turns, id)'))
        self.assertLess(stop, action.index('requestConfiguredSpeech(renderedSpeechOwner, id, text)'))
        self.assertLess(action.index('invalidatePreparedSpeech()'), stop)

    def test_original_button_is_kept_and_animation_is_local(self):
        bar = (CHAT / 'MessageActionBar.kt').read_text()
        self.assertTrue((CHAT / 'MessageSpeechIcon.kt').is_file())
        icon = (CHAT / 'MessageSpeechIcon.kt').read_text()
        self.assertIn('if (allowSpeech) ConversationIconButton(onClick = { actions.onSpeak(id) })', bar)
        self.assertIn('MessageSpeechIcon(speaking, Modifier.size(13.dp))', bar)
        self.assertLess(icon.index('if (!speaking)'), icon.index('val transition = rememberInfiniteTransition'))
        for part in ('DaiyuIcons.Speak, "朗读"', 'contentDescription = "停止朗读"', 'drawPath(body, tint, style = stroke)',
                     'drawPath(inner, tint, alpha =', 'drawPath(outer, tint, alpha =', 'tween(800, easing = LinearEasing)'):
            self.assertIn(part, icon)
        for bad in ('TextButton(', 'Text(', 'SpeechMorphButton(', 'graphicsLayer', 'delay('):
            self.assertNotIn(bad, icon)

    def test_async_errors_keep_short_feedback_without_normal_status(self):
        main = (SRC / 'MainActivity.kt').read_text()
        self.assertIn('LaunchedEffect(messageSpeechAttempt, messageSpeech)', main)
        effect = main.split('LaunchedEffect(messageSpeechAttempt, messageSpeech)', 1)[1].split('val contextUsage', 1)[0]
        self.assertIn('messageSpeech.owner == speechHost.owner', effect)
        self.assertIn('messageSpeech == speechHost.state.value', effect)
        self.assertIn('reportedMessageSpeechError != event', effect)
        self.assertIn('reportedMessageSpeechError = event', effect)
        self.assertIn('setOf(SpeechPhase.UNAVAILABLE, SpeechPhase.ERROR)', effect)
        self.assertIn('voiceHostStatusResource(messageSpeech)?.let { voiceConfigurationToast(getString(it)) }', effect)
        self.assertNotIn('SpeechPhase.COMPLETED', effect)
        self.assertNotIn('SpeechPhase.STOPPED', effect)
        self.assertNotIn('Text(', effect)
        play = main.split('fun play(value: FrozenVoiceSpeech)', 1)[1].split('val config = frozen.configuration', 1)[0]
        self.assertLess(play.index('messageSpeechAttempt++'), play.index('speechHost.speak(expectedOwner, id, text)'))

    def test_animation_pixel_regression_waits_for_real_draw_before_each_capture(self):
        test = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/MessageSpeechButtonTest.kt').read_text()
        body = test.split('fun testSpeakerWavesAnimateInsideUnchangedHitTarget()', 1)[1]
        self.assertEqual(2, body.count('captureScreenNode(waves).toPixelMap()'))
        self.assertNotIn('captureToImage()', body)
        self.assertIn('ui.mainClock.advanceTimeBy(400)', body)
        self.assertIn('assertEquals(beforeBounds, button.fetchSemanticsNode().boundsInRoot)', body)
        self.assertIn('changed > 0', body)

    def test_capture_phase_spacing_cannot_alias_any_current_wave_alpha_bucket(self):
        icon = (CHAT / 'MessageSpeechIcon.kt').read_text()
        test = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/MessageSpeechButtonTest.kt').read_text()
        body = test.split('fun testSpeakerWavesAnimateInsideUnchangedHitTarget()', 1)[1]
        period = int(re.search(r'tween\((\d+), easing = LinearEasing', icon).group(1))
        thresholds = [float(value) for value in re.findall(r'wave < ([0-9.]+)f', icon)]
        spacing = int(re.search(r'advanceTimeBy\((\d+)\)', body).group(1))
        self.assertEqual(2, len(thresholds))
        def alphas(time):
            phase = (time % period) / period
            return tuple(phase < threshold for threshold in thresholds)
        # Include one capture frame and one frame of rounding, not only ideal virtual time.
        for extra_frames in range(33):
            for phase in range(period):
                self.assertNotEqual(alphas(phase), alphas(phase + spacing + extra_frames),
                                    (phase, spacing + extra_frames))

    def test_wave_fixture_explicitly_enables_its_own_motion_without_changing_system_settings(self):
        test = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/MessageSpeechButtonTest.kt').read_text()
        fixture = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ComposeActivityTestCase.kt').read_text()
        self.assertIn('WiredActivityTestCase(object : MotionDurationScale', test)
        self.assertIn('override val scaleFactor = 1f', test)
        self.assertIn('StandardTestDispatcher() + (motionDurationScale ?: EmptyCoroutineContext)', fixture)
        self.assertIn('ComposeActivityTestCase(motionDurationScale)', fixture)
        self.assertNotIn('settings put', test)
        self.assertNotIn('Settings.Global.put', test)


if __name__ == '__main__':
    unittest.main()
