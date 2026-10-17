package app.daiyu.android.platform

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.test.InstrumentationTestCase
import android.test.mock.MockContentResolver
import org.json.JSONObject

/** In-memory provider fixtures only; never connects to an installed user-data provider. */
@Suppress("DEPRECATION")
class DeviceQueryBoundsTest : InstrumentationTestCase() {
    private class FixtureProvider : ContentProvider() {
        var queries = 0
        var returned: MatrixCursor? = null
        override fun onCreate() = true
        override fun getType(uri: Uri): String = "vnd.android.cursor.dir/fixture"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            queries++
            // Deliberately ignores query limit hints, as a real provider is allowed to do.
            return MatrixCursor(arrayOf("_id", "name")).apply {
                repeat(75) { addRow(arrayOf(it.toLong(), "fixture-" + "x".repeat(1024))) }
                returned = this
            }
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri? = throw AssertionError("no writes")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw AssertionError("no writes")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw AssertionError("no writes")
    }

    private fun fixture(): Pair<FixtureProvider, MockContentResolver> {
        val provider = FixtureProvider()
        provider.attachInfo(instrumentation.targetContext, ProviderInfo().apply { authority = "r06.fixture"; exported = false })
        val resolver = MockContentResolver().apply { addProvider("r06.fixture", provider) }
        return provider to resolver
    }

    fun testProviderIgnoringHintsStillHasBoundedRowsFieldsAndClosedCursor() {
        val (provider, resolver) = fixture()
        val result = DeviceQueries.rows(resolver, Uri.parse("content://r06.fixture/rows"), arrayOf("_id", "name"),
            null, null, null, 3, { false }) { cursor ->
            JSONObject().put("id", cursor.getLong(0)).put("name", boundedText(cursor.getString(1)))
        }
        assertTrue(result.getBoolean("ok")); assertEquals(1, provider.queries)
        val data = result.getJSONObject("data")
        assertEquals(3, data.getJSONArray("items").length())
        assertTrue(data.getBoolean("truncated")); assertTrue(data.getBoolean("fields_truncated"))
        for (index in 0 until 3) assertEquals(256, data.getJSONArray("items").getJSONObject(index).getString("name").length)
        assertTrue(provider.returned!!.isClosed)
    }

    fun testCancelledQueryNeverTouchesFixtureProvider() {
        val (provider, resolver) = fixture()
        try {
            DeviceQueries.rows(resolver, Uri.parse("content://r06.fixture/rows"), arrayOf("_id", "name"),
                null, null, null, 3, { true }) { JSONObject() }
            fail("cancelled read reached provider")
        } catch (_: android.os.OperationCanceledException) { }
        assertEquals(0, provider.queries)
    }
}
