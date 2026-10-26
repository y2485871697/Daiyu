package app.daiyu.android

import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.model.ResponsesModel
import app.daiyu.android.storage.ModelSettings
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.URL
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException

/** Synthetic only: event/request shapes and Runner setup follow ResponsesTest's fixtures. */
@Suppress("DEPRECATION")
class ResponsesCancellationTest:InstrumentationTestCase() {
    private val settings=ModelSettings("https://example.invalid/v1/responses","fixture-model","synthetic-test-key",true)
    private fun initial()=Message(MessageId("a"),ConversationId("c"),RunId("r"),"assistant",emptyList(),RunStatus.RUNNING)
    private fun event(type:String,vararg fields:Pair<String,Any>):JSONObject {
        val result=JSONObject().put("type",type)
        fields.forEach {result.put(it.first,it.second)}
        return result
    }
    private fun frames(vararg events:JSONObject)=events.joinToString("") {"data: $it\n\n"}
    private fun call()=event("function_call","id" to "fc","call_id" to "call","name" to "get_battery_status","arguments" to "{}","status" to "completed")
    private fun ready()=event("response.output_item.done","item" to call())
    private fun delta()=event("response.output_text.delta","item_id" to "m","content_index" to 0,"delta" to "accepted")
    private fun message(id:String="m",text:String="confirmed")=event("message","id" to id,"role" to "assistant","status" to "completed",
        "content" to JSONArray().put(event("output_text","text" to text,"annotations" to JSONArray())))
    private fun completed(vararg items:JSONObject)=event("response.completed","response" to JSONObject().put("status","completed").put("output",JSONArray(items.toList())))
    private fun transportFailures()=listOf(SocketTimeoutException("synthetic"),SSLException("synthetic"),IOException("synthetic"))

