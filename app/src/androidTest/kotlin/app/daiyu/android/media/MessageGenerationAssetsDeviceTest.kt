package app.daiyu.android.media

import android.test.AndroidTestCase
import app.daiyu.android.model.generation.GenerationKind
import app.daiyu.android.model.generation.GenerationMediaInfo
import app.daiyu.android.storage.GenerationAttemptBinding
import java.io.File
import java.util.Base64

/** Static offline fixtures only. No HTTP, paid submission, codec encoding or generated video. */
@Suppress("DEPRECATION")
class MessageGenerationAssetsDeviceTest : AndroidTestCase() {
    private lateinit var root: File
    private lateinit var owner: GenerationAssetOwner
    private val png: ByteArray get() = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+kF9sAAAAASUVORK5CYII=")

    override fun setUp() {
        super.setUp()
        root = File(context.cacheDir, "generation-assets-device-test")
        root.deleteRecursively()
        check(root.mkdirs())
        owner = GenerationAssetOwner("offline-fixture-account",
            GenerationAttemptBinding("conversation", "assistant", "run", "call", "image_generation"))
    }
    override fun tearDown() {
        try { root.deleteRecursively() } finally { super.tearDown() }
    }
    private fun store() = MessageGenerationAssets(root, GenerationAssetFence(owner) { true })
    private fun invalid(action: () -> Unit) {
        try { action(); fail("Expected real media inspection to reject fixture") }
        catch (failure: GenerationAssetException) { assertEquals(GenerationAssetFailure.INVALID_MEDIA, failure.failure) }
    }

    fun testStaticPngReallyDecodesAndRecoversOriginalWithoutRecompression() {
        val target = store()
        val identity = owner.binding.identity("image")
        val bytes = png
        val info = target.inspect(GenerationKind.IMAGE, bytes, "application/octet-stream")
        assertEquals(GenerationMediaInfo("image/png", 1, 1), info)
        val first = target.commit(identity, 0, GenerationKind.IMAGE, bytes, info)
        val second = store().commit(identity, 1, GenerationKind.IMAGE, bytes, info)
        val reopened = store()
        assertEquals(first, reopened.pending(identity, 0)!!.receipt)
        assertEquals(second, reopened.pending(identity, 1)!!.receipt)
        assertEquals(first, reopened.commit(identity, 0, GenerationKind.IMAGE, bytes, info))
        assertTrue(bytes.contentEquals(reopened.openOriginal(first).use { it.readBytes() }))
    }

    fun testMimeSpoofingAndBypassedInspectionCannotPublish() {
        invalid { store().inspect(GenerationKind.IMAGE, png, "image/jpeg") }
        invalid { store().commit(owner.binding.identity("image"), 0, GenerationKind.IMAGE,
            png, GenerationMediaInfo("image/png", 999, 999)) }
        invalid { store().inspect(GenerationKind.IMAGE, "image/png".toByteArray(), "image/png") }
        assertFalse(root.walkTopDown().any { it.name == "original" })
    }

    fun testVideoNeedsARealTrackSampleAndDecodedFrameNotAContainerHeaderOrPng() {
        val header = byteArrayOf(0, 0, 0, 16) + "ftypisom".toByteArray(Charsets.US_ASCII) + ByteArray(4)
        invalid { store().inspect(GenerationKind.VIDEO, header, "video/mp4") }
        invalid { store().inspect(GenerationKind.VIDEO, png, "video/mp4") }
        invalid { store().commit(owner.binding.identity("video"), 0, GenerationKind.VIDEO,
            header, GenerationMediaInfo("video/mp4", 1, 1)) }
        assertFalse(root.walkTopDown().any { it.name == "original" })
    }
}
