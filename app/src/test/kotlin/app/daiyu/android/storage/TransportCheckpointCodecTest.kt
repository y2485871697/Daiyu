package app.daiyu.android.storage

import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TransportCheckpointCodecTest {
    private fun checkpoint(): RunCheckpoint {
        val request = RequestCheckpoint("request","attempt","owner",RequestOperation.RESUME)
        return RunCheckpoint(Message(MessageId("a"),ConversationId("c"),RunId("r"),"assistant",
            listOf(MessageBlock.Text("m","accepted")),RunStatus.RUNNING,protocolItems = listOf("{\"type\":\"message\"}")),
            emptyList(),ExecutionPhase.STREAMING,owner = "owner",revision = 3,request = request,
            usage = listOf(RequestUsageCheckpoint(request,ModelEvent.Usage(5,2))), usageRequests = listOf(request),
            transport = TransportCheckpoint("resp_fixture",42,
                JSONObject().put("response_id","resp_fixture").put("sequence",42)
                    .put("parts",JSONObject().put("m",JSONObject().put("0","accepted")))
                    .put("summaries",JSONObject()).put("calls",org.json.JSONArray()).put("chars",8).toString()))
    }
    private fun reject(action: () -> Unit) { try { action(); fail("must reject") } catch (_: Exception) {} }
    @Test fun v7RoundTripsCursorDecoderAndAcceptedStateTogether() {
        val original = checkpoint()
        val encoded = RunCheckpointCodec.encode(original)
        assertEquals(7,JSONObject(encoded).getInt("version"))
        assertEquals(original,RunCheckpointCodec.decode(encoded))
        val interrupted = ProcessRecovery.interrupt(RunCheckpointCodec.decode(encoded))
        assertEquals(ExecutionPhase.TERMINAL,interrupted.phase)
        assertEquals(RunStatus.INTERRUPTED,interrupted.message.status)
        assertEquals(original.transport,interrupted.transport) // Evidence only; no resume/open API in recovery.
    }
    @Test fun normalV6StaysExactAndHasNoTransportAuthority() {
        val normal = checkpoint().copy(transport = null)
        val json = JSONObject(RunCheckpointCodec.encode(normal))
        assertEquals(6,json.getInt("version")); assertFalse(json.has("transport"))
        assertEquals(normal,RunCheckpointCodec.decode(json.toString()))
        json.put("version",7).put("transport",JSONObject.NULL)
        assertEquals(normal,RunCheckpointCodec.decode(json.toString()))
    }
    @Test fun invalidCursorTypesStateMismatchOrOpenRequestReject() {
        val original = checkpoint()
        reject { RunCheckpointCodec.encode(original.copy(request = original.request!!.copy(operation = RequestOperation.OPEN))) }
        reject { RunCheckpointCodec.encode(original.copy(transport = original.transport!!.copy(sequence = 43))) }
        reject { RunCheckpointCodec.encode(original.copy(transport = original.transport!!.copy(decoderState = "{}"))) }
        for (bad in listOf<Any>(42.0,"42",-1)) {
            val json = JSONObject(RunCheckpointCodec.encode(original))
            json.getJSONObject("transport").put("sequence",bad)
            try { RunCheckpointCodec.decode(json.toString()); fail("must reject") } catch (_: Exception) {}
        }
    }
}