    private enum class Block { NONE, HEADERS, READ }
    private class Connection(body:String,private val block:Block=Block.NONE,private val readFailure:IOException?=null,
        private val status:Int=200):HttpsURLConnection(URL("https://example.invalid/v1/responses")) {
        val entered=CountDownLatch(1)
        val released=CountDownLatch(1)
        val disconnected=AtomicBoolean(false)
        val request=ByteArrayOutputStream()
        var cleanupFailure:RuntimeException?=null
        var onDisconnect:(()->Unit)?=null
        private val bytes=body.toByteArray(Charsets.UTF_8)
        private var offset=0
        private fun waitForDisconnect() {
            entered.countDown()
            if(!released.await(5,TimeUnit.SECONDS)) throw IOException("fixture wait expired")
        }
        private val response=object:InputStream() {
            override fun read():Int {
                val one=ByteArray(1)
                return if(read(one,0,1)<0) -1 else one[0].toInt() and 255
            }
            override fun read(target:ByteArray,start:Int,length:Int):Int {
                if(length==0) return 0
                if(offset<bytes.size) {
                    val count=minOf(length,bytes.size-offset)
                    bytes.copyInto(target,start,offset,offset+count);offset+=count
                    return count
                }
                if(block==Block.READ) waitForDisconnect()
                readFailure?.let {throw it}
                return -1
            }
        }
        override fun connect() {}
        override fun disconnect() {
            disconnected.set(true);released.countDown()
            // Clear before invoking: cancelling the session disconnects this connection again.
            val callback=onDisconnect;onDisconnect=null
            callback?.invoke()
            cleanupFailure?.let {throw it}
        }
        override fun usingProxy()=false
        override fun getCipherSuite()="fixture"
        override fun getLocalCertificates():Array<Certificate>?=null
        override fun getServerCertificates():Array<Certificate> = emptyArray()
        override fun getOutputStream()=request
        override fun getInputStream()=response
        override fun getResponseCode():Int {
            if(block==Block.HEADERS) {
                waitForDisconnect()
                readFailure?.let {throw it}
            }
            return status
        }
        override fun getContentType()="text/event-stream"
    }
    private fun model(connection:Connection,opened:AtomicInteger)=ResponsesModel(settings) {
        check(opened.incrementAndGet()==1) {"second connection forbidden"}
        connection
    }
    private class Worker(task:()->Unit) {
        val failure=AtomicReference<Throwable?>(null)
        private val thread=Thread {
            try {task()} catch(caught:Throwable) {failure.set(caught)}
        }.apply {isDaemon=true;start()}
        fun join():Boolean {thread.join(5_000);return !thread.isAlive}
    }
    private fun expectInterrupted(task:()->Unit) {
        try {task();fail("cancelled next must not succeed")} catch(_:InterruptedException) {}
    }
    private fun assertNoConfirmation(events:List<ModelEvent>) {
        assertFalse(events.any {it is ModelEvent.ProtocolOutput})
        assertFalse(events.filterIsInstance<ModelEvent.TextFinal>().any {it.text=="confirmed"})
    }
    fun testCancelBeforeNextDoesNotCreateConnection() {
        val opened=AtomicInteger()
        val connection=Connection(frames(completed(message())))
        val session=model(connection,opened).open(emptyList())
        session.cancel()
        expectInterrupted {session.next(emptyList()) {fail("no events before request")}}
        assertEquals(0,opened.get());assertEquals(0,connection.request.size())
    }
    fun testCancelBlockedHeadersAndReadWinsOverTransportFailure() {
        for(block in listOf(Block.HEADERS,Block.READ)) for(failure in transportFailures()) {
            val connection=Connection(if(block==Block.READ) frames(delta(),ready()) else "",block,failure)
            val opened=AtomicInteger()
            val session=model(connection,opened).open(emptyList())
            val events=mutableListOf<ModelEvent>()
            val worker=Worker {session.next(emptyList()) {events.add(it)}}
            try {
                assertTrue("transport reached blocking point",connection.entered.await(5,TimeUnit.SECONDS))
                session.cancel()
                assertTrue("disconnect must unblock transport",worker.join())
                assertTrue("cancel must not become service failure",worker.failure.get() is InterruptedException)
                assertEquals(1,opened.get());assertTrue(connection.disconnected.get())
                assertFalse(connection.instanceFollowRedirects)
                assertNoConfirmation(events)
                if(block==Block.READ) assertEquals("accepted",events.filterIsInstance<ModelEvent.TextFinal>().single().text)
                expectInterrupted {session.next(emptyList()) {fail("no second request")}}
                assertEquals(1,opened.get())
            } finally {session.cancel();connection.released.countDown();worker.join()}
        }
    }
    fun testCancelWithReturnedErrorHeadersAndEofStillWins() {
        for(block in listOf(Block.HEADERS,Block.READ)) {
            // READ needs successful headers so that EOF, not HTTP status, is exercised.
            val connection=Connection("",block,status=if(block==Block.HEADERS) 503 else 200)
            val session=ResponsesModel(settings) {connection}.open(emptyList())
            val worker=Worker {session.next(emptyList()) {
                assertTrue("only local estimate is allowed", it is ModelEvent.InputEstimate)
            }}
            try {
                assertTrue(connection.entered.await(5,TimeUnit.SECONDS));session.cancel()
                assertTrue(worker.join());assertTrue(worker.failure.get() is InterruptedException)
                assertTrue(connection.disconnected.get())
            } finally {session.cancel();connection.released.countDown();worker.join()}
        }
    }
    fun testCancellationInsideTerminalFramePreventsFurtherFinalsAndProtocolOutput() {
        // Cancellation after the first final in one completed frame must gate the next final.
        for(items in listOf(arrayOf(message()),arrayOf(message(),message("later")))) {
            val connection=Connection(frames(ready(),completed(call(),*items)))
            val session=ResponsesModel(settings) {connection}.open(emptyList())
            val events=mutableListOf<ModelEvent>()
            expectInterrupted {
                session.next(emptyList()) {
                    events.add(it)
                    if(it is ModelEvent.TextFinal) session.cancel()
                }
            }
            assertEquals(1,events.filterIsInstance<ModelEvent.TextFinal>().size)
            assertFalse(events.any {it is ModelEvent.ProtocolOutput})
            assertTrue(connection.disconnected.get())
        }
    }
    fun testCancellationOnToolReadyDoesNotConfirmTools() {
        val connection=Connection(frames(ready(),completed(call(),message())))
        val session=ResponsesModel(settings) {connection}.open(emptyList())
        val events=mutableListOf<ModelEvent>()
        var cancelledAt = -1
        expectInterrupted {session.next(emptyList()) {
            events.add(it)
            if(it is ModelEvent.ToolReady) { cancelledAt = events.size; session.cancel() }
        }}
        assertEquals(1,events.filterIsInstance<ModelEvent.ToolReady>().size)
        assertTrue(cancelledAt >= 0)
        assertEquals(cancelledAt, events.size)
        assertFalse(events.any { it is ModelEvent.ProtocolOutput })
        assertEquals("confirmed", events.filterIsInstance<ModelEvent.TextFinal>().single().text)
        assertTrue(connection.disconnected.get())
    }
    fun testCancellationDuringProtocolOutputDoesNotReturnSuccess() {
        val connection=Connection(frames(completed(message())))
        val session=ResponsesModel(settings) {connection}.open(emptyList())
        expectInterrupted {session.next(emptyList()) {if(it is ModelEvent.ProtocolOutput) session.cancel()}}
        assertTrue(connection.disconnected.get())
    }
    fun testCancellationFromDisconnectDoesNotReturnSuccess() {
        for(cleanupThrows in listOf(false,true)) {
            val connection=Connection(frames(completed(message())))
            val opened=AtomicInteger()
            val session=model(connection,opened).open(emptyList())
            val events=mutableListOf<ModelEvent>()
            var callbacks=0
            connection.onDisconnect={callbacks++;session.cancel()}
            if(cleanupThrows) connection.cleanupFailure=IllegalStateException("cleanup")
            expectInterrupted {session.next(emptyList()) {events.add(it)}}
            // Reaching ProtocolOutput proves cancellation happened in cleanup, not during decoding.
            assertEquals(1,events.filterIsInstance<ModelEvent.ProtocolOutput>().size)
            assertEquals("confirmed",events.filterIsInstance<ModelEvent.TextFinal>().single().text)
            assertEquals(1,callbacks);assertTrue(connection.disconnected.get())
            assertEquals(1,opened.get());assertFalse(connection.instanceFollowRedirects)
            expectInterrupted {session.next(emptyList()) {fail("no second request")}}
            assertEquals(1,opened.get())
        }
    }
    fun testDisconnectCancellationDoesNotReplaceSinkFailure() {
        val failures=transportFailures()+listOf(org.json.JSONException("sink"),IllegalStateException("sink"),ModelRequestException(ModelFailure.PROTOCOL))
        for(failure in failures) for(target in listOf("text","tool","protocol")) {
            val connection=Connection(frames(delta(),ready(),completed(call(),message())))
            val opened=AtomicInteger()
            val session=model(connection,opened).open(emptyList())
            var callbacks=0
            connection.onDisconnect={callbacks++;session.cancel()}
            connection.cleanupFailure=IllegalStateException("cleanup")
            try {
                session.next(emptyList()) {event ->
                    if(when(target) {
                        "text" -> event is ModelEvent.TextFinal
                        "tool" -> event is ModelEvent.ToolReady
                        else -> event is ModelEvent.ProtocolOutput
                    }) throw failure
                }
                fail("sink must fail")
            } catch(caught:Exception) {assertSame(failure,caught)}
            assertEquals(1,callbacks);assertTrue(connection.disconnected.get())
            assertEquals(1,opened.get());assertFalse(connection.instanceFollowRedirects)
            expectInterrupted {session.next(emptyList()) {fail("no second request")}}
            assertEquals(1,opened.get())
        }
    }
    fun testRunnerCancellationPreservesAcceptedTextAndNeverExecutesReadyTool() {
        val connection=Connection(frames(delta(),ready()),Block.READ,IOException("disconnected"))
        val opened=AtomicInteger();val executions=AtomicInteger()
        val result=AtomicReference<Message?>(null)
        val runner=ChatRunner(model(connection,opened),object:ConversationStore {override fun save(message:Message) {}},
            BatteryTool {executions.incrementAndGet();"{}"},{})
        val worker=Worker {result.set(runner.run(initial(),emptyList()))}
        try {
            assertTrue(connection.entered.await(5,TimeUnit.SECONDS));runner.stop()
            assertTrue(worker.join());assertNull(worker.failure.get())
            val final=requireNotNull(result.get())
            assertEquals(RunStatus.STOPPED,final.status);assertNull(final.error)
            assertEquals("accepted",final.blocks.filterIsInstance<MessageBlock.Text>().single().text)
            assertTrue(final.protocolItems.isEmpty());assertEquals(0,executions.get())
            assertEquals(1,opened.get());assertTrue(connection.disconnected.get())
        } finally {runner.stop();connection.released.countDown();worker.join()}
    }
    fun testRunnerFailuresKeepTextButDoNotExecutePreviouslyReadyTool() {
        val prefix=frames(delta(),ready())
        val cases=transportFailures().map {Connection(prefix,readFailure=it) to when(it) {
            is SocketTimeoutException -> ModelFailure.TIMEOUT
            is SSLException -> ModelFailure.TLS
            else -> ModelFailure.NETWORK
        }} + listOf(
            Connection(prefix) to ModelFailure.INCOMPLETE,
            Connection(prefix+frames(event("response.failed"))) to ModelFailure.REQUEST,
            Connection(prefix+frames(event("response.completed","response" to JSONObject().put("status","completed")))) to ModelFailure.PROTOCOL)
        for((connection,failure) in cases) {
            val opened=AtomicInteger();var executions=0
            val result=ChatRunner(model(connection,opened),object:ConversationStore {override fun save(message:Message) {}},
                BatteryTool {executions++;"{}"},{}).run(initial(),emptyList())
            assertEquals(RunStatus.FAILED,result.status);assertEquals(failure.userMessage,result.error)
            assertEquals("accepted",result.blocks.filterIsInstance<MessageBlock.Text>().single().text)
            assertTrue(result.protocolItems.isEmpty());assertEquals(0,executions)
            assertEquals(1,opened.get());assertTrue(connection.disconnected.get())
            assertFalse(connection.instanceFollowRedirects)
        }
    }
    fun testSinkFailureIdentityWinsEvenWhenSinkCancels() {
        val failures=transportFailures()+listOf(org.json.JSONException("sink"),IllegalStateException("sink"),ModelRequestException(ModelFailure.PROTOCOL))
        for(failure in failures) for(target in listOf("text","tool","protocol")) {
            val connection=Connection(frames(delta(),ready(),completed(call(),message())))
            val session=ResponsesModel(settings) {connection}.open(emptyList())
            try {
                session.next(emptyList()) {event ->
                    if(when(target) {
                        "text" -> event is ModelEvent.TextFinal
                        "tool" -> event is ModelEvent.ToolReady
                        else -> event is ModelEvent.ProtocolOutput
                    }) {session.cancel();throw failure}
                }
                fail("sink must fail")
            } catch(caught:Exception) {assertSame(failure,caught)}
            assertTrue(connection.disconnected.get())
        }
    }
    fun testCleanupFailureDoesNotReplaceSinkFailure() {
        val connection=Connection(frames(completed(message())))
        connection.cleanupFailure=IllegalStateException("cleanup")
        val session=ResponsesModel(settings) {connection}.open(emptyList())
        val original=IOException("sink")
        try {session.next(emptyList()) {if(it is ModelEvent.TextFinal) throw original};fail("sink must fail")}
        catch(caught:Exception) {assertSame(original,caught)}
        assertTrue(connection.disconnected.get())
    }
    fun testRedirectIsNotFollowedAndRunnerNeverRetries() {
        val connection=Connection("",status=302)
        val opened=AtomicInteger();var executions=0
        val result=ChatRunner(model(connection,opened),object:ConversationStore {override fun save(message:Message) {}},
            BatteryTool {executions++;"{}"},{}).run(initial(),emptyList())
        assertEquals(ModelFailure.REDIRECT.userMessage,result.error);assertEquals(RunStatus.FAILED,result.status)
        assertEquals(1,opened.get());assertEquals(0,executions)
        assertFalse(connection.instanceFollowRedirects);assertTrue(connection.disconnected.get())
    }
}
