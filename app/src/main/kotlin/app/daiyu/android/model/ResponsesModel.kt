package app.daiyu.android.model

import app.daiyu.android.domain.*
import app.daiyu.android.storage.ModelSettings
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

class ResponsesModel internal constructor(private val settings:ModelSettings, private val connect:(URL)->HttpsURLConnection):ModelPort {
    constructor(settings:ModelSettings):this(settings,{it.openConnection() as HttpsURLConnection})
    override fun open(history:List<Message>):ModelSession = Session(settings,history,connect)
    private class Session(private val settings:ModelSettings,history:List<Message>,private val connect:(URL)->HttpsURLConnection):ModelSession {
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
            val conn=connect(URL(settings.endpoint))
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
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val items=ResponsesStream.read(reader,cancelled::get,sink)
                    items.forEach {input.put(JSONObject(it));ownItems.add(it)}
                    sink(ModelEvent.ProtocolOutput(ownItems.toList()))
                }
            } finally {conn.disconnect();connection=null}
        }
        override fun cancel() {cancelled.set(true);connection?.disconnect()}
    }
}
