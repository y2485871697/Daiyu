package app.daiyu.android.storage

import android.content.Context
import android.content.ContextWrapper
import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.testContext
import java.io.File
import java.util.UUID

@Suppress("DEPRECATION")
class ConversationHistoryDatabaseTest : InstrumentationTestCase() {
    private val contexts = mutableListOf<Context>()
    private val databases = mutableListOf<ConversationDatabase>()

    private fun fresh(): Context {
        val token = UUID.randomUUID().toString()
        val base = testContext(instrumentation.targetContext, "history-$token-")
        val files = File(base.cacheDir, token).apply { check(mkdirs()) }
        return object : ContextWrapper(base) { override fun getFilesDir(): File = files }
            .also { contexts += it }
    }
    private fun open(context: Context) = ConversationDatabase(context).also { databases += it }
    override fun tearDown() {
        databases.forEach { it.close() }
        contexts.forEach { it.deleteDatabase("conversations.db"); it.filesDir.deleteRecursively() }
        super.tearDown()
    }
    private fun assistant(owner: ConversationId, run: RunId, status: RunStatus = RunStatus.RUNNING): Message {
        val id = MessageId("assistant-${UUID.randomUUID()}")
        val blocks = listOf(
            MessageBlock.Text("one", "正文一"),
            MessageBlock.Tool(ToolInvocation(ToolCallId("call-${UUID.randomUUID()}"), "lookup", "{}"), "工具结果"),
            MessageBlock.Text("two", "正文二")
        )
        val segment = MessageHistorySegment(id, "assistant", blocks, listOf("{\"type\":\"message\",\"role\":\"assistant\",\"content\":[]}"))
        return Message(id, owner, run, "assistant", blocks, status, historySegments = listOf(segment))
    }

    fun testSchema4UpgradeIsReopenableAndLegacyRowsKeepEmptySegments() {
        val context = fresh()
        context.openOrCreateDatabase("conversations.db", 0, null).use { old ->
            old.execSQL("CREATE TABLE conversations(id TEXT PRIMARY KEY,title TEXT NOT NULL,draft TEXT NOT NULL,updated INTEGER NOT NULL,folder TEXT NOT NULL DEFAULT '',pinned INTEGER NOT NULL DEFAULT 0)")
            old.execSQL("CREATE TABLE messages(seq INTEGER PRIMARY KEY AUTOINCREMENT,id TEXT UNIQUE NOT NULL,conversation_id TEXT NOT NULL,run_id TEXT NOT NULL,role TEXT NOT NULL,blocks TEXT NOT NULL,status TEXT NOT NULL,error TEXT,protocol TEXT NOT NULL)")
            old.execSQL("CREATE INDEX messages_tail ON messages(conversation_id,seq)")
            old.execSQL("CREATE TABLE composer(id INTEGER PRIMARY KEY CHECK(id=1),draft TEXT NOT NULL)")
            old.execSQL("INSERT INTO composer VALUES(1,'legacy')")
            old.execSQL("INSERT INTO conversations VALUES('c','title','draft',1,'',0)")
            old.execSQL("INSERT INTO messages(id,conversation_id,run_id,role,blocks,status,error,protocol) VALUES('m','c','r','assistant','[]','COMPLETED',NULL,'[]')")
            old.version = 4
        }
        var db = open(context)
        assertEquals(14, db.readableDatabase.version)
        assertEquals("[]", db.readableDatabase.rawQuery("SELECT history_segments FROM messages", null).use { it.moveToFirst(); it.getString(0) })
        db.close(); databases.remove(db)
        db = open(context)
        assertEquals("[]", db.readableDatabase.rawQuery("SELECT history_segments FROM messages", null).use { it.moveToFirst(); it.getString(0) })
    }

    fun testNonEmptySegmentsRoundTripKeepsAggregateBlocksAndTextBackup() {
        val source = fresh(); val db = open(source); val owner = db.create("default").id; val run = RunId("run-${UUID.randomUUID()}")
        val user = Message(MessageId("user-${UUID.randomUUID()}"), owner, run, "user", listOf(MessageBlock.Text("u", "问题")), RunStatus.COMPLETED)
        val pending = assistant(owner, run)
        db.send(user, pending); db.save(pending.copy(status = RunStatus.COMPLETED))
        val loaded = db.messages(owner).last().second
        assertEquals(listOf("正文一", "工具结果", "正文二"), loaded.blocks.map { when (it) {
            is MessageBlock.Text -> it.text
            is MessageBlock.Tool -> it.result ?: ""
            is MessageBlock.Image -> error("This text/tool fixture must not contain an image")
        } })
        assertEquals(1, loaded.historySegments.size)
        val backup = db.exportBackup()
        val target = fresh(); val imported = open(target)
        assertEquals(1 to 2, imported.importBackup(backup))
        assertEquals(backup, imported.exportBackup())
        var rejected=false
        try {imported.importBackup(backup)} catch(_:Exception) {rejected=true}
        assertTrue("text backup identity collision must reject",rejected)
        assertEquals(backup, imported.exportBackup())
        val envelope=org.json.JSONObject(backup)
        val payload=org.json.JSONObject(envelope.getString("payload"))
        payload.getJSONArray("conversations").getJSONObject(0).put("title","different content with all same identities")
        val raw=payload.toString()
        val digest=java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") {"%02x".format(it)}
        val changed=envelope.put("payload",raw).put("sha256",digest).toString()
        imported.preflightBackup(changed)
        var changedRejected=false
        try {imported.importBackup(changed)} catch(_:Exception) {changedRejected=true}
        assertTrue("different content must not be reported as a duplicate success",changedRejected)
        assertEquals(backup,imported.exportBackup())
    }

