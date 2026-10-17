package app.daiyu.android.platform

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.DocumentsContract
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject

/** Preserved DeviceQueries read adapters, narrowed to current Catalog ownership and consent.
 * No listener/global cache. Cursor consumption and fields remain bounded even if providers ignore hints.
 */
internal object DeviceQueries {
    fun notifications(context: Context, args: JSONObject, cancelled: () -> Boolean): JSONObject {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return failure("unsupported")
        val available = manager.activeNotifications
        val items = JSONArray()
        var fieldsTruncated = false
        for (item in available.sortedByDescending { it.postTime }) {
            checkReadCancellation(cancelled)
            if (items.length() >= args.queryLimit()) break
            val extras = item.notification.extras
            val title = extras?.getCharSequence(Notification.EXTRA_TITLE)
            val text = extras?.getCharSequence(Notification.EXTRA_TEXT)
            fieldsTruncated = fieldsTruncated || clippedText(title) || clippedText(text)
            items.put(JSONObject().put("package", boundedText(item.packageName)).put("id", item.id)
                .put("posted_at", item.postTime).put("title", boundedText(title) ?: JSONObject.NULL)
                .put("text", boundedText(text) ?: JSONObject.NULL))
        }
        return success(JSONObject().put("scope", "own_app_only").put("items", items).put("limit", args.queryLimit())
            .put("truncated", available.size > items.length() || fieldsTruncated).put("fields_truncated", fieldsTruncated)
            .put("possibly_truncated", items.length() == args.queryLimit()).put("history_available", false))
    }

