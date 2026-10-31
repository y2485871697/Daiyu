"""Source contracts only; modal UI, audio and physical haptics await the approved Android batch."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'
CHAT = SRC / 'ui/screens/chat/conversation'


def voice_sheet():
    return (CHAT / 'ChatMenus.kt').read_text().split('internal fun ChatVoiceModeMenu(', 1)[1]


class VoiceLongPressMenuContractTest(unittest.TestCase):
    def test_voice_reuses_attachment_bottom_sheet_after_visibility_and_availability_gates(self):
        sheet = voice_sheet()
        self.assertIn('if (!expanded) return', sheet)
        labels = ['MenuEntry("语音输入"', 'MenuEntry("语音对话"', 'MenuEntry("豆包实时通话"']
        positions = [sheet.index(label) for label in labels]
        self.assertEqual(positions, sorted(positions))
        self.assertLess(sheet.index('.filter { it.action in available }'), sheet.index('if (entries.isEmpty()) return'))
        self.assertLess(sheet.index('if (entries.isEmpty()) return'), sheet.index('ChatSelectionSheet('))
        self.assertIn('ChatSelectionSheet("语音功能", onDismiss,', sheet)
        self.assertIn('showCloseButton = false', sheet)
        self.assertIn('titleFontSize = 20.sp', sheet)
        self.assertNotIn('subtitle =', sheet)
        self.assertNotIn('subtitleFontSize =', sheet)
        self.assertNotIn('选择要使用的语音功能', sheet)
        self.assertIn('Modifier.testTag("chat-voice-mode-menu").fillMaxWidth().verticalScroll(rememberScrollState())', sheet)
        self.assertNotIn('ChatAnchoredMenu(', sheet)

    def test_doubao_call_uses_its_own_handset_not_the_input_microphone(self):
        sheet = voice_sheet()
        self.assertIn('MenuEntry("语音输入", DaiyuIcons.Voice, ChatMenuAction.VOICE_INPUT)', sheet)
        self.assertIn('MenuEntry("语音对话", DaiyuIcons.Speak, ChatMenuAction.VOICE_CHAT)', sheet)
        self.assertIn('MenuEntry("豆包实时通话", DaiyuIcons.Call, ChatMenuAction.VOICE_DOUBAO)', sheet)
        icons = (SRC / 'ui/icons/DaiyuIcons.kt').read_text()
        call = icons.split('val Call: ImageVector by lazy {', 1)[1].split('    val ', 1)[0]
        for fragment in ('name = "Daiyu.Call"', 'defaultWidth = 24.dp', 'defaultHeight = 24.dp',
                         'viewportWidth = 24f, viewportHeight = 24f', 'fill = null',
                         'strokeLineWidth = 1.75f', 'StrokeCap.Round', 'StrokeJoin.Round', 'curveTo('):
            self.assertIn(fragment, call)
        self.assertNotIn('Daiyu.Voice', call)

    def test_voice_rows_match_attachment_density_not_small_popup(self):
        sheet = voice_sheet()
        attachment = (CHAT / 'ChatMenus.kt').read_text().split('internal fun ChatAttachmentSheet(', 1)[1].split('internal fun UserTurnMenu(', 1)[0]
        for fragment in ('.heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp))',
                         '.padding(horizontal = 12.dp, vertical = 14.dp)', 'Arrangement.spacedBy(16.dp)',
                         'Modifier.size(24.dp)', 'fontSize = 16.sp, lineHeight = 24.sp'):
            self.assertIn(fragment, sheet)
            self.assertIn(fragment, attachment)
        self.assertNotIn('ChatMenuItem(', sheet)

    def test_menu_state_is_revoked_by_availability_disabled_or_editor_changes(self):
        composer = (CHAT / 'ChatComposer.kt').read_text()
        self.assertIn('var voiceModes by remember(state.editorGeneration, state.enabled, state.voiceModes, callUi?.sessionKey)', composer)
        self.assertIn('val toolbarEnabled = state.enabled && !editing', composer)
        self.assertIn('val voiceMenuEnabled = toolbarEnabled && state.voiceModes.isNotEmpty()', composer)
        self.assertIn('ChatVoiceModeMenu(voiceModes && voiceMenuEnabled && callUi == null,', composer)

    def test_only_long_press_can_open_the_mode_picker(self):
        button = (CHAT / 'ChatComposer.kt').read_text().split('SpeechMorphButton(', 1)[1].split('onLongClick =', 1)[0]
        self.assertIn('onClick = { if (callUi != null || state.voiceMode in state.voiceModes) actions.onVoice() }', button)
        for forbidden in ('voiceModes = true', 'actions.onMenuAction', '.single()', 'size != 1'):
            self.assertNotIn(forbidden, button)
        composer = (CHAT / 'ChatComposer.kt').read_text()
        self.assertEqual(1, composer.count('voiceModes = true'))

    def test_disabled_long_press_is_removed_before_feedback_and_callback(self):
        composer = (CHAT / 'ChatComposer.kt').read_text().split('SpeechMorphButton(', 1)[1]
        self.assertIn('enabled = callUi != null || voiceMenuEnabled,', composer)
        self.assertNotIn('enabled = voiceMenuEnabled && callUi?.presentation != DoubaoCallPresentation.CLOSING', composer)
        self.assertIn('onLongClick = if (callUi != null || voiceMenuEnabled)', composer)
        self.assertIn('val admitted = actions.onVoiceLongPress()', composer)
        self.assertIn('if (admitted && callUi == null) voiceModes = true', composer)
        button = (CHAT / 'SpeechMorphButton.kt').read_text()
        self.assertIn('onLongClick: (() -> Unit)?', button)
        self.assertIn('onLongClick = onLongClick,', button)
        self.assertIn('onLongClickLabel = if (onLongClick != null) "选择语音功能" else null', button)
        self.assertNotIn('performHapticFeedback', button)
        self.assertNotIn('TouchHaptics.longPress', button)

    def test_selection_stays_open_without_audio_and_uses_model_picker_background(self):
        sheet = voice_sheet()
        self.assertIn('val selectionBackground = colors.selectionContainer', sheet)
        self.assertIn('val selectedBackground = colors.selectionContainer', (CHAT / 'ChatModelPicker.kt').read_text())
        self.assertIn('.background(if (isSelected) selectionBackground else Color.Transparent)', sheet)
        self.assertIn('.semantics { this.selected = isSelected }', sheet)
        self.assertIn('role = Role.RadioButton) { onAction(entry.action) }', sheet)
        self.assertIn('if (isSelected) Icon(DaiyuIcons.Check,', sheet)
        for forbidden in ('onDismiss()', 'onVoice()', 'startDuplex', 'requestDictation', 'VoiceSettingsPreferences'):
            self.assertNotIn(forbidden, sheet)
        menus = (CHAT / 'ChatMenus.kt').read_text()
        self.assertNotIn('dismissOnAction', menus)

    def test_real_settings_projection_and_execution_rechecks_remain(self):
        main = (SRC / 'MainActivity.kt').read_text()
        for feature, action in [('input', 'VOICE_INPUT'), ('conversation', 'VOICE_CHAT'), ('realtime', 'VOICE_DOUBAO')]:
            self.assertIn(f'if (voiceSettings.enabled("{feature}")) add(ChatMenuAction.{action})', main)
        self.assertIn('if (!VoiceSettingsPreferences(applicationContext).enabled(feature)) return@voice', main)

    def test_device_source_covers_modal_geometry_and_real_short_press_matrix(self):
        device = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ChatVoiceModeMenuActivityTest.kt').read_text()
        for fragment in ('for (mask in 0..7) for (selected in modes)', 'performTouchInput { click(center) }',
                         'listOf("voice:${selected.name}")', 'assertEquals(selected, state.voiceMode)',
                         'for (mask in 0..7)', 'performTouchInput { longClick() }',
                         'item.assertDoesNotExist()', 'assertTrue(events.isEmpty())',
                         'assertTrue(requests.isEmpty())', 'listOf(HapticKind.LONG_PRESS)',
                         'generation = state.editorGeneration + 1', 'enabled = false',
                         'node("chat-selection-sheet")', 'node("preview-dialog-viewport")',
                         'surface.width >= window.width * .75f', 'awaitNodeWindowFocus',
                         '@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)',
                         'node("preview-dialog-scrim").performTouchInput', 'KeyEvent.KEYCODE_BACK',
                         'state = state.copy(voiceMode = it)', 'row.assertIsSelected()',
                         'row.assertIsNotSelected()', 'captureScreenNode(row)', 'Color(0xFFEFEDFB)',
                         'applyInterfaceScale = false', 'candidate == mode, hasSelectedBackground'):
            self.assertIn(fragment, device)
        self.assertNotIn('menu.bottom <= button.top', device)


if __name__ == '__main__':
    unittest.main()
