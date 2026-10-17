package app.daiyu.android.workspace

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import app.daiyu.android.platform.DeviceQueries
import app.daiyu.android.platform.PlatformSafGrants
import org.json.JSONObject

internal data class WorkspaceFile(val uri: Uri, val name: String, val directory: Boolean)
internal data class WorkspaceListing(val files: List<WorkspaceFile>, val truncated: Boolean)

/** Uses the application's existing exact SAF grants; never turns a URI into a host path. */
internal class WorkspaceFilesAccess(
    private val grants: PlatformSafGrants,
    private val queryChildren: (Uri, () -> Boolean) -> JSONObject,
) {
    constructor(context: Context) : this(PlatformSafGrants(context), { tree, cancelled ->
        DeviceQueries.files(context, JSONObject().put("tree_uri", tree.toString()).put("limit", 50), cancelled)
    })

    fun grants(): List<Uri> = grants.grants()
    fun accept(data: Intent, tree: Boolean): Boolean = grants.accept(data, tree)
    fun revoke(uri: Uri) = grants.revoke(uri)

    fun files(tree: Uri, cancelled: () -> Boolean): WorkspaceListing {
        fun requireCurrent() {
            if (Thread.currentThread().isInterrupted || cancelled()) throw InterruptedException()
            check(tree in grants.grants() && DocumentsContract.isTreeUri(tree)) { "workspace_directory_unavailable" }
        }
        requireCurrent()
        val result = queryChildren(tree, cancelled)
        requireCurrent()
        check(result.optBoolean("ok")) { "workspace_directory_unavailable" }
        val data = result.getJSONObject("data")
        val items = data.getJSONArray("items")
        check(items.length() <= 50)
        val files = (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            val uri = Uri.parse(item.getString("uri"))
            check(uri.scheme == "content" && uri.authority == tree.authority &&
                DocumentsContract.isTreeUri(uri) &&
                DocumentsContract.getTreeDocumentId(uri) == DocumentsContract.getTreeDocumentId(tree))
            WorkspaceFile(uri, item.optString("name").ifBlank { "未命名文件" },
                item.optString("mime_type") == DocumentsContract.Document.MIME_TYPE_DIR)
        }
        return WorkspaceListing(files.distinctBy { it.uri }, data.getBoolean("truncated"))
    }
}