    fun calendar(context: Context, args: JSONObject, cancelled: () -> Boolean): JSONObject {
        val start = args.getLong("start_ms"); val end = args.getLong("end_ms")
        if (end <= start || end - start > AndroidPlatformReads.MAX_CALENDAR_WINDOW_MS) return failure("invalid_time_window")
        requirePermission(context, Manifest.permission.READ_CALENDAR)?.let { return it }
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, start); ContentUris.appendId(builder, end)
        val projection = arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY)
        return rows(context.contentResolver, builder.build(), projection, null, null,
            "${CalendarContract.Instances.BEGIN} ASC", args.queryLimit(), cancelled) { cursor ->
            JSONObject().put("event_id", cursor.getLong(0)).put("title", boundedText(cursor.getString(1)))
                .put("start_ms", cursor.getLong(2)).put("end_ms", cursor.getLong(3)).put("all_day", cursor.getInt(4) != 0)
        }
    }

    fun contacts(context: Context, args: JSONObject, cancelled: () -> Boolean): JSONObject {
        requirePermission(context, Manifest.permission.READ_CONTACTS)?.let { return it }
        // Catalog requires a nonblank filter: never silently broaden this to a full contact dump.
        val query = args.getString("query").trim()
        if (query.isEmpty()) return failure("invalid_arguments")
        val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(query))
        return rows(context.contentResolver, uri,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            null, null, "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC", args.queryLimit(), cancelled) { cursor ->
            JSONObject().put("contact_id", cursor.getLong(0)).put("display_name", boundedText(cursor.getString(1)))
        }.apply { if (optBoolean("ok")) getJSONObject("data").put("scope", "display_names_only") }
    }

    internal fun mediaPermission(kind: String): String = if (Build.VERSION.SDK_INT >= 33) when (kind) {
        "image" -> Manifest.permission.READ_MEDIA_IMAGES
        "video" -> Manifest.permission.READ_MEDIA_VIDEO
        else -> Manifest.permission.READ_MEDIA_AUDIO
    } else Manifest.permission.READ_EXTERNAL_STORAGE

    internal fun selectedMedia(context: Context, kind: String): Boolean = Build.VERSION.SDK_INT >= 34 && kind != "audio" &&
        !hasPermission(context, mediaPermission(kind)) && hasPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

    fun media(context: Context, args: JSONObject, cancelled: () -> Boolean): JSONObject {
        val kind = args.getString("kind")
        val permission = mediaPermission(kind)
        val selectedOnly = selectedMedia(context, kind)
        if (!hasPermission(context, permission) && !selectedOnly)
            return failure("permission_denied", permissions = listOf(permission))
        val uri = when (kind) {
            "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        return rows(context.contentResolver, uri, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED),
            null, null, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC", args.queryLimit(), cancelled) { cursor ->
            JSONObject().put("uri", ContentUris.withAppendedId(uri, cursor.getLong(0)).toString())
                .put("name", boundedText(cursor.getString(1))).put("mime_type", boundedText(cursor.getString(2)))
                .put("size_bytes", cursor.getLong(3)).put("modified_epoch_seconds", cursor.getLong(4))
        }.apply { if (optBoolean("ok")) getJSONObject("data").put("authorization", if (selectedOnly) "selected_only" else "media_permission") }
    }

    fun files(context: Context, args: JSONObject, cancelled: () -> Boolean): JSONObject {
        val tree = Uri.parse(args.getString("tree_uri"))
        if (tree.scheme != "content" || tree.userInfo != null || tree.fragment != null || !DocumentsContract.isTreeUri(tree))
            return failure("invalid_arguments", "saf_tree_required")
        // Exact persisted grant only: no arbitrary raw paths, authority traversal or transient URI probes.
        if (context.contentResolver.persistedUriPermissions.none { it.isReadPermission && it.uri == tree })
            return failure("permission_denied", "persisted_tree_read_grant_required")
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val items = JSONArray()
        var scanned = 0
        var fieldsTruncated = false
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        val cursor = context.contentResolver.query(children, projection, null, null, null)
            ?: return failure("unavailable", "provider_returned_no_cursor")
        cursor.use {
            while (scanned < 500 && items.length() < args.queryLimit() && it.moveToNext()) {
                checkReadCancellation(cancelled)
                scanned++
                val name = it.getString(1)
                if (args.queryText().isNotEmpty() && !name.orEmpty().contains(args.queryText(), true)) continue
                val documentUri = documentUri(tree, it.getString(0))
                    ?: return failure("unavailable", "document_uri_too_large")
                fieldsTruncated = fieldsTruncated || clippedText(name) || clippedText(it.getString(2))
                items.put(JSONObject().put("uri", documentUri).put("name", boundedText(name)).put("mime_type", boundedText(it.getString(2)))
                    .put("size_bytes", if (it.isNull(3)) JSONObject.NULL else it.getLong(3))
                    .put("modified_ms", if (it.isNull(4)) JSONObject.NULL else it.getLong(4)))
            }
        }
        return success(JSONObject().put("items", items).put("limit", args.queryLimit()).put("scan_limit", 500)
            .put("truncated", scanned == 500 || items.length() == args.queryLimit() || fieldsTruncated)
            .put("fields_truncated", fieldsTruncated).put("scope", "granted_tree_direct_children"))
    }

    /** Match the platform URI schemas and settings grants; never truncate a URI into another identifier. */
    internal fun documentUri(tree: Uri, documentId: String): String? =
        DocumentsContract.buildDocumentUriUsingTree(tree, documentId).toString().takeIf { it.length <= 2048 }

    internal fun rows(resolver: ContentResolver, uri: Uri, projection: Array<String>, selection: String?,
        selectionArgs: Array<String>?, order: String?, limit: Int, cancelled: () -> Boolean, row: (Cursor) -> JSONObject): JSONObject {
        val queryArgs = Bundle().apply {
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit + 1)
            order?.let { putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, it) }
            selection?.let { putString(ContentResolver.QUERY_ARG_SQL_SELECTION, it) }
            selectionArgs?.let { putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, it) }
        }
        checkReadCancellation(cancelled)
        val cursor = resolver.query(uri, projection, queryArgs, null) ?: return failure("unavailable", "provider_returned_no_cursor")
        val items = JSONArray()
        var truncated = false
        var fieldsTruncated = false
        cursor.use {
            while (it.moveToNext()) {
                checkReadCancellation(cancelled)
                if (items.length() == limit) { truncated = true; break }
                for (column in projection.indices) {
                    if (it.getType(column) == Cursor.FIELD_TYPE_STRING && clippedText(it.getString(column))) fieldsTruncated = true
                }
                items.put(row(it))
            }
        }
        return success(JSONObject().put("items", items).put("limit", limit).put("truncated", truncated || fieldsTruncated)
            .put("fields_truncated", fieldsTruncated).put("possibly_truncated", items.length() == limit)
            .put("field_limit_chars", AndroidPlatformReads.MAX_FIELD_CHARS))
    }
}
