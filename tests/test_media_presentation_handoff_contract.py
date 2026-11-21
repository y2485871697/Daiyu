import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/kotlin/app/daiyu/android'


class MediaPresentationHandoffContract(unittest.TestCase):
    def test_image_identity_is_bound_before_asynchronous_history_admission(self):
        source = (SRC / 'MainActivity.kt').read_text()
        send = source.split('    private fun sendImageDraft(', 1)[1].split('    private fun settleImageReceipt', 1)[0]
        self.assertIn('owner.attempt !== attemptBefore', send)
        self.assertIn('userMessageEntrance?.messageId != frozen.identity.userId.value', send)
        self.assertIn('UserMessageEntrance(frozen.identity.userId.value, waitForIme = true, waitForMedia = true)', send)
        history = source.split('    private fun loadAcceptedHistory(', 1)[1].split('    private fun readAcceptedWindow', 1)[0]
        self.assertIn('userMessageEntrance?.messageId != user.id.value', history)
        self.assertIn('shown.none { it.second.id == user.id }', history)
        self.assertIn('shown.none { it.second.id == receipt.userId }', source)

    def test_video_owner_is_posted_before_any_new_user_can_commit(self):
        source = (SRC / 'ChatController.kt').read_text()
        send = source.split('    internal fun sendVideos(', 1)[1].split('    internal var screenScreenshotRevision', 1)[0]
        self.assertLess(send.index('bindPresentation.get(5, java.util.concurrent.TimeUnit.SECONDS)'),
                        send.index('sendPrepared(token, conversation, request.text'))
        self.assertIn('bindPresentation.cancel(false)', send)
        activity = (SRC / 'MainActivity.kt').read_text()
        self.assertIn('onPreparedUser = { userId ->', activity)
        self.assertIn('userEntranceVideoRequest === request && videoUi.editor() == original', activity)

    def test_exit_retains_only_drawn_pixels_and_uses_the_height_clock(self):
        source = (SRC / 'ui/screens/chat/conversation/ComposerAttachmentExit.kt').read_text()
        self.assertIn('frame.bitmap = ImageBitmap(width, height)', source)
        self.assertIn('draw(this, layoutDirection, checkNotNull(frame.canvas), size)', source)
        self.assertIn('this@drawWithContent.drawContent()', source)
        raster = source.rsplit('.drawWithContent {', 1)[1]
        self.assertIn('drawImage(checkNotNull(frame.bitmap))', raster)
        self.assertEqual(1, raster.count('drawContent()'))
        self.assertIn('drawImage(pixels, topLeft = Offset(0f, size.height - pixels.height)', source)
        self.assertIn('size.height / frame.exitStartHeight', source)
        self.assertIn('frame.targetHeight = child.height', source)
        self.assertIn('var targetHeight by mutableIntStateOf(0)', source)
        self.assertIn('if (frame.targetHeight > 0 && width > 0 && height > 0)', raster)
        self.assertIn('frame.visualRevision', source)
        for forbidden in ('delay(', 'finishSubmission', 'delete(', 'clearDraft', 'LaunchedEffect', 'rememberGraphicsLayer', 'toImageBitmap', 'launch('):
            self.assertNotIn(forbidden, source)
        composer = (SRC / 'ui/screens/chat/conversation/ChatComposer.kt').read_text()
        self.assertLess(composer.index('ComposerAttachmentExit {'), composer.index('attachmentContent()'))
        self.assertLess(composer.index('attachmentContent()'), composer.index('key(state.editorGeneration, actions.editingMessageKey) {'))

    def test_ready_images_use_asset_identity_without_weakening_operation_keys(self):
        source = (SRC / 'ui/screens/chat/attachments/ImageAttachments.kt').read_text()
        self.assertIn('key(if (item is ImageAttachmentItem.Ready) item.attachment else item.key)', source)
        self.assertIn('PreviewResult.Loading, item.attachment, maxEdge, attempt', source)
        self.assertIn('actions.onRemove(item.key)', source)

    def test_media_decode_refreshes_exit_raster_and_admits_the_final_page_lease(self):
        for name in ('ImageAttachments.kt', 'VideoAttachments.kt'):
            source = (SRC / 'ui/screens/chat/attachments' / name).read_text()
            self.assertIn('ObserveComposerAttachmentVisual(result)', source)
        activity = (SRC / 'MainActivity.kt').read_text()
        self.assertLess(activity.index('imageHost.prepareMessageThumbnails(it,'),
                        activity.index('if (snapshot != null) messageMediaSnapshot = snapshot'))
        self.assertIn('imageHost.historyLoader !== loader', activity)
        entrance = (SRC / 'ui/screens/chat/conversation/UserMessageEntrance.kt').read_text()
        self.assertIn('request.mediaReady && (!awaitPlacement || motion.positioned)', entrance)
        host = (SRC / 'ImageChatHost.kt').read_text()
        self.assertIn('videos.filter { it.expectedOwner.messageId == messageId }', host)
        self.assertIn('if (messageId == null) videos.takeLast(4)', host)
        self.assertIn('lease === target && target.isOpen()', host)


