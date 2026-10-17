package app.daiyu.android.title

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.storage.ModelSettings
import java.util.UUID

@Suppress("DEPRECATION")
class TitleModelCoordinatorTest : InstrumentationTestCase() {
    private lateinit var db: SQLiteDatabase
    private lateinit var settings: TitleModelSettingsStore
    private lateinit var commits: SqliteTitleCommitAdapter
    private val owner = ConversationId("a")
    private val run = RunId("run-a")
    private val binding = TitleModelBinding("dedicated-title", "fixed-profile")
    private var calls = 0
    private var savedUsage: ParentTitleUsage? = null
    private fun turn() = listOf(
        Message(MessageId("u"), owner, run, "user", listOf(MessageBlock.Text("u", "synthetic question")), RunStatus.COMPLETED),
        Message(MessageId("m"), owner, run, "assistant", listOf(MessageBlock.Text("a", "synthetic answer")), RunStatus.COMPLETED))

    override fun setUp() {
        super.setUp()
        db = SQLiteDatabase.create(null)
        db.execSQL("CREATE TABLE conversations(id TEXT PRIMARY KEY,title TEXT NOT NULL)")
        db.execSQL("CREATE TABLE messages(seq INTEGER PRIMARY KEY AUTOINCREMENT,id TEXT UNIQUE,conversation_id TEXT,run_id TEXT,role TEXT,status TEXT,blocks TEXT NOT NULL DEFAULT '[]',history_segments TEXT NOT NULL DEFAULT '[]')")
        db.execSQL("INSERT INTO conversations VALUES('a','新会话'),('b','新会话')")
        db.execSQL("INSERT INTO messages(id,conversation_id,run_id,role,status) VALUES('u','a','run-a','user','COMPLETED'),('m','a','run-a','assistant','COMPLETED')")
        db.execSQL("UPDATE messages SET blocks=? WHERE id='u'", arrayOf("[{\"type\":\"text\",\"item\":\"u\",\"text\":\"synthetic question\"}]"))
        db.beginTransaction()
        try { SqliteTitleCommitAdapter.installSchema(db); db.setTransactionSuccessful() } finally { db.endTransaction() }
        val namespace = "title-coordinator-test-${UUID.randomUUID()}"
        var stored: ModelSettings? = null
        settings = TitleModelSettingsStore(instrumentation.targetContext.getSharedPreferences(namespace, Context.MODE_PRIVATE),
            { requireNotNull(stored) }, { value, _, _ -> stored = value }, namespace)
        settings.save(TitleModelProfile(binding, ModelSettings("https://title.invalid", "title-model", "synthetic-key", false)))
        commits = SqliteTitleCommitAdapter({ db }, ParentTitleUsagePort { _, receipt -> savedUsage = receipt })
        calls = 0; savedUsage = null
    }
    override fun tearDown() { db.close(); super.tearDown() }
    private fun coordinator(during: () -> Unit = {}, fail: Boolean = false) = TitleModelCoordinator(settings, commits) { frozen ->
        assertEquals("title-model", frozen.model)
        object : ModelPort {
            override fun open(history: List<Message>): ModelSession = error("must explicitly disable tools")
            override fun open(history: List<Message>, definitions: List<ToolDefinition>): ModelSession {
                assertTrue(definitions.isEmpty())
                return object : ModelSession {
                    override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                        calls++; during()
                        sink(ModelEvent.TextDelta("title", "not a committed title"))
                        sink(ModelEvent.TextFinal("title", "最终标题"))
                        sink(ModelEvent.Usage(100, 40, 5))
                        sink(ModelEvent.Usage(9, null, null))
                        if (fail) error("synthetic terminal failure")
                    }
                    override fun cancel() { }
                }
            }
        }
    }
    private fun title(id: String) = db.rawQuery("SELECT title FROM conversations WHERE id=?", arrayOf(id)).use { it.moveToFirst(); it.getString(0) }

    fun testDedicatedModelFinalOnlyAndLatestUsageOwnedByParent() {
        val c = coordinator(); val job = c.prepare(owner, run).job!!
        assertEquals(TitleOutcome.SAVED, c.generate(job, turn()))
        assertEquals("最终标题", title("a")); assertEquals("新会话", title("b"))
        assertEquals(owner, savedUsage!!.conversationId); assertEquals(run, savedUsage!!.parentRunId)
        assertEquals(ModelEvent.Usage(9, null, null), savedUsage!!.usage)
        assertEquals(TitleOutcome.ALREADY_STARTED, c.generate(job, turn())); assertEquals(1, calls)
        c.close()
    }
    fun testManualSameTitleRenameBeforePreparationStillHasPriority() {
        db.execSQL("UPDATE conversations SET title='新会话' WHERE id='a'")
        val c = coordinator()
        assertEquals(TitleOutcome.INELIGIBLE, c.prepare(owner, run).outcome)
        assertEquals(0, calls); assertNull(savedUsage); c.close()
    }
    fun testManualSameTitleRenameDuringGenerationWinsButRealRequestUsageRemains() {
        val c = coordinator(during = { db.execSQL("UPDATE conversations SET title='新会话' WHERE id='a'") })
        val job = c.prepare(owner, run).job!!
        assertEquals(TitleOutcome.STALE, c.generate(job, turn()))
        assertEquals("新会话", title("a")); assertNotNull(savedUsage)
        c.close()
    }
    fun testDisableDuringGenerationAndOldOwnerCloseFenceLateCallbacks() {
        val disabled = coordinator(during = { settings.disable() })
        val job = disabled.prepare(owner, run).job!!
        assertEquals(TitleOutcome.OWNER_REVOKED, disabled.generate(job, turn()))
        assertNotNull(savedUsage); assertNull(savedUsage!!.usage); disabled.close()
    }
    fun testDisableThenReenableSameProfileCannotResurrectOldJob() {
        val c = coordinator(during = {
            settings.disable()
            settings.save(TitleModelProfile(binding, ModelSettings("https://title.invalid", "title-model", "synthetic-key", false)))
        })
        val job = c.prepare(owner, run).job!!
        assertEquals(TitleOutcome.OWNER_REVOKED, c.generate(job, turn()))
        assertNotNull(savedUsage); assertNull(savedUsage!!.usage); c.close()
    }
    fun testFirstMessageEditDuringGenerationUsesPreservedLateGuard() {
        val c = coordinator(during = { db.execSQL("UPDATE messages SET blocks='[]' WHERE id='u'") })
        val job = c.prepare(owner, run).job!!
        assertEquals(TitleOutcome.STALE, c.generate(job, turn()))
        assertEquals("新会话", title("a")); assertNotNull(savedUsage); c.close()
    }
    fun testCloseAndRevokeDoNotGenerateOrSave() {
        val c = coordinator(); val job = c.prepare(owner, run).job!!
        c.revoke(job)
        assertEquals(TitleOutcome.OWNER_REVOKED, c.generate(job, turn()))
        val other = c.prepare(owner, run).job!!
        c.close()
        assertEquals(TitleOutcome.OWNER_REVOKED, c.generate(other, turn()))
        assertEquals(0, calls); assertNull(savedUsage)
    }
    fun testCloseDuringHeldTransportCannotSaveEvenIfCancelDoesNothing() {
        lateinit var c: TitleModelCoordinator
        c = coordinator(during = { c.close() })
        val job = c.prepare(owner, run).job!!
        assertEquals(TitleOutcome.OWNER_REVOKED, c.generate(job, turn()))
        assertEquals("新会话", title("a")); assertNotNull(savedUsage)
    }
    fun testFailedTerminalAndWrongConversationNeverSave() {
        val c = coordinator(fail = true); val job = c.prepare(owner, run).job!!
        assertEquals(TitleOutcome.MODEL_FAILED, c.generate(job, turn())); assertNotNull(savedUsage)
        assertEquals(ModelEvent.Usage(9, null, null), savedUsage!!.usage)
        val wrong = c.prepare(owner, run).job!!
        assertEquals(TitleOutcome.INVALID_INPUT, c.generate(wrong, turn().map { it.copy(conversationId = ConversationId("b")) }))
        assertEquals(1, calls); assertEquals("新会话", title("a"))
        c.close()
    }
    fun testHeldNextCloseDisableAndRebindSynchronouslyCancelRealSession() {
        for (change in listOf("close", "disable", "rebind")) {
            settings.save(TitleModelProfile(binding, ModelSettings("https://title.invalid", "title-model", "synthetic-key", false)))
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val cancelled = java.util.concurrent.CountDownLatch(1)
            val done = java.util.concurrent.CountDownLatch(1)
            val result = java.util.concurrent.atomic.AtomicReference<TitleOutcome>()
            val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
            val c = TitleModelCoordinator(settings, commits) {
                assertFalse(Thread.holdsLock(settings.admissionGate))
                ModelPort {
                    assertFalse(Thread.holdsLock(settings.admissionGate))
                    object : ModelSession {
                        override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                            assertFalse(Thread.holdsLock(settings.admissionGate))
                            entered.countDown()
                            check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                            sink(ModelEvent.Usage(13, null, 2)) // known usage survives revoke
                            sink(ModelEvent.TextFinal("late", "迟到标题"))
                        }
                        override fun cancel() {
                            assertFalse(Thread.holdsLock(settings.admissionGate))
                            cancelled.countDown()
                        }
                    }
                }
            }
            val job = c.prepare(owner, run).job!!
            val worker = Thread {
                try { result.set(c.generate(job, turn())) } catch (t: Throwable) { failure.set(t) }
                finally { done.countDown() }
            }.apply { start() }
            try {
                assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
                when (change) {
                    "close" -> c.close()
                    "disable" -> settings.disable()
                    else -> settings.save(TitleModelProfile(TitleModelBinding("replacement", UUID.randomUUID().toString()),
                        ModelSettings("https://replacement.invalid", "title-model", "synthetic-key", false)))
                }
                assertEquals("cancel must complete before configuration call returns", 0L, cancelled.count)
                assertEquals("新会话", title("a"))
            } finally { release.countDown() }
            assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS))
            worker.join(1000)
            failure.get()?.let { throw it }
            assertEquals(TitleOutcome.OWNER_REVOKED, result.get())
            assertEquals(ModelEvent.Usage(13, null, 2), savedUsage!!.usage)
            assertEquals("新会话", title("a")); c.close()
        }
    }

    fun testConfigurationWinsBetweenRevisionCheckAndOpenPublication() {
        val opening = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(1)
        val cancelled = java.util.concurrent.atomic.AtomicInteger()
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val result = java.util.concurrent.atomic.AtomicReference<TitleOutcome>()
        val c = TitleModelCoordinator(settings, commits) {
            ModelPort {
                assertFalse(Thread.holdsLock(settings.admissionGate))
                opening.countDown()
                check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                object : ModelSession {
                    override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) { requests.incrementAndGet() }
                    override fun cancel() { cancelled.incrementAndGet() }
                }
            }
        }
        val job = c.prepare(owner, run).job!!
        Thread { try { result.set(c.generate(job, turn())) } finally { done.countDown() } }.start()
        try {
            assertTrue(opening.await(10, java.util.concurrent.TimeUnit.SECONDS))
            settings.disable() // must not block on open, nor allow an old job to publish later
        } finally { release.countDown() }
        assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(TitleOutcome.OWNER_REVOKED, result.get())
        assertEquals(0, requests.get()); assertEquals(1, cancelled.get())
        assertNull(savedUsage); c.close()
    }

    fun testUsageFailureIsNotSwallowedByCoordinator() {
        val failing = SqliteTitleCommitAdapter({ db }, ParentTitleUsagePort { _, _ -> error("synthetic usage failure") })
        val c = TitleModelCoordinator(settings, failing) { _ ->
            ModelPort { object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) { sink(ModelEvent.TextFinal("t", "标题")) }
                override fun cancel() { }
            } }
        }
        try { c.generate(c.prepare(owner, run).job!!, turn()); fail() }
        catch (_: TitleUsagePersistenceException) { }
        assertEquals("新会话", title("a")); c.close()
    }

    fun testDisabledBindingFailsExplicitlyWithoutModelCalls() {
        val c = TitleModelCoordinator(settings, commits) { calls++; error("must not create") }
        settings.disable()
        assertEquals(TitleOutcome.DISABLED, c.prepare(owner, run).outcome)
        assertEquals(0, calls); c.close()
    }
}
