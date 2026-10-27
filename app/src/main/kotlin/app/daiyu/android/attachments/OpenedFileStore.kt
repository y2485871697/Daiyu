package app.daiyu.android.attachments

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

/** Only complete app-owned documents become readable attachments; source grants are never persisted. */
internal class OpenedFileStore(private val context: Context) {
    data class Document(val uri: Uri, val name: String, val mime: String, val size: Long) {
        fun reference(): String = "文件引用（未读取）：$uri\n附件信息（按需读取）：$name（$size 字节）；" +
            "用 file_metadata 查看元数据；用 read_text_file 按行或 query 检索，按 next_offset_bytes 续读；不支持的文档格式应明确说明。"
    }
    private val root get() = File(context.filesDir, "opened-attachments")
    private val authority get() = "${context.packageName}.opened.attachments"

    fun import(uri: Uri, fallbackMime: String?, limit: Long, cancellation: CancellationSignal): Document {
        require(uri.scheme == "content" && !uri.authority.isNullOrBlank() && uri.userInfo == null && uri.fragment == null)
        require(!android.provider.DocumentsContract.isTreeUri(uri))
        cancellation.throwIfCanceled()
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "附件"
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null, cancellation)?.use {
            if (it.moveToFirst() && !it.isNull(0)) name = it.getString(0)
        }
        name = name.filterNot { it.isISOControl() }.take(200).ifBlank { "附件" }
        fun mimeValue(raw: String?): String? = raw?.substringBefore(';')?.trim()?.lowercase(java.util.Locale.ROOT)
            ?.takeIf { it.length <= 127 && MIME.matches(it) }
        val reported = mimeValue(context.contentResolver.getType(uri)) ?: mimeValue(fallbackMime)
        val mime = reported ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        check(root.isDirectory || root.mkdirs())
        val id = UUID.randomUUID().toString()
        val staging = File(root, ".$id")
        val published = File(root, id)
        check(staging.mkdir())
        try {
            val size = context.contentResolver.openAssetFileDescriptor(uri, "r", cancellation)?.use { descriptor ->
                descriptor.createInputStream().use { input ->
                    cancellation.setOnCancelListener { runCatching { input.close() } }
                    try {
                        File(staging, "content").outputStream().use { output ->
                            copyOpenedFile(input, output, minOf(MAX_FILE_BYTES, limit)) { cancellation.isCanceled }
                                .also { output.fd.sync() }
                        }
                    } finally { cancellation.setOnCancelListener(null) }
                }
            } ?: throw FileNotFoundException("attachment_unavailable")
            val metadata = JSONObject().put("name", name).put("mime", mime).put("size", size).toString().toByteArray(Charsets.UTF_8)
            require(metadata.size <= 4096)
            File(staging, "metadata.json").outputStream().use { output ->
                output.write(metadata); output.fd.sync()
            }
            cancellation.throwIfCanceled()
            check(staging.renameTo(published))
            return Document(Uri.Builder().scheme("content").authority(authority).appendPath(id).build(), name, mime, size)
        } finally { if (staging.exists()) staging.deleteRecursively() }
    }

    private fun directory(uri: Uri): File {
        val id = uri.pathSegments.singleOrNull()
        require(uri.scheme == "content" && uri.authority == authority && uri.query == null && uri.fragment == null &&
            id != null && ID.matches(id))
        return File(root, id).also { require(it.canonicalFile.parentFile == root.canonicalFile) }
    }
    fun document(uri: Uri): Document {
        val directory = directory(uri)
        val metadata = File(directory, "metadata.json")
        if (!metadata.isFile || metadata.length() > 4096 || !File(directory, "content").isFile)
            throw FileNotFoundException("attachment_unavailable")
        val value = JSONObject(metadata.readText(Charsets.UTF_8))
        return Document(uri, value.getString("name"), value.getString("mime"), File(directory, "content").length())
    }
    fun owns(uri: Uri): Boolean = try { document(uri); true } catch (_: Exception) { false }
    fun file(uri: Uri): File { document(uri); return File(directory(uri), "content") }
    fun remove(document: Document) { directory(document.uri).deleteRecursively() }

    companion object {
        const val MAX_FILE_BYTES = 128L * 1024 * 1024
        const val MAX_BATCH_BYTES = 256L * 1024 * 1024
        private val MIME = Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")
        private val ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
