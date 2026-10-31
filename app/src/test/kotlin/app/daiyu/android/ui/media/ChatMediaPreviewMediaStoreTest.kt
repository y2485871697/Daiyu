package app.daiyu.android.ui.media

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ChatMediaPreviewMediaStoreTest {
    @Test
    fun originalBytesArePublishedOnlyAfterCopyAndSourceIsClosed() = runBlocking {
        val provider = installProvider()
        val bytes = ByteArray(20_000) { it.toByte() }
        val source = CloseTrackingStream(bytes)
        val result = saveChatMediaPreviewOriginal(RuntimeEnvironment.getApplication(), item(source))
        assertTrue(result)
        assertTrue(source.closed)
        assertArrayEquals(bytes, provider.output.readBytes())
        assertEquals("Movies/Daiyu", provider.inserted.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
        assertEquals("original.mp4", provider.inserted.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
        assertEquals("video/mp4", provider.inserted.getAsString(MediaStore.MediaColumns.MIME_TYPE))
        assertEquals(1, provider.inserted.getAsInteger(MediaStore.MediaColumns.IS_PENDING).toInt())
        assertEquals(0, provider.published?.getAsInteger(MediaStore.MediaColumns.IS_PENDING)?.toInt())
        assertFalse(provider.deleted)
    }

    @Test
    fun publicationFailureDeletesPendingEntryAndClosesSource() = runBlocking {
        val provider = installProvider().apply { failPublish = true }
        val source = CloseTrackingStream(byteArrayOf(1, 2, 3))
        assertFalse(saveChatMediaPreviewOriginal(RuntimeEnvironment.getApplication(), item(source)))
        assertTrue(provider.deleted)
        assertTrue(source.closed)
    }

    @Test
    fun unavailableAuthorizedOriginalCreatesNoEntry() = runBlocking {
        val provider = installProvider()
        val item = ChatMediaPreviewItem("authorized-key", "image/png", "thumbnail.png", { null }, { null })
        assertFalse(saveChatMediaPreviewOriginal(RuntimeEnvironment.getApplication(), item))
        assertFalse(provider.wasInserted)
    }

    @Test
    fun inputCloseFailureAfterPublicationStillReportsCommittedSave() = runBlocking {
        val provider = installProvider()
        val source = object : ByteArrayInputStream(byteArrayOf(5, 6, 7)) {
            override fun close() { throw IOException("input close failed") }
        }
        assertTrue(saveChatMediaPreviewOriginal(RuntimeEnvironment.getApplication(), item(source)))
        assertEquals(0, provider.published?.getAsInteger(MediaStore.MediaColumns.IS_PENDING)?.toInt())
        assertArrayEquals(byteArrayOf(5, 6, 7), provider.output.readBytes())
        assertFalse(provider.deleted)
    }

    @Test
    fun cleanupFailureDoesNotReplaceCancellation() = runBlocking {
        val provider = installProvider().apply { failDelete = true }
        val cancellation = CancellationException("owner cancelled")
        val source = object : InputStream() {
            override fun read(): Int = throw cancellation
        }
        try {
            saveChatMediaPreviewOriginal(RuntimeEnvironment.getApplication(), item(source))
            throw AssertionError("Cancellation must propagate")
        } catch (actual: CancellationException) {
            org.junit.Assert.assertSame(cancellation, actual)
            assertTrue(actual.suppressed.any { it is IOException })
        }
        assertTrue(provider.deleted)
    }

    @Test
    @Config(sdk = [28])
    fun selectedDocumentReceivesOriginalBytesWithoutStoragePermission() = runBlocking {
        val provider = installProvider()
        val bytes = byteArrayOf(9, 8, 7, 6)
        val source = CloseTrackingStream(bytes)
        assertTrue(saveChatMediaPreviewDocument(
            RuntimeEnvironment.getApplication(), item(source), Uri.parse("content://media/selected-document"),
        ))
        assertArrayEquals(bytes, provider.output.readBytes())
        assertTrue(source.closed)
        assertFalse(provider.wasInserted)
    }

    private fun item(source: InputStream) = ChatMediaPreviewItem(
        key = "authorized-key",
        mimeType = "video/mp4",
        displayName = "preview.mp4",
        loadImage = { error("Saving must never request a thumbnail") },
        openOriginal = { ChatMediaPreviewOriginal(source, "video/mp4", "original.mp4") },
    )

    private fun installProvider(): RecordingProvider {
        val context = RuntimeEnvironment.getApplication()
        val provider = RecordingProvider(File.createTempFile("media-store-", ".bin", context.cacheDir))
        provider.attachInfo(context, ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
        return provider
    }

    private class CloseTrackingStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
        override fun close() { closed = true; super.close() }
    }

    private class RecordingProvider(val output: File) : ContentProvider() {
        var inserted = ContentValues()
        var published: ContentValues? = null
        var wasInserted = false
        var deleted = false
        var failPublish = false
        var failDelete = false
        override fun onCreate() = true
        override fun getType(uri: Uri): String = "video/mp4"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            wasInserted = true
            inserted = ContentValues(requireNotNull(values))
            return Uri.withAppendedPath(uri, "1")
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            if (failPublish) return 0
            published = ContentValues(requireNotNull(values))
            return 1
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            deleted = true
            if (failDelete) throw IOException("provider cleanup failed")
            output.delete()
            return 1
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE)
    }
}
