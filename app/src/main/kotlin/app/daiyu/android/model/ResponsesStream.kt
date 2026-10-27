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
    private fun decode(reader:BufferedReader, cancelled:()->Boolean, sink:(ModelEvent)->Unit):List<String> =
        State().read(reader, cancelled, sink)

    /** Retained by one original request across POST/GET. State advances only after ACK. */
    class State {
        var responseId: String? = null
            private set
        var acknowledgedSequence: Long? = null
            private set
        private var output:List<String>?=null
        private var usage:ModelEvent.Usage?=null
        private val calls=linkedMapOf<String,ToolInvocation>()
        private val parts=mutableMapOf<String,java.util.SortedMap<Int,String>>()
        private val summaries=mutableMapOf<String,java.util.SortedMap<Int,String>>()
        private var chars=0
        private var poisoned = false
        val canResume: Boolean get() = !poisoned && output == null && responseId != null && acknowledgedSequence != null
        private fun snapshot(sequence: Long): String = JSONObject().apply {
            put("response_id", responseId); put("sequence", sequence); put("chars", chars)
            fun map(source: Map<String, java.util.SortedMap<Int,String>>) = JSONObject().apply {
                source.forEach { (id, values) -> put(id, JSONObject().apply { values.forEach { (k,v) -> put(k.toString(),v) } }) }
            }
            put("parts", map(parts)); put("summaries", map(summaries))
            put("calls", org.json.JSONArray(calls.values.map { call -> JSONObject().put("id",call.id.value).put("name",call.name).put("arguments",call.arguments) }))
            output?.let { put("output",org.json.JSONArray(it)) }
        }.toString()

        fun read(reader:BufferedReader, cancelled:()->Boolean, legacySink:(ModelEvent)->Unit = {},
                 prefix: List<String>? = null,
                 acknowledgedSink: ((ModelEventBatch)->ModelDeliveryAck)? = null):List<String> {
        check(!poisoned && output == null)
        val batch = mutableListOf<ModelEvent>()
        val sink: (ModelEvent)->Unit = { event -> if (acknowledgedSink == null) legacySink(event) else batch.add(event) }
        fun index(event: JSONObject, key: String, fallback: Int = 0): Int {
            val value = event.opt(key)
            if (value == null || value === JSONObject.NULL) return fallback
            checkProtocol(value is Int || value is Long)
            val number = (value as Number).toLong()
            checkProtocol(number in 0..Int.MAX_VALUE.toLong())
            return number.toInt()
        }
        fun done(event: JSONObject, key: String, apply: (String) -> Unit) {
            val value = event.opt(key)
            if (value == null || value === JSONObject.NULL) return
            checkProtocol(value is String)
            apply(value as String)
        }
        fun call(item: JSONObject): ToolInvocation {
            checkProtocol(item.optString("status", "completed") == "completed")
            val id = item.getString("call_id"); val name = item.getString("name")
            checkProtocol(id.isNotBlank() && name.isNotBlank())
            val arguments = item.getString("arguments")
            MessagesRequest.jsonObject(arguments)
            return ToolInvocation(ToolCallId(id), name, arguments)
        }
        fun sameJson(a: Any?, b: Any?): Boolean = when {
            a is JSONObject && b is JSONObject -> a.keys().asSequence().toSet() == b.keys().asSequence().toSet() && a.keys().asSequence().all { sameJson(a.get(it), b.get(it)) }
            a is org.json.JSONArray && b is org.json.JSONArray -> a.length() == b.length() && (0 until a.length()).all { sameJson(a.get(it), b.get(it)) }
            a is Number && b is Number -> a.toString().toBigDecimal().compareTo(b.toString().toBigDecimal()) == 0
            else -> a == b
        }
        fun sameCall(a: ToolInvocation, b: ToolInvocation): Boolean = a.id == b.id && a.name == b.name && sameJson(MessagesRequest.jsonObject(a.arguments), MessagesRequest.jsonObject(b.arguments))
        fun text(event:JSONObject, value:String, final:Boolean) {
            val id=event.getString("item_id")
            val index=index(event, "content_index")
            checkProtocol(index>=0)
            val content=parts.getOrPut(id) {sortedMapOf()}
            content[index]=if(final) value else content.getOrDefault(index,"")+value
            val combined=content.values.joinToString("")
            checkProtocol(combined.length<=2_000_000)
            sink(ModelEvent.TextFinal(id,combined))
        }
        fun summary(event:JSONObject, value:String, final:Boolean) {
            val id=event.getString("item_id")
            val index=index(event, "summary_index", index(event, "content_index"))
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
                if (part.optString("type") == "summary_text") content[i]=part.getString("text")
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
            val seq = if(event.has("sequence_number")) {
                val raw = event.get("sequence_number")
                checkProtocol(raw is Int || raw is Long)
                (raw as Number).toLong()
            } else null
            if (seq != null && acknowledgedSequence != null && seq <= acknowledgedSequence!!) return@read true
            if (acknowledgedSink != null) checkProtocol(seq != null && seq >= 0)
            batch.clear()
            val savedId = responseId
            val savedCalls = LinkedHashMap(calls)
            fun copy(source: Map<String,java.util.SortedMap<Int,String>>) = source.mapValues { java.util.TreeMap(it.value) }
            val savedParts = copy(parts)
            val savedSummaries = copy(summaries)
            val savedChars = chars
            val savedOutput = output
            val savedUsage = usage
            val response = event.optJSONObject("response")
            if (acknowledgedSink != null && response != null && response.has("id")) {
                val id = response.getString("id")
                checkProtocol(id.matches(Regex("resp_[A-Za-z0-9_-]{1,180}")))
                checkProtocol(responseId == null || responseId == id)
                responseId = id
            }
            try {
            when(event.getString("type")) {
                "response.reasoning_summary_text.delta" -> {
                    val delta=event.getString("delta");chars+=delta.length
                    checkProtocol(chars<=2_000_000)
                    summary(event,delta,false)
                }
                "response.reasoning_summary_text.done" -> done(event,"text") { summary(event,it,true) }
                "response.reasoning_summary_part.done" -> {
                    val part=event.getJSONObject("part")
                    if (part.optString("type") == "summary_text") {
                        checkProtocol(part.opt("text") is String)
                        summary(event, part.getString("text"), true)
                    }
                }
                "response.output_text.delta","response.refusal.delta" -> {
                    val delta=event.getString("delta");chars+=delta.length
                    checkProtocol(chars<=2_000_000)
                    text(event,delta,false)
                }
                "response.output_text.done" -> done(event,"text") { text(event,it,true) }
                "response.refusal.done" -> done(event,"refusal") { text(event,it,true) }
                "response.output_item.done" -> {
                    val item=event.getJSONObject("item")
                    checkProtocol(item.optString("type") != "function_call_output")
                    summaryItem(item)
                    if(item.getString("type")=="function_call") {
                        val value = call(item)
                        val previous = calls.putIfAbsent(value.id.value, value)
                        checkProtocol(previous == null || sameCall(previous, value))
                    }
                }
                "response.completed" -> {
                    val response=event.getJSONObject("response")
                    checkProtocol(response.getString("status")=="completed")
                    val out=response.getJSONArray("output")
                    try {
                        for (i in 0 until out.length()) {
                            checkProtocol(out.getJSONObject(i).optString("type") != "function_call_output")
                            ResponsesSearchProtocol.validateItem(out.getJSONObject(i))
                        }
                    } catch (_: IllegalArgumentException) { throw ModelRequestException(ModelFailure.PROTOCOL) }
                    val confirmed=linkedMapOf<String,ToolInvocation>()
                    for(i in 0 until out.length()) {
                        val item=out.getJSONObject(i)
                        if(item.getString("type")=="function_call") {
                            val value = call(item)
                            checkProtocol(confirmed.put(value.id.value, value) == null)
                        }
                    }
                    checkProtocol(calls.all { (id, value) -> confirmed[id]?.let { sameCall(value, it) } == true })
                    calls.clear(); calls.putAll(confirmed)
                    usage=ProviderUsage.responses(response)
                    for(i in 0 until out.length()) {
                        val item=out.getJSONObject(i)
                        summaryItem(item)
                        if(item.getString("type")=="message") {
                            val content=item.getJSONArray("content")
                            val full=(0 until content.length()).joinToString("") { j ->
                                val part=content.getJSONObject(j)
                                when(part.getString("type")) {"output_text"->part.getString("text");"refusal"->part.getString("refusal");else->""}
                            }
                            val displayed = ResponsesSearchProtocol.displayText(item, full)
                            checkProtocol(displayed.length<=2_000_000)
                            sink(ModelEvent.TextFinal(item.getString("id"),displayed))
                        }
                    }
                    output=(0 until out.length()).map {
                        val item = out.getJSONObject(it)
                        app.daiyu.android.model.images.FrozenMessageImagePlan.rejectRawImageNode(item)
                        item.toString()
                    }
                    // Tools become executable only after the whole successful terminal is valid.
                    calls.values.forEach { sink(ModelEvent.ToolReady(it)) }
                    // The terminal wire sequence is not ACKed before its derived tail events.
                    usage?.let(sink)
                    if (prefix != null) sink(ModelEvent.ProtocolOutput(prefix + checkNotNull(output)))
                }
                "response.failed","error" -> throw ModelRequestException(ModelFailure.REQUEST)
                "response.incomplete" -> throw ModelRequestException(ModelFailure.INCOMPLETE)
            }
            if (acknowledgedSink != null) {
                val id = responseId ?: throw ModelRequestException(ModelFailure.PROTOCOL)
                val checkpoint = TransportCheckpoint(id, checkNotNull(seq), snapshot(seq))
                if (acknowledgedSink(ModelEventBatch(batch.toList(), checkpoint)) != ModelDeliveryAck.ACCEPTED)
                    throw ModelDeliveryRejectedException()
            }
            acknowledgedSequence = seq ?: acknowledgedSequence
            } catch (failure: Exception) {
                if (failure is ModelDeliveryRejectedException) {
                    // Explicit rejection is NOT an unknown commit. A paused/expired gate may
                    // reject a decoded wire event; restore the last ACKed decoder state.
                    responseId = savedId
                    calls.clear(); calls.putAll(savedCalls)
                    parts.clear(); parts.putAll(savedParts)
                    summaries.clear(); summaries.putAll(savedSummaries)
                    chars = savedChars; output = savedOutput; usage = savedUsage
                } else {
                    // Unknown/failed consumers cannot be replayed safely. Fail closed.
                    poisoned = true
                }
                throw failure
            }
            output==null
        }
        if(cancelled()) throw InterruptedException()
        return output ?: throw ModelRequestException(if (acknowledgedSink != null) ModelFailure.NETWORK else ModelFailure.INCOMPLETE, transportInterrupted = true)
        }
    }
}
