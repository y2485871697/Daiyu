package app.daiyu.android

import android.test.InstrumentationTestCase
import app.daiyu.android.media.AndroidImageInspector
import app.daiyu.android.model.generation.*
import java.util.concurrent.atomic.AtomicInteger

/** Real Android decode + unchanged client preflight. All media are static local fixtures;
 * HTTP is a tripwire, never a network connection. No paid call, URI grant, DB, or encoding. */
@Suppress("DEPRECATION")
class GenerationInputImportTest : InstrumentationTestCase() {
    private val png get() = app.daiyu.android.validDeviceTestPng()
    private val inspector = AndroidImageInspector()
    private val edit = GenerationProtocol.OPENAI_IMAGE_EDIT_MULTIPART

    private fun import(bytes: ByteArray, target: GenerationInputTarget = GenerationInputTarget.REFERENCE,
        reference: GenerationInput? = null): GenerationInput = GenerationInputImport.read(
        "content://synthetic/local", { bytes.inputStream() }, inspector, edit, target, reference) { false }

    fun testStaticPngFixtureDecodesPixelsAndNeverSharesMutableBytes() {
        val bytes = validDeviceTestPng()
        val image = checkNotNull(android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        try { assertEquals(1, image.width); assertEquals(1, image.height) } finally { image.recycle() }
        assertEquals("image/png", inspector.inspect(bytes).mime)
        bytes[0] = 0
        assertEquals(137.toByte(), validDeviceTestPng()[0])
    }

    fun testRealDecodedPngAndSameSizeMaskPreserveExactOriginalBytes() {
        val bytes = png
        val reference = import(bytes)
        val mask = import(bytes, GenerationInputTarget.MASK, reference)
        assertEquals("image/png", reference.mimeType)
        assertTrue(bytes.contentEquals(reference.bytes))
        assertTrue(bytes.contentEquals(mask.bytes))
        assertEquals(1, inspector.inspect(reference.bytes).width)
        assertEquals(1, inspector.inspect(mask.bytes).height)
        bytes[0] = 0
        assertEquals(137.toByte(), reference.bytes[0])
    }

    fun testHeaderOnlyCounterfeitAndNonContentSourcesNeverBecomeInputs() {
        for (bytes in listOf(byteArrayOf(1, 2, 3), png.copyOf(24), "not an image".toByteArray())) {
            assertTrue(runCatching { import(bytes) }.isFailure)
        }
        val opens = AtomicInteger()
        for (source in listOf("file:///private/image.png", "https://remote.invalid/image.png", "data:image/png,x")) {
            assertTrue(runCatching { GenerationInputImport.read(source,
                { opens.incrementAndGet(); png.inputStream() }, inspector, edit,
                GenerationInputTarget.REFERENCE, null) { false } }.isFailure)
        }
        assertEquals(0, opens.get())
    }

    fun testInvalidRealMediaAndProtocolInputsCauseZeroPostAndZeroReservations() {
        val posts = AtomicInteger()
        val reservations = AtomicInteger()
        val assets = object : GenerationAssets {
            override fun inspect(kind: GenerationKind, bytes: ByteArray, declaredMime: String): GenerationMediaInfo {
                check(kind == GenerationKind.IMAGE)
                val info = inspector.inspect(bytes)
                require(info.mime == declaredMime)
                return GenerationMediaInfo(info.mime, info.width, info.height)
            }
            override fun commit(identity: GenerationIdentity, outputIndex: Int, kind: GenerationKind,
                bytes: ByteArray, info: GenerationMediaInfo): GenerationAssetReceipt = error("no output expected")
        }
        val store = object : GenerationAttemptStore {
            override fun reserve(attempt: GenerationAttempt): GenerationReservation {
                reservations.incrementAndGet(); error("invalid input reached reservation")
            }
            override fun finish(attempt: GenerationAttempt, submission: Submission) = error("no reservation")
        }
        val io = GenerationIo(assets, store, { false }, GenerationHttp {
            posts.incrementAndGet(); error("invalid input reached HTTP")
        })
        val images = ImageGenerationClient(io)
        val videos = VideoGenerationClient(io)
        val valid = import(png)
        fun request(protocol: GenerationProtocol, refs: List<GenerationInput>, mask: GenerationInput? = null) =
            GenerationRequest(GenerationIdentity("operation", "item"), protocol,
                when (protocol) {
                    GenerationProtocol.OPENAI_IMAGES_JSON -> "https://synthetic.invalid/images/generations"
                    GenerationProtocol.OPENAI_IMAGE_EDIT_MULTIPART -> "https://synthetic.invalid/images/edits"
                    GenerationProtocol.OPENAI_VIDEO_MULTIPART -> "https://synthetic.invalid/videos"
                }, "synthetic-key", "explicit-model", "draw", refs, mask)
        val invalidImageRequests = listOf(
            request(edit, listOf(GenerationInput(byteArrayOf(1), "image/png"))),
            request(edit, listOf(GenerationInput(png.copyOf(24), "image/png"))),
            request(edit, listOf(GenerationInput(png, "image/jpeg"))),
            request(edit, listOf(valid), GenerationInput(byteArrayOf(1), "image/png")),
            request(edit, listOf(valid, valid)),
            request(GenerationProtocol.OPENAI_IMAGES_JSON, listOf(valid)))
        for (request in invalidImageRequests) assertTrue(images.submit(request) is Submission.NotExecuted)
        assertTrue(videos.submit(request(GenerationProtocol.OPENAI_VIDEO_MULTIPART,
            listOf(valid, valid))) is Submission.NotExecuted)
        assertTrue(videos.submit(request(GenerationProtocol.OPENAI_VIDEO_MULTIPART,
            listOf(valid), valid)) is Submission.NotExecuted)
        assertEquals(0, posts.get())
        assertEquals(0, reservations.get())
    }
}
