"""Source contracts only; Compose, audio and physical haptics run in the approved unified batch."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'
CHAT = SRC / 'ui/screens/chat/conversation'


class VoiceLongPressMenuContractTest(unittest.TestCase):
    def test_menu_uses_shared_popup_and_row_style_after_filtering(self):
        menu = (CHAT / 'ChatMenus.kt').read_text().split('internal fun ChatVoiceModeMenu(', 1)[1]
        labels = ['MenuEntry("语音输入"', 'MenuEntry("语音对话"', 'MenuEntry("豆包实时通话"']
        positions = [menu.index(label) for label in labels]
        self.assertEqual(positions, sorted(positions))
        self.assertLess(menu.index('.filter { it.action in available }'), menu.index('if (entries.isEmpty()) return'))
        self.assertLess(menu.index('if (entries.isEmpty()) return'), menu.index('ChatAnchoredMenu('))
        self.assertIn('Modifier.testTag("chat-voice-mode-menu")', menu)
        self.assertIn('checked = entry.action == selected, previewStyle = true', menu)
        self.assertNotIn('ChatSelectionSheet(', menu)

    def test_menu_state_is_revoked_by_availability_disabled_or_editor_changes(self):
        composer = (CHAT / 'ChatComposer.kt').read_text()
        self.assertIn('var voiceModes by remember(state.editorGeneration, state.enabled, state.voiceModes)', composer)
        self.assertIn('val voiceMenuEnabled = state.enabled && state.voiceModes.isNotEmpty()', composer)
        self.assertIn('ChatVoiceModeMenu(voiceModes && voiceMenuEnabled,', composer)

    def test_disabled_long_press_is_removed_before_feedback_and_callback(self):
        composer = (CHAT / 'ChatComposer.kt').read_text().split('SpeechMorphButton(', 1)[1]
        self.assertIn('enabled = voiceMenuEnabled,', composer)
        self.assertIn('onLongClick = if (voiceMenuEnabled) ({ actions.onVoiceLongPress(); voiceModes = true }) else null', composer)
        button = (CHAT / 'SpeechMorphButton.kt').read_text()
        self.assertIn('onLongClick: (() -> Unit)?', button)
        self.assertIn('onLongClick = onLongClick,', button)
        self.assertIn('onLongClickLabel = if (onLongClick != null) "选择语音功能" else null', button)
        self.assertNotIn('performHapticFeedback', button)
        self.assertNotIn('TouchHaptics.longPress', button)

    def test_long_press_and_menu_selection_do_not_start_audio(self):
        composer = (CHAT / 'ChatComposer.kt').read_text().split('onLongClick = if (voiceMenuEnabled)', 1)[1].split('ChatVoiceModeMenu(', 1)[0]
        self.assertNotIn('actions.onVoice()', composer)
        menus = (CHAT / 'ChatMenus.kt').read_text().split('internal fun ChatVoiceModeMenu(', 1)[1]
        self.assertIn('ChatMenuItem(entry, onDismiss, onAction,', menus)
        for fragment in ('onVoice()', 'startDuplex', 'requestDictation', 'VoiceSettingsPreferences'):
            self.assertNotIn(fragment, menus)

    def test_voice_selection_keeps_menu_open_and_uses_model_picker_background(self):
        menus = (CHAT / 'ChatMenus.kt').read_text()
        voice = menus.split('internal fun ChatVoiceModeMenu(', 1)[1]
        self.assertIn('dismissOnAction = false, selectionBackground = selectionBackground', voice)
        self.assertIn('if (colors.dark) colors.bubble else Color(0xFFEFEDFB)', voice)
        self.assertIn('if (colors.dark) colors.bubble else Color(0xFFEFEDFB)', (CHAT / 'ChatModelPicker.kt').read_text())
        row = menus.split('private fun ChatMenuItem(', 1)[1].split('internal fun ChatAttachmentSheet(', 1)[0]
        self.assertIn('dismissOnAction: Boolean = true, selectionBackground: Color? = null', row)
        self.assertIn('if (dismissOnAction && entry.action !=', row)
        self.assertIn('.background(if (checked == true) selectionBackground ?: Color.Transparent else Color.Transparent)', row)
        self.assertIn('if (selectionBackground != null) selected = checked == true', row)

    def test_real_settings_projection_and_execution_rechecks_remain(self):
        main = (SRC / 'MainActivity.kt').read_text()
        for feature, action in [('input', 'VOICE_INPUT'), ('conversation', 'VOICE_CHAT'), ('realtime', 'VOICE_DOUBAO')]:
            self.assertIn(f'if (voiceSettings.enabled("{feature}")) add(ChatMenuAction.{action})', main)
        self.assertIn('if (!VoiceSettingsPreferences(applicationContext).enabled(feature)) return@voice', main)

    def test_device_regression_source_covers_combinations_reset_and_haptic_positive_control(self):
        device = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ChatVoiceModeMenuActivityTest.kt').read_text()
        for fragment in ('for (mask in 0..7)', 'performTouchInput { longClick() }',
                         'item.assertDoesNotExist()', 'assertTrue(events.isEmpty())',
                         'assertTrue(requests.isEmpty())', 'listOf(HapticKind.LONG_PRESS)',
                         'generation = state.editorGeneration + 1', 'enabled = false',
                         'assertEquals(listOf("long", mode.name)', 'menu.bottom <= button.top + 1f',
                         'state = state.copy(voiceMode = it)', 'row.assertIsSelected()',
                         'row.assertIsNotSelected()', 'captureScreenNode(row)', 'Color(0xFFEFEDFB)',
                         'applyInterfaceScale = false', 'candidate == mode, hasSelectedBackground'):
            self.assertIn(fragment, device)


if __name__ == '__main__':
    unittest.main()
