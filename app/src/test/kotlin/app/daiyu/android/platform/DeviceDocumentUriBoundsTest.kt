package app.daiyu.android.platform

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Pure URI fixtures: no provider, persisted grant, filesystem or permission acquisition. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, manifest = Config.NONE)
class DeviceDocumentUriBoundsTest {
    private val tree = Uri.parse("content://r06.fixture/tree/root")
    private val prefixLength = DocumentsContract.buildDocumentUriUsingTree(tree, "").toString().length

    @Test fun listFilesAcceptsComplete2048UriAndRejects2049WithoutTruncatingIdentifier() {
        val id = "x".repeat(2048 - prefixLength)
        val expected = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString()
        assertEquals(2048, expected.length)
        val emitted = DeviceQueries.documentUri(tree, id)
        assertEquals(expected, emitted)
        assertEquals(id, DocumentsContract.getDocumentId(Uri.parse(emitted!!)))
        for (tool in listOf(PlatformReadTool.FILE_METADATA, PlatformReadTool.FILE_TEXT)) {
            assertEquals(expected, tool.schema.parse(JSONObject().put("uri", emitted).toString()).text("uri"))
        }
        val tooLong = DocumentsContract.buildDocumentUriUsingTree(tree, id + "y").toString()
        assertEquals(2049, tooLong.length)
        assertNull(DeviceQueries.documentUri(tree, id + "y"))
        for (tool in listOf(PlatformReadTool.FILE_METADATA, PlatformReadTool.FILE_TEXT)) {
            try { tool.schema.parse(JSONObject().put("uri", tooLong).toString()); fail("oversized URI accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun boundAppliesToCompleteEncodedUriNotOnlyRawDocumentId() {
        val encodedId = " ".repeat(100) + "x".repeat(2048 - prefixLength - 300)
        val expected = DocumentsContract.buildDocumentUriUsingTree(tree, encodedId).toString()
        assertEquals(2048, expected.length)
        assertEquals(expected, DeviceQueries.documentUri(tree, encodedId))
        assertEquals(encodedId, DocumentsContract.getDocumentId(Uri.parse(expected)))
        assertNull(DeviceQueries.documentUri(tree, encodedId + "x"))
        assertNull(DeviceQueries.documentUri(tree, " ".repeat(700))) // Raw ID fits; encoded URI does not.
    }

    @Test fun settingsGrantAndTreeSchemaHaveTheSame2048Boundary() {
        for (isTree in listOf(false, true)) {
            val prefix = "content://r06.fixture/${if (isTree) "tree" else "document"}/"
            val accepted = prefix + "a".repeat(2048 - prefix.length)
            assertEquals(2048, accepted.length)
            val intent = Intent().setData(Uri.parse(accepted)).addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            assertEquals(Uri.parse(accepted), PlatformSettingsAccess.acceptedUri(intent, isTree))
            intent.data = Uri.parse(accepted + "b")
            assertNull(PlatformSettingsAccess.acceptedUri(intent, isTree))
            if (isTree) {
                assertEquals(accepted, PlatformReadTool.FILES.schema.parse(
                    JSONObject().put("tree_uri", accepted).toString()).text("tree_uri"))
                try { PlatformReadTool.FILES.schema.parse(JSONObject().put("tree_uri", accepted + "b").toString()); fail() }
                catch (_: IllegalArgumentException) { }
            }
        }
    }
}
