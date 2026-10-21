package app.daiyu.android

import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.model.ResponsesModel
import app.daiyu.android.model.ResponsesStream
import app.daiyu.android.storage.ModelSettings
import app.daiyu.android.platform.AndroidReadOnlyTools
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
    private fun reasoning(vararg summaries:String, id:String="rs", encrypted:String="synthetic-encrypted-content")=
        event("reasoning","id" to id,"encrypted_content" to encrypted,
            "summary" to JSONArray(summaries.map {event("summary_text","text" to it)}))
    private fun reasoningDelta(index:Int,text:String,id:String="rs",sequence:Int=1)=
        event("response.reasoning_summary_text.delta","item_id" to id,"output_index" to 0,
            "summary_index" to index,"sequence_number" to sequence,"delta" to text)
    private fun reasoningDone(index:Int,text:String,id:String="rs",sequence:Int=2)=
        event("response.reasoning_summary_text.done","item_id" to id,"output_index" to 0,
            "summary_index" to index,"sequence_number" to sequence,"text" to text)
    private fun reasoningPart(index:Int,text:String,status:String="completed",sequence:Int=3)=
        event("response.reasoning_summary_part.done","item_id" to "rs","output_index" to 0,
            "summary_index" to index,"sequence_number" to sequence,"status" to status,
            "part" to event("summary_text","text" to text))
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
    fun testDeviceStatusCatalogSchemaAndPersistedContinuation() {
        for(battery in listOf(false,true)) for(device in listOf(false,true)) {
            val config=settings.copy(batteryEnabled=battery,deviceStatusEnabled=device)
            val connection=Connection(frames(completed(message("answer"))))
            ResponsesModel(config) {connection}.open(emptyList()).next(emptyList()) {}
            val request=connection.json()
            val names=if(request.has("tools")) (0 until request.getJSONArray("tools").length()).map {
                val tool=request.getJSONArray("tools").getJSONObject(it)
                assertFalse(tool.getBoolean("strict"));val parameters=tool.getJSONObject("parameters")
                assertFalse(parameters.getBoolean("additionalProperties"));assertEquals(0,parameters.getJSONObject("properties").length())
                tool.getString("name")
            }.toSet() else emptySet()
            assertEquals(config.enabledTools().map {it.toolName}.toSet(),names)
        }
        val config=settings.copy(batteryEnabled=false,deviceStatusEnabled=true)
        val item=call().put("name","device_status")
        val first=Connection(frames(event("response.output_item.done","item" to item),completed(item)));val second=Connection(frames(completed(message("设备信息已读取"))))
        var opened=0;val connections=listOf(first,second)
        val context=testContext(instrumentation.targetContext);context.deleteDatabase("conversations.db")
        var db=app.daiyu.android.storage.ConversationDatabase(context)
        try {
            val c=db.create("default");val initial=initial().copy(conversationId=c.id)
            val user=initial.copy(id=MessageId("u-device"),role="user",status=RunStatus.COMPLETED,blocks=listOf(MessageBlock.Text("u","设备状态")))
            db.send(user,initial)
            val model=ResponsesModel(config) {connections[opened++]}
            val result=ChatRunner(model,db,AndroidReadOnlyTools(instrumentation.targetContext,config.enabledTools()),{}).run(initial,listOf(user))
            assertEquals(RunStatus.COMPLETED,result.status);assertEquals(2,opened)
            val block=result.blocks.filterIsInstance<MessageBlock.Tool>().single()
            assertTrue(JSONObject(block.result!!).getBoolean("ok"))
            val sent=second.json().getJSONArray("input")
            val output=(0 until sent.length()).map {sent.getJSONObject(it)}.single {it.optString("type")=="function_call_output"}
            assertEquals(block.invocation.id.value,output.getString("call_id"));assertEquals(block.result,output.getString("output"))
            db.close();db=app.daiyu.android.storage.ConversationDatabase(context);db.recover()
            assertEquals(result,db.messages(c.id).last().second);assertEquals(2,opened)
        } finally {db.close()}
    }
    fun testConfirmedDisabledOrInvalidDeviceStatusReturnsSafeResult() {
        for((enabled,arguments,expected) in listOf(
            Triple(false,"{}","capability_disabled"),Triple(true,"{} trailing","invalid_arguments"))) {
            val config=settings.copy(batteryEnabled=false,deviceStatusEnabled=enabled)
            val item=call().put("name","device_status").put("arguments",arguments)
            val connections=listOf(Connection(frames(event("response.output_item.done","item" to item),completed(item))),Connection(frames(completed(message("拒绝读取")))))
            var opened=0
            val context=object:android.content.ContextWrapper(instrumentation.targetContext) {
                override fun getSystemService(name:String):Any?=error("must not read disabled or invalid tool")
                override fun registerReceiver(receiver:android.content.BroadcastReceiver?,filter:android.content.IntentFilter?):android.content.Intent?=error("must not read battery")
                override fun getFilesDir():java.io.File=error("must not read storage")
            }
            val result=ChatRunner(ResponsesModel(config) {connections[opened++]},object:ConversationStore {override fun save(message:Message) {}},
                AndroidReadOnlyTools(context,config.enabledTools()),{}).run(initial(),emptyList())
            assertEquals(RunStatus.COMPLETED,result.status)
            val output=result.blocks.filterIsInstance<MessageBlock.Tool>().single().result!!
            assertEquals(expected,JSONObject(output).getString("error"));assertEquals(2,opened)
        }
    }
    fun testIncompleteDeviceStatusNeverExecutes() {
        val config=settings.copy(batteryEnabled=false,deviceStatusEnabled=true)
        val connection=Connection(frames(event("response.output_item.done","item" to call().put("name","device_status"))))
        var executions=0
        val result=ChatRunner(ResponsesModel(config) {connection},object:ConversationStore {override fun save(message:Message) {}},
            BatteryTool {executions++;"{}"},{}).run(initial(),emptyList())
        assertEquals(RunStatus.FAILED,result.status);assertEquals(0,executions)
    }
    fun testBaseAddressAndCustomPathUseOneResolvedRequest() {
        for((config,expected) in listOf(
            settings.copy(endpoint="https://example.invalid") to "https://example.invalid/v1/responses",
            settings.copy(endpoint="https://example.invalid/v1/") to "https://example.invalid/v1/responses",
            settings.copy(endpoint="https://example.invalid/gateway",responsesPath="/responses") to "https://example.invalid/gateway/responses")) {
            var opened=0
            val connection=Connection(frames(completed(message("answer"))))
            val model=ResponsesModel(config) {url -> opened++;assertEquals(expected,url.toString());connection}
            val result=ChatRunner(model,object:ConversationStore {override fun save(message:Message) {}},BatteryTool {error("unused")},{}).run(initial(),emptyList())
            assertEquals(RunStatus.COMPLETED,result.status);assertEquals(1,opened);assertTrue(connection.disconnected)
        }
    }
    fun testHttpFailureCategoriesAndMimeAreSafe() {
        val expected=mapOf(401 to ModelFailure.AUTHENTICATION,403 to ModelFailure.PERMISSION,
            404 to ModelFailure.ENDPOINT,405 to ModelFailure.ENDPOINT,302 to ModelFailure.REDIRECT,
            429 to ModelFailure.RATE_LIMIT,500 to ModelFailure.SERVER,400 to ModelFailure.REQUEST)
        for((code,failure) in expected) {
            val result=run(listOf(Connection("synthetic-secret",code)))
            assertEquals(RunStatus.FAILED,result.status);assertEquals(failure.userMessage,result.error)
            assertFalse(result.error!!.contains("synthetic-secret"))
        }
        assertEquals(RunStatus.COMPLETED,run(listOf(Connection(frames(completed(message("answer"))),200,"Text/Event-Stream; charset=UTF-8"))).status)
        assertEquals(ModelFailure.PROTOCOL.userMessage,run(listOf(Connection("{}",200,"text/event-streamXYZ"))).error)
        assertEquals(ModelFailure.PROTOCOL.userMessage,run(listOf(Connection(frames(JSONObject().put("choices",JSONArray()))))).error)
        assertEquals(ModelFailure.INCOMPLETE.userMessage,run(listOf(Connection("data: [DONE]\n\n"))).error)
    }
    fun testTransportFailuresAreClassifiedWithoutRetriesOrLeaks() {
        for ((exception, expected) in listOf(
            java.net.SocketTimeoutException("synthetic-secret") to ModelFailure.TIMEOUT,
            javax.net.ssl.SSLException("synthetic-secret") to ModelFailure.TLS,
            java.io.IOException("synthetic-secret") to ModelFailure.NETWORK)) {
            var opened=0
            var disconnected=false
            val connection=object:HttpsURLConnection(URL(settings.endpoint)) {
                override fun connect() {}
                override fun disconnect() {disconnected=true}
                override fun usingProxy()=false
                override fun getCipherSuite()="fixture"
                override fun getLocalCertificates():Array<Certificate>?=null
                override fun getServerCertificates():Array<Certificate> = emptyArray()
                override fun getOutputStream():java.io.OutputStream {throw exception}
            }
            val result=ChatRunner(ResponsesModel(settings) {opened++;connection},
                object:ConversationStore {override fun save(message:Message) {}},BatteryTool {error("unused")},{})
                .run(initial(),emptyList())
            assertEquals(RunStatus.FAILED,result.status);assertEquals(expected.userMessage,result.error)
            assertEquals(1,opened);assertTrue(disconnected);assertFalse(result.error!!.contains("synthetic-secret"))
        }
    }
    fun testConnectionFactoryFailuresAndMalformedEventsAreClassified() {
        for((exception,expected) in listOf(
            java.net.SocketTimeoutException("synthetic-secret") to ModelFailure.TIMEOUT,
            javax.net.ssl.SSLException("synthetic-secret") to ModelFailure.TLS,
            java.io.IOException("synthetic-secret") to ModelFailure.NETWORK)) {
            var opened=0
            val result=ChatRunner(ResponsesModel(settings) {opened++;throw exception},
                object:ConversationStore {override fun save(message:Message) {}},BatteryTool {error("unused")},{})
                .run(initial(),emptyList())
            assertEquals(expected.userMessage,result.error);assertEquals(1,opened)
        }
        for(event in listOf(
            event("response.output_text.delta","content_index" to 0,"delta" to "x"),
            delta(-1,"x"),delta(0,"x").put("sequence_number","bad"),
            event("response.completed","response" to JSONObject().put("status","completed")))) {
            assertEquals(ModelFailure.PROTOCOL.userMessage,run(listOf(Connection(frames(event)))).error)
        }
        assertEquals(ModelFailure.INCOMPLETE.userMessage,run(listOf(Connection("data: ${completed()}"))).error)
    }
    fun testSinkIoFailuresKeepTheirIdentity() {
        for(exception in listOf(java.io.IOException("sink"),java.net.SocketTimeoutException("sink"),javax.net.ssl.SSLException("sink"))) {
            for(protocol in listOf(false,true)) {
                val connection=Connection(frames(delta(0,"text"),completed(message("text"))))
                val session=ResponsesModel(settings) {connection}.open(emptyList())
                try {
                    session.next(emptyList()) {event ->
                        if(event !is ModelEvent.InputEstimate && (event is ModelEvent.ProtocolOutput)==protocol) throw exception
                    }
                    fail("sink must fail")
                } catch(failure:java.io.IOException) {assertSame(exception,failure)}
                assertTrue(connection.disconnected)
            }
        }
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
    fun testReasoningLifecycleSnapshotsAreIdempotentAndNeverBody() {
        val text="OK.\n".repeat(800)
        val item=reasoning(text)
        val connection=Connection(frames(reasoningDelta(0,text),reasoningDone(0,text),reasoningPart(0,text),
            event("response.output_item.done","output_index" to 0,"sequence_number" to 4,"item" to item),
            completed(item,message("answer")).put("sequence_number",5)))
        val result=run(listOf(connection))
        assertEquals(RunStatus.COMPLETED,result.status)
        assertEquals("answer",(result.blocks.single() as MessageBlock.Text).text)
        assertEquals("synthetic-encrypted-content",JSONObject(result.protocolItems.first()).getString("encrypted_content"))
        assertFalse(connection.json().has("reasoning"))
    }
    fun testRepetitiveReasoningStopsBeforeToolsAndKeepsBody() {
        var executed=0
        val body=frames(delta(0,"accepted"),event("response.output_item.done","item" to call()))+
            (1..20).joinToString("") {frames(reasoningDelta(0,"OK.\n".repeat(128),sequence=it))}+
            frames(completed(reasoning("ignored"),call(),message("ignored")))
        val result=run(listOf(Connection(body))) {executed++;"{}"}
        assertEquals(RunStatus.FAILED,result.status)
        assertTrue(result.error!!.contains("思考摘要"))
        assertEquals(0,executed)
        assertEquals("accepted",(result.blocks.first() as MessageBlock.Text).text)
    }
    fun testDonePartItemAndCompletedOnlyReasoningAreDetected() {
        val text="OK.\n".repeat(2000)
        val bodies=listOf(
            frames(reasoningDone(0,text),completed()),
            frames(reasoningPart(0,text),completed()),
            frames(event("response.output_item.done","item" to reasoning(text)),completed()),
            frames(completed(reasoning(text))))
        for(body in bodies) {
            val result=run(listOf(Connection(body)))
            assertEquals(RunStatus.FAILED,result.status)
            assertTrue(result.error!!.contains("思考摘要"))
            assertTrue(result.blocks.isEmpty())
        }
    }
    fun testReasoningIndicesCorrectionsAndDuplicateSequenceDoNotDoubleCount() {
        val a="Draft.\n".repeat(200)
        val b="Final.\n".repeat(200)
        val repeated=reasoningDelta(1,b,sequence=1)
        val item=reasoning(b,b)
        val result=run(listOf(Connection(frames(repeated,repeated,reasoningDelta(0,a,sequence=2),
            reasoningDone(0,b,sequence=3),reasoningDone(1,b,sequence=4),
            event("response.output_item.done","item" to item,"sequence_number" to 5),
            completed(item,message("answer")).put("sequence_number",6)))))
        assertEquals(RunStatus.COMPLETED,result.status)
        assertEquals("answer",(result.blocks.single() as MessageBlock.Text).text)
    }
    fun testReasoningRepetitionAcrossIndicesAndItemsIsNotReset() {
        val text="OK.\n".repeat(800)
        val parts=run(listOf(Connection(frames(reasoningDelta(0,text,sequence=1),
            reasoningDelta(1,text,sequence=2),completed(reasoning(text,text))))))
        assertEquals(RunStatus.FAILED,parts.status)
        val items=run(listOf(Connection(frames(reasoningDelta(0,text,id="first",sequence=1),
            reasoningDelta(0,text,id="second",sequence=2),completed()))))
        assertEquals(RunStatus.FAILED,items.status)
    }
    fun testEmptyOrMissingFinalSummaryDoesNotResetObservedWindow() {
        val text="OK.\n".repeat(800)
        val absent=reasoning().also {it.remove("summary")}
        for(item in listOf(reasoning(),absent,reasoning().put("summary",JSONObject.NULL))) {
            val result=run(listOf(Connection(frames(reasoningDelta(0,text,sequence=1),
                event("response.output_item.done","item" to item,"sequence_number" to 2),
                reasoningDelta(0,text,sequence=3),completed(item)))))
            assertEquals(RunStatus.FAILED,result.status)
            assertTrue(result.error!!.contains("思考摘要"))
        }
        val missingAtTerminal=run(listOf(Connection(frames(reasoningDelta(0,text),completed(message("answer"))))))
        assertEquals(RunStatus.COMPLETED,missingAtTerminal.status)
    }
    fun testEncryptedReasoningAndToolDataRemainExcludedAndReplayUnchanged() {
        val encrypted="OK.\n".repeat(2000)
        val item=reasoning(id="rs",encrypted=encrypted)
        val invocation=call().put("arguments",JSONObject().put("ignored",encrypted).toString())
        val first=Connection(frames(event("response.output_item.done","item" to invocation),completed(item,invocation)))
        val second=Connection(frames(reasoningDelta(0,"observable summary"),completed(reasoning("observable summary"),message("answer"))))
        val result=run(listOf(first,second)) {encrypted}
        assertEquals(RunStatus.COMPLETED,result.status)
        assertEquals(encrypted,second.json().getJSONArray("input").getJSONObject(0).getString("encrypted_content"))
        assertEquals(encrypted,second.json().getJSONArray("input").getJSONObject(2).getString("output"))
        assertEquals(invocation.getString("arguments"),second.json().getJSONArray("input").getJSONObject(1).getString("arguments"))
        assertEquals(1,result.blocks.filterIsInstance<MessageBlock.Text>().size)
        val third=Connection(frames(completed(message("later"))))
        val session=ResponsesModel(settings) {third}.open(listOf(result))
        session.next(emptyList()) {};session.cancel()
        assertEquals(encrypted,third.json().getJSONArray("input").getJSONObject(0).getString("encrypted_content"))
        assertEquals(invocation.getString("arguments"),third.json().getJSONArray("input").getJSONObject(1).getString("arguments"))
    }
    fun testIncompleteSummaryDoesNotAuthorizeToolsAndMalformedIndexFails() {
        var executed=0
        val result=run(listOf(Connection(frames(event("response.output_item.done","item" to call()),
            reasoningPart(0,"partial",status="incomplete"),event("response.incomplete"))))) {executed++;"{}"}
        assertEquals(RunStatus.FAILED,result.status);assertEquals(0,executed)
        assertNull((result.blocks.single() as MessageBlock.Tool).result)
        assertEquals(RunStatus.FAILED,run(listOf(Connection(frames(reasoningDelta(-1,"bad"),completed())))).status)
        val malformed=event("response.reasoning_summary_part.done","item_id" to "rs","summary_index" to 0,
            "part" to event("summary_text"))
        assertEquals(RunStatus.FAILED,run(listOf(Connection(frames(malformed,completed())))).status)
    }
    fun testVisibleProgressResetsSummaryButRepeatedFinalDoesNot() {
        val text="OK.\n".repeat(800)
        val accepted=run(listOf(Connection(frames(reasoningDelta(0,text),delta(0,"answer"),
            reasoningDone(0,text,sequence=2),reasoningDelta(0,text,sequence=3),
            completed(reasoning(text+text),message("answer"))))))
        assertEquals(RunStatus.COMPLETED,accepted.status)
        val rejected=run(listOf(Connection(frames(delta(0,"answer"),reasoningDelta(0,text),
            done(0,"answer"),reasoningDelta(0,text,sequence=2),completed()))))
        assertEquals(RunStatus.FAILED,rejected.status)
    }
    fun testSummarySnapshotOrderAndItemIsolationDirectly() {
        val snapshots=mutableListOf<ModelEvent.ReasoningFinal>()
        val body=frames(reasoningDelta(1,"second",sequence=1),reasoningDelta(0,"first",sequence=2),
            reasoningDelta(0,"other",id="other-item",sequence=3),completed())
        ResponsesStream.read(body.byteInputStream().bufferedReader(),{false}) {
            assertFalse(it is ModelEvent.TextDelta || it is ModelEvent.TextFinal)
            if(it is ModelEvent.ReasoningFinal) snapshots.add(it)
        }
        assertEquals(listOf("second","firstsecond","other"),snapshots.map {it.text})
        assertEquals(listOf("rs","rs","other-item"),snapshots.map {it.itemId})
    }
    fun testSyntheticAccountRequestsRemainIsolated() {
        val a=ModelSettings("https://account-a.example.invalid/v1/responses","fixture-a","synthetic-key-a",true)
        val b=ModelSettings("https://account-b.example.invalid/v1/responses","fixture-b","synthetic-key-b",false)
        val firstA=Connection(frames(completed(message("a-only"))))
        val firstB=Connection(frames(completed(message("b-only"))))
        val nextA=Connection(frames(completed(message("a-next"))))
        val urls=mutableListOf<String>();var openedA=0;var openedB=0
        val modelA=ResponsesModel(a) {url ->
            urls.add(url.toExternalForm())
            when(openedA++) {0->firstA;1->nextA;else->throw AssertionError("unexpected A request")}
        }
        val sessionA=modelA.open(emptyList())
        val sessionB=ResponsesModel(b) {url ->urls.add(url.toExternalForm());assertEquals(0,openedB++);firstB}.open(emptyList())
        var nextSessionA:ModelSession?=null
        try {
            sessionA.next(emptyList()) {};sessionB.next(emptyList()) {}
            try {sessionA.next(emptyList()) {};fail("terminal session must not repost")}
            catch(_:IllegalStateException) {}
            assertEquals(1,openedA)
            nextSessionA=modelA.open(listOf(initial().copy(status=RunStatus.COMPLETED,
                blocks=listOf(MessageBlock.Text("answer","a-only")),protocolItems=listOf(message("a-only").toString()))))
            nextSessionA.next(emptyList()) {}
        } finally {sessionA.cancel();sessionB.cancel();nextSessionA?.cancel()}
        assertEquals(listOf(a.endpoint,b.endpoint,a.endpoint),urls)
        assertEquals(2,openedA);assertEquals(1,openedB)
        for((connection,config) in listOf(firstA to a,firstB to b,nextA to a)) {
            assertEquals(config.model,connection.json().getString("model"))
            assertEquals("Bearer ${config.apiKey}",connection.getRequestProperty("Authorization"))
            assertEquals("POST",connection.requestMethod)
            assertEquals("application/json",connection.getRequestProperty("Content-Type"))
            assertEquals("text/event-stream",connection.getRequestProperty("Accept"))
            assertFalse(connection.instanceFollowRedirects);assertTrue(connection.disconnected)
            assertEquals(config.batteryEnabled,connection.json().has("tools"))
        }
        assertEquals(0,firstA.json().getJSONArray("input").length())
        assertEquals(0,firstB.json().getJSONArray("input").length())
        val input=nextA.json().getJSONArray("input");assertEquals(1,input.length())
        assertEquals("a-only",input.getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text"))
    }
    fun testDistinctSameToolCallsKeepExecutionAndOutputOrder() {
        val a=call("call-a").put("id","fc-a");val b=call("call-b").put("id","fc-b")
        val first=Connection(frames(event("response.output_item.done","item" to a),event("response.output_item.done","item" to b),completed(a,b)))
        val second=Connection(frames(completed(message("finished"))))
        val executed=mutableListOf<String>();val outputs=listOf("fixture-a","fixture-b")
        val result=run(listOf(first,second)) {invocation ->
            assertEquals("get_battery_status",invocation.name)
            executed.add(invocation.id.value)
            when(invocation.id.value) {"call-a"->outputs[0];"call-b"->outputs[1];else->throw AssertionError("unexpected call")}
        }
        assertEquals(RunStatus.COMPLETED,result.status);assertEquals(listOf("call-a","call-b"),executed)
        val blocks=result.blocks.filterIsInstance<MessageBlock.Tool>()
        assertEquals(executed,blocks.map {it.invocation.id.value});assertEquals(outputs,blocks.map {it.result})
        val input=second.json().getJSONArray("input");assertEquals(4,input.length())
        for(index in 0..1) {
            val invocation=input.getJSONObject(index);val output=input.getJSONObject(index+2)
            assertEquals("function_call",invocation.getString("type"));assertEquals(executed[index],invocation.getString("call_id"))
            assertEquals("get_battery_status",invocation.getString("name"));assertEquals("{}",invocation.getString("arguments"))
            assertEquals("function_call_output",output.getString("type"));assertEquals(executed[index],output.getString("call_id"))
            assertEquals(outputs[index],output.getString("output"))
        }
    }
    fun testInvalidCompleteCallsReturnPlatformValidationErrors() {
        val backend=app.daiyu.android.platform.AndroidBatteryTool(instrumentation.targetContext,true)
        for(arguments in listOf("{\"extra\":true}","[]","null","\"text\"","0","true","{")) {
            val invocation=call().put("arguments",arguments)
            val first=Connection(frames(event("response.output_item.done","item" to invocation),completed(invocation)))
            val second=Connection(frames(completed(message("invalid request rejected"))))
            var successes=0;var validations=0
            val result=run(listOf(first,second)) {
                validations++
                backend.execute(it).also {output ->if(JSONObject(output).getBoolean("ok")) successes++}
            }
            assertEquals(RunStatus.COMPLETED,result.status);assertEquals(1,validations);assertEquals(0,successes)
            val output=JSONObject((result.blocks.first() as MessageBlock.Tool).result!!)
            assertFalse(output.getBoolean("ok"));assertEquals("invalid_arguments",output.getString("error"))
            val input=second.json().getJSONArray("input");assertEquals(2,input.length())
            val replay=input.getJSONObject(1)
            assertEquals("function_call_output",replay.getString("type"))
            assertEquals("call",replay.getString("call_id"))
            assertEquals((result.blocks.first() as MessageBlock.Tool).result,replay.getString("output"))
            assertFalse(JSONObject(replay.getString("output")).getBoolean("ok"))
            assertEquals("invalid_arguments",JSONObject(replay.getString("output")).getString("error"))
        }
    }

}
