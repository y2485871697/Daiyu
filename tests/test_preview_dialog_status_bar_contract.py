"""检查预览对话框与状态栏的窗口源码配置。"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/app/daiyu/android"


class PreviewDialogStatusBarContractTest(unittest.TestCase):
    def test_fixed_window_configuration_is_keyed_to_window_and_presentation(self):
        source = (SRC / "ui/components/PreviewDialog.kt").read_text()
        fixed = source.split('DisposableEffect(window, bottomSheet) {', 1)[1].split('onDispose { }', 1)[0]
        self.assertEqual(1, source.count('window?.setDimAmount(0f)'))
        self.assertEqual(1, source.count('window?.setWindowAnimations(0)'))
        self.assertIn('window?.setDimAmount(0f)', fixed)
        self.assertIn('if (bottomSheet) window?.setWindowAnimations(0) else window?.usePlatformDialogFade()', fixed)
        self.assertNotIn('SideEffect {', source)
        self.assertIn('scrimColor ?: Color.Black.copy(alpha = dimAmount)', source)
        self.assertIn('radius = scrimBlurRadius, saturation = 1f', source)

    def test_preview_does_not_force_status_text_or_navigation_bar_appearance(self):
        source = (SRC / "ui/components/PreviewDialog.kt").read_text()
        self.assertNotIn("isAppearanceLightStatusBars", source)
        self.assertNotIn("isAppearanceLightNavigationBars", source)
        self.assertNotIn("WindowCompat", source)
        self.assertNotIn("activity.window", source)

    def test_default_scrim_reuses_original_window_dimming_without_double_darkening(self):
        source = (SRC / "ui/components/PreviewDialog.kt").read_text()
        self.assertIn("val dimAmount = remember(window) { window?.attributes?.dimAmount ?: 0f }", source)
        self.assertLess(source.index("val dimAmount ="), source.index("window?.setDimAmount(0f)"))
        self.assertIn("scrimColor ?: Color.Black.copy(alpha = dimAmount)", source)
        self.assertIn(".previewDialogDimBackground(dialogDimStack,", source)
        self.assertIn("Color.Black.copy(alpha = dimAmount), motion)", source)
        self.assertNotIn("0x2620172d", source)
        self.assertNotIn("0x55000000", source)

    def test_workspace_and_selection_keep_the_shared_window_path(self):
        workspace = (SRC / "ui/components/ConversationWorkspaceSheet.kt").read_text()
        agent = (SRC / "ui/components/subagent/ConversationAgentStyle.kt").read_text()
        selection = (SRC / "ui/screens/chat/conversation/ChatSelectionSheet.kt").read_text()
        self.assertIn("ConversationAgentSheet(onDismiss", workspace)
        self.assertIn("PreviewDialog(onDismiss, bottomSheet = true)", agent)
        self.assertIn("PreviewDialog(onDismiss, bottomSheet = true,", selection)



MEDIA = SRC / "ui/media"

class PreviewDialogContract(unittest.TestCase):
    def test_status_bar_effect_only_uses_preview_dialog_window(self):
        source = (MEDIA / 'ChatMediaPreviewSystemBars.kt').read_text()
        self.assertIn('view.parent as? DialogWindowProvider', source)
        self.assertIn('BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE', source)
        self.assertIn('onDispose { lease?.close() }', source)
        self.assertNotIn('Activity', source.split('internal class', 1)[1])
        self.assertIn('ChatMediaPreviewHideStatusBars()', (MEDIA / 'ChatMediaPreviewHost.kt').read_text())

    def test_download_uses_original_stream_and_video_awaits_release(self):
        storage = (MEDIA / 'ChatMediaPreviewStorage.kt').read_text()
        self.assertIn('item.openOriginal()', storage)
        self.assertIn('chatMediaPreviewCopy(original.stream', storage)
        self.assertNotIn('Bitmap.compress', storage)
        video = (MEDIA / 'ChatMediaPreviewVideo.kt').read_text()
        self.assertIn('awaitCancellation()', video)
        self.assertIn('view.stopPlayback()', video)
        self.assertIn('Lifecycle.State.RESUMED', video)

    def test_media_download_first_frame_has_no_mutating_status_bar_padding(self):
        host = (MEDIA / 'ChatMediaPreviewHost.kt').read_text()
        self.assertNotIn('statusBarsPadding()', host)
        self.assertIn('Modifier.align(Alignment.TopEnd).padding(24.dp)', host)
        download = host.split('Icon(Icons.Rounded.Download', 1)[1].split('if (saving)', 1)[0]
        self.assertIn('tint = MaterialTheme.colorScheme.primary', download)
        self.assertNotIn('Color.White', download)

    def test_video_poster_survives_prepared_until_current_source_texture(self):
        video = (MEDIA / 'ChatMediaPreviewVideo.kt').read_text()
        self.assertIn('mutableStateOf(item.cachedImage())', video)
        self.assertIn('if (!firstFrame && !failed) cover?.let', video)
        self.assertNotIn('CircularProgressIndicator', video)
        self.assertIn('view.setOnFirstFrameListener { firstFrame = true }', video)
        view = (MEDIA / 'ChatMediaPreviewVideoView.kt').read_text()
        self.assertIn('generation == sourceGeneration', view)
        self.assertIn('MEDIA_INFO_VIDEO_RENDERING_START', view)
        self.assertIn('surface === surfaceTexture', view)
        self.assertIn('firstFrame.renderingStarted(generation)', view)
        self.assertIn('firstFrame.textureUpdated(sourceGeneration)', view)
        self.assertIn('firstFrame.reset(sourceGeneration)', view)
        self.assertIn('firstFrameListener?.invoke()', view)

    def test_video_completion_and_seek_are_native_events_not_polling_guesses(self):
        video = (MEDIA / 'ChatMediaPreviewVideo.kt').read_text()
        self.assertIn('player.isLooping = false', video)
        self.assertIn('view.setOnCompletionListener { controls = controls.completed() }', video)
        self.assertIn('view.setOnSeekCompleteListener { controls = controls.seekCompleted() }', video)
        self.assertLess(video.index('controls = controls.finishSeeking()'),
                        video.index('view.seekTo(controls.positionMillis)', video.index('onSeekFinished =')))
        progress = (MEDIA / 'ChatMediaPreviewVideoProgress.kt').read_text()
        self.assertIn('val fraction = value.coerceIn(0f, 1f)', progress)
        self.assertNotIn('position.animateTo', progress)
        self.assertNotIn('Animatable(target)', progress)
        bridge = (SRC / 'ChatMediaPreviewBridge.kt').read_text()
        self.assertIn('displayLoader?.cachedVideo(video.expectedOwner, video.reference, 4096)', bridge)
        self.assertIn('if (!lease.isOpen()) null', bridge)

    def test_committed_video_invalidates_projection_even_when_editor_no_longer_matches(self):
        source = (SRC / 'MainActivity.kt').read_text()
        receipt = source.split('private fun settleRetainedVideoReceipt()', 1)[1].split('// A mount', 1)[0]
        self.assertLess(receipt.index('messageMediaCommitRevision++'), receipt.index('if (accepted && conversation != null)'))
        self.assertIn('editorGeneration, messageMediaCommitRevision)', source)

    def test_project_identity_guard_accepts_media_port(self):
        import runpy
        runpy.run_path(str(ROOT / 'scripts/verify_project_identity.py'))['verify_tree'](ROOT)

    def test_video_controls_time_and_icons_follow_the_shared_theme(self):
        controls = (MEDIA / 'ChatMediaPreviewVideoControls.kt').read_text()
        time = controls.split('text = "${chatMediaPreviewVideoTime(', 1)[1].split('IconButton(', 1)[0]
        self.assertIn('color = MaterialTheme.colorScheme.primary', time)
        self.assertNotIn('Color.White', time)
        self.assertIn('Modifier.size(24.dp).testTag("chat-media-video-play-pause-icon")', controls)
        self.assertIn('Modifier.size(24.dp).testTag("chat-media-video-orientation-icon").padding(4.dp)', controls)

    def test_visible_native_progress_has_no_poll_and_tween_tail_lag(self):
        video = (MEDIA / 'ChatMediaPreviewVideo.kt').read_text()
        self.assertIn('controls.visible) {', video)
        self.assertIn('if (!controls.visible) return@LaunchedEffect', video)
        self.assertIn('withFrameNanos { controls = controls.positionChanged(view.currentPosition) }', video)
        self.assertNotIn('delay(200)', video)
        progress = (MEDIA / 'ChatMediaPreviewVideoProgress.kt').read_text()
        self.assertIn('val fraction = value.coerceIn(0f, 1f)', progress)
        self.assertIn('val playedX = size.width * fraction', progress)
        self.assertNotIn('position.animateTo', progress)
        self.assertIn('height.animateTo', progress)


if __name__ == "__main__":
    unittest.main()
