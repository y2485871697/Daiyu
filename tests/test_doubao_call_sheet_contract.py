"""Formal call overlay wiring; visual execution is a separate approved device batch."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'
CHAT = SRC / 'ui/screens/chat/conversation'

class DoubaoCallSheetContractTest(unittest.TestCase):
    def test_overlay_is_a_sibling_not_composer_extra_actions(self):
        screen = (CHAT / 'ChatScreen.kt').read_text()
        self.assertIn('DoubaoCallOverlay(callUi, callActions', screen)
        self.assertIn('voiceAnchor.translate(-overlayOrigin)', screen)
        self.assertIn('composerTop = composerBounds', screen)
        main = (SRC / 'MainActivity.kt').read_text()
        self.assertIn('callUi = doubaoCallPresentation.state?.takeIf', main)
        self.assertIn('callActions = doubaoCallActions()', main)
        self.assertNotIn('engineeringUi || remoteVoice.duplexActive', main)

    def test_actual_entry_bounds_and_original_indicator_are_retained(self):
        composer = (CHAT / 'ChatComposer.kt').read_text()
        self.assertIn('onVoiceAnchorBounds(it.boundsInRoot())', composer)
        self.assertIn('generating = state.showGenerationLoading()', composer)
        self.assertIn('listening = state.listening', composer)
        button = (CHAT / 'SpeechMorphButton.kt').read_text()
        self.assertIn('indicatorSize = 20.dp', button)
        self.assertIn('description ?: if (listening)', button)

    def test_hangup_freezes_before_immediate_backend_stop_and_completion_only_unmounts(self):
        main = (SRC / 'MainActivity.kt').read_text()
        actions = main.split('private fun doubaoCallActions()', 1)[1].split('@Composable private fun projectedVoiceComposer()', 1)[0]
        self.assertLess(actions.index('beginClose(key, SystemClock.elapsedRealtime())'), actions.index('remoteVoice.stopDuplex(key)'))
        completion = actions.split('onCloseAnimationFinished =', 1)[1]
        self.assertIn('finishClose(completedKey)', completion)
        self.assertNotIn('stopDuplex', completion)
        self.assertIn('remoteVoice.setDuplexMuted(key,', actions)
        self.assertIn('remoteVoice.setDuplexSpeaker(key,', actions)

    def test_minimize_is_presentation_only_and_typing_does_not_hangup(self):
        main = (SRC / 'MainActivity.kt').read_text()
        self.assertIn('onMinimize = { if (current()) doubaoCallPresentation.minimize(key) }', main)
        self.assertIn('!applyingDictationDraft && !doubaoCallPresentation.onDraftChanged(selected?.id?.value, remoteVoice.duplexActive)', main)
        owner = (SRC / 'DoubaoCallPresentationOwner.kt').read_text()
        self.assertNotIn('remoteVoice', owner)
        self.assertIn('fun finishClose(key: Long)', owner)
        self.assertIn('state?.sessionKey == key', owner)

    def test_session_snapshot_not_debug_text_drives_the_picture(self):
        main = (SRC / 'MainActivity.kt').read_text()
        self.assertIn('host.duplexSnapshot.collect', main)
        owner = (SRC / 'DoubaoCallPresentationOwner.kt').read_text()
        self.assertIn('snapshot.state.transcript', owner)
        self.assertIn('snapshot.state.reply', owner)
        self.assertNotIn('split(', owner)
        self.assertIn('snapshot.sessionKey == previous.sessionKey', owner)

    def test_long_captions_wrap_and_scroll_without_redesigning_the_panel(self):
        overlay = (CHAT / 'DoubaoCallOverlay.kt').read_text()
        caption = overlay.split('private fun CaptionLine(', 1)[1].split('@Composable', 1)[0]
        self.assertIn('heightIn(min = 20.dp)', caption)
        self.assertIn('softWrap = true', caption)
        self.assertNotIn('maxLines = 1', caption)
        self.assertIn('height(64.dp)', overlay)
        self.assertIn('padding(top = 7.dp, bottom = 5.dp).verticalScroll(', overlay)
        self.assertIn('CallGlyph(CallControl.MIC, Color(0xFFB1A4BD)', overlay)
        self.assertIn('CallGlyph(CallControl.HANG_UP, Color(0xFFAD9CBA)', overlay)

if __name__ == '__main__':
    unittest.main()
