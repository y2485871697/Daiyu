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
        val source = object : java.io.InputStream() {
            private var consumed = 0L
            private var part = ByteArray(0)
            private var offset = 0
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 255
            }
            override fun read(target: ByteArray, off: Int, len: Int): Int {
                if (off < 0 || len < 0 || off > target.size - len) throw IndexOutOfBoundsException()
                if (len == 0) return 0
                cancellationSignal?.throwIfCanceled()
                if (consumed == length) return -1
                if (offset == part.size) {
                    part = db.rawQuery("SELECT substr(CAST($column AS BLOB),?,65536) FROM $table WHERE $key=?",
                        arrayOf((consumed + 1).toString(), id), cancellationSignal).use {
                        check(it.moveToFirst()); it.getBlob(0)
                    }
                    check(part.isNotEmpty() && part.size.toLong() <= length - consumed)
                    offset = 0
                }
                val count = minOf(len, part.size - offset)
                part.copyInto(target, off, offset, offset + count)
                offset += count
                consumed += count
                return count
            }
        }
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return java.io.InputStreamReader(source, decoder).use { reader ->
            val result = StringBuilder(length.toInt())
            val chars = CharArray(8192)
            while (true) {
                cancellationSignal?.throwIfCanceled()
                val count = reader.read(chars)
                if (count == -1) break
                result.append(chars, 0, count)
            }
            cancellationSignal?.throwIfCanceled()
            result.toString()
        }
    }
}
