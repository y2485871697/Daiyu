package app.daiyu.android.backup.legacy

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import app.daiyu.android.storage.ConversationDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID

/** Controller boundary. Preview does no SQL. Commit re-reads and verifies the exact previewed bytes.
 * Only inert legacy_r12_* tables are touched, on the EXISTING helper's single SQL transaction.
 * No live conversations/messages/runtime/actions/preferences/context tables are written. */
class LegacyImportService internal constructor(private val database: () -> SQLiteDatabase,
    private val beforeCommit: () -> Unit = {}) {
    constructor(store: ConversationDatabase) : this({ store.writableDatabase })

    fun preview(input: InputStream): LegacyPreview {
        val container = LegacyArchive.read(input); val plan = LegacyParser.parse(container)
        compatibilityViews(plan) // reserve all persisted text before promising an import
        return LegacyPreview(container.sha256, plan.format, plan.version, plan.conversations.size,
            plan.conversations.sumOf { it.messages.size }, plan.historyCount, plan.originals.size,
            plan.originals.values.sumOf { it.size.toLong() }, plan.report)
    }

    /** Explicit copy, not overwrite/dedup/restore. Every call allocates new conversation/message IDs.
     * Caller owns the InputStream and should invoke this bounded synchronous method off the UI thread. */
    fun commit(input: InputStream, expectedSha256: String): LegacyImportResult {
        require(expectedSha256.matches(Regex("[0-9a-f]{64}")))
        val container = LegacyArchive.read(input)
        require(container.sha256 == expectedSha256) { "Input changed since preview; nothing imported" }
        val plan = LegacyParser.parse(container) // all paths, hashes, owners and budgets before SQL
        val views = compatibilityViews(plan)
        val importId = newId(); val conversationIds = plan.conversations.map { newId() }
        val db = database()
        check(!db.inTransaction()) { "Legacy import owns one helper transaction; call outside another transaction" }
        val rows = mutableListOf<Pair<String, ContentValues>>()
        val payloads = mutableListOf<Triple<String, String, ByteArray>>()
        fun insert(table: String, values: ContentValues) {
            check(db.insertOrThrow(table, null, values) != -1L) { "Compatibility row was not inserted" }
            rows += table to values
        }
        fun payload(owner: String, name: String, bytes: ByteArray) {
            writeChunks(db, owner, name, bytes)
            payloads += Triple(owner, name, bytes)
        }
        db.beginTransaction()
        try {
            LegacyCompatibilitySchema.install(db)
            listOf("sqlite_master", "sqlite_temp_master").forEach { catalog ->
                db.rawQuery("SELECT 1 FROM $catalog WHERE type='trigger' AND tbl_name IN ('legacy_r12_imports','legacy_r12_histories','legacy_r12_assets','legacy_r12_chunks') LIMIT 1", null).use {
                    check(!it.moveToFirst()) { "Compatibility tables must not have triggers" }
                }
            }
            insert("legacy_r12_imports", ContentValues().apply {
                put("id", importId); put("source_hash", container.sha256); put("format", plan.format); put("version", plan.version)
            })
            payload(importId, "report", reportJson(plan.report).toString().toByteArray(Charsets.UTF_8))
            plan.originals.forEach { (entry, bytes) ->
                insert("legacy_r12_assets", ContentValues().apply {
                    put("import_id", importId); put("entry", entry); put("sha256", digest(bytes)); put("byte_count", bytes.size)
                })
                payload(importId, "asset:$entry", bytes)
            }
            plan.conversations.forEachIndexed { index, conv ->
                val id = conversationIds[index]
                insert("legacy_r12_histories", ContentValues().apply {
                    put("id", id); put("import_id", importId); put("source_id", conv.sourceId); put("title", conv.title)
                    if (conv.createdAt == null) putNull("source_created") else put("source_created", conv.createdAt)
                    if (conv.updatedAt == null) putNull("source_updated") else put("source_updated", conv.updatedAt)
                })
                payload(id, "compat", conv.raw.toByteArray(Charsets.UTF_8))
                // IDs of source records remain only in the raw evidence and source_id metadata.
                // New IDs are never tool call IDs, runs, accepted context or checkpoints.
                payload(id, "view", views[index])
            }
            beforeCommit()
            verifyWrittenRows(db, importId, conversationIds, rows, payloads)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return LegacyImportResult(importId, conversationIds, plan.report)
    }

    /** Separate read-only history catalogue: parent must NOT send these records through runtime. */
    fun histories(): List<LegacyHistorySummary> {
        val db = database(); if (!installed(db)) return emptyList()
        return db.rawQuery("SELECT id,source_id,title,source_created,source_updated,import_id FROM legacy_r12_histories ORDER BY rowid", null).use { c ->
            buildList { while (c.moveToNext()) add(LegacyHistorySummary(c.getString(0), c.getString(1), c.getString(2),
                if (c.isNull(3)) null else c.getLong(3), if (c.isNull(4)) null else c.getLong(4), c.getString(5))) }
        }
    }

    fun reopen(conversationId: String): LegacyReadOnlyHistory {
        val db = database(); check(installed(db))
        val summary = db.rawQuery("SELECT source_id,title,source_created,source_updated,import_id FROM legacy_r12_histories WHERE id=?", arrayOf(conversationId)).use { c ->
            require(c.moveToFirst()) { "No legacy history" }
            LegacyHistorySummary(conversationId, c.getString(0), c.getString(1), if (c.isNull(2)) null else c.getLong(2), if (c.isNull(3)) null else c.getLong(3), c.getString(4))
        }
        val view = LegacyJson.objectOf(readChunks(db, conversationId, "view", LegacyArchive.TEXT_LIMIT))
        val rawText = LegacyArchive.utf8(readChunks(db, conversationId, "compat", LegacyArchive.TEXT_LIMIT))
        val raw = LegacyJson.parse(rawText) as JSONObject
        val sourceMessages = LegacyParser.rows(raw, "messages").sortedBy { LegacyJson.integer(it, "sortIndex") }
        val messageIds = view.getJSONArray("messageIds"); check(messageIds.length() == sourceMessages.size)
        val messages = sourceMessages.mapIndexed { index, m -> LegacyUiMessage(messageIds.getString(index), m.getString("id"),
            m.getString("type"), m.getString("content"), LegacyParser.time(m, "generatedAtMillis")) }
        val media = LegacyJson.rows(view.getJSONArray("media")).map { m -> LegacyMedia(m.getString("reference"), nullableString(m, "entry"),
            nullableString(m, "sha256"), LegacyParser.time(m, "bytes"), m.getString("purpose"), m.getBoolean("available")) }
        return LegacyReadOnlyHistory(summary, messages, LegacyParser.arrayString(raw.getJSONObject("conversation"), "historyJson").toString(),
            raw.optJSONObject("contextCheckpoint")?.let { LegacyParser.arrayString(it, "historyJson").toString() }, media,
            rawText,
            decodeReport(LegacyJson.objectOf(readChunks(db, summary.importId, "report", LegacyArchive.TEXT_LIMIT))))
    }

    /** Raw original bytes only, addressed by verified inventory entry, never by a host path or URL. */
    fun original(importId: String, entry: String): ByteArray {
        LegacyArchive.path(entry)
        val db = database(); check(installed(db))
        val metadata = db.rawQuery("SELECT sha256,byte_count FROM legacy_r12_assets WHERE import_id=? AND entry=?", arrayOf(importId, entry)).use { c ->
            require(c.moveToFirst()) { "Original is unavailable" }; c.getString(0) to c.getLong(1)
        }
        val bytes = readChunks(db, importId, "asset:$entry", LegacyArchive.BLOB_LIMIT)
        check(bytes.size.toLong() == metadata.second && digest(bytes) == metadata.first) { "Stored original failed verification" }
        return bytes
    }

    /** Includes unreferenced verified ZIP originals, which are never silently discarded. */
    fun originals(importId: String): List<LegacyMedia> {
        val db = database(); if (!installed(db)) return emptyList()
        return db.rawQuery("SELECT entry,sha256,byte_count FROM legacy_r12_assets WHERE import_id=? ORDER BY entry", arrayOf(importId)).use { c ->
            buildList { while (c.moveToNext()) add(LegacyMedia(c.getString(0), c.getString(0), c.getString(1), c.getLong(2), "archive-original", true)) }
        }
    }

    private fun compatibilityViews(plan: LegacyPlan): List<ByteArray> {
        val views = plan.conversations.map { conv ->
            JSONObject().put("messageIds", JSONArray(conv.messages.map { newId() }))
                .put("media", JSONArray(conv.media.map { m -> JSONObject().put("reference", m.reference)
                    .put("entry", m.entry ?: JSONObject.NULL).put("sha256", m.sha256 ?: JSONObject.NULL)
                    .put("bytes", m.bytes ?: JSONObject.NULL).put("purpose", m.purpose).put("available", m.available) }))
                .toString().toByteArray(Charsets.UTF_8)
        }
        val textBytes = views.sumOf { it.size.toLong() } + plan.conversations.sumOf {
            it.raw.toByteArray(Charsets.UTF_8).size.toLong() + it.title.toByteArray(Charsets.UTF_8).size + it.sourceId.toByteArray(Charsets.UTF_8).size
        } + reportJson(plan.report).toString().toByteArray(Charsets.UTF_8).size
        val blobBytes = plan.originals.values.sumOf { it.size.toLong() }
        require(textBytes <= LegacyArchive.TEXT_LIMIT && blobBytes <= LegacyArchive.BLOB_LIMIT && textBytes + blobBytes <= LegacyArchive.COMPLETE_LIMIT) {
            "Complete compatibility data exceeds new-library limits; no truncation or limit expansion permitted"
        }
        return views
    }

    private fun installed(db: SQLiteDatabase) = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='legacy_r12_histories'", null).use { it.moveToFirst() }
    private fun writeChunks(db: SQLiteDatabase, owner: String, name: String, bytes: ByteArray) {
        var offset = 0; var part = 0
        do {
            val end = minOf(bytes.size, offset + 65536)
            check(db.insertOrThrow("legacy_r12_chunks", null, ContentValues().apply {
                put("owner", owner); put("name", name); put("part", part++); put("data", bytes.copyOfRange(offset, end))
            }) != -1L) { "Compatibility chunk was not inserted" }
            offset = end
        } while (offset < bytes.size)
    }
    private fun verifyWrittenRows(db: SQLiteDatabase, importId: String, conversationIds: List<String>,
        rows: List<Pair<String, ContentValues>>, payloads: List<Triple<String, String, ByteArray>>) {
        rows.forEach { (table, values) ->
            val keys = if (table == "legacy_r12_assets") listOf("import_id", "entry") else listOf("id")
            db.query(table, null, keys.joinToString(" AND ") { "$it=?" },
                keys.map { values.getAsString(it) }.toTypedArray(), null, null, null).use { c ->
                check(c.moveToFirst()) { "Missing compatibility row" }
                values.valueSet().forEach { (column, expected) ->
                    val index = c.getColumnIndexOrThrow(column)
                    check(when (expected) {
                        null -> c.isNull(index)
                        is Number -> c.getType(index) == android.database.Cursor.FIELD_TYPE_INTEGER && c.getLong(index) == expected.toLong()
                        else -> c.getType(index) == android.database.Cursor.FIELD_TYPE_STRING && c.getString(index) == expected.toString()
                    }) { "Compatibility row changed before commit" }
                }
                check(!c.moveToNext())
            }
        }
        listOf("legacy_r12_histories", "legacy_r12_assets").forEach { table ->
            db.rawQuery("SELECT count(*) FROM $table WHERE import_id=?", arrayOf(importId)).use { c ->
                check(c.moveToFirst() && c.getLong(0) == rows.count { it.first == table }.toLong())
            }
        }
        payloads.forEach { (owner, name, expected) ->
            var offset = 0
            var part = 0
            db.rawQuery("SELECT part,data FROM legacy_r12_chunks WHERE owner=? AND name=? ORDER BY part",
                arrayOf(owner, name)).use { c ->
                while (c.moveToNext()) {
                    check(c.getType(0) == android.database.Cursor.FIELD_TYPE_INTEGER && c.getLong(0) == part++.toLong())
                    check(c.getType(1) == android.database.Cursor.FIELD_TYPE_BLOB)
                    val bytes = c.getBlob(1)
                    val end = minOf(expected.size, offset + 65536)
                    check(bytes.contentEquals(expected.copyOfRange(offset, end))) { "Compatibility payload changed before commit" }
                    offset = end
                }
            }
            check(offset == expected.size && part == maxOf(1, (expected.size + 65535) / 65536))
        }
        val chunkCounts = payloads.groupBy { it.first }.mapValues { (_, entries) ->
            entries.sumOf { maxOf(1, (it.third.size + 65535) / 65536).toLong() }
        }
        (listOf(importId) + conversationIds).forEach { owner ->
            val expected = chunkCounts.getValue(owner)
            db.rawQuery("SELECT count(*) FROM legacy_r12_chunks WHERE owner=?", arrayOf(owner)).use { c ->
                check(c.moveToFirst() && c.getLong(0) == expected) { "Unexpected compatibility payload" }
            }
        }
    }
    private fun readChunks(db: SQLiteDatabase, owner: String, name: String, limit: Int): ByteArray {
        val out = ByteArrayOutputStream(); var part = 0
        db.rawQuery("SELECT part,data FROM legacy_r12_chunks WHERE owner=? AND name=? ORDER BY part", arrayOf(owner, name)).use { c ->
            while (c.moveToNext()) {
                check(c.getType(0) == android.database.Cursor.FIELD_TYPE_INTEGER && c.getLong(0) == part++.toLong()); val bytes = c.getBlob(1)
                check(bytes.size <= 65536 && out.size().toLong() + bytes.size <= limit); out.write(bytes)
            }
        }
        check(part > 0) { "Missing compatibility payload" }; return out.toByteArray()
    }
    private fun newId() = "legacy-${UUID.randomUUID()}"
    private fun nullableString(obj: JSONObject, key: String): String? = if (obj.isNull(key)) null else obj.getString(key)
    private fun reportJson(report: LegacyConversionReport) = JSONObject().put("issues", JSONArray(report.issues.map {
        JSONObject().put("code", it.code).put("count", it.count).put("detail", it.detail)
    }))
    private fun decodeReport(json: JSONObject) = LegacyConversionReport(LegacyJson.rows(json.getJSONArray("issues")).map {
        LegacyIssue(it.getString("code"), it.getInt("count"), it.getString("detail"))
    })
}
