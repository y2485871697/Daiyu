"""Source wiring guards for quiet single-click media submission."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/app/daiyu/android"


class MediaSendContract(unittest.TestCase):
    def test_video_picker_receiver_outlives_paused_composer_but_not_route_owner(self):
        activity = (SRC / "MainActivity.kt").read_text()
        route = activity.split('if (retainedPage == "chat" && !sidebarVisible) {', 1)[1].split('androidx.activity.compose.BackHandler', 1)[0]
        self.assertIn('DisposableEffect(videoHost, this@MainActivity, selected?.id, editorGeneration)', route)
        self.assertIn('VideoPickerBinding(videoHost, activityResultRegistry)', route)
        self.assertIn('picker.close()', route)
        self.assertIn('pickerBinding = picker', activity)
        self.assertIn('composerAttachments = { if (routeActive && !sidebarVisible)', activity)
        ui = (SRC / "ui/screens/chat/attachments/VideoAttachments.kt").read_text()
        self.assertIn('pickerBinding: VideoPickerBinding? = null', ui)
        self.assertIn('if (pickerBinding == null) picker.close()', ui)
        test = (ROOT / "app/src/androidTest/kotlin/app/daiyu/android/TextSendActivityTest.kt").read_text()
        success = test.split('fun testSuccessfulVideoPickerResultsSurvivePausedComposerDisposal()', 1)[1].split('fun testFirstTextAcceptance', 1)[0]
        for needle in ('awaitHomeWindowSettled', 'mainClock.advanceTimeBy', 'dispatchResult(requestCode, Activity.RESULT_OK, result)',
                       'else clipData =', 'data = uri', 'VideoHostDraftStore', 'openOriginal', 'fixture.opens.get()'):
            self.assertIn(needle, success)
        self.assertNotIn('host.picked(', success)

    def test_normal_picture_send_has_no_preparation_controls_or_status(self):
        activity = (SRC / "MainActivity.kt").read_text()
        self.assertNotIn('"取消图片准备"', activity)
        self.assertNotIn('"正在核实图片发送结果"', activity)
        attachments = (SRC / "ui/screens/chat/attachments/ImageAttachments.kt").read_text()
        self.assertNotIn('"image_attachments_submitting"', attachments)
        self.assertIn('"image-host-unknown"', activity)
        self.assertIn("imageHost.canRecover", activity)

    def test_video_single_click_keeps_unknown_and_duplicate_send_gates(self):
        ui = (SRC / "ui/screens/chat/attachments/VideoAttachments.kt").read_text()
        self.assertNotIn("VideoConfirmation", ui)
        self.assertNotIn('"video_attachments_confirmation"', ui)
        self.assertIn("host.submit(text.text, state)", ui)
        self.assertIn("state.fenced && !state.submitting && !state.recovering", ui)
        host = (SRC / "VideoChatHost.kt").read_text()
        self.assertIn("target.submitting = true", host)
        unknown = host.split("if (receipt.outcome == VideoDraftOutcome.UNKNOWN)", 1)[1].split("return true", 1)[0]
        self.assertIn("target.submitting = false", unknown)
        self.assertIn("videoReceiptMatches(request, receipt)", host)

    def test_thumbnail_handoff_is_bounded_and_failed_validation_revokes_cache(self):
        host = (SRC / "ImageChatHost.kt").read_text()
        self.assertIn("controller.confirmImageReceipt(frozen, result)", host)
        self.assertIn("lease.acceptedThumbnails(result)", host)
        self.assertIn("lease.installAcceptedThumbnails(thumbnails)", host)
        self.assertIn("8 * 1024 * 1024", host)
        self.assertIn("it.width <= maxEdge && it.height <= maxEdge", host)
        self.assertIn("messageThumbnails.remove(ref)", host)
        self.assertIn("draftThumbnails.evictAll(); messageThumbnails.evictAll()", host)
        ui = (SRC / "ui/screens/chat/conversation/MessageContent.kt").read_text()
        self.assertIn("loader.cached(image.expectedOwner, image.reference, 1024)", ui)
        self.assertIn("loadMessageImage(image, loader, 1024)", ui)

    def test_retained_media_projection_does_not_cross_dataset_or_commit_revision(self):
        source = (SRC / "MainActivity.kt").read_text()
        prepare = source.split("private fun prepareMessageMedia()", 1)[1].split("private var toolPresentations", 1)[0]
        self.assertIn("messageMediaSnapshot?.conversation != key.conversation", prepare)
        self.assertIn("messageMediaKey?.data != key.data", prepare)
        self.assertIn("messageMediaKey?.mediaCommit != key.mediaCommit", prepare)
        self.assertIn("currentMessageMediaKey() != key", prepare)

    def test_cloud_checks_include_video_host_and_exact_media_device_scope(self):
        workflow = (ROOT / ".github/workflows/media-inline-checks.yml").read_text()
        self.assertIn("--tests '*VideoChatHostTest'", workflow)
        self.assertIn("--tests '*ToolReceiptSourceTest'", workflow)
        self.assertIn("test_media_send_contract.py", workflow)
        smoke = (ROOT / "scripts/smoke_boot.sh").read_text()
        self.assertIn('media_send_only = command_for("", os.environ)[1] == "media-send-targeted"', smoke)
        self.assertIn("media_send_sources if media_send_only", smoke)
        discovery = next(line for line in smoke.splitlines() if 'discovery_scope="targeted" if ' in line)
        self.assertIn("media_send_only", discovery.split(' else "full"', 1)[0].split(" if ", 1)[1].split(" or "))

    def test_video_files_use_current_model_reasoning_and_ordinary_tool_route(self):
        controller = (SRC / "ChatController.kt").read_text()
        send = controller.split("val hasVideoFiles =", 1)[1].split("val preparedContext =", 1)[0]
        self.assertIn("videoAttempt?.prepareFiles", send)
        self.assertIn("messageVideoFiles.preflight", send)
        self.assertNotIn("ReasoningEffort.OFF", send)
        self.assertNotIn("fetchSavedVideoEvidence", send)
        self.assertIn("messageVideoFiles.wrap(delegate, requestProvider", controller)
        ordinary = controller.split("val ordinaryRun =", 1)[1].split("val prepared = try", 1)[0]
        self.assertNotIn("video", ordinary)
        self.assertIn("--tests '*ControllerVideoFilesTest'", (ROOT / ".github/workflows/media-inline-checks.yml").read_text())

    def test_local_video_manifest_is_not_native_video_upload_or_android_frame_extraction(self):
        source = (SRC / "ControllerVideoFiles.kt").read_text()
        for expected in ("assets.openOriginal", "ATOMIC_MOVE", "ref.sha256", "Linux 路径:",
                         "当前已选 Linux 环境", "不要切换 Linux 后端", "withAppendedUserText"):
            self.assertIn(expected, source)
        self.assertNotIn("data:video/", source)
        self.assertNotIn("MediaMetadataRetriever", source)
        self.assertNotIn("OpenRouterVideoCapability", source)

    def test_video_frame_temporary_files_use_linux_tmp_and_cleanup_only_analysis_outputs(self):
        source = (SRC / "ControllerVideoFiles.kt").read_text()
        manifest = source.split("val manifest = buildString", 1)[1].split("row.copy(blocks", 1)[0]
        for expected in ("当前 Linux 环境的包管理器安装", "Linux /tmp", "本次分析新建的独立临时目录",
                         "mktemp -d /tmp/daiyu-video-XXXXXX", "完成分析后，立即删除", "失败或取消时也要清理",
                         "不能在图片读取完成前删除", "不得清空整个 /tmp", "不得删除输入视频原件",
                         ".daiyu-video-inputs 中的原视频副本", "其他任务、用户的文件"):
            self.assertIn(expected, manifest)
        self.assertNotIn("及其已有工具", manifest)

    def test_draft_video_uses_shared_fullscreen_player_and_orientation_controls(self):
        ui = (SRC / "ui/screens/chat/attachments/VideoAttachments.kt").read_text()
        self.assertIn("ChatMediaPreviewDialog(selection, onSaveDocument", ui)
        self.assertIn("host.openPreviewOriginal", ui)
        self.assertNotIn("封面预览（非视频播放）", ui)
        controls = (SRC / "ui/media/ChatMediaPreviewVideoControls.kt").read_text()
        for expected in ("chat-media-video-play-pause", "chat-media-video-progress", "chat-media-video-orientation",
                         "切换横屏", "切换竖屏"):
            self.assertIn(expected, controls)

    def test_legacy_draft_video_save_result_is_owned_by_route_not_preview_composition(self):
        source = (SRC / "ui/screens/chat/attachments/VideoPickerBinding.kt").read_text()
        save = source.split("fun savePreviewOriginal", 1)[1].split("override fun close", 1)[0]
        self.assertLess(save.index("item.openOriginal()"), save.index("launcher.launch(item.displayName)"))
        self.assertIn("ActivityResultContracts.CreateDocument(item.mimeType)", save)
        self.assertIn("result.await()", save)
        self.assertIn("saveScope.cancel()", source)
        self.assertIn("staged?.delete()", save)
