package app.daiyu.android.platform

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.test.InstrumentationTestCase
import android.test.mock.MockContentResolver
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Exercises the real metadata/text backend with synthetic, exactly read-granted provider documents. */
@Suppress("DEPRECATION")
class DocumentTextReadTest : InstrumentationTestCase() {
    private lateinit var directory: File
    private lateinit var source: File
    private lateinit var context: Context
    private var mime: String? = "application/octet-stream"
    private var name = "scroll-flicker（1）.log"
    private var granted = true
    private var opens = 0
    private var queries = 0
    private var typeReads = 0
    private var pipe = false
    private var sliceStart = 0L
    private var sliceLength = AssetFileDescriptor.UNKNOWN_LENGTH
    private val file = Uri.parse("content://document-text-test/document/scroll-flicker%EF%BC%881%EF%BC%89.log")
    private val child = Uri.parse("content://document-text-test/tree/fixture/document/scroll-flicker%EF%BC%881%EF%BC%89.log")

    override fun setUp() {
        super.setUp()
        directory = File(instrumentation.targetContext.cacheDir, "document-text-${UUID.randomUUID()}").apply { check(mkdirs()) }
        source = File(directory, "fixture.bin").apply { writeText("首行😀\nneedle 中文\n末行\n") }
        val resolver = MockContentResolver()
        context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = directory
            override fun getContentResolver() = resolver
            override fun checkUriPermission(uri: Uri, pid: Int, uid: Int, modeFlags: Int): Int {
                assertEquals(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION, modeFlags)
                return if (granted && (uri == file || uri == child)) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
            }
        }
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri): String? { typeReads++; return mime }
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): MatrixCursor {
                queries++
                val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
                return MatrixCursor(columns).apply {
                    addRow(columns.map<String, Any> { if (it == OpenableColumns.DISPLAY_NAME) name else source.length() }.toTypedArray())
                }
            }
            override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
                assertEquals("r", mode); assertTrue(granted); opens++
                if (pipe) {
                    val ends = ParcelFileDescriptor.createPipe()
                    ParcelFileDescriptor.AutoCloseOutputStream(ends[1]).use { it.write(source.readBytes()) }
                    return AssetFileDescriptor(ends[0], 0, AssetFileDescriptor.UNKNOWN_LENGTH)
                }
                return AssetFileDescriptor(ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY), sliceStart, sliceLength)
            }
            override fun insert(uri: Uri, values: ContentValues?): Uri? = error("unused")
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = error("unused")
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = error("unused")
        }
        provider.attachInfo(context, ProviderInfo().apply { authority = file.authority; exported = false })
        resolver.addProvider(file.authority!!, provider)
    }

    override fun tearDown() { try { directory.deleteRecursively() } finally { super.tearDown() } }
    private fun backend() = AndroidPlatformReads(context, DeviceResultPolicy(setOf("read_text_file", "file_metadata"), setOf("read_text_file", "file_metadata")))
    private fun read(uri: Uri = file, args: JSONObject = JSONObject(), tool: PlatformReadTool = PlatformReadTool.FILE_TEXT): JSONObject =
        JSONObject(backend().read(tool, tool.schema.parse(args.put("uri", uri.toString()).toString())) { false })
    private fun data(result: JSONObject): JSONObject { assertTrue(result.toString(), result.getBoolean("ok")); return result.getJSONObject("data") }

    fun testGenericMissingAndBlankMimeReadOriginalNameByLinesForFileAndTreeChild() {
        assertEquals(0, opens)
        for (type in listOf(null, "", "application/octet-stream", "Application/Octet-Stream; charset=UTF-8")) {
            mime = type
            for (uri in listOf(file, child)) {
                val result = data(read(uri, JSONObject().put("start_line", 2).put("line_count", 1)))
                assertEquals("needle 中文\n", result.getString("text"))
                assertEquals(1, result.getInt("lines_returned"))
                assertEquals("explicit_content_uri_grant", result.getString("scope"))
            }
        }
        assertEquals(8, opens); assertEquals(8, queries)
    }

    fun testGenericTextSearchPagingAndBackendRecreationUseSourceByteOffsets() {
        val original = "日志😀\n".repeat(2400) + "needle 中文\n"
        source.writeText(original)
        val first = data(read(args = JSONObject().put("line_count", 200)))
        val offset = first.getLong("next_offset_bytes")
        assertTrue(first.getBoolean("truncated")); assertTrue(offset > 0)
        val second = data(read(args = JSONObject().put("offset_bytes", offset).put("line_count", 200)))
        assertEquals(first.getString("text") + second.getString("text"), original.take(first.getString("text").length + second.getString("text").length))
        val search = data(read(child, JSONObject().put("query", "NEEDLE").put("line_count", 1)))
        assertEquals("needle 中文\n", search.getString("text"))
        assertTrue(first.getString("text").toByteArray().size <= DocumentTextWindow.MAX_OUTPUT_BYTES)
    }

    fun testMetadataAndMalformedUriKeepExactReadGrantBoundaryWithoutOpeningContent() {
        val metadata = read(tool = PlatformReadTool.FILE_METADATA)
        assertTrue(metadata.toString(), metadata.getBoolean("ok")); assertEquals(0, opens)
        val malformed = Uri.parse(file.toString().replace("%EF%BC%89", "%EF%89"))
        val invalid = read(malformed)
        assertEquals("invalid_uri", invalid.getString("error")); assertTrue(invalid.getString("hint").contains("exactly"))
        assertEquals(0, typeReads)
        val other = Uri.parse(file.toString().replace(".log", "-other.log"))
        assertEquals("uri_read_grant_required", read(other).getString("error")); assertEquals(0, opens)
    }

    fun testExplicitNonTextMimeAndUnknownGenericNamesRejectBeforeContentOpen() {
        for (type in listOf("application/pdf", "image/png", "application/zip", "application/unknown")) {
            mime = type
            assertEquals("unsupported_document_format", read().getString("error"))
        }
        assertEquals(0, queries)
        for (type in listOf(null, "", "application/octet-stream")) {
            mime = type
            for (displayName in listOf("document", "archive.zip", "file.log.exe")) {
                name = displayName
                assertFalse(read().getBoolean("ok"))
            }
        }
        assertEquals(0, opens)
    }

    fun testBinaryHeadersAreRejectedEvenWhenContinuationSkipsTheirHeader() {
        for (content in listOf("%PDF-1.7\nplain ascii body\n", "GIF89a ASCII body\n", "PK\u0003\u0004ASCII body\n")) {
            source.writeText(content)
            for (offset in listOf(0L, 10L)) {
                val result = read(args = JSONObject().put("offset_bytes", offset))
                assertEquals("binary_document", result.getString("error")); assertFalse(result.has("data"))
            }
        }
    }

    fun testNulAndMalformedUtf8DoNotBecomeSuccessfulText() {
        source.writeText("normal\u0000binary")
        assertEquals("binary_document", read().getString("error"))
        source.writeBytes(byteArrayOf(0x61, 0xc3.toByte(), 0x28))
        assertEquals("invalid_utf8", read().getString("error"))
    }

    fun testRevokedGrantAndMissingDestinationConsentNeverReadProvider() {
        granted = false
        assertEquals("uri_read_grant_required", read().getString("error"))
        assertEquals(0, typeReads); assertEquals(0, queries); assertEquals(0, opens)
        granted = true
        val noConsent = AndroidPlatformReads(context)
        val args = PlatformReadTool.FILE_TEXT.schema.parse(JSONObject().put("uri", file.toString()).toString())
        assertEquals("private_result_consent_required", JSONObject(noConsent.read(PlatformReadTool.FILE_TEXT, args) { false }).getString("error"))
        assertEquals(0, typeReads); assertEquals(0, queries); assertEquals(0, opens)
    }

    fun testNonSeekableHeaderReplayPreservesOffsetAndFirstLine() {
        pipe = true
        source.writeText("first line\nneedle 中文\nlast\n")
        assertEquals("first line\n", data(read(args = JSONObject().put("line_count", 1))).getString("text"))
        val result = data(read(args = JSONObject().put("offset_bytes", "first line\n".toByteArray().size).put("line_count", 1)))
        assertEquals("needle 中文\n", result.getString("text"))
    }

    fun testHeaderReplayRespectsAssetDescriptorSliceAndExplicitTextMime() {
        val text = "首行😀\nneedle 中文\n末行\n"
        val prefix = "%PDF-fake outside slice\n".toByteArray()
        source.writeBytes(prefix + text.toByteArray() + "hidden tail".toByteArray())
        sliceStart = prefix.size.toLong(); sliceLength = text.toByteArray().size.toLong()
        assertEquals(text, data(read()).getString("text"))
        mime = "Text/Plain; charset=UTF-8"
        assertEquals(text, data(read()).getString("text"))
        assertEquals(1, queries)
    }
}
