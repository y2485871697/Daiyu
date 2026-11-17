package app.daiyu.android.references

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns

internal object ChatReferenceMetadata {
    fun describe(resolver: ContentResolver, uri: Uri, tree: Boolean): String {
        val fallback = if (tree) {
            cleanName(runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
                ?.substringAfterLast('/')?.substringAfterLast(':')).ifBlank { "已选目录" }
        } else "已选文件"
        return try {
            val document = if (tree) DocumentsContract.buildDocumentUriUsingTree(uri,
                DocumentsContract.getTreeDocumentId(uri)) else uri
            val columns = if (tree) arrayOf(OpenableColumns.DISPLAY_NAME)
                else arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            resolver.query(document, columns, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use fallback
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val name = cleanName(if (nameColumn >= 0 && !cursor.isNull(nameColumn)) cursor.getString(nameColumn) else null)
                    .ifBlank { fallback }
                if (tree) name else {
                    val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                    val size = if (sizeColumn >= 0 && !cursor.isNull(sizeColumn))
                        runCatching { cursor.getLong(sizeColumn) }.getOrNull()?.takeIf { it >= 0 } else null
                    "$name（${size?.let { "$it 字节" } ?: "大小未知"}）"
                }
            } ?: fallback
        } catch (_: Exception) { fallback }
    }

    private fun cleanName(value: String?): String = value.orEmpty().replace('\n', ' ').replace('\r', ' ')
        .filterNot { it.isISOControl() }.take(200).trim()
}
