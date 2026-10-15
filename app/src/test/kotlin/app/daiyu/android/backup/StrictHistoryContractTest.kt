package app.daiyu.android.backup

import app.daiyu.android.context.ArchiveCodec
import app.daiyu.android.domain.*
import app.daiyu.android.storage.MessageHistoryCodec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StrictHistoryContractTest {
    private fun owner(id:String="a")=Message(MessageId(id),ConversationId("c"),RunId("r"),"assistant",
        emptyList(),RunStatus.COMPLETED,historySegments=listOf(
            MessageHistorySegment(MessageId("u"),"user",listOf(MessageBlock.Text("u","accepted")))))
    private fun rejected(work:()->Unit) {try {work();fail("accepted malformed history")} catch(_:IllegalArgumentException) {} }
    @Test fun completeArchiveRejectsCrossOwnerAndStandaloneConflictsWithoutRewriting() {
        val a=owner();val b=owner("b");val bytes=ArchiveCodec.encode(listOf(a))
        rejected {ArchiveCodec.encode(listOf(a,b))}
        val user=a.copy(id=MessageId("u"),role="user",historySegments=emptyList(),blocks=listOf(MessageBlock.Text("u","wrong")))
        rejected {ArchiveCodec.encode(listOf(a,user))}
        val wire=JSONArray(String(bytes))
        val userWire=JSONArray(String(ArchiveCodec.encode(listOf(user)))).getJSONObject(0)
        wire.put(userWire)
        rejected {ArchiveCodec.decode(wire.toString().toByteArray())}
        val good=user.copy(blocks=a.historySegments.single().blocks)
        assertEquals(listOf(a,good),ArchiveCodec.decode(ArchiveCodec.encode(listOf(a,good))))
    }
    @Test fun archiveRejectsExplicitEmptySegmentsAndTrailingJson() {
        val bytes=ArchiveCodec.encode(listOf(owner()))
        rejected {ArchiveCodec.decode(bytes+" []".toByteArray())}
        val array=JSONArray(String(bytes));array.getJSONObject(0).put("history_segments",JSONArray())
        rejected {ArchiveCodec.decode(array.toString().toByteArray())}
    }
    @Test fun segmentWireRejectsWrongTypesUserToolsAndUnknownFields() {
        val valid=MessageHistoryCodec.encode(owner().historySegments)
        val fields=listOf<(JSONObject)->Unit>(
            {it.put("message_id",12)}, {it.put("extra",true)},
            {it.put("blocks",JSONArray().put(JSONObject().put("type","tool").put("id","call").put("name","lookup").put("arguments","{}").put("result",JSONObject.NULL)))})
        fields.forEach { mutate ->
            val copy=JSONArray(valid.toString());mutate(copy.getJSONObject(0))
            try {MessageHistoryCodec.decode(copy);fail("invalid segment")} catch(_:Exception) {}
        }
    }
    @Test fun segmentResourceLimitsApplyToAuthoritativeBlocks() {
        val o=owner()
        val bad=listOf(
            MessageHistorySegment(o.id,"assistant",List(10_001) {MessageBlock.Text("t","x")}),
            MessageHistorySegment(o.id,"assistant",listOf(MessageBlock.Text("t","x".repeat(4*1024*1024+1)))),
            MessageHistorySegment(o.id,"assistant",emptyList(),List(10_001) {"raw"}))
        bad.forEach { rejected {MessageHistoryCodec.encode(listOf(it))} }
    }
}
