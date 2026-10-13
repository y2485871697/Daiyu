package app.daiyu.android

import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.model.ResponsesModel
import app.daiyu.android.storage.ModelSettings
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

@Suppress("DEPRECATION")
class ResponsesTest:InstrumentationTestCase() {
    private val settings=ModelSettings("https://example.invalid/v1/responses","fixture-model","synthetic-test-key",true)
    private fun initial()=Message(MessageId("a"),ConversationId("c"),RunId("r"),"assistant",emptyList(),RunStatus.RUNNING)
    private fun event(type:String,vararg fields:Pair<String,Any>):JSONObject {
        val result=JSONObject().put("type",type)
        fields.forEach {result.put(it.first,it.second)}
        return result
    }
    private fun frames(vararg events:JSONObject)=events.joinToString("") {"data: $it\n\n"}
    private fun call(id:String="call"):JSONObject=event("function_call","id" to "fc", "call_id" to id,"name" to "get_battery_status","arguments" to "{}","status" to "completed")
    private fun message(text:String)=event("message","id" to "m","role" to "assistant","status" to "completed","content" to JSONArray().put(event("output_text","text" to text,"annotations" to JSONArray())))
    private fun completed(vararg items:JSONObject)=event("response.completed","response" to JSONObject().put("status","completed").put("output",JSONArray(items.toList())))
    private fun delta(index:Int,text:String)=event("response.output_text.delta","item_id" to "m","content_index" to index,"delta" to text)
    private fun done(index:Int,text:String)=event("response.output_text.done","item_id" to "m","content_index" to index,"text" to text)
    private class Connection(body:String,private val code:Int=200,private val mime:String="text/event-stream"):HttpsURLConnection(URL("https://example.invalid/v1/responses")) {
        val request=ByteArrayOutputStream()
        private val response=ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
        var disconnected=false
        override fun connect() {}
        override fun disconnect() {disconnected=true;response.close()}
        override fun usingProxy()=false
        override fun getCipherSuite()="fixture"
        override fun getLocalCertificates():Array<Certificate>?=null
        override fun getServerCertificates():Array<Certificate> = emptyArray()
        override fun getOutputStream()=request
        override fun getInputStream()=response
        override fun getResponseCode()=code
        override fun getContentType()=mime
        fun json()=JSONObject(request.toString("UTF-8"))
    }
    private fun run(connections:List<Connection>,tool:(ToolInvocation)->String={"{}"}):Message {
        var opened=0
        val model=ResponsesModel(settings) { connections[opened++] }
        val result=ChatRunner(model,object:ConversationStore {override fun save(message:Message) {}},BatteryTool(tool),{}).run(initial(),emptyList())
        assertEquals(connections.size,opened)
        assertTrue(connections.all {it.disconnected})
        return result
    }
    fun testTwoRoundRequestAndEncryptedReasoningReplay() {
        val reasoning=event("reasoning","id" to "reason","encrypted_content" to "synthetic-encrypted-content","summary" to JSONArray())
        val call=call()
        val first=Connection(frames(event("response.output_item.done","item" to call),completed(reasoning,call)))
        val second=Connection(frames(delta(0,"已确认"),completed(message("已确认"))))
        var executed=0
        var opened=0
        var saved:Message?=null
        val model=ResponsesModel(settings) {
            if(opened++==0) first else {
                assertEquals("{\"percent\":77}",(saved!!.blocks.single() as MessageBlock.Tool).result)
                second
            }
        }
        val store=object:ConversationStore {override fun save(message:Message) {saved=message}}
        val result=ChatRunner(model,store,BatteryTool {executed++;"{\"percent\":77}"},{}).run(initial(),emptyList())
        assertEquals(2,opened);assertTrue(first.disconnected && second.disconnected)
        assertEquals(RunStatus.COMPLETED,result.status);assertEquals(1,executed)
        val request=first.json()
        assertFalse(request.getBoolean("store"));assertTrue(request.getBoolean("stream"))
        assertEquals("reasoning.encrypted_content",request.getJSONArray("include").getString(0))
        assertEquals("get_battery_status",request.getJSONArray("tools").getJSONObject(0).getString("name"))
        assertEquals("POST",first.requestMethod);assertFalse(first.instanceFollowRedirects)
        assertEquals(20_000,first.connectTimeout);assertEquals(60_000,first.readTimeout)
        val input=second.json().getJSONArray("input")
        assertEquals(3,input.length());assertEquals("synthetic-encrypted-content",input.getJSONObject(0).getString("encrypted_content"))
        assertEquals("call",input.getJSONObject(2).getString("call_id"))
        assertEquals("{\"percent\":77}",input.getJSONObject(2).getString("output"))
        assertEquals(4,result.protocolItems.size)
        val third=Connection(frames(completed(message("下一轮"))))
        val history=ResponsesModel(settings) { third }.open(listOf(result))
        history.next(emptyList()) {}
        assertEquals(result.protocolItems.size,third.json().getJSONArray("input").length())
        assertEquals("function_call_output",third.json().getJSONArray("input").getJSONObject(2).getString("type"))
        history.cancel()
    }
    fun testMultipleContentPartsSurviveIncompleteResponse() {
        val result=run(listOf(Connection(frames(delta(0,"前"),done(0,"前文"),delta(1,"后"),done(1,"后文"),event("response.incomplete")))))
        assertEquals(RunStatus.FAILED,result.status)
        assertEquals("前文后文",(result.blocks.single() as MessageBlock.Text).text)
    }
    fun testDuplicateFinalCallNeverExecutes() {
        val call=call();var executed=0
        val result=run(listOf(Connection(frames(event("response.output_item.done","item" to call),completed(call,call))))) {executed++;"{}"}
        assertEquals(RunStatus.FAILED,result.status);assertEquals(0,executed)
    }
    fun testIncompleteCallNeverExecutes() {
        var executed=0
        val result=run(listOf(Connection(frames(event("response.output_item.done","item" to call()),event("response.incomplete"))))) {executed++;"{}"}
        assertEquals(RunStatus.FAILED,result.status);assertEquals(0,executed)
        assertNull((result.blocks.single() as MessageBlock.Tool).result)
    }
    fun testFinalCallMustBeCompletedAndMatch() {
        val changed=call().put("status","in_progress")
        var executed=0
        val result=run(listOf(Connection(frames(event("response.output_item.done","item" to call()),completed(changed))))) {executed++;"{}"}
        assertEquals(RunStatus.FAILED,result.status);assertEquals(0,executed)
        val mismatch=call().put("arguments","{\"extra\":true}")
        val mismatched=run(listOf(Connection(frames(event("response.output_item.done","item" to call()),completed(mismatch))))) {executed++;"{}"}
        assertEquals(RunStatus.FAILED,mismatched.status);assertEquals(0,executed)
    }
    fun testRefusalAndDuplicateSequence() {
        val delta=event("response.refusal.delta","item_id" to "m","content_index" to 0,"delta" to "拒绝","sequence_number" to 1)
        val refusal=message("").put("content",JSONArray().put(event("refusal","refusal" to "拒绝")))
        val result=run(listOf(Connection(frames(delta,delta,event("response.refusal.done","item_id" to "m","content_index" to 0,"refusal" to "拒绝"),completed(refusal)))))
        assertEquals(RunStatus.COMPLETED,result.status);assertEquals("拒绝",(result.blocks.single() as MessageBlock.Text).text)
    }
    fun testStopClosesConnectionAndKeepsReceivedText() {
        val connection=Connection(frames(delta(0,"前文"),done(0,"前文"),delta(1,"后文"),completed(message("不接纳"))))
        lateinit var runner:ChatRunner
        runner=ChatRunner(ResponsesModel(settings) {connection},object:ConversationStore {override fun save(message:Message) {}},BatteryTool {"{}"}) {
            if(it.status==RunStatus.RUNNING && (it.blocks.lastOrNull() as? MessageBlock.Text)?.text=="前文后文") runner.stop()
        }
        val result=runner.run(initial(),emptyList())
        assertEquals(RunStatus.STOPPED,result.status)
        assertEquals("前文后文",(result.blocks.single() as MessageBlock.Text).text);assertTrue(connection.disconnected)
    }
    fun testHttpFailureAndWrongContentTypeDoNotRetry() {
        assertEquals(RunStatus.FAILED,run(listOf(Connection("",401))).status)
        assertEquals(RunStatus.FAILED,run(listOf(Connection("{}",200,"application/json"))).status)
    }
    fun testRepetitiveStreamAbortsBeforePendingToolExecution() {
        var executed=0
        val body=frames(delta(0,"accepted"),event("response.output_item.done","item" to call()))+
            (1..20).joinToString("") {frames(delta(0,"OK.\n".repeat(128)))}+completed(call(),message("ignored")).let {frames(it)}
        val result=run(listOf(Connection(body))) {executed++;"{}"}
        assertEquals(RunStatus.FAILED,result.status)
        assertTrue(result.error!!.contains("高度重复"))
        assertEquals(0,executed)
        assertTrue((result.blocks.first() as MessageBlock.Text).text.startsWith("accepted"))
    }
    fun testDoneAndCompletedSnapshotsDoNotDoubleCountRepeatedShortAnswer() {
        val text="OK.\n".repeat(800)
        val result=run(listOf(Connection(frames(delta(0,text),done(0,text),completed(message(text))))))
        assertEquals(RunStatus.COMPLETED,result.status)
        assertEquals(text,(result.blocks.single() as MessageBlock.Text).text)
    }
    fun testCompletedOnlyRepetitiveAnswerFails() {
        val result=run(listOf(Connection(frames(completed(message("OK.\n".repeat(2000)))))))
        assertEquals(RunStatus.FAILED,result.status)
        assertTrue(result.error!!.contains("高度重复"))
    }
    fun testUnterminatedEventAndMissingTerminalFail() {
        assertEquals(RunStatus.FAILED,run(listOf(Connection("data: ${completed()}"))).status)
        val result=run(listOf(Connection(frames(delta(0,"保留"))+"data: [DONE]\n\n")))
        assertEquals(RunStatus.FAILED,result.status);assertEquals("保留",(result.blocks.single() as MessageBlock.Text).text)
    }
}
