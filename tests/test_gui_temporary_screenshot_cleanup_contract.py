"""Terminal physical drain owns cleanup of this run's private screen captures."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/kotlin/app/daiyu/android'


class TemporaryScreenScreenshotCleanupContract(unittest.TestCase):
    def test_controller_cleans_only_after_terminal_result_and_physical_device_drain(self):
        source = (APP / 'ChatController.kt').read_text()
        cleanup = source.split('    private fun cleanupRun(lifetime:RunResources)', 1)[1].split('    @Synchronized internal fun canRetryQuestionCleanup', 1)[0]
        start = cleanup.index('if (!lifetime.screenImagesCleaned)')
        self.assertLess(cleanup.index('if(lifetime.result==null) return'), start)
        self.assertLess(cleanup.index('if(!lifetime.deviceCleanup.drained) return'), start)
        self.assertLess(start, cleanup.index('lifetime.writer.shutdown()'))
        self.assertIn('screenshotExecutor.execute', cleanup)
        self.assertIn('screenToolImages.finishRun(', cleanup)
        self.assertIn('lifetime.initial.conversationId.value, lifetime.initial.runId.value', cleanup)
        self.assertIn('if (!lifetime.screenImagesCleaning)', cleanup)
        self.assertIn('handler.post { synchronized(this)', cleanup)
        success = cleanup.split('cleaned.fold(onSuccess = {', 1)[1].split('}, onFailure = {', 1)[0]
        failure = cleanup.split('}, onFailure = {', 1)[1].split('})', 1)[0]
        self.assertIn('lifetime.screenImagesCleaned = true', success)
        self.assertIn('screenScreenshotRevision++', success)
        self.assertIn('cleanupRun(lifetime)', success)
        self.assertNotIn('lifetime.screenImagesCleaned = true', failure)
        self.assertNotIn('cleanupRun(lifetime)', failure)
        self.assertIn('temporary_screen_capture_cleanup_failed', failure)
        self.assertIn('请重试停止', failure)
        self.assertIn('lifetime.screenImagesCleanupFailed = true', failure)
        retry = source.split('internal fun canRetryQuestionCleanup(', 1)[1].split('    private fun closeStorageWhenIdle', 1)[0]
        self.assertIn('it.screenImagesCleanupFailed && !it.screenImagesCleaning', retry)

    def test_only_registered_files_are_deleted_and_existing_previews_are_not_adopted(self):
        source = (APP / 'media/ScreenToolImageStore.kt').read_text()
        receipt = source.split('    fun receipt(', 1)[1].split('    @Synchronized', 1)[0]
        finish = source.split('    fun finishRun(', 1)[1].split('    @Synchronized', 1)[0]
        self.assertLess(receipt.index('if (!Files.exists(target.toPath(), NOFOLLOW_LINKS))'), receipt.index('owned.add(target)'))
        self.assertLess(receipt.index('Files.move('), receipt.index('owned.add(target)'))
        self.assertIn('owned.add(temporary)', receipt)
        self.assertIn('owned.remove(temporary)', receipt)
        self.assertIn('check(!wasCleared(conversation, run))', receipt)
        self.assertIn('put("temporary", temporaryFiles[Owner(conversation, run)]?.contains(target) == true)', receipt)
        self.assertIn('val files = temporaryFiles[owner] ?: return 0', finish)
        self.assertIn('for (file in files)', finish)
        self.assertIn('checkMediaPath(file)', finish)
        self.assertIn('Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && file.delete()', finish)
        self.assertLess(finish.index('file.delete()'), finish.index('marker.createNewFile()'))
        self.assertLess(finish.index('it.fd.sync()'), finish.index('temporaryFiles.remove(owner)'))
        for forbidden in ('walkTopDown', 'listFiles', 'deleteRecursively', 'attachments', 'browser_screenshots'):
            self.assertNotIn(forbidden, finish)

    def test_cleared_receipts_have_explicit_ui_state_and_do_not_offer_reload(self):
        source = (APP / 'ChatController.kt').read_text()
        loader = source.split('    internal fun loadToolScreenshot(', 1)[1].split('    internal fun ', 1)[0]
        self.assertIn('record.invocation.name == "screen"', loader)
        self.assertIn('asset?.optBoolean("temporary") == true && screenToolImages.wasCleared(owner.conversationId, owner.runId)', loader)
        self.assertIn('complete(if (valid) image else null, valid && cleared)', loader)
        ui = (APP / 'ui/screens/chat/conversation/ToolScreenshotContent.kt').read_text()
        self.assertIn('controller.screenScreenshotRevision', ui)
        self.assertIn('临时截图已清理', ui)
        self.assertIn('if (!loading && !cleared)', ui)
        self.assertIn('if (cleaned) expanded = false', ui)

    def test_verified_preview_is_seeded_synchronously_and_failed_reads_remain_retryable(self):
        controller = (APP / 'ChatController.kt').read_text()
        loader = controller.split('    internal fun loadToolScreenshot(', 1)[1].split('    @Synchronized internal fun ', 1)[0]
        self.assertLess(loader.index('if (cached != null)'), loader.index('screenshotExecutor.execute'))
        commit = loader.split('val valid = synchronized(this)', 1)[1].split('if (!valid) image?.recycle()', 1)[0]
        self.assertIn('screenScreenshotRevision == key.screenshotRevision', commit)
        self.assertIn('if (current) toolScreenshotPreviews.put(key, image, cleared)', commit)
        cache = (APP / 'ToolScreenshotPreviewCache.kt').read_text()
        self.assertIn('8 * 1024 * 1024', cache)
        self.assertIn('if (image != null || cleared)', cache)
        self.assertNotIn('.recycle()', cache)
        ui = (APP / 'ui/screens/chat/conversation/ToolScreenshotContent.kt').read_text()
        self.assertIn('if (!controller.ready || controller.dataBusy) return', ui)
        self.assertIn('mutableStateOf(initial?.image)', ui)
        self.assertIn('mutableStateOf(initial == null)', ui)
        self.assertIn('controller.dataRevision', ui)
        self.assertIn('onDispose { visible = false }', ui)

    def test_cleanup_and_close_drop_preview_cache_without_recycling_borrowed_images(self):
        controller = (APP / 'ChatController.kt').read_text()
        cleanup = controller.split('    private fun cleanupRun(lifetime:RunResources)', 1)[1].split('    @Synchronized internal fun canRetryQuestionCleanup', 1)[0]
        self.assertIn('toolScreenshotPreviews.clear(); screenScreenshotRevision++', cleanup)
        close = controller.split('    internal fun close() {', 1)[1]
        self.assertIn('toolScreenshotPreviews.clear()', close)
        workflow = (ROOT / '.github/workflows/gui-screenshot-checks.yml').read_text()
        self.assertIn('--tests app.daiyu.android.ToolScreenshotPreviewCacheTest', workflow)

    def test_incremental_cloud_scope_includes_store_and_native_receipt_regressions_without_packaging(self):
        source = (ROOT / '.github/workflows/gui-screenshot-checks.yml').read_text()
        self.assertIn('options: [screenshot, direct-read, temporary-cleanup, gestures]', source)
        targeted = source.split('      - name: 独立截图路由及资源生命周期定向检查（不打包）', 1)[1].split('      - name: 核对定向报告及零安装包', 1)[0]
        self.assertIn('test_gui_temporary_screenshot_cleanup_contract', targeted)
        for name in ('ScreenToolImageStoreTest', 'ScreenToolImageInputTest', 'ToolScreenshotReceiptUnitTest'):
            self.assertIn(name, targeted)
        self.assertIn('checks_only.init.gradle', targeted)
        self.assertNotIn('assemble', targeted)
        self.assertNotIn('packageDebug', targeted)
