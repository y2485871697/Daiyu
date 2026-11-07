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

    fun readBuffered(reader: BufferedReader, cancelled: () -> Boolean, sink: (ModelEvent) -> Unit): List<String> {
        var deliveryFailure: Exception? = null
        try {
            return State(bufferDeltas = true).read(reader, cancelled, { event ->
                try { sink(event) } catch (failure: Exception) { deliveryFailure = failure; throw failure }
            })
        } catch (failure: org.json.JSONException) {
            if (failure === deliveryFailure) throw failure
            throw ModelRequestException(ModelFailure.PROTOCOL)
        }
    }

    /** Working deltas are bounded; only an accepted batch advances the resumable cursor. */
    class State(private val bufferDeltas: Boolean = false, private val nanoTime: () -> Long = System::nanoTime) {
        var responseId: String? = null
            private set
        var acknowledgedSequence: Long? = null
            private set
        private var output:List<String>?=null
        private var usage:ModelEvent.Usage?=null
        private val calls=linkedMapOf<String,ToolInvocation>()
        private val parts=mutableMapOf<String,java.util.SortedMap<Int,String>>()
        private val summaries=mutableMapOf<String,java.util.SortedMap<Int,String>>()
        private val reasoningIds = linkedMapOf<Int, String>()
        private val reasoningAliases = linkedMapOf<String, String>()
        private val pendingReasoning = linkedMapOf<String, String>()
        private var chars=0
        private var poisoned = false
        val canResume: Boolean get() = !poisoned && output == null && responseId != null && acknowledgedSequence != null
        private fun snapshot(sequence: Long): String = JSONObject().apply {
            put("response_id", responseId); put("sequence", sequence); put("chars", chars)
            fun map(source: Map<String, java.util.SortedMap<Int,String>>) = JSONObject().apply {
                source.forEach { (id, values) -> put(id, JSONObject().apply { values.forEach { (k,v) -> put(k.toString(),v) } }) }
            }
            put("parts", map(parts)); put("summaries", map(summaries))
            put("reasoning_ids", JSONObject().apply { reasoningIds.forEach { (index, id) -> put(index.toString(), id) } })
            put("reasoning_aliases", JSONObject().apply { reasoningAliases.forEach { (raw, id) -> put(raw, id) } })
            put("pending_reasoning", JSONObject().apply { pendingReasoning.forEach { (id, item) -> put(id, item) } })
            put("calls", org.json.JSONArray(calls.values.map { call -> JSONObject().put("id",call.id.value).put("name",call.name).put("arguments",call.arguments) }))
            output?.let { put("output",org.json.JSONArray(it)) }
        }.toString()

        private data class Memory(val id: String?, val sequence: Long?, val output: List<String>?,
            val usage: ModelEvent.Usage?, val calls: Map<String, ToolInvocation>,
            val parts: Map<String, java.util.SortedMap<Int, String>>,
            val summaries: Map<String, java.util.SortedMap<Int, String>>,
            val reasoningIds: Map<Int, String>, val aliases: Map<String, String>,
            val pendingReasoning: Map<String, String>, val chars: Int)
        private fun capture(): Memory {
            fun copy(values: Map<String, java.util.SortedMap<Int, String>>) = values.mapValues { java.util.TreeMap(it.value) }
            return Memory(responseId, acknowledgedSequence, output, usage, LinkedHashMap(calls), copy(parts), copy(summaries),
                LinkedHashMap(reasoningIds), LinkedHashMap(reasoningAliases), LinkedHashMap(pendingReasoning), chars)
        }
        private fun restore(saved: Memory) {
            responseId = saved.id; acknowledgedSequence = saved.sequence; output = saved.output; usage = saved.usage
            calls.clear(); calls.putAll(saved.calls)
            parts.clear(); parts.putAll(saved.parts)
            summaries.clear(); summaries.putAll(saved.summaries)
            reasoningIds.clear(); reasoningIds.putAll(saved.reasoningIds)
            reasoningAliases.clear(); reasoningAliases.putAll(saved.aliases)
            pendingReasoning.clear(); pendingReasoning.putAll(saved.pendingReasoning)
            chars = saved.chars
        }

        fun read(reader:BufferedReader, cancelled:()->Boolean, legacySink:(ModelEvent)->Unit = {},
                 prefix: List<String>? = null,
                 acknowledgedSink: ((ModelEventBatch)->ModelDeliveryAck)? = null):List<String> {
        check(!poisoned && output == null)
        val batch = mutableListOf<ModelEvent>()
        val sink: (ModelEvent)->Unit = batch::add
        val pending = mutableListOf<ModelEvent>()
        var base: Memory? = null
        var tail: Long? = null
        var pendingKey: String? = null
        var pendingCharacters = 0
        var pendingFrames = 0
        var startedNanos = 0L
        var deliveryFailure: Exception? = null
        fun clearPending() { pending.clear(); base = null; tail = null; pendingKey = null; pendingCharacters = 0; pendingFrames = 0 }
        fun discardPending() { base?.let(::restore); clearPending() }
        fun flushPending() {
            val saved = base ?: return
            if (cancelled()) { discardPending(); throw InterruptedException() }
            try {
                if (acknowledgedSink != null) {
                    val sequence = checkNotNull(tail)
                    val checkpoint = TransportCheckpoint(responseId ?: throw ModelRequestException(ModelFailure.PROTOCOL),
                        sequence, snapshot(sequence))
                    if (acknowledgedSink(ModelEventBatch(pending.toList(), checkpoint)) != ModelDeliveryAck.ACCEPTED)
                        throw ModelDeliveryRejectedException()
                } else pending.forEach(legacySink)
                acknowledgedSequence = tail ?: acknowledgedSequence
                clearPending()
            } catch (failure: Exception) {
                deliveryFailure = failure
                if (failure is ModelDeliveryRejectedException) restore(saved) else poisoned = true
                clearPending()
                throw failure
            }
        }
        fun appendPending(event: ModelEvent) {
            val last = pending.lastOrNull()
            val replacement = when {
                event is ModelEvent.TextFinal && last is ModelEvent.TextFinal && event.itemId == last.itemId &&
                    event.text.startsWith(last.text) -> event
                event is ModelEvent.ReasoningDelta && last is ModelEvent.ReasoningDelta && event.cumulative &&
                    last.cumulative && event.itemId == last.itemId && event.text.startsWith(last.text) -> event
                else -> null
            }
            if (replacement == null) pending.add(event) else pending[pending.lastIndex] = replacement
        }
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
        fun reasoningId(rawId: String, outputIndex: Int?): String {
            val known = reasoningAliases[rawId]
            val indexed = outputIndex?.let(reasoningIds::get)
            checkProtocol(known == null || indexed == null || known == indexed)
            val id = indexed ?: known ?: rawId
            if (outputIndex != null) {
                checkProtocol(reasoningIds.none { (index, value) -> index != outputIndex && value == id })
                reasoningIds[outputIndex] = id
            }
            reasoningAliases[rawId] = id
            return id
        }
        fun outputIndex(event: JSONObject): Int? =
            if (!event.has("output_index") || event.isNull("output_index")) null else index(event, "output_index")
        fun summary(event:JSONObject, value:String, final:Boolean) {
            val id=reasoningId(event.getString("item_id"), outputIndex(event))
            val index=index(event, "summary_index", index(event, "content_index"))
            checkProtocol(index>=0)
            val content=summaries.getOrPut(id) {sortedMapOf()}
            content[index]=if(final) value.takeUnless { it.isBlank() && content[index]?.isNotBlank() == true }
                ?: content.getValue(index) else content.getOrDefault(index,"")+value
            val combined=content.values.joinToString("")
            checkProtocol(combined.length<=2_000_000)
            sink(ModelEvent.ReasoningDelta(id,combined, cumulative = true))
        }
        fun summaryItem(item:JSONObject, outputIndex: Int? = null) {
            if(item.getString("type")!="reasoning") return
            val id = reasoningId(item.getString("id"), outputIndex)
            val parts = if (!item.has("summary") || item.isNull("summary")) null else item.getJSONArray("summary")
            if (parts == null || parts.length() == 0) {
                summaries[id]?.let { sink(ModelEvent.ReasoningFinal(id, it.values.joinToString(""))) }
                return
            }
            val content=sortedMapOf<Int,String>()
            for(i in 0 until parts.length()) {
                val part=parts.getJSONObject(i)
                if (part.optString("type") == "summary_text") content[i]=part.getString("text")
            }
            // Empty/redacted terminal metadata closes the item; it is not a text correction.
            val finalContent = if (content.values.none { it.isNotBlank() }) summaries[id] ?: content else content
            val combined=finalContent.values.joinToString("")
            checkProtocol(combined.length<=2_000_000)
            summaries[id]=finalContent
            sink(ModelEvent.ReasoningFinal(id,combined))
        }
        try {
        SseReader.read(reader) { data ->
            if(cancelled()) throw InterruptedException()
            if(data=="[DONE]") { flushPending(); return@read false }
            val event=try {JSONObject(data)} catch (_:org.json.JSONException) {throw ModelRequestException(ModelFailure.PROTOCOL)}
            if(!event.has("type")) throw ModelRequestException(ModelFailure.PROTOCOL)
            val seq = if(event.has("sequence_number")) {
                val raw = event.get("sequence_number")
                checkProtocol(raw is Int || raw is Long)
                (raw as Number).toLong()
            } else null
            val seen = tail ?: acknowledgedSequence
            if (seq != null && seen != null && seq <= seen) return@read true
            if (acknowledgedSink != null) checkProtocol(seq != null && seq >= 0)
            val type = event.getString("type")
            val deltaFrame = bufferDeltas && type in setOf("response.output_text.delta", "response.refusal.delta", "response.reasoning_summary_text.delta")
            val key = if (deltaFrame) "$type:${event.optString("item_id")}:${event.optInt("content_index", 0)}:${event.optInt("summary_index", 0)}" else null
            if (!deltaFrame || key != pendingKey) flushPending()
            batch.clear()
            val saved = capture()
            val response = event.optJSONObject("response")
            if (acknowledgedSink != null && response != null && response.has("id")) {
                val id = response.getString("id")
                checkProtocol(id.matches(Regex("resp_[A-Za-z0-9_-]{1,180}")))
                checkProtocol(responseId == null || responseId == id)
                responseId = id
            }
            try {
            when(event.getString("type")) {
                "response.output_item.added" -> {
                    val item = event.getJSONObject("item")
                    if (item.optString("type") == "reasoning") outputIndex(event)?.let {
                        reasoningId(item.getString("id"), it)
                    }
                }
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
                    // Unindexed, renamed summaries need the complete output to prove identity.
                    if (item.optString("type") != "reasoning" || outputIndex(event) != null ||
                        item.optString("id") in reasoningAliases || summaries.isEmpty())
                        summaryItem(item, outputIndex(event))
                    else pendingReasoning[item.getString("id")] = item.toString()
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
                    val finalReasoning = (0 until out.length()).map { it to out.getJSONObject(it) }
                        .filter { it.second.optString("type") == "reasoning" }
                    checkProtocol(finalReasoning.map { it.second.getString("id") }.let { ids ->
                        ids.all { it.isNotBlank() } && ids.distinct().size == ids.size
                    })
                    // Indexed identities take precedence. For old gateways without indices,
                    // only a unique exact match among unmatched items in this response is usable.
                    finalReasoning.filter { (i, item) -> i in reasoningIds || item.optString("id") in reasoningAliases }
                        .forEach { (i, item) -> reasoningId(item.getString("id"), i) }
                    val unmatched = finalReasoning.filter { (_, item) -> item.getString("id") !in reasoningAliases }
                    val claimed = finalReasoning.mapNotNull { (_, item) -> reasoningAliases[item.getString("id")] }.toSet()
                    val available = summaries.filterKeys { it !in claimed }
                    fun finalSummary(item: JSONObject): String? {
                        val values = item.optJSONArray("summary") ?: return null
                        return (0 until values.length()).mapNotNull { j -> values.optJSONObject(j)?.takeIf {
                            it.optString("type") == "summary_text"
                        }?.optString("text") }.joinToString("").takeIf { it.isNotBlank() }
                    }
                    if (unmatched.size == available.size) {
                        val plan = unmatched.map { (i, item) ->
                            val text = finalSummary(item)
                            val matches = available.filterValues { it.values.joinToString("") == text }
                            val candidate = matches.keys.singleOrNull()?.takeIf { oldId ->
                                text != null && finalReasoning.count { finalSummary(it.second) == text } == 1 &&
                                    summaries.values.count { it.values.joinToString("") == text } == 1 &&
                                    finalReasoning.none { it.second.getString("id") == oldId } &&
                                    reasoningIds.none { (slot, id) -> id == oldId && slot != i }
                            }
                            Triple(i, item.getString("id"), candidate)
                        }
                        if (plan.all { it.third != null } && plan.map { it.third }.distinct().size == plan.size) {
                            plan.forEach { (i, rawId, displayId) ->
                                reasoningAliases[rawId] = checkNotNull(displayId)
                                reasoningId(rawId, i)
                            }
                        }
                    }
                    for(i in 0 until out.length()) {
                        val item=out.getJSONObject(i)
                        if (item.optString("type") == "reasoning" && finalSummary(item) == null)
                            pendingReasoning[item.getString("id")]?.let { summaryItem(JSONObject(it), i) }
                        summaryItem(item, i)
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
                    val terminalIds = finalReasoning.map { it.second.getString("id") }.toSet()
                    pendingReasoning.filterKeys { it !in terminalIds }.values.forEach { summaryItem(JSONObject(it)) }
                    pendingReasoning.clear()
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
                "response.failed","error" -> throw ProviderHttpFailure.event(
                    event.optJSONObject("response")?.optJSONObject("error") ?: event.optJSONObject("error") ?: event)
                "response.incomplete" -> throw ModelRequestException(ModelFailure.INCOMPLETE)
            }
            if (base == null) { base = saved; startedNanos = nanoTime() }
            tail = seq
            pendingKey = key
            batch.forEach(::appendPending)
            pendingFrames++
            if (deltaFrame) pendingCharacters += event.getString("delta").length
            if (!deltaFrame || pendingCharacters >= 512 || pendingFrames >= 512 || nanoTime() - startedNanos >= 250_000_000L)
                flushPending()
            } catch (failure: Exception) {
                if (failure !== deliveryFailure) {
                    restore(saved)
                    if (cancelled() || failure is InterruptedException || failure is java.util.concurrent.CancellationException) discardPending()
                    else {
                        try { flushPending() } catch (delivery: Exception) {
                            if (delivery !== failure) delivery.addSuppressed(failure)
                            throw delivery
                        }
                        poisoned = true
                    }
                }
                throw failure
            }
            output==null
        }
        flushPending()
        } catch (failure: Exception) {
            if (failure !== deliveryFailure) {
                if (cancelled() || failure is InterruptedException || failure is java.util.concurrent.CancellationException) discardPending()
                else try { flushPending() } catch (delivery: Exception) {
                    if (delivery !== failure) delivery.addSuppressed(failure)
                    throw delivery
                }
            }
            throw failure
        }
        if(cancelled()) throw InterruptedException()
        return output ?: throw ModelRequestException(if (acknowledgedSink != null) ModelFailure.NETWORK else ModelFailure.INCOMPLETE, transportInterrupted = true)
        }
    }
}
