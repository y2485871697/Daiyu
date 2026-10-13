package app.daiyu.android

import android.test.InstrumentationTestCase
import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import app.daiyu.android.domain.*
import app.daiyu.android.model.SseReader
import app.daiyu.android.storage.*
import app.daiyu.android.platform.AndroidBatteryTool
import java.io.StringReader
import org.json.JSONObject

@Suppress("DEPRECATION")
class PlatformTest:InstrumentationTestCase() {
    private fun testContext():Context=object:ContextWrapper(instrumentation.targetContext) {
        private fun testName(name:String)="platform-test-$name"
        override fun getDatabasePath(name:String)=baseContext.getDatabasePath(testName(name))
        override fun deleteDatabase(name:String)=baseContext.deleteDatabase(testName(name))
        override fun openOrCreateDatabase(name:String,mode:Int,factory:SQLiteDatabase.CursorFactory?)=
            baseContext.openOrCreateDatabase(testName(name),mode,factory)
        override fun openOrCreateDatabase(name:String,mode:Int,factory:SQLiteDatabase.CursorFactory?,handler:DatabaseErrorHandler?)=
            baseContext.openOrCreateDatabase(testName(name),mode,factory,handler)
        override fun getSharedPreferences(name:String,mode:Int)=baseContext.getSharedPreferences(testName(name),mode)
    }
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
        db.save(restored);assertEquals(2,db.messages(a.id).size)
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
    fun testEncryptedSettingsAndSseFrames() {
        val context=testContext()
        val config=ModelSettings("https://example.invalid/v1/responses","fixture-model","synthetic-test-key",true)
        ModelSettingsStore(context).save(config);assertEquals(config,ModelSettingsStore(context).load())
        assertFalse(context.getSharedPreferences("model-settings",0).getString("key","")!!.contains(config.apiKey))
        val stoppedFrames=mutableListOf<String>()
        SseReader.read(StringReader("data: complete\n\ndata: should-not-read\n\n").buffered()) {stoppedFrames.add(it);false}
        assertEquals(listOf("complete"),stoppedFrames)
        val frames=mutableListOf<String>()
        SseReader.read(StringReader(": comment\r\ndata: 一\r\ndata: 二\r\n\r\ndata: 三\n\n").buffered()) {frames.add(it);true}
        assertEquals(listOf("一\n二","三"),frames)
        try {SseReader.read(StringReader("data: partial").buffered()) {true};fail("must reject unterminated event")} catch (_:IllegalArgumentException) {}
        context.getSharedPreferences("model-settings",0).edit().clear().commit()
    }
}
