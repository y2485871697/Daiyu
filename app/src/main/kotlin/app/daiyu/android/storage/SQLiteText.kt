package app.daiyu.android.storage

import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal

/** Avoid the API26 CursorWindow row limit without truncating a persisted JSON field. */
internal object SQLiteText {
    fun read(db: SQLiteDatabase, table: String, column: String, key: String, id: String,
        maxBytes: Long = 64L * 1024 * 1024, cancellationSignal: CancellationSignal? = null): String? {
        cancellationSignal?.throwIfCanceled()
        require(table == "messages" && key == "id" && column in setOf("blocks", "protocol", "history_segments", "error") ||
            table == "runtime_checkpoints" && key == "message_id" && column == "payload" ||
            table == "message_video_inputs" && key == "message_id" && column == "payload" ||
            table in setOf("composer", "conversations") && key == "id" && column == "draft")
        require(maxBytes in 1..(64L * 1024 * 1024))
        val length=db.rawQuery("SELECT length(CAST($column AS BLOB)) FROM $table WHERE $key=?", arrayOf(id), cancellationSignal).use {
            check(it.moveToFirst()); if(it.isNull(0)) null else it.getLong(0)
        } ?: return null
        require(length in 0..maxBytes)
        val bytes=java.io.ByteArrayOutputStream();var start=1L
        while(start<=length) {
            cancellationSignal?.throwIfCanceled()
            val part=db.rawQuery("SELECT substr(CAST($column AS BLOB),?,65536) FROM $table WHERE $key=?", arrayOf(start.toString(),id), cancellationSignal).use {
                check(it.moveToFirst());it.getBlob(0)
            }
            check(part.isNotEmpty() && bytes.size().toLong()+part.size<=length)
            bytes.write(part);start+=part.size
        }
        cancellationSignal?.throwIfCanceled()
        check(bytes.size().toLong()==length)
        return Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
            .also { cancellationSignal?.throwIfCanceled() }
    }
}
