package app.daiyu.android.model

import app.daiyu.android.domain.*
import app.daiyu.android.storage.ModelSettings
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

class ResponsesModel(private val settings:ModelSettings):ModelPort {
    override fun open(history:List<Message>):ModelSession = Session(settings,history)
    private class Session(private val settings:ModelSettings,history:List<Message>):ModelSession {
        private val cancelled=AtomicBoolean(false)
        @Volatile private var connection:HttpsURLConnection?=null
        private val input=JSONArray()
        private val ownItems=mutableListOf<String>()
        init {
            settings.validate()
            history.forEach { message ->
                if(message.role=="user") {
                    input.put(JSONObject().put("role","user").put("content",message.blocks.filterIsInstance<MessageBlock.Text>().joinToString("\n") {it.text}))
                } else if(message.status==RunStatus.COMPLETED) {
                    if(message.protocolItems.isNotEmpty()) message.protocolItems.forEach {input.put(JSONObject(it))}
                    else message.blocks.forEach { block -> when(block) {
                        is MessageBlock.Text -> input.put(JSONObject().put("role","assistant").put("content",block.text))
                        is MessageBlock.Tool -> if(block.result!=null) {
                            input.put(JSONObject().put("type","function_call").put("call_id",block.invocation.id.value).put("name",block.invocation.name).put("arguments",block.invocation.arguments))
                            input.put(JSONObject().put("type","function_call_output").put("call_id",block.invocation.id.value).put("output",block.result))
                        }
                    } }
                }
            }
        }
        override fun next(outputs:List<ToolOutput>,sink:(ModelEvent)->Unit) {
            if(cancelled.get()) throw InterruptedException()
            outputs.forEach {val item=JSONObject().put("type","function_call_output").put("call_id",it.callId.value).put("output",it.output);input.put(item);ownItems.add(item.toString())}
            val tool=JSONObject().put("type","function").put("name","get_battery_status").put("description","读取当前设备电量和充电状态；无写入能力")
                .put("parameters",JSONObject().put("type","object").put("properties",JSONObject()).put("required",JSONArray()).put("additionalProperties",false)).put("strict",true)
            val request=JSONObject().put("model",settings.model).put("input",input).put("stream",true).put("store",false).put("include",JSONArray().put("reasoning.encrypted_content"))
            if(settings.batteryEnabled) request.put("tools",JSONArray().put(tool))
            val conn=URL(settings.endpoint).openConnection() as HttpsURLConnection
            connection=conn
            try {
                if(cancelled.get()) throw InterruptedException()
                conn.requestMethod="POST";conn.doOutput=true;conn.instanceFollowRedirects=false
                conn.connectTimeout=20_000;conn.readTimeout=60_000
                conn.setRequestProperty("Authorization","Bearer ${settings.apiKey}")
                conn.setRequestProperty("Content-Type","application/json");conn.setRequestProperty("Accept","text/event-stream")
                conn.outputStream.use {it.write(request.toString().toByteArray(Charsets.UTF_8))}
                if(conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
                require(conn.contentType?.startsWith("text/event-stream")==true) {"服务未返回事件流"}
                var completed=false;val sequence=mutableSetOf<Long>();var chars=0
                val calls=linkedMapOf<String,ToolInvocation>()
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    SseReader.read(reader) { data ->
                        if(cancelled.get()) throw InterruptedException()
                        if(data!="[DONE]") {
                            val event=JSONObject(data)
                            if(event.has("sequence_number") && !sequence.add(event.getLong("sequence_number"))) return@read true
                            check(!completed) {"完成后仍收到新事件"}
                            when(event.getString("type")) {
                                "response.output_text.delta" -> {
                                    val delta=event.getString("delta");chars+=delta.length;require(chars<=2_000_000) {"回复过大"}
                                    sink(ModelEvent.TextDelta(event.getString("item_id"),delta))
                                }
                                "response.output_text.done" -> sink(ModelEvent.TextFinal(event.getString("item_id"),event.getString("text")))
                                "response.refusal.delta" -> sink(ModelEvent.TextDelta(event.getString("item_id"),event.getString("delta")))
                                "response.refusal.done" -> sink(ModelEvent.TextFinal(event.getString("item_id"),event.getString("refusal")))
                                "response.output_item.done" -> {
                                    val item=event.getJSONObject("item")
                                    if(item.getString("type")=="function_call") {
                                        require(item.optString("status","completed")=="completed")
                                        val call=ToolInvocation(ToolCallId(item.getString("call_id")),item.getString("name"),item.getString("arguments"))
                                        val prior=calls.putIfAbsent(call.id.value,call);require(prior==null || prior==call)
                                        if(prior==null) sink(ModelEvent.ToolReady(call))
                                    }
                                }
                                "response.completed" -> {
                                    val response=event.getJSONObject("response");require(response.getString("status")=="completed")
                                    val out=response.getJSONArray("output")
                                    val confirmed=mutableSetOf<String>()
                                    for(i in 0 until out.length()) {
                                        val item=out.getJSONObject(i)
                                        if(item.getString("type")=="function_call") {
                                            val id=item.getString("call_id");confirmed.add(id)
                                            require(calls[id]==ToolInvocation(ToolCallId(id),item.getString("name"),item.getString("arguments"))) {"工具调用缺少完整确认"}
                                        }
                                        if(item.getString("type")=="message") {
                                            val content=item.getJSONArray("content")
                                            val text=(0 until content.length()).joinToString("") { j ->
                                                val part=content.getJSONObject(j)
                                                when(part.getString("type")) {"output_text"->part.getString("text");"refusal"->part.getString("refusal");else->""}
                                            }
                                            sink(ModelEvent.TextFinal(item.getString("id"),text))
                                        }
                                        input.put(item);ownItems.add(item.toString())
                                    }
                                    require(confirmed==calls.keys) {"调用与最终响应不一致"}
                                    sink(ModelEvent.ProtocolOutput(ownItems.toList()))
                                    completed=true
                                }
                                "response.failed","response.incomplete","error" -> throw IllegalStateException("模型未成功完成")
                            }
                        }
                        !completed
                    }
                }
                check(completed) {"事件流未收到成功终态"}
            } finally {conn.disconnect();connection=null}
        }
        override fun cancel() {cancelled.set(true);connection?.disconnect()}
    }
}
