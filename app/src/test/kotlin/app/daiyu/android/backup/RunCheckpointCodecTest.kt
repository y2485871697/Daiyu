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
        val mutations=listOf<(JSONObject)->Unit>({it.put("version",2)}, {it.put("revision","4")},
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

}
