package app.daiyu.android.storage

import android.database.sqlite.SQLiteDatabase

/** Avoid the API26 CursorWindow row limit without truncating a persisted JSON field. */
internal object SQLiteText {
    fun read(db: SQLiteDatabase, table: String, column: String, key: String, id: String, maxBytes: Long = 64L * 1024 * 1024): String? {
        require(table == "messages" && key == "id" && column in setOf("blocks", "protocol", "history_segments", "error") ||
            table == "runtime_checkpoints" && key == "message_id" && column == "payload" ||
            table == "message_video_inputs" && key == "message_id" && column == "payload")
        require(maxBytes in 1..(64L * 1024 * 1024))
        val length=db.rawQuery("SELECT length(CAST($column AS BLOB)) FROM $table WHERE $key=?", arrayOf(id)).use {
            check(it.moveToFirst()); if(it.isNull(0)) null else it.getLong(0)
        } ?: return null
        require(length in 0..maxBytes)
        val bytes=java.io.ByteArrayOutputStream();var start=1L
        while(start<=length) {
            val part=db.rawQuery("SELECT substr(CAST($column AS BLOB),?,65536) FROM $table WHERE $key=?", arrayOf(start.toString(),id)).use {
                check(it.moveToFirst());it.getBlob(0)
            }
            check(part.isNotEmpty() && bytes.size().toLong()+part.size<=length)
            bytes.write(part);start+=part.size
        }
        check(bytes.size().toLong()==length)
        return Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
    }
}
