package app.daiyu.android

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.test.InstrumentationTestCase
import app.daiyu.android.assistants.*
import app.daiyu.android.context.ContextService
import app.daiyu.android.domain.*
import app.daiyu.android.storage.ConversationDatabase
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

@Suppress("DEPRECATION")
class AssistantBindingDatabaseTest : InstrumentationTestCase() {
    private lateinit var context: Context
    private val opened = mutableListOf<ConversationDatabase>()
    override fun setUp() {
        super.setUp()
        context = testContext(instrumentation.targetContext, "assistant-db-${UUID.randomUUID()}-")
    }
    private fun database() = ConversationDatabase(context).also { opened.add(it) }
    override fun tearDown() {
        opened.forEach { it.close() }
        context.deleteDatabase("conversations.db")
        context.getSharedPreferences("daiyu-assistants", 0).edit().clear().commit()
        super.tearDown()
    }
    private fun seedV9() {
        // Produce the complete predecessor schema, not a mock helper or onUpgrade invocation.
        val helper = database()
        val db = helper.writableDatabase
        removePostV9ConversationSchemaForFixture(db)
        db.execSQL("ALTER TABLE conversations RENAME TO conversations_v10")
        db.execSQL("CREATE TABLE conversations(id TEXT PRIMARY KEY,title TEXT NOT NULL,draft TEXT NOT NULL,updated INTEGER NOT NULL,folder TEXT NOT NULL DEFAULT '',pinned INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("INSERT INTO conversations VALUES('old','旧会话','旧草稿',7,'工作',1)")
        db.execSQL("DROP TABLE conversations_v10")
        db.execSQL("DROP TABLE assistants")
        db.execSQL("DROP TABLE assistant_selection")
        db.version = 9
        helper.close()
    }
    private fun prefs(raw: String) {
        check(context.getSharedPreferences("daiyu-assistants", 0).edit().putString(LegacyAssistantCodec.KEY, raw).commit())
    }
    private fun raw() = context.getSharedPreferences("daiyu-assistants", 0).getString(LegacyAssistantCodec.KEY, null)
    private fun pair(id: ConversationId): Pair<Message, Message> {
        val run = RunId(UUID.randomUUID().toString())
        val user = Message(MessageId(UUID.randomUUID().toString()), id, run, "user",
            listOf(MessageBlock.Text("u", "hello")), RunStatus.COMPLETED)
        return user to user.copy(id = MessageId(UUID.randomUUID().toString()), role = "assistant", blocks = emptyList(), status = RunStatus.RUNNING)
    }
    private fun rejected(action: () -> Unit) {
        try { action(); fail("must reject without replacing state") } catch (_: IllegalArgumentException) { }
        catch (_: IllegalStateException) { }
    }

    fun testV9MigratesSevenFieldsOrderAndSelectionOnceAndRetainsSource() {
        seedV9()
        val first = AssistantProfile("first", "一", "原提示\n", "first.png", 42, false, listOf("s2", "s1"))
        val second = AssistantProfile("second", "二", "二的提示", createdAt = 81, enabledSkillIds = emptyList())
        val source = LegacyAssistantCodec.encode(LegacyAssistantCodec.Snapshot(listOf(second, first), first.id))
        prefs(source)
        val db = database()
        val store = SqliteAssistantStore(db).also { it.requireReady() }
        assertEquals(30, db.readableDatabase.version)
        assertEquals(listOf(second, first), store.list())
        assertEquals(first.id, store.selectedId.value)
        assertEquals(source, raw())
        assertNull(db.conversations().single().assistantId)
        assertEquals("旧草稿", db.conversations().single().draft)
        val created = db.create(first.id)
        assertEquals(first.id, created.assistantId)
        store.select(second.id)
        store.save(first.copy(prompt = "changed"))
        db.close()
        prefs("not valid JSON any more") // A successfully migrated source is never reread.
        val reopened = database()
        val restored = SqliteAssistantStore(reopened).also { it.requireReady() }
        assertEquals(second.id, restored.selectedId.value)
        assertEquals(listOf(second.id, first.id), restored.list().map { it.id })
        assertEquals("changed", restored.read(first.id)?.prompt)
        assertEquals(first.id, reopened.assistantId(created.id))
        assertNull(reopened.assistantId(ConversationId("old")))
    }

    fun testV21AddsDescriptionWithoutChangingProfilesSelectionOrConversationBinding() {
        val helper = database()
        val store = SqliteAssistantStore(helper)
        val profile = store.save(AssistantProfile("described", "助手", "提示词", "avatar.png", 42,
            false, listOf("skill-one")))
        store.select(profile.id)
        val conversation = helper.create(profile.id)
        val sql = helper.writableDatabase
        sql.execSQL("ALTER TABLE assistants RENAME TO assistants_new")
        sql.execSQL("CREATE TABLE assistants(id TEXT PRIMARY KEY NOT NULL,name TEXT NOT NULL,prompt TEXT NOT NULL,avatar_file_name TEXT,created_at INTEGER NOT NULL,memory_enabled INTEGER NOT NULL CHECK(memory_enabled IN (0,1)),enabled_skill_ids TEXT NOT NULL,position INTEGER NOT NULL UNIQUE)")
        sql.execSQL("INSERT INTO assistants SELECT id,name,prompt,avatar_file_name,created_at,memory_enabled,enabled_skill_ids,position FROM assistants_new")
        sql.execSQL("DROP TABLE assistants_new")
        sql.version = 21
        helper.close()
        val upgraded = database()
        val restored = SqliteAssistantStore(upgraded).also { it.requireReady() }
        assertEquals(30, upgraded.readableDatabase.version)
        assertEquals(profile, restored.read(profile.id))
        assertEquals(profile.id, restored.selectedId.value)
        assertEquals(profile.id, upgraded.assistantId(conversation.id))
        val edited = restored.compareAndSet(profile, profile.copy(description = "整理文档与资料"))
        rejected { restored.compareAndSet(profile, profile.copy(description = "stale")) }
        val duplicate = restored.duplicate(profile.id)
        assertEquals(edited.description, duplicate.description)
        upgraded.close()
        assertEquals(edited, SqliteAssistantStore(database()).read(profile.id))
    }

    fun testDescriptionCodecAndPreferenceRoundTripIncludingOldMissingField() {
        val profile = AssistantProfile("intro", "介绍", "prompt", description = "一句话介绍")
        val snapshot = LegacyAssistantCodec.Snapshot(listOf(profile), profile.id)
        assertEquals(snapshot, LegacyAssistantCodec.decode(LegacyAssistantCodec.encode(snapshot)))
        val old = JSONObject(LegacyAssistantCodec.encode(snapshot))
        old.getJSONArray("profiles").getJSONObject(0).remove("description")
        assertEquals("", LegacyAssistantCodec.decode(old.toString()).profiles.single().description)
        val preferences = context.getSharedPreferences("daiyu-assistants", 0)
        val legacy = SharedPreferencesAssistantStore(preferences)
        legacy.save(profile)
        assertEquals(profile, SharedPreferencesAssistantStore(preferences).read(profile.id))
    }

    fun testCorruptLegacyRollsBackSchemaAndVersionThenCanRetry() {
        seedV9()
        val bad = "{\"profiles\":[],\"activeId\":\"missing\"}"
        prefs(bad)
        val failed = database()
        rejected { SqliteAssistantStore(failed).requireReady() }
        failed.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath("conversations.db").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(9, db.version)
            assertFalse(db.rawQuery("SELECT 1 FROM sqlite_master WHERE name='assistants'", null).use { it.moveToFirst() })
            assertFalse(db.rawQuery("PRAGMA table_info(conversations)", null).use { c ->
                var found = false; while(c.moveToNext()) if(c.getString(1) == "assistant_id") found = true; found
            })
            assertEquals("旧草稿", db.rawQuery("SELECT draft FROM conversations", null).use { it.moveToFirst(); it.getString(0) })
        }
        assertEquals(bad, raw())
        prefs(LegacyAssistantCodec.encode(LegacyAssistantCodec.Snapshot(listOf(AssistantProfile("fixed", "修复", "")), "fixed")))
        assertEquals("fixed", SqliteAssistantStore(database()).also { it.requireReady() }.selectedId.value)
    }

