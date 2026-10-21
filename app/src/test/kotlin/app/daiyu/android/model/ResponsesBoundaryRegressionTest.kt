package app.daiyu.android.model

import app.daiyu.android.domain.*
import app.daiyu.android.storage.ModelSettings
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

class ResponsesBoundaryRegressionTest {
    private val config=ModelSettings("https://example.invalid/v1/responses","fixture","synthetic-key",false)
    private class Connection(private val status:Int=200,
        private val stream:String="data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}\n\n"):HttpsURLConnection(URL("https://example.invalid")) {
        val body=ByteArrayOutputStream()
        var disconnected=false
        var inputClosed=false
        override fun connect() {}
        override fun disconnect() {disconnected=true}
        override fun usingProxy()=false
        override fun getCipherSuite()="fixture"
        override fun getLocalCertificates():Array<Certificate>?=null
        override fun getServerCertificates():Array<Certificate> = emptyArray()
        override fun getOutputStream()=body
        override fun getResponseCode()=status
        override fun getContentType()="text/event-stream"
        override fun getInputStream()=object : ByteArrayInputStream(stream.toByteArray(Charsets.UTF_8)) {
            override fun close() {inputClosed=true;super.close()}
        }
    }
    @Test fun initialUtf8BomPreservesCompletedProtocolOutput() {
        val terminal="data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}\n\n"
        fun read(prefix:String):List<ModelEvent> {
            val connection=Connection(stream=prefix+terminal)
            val events=mutableListOf<ModelEvent>()
            ResponsesModel(config) {connection}.open(emptyList()).next(emptyList()) {events.add(it)}
            assertTrue(connection.inputClosed);assertTrue(connection.disconnected)
            return events
        }
        val plain=read("");val bom=read("\uFEFF")
        assertEquals(plain,bom)
        assertEquals(1,bom.filterIsInstance<ModelEvent.ProtocolOutput>().size)
    }
    @Test fun oversizedSseLineClosesTransportAndCannotRepost() {
        val connection=Connection(stream="data: "+"x".repeat(1_048_576))
        var connections=0
        val events=mutableListOf<ModelEvent>()
        val session=ResponsesModel(config) {connections++;connection}.open(emptyList())
        try {session.next(emptyList()) {events.add(it)};fail("oversized line accepted")}
        catch(failure:ModelRequestException) {assertEquals(ModelFailure.PROTOCOL,failure.failure)}
        assertTrue(connection.inputClosed);assertTrue(connection.disconnected)
        assertTrue(events.none {it is ModelEvent.ToolReady || it is ModelEvent.ProtocolOutput})
        try {session.next(emptyList()) {};fail("failed request reposted")}
        catch(_:IllegalStateException) {}
        assertEquals(1,connections)
    }
    @Test fun genericDefinitionsReachRequest() {
        val connection=Connection()
        val session=ResponsesModel(config) {connection}.open(emptyList(),listOf(ToolDefinition("lookup","find","""{"type":"object","properties":{"id":{"type":"string","minLength":1}},"required":["id"],"additionalProperties":false}""")))
        session.next(emptyList()) {}
        val tool=JSONObject(connection.body.toString("UTF-8")).getJSONArray("tools").getJSONObject(0)
        assertEquals("lookup",tool.getString("name"))
        assertEquals(1,tool.getJSONObject("parameters").getJSONObject("properties").getJSONObject("id").getInt("minLength"))
    }
    @Test fun failedRequestCannotBePostedTwice() {
        var connections=0
        val session=ResponsesModel(config) {connections++;Connection(500)}.open(emptyList())
        try {session.next(emptyList()) {};fail("first request must fail")} catch(_:ModelRequestException) {}
        try {session.next(emptyList()) {}} catch(_:Exception) {}
        assertEquals("unknown result must not be reposted",1,connections)
    }
    @Test fun completedSessionCannotBePostedTwice() {
        var connections=0
        val session=ResponsesModel(config) {connections++;Connection()}.open(emptyList())
        session.next(emptyList()) {}
        try {session.next(emptyList()) {}} catch(_:Exception) {}
        assertEquals("terminal session must not repost",1,connections)
    }
    @Test fun orphanToolOutputRejectedBeforeConnection() {
        var connections=0
        val session=ResponsesModel(config) {connections++;Connection()}.open(emptyList())
        try {session.next(listOf(ToolOutput(ToolCallId("orphan"),"result"))) {}} catch(_:Exception) {}
        assertEquals("unmatched result cannot be posted",0,connections)
    }
    @Test fun allPendingResultsMustMatchExactlyBeforeNextConnection() {
        fun item(id:String)=JSONObject().put("type","function_call").put("id","fc-$id")
            .put("call_id",id).put("name","lookup").put("arguments","{}").put("status","completed")
        val first=item("one");val second=item("two")
        val stream=listOf(JSONObject().put("type","response.output_item.done").put("item",first),
            JSONObject().put("type","response.output_item.done").put("item",second),
            JSONObject().put("type","response.completed").put("response",JSONObject().put("status","completed")
                .put("output",org.json.JSONArray().put(first).put(second))))
            .joinToString("") { "data: $it\n\n" }
        val sent=mutableListOf<Connection>()
        val session=ResponsesModel(config) {
            Connection(stream=if(sent.isEmpty()) stream else "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}\n\n").also {sent.add(it)}
        }.open(emptyList())
        session.next(emptyList()) {}
        val one=ToolOutput(ToolCallId("one"),"first");val two=ToolOutput(ToolCallId("two"),"second")
        for (invalid in listOf(listOf(one),listOf(one,one),listOf(one,two,ToolOutput(ToolCallId("extra"),"bad")))) {
            try {session.next(invalid) {};fail("invalid pending accepted")} catch(_:IllegalArgumentException) {}
            assertEquals(1,sent.size)
        }
        session.next(listOf(two,one)) {}
        assertEquals(2,sent.size)
        val input=JSONObject(sent.last().body.toString("UTF-8")).getJSONArray("input")
        val submitted=(0 until input.length()).map {input.getJSONObject(it)}.filter {it.optString("type")=="function_call_output"}
        assertEquals(listOf("two","one"),submitted.map {it.getString("call_id")})
    }
    @Test fun sinkFailureRetainsIdentityAndSealsSession() {
        var connections=0
        val failure=IllegalStateException("synthetic sink failure")
        val session=ResponsesModel(config) {connections++;Connection()}.open(emptyList())
        try {
            session.next(emptyList()) { event ->
                if (event is ModelEvent.ProtocolOutput) throw failure
            }
            fail("sink failure swallowed")
        } catch(caught:Exception) {assertSame(failure,caught)}
        try {session.next(emptyList()) {};fail("failed sink reposted")} catch(_:IllegalStateException) {}
        assertEquals(1,connections)
    }
    @Test fun inputEstimateSinkFailureSealsSessionBeforeConnecting() {
        var connections=0
        val failure=IllegalStateException("synthetic estimate sink failure")
        val session=ResponsesModel(config) {connections++;Connection()}.open(emptyList())
        try {session.next(emptyList()) {throw failure};fail("input estimate sink failure swallowed")}
        catch(caught:Exception) {assertSame(failure,caught)}
        try {session.next(emptyList()) {};fail("failed estimate sink reposted")} catch(_:IllegalStateException) {}
        assertEquals(0,connections)
    }
    @Test fun usageDeliveryFailureSealsSessionWithoutReposting() {
        var connections=0
        val failure=org.json.JSONException("usage sink")
        val stream="data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[],\"usage\":{\"input_tokens\":3}}}\n\n"
        val session=ResponsesModel(config) {connections++;Connection(stream=stream)}.open(emptyList())
        try {session.next(emptyList()) {if(it is ModelEvent.Usage) throw failure};fail("sink failed")}
        catch(actual:Exception) {assertSame(failure,actual)}
        try {session.next(emptyList()) {};fail("failed session reposted")} catch(_:IllegalStateException) {}
        assertEquals(1,connections)
    }
    @Test fun cancellingInsideUsageKeepsObservationButPreventsProtocolOutput() {
        val stream="data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[],\"usage\":{\"input_tokens\":3}}}\n\n"
        val session=ResponsesModel(config) {Connection(stream=stream)}.open(emptyList())
        val events=mutableListOf<ModelEvent>()
        try {session.next(emptyList()) {events.add(it);if(it is ModelEvent.Usage) session.cancel()};fail("cancelled")}
        catch(_:InterruptedException) {}
        assertEquals(1,events.filterIsInstance<ModelEvent.Usage>().size)
        assertTrue(events.none {it is ModelEvent.ProtocolOutput})
    }
    @Test fun nestedOptionalSchemaIsNotReplacedOrForcedStrict() {
        val connection=Connection()
        val schema="""{"type":"object","properties":{"query":{"type":"string","minLength":2,"maxLength":8,"enum":["ab","cd"]},"options":{"type":"object","properties":{"tags":{"type":"array","items":{"type":"string"}}},"additionalProperties":false}},"required":["query"],"additionalProperties":false}"""
        ResponsesModel(config) {connection}.open(emptyList(),listOf(ToolDefinition("lookup","find",schema))).next(emptyList()) {}
        val tool=JSONObject(connection.body.toString("UTF-8")).getJSONArray("tools").getJSONObject(0)
        assertFalse(tool.getBoolean("strict"))
        val parameters=tool.getJSONObject("parameters")
        assertEquals(listOf("query"),(0 until parameters.getJSONArray("required").length()).map {parameters.getJSONArray("required").getString(it)})
        assertEquals(8,parameters.getJSONObject("properties").getJSONObject("query").getInt("maxLength"))
        assertEquals("ab",parameters.getJSONObject("properties").getJSONObject("query").getJSONArray("enum").getString(0))
        assertEquals("array",parameters.getJSONObject("properties").getJSONObject("options").getJSONObject("properties").getJSONObject("tags").getString("type"))
    }

}