class MediaSubmissionHandoffContract(unittest.TestCase):
    def test_display_submission_does_not_grant_send_cancel_or_runtime_authority(self):
        root = Path(__file__).resolve().parents[1] / 'app/src/main/kotlin/app/daiyu/android'
        composer = (root / 'ui/screens/chat/conversation/ChatComposer.kt').read_text()
        model = (root / 'ui/screens/chat/conversation/ChatScreenModels.kt').read_text()
        host = (root / 'ImageChatHost.kt').read_text()
        main = (root / 'MainActivity.kt').read_text()
        self.assertIn('val mediaSubmitting = state.mediaSubmissionKey != null', composer)
        self.assertIn('val primaryEnabled = !mediaSubmitting &&', composer)
        self.assertIn('mediaSubmitting -> "正在发送"', composer)
        self.assertIn('if (mediaSubmitting) return@ChatIconButton', composer)
        self.assertIn('mediaSubmissionKey == null', model)
        self.assertIn('target.submissionVisualKey = frozen.identity.assistantId.value', host)
        finish = host.split('private fun finish(', 1)[1].split('private fun cancelTransient', 1)[0]
        self.assertLess(finish.index('outstanding--'), finish.index('target.submissionVisualKey = null'))
        self.assertLess(finish.index('settled(result, attempt.text)'), finish.index('target.submissionVisualKey = null'))
        image = main.split('private fun settleImageReceipt(', 1)[1].split('private val streamingHistoryRefresh', 1)[0]
        self.assertLess(image.index('controlOwner = controller.active?'), image.index('publishChat()'))
        video = main.split('private fun settleRetainedVideoReceipt()', 1)[1].split('// A mount', 1)[0]
        self.assertLess(video.index('controlOwner = controller.active?'), video.index('publishChat(); loadAcceptedHistory'))


    def test_background_preview_uses_route_ownership_and_video_cover_keeps_validation(self):
        host = (ROOT / "app/src/main/kotlin/app/daiyu/android/ui/media/ChatMediaPreviewHost.kt").read_text()
        self.assertIn("LocalPageInputOwned.current", host)
        self.assertIn("remember(previewOwner)", host)
        self.assertNotIn("LaunchedEffect(pageActive) { if (!pageActive)", host)
        self.assertNotIn("selection?.takeIf { pageActive }", host)
        controller = (ROOT / "app/src/main/kotlin/app/daiyu/android/ChatController.kt").read_text()
        section = controller[controller.index("internal fun messageVideoThumbnail"):controller.index("private fun <T> imageRead")]
        self.assertLess(section.index(".openOriginal("), section.index("messageVideoCovers.load("))
        self.assertIn("validateMessageVideo", controller)
        cache = (ROOT / "app/src/main/kotlin/app/daiyu/android/media/video/VideoThumbnailCache.kt").read_text()
        self.assertIn("32L * 1024 * 1024", cache)
        self.assertIn("videoDigest(it) != digest", cache)

    def test_video_prefetch_matches_list_resolution_and_keeps_window_intersections(self):
        host = (SRC / 'ImageChatHost.kt').read_text()
        self.assertIn('loader.loadVideo(video.expectedOwner, video.reference, 1024)', host)
        conversation = SRC / 'ui/screens/chat/conversation'
        listing = (conversation / 'ChatMessageList.kt').read_text()
        self.assertIn('loadMessageVideo(block.video, loader, 1024)', listing)
        self.assertIn('loadMessageImage(block.image, loader, 384)', listing)
        self.assertIn('prefetch.update(it)', listing)
        scheduler = (conversation / 'MessageVideoThumbnailPrefetch.kt').read_text()
        self.assertIn('items.distinct().take(4).toSet()', scheduler)
        self.assertIn('jobs.keys.filter { it !in wanted }', scheduler)
        self.assertIn('jobs.getOrPut(item)', scheduler)

if __name__ == '__main__':
    unittest.main()