    fun testRecoverPreservesSegmentsAndDeleteRejectsLateSave() {
        val context = fresh(); val db = open(context); val owner = db.create("default").id; val run = RunId("run-${UUID.randomUUID()}")
        val user = Message(MessageId("user-${UUID.randomUUID()}"), owner, run, "user", emptyList(), RunStatus.COMPLETED)
        val pending = assistant(owner, run)
        db.send(user, pending)
        db.recover()
        val recovered = db.messages(owner).last().second
        assertEquals(RunStatus.INTERRUPTED, recovered.status); assertEquals(1, recovered.historySegments.size)
        db.delete(owner)
        try { db.save(pending.copy(status = RunStatus.COMPLETED)); fail("late save must be rejected") } catch (_: Exception) { }
        assertTrue(db.messages(owner).isEmpty()); assertTrue(db.conversations().none { it.id == owner })
    }
    fun testRealRunnerSegmentsSurviveCloseReopenAndContextSend() {
        val context=fresh();var db=open(context);val conversation=db.create("default").id
        val run=RunId("runner-run");val id=MessageId("runner-owner")
        val user=Message(MessageId("runner-user"),conversation,run,"user",listOf(MessageBlock.Text("u","question")),RunStatus.COMPLETED)
        val initial=Message(id,conversation,run,"assistant",emptyList(),RunStatus.RUNNING)
        val call=ToolInvocation(ToolCallId("runner-call"),"lookup","{}")
        db.send(user,initial)
        var next=0;var executions=0
        val session=object:ModelSession {
            override fun next(outputs:List<ToolOutput>,sink:(ModelEvent)->Unit) {
                if(next++==0) { sink(ModelEvent.TextFinal("first","before"));sink(ModelEvent.ToolReady(call)) }
                else { assertEquals(listOf(ToolOutput(call.id,"full result")),outputs);sink(ModelEvent.TextFinal("last","after")) }
            }
            override fun cancel() {}
        }
        val result=ChatRunner(ModelPort {session},db,BatteryTool {executions++;"full result"},{}).run(initial,listOf(user))
        assertEquals(RunStatus.COMPLETED,result.status);assertTrue(result.historySegments.isNotEmpty())
        db.close();databases.remove(db);db=open(context)
        val loaded=db.messages(conversation).last().second
        assertEquals(result,loaded);assertEquals(1,executions)
        val prepared=app.daiyu.android.context.ContextService(context,db).prepareSend(conversation)
        assertEquals(result,prepared.messages.last())
        assertEquals(listOf("before","full result","after"),MessageHistory.expand(prepared.messages).last().blocks.map {
            when(it) {
                is MessageBlock.Text -> it.text
                is MessageBlock.Tool -> it.result
                is MessageBlock.Image -> error("This runner text/tool fixture must not contain an image")
            }
        })
        val backup=db.exportBackup();assertTrue(backup.contains("history_segments"))
        var duplicateRejected=false
        try {db.importBackup(backup)} catch(_:Exception) {duplicateRejected=true}
        assertTrue(duplicateRejected);assertEquals(backup,db.exportBackup())
    }
    fun testInvalidSupplementSaveRollsBackAcrossCloseReopen() {
        val context=fresh();var db=open(context);val conversation=db.create("default").id;val run=RunId("conflict-run")
        val user=Message(MessageId("conflict-user"),conversation,run,"user",listOf(MessageBlock.Text("u","original")),RunStatus.COMPLETED)
        val initial=Message(MessageId("conflict-owner"),conversation,run,"assistant",emptyList(),RunStatus.RUNNING)
        db.send(user,initial);val before=db.messages(conversation)
        val bad=initial.copy(historySegments=listOf(MessageHistorySegment(user.id,"user",listOf(MessageBlock.Text("u","changed")))))
        var rejected=false
        try {db.save(bad)} catch(_:PersistenceException) {rejected=true}
        assertTrue(rejected);assertEquals(before,db.messages(conversation))
        db.close();databases.remove(db);db=open(context);assertEquals(before,db.messages(conversation))
    }

}
