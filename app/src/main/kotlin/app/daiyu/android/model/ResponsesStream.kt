package app.daiyu.android.model

import app.daiyu.android.domain.*
import org.json.JSONObject
import java.io.BufferedReader

internal object ResponsesStream {
    private fun checkProtocol(valid:Boolean) {
        if(!valid) throw ModelRequestException(ModelFailure.PROTOCOL)
    }
    fun read(reader:BufferedReader, cancelled:()->Boolean, sink:(ModelEvent)->Unit):List<String> {
        var deliveryFailure:Exception?=null
        try {
            return decode(reader,cancelled) {event ->
                if(cancelled()) throw InterruptedException()
                try {sink(event)} catch(failure:Exception) {deliveryFailure=failure;throw failure}
            }
        } catch(failure:org.json.JSONException) {
            if(failure===deliveryFailure) throw failure
            throw ModelRequestException(ModelFailure.PROTOCOL)
        }
    }
    private fun decode(reader:BufferedReader, cancelled:()->Boolean, sink:(ModelEvent)->Unit):List<String> {
        var output:List<String>?=null
        val sequence=mutableSetOf<Long>()
        val calls=linkedMapOf<String,ToolInvocation>()
        val parts=mutableMapOf<String,java.util.SortedMap<Int,String>>()
        val summaries=mutableMapOf<String,java.util.SortedMap<Int,String>>()
        var chars=0
        fun text(event:JSONObject, value:String, final:Boolean) {
            val id=event.getString("item_id")
            val index=event.getInt("content_index")
            checkProtocol(index>=0)
            val content=parts.getOrPut(id) {sortedMapOf()}
            content[index]=if(final) value else content.getOrDefault(index,"")+value
            val combined=content.values.joinToString("")
            checkProtocol(combined.length<=2_000_000)
            sink(ModelEvent.TextFinal(id,combined))
        }
        fun summary(event:JSONObject, value:String, final:Boolean) {
            val id=event.getString("item_id")
            val index=event.getInt("summary_index")
            checkProtocol(index>=0)
            val content=summaries.getOrPut(id) {sortedMapOf()}
            content[index]=if(final) value else content.getOrDefault(index,"")+value
            val combined=content.values.joinToString("")
            checkProtocol(combined.length<=2_000_000)
            sink(ModelEvent.ReasoningFinal(id,combined))
        }
        fun summaryItem(item:JSONObject) {
            if(item.getString("type")!="reasoning") return
            if(!item.has("summary") || item.isNull("summary")) return
            val parts=item.getJSONArray("summary")
            if(parts.length()==0) return
            val content=sortedMapOf<Int,String>()
            for(i in 0 until parts.length()) {
                val part=parts.getJSONObject(i)
                checkProtocol(part.getString("type")=="summary_text")
                content[i]=part.getString("text")
            }
            val combined=content.values.joinToString("")
            checkProtocol(combined.length<=2_000_000)
            val id=item.getString("id")
            summaries[id]=content
            sink(ModelEvent.ReasoningFinal(id,combined))
        }
        SseReader.read(reader) { data ->
            if(cancelled()) throw InterruptedException()
            if(data=="[DONE]") return@read false
            val event=try {JSONObject(data)} catch (_:org.json.JSONException) {throw ModelRequestException(ModelFailure.PROTOCOL)}
            if(!event.has("type")) throw ModelRequestException(ModelFailure.PROTOCOL)
            if(event.has("sequence_number") && !sequence.add(event.getLong("sequence_number"))) return@read true
            when(event.getString("type")) {
                "response.reasoning_summary_text.delta" -> {
                    val delta=event.getString("delta");chars+=delta.length
                    checkProtocol(chars<=2_000_000)
                    summary(event,delta,false)
                }
                "response.reasoning_summary_text.done" -> summary(event,event.getString("text"),true)
                "response.reasoning_summary_part.done" -> {
                    val part=event.getJSONObject("part")
                    checkProtocol(part.getString("type")=="summary_text")
                    summary(event,part.getString("text"),true)
                }
                "response.output_text.delta","response.refusal.delta" -> {
                    val delta=event.getString("delta");chars+=delta.length
                    checkProtocol(chars<=2_000_000)
                    text(event,delta,false)
                }
                "response.output_text.done" -> text(event,event.getString("text"),true)
                "response.refusal.done" -> text(event,event.getString("refusal"),true)
                "response.output_item.done" -> {
                    val item=event.getJSONObject("item")
                    summaryItem(item)
                    if(item.getString("type")=="function_call") {
                        checkProtocol(item.optString("status","completed")=="completed")
                        val call=ToolInvocation(ToolCallId(item.getString("call_id")),item.getString("name"),item.getString("arguments"))
                        checkProtocol(calls.putIfAbsent(call.id.value,call)==null)
                        sink(ModelEvent.ToolReady(call))
                    }
                }
                "response.completed" -> {
                    val response=event.getJSONObject("response")
                    checkProtocol(response.getString("status")=="completed")
                    val out=response.getJSONArray("output")
                    val confirmed=mutableSetOf<String>()
                    for(i in 0 until out.length()) {
                        val item=out.getJSONObject(i)
                        if(item.getString("type")=="function_call") {
                            val id=item.getString("call_id")
                            checkProtocol(confirmed.add(id))
                            checkProtocol(item.optString("status","completed")=="completed")
                            checkProtocol(calls[id]==ToolInvocation(ToolCallId(id),item.getString("name"),item.getString("arguments")))
                        }
                    }
                    checkProtocol(confirmed==calls.keys)
                    for(i in 0 until out.length()) {
                        val item=out.getJSONObject(i)
                        summaryItem(item)
                        if(item.getString("type")=="message") {
                            val content=item.getJSONArray("content")
                            val full=(0 until content.length()).joinToString("") { j ->
                                val part=content.getJSONObject(j)
                                when(part.getString("type")) {"output_text"->part.getString("text");"refusal"->part.getString("refusal");else->""}
                            }
                            checkProtocol(full.length<=2_000_000)
                            sink(ModelEvent.TextFinal(item.getString("id"),full))
                        }
                    }
                    output=(0 until out.length()).map {out.getJSONObject(it).toString()}
                }
                "response.failed","error" -> throw ModelRequestException(ModelFailure.REQUEST)
                "response.incomplete" -> throw ModelRequestException(ModelFailure.INCOMPLETE)
            }
            output==null
        }
        if(cancelled()) throw InterruptedException()
        return output ?: throw ModelRequestException(ModelFailure.INCOMPLETE)
    }
}
