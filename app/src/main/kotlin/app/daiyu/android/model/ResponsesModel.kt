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
    override fun open(history:List<Message>):ModelSession = open(history,settings.enabledTools())
    override fun open(history:List<Message>,tools:Set<ReadOnlyTool>):ModelSession = Session(settings,history,connect,tools.toSet())
    private class Session(private val settings:ModelSettings,history:List<Message>,private val connect:(URL)->HttpsURLConnection,private val tools:Set<ReadOnlyTool>):ModelSession {
        private val cancelled=AtomicBoolean(false)
        @Volatile private var connection:HttpsURLConnection?=null
        private val input=JSONArray()
        private val ownItems=mutableListOf<String>()
        init {
            settings.validate()
            MessageHistory.expand(history).forEach { message ->
                if(message.role=="user") {
                    input.put(JSONObject().put("role","user").put("content",message.blocks.filterIsInstance<MessageBlock.Text>().joinToString("\n") {it.text}))
                } else if(message.status==RunStatus.COMPLETED) {
                    if(message.protocolItems.isNotEmpty()) {
                        val items = message.protocolItems.map { JSONObject(it) }
                        items.forEach { input.put(it) }
                        val calls = items.filter { it.optString("type") == "function_call" }.map { it.getString("call_id") }.toSet()
                        val results = items.filter { it.optString("type") == "function_call_output" }.associate { it.getString("call_id") to it.getString("output") }
                        message.blocks.filterIsInstance<MessageBlock.Tool>().forEach { block ->
                            val result = block.result
                            if(result != null) {
                                require(block.invocation.id.value in calls) { "历史工具缺少已确认协议调用" }
                                if(block.invocation.id.value in results) require(results[block.invocation.id.value] == result) { "历史工具结果冲突" }
                                else input.put(JSONObject().put("type","function_call_output").put("call_id",block.invocation.id.value).put("output",result))
                            }
                        }
                    }
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
        private fun checkCancelled() {if(cancelled.get()) throw InterruptedException()}
        override fun next(outputs:List<ToolOutput>,sink:(ModelEvent)->Unit) {
            checkCancelled()
            outputs.forEach {val item=JSONObject().put("type","function_call_output").put("call_id",it.callId.value).put("output",it.output);input.put(item);ownItems.add(item.toString())}
            val schemas=JSONArray()
            tools.forEach { definition ->
                schemas.put(JSONObject().put("type","function").put("name",definition.toolName).put("description",definition.description)
                    .put("parameters",JSONObject().put("type","object").put("properties",JSONObject()).put("required",JSONArray()).put("additionalProperties",false)).put("strict",true))
            }
            val request=JSONObject().put("model",settings.model).put("input",input).put("stream",true).put("store",false).put("include",JSONArray().put("reasoning.encrypted_content"))
            if(schemas.length()>0) request.put("tools",schemas)
            var deliveryFailure:Exception?=null
            val deliver:(ModelEvent)->Unit={event ->
                checkCancelled()
                try {sink(event)} catch(failure:Exception) {deliveryFailure=failure;throw failure}
            }
            var ownedConnection:HttpsURLConnection?=null
            try {
                checkCancelled()
                val conn=connect(URL(settings.requestEndpoint()))
                ownedConnection=conn;connection=conn
                checkCancelled()
                conn.requestMethod="POST";conn.doOutput=true;conn.instanceFollowRedirects=false
                conn.connectTimeout=20_000;conn.readTimeout=60_000
                conn.setRequestProperty("Authorization","Bearer ${settings.apiKey}")
                conn.setRequestProperty("Content-Type","application/json");conn.setRequestProperty("Accept","text/event-stream")
                conn.outputStream.use {it.write(request.toString().toByteArray(Charsets.UTF_8))}
                checkCancelled()
                val status = conn.responseCode
                checkCancelled()
                if(status !in 200..299) throw ModelRequestException(when(status) {
                    401 -> ModelFailure.AUTHENTICATION
                    403 -> ModelFailure.PERMISSION
                    404,405 -> ModelFailure.ENDPOINT
                    429 -> ModelFailure.RATE_LIMIT
                    in 300..399 -> ModelFailure.REDIRECT
                    in 500..599 -> ModelFailure.SERVER
                    else -> ModelFailure.REQUEST
                })
                if(conn.contentType?.substringBefore(';')?.trim()?.equals("text/event-stream",ignoreCase=true)!=true)
                    throw ModelRequestException(ModelFailure.PROTOCOL)
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val items=ResponsesStream.read(reader,cancelled::get,deliver)
                    checkCancelled()
                    items.forEach {input.put(JSONObject(it));ownItems.add(it)}
                    deliver(ModelEvent.ProtocolOutput(ownItems.toList()))
                    checkCancelled()
                }
                checkCancelled()
            } catch(failure:Exception) {
                // A sink failure belongs to the caller, even if it also cancelled the session.
                if(failure===deliveryFailure) throw failure
                checkCancelled()
                throw when(failure) {
                    is java.net.SocketTimeoutException -> ModelRequestException(ModelFailure.TIMEOUT)
                    is javax.net.ssl.SSLException -> ModelRequestException(ModelFailure.TLS)
                    is java.io.IOException -> ModelRequestException(ModelFailure.NETWORK)
                    else -> failure
                }
            } finally {
                // Best-effort cleanup must not replace a sink or transport failure.
                try {ownedConnection?.disconnect()} catch(_:Exception) {} finally {connection=null}
            }
            // Cleanup may cancel too. Only check on success so the original failure stays primary.
            checkCancelled()
        }
        override fun cancel() {cancelled.set(true);connection?.disconnect()}
    }
}