    fun testStoreMutationRollsBackAndNeverPublishesIgnoredWrites() {
        val db = database(); val store = SqliteAssistantStore(db)
        val before = store.list()
        db.writableDatabase.execSQL("CREATE TRIGGER ignore_assistant BEFORE INSERT ON assistants BEGIN SELECT RAISE(IGNORE); END")
        rejected { store.save(AssistantProfile("ignored", "ignored", "")) }
        assertEquals(before, store.list())
        db.close()
        assertEquals(before, SqliteAssistantStore(database()).list())
    }

    fun testBindingCasGlobalSwitchDeletionAndAcceptanceRollback() {
        val db = database(); val store = SqliteAssistantStore(db)
        store.save(AssistantProfile("other", "其他", ""))
        val original = db.create("default"); val legacy = db.create()
        db.draft(original.id, "retained")
        store.select("other")
        assertEquals("default", db.assistantId(original.id))
        assertNull(db.assistantId(legacy.id))
        assertFalse(db.bindAssistant(original.id, null, "other"))
        assertTrue(db.bindAssistant(legacy.id, null, "other"))
        assertFalse(db.bindAssistant(legacy.id, null, "default"))
        val (u, a) = pair(original.id)
        rejected { db.send(u, a, expectedAssistantId = "other") }
        assertEquals("retained", db.conversations().first { it.id == original.id }.draft)
        assertTrue(db.messages(original.id).isEmpty())
        store.delete("default")
        assertEquals("default", db.assistantId(original.id))
        rejected { db.send(u, a, expectedAssistantId = "default") }
        assertTrue(db.bindAssistant(original.id, "default", "other"))
        val accepted = db.send(u, a, expectedAssistantId = "other")
        assertEquals("other", accepted.assistantId)
        rejected { db.bindAssistant(original.id, "other", "other") } // Running gate.
        db.save(a.copy(status = RunStatus.COMPLETED))
        assertEquals("", db.conversations().first { it.id == original.id }.draft)
    }

