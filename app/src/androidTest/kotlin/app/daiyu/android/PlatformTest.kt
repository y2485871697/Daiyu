package app.daiyu.android

import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.model.SseReader
import app.daiyu.android.storage.*
import app.daiyu.android.platform.AndroidBatteryTool
import app.daiyu.android.platform.AndroidReadOnlyTools
import java.io.StringReader
import org.json.JSONObject

@Suppress("DEPRECATION")
class PlatformTest:InstrumentationTestCase() {
    private val keyAliasPrefix="app.daiyu.android.test.credentials.${java.util.UUID.randomUUID()}"
    private fun settingsStore(context:android.content.Context)=ModelSettingsStore(context,keyAliasPrefix)
    override fun tearDown() {
        try {
            val keys=java.security.KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
            java.util.Collections.list(keys.aliases()).filter {it==keyAliasPrefix || it.startsWith("$keyAliasPrefix.")}.forEach {keys.deleteEntry(it)}
        } finally {super.tearDown()}
    }

    private fun testContext()=testContext(instrumentation.targetContext)
    fun testPersistenceRecoveryAndDraftIsolation() {
        val context=testContext()
        context.deleteDatabase("conversations.db")
        var db=ConversationDatabase(context)
        val a=db.create();val b=db.create()
        db.draft(a.id,"草稿A");db.draft(b.id,"草稿B")
        val run=RunId("r")
        val user=Message(MessageId("u"),a.id,run,"user",listOf(MessageBlock.Text("u","查询电量")),RunStatus.COMPLETED)
        val assistant=Message(MessageId("a"),a.id,run,"assistant",listOf(MessageBlock.Text("t","已确认"),MessageBlock.Tool(ToolInvocation(ToolCallId("c"),"get_battery_status","{}"),"{\"ok\":true}")),RunStatus.RUNNING)
        db.send(user,assistant);db.close()
        db=ConversationDatabase(context);db.recover()
        val restored=db.messages(a.id).last().second
        assertEquals(MessageId("a"),restored.id);assertEquals(RunStatus.INTERRUPTED,restored.status)
        assertEquals("{\"ok\":true}",(restored.blocks[1] as MessageBlock.Tool).result)
        assertEquals("",db.conversations().first {it.id==a.id}.draft)
        assertEquals("草稿B",db.conversations().first {it.id==b.id}.draft)
        assertTrue(db.messages(b.id).isEmpty())
        try {db.save(restored.copy(status=RunStatus.RUNNING));fail("late receipt must fail")} catch(_:PersistenceException) {}
        assertEquals(2,db.messages(a.id).size)
        db.close()
    }
    fun testBatteryAllowlistAndRealBackend() {
        val context=instrumentation.targetContext
        val call=ToolInvocation(ToolCallId("c"),"get_battery_status","{}")
        assertEquals("capability_disabled",JSONObject(AndroidBatteryTool(context,false).execute(call)).getString("error"))
        assertEquals("unknown_tool",JSONObject(AndroidBatteryTool(context,true).execute(call.copy(name="delete"))).getString("error"))
        assertEquals("invalid_arguments",JSONObject(AndroidBatteryTool(context,true).execute(call.copy(arguments="{\"write\":true}"))).getString("error"))
        val result=JSONObject(AndroidBatteryTool(context,true).execute(call))
        assertTrue(result.getBoolean("ok"));assertTrue(result.getInt("percent") in 0..100)
    }
    fun testDeviceStatusPermissionArgumentsAndRealBackend() {
        val context=instrumentation.targetContext
        val call=ToolInvocation(ToolCallId("device"),"device_status","{}")
        val disabled=AndroidReadOnlyTools(context,emptySet())
        assertEquals("capability_disabled",JSONObject(disabled.execute(call)).getString("error"))
        val enabled=mutableSetOf(ReadOnlyTool.DEVICE_STATUS)
        val tools=AndroidReadOnlyTools(context,enabled);enabled.clear()
        for(arguments in listOf("", "[]", "null", "{}junk", "{}{}", "{/*comment*/}", "{\"extra\":1}", "{;}", "{\u00a0}")) {
            assertEquals(arguments,"invalid_arguments",JSONObject(tools.execute(call.copy(arguments=arguments))).getString("error"))
            assertEquals("invalid_arguments",JSONObject(AndroidBatteryTool(context,true).execute(call.copy(name="get_battery_status",arguments=arguments))).getString("error"))
        }
        assertEquals("unknown_tool",JSONObject(tools.execute(call.copy(name="set_volume"))).getString("error"))
        assertEquals("capability_disabled",JSONObject(tools.execute(call.copy(name="get_battery_status"))).getString("error"))
        val result=JSONObject(tools.execute(call.copy(arguments=" \n{ \t}\r")))
        assertTrue(result.getBoolean("ok"));assertTrue(result.getLong("observed_at")>0)
        assertEquals(android.os.Build.VERSION.SDK_INT,result.getJSONObject("android").getInt("api_level"))
        assertTrue(result.getLong("uptime_ms")>=0)
        val memory=result.getJSONObject("memory");assertTrue(memory.getBoolean("ok"));assertTrue(memory.getLong("total_bytes")>0)
        val storage=result.getJSONObject("storage");assertTrue(storage.getBoolean("ok"));assertEquals("app_data_volume",storage.getString("scope"))
        assertTrue(storage.getLong("available_bytes") in 0..storage.getLong("total_bytes"))
        assertTrue(result.getJSONObject("battery").getBoolean("ok"))
        for(secret in listOf("serial", "fingerprint", "android_id", "apiKey")) assertFalse(result.has(secret))
    }
    fun testDeviceStatusPartialUnavailableDoesNotHideOtherReadings() {
        val context=object:android.content.ContextWrapper(instrumentation.targetContext) {
            override fun getSystemService(name:String):Any? {
                if(name==android.content.Context.ACTIVITY_SERVICE) throw SecurityException("synthetic-private-detail")
                return super.getSystemService(name)
            }
        }
        val result=JSONObject(AndroidReadOnlyTools(context,setOf(ReadOnlyTool.DEVICE_STATUS)).execute(
            ToolInvocation(ToolCallId("partial"),"device_status","{}")))
        assertTrue(result.getBoolean("ok"));assertEquals("permission_denied",result.getJSONObject("memory").getString("error"))
        assertTrue(result.getJSONObject("storage").getBoolean("ok"));assertTrue(result.getJSONObject("battery").getBoolean("ok"))
        assertFalse(result.toString().contains("synthetic-private-detail"))
    }
    fun testDeviceStatusSettingsDefaultAndSnapshot() {
        val context=testContext();val prefs=context.getSharedPreferences("model-settings",0)
        prefs.edit().clear().commit()
        val store=settingsStore(context)
        prefs.edit().putBoolean("battery",true).commit()
        assertFalse(store.load().deviceStatusEnabled)
        val config=ModelSettings("https://example.invalid","fixture","synthetic-key",false,deviceStatusEnabled=true)
        store.save(config);val snapshot=store.load()
        assertEquals(setOf(ReadOnlyTool.DEVICE_STATUS),snapshot.enabledTools())
        store.save(config.copy(deviceStatusEnabled=false,batteryEnabled=true))
        assertEquals(setOf(ReadOnlyTool.DEVICE_STATUS),snapshot.enabledTools())
        assertEquals(setOf(ReadOnlyTool.BATTERY),store.load().enabledTools())
        prefs.edit().clear().commit()
    }
    fun testEncryptedSettingsAndSseFrames() {
        val context=testContext()
        val config=ModelSettings("https://example.invalid/gateway","fixture-model","synthetic-test-key",true,"/responses")
        settingsStore(context).save(config);assertEquals(config,settingsStore(context).load())
        assertEquals("https://example.invalid/gateway/responses",settingsStore(context).load().requestEndpoint())
        assertFalse(context.getSharedPreferences("model-settings",0).getString("key","")!!.contains(config.apiKey))
        val stoppedFrames=mutableListOf<String>()
        SseReader.read(StringReader("data: complete\n\ndata: should-not-read\n\n").buffered()) {stoppedFrames.add(it);false}
        assertEquals(listOf("complete"),stoppedFrames)
        val frames=mutableListOf<String>()
        SseReader.read(StringReader(": comment\r\ndata: 一\r\ndata: 二\r\n\r\ndata: 三\n\n").buffered()) {frames.add(it);true}
        assertEquals(listOf("一\n二","三"),frames)
        try {SseReader.read(StringReader("data: partial").buffered()) {true};fail("must reject unterminated event")} catch (failure:ModelRequestException) {assertEquals(ModelFailure.INCOMPLETE,failure.failure)}
        context.getSharedPreferences("model-settings",0).edit().clear().commit()
    }
    fun testCredentialFailuresPreserveConfigurationAndRejectSend() {
        for(kind in listOf("iv","ciphertext","missing-key","partial")) {
            val context=testContext(instrumentation.targetContext,"credential-${java.util.UUID.randomUUID()}-")
            val store=settingsStore(context)
            val prefs=context.getSharedPreferences("model-settings",0)
            val config=ModelSettings("https://example.invalid/gateway","fixture-model","synthetic-key",false,"/responses",true)
            store.save(config)
            val alias=prefs.getString("key-alias",null)!!
            when(kind) {
                "iv"->prefs.edit().remove("iv").commit()
                "ciphertext"->{
                    val bytes=android.util.Base64.decode(prefs.getString("key",null),android.util.Base64.NO_WRAP)
                    bytes[0]=(bytes[0].toInt() xor 1).toByte()
                    prefs.edit().putString("key",android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP)).commit()
                }
                "missing-key"->java.security.KeyStore.getInstance("AndroidKeyStore").apply {load(null);deleteEntry(alias)}
                "partial"->prefs.edit().remove("key").commit()
            }
            val before=prefs.all.toMap()
            val editing=settingsStore(context).loadForEditing()
            assertTrue(editing.credentialUnavailable);assertEquals(config.copy(apiKey=""),editing.settings)
            try {store.load();fail("damaged credentials must be rejected")} catch (_:CredentialUnavailableException) {}
            assertEquals(before,prefs.all)
            val keys=java.security.KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
            if(kind=="missing-key") assertFalse(keys.containsAlias(alias))
            val db=ConversationDatabase(context);val a=db.create();val b=db.create()
            db.draft("new draft");db.draft(a.id,"A draft");db.draft(b.id,"B draft")
            val conversations=db.conversations()
            val requests=java.util.concurrent.atomic.AtomicInteger()
            val controller=ChatController(instrumentation.targetContext.applicationContext as android.app.Application,db,store,
                context.getSharedPreferences("conversation-ui",0),{requests.incrementAndGet();error("no model request")})
            try {
                val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
                while(!controller.ready && System.nanoTime()<deadline) Thread.sleep(10)
                assertTrue(controller.ready)
                instrumentation.runOnMainSync {assertNull(controller.send(null,"attempt"));assertNull(controller.send(a,"attempt"))}
                assertEquals(0,requests.get());assertNull(controller.active);assertEquals(conversations,db.conversations())
                assertEquals("new draft",db.draft());assertTrue(db.messages(a.id).isEmpty());assertTrue(db.messages(b.id).isEmpty())
                val repaired=config.copy(apiKey="replacement-key")
                store.save(repaired);assertEquals(repaired,settingsStore(context).load())
                assertFalse(keys.containsAlias(alias));assertEquals(conversations,db.conversations());assertEquals("new draft",db.draft())
            } finally {controller.close();prefs.edit().clear().commit();context.deleteDatabase("conversations.db")}
        }
    }
    private fun pair(id:ConversationId,suffix:String):Pair<Message,Message> {
        val run=RunId("run-$suffix")
        return Message(MessageId("user-$suffix"),id,run,"user",listOf(MessageBlock.Text("u","发送")),RunStatus.COMPLETED) to
            Message(MessageId("assistant-$suffix"),id,run,"assistant",emptyList(),RunStatus.RUNNING)
    }
    fun testComposerCommitRollbackAndReopen() {
        val context=testContext();context.deleteDatabase("conversations.db")
        var db=ConversationDatabase(context)
        db.draft("未创建草稿");assertTrue(db.conversations().isEmpty())
        val a=db.create();db.draft(a.id,"A新稿")
        val pair=pair(ConversationId("new"),"new")
        try {db.send(pair.first,pair.second.copy(id=pair.first.id),true);fail("collision must roll back")} catch(_:android.database.SQLException) {}
        assertEquals("未创建草稿",db.draft());assertEquals(1,db.conversations().size)
        assertTrue(db.messages(pair.first.conversationId).isEmpty())
        db.send(pair.first,pair.second,true)
        assertEquals("",db.draft());assertEquals(2,db.conversations().size)
        assertEquals("A新稿",db.conversations().first {it.id==a.id}.draft)
        db.draft("下次新稿");db.close();db=ConversationDatabase(context)
        assertEquals("下次新稿",db.draft());assertEquals(2,db.messages(ConversationId("new")).size)
        db.close()
    }
    fun testLateReceiptIdentityTerminalAndDelete() {
        val context=testContext();context.deleteDatabase("conversations.db")
        val db=ConversationDatabase(context);val a=db.create();val b=db.create();db.draft(b.id,"B草稿")
        val (user,assistant)=pair(a.id,"identity");db.send(user,assistant)
        fun rejected(value:Message) {try {db.save(value);fail("late or mismatched write must fail")} catch(_:PersistenceException) {}}
        rejected(assistant.copy(conversationId=b.id));rejected(assistant.copy(runId=RunId("other")));rejected(assistant.copy(role="user"))
        val completed=assistant.copy(status=RunStatus.COMPLETED,blocks=listOf(MessageBlock.Text("m","完成")))
        db.save(completed);rejected(assistant)
        assertEquals(completed,db.messages(a.id).last().second)
        db.delete(a.id);rejected(assistant)
        assertTrue(db.messages(a.id).isEmpty());assertEquals("B草稿",db.conversations().single().draft)
        db.close()
    }
    fun testPaginationAndRecoveryAreStable() {
        val context=testContext();context.deleteDatabase("conversations.db")
        val db=ConversationDatabase(context);val a=db.create()
        repeat(61) {i->val (u,m)=pair(a.id,"page-$i");db.send(u,m);db.save(m.copy(status=RunStatus.COMPLETED))}
        for(limit in listOf(-1,0,201)) {try {db.messages(a.id,limit=limit);fail("invalid page size")} catch(_:IllegalArgumentException) {}}
        val tail=db.messages(a.id);val middle=db.messages(a.id,tail.first().first);val first=db.messages(a.id,middle.first().first)
        assertEquals(50,tail.size);assertEquals(50,middle.size);assertEquals(22,first.size)
        val all=first+middle+tail;assertEquals(122,all.map {it.second.id}.toSet().size)
        assertTrue(all.zipWithNext().all {it.first.first<it.second.first})
        val (u,m)=pair(a.id,"interrupt");db.send(u,m)
        db.save(m.copy(blocks=listOf(MessageBlock.Text("m","部分文本")),protocolItems=listOf("{\"type\":\"reasoning\"}")))
        db.draft(a.id,"下一条草稿");db.recover();val recovered=db.messages(a.id).last().second
        db.recover();assertEquals(recovered,db.messages(a.id).last().second)
        assertEquals(RunStatus.INTERRUPTED,recovered.status);assertEquals("部分文本",(recovered.blocks.single() as MessageBlock.Text).text)
        assertEquals(listOf("{\"type\":\"reasoning\"}"),recovered.protocolItems)
        assertEquals("下一条草稿",db.conversations().single().draft);db.close()
    }
    fun testVersionOneMigrationPreservesHistoryAndDraft() {
        val context=testContext();context.deleteDatabase("conversations.db")
        val old=context.openOrCreateDatabase("conversations.db",0,null)
        old.execSQL("CREATE TABLE conversations(id TEXT PRIMARY KEY,title TEXT NOT NULL,draft TEXT NOT NULL,updated INTEGER NOT NULL)")
        old.execSQL("CREATE TABLE messages(seq INTEGER PRIMARY KEY AUTOINCREMENT,id TEXT UNIQUE NOT NULL,conversation_id TEXT NOT NULL,run_id TEXT NOT NULL,role TEXT NOT NULL,blocks TEXT NOT NULL,status TEXT NOT NULL,error TEXT,protocol TEXT NOT NULL)")
        old.execSQL("CREATE INDEX messages_tail ON messages(conversation_id,seq)")
        old.execSQL("INSERT INTO conversations VALUES('legacy','旧会话','旧草稿',123)")
        old.execSQL("INSERT INTO messages(id,conversation_id,run_id,role,blocks,status,error,protocol) VALUES('old','legacy','old-run','assistant','[]','COMPLETED',NULL,'[]')")
        old.version=1;old.close()
        val db=ConversationDatabase(context)
        assertEquals("",db.draft());assertEquals("旧草稿",db.conversations().single().draft)
        assertEquals(MessageId("old"),db.messages(ConversationId("legacy")).single().second.id)
        assertEquals(2,db.readableDatabase.version);db.close()
    }
    fun testControllerCallbacksKeepNewDraftsAndOtherConversation() {
        val context=testContext();context.deleteDatabase("conversations.db")
        val db=ConversationDatabase(context)
        val config=ModelSettings("https://example.invalid/v1/responses","fixture","synthetic-key",false)
        val settings=settingsStore(context);settings.save(config)
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val terminal=java.util.concurrent.CountDownLatch(1)
        val model=ModelPort {object:ModelSession {
            override fun next(outputs:List<ToolOutput>,sink:(ModelEvent)->Unit) {
                entered.countDown();check(release.await(10,java.util.concurrent.TimeUnit.SECONDS))
                sink(ModelEvent.TextDelta("m","迟到内容"))
            }
            override fun cancel() {release.countDown()}
        }}
        val controller=ChatController(instrumentation.targetContext.applicationContext as android.app.Application,db,settings,context.getSharedPreferences("conversation-ui",0),{model})
        try {
            val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while(!controller.ready && System.nanoTime()<deadline) Thread.sleep(10)
            assertTrue(controller.ready)
            var saved:Conversation?=null
            instrumentation.runOnMainSync {saved=controller.send(null,"首条消息")}
            assertNotNull(saved);assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS))
            val b=db.create();db.draft(b.id,"B新稿");db.draft(saved!!.id,"A新稿");db.draft("未创建新稿")
            instrumentation.runOnMainSync {controller.observe {if(controller.active==null) terminal.countDown()}}
            release.countDown();assertTrue(terminal.await(5,java.util.concurrent.TimeUnit.SECONDS))
            assertEquals("A新稿",db.conversations().first {it.id==saved!!.id}.draft)
            assertEquals("B新稿",db.conversations().first {it.id==b.id}.draft)
            assertEquals("未创建新稿",db.draft());assertTrue(db.messages(b.id).isEmpty())
            assertEquals(RunStatus.COMPLETED,db.messages(saved!!.id).last().second.status)
            context.getSharedPreferences("model-settings",0).edit().clear().commit()
            instrumentation.runOnMainSync {assertNull(controller.send(null,"校验失败"))}
            assertEquals("未创建新稿",db.draft());assertEquals(2,db.conversations().size)
        } finally {release.countDown();controller.close()}
    }

}
