package app.daiyu.android.model

import app.daiyu.android.domain.*
import app.daiyu.android.storage.ModelSettings
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

class SegmentedResponsesTest {
    private val config=ModelSettings("https://example.invalid/v1/responses","fixture","synthetic-key",false)
    private fun owner()=Message(MessageId("a"),ConversationId("c"),RunId("r"),"assistant",emptyList(),RunStatus.COMPLETED)
    private val invocation=ToolInvocation(ToolCallId("call"),"lookup","{}")
    private fun call()=JSONObject().put("type","function_call").put("call_id","call").put("name","lookup").put("arguments","{}").toString()
    private fun output()=JSONObject().put("type","function_call_output").put("call_id","call").put("output","done").toString()
    private fun message(text:String)=JSONObject().put("type","message").put("role","assistant").put("content",JSONArray().put(JSONObject().put("type","output_text").put("text",text))).toString()
    private class Connection:HttpsURLConnection(URL("https://example.invalid")) {
        val request=ByteArrayOutputStream()
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy()=false
        override fun getCipherSuite()="fixture"
        override fun getLocalCertificates():Array<Certificate>?=null
        override fun getServerCertificates():Array<Certificate> = emptyArray()
        override fun getOutputStream()=request
        override fun getInputStream()=ByteArrayInputStream("data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}\n\n".toByteArray())
        override fun getResponseCode()=200
        override fun getContentType()="text/event-stream"
    }
    private fun input(history:List<Message>):List<JSONObject> {
        val conn=Connection()
        ResponsesModel(config) {conn}.open(history).next(emptyList()) {}
        val input=JSONObject(conn.request.toString("UTF-8")).getJSONArray("input")
        return (0 until input.length()).map {input.getJSONObject(it)}
    }
    @Test fun expandsAtEncoderOnceAndIgnoresOwnerDisplayProjection() {
        val o=owner();val u=o.copy(id=MessageId("u"),role="user",blocks=listOf(MessageBlock.Text("u","extra")))
        val segmented=o.copy(blocks=listOf(MessageBlock.Text("ignored","display")),protocolItems=listOf(message("ignored")),historySegments=listOf(
            MessageHistorySegment(o.id,"assistant",emptyList(),listOf(message("first"))),
            MessageHistorySegment(u.id,"user",u.blocks),
            MessageHistorySegment(o.id,"assistant",listOf(MessageBlock.Text("last","last")))))
        val items=input(listOf(u,segmented))
        assertEquals(3,items.size)
        assertEquals("first",items[0].getJSONArray("content").getJSONObject(0).getString("text"))
        assertEquals("extra",items[1].getString("content"));assertEquals("last",items[2].getString("content"))
    }
    @Test fun completedToolBeforeReopenAddsMissingOutputExactlyOnce() {
        for(raw in listOf(listOf(call()),listOf(call(),output()))) {
            val o=owner();val block=MessageBlock.Tool(invocation,"done")
            val items=input(listOf(o.copy(historySegments=listOf(MessageHistorySegment(o.id,"assistant",listOf(block),raw)))))
            assertEquals(listOf("function_call","function_call_output"),items.map {it.getString("type")})
            assertEquals("done",items.last().getString("output"))
        }
    }
    @Test fun rawReasoningAndPartialTailKeepOrder() {
        val o=owner();val reasoning="{\"type\":\"reasoning\",\"id\":\"rs\",\"encrypted_content\":\"fixture\"}"
        val items=input(listOf(o.copy(historySegments=listOf(
            MessageHistorySegment(o.id,"assistant",emptyList(),listOf(reasoning,message("first"))),
            MessageHistorySegment(o.id,"assistant",listOf(MessageBlock.Text("tail","partial")))))))
        assertEquals("reasoning",items[0].getString("type"));assertEquals("fixture",items[0].getString("encrypted_content"));assertEquals("partial",items[2].getString("content"))
    }
    @Test fun invalidHistoryAndOrphanResultRejectBeforeConnection() {
        var calls=0;val model=ResponsesModel(config) {calls++;Connection()};val o=owner()
        val cases=listOf(
            o.copy(historySegments=listOf(MessageHistorySegment(MessageId("other"),"assistant",emptyList()))),
            o.copy(historySegments=listOf(MessageHistorySegment(o.id,"assistant",listOf(MessageBlock.Tool(invocation,"done")),listOf(message("no-call"))))),
            o.copy(historySegments=listOf(MessageHistorySegment(o.id,"assistant",listOf(MessageBlock.Tool(invocation,"wrong")),listOf(call(),output())))))
        cases.forEach {try {model.open(listOf(it));fail("accepted invalid history")} catch(_:IllegalArgumentException) {}}
        assertEquals(0,calls)
    }
}
