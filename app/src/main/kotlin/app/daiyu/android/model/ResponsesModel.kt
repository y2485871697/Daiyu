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
    override fun open(history:List<Message>,tools:Set<ReadOnlyTool>):ModelSession = open(history,tools.map {
        ToolDefinition(it.toolName,it.description,"""{"type":"object","properties":{},"required":[],"additionalProperties":false}""")
    })
    override fun open(history:List<Message>,definitions:List<ToolDefinition>):ModelSession = Session(settings,history,connect,definitions.toList())
    private class Session(private val settings:ModelSettings,history:List<Message>,private val connect:(URL)->HttpsURLConnection,private val tools:List<ToolDefinition>):ModelSession {
        private val cancelled=AtomicBoolean(false)
        @Volatile private var connection:HttpsURLConnection?=null
        private val input=JSONArray()
        private val ownItems=mutableListOf<String>()
        private var usable=true
        private var started=false
        private var pending=emptySet<String>()
        init {
            settings.validate()
            MessageHistory.expand(history).forEach { message ->
                if(message.role=="user") {
                    input.put(JSONObject().put("role","user").put("content",message.blocks.filterIsInstance<MessageBlock.Text>().joinToString("\n") {it.text}))
                } else if(message.status==RunStatus.COMPLETED ||
                    (message.role=="assistant" && message.status==RunStatus.INTERRUPTED)) {
                    val interrupted=message.status==RunStatus.INTERRUPTED
                    if(message.protocolItems.isNotEmpty()) {
                        val items = message.protocolItems.map { raw ->
                            val item = JSONObject(raw)
                            require(item.has("type") || item.optJSONArray("content") == null ||
                                item.optString("role") !in setOf("user", "assistant")) {
                                "Messages 协议历史不能用于 Responses"
                            }
                            item
                        }
                        if(!interrupted) items.forEach { input.put(it) }
                        val calls = items.filter { it.optString("type") == "function_call" }.map { it.getString("call_id") }.toSet()
                        val results = items.filter { it.optString("type") == "function_call_output" }.associate { it.getString("call_id") to it.getString("output") }
                        message.blocks.filterIsInstance<MessageBlock.Tool>().forEach { block ->
                            val result = block.result
                            if(result != null) {
                                require(block.invocation.id.value in calls) { "历史工具缺少已确认协议调用" }
                                if(block.invocation.id.value in results) require(results[block.invocation.id.value] == result) { "历史工具结果冲突" }
                                else if(!interrupted) input.put(JSONObject().put("type","function_call_output").put("call_id",block.invocation.id.value).put("output",result))
                            }
                        }
                    }
                    else if(!interrupted) message.blocks.forEach { block -> when(block) {
                        is MessageBlock.Text -> input.put(JSONObject().put("role","assistant").put("content",block.text))
                        is MessageBlock.Tool -> if(block.result!=null) {
                            input.put(JSONObject().put("type","function_call").put("call_id",block.invocation.id.value).put("name",block.invocation.name).put("arguments",block.invocation.arguments))
                            input.put(JSONObject().put("type","function_call_output").put("call_id",block.invocation.id.value).put("output",block.result))
                        }
                    } }
                    if(interrupted) {
                        val text=message.blocks.filterIsInstance<MessageBlock.Text>()
                            .filter {it.text.isNotBlank()}.joinToString("\n") {it.text}
                        if(text.isNotBlank()) input.put(JSONObject().put("role","assistant")
                            .put("content",text+"\n\n[回复已中断]"))
                    }
                }
            }
        }
        private fun checkCancelled() {if(cancelled.get()) throw InterruptedException()}
        override fun next(outputs:List<ToolOutput>,sink:(ModelEvent)->Unit) {
            checkCancelled()
            check(usable && (!started || pending.isNotEmpty())) { "Responses session cannot be replayed" }
            require(outputs.map {it.callId.value}.toSet()==pending && outputs.size==pending.size) {
                "Tool outputs must match all pending tool calls exactly once"
            }
            started=true
            usable=false
            outputs.forEach {val item=JSONObject().put("type","function_call_output").put("call_id",it.callId.value).put("output",it.output);input.put(item);ownItems.add(item.toString())}
            val schemas=JSONArray()
            tools.forEach { definition ->
                schemas.put(JSONObject().put("type","function").put("name",definition.name).put("description",definition.description)
                    .put("parameters",JSONObject(definition.parameters)).put("strict",false))
            }
            val baseRequest=JSONObject().put("model",settings.model).put("input",input).put("stream",true).put("store",false).put("include",JSONArray().put("reasoning.encrypted_content"))
            if(schemas.length()>0) baseRequest.put("tools",schemas)
            val request=settings.requestOptions.apply(baseRequest,ProviderProtocol.RESPONSES)
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
                    val calls=mutableSetOf<String>()
                    items.forEach { raw ->
                        val item=JSONObject(raw)
                        if(item.optString("type")=="function_call") {
                            val id=item.opt("call_id") as? String ?: throw ModelRequestException(ModelFailure.PROTOCOL)
                            if(id.isBlank() || !calls.add(id)) throw ModelRequestException(ModelFailure.PROTOCOL)
                        }
                        input.put(item);ownItems.add(raw)
                    }
                    deliver(ModelEvent.ProtocolOutput(ownItems.toList()))
                    checkCancelled()
                    pending=calls.toSet()
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
            usable=true
        }
        override fun cancel() {
            cancelled.set(true)
            try {connection?.disconnect()} catch(_:Exception) {}
        }
    }
}
