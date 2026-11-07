package app.daiyu.android.storage

import android.database.sqlite.SQLiteDatabase
import app.daiyu.android.diagnostics.RuntimeDiagnostics

/** Lease-local raw values retained only after complete message validation. */
internal class SQLiteVerifiedTextReuse(
    private val maxCharacters: Long = 14L * 1024 * 1024,
    private val minCacheCharacters: Int = 1024,
) {
    init { require(maxCharacters >= 0 && minCacheCharacters >= 0) }
    private data class Key(val id: String, val column: String)
    private val entries = LinkedHashMap<Key, String>()
    internal var retainedCharacters = 0L
        private set
    internal var hits = 0L
        private set
    internal var proofs = 0L
        private set

    fun read(db: SQLiteDatabase, id: String, column: String): String? {
        require(column in setOf("blocks", "error", "protocol", "history_segments"))
        val candidate = entries[Key(id, column)]
        if (candidate != null) {
            val same = RuntimeDiagnostics.measure(RuntimeDiagnostics.Stage.SQL_TEXT_PROOF) {
                proofs++
                db.compileStatement("SELECT CAST($column AS BLOB)=CAST(? AS BLOB) FROM messages WHERE id=?").use {
                    it.bindString(1, candidate)
                    it.bindString(2, id)
                    it.simpleQueryForLong() == 1L
                }
            }
            if (same) {
                hits++
                RuntimeDiagnostics.event(RuntimeDiagnostics.Stage.SQL_TEXT_READ, RuntimeDiagnostics.Code.CACHE_HIT,
                    size = candidate.length.toLong())
                return candidate
            }
        }
        RuntimeDiagnostics.event(RuntimeDiagnostics.Stage.SQL_TEXT_READ, RuntimeDiagnostics.Code.CACHE_MISS)
        return SQLiteText.read(db, "messages", column, "id", id)
    }

    fun remember(columns: StoredMessageColumns) {
        // Select a whole message batch first: two fields above the combined budget
        // must not repeatedly evict each other. Keep the largest fitting values.
        val fields = listOf("protocol" to columns.protocol, "history_segments" to columns.history)
            .sortedByDescending { it.second.length }
        fields.forEach { (column, _) ->
            entries.remove(Key(columns.messageId, column))?.let { retainedCharacters -= it.length }
        }
        var remaining = maxCharacters
        for ((column, value) in fields) {
            if (value.length < minCacheCharacters || value.length.toLong() > remaining) {
                RuntimeDiagnostics.event(RuntimeDiagnostics.Stage.SQL_TEXT_READ, RuntimeDiagnostics.Code.CACHE_BYPASS,
                    size = value.length.toLong())
                continue
            }
            remaining -= value.length
            while (entries.isNotEmpty() && (entries.size >= 8 || retainedCharacters > maxCharacters - value.length)) {
                val first = entries.entries.first()
                retainedCharacters -= first.value.length
                entries.remove(first.key)
            }
            entries[Key(columns.messageId, column)] = value
            retainedCharacters += value.length
        }
    }

    fun clear() {
        entries.clear()
        retainedCharacters = 0
    }
}
