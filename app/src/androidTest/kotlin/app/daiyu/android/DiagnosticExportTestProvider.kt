package app.daiyu.android

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.util.UUID

/** AndroidTest-only document target; never serves application or user files. */
class DiagnosticExportTestProvider : ContentProvider() {
    override fun onCreate() = true
    private fun target(uri: Uri): File {
        val id = checkNotNull(uri.lastPathSegment)
        require(uri.pathSegments.size == 1 && UUID.fromString(id).toString() == id)
        val root = File(checkNotNull(context).cacheDir, "diagnostic-export-test").apply { check(isDirectory || mkdirs()) }
        return File(root, "$id.json")
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(target(uri), ParcelFileDescriptor.parseMode(mode))
    override fun getType(uri: Uri) = "application/json"
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = if (target(uri).delete()) 1 else 0
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
