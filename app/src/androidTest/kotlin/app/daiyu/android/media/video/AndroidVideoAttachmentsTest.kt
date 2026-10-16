package app.daiyu.android.media.video

import android.net.Uri
import android.os.CancellationSignal
import android.test.AndroidTestCase
import java.io.File
import java.util.UUID

/** Uses only static malformed bytes; no media is generated and no network/provider is accessed.
 * Positive device-codec fixtures are intentionally not claimed by these rejection tests.
 */
@Suppress("DEPRECATION")
class AndroidVideoAttachmentsTest : AndroidTestCase() {
    private lateinit var root: File
    private val owner = VideoAttachmentOwner("conversation", "draft")
    override fun setUp() {
        super.setUp()
        root = File(context.cacheDir, "video-rejections-${UUID.randomUUID()}").apply { mkdirs() }
    }
    override fun tearDown() {
        root.deleteRecursively()
        super.tearDown()
    }
    private fun expect(reason: VideoAttachmentFailure, block: () -> Unit) {
        try { block(); fail("Expected $reason") }
        catch (failure: VideoAttachmentException) { assertEquals(reason, failure.failure) }
    }

    fun testRecognizedHeaderAloneIsNotAnOriginalVideo() {
        val store = VideoAssetStore(root)
        val header = byteArrayOf(0, 0, 0, 16) + "ftypisom".toByteArray() + ByteArray(4)
        expect(VideoAttachmentFailure.INVALID_VIDEO) { store.import(owner, header.inputStream()) }
        assertTrue(store.list(owner).isEmpty())
        assertFalse(root.walkTopDown().any { it.isFile })
    }

    fun testJpegAndProviderMimeTextNeverBecomeVideoAssets() {
        val store = VideoAssetStore(root)
        for (bytes in listOf(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()), "video/mp4".toByteArray())) {
            expect(VideoAttachmentFailure.UNSUPPORTED_VIDEO) { store.import(owner, bytes.inputStream()) }
        }
        assertTrue(store.list(owner).isEmpty())
    }

    fun testCancellationAndNonContentUrisFailBeforeProviderAccess() {
        val facade = AndroidVideoAttachments(context.contentResolver, VideoAssetStore(root))
        expect(VideoAttachmentFailure.CANCELLED) {
            facade.import(owner, Uri.parse("content://invalid/video"), CancellationSignal().apply { cancel() })
        }
        for (source in listOf("https://invalid/video.mp4", "file:///private/video.mp4", "data:video/mp4;base64,AA==")) {
            expect(VideoAttachmentFailure.SOURCE_UNAVAILABLE) { facade.import(owner, Uri.parse(source)) }
        }
        assertTrue(facade.store.list(owner).isEmpty())
    }
}
