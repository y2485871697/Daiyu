package app.daiyu.android.media

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.test.InstrumentationTestCase
import android.test.mock.MockContentResolver
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

@Suppress("DEPRECATION")
class AndroidImageAttachmentsTest : InstrumentationTestCase() {
    private lateinit var root: File
    private lateinit var source: File
    private lateinit var store: ImageAttachmentStore
    private lateinit var images: AndroidImageAttachments
    private val owner = ImageAttachmentOwner("image-conversation", "image-draft")
    private val uri = Uri.parse("content://test.daiyu.images/selected")
    private var sourceOpens = 0
    private var onOpen: () -> Unit = {}

    override fun setUp() {
        super.setUp()
        root = File(instrumentation.targetContext.cacheDir, "image-fixture-${UUID.randomUUID()}")
        check(root.mkdirs())
        source = File(root, "picked")
        val resolver = MockContentResolver()
        resolver.addProvider(uri.authority, object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "image/gif"
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
                sourceOpens++
                onOpen()
                return ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        })
        store = ImageAttachmentStore(File(root, "private"), AndroidImageInspector())
        images = AndroidImageAttachments(resolver, store)
    }
    override fun tearDown() {
        try { root.deleteRecursively() } finally { super.tearDown() }
    }
    private fun encoded(format: Bitmap.CompressFormat, width: Int = 64, height: Int = 32): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.BLUE)
            return ByteArrayOutputStream().use { output ->
                check(bitmap.compress(format, 92, output)); output.toByteArray()
            }
        } finally { bitmap.recycle() }
    }
    private fun failure(expected: ImageAttachmentFailure, action: () -> Unit) {
        try { action(); fail("Expected $expected") }
        catch (failure: ImageAttachmentException) { assertEquals(expected, failure.failure) }
    }

    fun testProviderMimeIsNotTrustedAndOriginalBytesSurviveProviderRemoval() {
        val original = encoded(Bitmap.CompressFormat.PNG)
        source.writeBytes(original)
        val ref = images.import(owner, uri)
        assertEquals("image/png", ref.mime)
        assertEquals(64, ref.width); assertEquals(32, ref.height)
        assertTrue(source.delete())
        val reopened = ImageAttachmentStore(File(root, "private"), AndroidImageInspector())
        assertTrue(original.contentEquals(reopened.read(owner, ref)))
        assertEquals(1, sourceOpens)
    }

    fun testJpegAndWebpAreDetectedFromActualBytes() {
        for ((format, mime) in listOf(Bitmap.CompressFormat.JPEG to "image/jpeg", Bitmap.CompressFormat.WEBP to "image/webp")) {
            source.writeBytes(encoded(format))
            val ref = images.import(owner, uri)
            assertEquals(mime, ref.mime)
            assertTrue(source.readBytes().contentEquals(store.read(owner, ref)))
        }
    }

    fun testPreviewIsBoundedAndDoesNotChangeStoredOriginal() {
        val original = encoded(Bitmap.CompressFormat.PNG, 401, 207)
        source.writeBytes(original)
        val ref = images.import(owner, uri)
        val preview = images.decodePreview(owner, ref, 80)
        try { assertTrue(preview.width <= 80); assertTrue(preview.height <= 80) }
        finally { preview.recycle() }
        assertTrue(original.contentEquals(store.read(owner, ref)))
    }

    fun testJpegPreviewHonorsOrientationWithoutReencodingOriginal() {
        source.writeBytes(encoded(Bitmap.CompressFormat.JPEG, 64, 32))
        val exif = ExifInterface(source.absolutePath)
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
        exif.saveAttributes()
        val original = source.readBytes()
        val ref = images.import(owner, uri)
        val preview = images.decodePreview(owner, ref)
        try { assertEquals(32, preview.width); assertEquals(64, preview.height) }
        finally { preview.recycle() }
        assertTrue(original.contentEquals(store.read(owner, ref)))
    }

    fun testUnsupportedOrInvalidInputIsRejectedWithoutReference() {
        source.writeBytes("not an image".toByteArray())
        failure(ImageAttachmentFailure.INVALID_IMAGE) { images.import(owner, uri) }
        assertTrue(store.list(owner).isEmpty())
        failure(ImageAttachmentFailure.SOURCE_UNAVAILABLE) { images.import(owner, Uri.fromFile(source)) }
        assertEquals(1, sourceOpens)
    }

    fun testCancelledImportDoesNotOpenProvider() {
        source.writeBytes(encoded(Bitmap.CompressFormat.PNG))
        val signal = CancellationSignal().apply { cancel() }
        failure(ImageAttachmentFailure.CANCELLED) { images.import(owner, uri, signal) }
        assertEquals(0, sourceOpens)
        assertTrue(store.list(owner).isEmpty())
    }

    fun testCancellationDuringOpenNeverPublishesReference() {
        source.writeBytes(encoded(Bitmap.CompressFormat.PNG))
        val signal = CancellationSignal()
        onOpen = { signal.cancel() }
        failure(ImageAttachmentFailure.CANCELLED) { images.import(owner, uri, signal) }
        assertTrue(store.list(owner).isEmpty())
    }

    fun testImageOnlyFreezePreservesPixelsAndRejectedCapabilityKeepsDraft() {
        val original = encoded(Bitmap.CompressFormat.PNG)
        source.writeBytes(original)
        val ref = images.import(owner, uri)
        failure(ImageAttachmentFailure.MODEL_UNSUPPORTED) { store.freeze(owner, listOf(ref), false) }
        val ready = store.freeze(owner, listOf(ref), true).single()
        assertTrue(original.contentEquals(ready.bytes()))
        assertEquals(listOf(ref), store.list(owner))
        store.clear(owner)
        assertTrue(store.list(owner).isEmpty())
        assertTrue(original.contentEquals(ready.bytes()))
    }
}
