package app.daiyu.android.backup

import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.storage.RunCheckpointCodec
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RunCheckpointCodecTest {
    private val user=Message(MessageId("u"),ConversationId("c"),RunId("r"),"user",listOf(MessageBlock.Text("u","问题")),RunStatus.COMPLETED)
    private val call=ToolInvocation(ToolCallId("call"),"read","{\"key\":1}")
    private val assistant=user.copy(id=MessageId("a"),role="assistant",status=RunStatus.RUNNING,
        blocks=listOf(MessageBlock.Tool(call,"done")),protocolItems=listOf("opaque-protocol"),
        historySegments=listOf(MessageHistorySegment(MessageId("a"),"assistant",listOf(MessageBlock.Tool(call,"done")),listOf("raw"))))
    private fun checkpoint()=RunCheckpoint(assistant,listOf(user),ExecutionPhase.PAUSED,
        listOf(ToolRecord(call,ToolDisposition.SUCCEEDED,"done")),listOf(ToolOutput(call.id,"done")),
        listOf(user.copy(id=MessageId("extra"))),2,true,true,"owner",4)
    @Test fun allFieldsAndLegacyEmptyHistoryRoundTrip() {
        val value=checkpoint();assertEquals(value,RunCheckpointCodec.decode(RunCheckpointCodec.encode(value)))
        val empty=value.copy(history=emptyList(),supplements=emptyList(),tools=emptyList(),outputs=emptyList())
        assertEquals(empty,RunCheckpointCodec.decode(RunCheckpointCodec.encode(empty)))
    }
    private fun reject(block:()->Unit) {try {block();fail("invalid checkpoint accepted")} catch(_:Exception) {}}
    @Test fun strictVersionTypesBoundsAndOwnership() {
        val text=RunCheckpointCodec.encode(checkpoint())
        val mutations=listOf<(JSONObject)->Unit>({it.put("version",4)}, {it.put("revision","4")},
            {it.put("owner","")},{it.put("round",101)},{it.put("message_reconciled",false)},
            {it.put("phase","TERMINAL")},{it.put("extra",true)}, {it.put("request_unconfirmed",1)})
        mutations.forEach {mutate-> val json=JSONObject(text);mutate(json);reject {RunCheckpointCodec.decode(json.toString())}}
        reject {RunCheckpointCodec.decode(text+" []")}
        reject {RunCheckpointCodec.encode(checkpoint().copy(history=listOf(user.copy(conversationId=ConversationId("other")))))}
    }
    @Test fun invalidToolResultsAndCrossRunSupplementsReject() {
        reject {RunCheckpointCodec.encode(checkpoint().copy(tools=listOf(ToolRecord(call,ToolDisposition.IN_FLIGHT,"bad"))))}
        reject {RunCheckpointCodec.encode(checkpoint().copy(outputs=listOf(ToolOutput(call.id,"different"))))}
        reject {RunCheckpointCodec.encode(checkpoint().copy(supplements=listOf(user.copy(runId=RunId("other")))))}
        val unknown=checkpoint().copy(tools=listOf(ToolRecord(call,ToolDisposition.UNKNOWN)),outputs=emptyList())
        assertEquals(unknown,RunCheckpointCodec.decode(RunCheckpointCodec.encode(unknown)))
    }
    @Test fun runningCapacityReservesRecoveryGrowth() {
        val limit=RunCheckpointCodec.MAX_BYTES
        RunCheckpointCodec.checkEncodedSize(limit-RunCheckpointCodec.RECOVERY_RESERVE,true)
        reject {RunCheckpointCodec.checkEncodedSize(limit-RunCheckpointCodec.RECOVERY_RESERVE+1,true)}
        RunCheckpointCodec.checkEncodedSize(limit,false)
        reject {RunCheckpointCodec.checkEncodedSize(limit+1,false)}
        val before=checkpoint().copy(owner="x",revision=9)
        val recovered=ProcessRecovery.interrupt(before).copy(owner="recovery-${java.util.UUID.randomUUID()}",revision=10,messageReconciled=true)
        val growth=RunCheckpointCodec.encode(recovered).toByteArray().size-RunCheckpointCodec.encode(before).toByteArray().size
        assertTrue(growth in 1 until RunCheckpointCodec.RECOVERY_RESERVE)
    }

    @Test fun confirmedErrorAndRejectionRoundTripWithoutBecomingSuccess() {
        for (disposition in listOf(ToolDisposition.FAILED, ToolDisposition.NOT_EXECUTED)) {
            val value=checkpoint().copy(tools=listOf(ToolRecord(call,disposition,"done")))
            assertEquals(value,RunCheckpointCodec.decode(RunCheckpointCodec.encode(value)))
            val recovered=ProcessRecovery.interrupt(value).copy(messageReconciled=true)
            assertEquals(disposition,recovered.tools.single().disposition)
            assertEquals("done",recovered.tools.single().result)
            reject {RunCheckpointCodec.encode(value.copy(tools=listOf(ToolRecord(call,disposition))))}
            reject {RunCheckpointCodec.encode(value.copy(outputs=listOf(ToolOutput(call.id,"wrong"))))}
        }
    }

    private fun waiting(): RunCheckpoint {
        val question = call.copy(name = "ask_user")
        return checkpoint().copy(message = assistant.copy(blocks = listOf(MessageBlock.Tool(question))),
            phase = ExecutionPhase.WAITING_USER, tools = listOf(ToolRecord(question, ToolDisposition.IN_FLIGHT)),
            outputs = emptyList(), pending = PendingToolCheckpoint(question.id))
    }
    @Test fun writesV3AndReadsLegacyOnlyWithTheirExactFields() {
        val original = checkpoint(); val v3 = JSONObject(RunCheckpointCodec.encode(original))
        assertEquals(3, v3.getInt("version")); assertTrue(v3.isNull("request"))
        assertEquals(original, RunCheckpointCodec.decode(v3.toString()))
        val v2 = JSONObject(v3.toString()).put("version", 2); v2.remove("request")
        assertEquals(original, RunCheckpointCodec.decode(v2.toString()))
        val v1 = JSONObject(v2.toString()).put("version", 1); v1.remove("pending")
        assertEquals(original, RunCheckpointCodec.decode(v1.toString()))
        reject { RunCheckpointCodec.decode(JSONObject(v1.toString()).put("pending", JSONObject.NULL).toString()) }
        reject { RunCheckpointCodec.decode(JSONObject(v2.toString()).put("request", JSONObject.NULL).toString()) }
        for (key in listOf("pending", "request")) {
            val missing = JSONObject(v3.toString()); missing.remove(key)
            reject { RunCheckpointCodec.decode(missing.toString()) }
        }
        val impossibleV1 = JSONObject(RunCheckpointCodec.encode(waiting())).put("version", 1)
        impossibleV1.remove("pending"); impossibleV1.remove("request")
        reject { RunCheckpointCodec.decode(impossibleV1.toString()) }
    }
    @Test fun requestIdentityRoundTripsAllOperationsAndRecoveryKeepsOriginalOwner() {
        for (operation in RequestOperation.entries) {
            val original = checkpoint().copy(request = RequestCheckpoint("request", "attempt", "owner", operation))
            assertEquals(original, RunCheckpointCodec.decode(RunCheckpointCodec.encode(original)))
            val recovered = ProcessRecovery.interrupt(original).copy(owner = "recovery", revision = 5, messageReconciled = true)
            assertEquals(original.request, recovered.request)
            assertEquals(recovered, RunCheckpointCodec.decode(RunCheckpointCodec.encode(recovered)))
        }
    }
    @Test fun requestIdentityRejectsMalformedShapesAndForeignActiveOwners() {
        val value = checkpoint().copy(request = RequestCheckpoint("request", "attempt", "owner", RequestOperation.NEXT))
        val text = RunCheckpointCodec.encode(value)
        val mutations = listOf<(JSONObject) -> Unit>(
            { it.put("request", "request") }, { it.put("request", org.json.JSONArray()) },
            { it.getJSONObject("request").remove("attempt_id") },
            { it.getJSONObject("request").put("extra", true) },
            { it.getJSONObject("request").put("request_id", "") },
            { it.getJSONObject("request").put("attempt_id", "x".repeat(201)) },
            { it.getJSONObject("request").put("owner", "foreign") },
            { it.getJSONObject("request").put("operation", "POST") },
            { it.getJSONObject("request").put("operation", 1) })
        mutations.forEach { mutate -> val json = JSONObject(text); mutate(json)
            reject { RunCheckpointCodec.decode(json.toString()) } }
        reject { RunCheckpointCodec.encode(value.copy(request = value.request!!.copy(owner = "foreign"))) }
    }
    @Test fun waitingPendingRoundTripAndStrictShape() {
        val value = waiting(); val text = RunCheckpointCodec.encode(value)
        assertEquals(value, RunCheckpointCodec.decode(text))
        assertEquals(setOf("call_id"), JSONObject(text).getJSONObject("pending").keys().asSequence().toSet())
        val mutations = listOf<(JSONObject) -> Unit>(
            { it.put("pending", JSONObject.NULL) }, { it.put("pending", "call") },
            { it.put("pending", org.json.JSONArray()) }, { it.put("pending", JSONObject()) },
            { it.put("pending", JSONObject().put("call_id", 7)) },
            { it.put("pending", JSONObject().put("call_id", "")) },
            { it.put("pending", JSONObject().put("call_id", "missing")) },
            { it.getJSONObject("pending").put("extra", true) }, { it.put("phase", "PAUSED") },
            { it.getJSONArray("tools").getJSONObject(0).put("name", "read") },
            { it.getJSONArray("tools").getJSONObject(0).put("disposition", "UNKNOWN") })
        mutations.forEach { mutate -> val json = JSONObject(text); mutate(json); reject { RunCheckpointCodec.decode(json.toString()) } }
    }
    @Test fun pendingUniquelyReferencesOnlyInFlightAskUser() {
        val value = waiting(); val question = value.tools.single().invocation
        listOf(value.copy(pending = null), value.copy(phase = ExecutionPhase.PAUSED), value.copy(tools = emptyList()),
            value.copy(tools = listOf(ToolRecord(question.copy(name = "read"), ToolDisposition.IN_FLIGHT))),
            value.copy(tools = listOf(ToolRecord(question, ToolDisposition.READY))),
            value.copy(tools = listOf(ToolRecord(question, ToolDisposition.UNKNOWN))),
            value.copy(tools = listOf(ToolRecord(question, ToolDisposition.SUCCEEDED, "answer")), outputs = listOf(ToolOutput(question.id, "answer"))),
            value.copy(tools = listOf(ToolRecord(question, ToolDisposition.IN_FLIGHT), ToolRecord(question, ToolDisposition.IN_FLIGHT))),
            value.copy(tools = value.tools + ToolRecord(call.copy(id = ToolCallId("other")), ToolDisposition.IN_FLIGHT)),
            value.copy(outputs = listOf(ToolOutput(question.id, "answer")))
        ).forEach { invalid -> reject { RunCheckpointCodec.encode(invalid) } }
    }
    @Test fun recoveryClearsPendingAndReservesEnoughCapacity() {
        val before = waiting().copy(owner = "x", revision = 9)
        val after = ProcessRecovery.interrupt(before).copy(owner = "recovery-${java.util.UUID.randomUUID()}", revision = 10, messageReconciled = true)
        assertNull(after.pending); assertEquals(ToolDisposition.UNKNOWN, after.tools.single().disposition)
        assertEquals(after, RunCheckpointCodec.decode(RunCheckpointCodec.encode(after)))
        val growth = RunCheckpointCodec.encode(after).toByteArray().size - RunCheckpointCodec.encode(before).toByteArray().size
        assertTrue(growth in 1 until RunCheckpointCodec.RECOVERY_RESERVE)
    }

}
