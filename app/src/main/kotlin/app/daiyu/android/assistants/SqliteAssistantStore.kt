package app.daiyu.android.assistants

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import app.daiyu.android.storage.ConversationDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/** Application-owned projection. The database, not preferences or the flow, is authoritative. */
class SqliteAssistantStore(private val database: ConversationDatabase) : AssistantStore {
    // An unavailable store is visible as an empty projection, never a replacement seed.
    // Initialization still throws to the controller recovery gate; source data is untouched.
    private data class Publication(val snapshot: LegacyAssistantCodec.Snapshot, val revision: Long)
    private fun publication(db: SQLiteDatabase) = Publication(readSnapshot(db), db.rawQuery(
        "SELECT revision FROM assistant_selection WHERE id=1", null).use { check(it.moveToFirst()); it.getLong(0) })
    private val initial = runCatching { current() }
    private val profiles = MutableStateFlow(initial.getOrNull()?.snapshot?.profiles.orEmpty())
    private val selected = MutableStateFlow(initial.getOrNull()?.snapshot?.selectedId.orEmpty())
    private val publicationLock = Any()
    private var publishedRevision = initial.getOrNull()?.revision ?: -1L
    fun requireReady() { initial.getOrThrow() }
    private fun current(): Publication {
        val db=database.readableDatabase
        db.beginTransaction()
        try { return publication(db).also { db.setTransactionSuccessful() } }
        finally { db.endTransaction() }
    }
    override val assistants = profiles.asStateFlow()
    override val selectedId = selected.asStateFlow()

    override fun read(id: String): AssistantProfile? = current().snapshot.profiles.firstOrNull { it.id == id }

    private fun <T> change(work: (SQLiteDatabase) -> T): T {
        val db = database.writableDatabase
        db.beginTransaction()
        val result: T
        val next: Publication
        try {
            result = work(db)
            db.execSQL("UPDATE assistant_selection SET revision=revision+1 WHERE id=1")
            next = publication(db)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        // No monitor spans SQLite I/O. A late publisher cannot overwrite a newer commit.
        synchronized(publicationLock) {
            if(next.revision > publishedRevision) {
                profiles.value = next.snapshot.profiles
                selected.value = next.snapshot.selectedId
                publishedRevision = next.revision
            }
        }
        return result
    }

    override fun save(profile: AssistantProfile): AssistantProfile = change { db ->
        AssistantStorage.id(profile.id)
        val value = profile.copy(name = profile.name.trim().ifBlank { AssistantPrompt.DEFAULT_NAME },
            enabledSkillIds = profile.enabledSkillIds.toList())
        val position = db.rawQuery("SELECT position FROM assistants WHERE id=?", arrayOf(value.id)).use {
            if (it.moveToFirst()) it.getLong(0) else db.rawQuery("SELECT coalesce(max(position),-1)+1 FROM assistants", null).use { c -> c.moveToFirst(); c.getLong(0) }
        }
        val values = values(value, position)
        if (db.update("assistants", values, "id=?", arrayOf(value.id)) == 0)
            check(db.insertOrThrow("assistants", null, values) != -1L)
        check(readSnapshot(db).profiles.single { it.id == value.id } == value) { "助手保存回执不匹配" }
        value
    }

    override fun delete(id: String) = change { db ->
        val before = readSnapshot(db)
        require(before.profiles.any { it.id == id }) { "助手不存在" }
        require(before.profiles.size > 1) { "必须保留至少一个助手" }
        check(db.delete("assistants", "id=?", arrayOf(id)) == 1)
        if (before.selectedId == id) check(db.update("assistant_selection", ContentValues().apply {
            put("selected_id", before.profiles.first { it.id != id }.id)
        }, "id=1", null) == 1)
        // Deliberately retain conversation assistant_id. A deleted binding is not a fallback.
        Unit
    }

    override fun select(id: String) = change { db ->
        require(readSnapshot(db).profiles.any { it.id == id }) { "助手不存在" }
        check(db.update("assistant_selection", ContentValues().apply { put("selected_id", id) }, "id=1", null) == 1)
        check(readSnapshot(db).selectedId == id) { "助手选择回执不匹配" }
        Unit
    }

    companion object {
        /** Called only in SQLiteOpenHelper's create/upgrade transaction. A parse failure rolls
         * back schema AND data/version. The source preferences remain intact even on success. */
        internal fun install(db: SQLiteDatabase, legacy: String?) {
            val seed = legacy?.let(LegacyAssistantCodec::decode) ?: LegacyAssistantCodec.Snapshot(
                listOf(SharedPreferencesAssistantStore.defaultProfile()), AssistantPrompt.DEFAULT_ID)
            db.execSQL("CREATE TABLE assistants(id TEXT PRIMARY KEY NOT NULL,name TEXT NOT NULL,prompt TEXT NOT NULL,avatar_file_name TEXT,created_at INTEGER NOT NULL,memory_enabled INTEGER NOT NULL CHECK(memory_enabled IN (0,1)),enabled_skill_ids TEXT NOT NULL,position INTEGER NOT NULL UNIQUE)")
            db.execSQL("CREATE TABLE assistant_selection(id INTEGER PRIMARY KEY CHECK(id=1),selected_id TEXT NOT NULL,revision INTEGER NOT NULL DEFAULT 0)")
            seed.profiles.forEachIndexed { index, profile -> check(db.insertOrThrow("assistants", null, values(profile, index.toLong())) != -1L) }
            check(db.insertOrThrow("assistant_selection", null, ContentValues().apply { put("id", 1); put("selected_id", seed.selectedId) }) != -1L)
            check(readSnapshot(db) == seed) { "助手迁移回执不匹配；原数据保留" }
        }

        private fun values(p: AssistantProfile, position: Long) = ContentValues().apply {
            put("id", p.id); put("name", p.name); put("prompt", p.prompt); put("avatar_file_name", p.avatarFileName)
            put("created_at", p.createdAt); put("memory_enabled", if (p.memoryEnabled) 1 else 0)
            put("enabled_skill_ids", JSONArray(p.enabledSkillIds).toString()); put("position", position)
        }

        private fun readSnapshot(db: SQLiteDatabase): LegacyAssistantCodec.Snapshot {
            val profiles = db.rawQuery("SELECT id,name,prompt,avatar_file_name,created_at,memory_enabled,enabled_skill_ids FROM assistants ORDER BY position", null).use { c ->
                buildList { while (c.moveToNext()) {
                    val skills = JSONArray(c.getString(6))
                    add(AssistantProfile(AssistantStorage.id(c.getString(0)), c.getString(1), c.getString(2), c.getString(3),
                        c.getLong(4), c.getInt(5) == 1, (0 until skills.length()).map { skills.getString(it) }))
                } }
            }
            val selected = db.rawQuery("SELECT selected_id FROM assistant_selection WHERE id=1", null).use { check(it.moveToFirst()); it.getString(0) }
            check(profiles.any { it.id == selected }) { "助手选择损坏；原数据保留" }
            return LegacyAssistantCodec.Snapshot(profiles, selected)
        }
    }
}
