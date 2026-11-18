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
