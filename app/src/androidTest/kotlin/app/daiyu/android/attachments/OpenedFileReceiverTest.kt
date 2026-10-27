package app.daiyu.android.attachments

import android.content.*
import android.content.pm.ProviderInfo
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.test.InstrumentationTestCase
import android.test.mock.MockContentResolver
import java.io.File
import java.util.UUID

@Suppress("DEPRECATION")
class OpenedFileReceiverTest : InstrumentationTestCase() {
    private lateinit var directory: File
    private lateinit var source: File
    private lateinit var context: Context
    private lateinit var resolver: MockContentResolver
    private lateinit var store: OpenedFileStore
    private var denied = false
    private val sourceUri = Uri.parse("content://opened-file-test/document.txt")
    override fun setUp() {
        super.setUp()
        directory = File(instrumentation.targetContext.cacheDir, "opened-file-test-${UUID.randomUUID()}").apply { check(mkdirs()) }
        source = File(directory, "source.txt").apply { writeText("中文附件😀\nline two\n") }
        resolver = MockContentResolver()
        context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getFilesDir() = directory
            override fun getContentResolver() = resolver
            override fun getApplicationContext(): Context = this
        }
        resolver.addProvider("opened-file-test", object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "text/plain"
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
                if (denied) throw SecurityException("source grant revoked")
                return ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) =
                MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf("中文附件.txt")) }
            override fun insert(uri: Uri, values: ContentValues?): Uri? = error("unused")
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = error("unused")
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = error("unused")
        })
        store = OpenedFileStore(context)
    }
    override fun tearDown() { try { directory.deleteRecursively() } finally { super.tearDown() } }
    private fun imported() = store.import(sourceUri, null, 1024, CancellationSignal())
    fun testTemporarySourceCopySurvivesRevocationAndStoreReopen() {
        val bytes = source.readBytes(); val document = imported()
        denied = true; source.delete()
        val reopened = OpenedFileStore(context)
        assertTrue(reopened.owns(document.uri)); assertEquals(document, reopened.document(document.uri))
        assertTrue(bytes.contentEquals(reopened.file(document.uri).readBytes()))
        assertEquals("中文附件.txt", document.name); assertEquals("text/plain", document.mime)
        val provider = OpenedFileProvider()
        provider.attachInfo(context, ProviderInfo().apply { authority = "${context.packageName}.opened.attachments"; exported = false })
        assertEquals("text/plain", provider.getType(document.uri))
        provider.query(document.uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null).use {
            assertTrue(it.moveToFirst()); assertEquals(document.name, it.getString(0)); assertEquals(bytes.size.toLong(), it.getLong(1))
        }
        ParcelFileDescriptor.AutoCloseInputStream(provider.openFile(document.uri, "r")).use { assertTrue(bytes.contentEquals(it.readBytes())) }
        assertTrue(runCatching { provider.openFile(document.uri, "rw") }.isFailure)
    }
    fun testCopyFailureAndCancellationPublishNoDocument() {
        assertTrue(runCatching { store.import(sourceUri, null, 1, CancellationSignal()) }.isFailure)
        assertTrue(File(directory, "opened-attachments").listFiles().orEmpty().isEmpty())
        val cancelled = CancellationSignal().apply { cancel() }
        assertTrue(runCatching { store.import(sourceUri, null, 1024, cancelled) }.isFailure)
        assertTrue(File(directory, "opened-attachments").listFiles().orEmpty().isEmpty())
        denied = true
        assertTrue(runCatching { imported() }.isFailure)
        assertTrue(File(directory, "opened-attachments").listFiles().orEmpty().isEmpty())
    }
    fun testOwnedResolverRejectsTraversalForeignAuthorityAndIncompleteFiles() {
        val document = imported()
        for (uri in listOf(document.uri.buildUpon().appendPath("content").build(),
            document.uri.buildUpon().authority("other-app").build(), document.uri.buildUpon().fragment("x").build(),
            Uri.parse("content://${context.packageName}.opened.attachments/%2e%2e"))) assertFalse(store.owns(uri))
        store.file(document.uri).delete(); assertFalse(store.owns(document.uri))
    }
    fun testViewClipDataAndShareStreamsAreMergedInStableOrderWithoutDuplicates() {
        val second = Uri.parse("content://opened-file-test/two.pdf")
        val data = Intent(Intent.ACTION_SEND_MULTIPLE).setData(sourceUri).putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(sourceUri, second))
        data.clipData = ClipData.newUri(resolver, "selected", second).apply { addItem(ClipData.Item(sourceUri)) }
        assertEquals(listOf(sourceUri, second), OpenedFileIntents.uris(data))
        assertEquals(listOf(sourceUri), OpenedFileIntents.uris(Intent(Intent.ACTION_VIEW).setData(sourceUri)))
        assertEquals(listOf(second), OpenedFileIntents.uris(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, second)))
    }
    fun testInvalidAndOversizedBatchesRejectWithoutReadingProvider() {
        for (uri in listOf(Uri.parse("file:///private.txt"), Uri.parse("https://example.invalid/document"), Uri.parse("content://opened-file-test/tree/one"))) {
            assertTrue(runCatching { OpenedFileIntents.uris(Intent(Intent.ACTION_VIEW).setData(uri)) }.isFailure)
        }
        val streams = ArrayList((0..OpenedFileIntents.MAX_FILES).map { Uri.parse("content://opened-file-test/$it") })
        assertTrue(runCatching { OpenedFileIntents.uris(Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, streams)) }.isFailure)
        assertTrue(runCatching { OpenedFileIntents.uris(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, Intent())) }.isFailure)
        assertTrue(runCatching { OpenedFileIntents.uris(Intent(Intent.ACTION_VIEW)) }.isFailure)
    }
    fun testInboxIsSingleUseAndDoesNotReplaceAnUnconsumedBatch() {
        OpenedAttachmentInbox.take()
        val first = OpenedAttachmentInbox.Batch(null, listOf(imported()))
        try {
            assertTrue(OpenedAttachmentInbox.offer(first)); assertFalse(OpenedAttachmentInbox.offer(first))
            assertSame(first, OpenedAttachmentInbox.take()); assertNull(OpenedAttachmentInbox.take())
        } finally { OpenedAttachmentInbox.take() }
    }
}
