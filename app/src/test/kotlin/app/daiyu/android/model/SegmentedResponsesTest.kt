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
        val conn=Connection();var connections=0
        val session=ResponsesModel(config) {connections++;conn}.open(history)
        assertEquals(0,connections)
        assertEquals(0,conn.request.size())
        session.next(emptyList()) {}
        assertEquals(1,connections)
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
    @Test fun messagesProtocolInFlatOrSegmentedHistoryRejectsBeforeConnection() {
        var connections=0
        val model=ResponsesModel(config) {connections++;Connection()}
        val o=owner()
        for(raw in listOf(
            """{"role":"assistant","content":[{"type":"text","text":"saved"}]}""",
            """{"role":"assistant","content":[{"type":"tool_use","id":"call","name":"lookup","input":{}}]}""",
            """{"role":"user","content":[{"type":"tool_result","tool_use_id":"call","content":"done"}]}""")) {
            val display=listOf(MessageBlock.Text("visible","must not replace raw"))
            for(history in listOf(
                o.copy(blocks=display,protocolItems=listOf(raw)),
                o.copy(historySegments=listOf(MessageHistorySegment(o.id,"assistant",display,listOf(raw)))))) {
                try {model.open(listOf(history));fail("Messages raw accepted by Responses")}
                catch (_:IllegalArgumentException) {}
            }
        }
        assertEquals(0,connections)
    }

    @Test fun interruptedFlatOnlyProjectsNonBlankTextAndDoesNotMutateSource() {
        val blocks=listOf(MessageBlock.Text("first"," first "),MessageBlock.Text("blank"," \t\n"),
            MessageBlock.Tool(invocation,"done"),MessageBlock.Text("second","second"))
        val reasoning="""{"type":"reasoning","id":"rs","encrypted_content":"reasoning-secret"}"""
        for(raw in listOf(emptyList<String>(),listOf(reasoning,message("raw-only-secret"),call(),output()))) {
            val source=owner().copy(status=RunStatus.INTERRUPTED,error="kept",blocks=blocks,protocolItems=raw)
            val snapshot=source.copy(blocks=source.blocks.toList(),protocolItems=source.protocolItems.toList())
            val items=input(listOf(source))
            assertEquals(1,items.size)
            assertEquals(setOf("role","content"),items.single().keySet())
            assertEquals("assistant",items.single().getString("role"))
            assertEquals(" first \nsecond\n\n[回复已中断]",items.single().getString("content"))
            assertEquals(snapshot,source)
            assertEquals(RunStatus.INTERRUPTED,source.status)
            assertSame(blocks,source.blocks)
            assertSame(raw,source.protocolItems)
        }
    }
    @Test fun interruptedWithoutVisibleTextNeverExportsRawOrTools() {
        val raw=listOf(message("raw-only-secret"),call(),output())
        for(blocks in listOf(emptyList<MessageBlock>(),listOf(MessageBlock.Text("blank"," \t\n")),
            listOf(MessageBlock.Tool(invocation,"done")),listOf(MessageBlock.Tool(invocation)))) {
            for(items in listOf(emptyList<String>(),raw)) {
                assertTrue(input(listOf(owner().copy(status=RunStatus.INTERRUPTED,blocks=blocks,protocolItems=items))).isEmpty())
            }
        }
    }
    @Test fun interruptedSegmentsKeepOrderDeduplicateSupplementAndIgnoreOwnerProjection() {
        val o=owner()
        val user=o.copy(id=MessageId("u"),role="user",blocks=listOf(MessageBlock.Text("u","extra")))
        val segments=listOf(
            MessageHistorySegment(o.id,"assistant",listOf(MessageBlock.Text("first","first")),listOf(call(),output())),
            MessageHistorySegment(user.id,"user",user.blocks),
            MessageHistorySegment(o.id,"assistant",listOf(MessageBlock.Tool(invocation,"done"),MessageBlock.Text("second","second")),listOf(call(),output())))
        val source=o.copy(status=RunStatus.INTERRUPTED,error="kept",blocks=listOf(MessageBlock.Text("display","ignored")),protocolItems=listOf("{"),historySegments=segments)
        val history=listOf(user,source)
        val snapshot=history.map {row -> row.copy(blocks=row.blocks.toList(),protocolItems=row.protocolItems.toList(),
            historySegments=row.historySegments.map {it.copy(blocks=it.blocks.toList(),protocolItems=it.protocolItems.toList())})}
        val items=input(history)
        assertEquals(listOf("assistant","user","assistant"),items.map {it.getString("role")})
        assertEquals(listOf("first\n\n[回复已中断]","extra","second\n\n[回复已中断]"),items.map {it.getString("content")})
        assertEquals(snapshot,history)
        assertSame(user,history[0]);assertSame(source,history[1]);assertSame(segments,source.historySegments)
        assertEquals(RunStatus.INTERRUPTED,source.status)
    }
    @Test fun interruptedAndCompletedReuseRawValidationBeforeConnection() {
        var connections=0
        val model=ResponsesModel(config) {connections++;Connection()}
        val text=listOf<MessageBlock>(MessageBlock.Text("blank"," \t\n"))
        val tool=text+MessageBlock.Tool(invocation,"done")
        val cases=listOf(text to listOf("{"),text to listOf("""{"type":"function_call"}"""),
            text to listOf("""{"type":"function_call_output","call_id":"call"}"""),
            text to listOf("""{"role":"assistant","content":[{"type":"text","text":"saved"}]}"""),
            tool to listOf(message("no-call")),(text+MessageBlock.Tool(invocation,"wrong")) to listOf(call(),output()))
        for(status in listOf(RunStatus.COMPLETED,RunStatus.INTERRUPTED)) {
            for((blocks,raw) in cases) {
                val source=owner().copy(status=status,blocks=blocks,protocolItems=raw)
                val histories=listOf(source,source.copy(blocks=emptyList(),protocolItems=emptyList(),
                    historySegments=listOf(MessageHistorySegment(source.id,"assistant",blocks,raw))))
                for(history in histories) {
                    val failure=runCatching {model.open(listOf(history))}.exceptionOrNull()
                    assertTrue("invalid raw accepted: $status",failure is IllegalArgumentException || failure is org.json.JSONException)
                }
            }
        }
        assertEquals(0,connections)
    }
    @Test fun interruptedProjectionIsAssistantOnlyAndDoesNotChangeOtherStatuses() {
        val text=listOf(MessageBlock.Text("visible","user-or-partial"))
        val ignored=listOf(RunStatus.RUNNING,RunStatus.STOPPED,RunStatus.FAILED).map {owner().copy(status=it,blocks=text,protocolItems=listOf("{"))}+
            owner().copy(role="system",status=RunStatus.INTERRUPTED,blocks=text,protocolItems=listOf("{"))
        assertTrue(input(ignored).isEmpty())
        val user=owner().copy(role="user",status=RunStatus.INTERRUPTED,blocks=text,protocolItems=listOf("{"))
        val items=input(listOf(user))
        assertEquals(1,items.size);assertEquals("user",items.single().getString("role"))
        assertEquals("user-or-partial",items.single().getString("content"))
    }
    @Test fun completedRawKeepsExistingPassthroughAndMissingOutputBehavior() {
        val opaque="""{"type":"fixture_extension","payload":"unchanged"}"""
        val raw=listOf(opaque,call())
        val source=owner().copy(blocks=listOf(MessageBlock.Tool(invocation,"done"),MessageBlock.Text("visible","display ignored")),protocolItems=raw)
        val items=input(listOf(source))
        assertEquals(listOf("fixture_extension","function_call","function_call_output"),items.map {it.getString("type")})
        assertEquals("unchanged",items.first().getString("payload"));assertEquals("done",items.last().getString("output"))
        assertEquals(raw,source.protocolItems)
        val projected=input(listOf(source.copy(status=RunStatus.INTERRUPTED)))
        assertEquals(1,projected.size);assertEquals("display ignored\n\n[回复已中断]",projected.single().getString("content"))
    }
    @Test fun interruptedOpenDoesNotPostOrResumeAutomatically() {
        var connections=0;val conn=Connection()
        val source=owner().copy(status=RunStatus.INTERRUPTED,blocks=listOf(MessageBlock.Text("partial","partial")))
        val session=ResponsesModel(config) {connections++;conn}.open(listOf(source))
        assertEquals(0,connections);assertEquals(0,conn.request.size())
        session.next(emptyList()) {}
        assertEquals(1,connections)
        val failure=runCatching {session.next(emptyList()) {}}.exceptionOrNull()
        assertTrue(failure is IllegalStateException);assertEquals(1,connections);assertEquals(RunStatus.INTERRUPTED,source.status)
    }

}
