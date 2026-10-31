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
        close = main.split('private fun closeDoubaoCall(key: Long)', 1)[1].split('private fun doubaoCallActions()', 1)[0]
        self.assertLess(close.index('beginClose(key, SystemClock.elapsedRealtime())'), close.index('remoteVoice.stopDuplex(key)'))
        self.assertIn('onHangUp = { if (current()) closeDoubaoCall(key) }', actions)
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
        self.assertIn('val iconColor = Color(style.icon)', overlay)
        self.assertIn('CallGlyph(CallControl.MIC, iconColor', overlay)
        self.assertIn('CallGlyph(CallControl.HANG_UP, iconColor', overlay)

    def test_preview_orb_model_and_svg_paths_are_consumed_by_native_renderer(self):
        geometry = (CHAT / 'DoubaoGenieGeometry.kt').read_text()
        overlay = (CHAT / 'DoubaoCallOverlay.kt').read_text()
        for value in ('DURATION_MILLIS = 420', 'ORB_DIAMETER = 66f', 'HALO_INSET = 7f',
                      'MINI_DIAMETER = 37f', 'MINI_WAVE_SCALE = .65f',
                      'floatArrayOf(12f, 21f, 29f, 20f, 11f)',
                      'floatArrayOf(0f, 500f, 900f, 300f, 700f)',
                      '0xFFEEE6FB', '0xFFA18AC7', '0xFFF6F2FC',
                      '0xFFE9DDF9', '0xFF9471BD', '0xFFEEE5F9', '0xFFA58CC5', '0xFF9F87BA',
                      'm7 10 5 5 5-5M7 5h10', 'M14 4h6v6m-6 0 6-6M10 20H4v-6m0 6 6-6'):
            self.assertIn(value, geometry)
        for value in ('DoubaoGenieGeometry.orbStyle(', 'DoubaoGenieGeometry.orbFrame(',
                      'Color(style.background)', 'Color(style.bars)', 'Color(style.halo)',
                      'Color(style.icon)', 'withInfiniteAnimationFrameNanos', 'Brush.radialGradient(',
                      'PathParser().parsePathString(svg).toPath()',
                      'DoubaoGenieGeometry.COLLAPSE_PATH', 'DoubaoGenieGeometry.EXPAND_PATH'):
            self.assertIn(value, overlay)
        self.assertIn('if (compact && connecting) 2300f else if (responding) 750f else 1450f', geometry)
        self.assertIn('pulse(elapsedMillis, 3000f)', geometry)
        self.assertIn('val width = 3f * barScale * unit', overlay)
        self.assertIn('(index - 2) * 6f * barScale * unit', overlay)

    def test_shared_420ms_progress_reverses_a_frozen_raster_not_live_child_layers(self):
        overlay = (CHAT / 'DoubaoCallOverlay.kt').read_text()
        screen = (CHAT / 'ChatScreen.kt').read_text()
        self.assertIn('remember(callUi?.sessionKey) { DoubaoCallMotionState() }', screen)
        self.assertIn('callMotion = motionState', screen)
        self.assertIn('motion = motionState', screen)
        self.assertIn('val progress = motion.progress', overlay)
        close = overlay.split('DoubaoCallPresentation.CLOSING -> {', 1)[1].split('DisposableEffect(motion)', 1)[0]
        self.assertLess(close.index('layer.toImageBitmap()'), close.index('progress.animateTo(0f'))
        self.assertIn('DoubaoGenieGeometry.DURATION_MILLIS * progress.value', close)
        self.assertNotIn('progress.snapTo(', close)
        self.assertIn('if (!closing || !snapshotReady)', overlay)
        self.assertIn('if (bitmap != null) drawImage(bitmap) else drawLayer(layer)', overlay)
        self.assertIn('running = !closing', overlay)

if __name__ == '__main__':
    unittest.main()