    fun testNewSendFreezesBindingAndVerifiesReceiptAfterTrigger() {
        val db = database(); val store = SqliteAssistantStore(db)
        store.save(AssistantProfile("other", "其他", ""))
        db.draft("composer")
        val (u, a) = pair(ConversationId("new-owner"))
        db.writableDatabase.execSQL("CREATE TRIGGER tamper_binding AFTER INSERT ON messages BEGIN UPDATE conversations SET assistant_id='other' WHERE id=NEW.conversation_id; END")
        rejected { db.send(u, a, true, "default") }
        assertTrue(db.conversations().isEmpty())
        assertEquals("composer", db.draft())
        db.writableDatabase.execSQL("DROP TRIGGER tamper_binding")
        store.select("other")
        assertEquals("default", db.send(u, a, true, "default").assistantId)
    }

    fun testBranchAndBackupPreserveBindingAndV1StaysUnbound() {
        val db = database(); val store = SqliteAssistantStore(db)
        store.save(AssistantProfile("bound", "绑定", ""))
        val owner = db.create("bound"); val (u, a) = pair(owner.id)
        db.send(u, a); db.save(a.copy(status = RunStatus.COMPLETED))
        val branch = ContextService(context, db).branch(owner.id, a.id)
        assertEquals("bound", db.assistantId(branch))
        val backup = db.exportConversationBackup(owner.id)
        assertEquals(3, JSONObject(backup).getInt("version"))
        db.delete(owner.id); store.delete("bound")
        db.importBackup(backup)
        assertEquals("bound", db.assistantId(owner.id)) // Missing stays missing; never default.
        db.delete(owner.id)
        val envelope = JSONObject(backup)
        val payload = JSONObject(envelope.getString("payload"))
        payload.getJSONArray("conversations").getJSONObject(0).apply {
            remove("assistant_id"); remove("workspace_binding")
        }
        val text = payload.toString()
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        db.importBackup(envelope.put("version", 1).put("payload", text).put("sha256", digest).toString())
        assertNull(db.assistantId(owner.id))
    }
    fun testV2NullBindingReceiptIsExactAndTamperingRollsBack() {
        val db = database(); val owner = db.create("default")
        db.writableDatabase.execSQL("UPDATE conversations SET assistant_id=NULL WHERE id=?", arrayOf(owner.id.value))
        val exported = JSONObject(db.exportConversationBackup(owner.id))
        assertEquals(3, exported.getInt("version"))
        val payload = JSONObject(exported.getString("payload"))
        payload.getJSONArray("conversations").getJSONObject(0).remove("workspace_binding")
        val text = payload.toString()
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        val backup = exported.put("version", 2).put("payload", text).put("sha256", digest).toString()
        db.delete(owner.id); db.importBackup(backup)
        assertNull(db.assistantId(owner.id))
        db.delete(owner.id)
        db.writableDatabase.execSQL("CREATE TRIGGER corrupt_null_backup AFTER INSERT ON conversations BEGIN " +
            "UPDATE conversations SET assistant_id='tampered' WHERE id=NEW.id; END")
        try {
            rejected { db.importBackup(backup) }
            assertTrue(db.conversations().isEmpty())
        } finally { db.writableDatabase.execSQL("DROP TRIGGER corrupt_null_backup") }
    }

    fun testRunningAssistantInAnotherConversationCannotBeDeletedButIdleBindingCan() {
        val db = database(); val store = SqliteAssistantStore(db)
        store.save(AssistantProfile("other", "其他", ""))
        val running = db.create("default")
        val viewing = db.create("other")
        db.draft(viewing.id, "other draft")
        store.select("other")
        val (user, assistant) = pair(running.id)
        db.send(user, assistant, expectedAssistantId = "default")
        val profiles = store.list()
        try { store.delete("default"); fail("running assistant deleted") }
        catch (failure: IllegalStateException) { assertEquals("请先结束任务", failure.message) }
        assertEquals(profiles, store.list())
        assertEquals("other", store.selectedId.value)
        assertEquals("default", db.assistantId(running.id))
        assertEquals("other", db.assistantId(viewing.id))
        assertEquals("other draft", db.conversations().first { it.id == viewing.id }.draft)
        assertEquals(RunStatus.RUNNING, db.messages(running.id).last().second.status)
        db.save(assistant.copy(status = RunStatus.COMPLETED))
        store.delete("default")
        assertNull(store.read("default"))
        assertEquals("default", db.assistantId(running.id))
    }

}
