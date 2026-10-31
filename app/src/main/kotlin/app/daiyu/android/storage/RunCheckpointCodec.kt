package app.daiyu.android.storage

import app.daiyu.android.context.ArchiveCodec
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.domain.question.ASK_USER_TOOL_NAME
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Local runtime data only; never part of model protocol or a portable backup. */
internal object RunCheckpointCodec {
    internal const val MAX_BYTES = 64 * 1024 * 1024
    internal const val RECOVERY_RESERVE = 4096
    internal fun checkEncodedSize(bytes: Int, running: Boolean) {
        require(bytes <= MAX_BYTES - if (running) RECOVERY_RESERVE else 0)
    }
    // An optional lease-owned writer reuses only quoted strings, never this tree or
    // validation. Archive normalization and the final UTF-8 limit apply on every call.
    fun encode(value: RunCheckpoint, writer: CheckpointJsonWriter? = null): String {
        validate(value)
        check(MAX_BYTES.toLong() <= ArchiveCodec.MAX_ARCHIVE_BYTES)
        fun messages(rows: List<Message>) = ArchiveCodec.checkpointArray(rows)
        fun projectedHistory(rows: List<Message>): JSONArray {
            require(rows.size <= ArchiveCodec.MAX_MESSAGES)
            return JSONArray().apply { rows.forEach { row ->
                val item = messages(listOf(row)).getJSONObject(0)
                if (row.reportedReasoning.isNotEmpty()) {
                    validateReportedReasoning(row)
                    item.put("reported_reasoning", JSONArray().apply { row.reportedReasoning.forEach {
                        put(ReportedReasoningCodec.encode(it))
                    } })
                }
                put(item)
            } }
        }
        val version = if (value.context?.capacity != null) 10 else if (value.context != null) 9 else if (value.usage.any { it.snapshot.cacheCreationTokens != null }) 8 else if (value.transport == null) 6 else 7
        return JSONObject().put("version", version).put("message", messages(listOf(value.message)))
            // Runtime context is a logical projection: several segments may share one physical message ID.
            .put("history", if (value.context != null) projectedHistory(value.history) else messages(value.history))
            .put("phase", value.phase.name)
            .put("tools", JSONArray().apply { value.tools.forEach { record -> put(JSONObject()
                .put("id", record.invocation.id.value).put("name", record.invocation.name)
                .put("arguments", record.invocation.arguments).put("disposition", record.disposition.name)
                .put("result", record.result ?: JSONObject.NULL)) } })
            .put("outputs", JSONArray().apply { value.outputs.forEach { put(JSONObject()
                .put("id", it.callId.value).put("output", it.output)) } })
            .put("supplements", messages(value.supplements)).put("round", value.round)
            .put("request_unconfirmed", value.requestUnconfirmed).put("message_reconciled", value.messageReconciled)
            .put("owner", value.owner).put("revision", value.revision)
            .put("request", value.request?.let(::encodeRequest) ?: JSONObject.NULL)
            .apply { value.context?.let { context -> put("context", JSONObject()
                .put("input", context.input?.let(::encodeInput) ?: JSONObject.NULL)
                .put("calibration", context.calibration?.let(::encodeInput) ?: JSONObject.NULL)
                .put("pressure", context.pressure?.let(::encodeRequest) ?: JSONObject.NULL)
                .put("summaries", JSONArray().apply { context.summaries.forEach { put(encodeRequest(it)) } })
                .put("compactions", context.compactions).apply { context.capacity?.let { capacity ->
                    put("capacity", JSONObject().put("id", capacity.id).put("created", capacity.created)
                        .put("estimated", capacity.estimatedTokens).put("window", capacity.windowTokens)
                        .put("budget", capacity.budgetTokens).put("segment", capacity.segment))
                } }) } }
            .apply { value.transport?.let { put("transport", JSONObject().put("response_id", it.responseId)
                .put("sequence", it.sequence).put("decoder_state", it.decoderState)) } }
            .put("usage_requests", JSONArray().apply { value.usageRequests.forEach { put(encodeRequest(it)) } })
            .put("usage", JSONArray().apply { value.usage.forEach { record -> put(JSONObject()
                .put("request", encodeRequest(record.request)).put("source", record.source.name)
                .put("input_tokens", record.snapshot.inputTokens ?: JSONObject.NULL)
                .put("output_tokens", record.snapshot.outputTokens ?: JSONObject.NULL)
                .put("cached_tokens", record.snapshot.cachedTokens ?: JSONObject.NULL)
                .apply { if (version >= 8) put("cache_creation_tokens", record.snapshot.cacheCreationTokens ?: JSONObject.NULL) }) } })
            .put("pending", value.pending?.let { JSONObject().put("call_id", it.callId.value) }
                ?: JSONObject.NULL).let { root -> writer?.write(root) ?: root.toString() }.also {
                checkEncodedSize(it.toByteArray(Charsets.UTF_8).size, value.message.status == RunStatus.RUNNING)
            }
    }
    internal data class Decoded(val checkpoint: RunCheckpoint, val recoverySourceOwner: String?)

    fun decode(text: String): RunCheckpoint = decodeWithRecovery(text).checkpoint

    internal fun decodeWithRecovery(text: String): Decoded {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val parser = JSONTokener(text)
        val value = parser.nextValue()
        require(value is JSONObject && parser.nextClean() == '\u0000')
        val version = integer(value, "version")
        require(version in 1L..10L)
        val keys = arrayOf("version", "message", "history", "phase", "tools", "outputs", "supplements", "round",
            "request_unconfirmed", "message_reconciled", "owner", "revision")
        val recoveryOwner = if (value.has("recovery_source_owner")) string(value, "recovery_source_owner") else null
        if (recoveryOwner != null) require(recoveryOwner.isNotBlank() && recoveryOwner.length <= 200 &&
            string(value, "phase") == "TERMINAL" && boolean(value, "message_reconciled") &&
            integer(value, "revision") >= 2 && value.isNull("pending") &&
            string(value, "owner").startsWith("recovery-") && string(value, "owner") != recoveryOwner)
        fields(value, *((when (version) { 1L -> keys; 2L -> keys + "pending"; 3L -> keys + arrayOf("pending", "request");
            4L, 5L -> keys + arrayOf("pending", "request", "usage")
            6L -> keys + arrayOf("pending", "request", "usage", "usage_requests")
            7L -> keys + arrayOf("pending", "request", "usage", "usage_requests", "transport")
            else -> keys + arrayOf("pending", "request", "usage", "usage_requests") +
                (if (version >= 9) arrayOf("context") else emptyArray()) +
                (if (value.has("transport")) arrayOf("transport") else emptyArray()) }) +
            if (recoveryOwner == null) emptyArray() else arrayOf("recovery_source_owner")))
        val context = if (version < 9) null else {
            val row = value.getJSONObject("context")
            fields(row, *(arrayOf("input", "calibration", "pressure", "summaries", "compactions") +
                if (version >= 10) arrayOf("capacity") else emptyArray()))
            val summaries = row.getJSONArray("summaries"); require(summaries.length() <= 100)
            RunContextCheckpoint(if (row.isNull("input")) null else decodeInput(row.getJSONObject("input")),
                if (row.isNull("calibration")) null else decodeInput(row.getJSONObject("calibration")),
                if (row.isNull("pressure")) null else decodeRequest(row.get("pressure")),
                (0 until summaries.length()).map { decodeRequest(summaries.get(it)) },
                integer(row, "compactions").also { require(it in 0..100) }.toInt(),
                if (version < 10) null else row.getJSONObject("capacity").let { capacity ->
                    fields(capacity, "id", "created", "estimated", "window", "budget", "segment")
                    ContextCapacityCheckpoint(string(capacity, "id"), integer(capacity, "created"),
                        integer(capacity, "estimated"), integer(capacity, "window"), integer(capacity, "budget"),
                        integer(capacity, "segment").also { require(it in 0L..50000L) }.toInt())
                })
        }
        val transport = if (version < 7L || !value.has("transport") || value.isNull("transport")) null else {
            val row = value.getJSONObject("transport")
            fields(row, "response_id", "sequence", "decoder_state")
            TransportCheckpoint(string(row, "response_id"), integer(row, "sequence"), string(row, "decoder_state"))
        }
        val pending = if (version == 1L || value.isNull("pending")) null else {
            val p = value.get("pending"); require(p is JSONObject)
            fields(p, "call_id")
            PendingToolCheckpoint(ToolCallId(string(p, "call_id")))
        }
        val request = if (version < 3L || value.isNull("request")) null else decodeRequest(value.get("request"))
        val usageRequests = if (version < 6L) emptyList() else {
            val rows = value.getJSONArray("usage_requests"); require(rows.length() <= 10_000)
            (0 until rows.length()).map { decodeRequest(rows.get(it)) }
        }
        val usage = if (version < 4L) emptyList() else {
            val rows = value.getJSONArray("usage"); require(rows.length() <= 10_000)
            (0 until rows.length()).map { i ->
                val row = rows.getJSONObject(i)
                val usageKeys = arrayOf("request", "input_tokens", "output_tokens", "cached_tokens")
                fields(row, *((if (version < 5L) usageKeys else usageKeys + "source") +
                    if (version >= 8L) arrayOf("cache_creation_tokens") else emptyArray()))
                RequestUsageCheckpoint(decodeRequest(row.get("request")), ModelEvent.Usage(
                    nullableInteger(row, "input_tokens"), nullableInteger(row, "output_tokens"),
                    nullableInteger(row, "cached_tokens"), if (version >= 8L) nullableInteger(row, "cache_creation_tokens") else null),
                    if (version < 5L) UsageRequestSource.MAIN else UsageRequestSource.valueOf(string(row, "source")))
            }
        }
        fun messages(key: String) = ArchiveCodec.decode(value.getJSONArray(key).toString().toByteArray(Charsets.UTF_8))
        fun projectedHistory(): List<Message> {
            val rows = value.getJSONArray("history")
            require(rows.length() <= ArchiveCodec.MAX_MESSAGES)
            return (0 until rows.length()).map { index ->
                val item = JSONObject(rows.getJSONObject(index).toString())
                val reported = if (!item.has("reported_reasoning")) emptyList() else {
                    val parts = item.getJSONArray("reported_reasoning")
                    require(parts.length() in 1..1024)
                    (0 until parts.length()).map { i ->
                        ReportedReasoningCodec.decode(parts.getJSONObject(i))
                    }
                }
                item.remove("reported_reasoning")
                ArchiveCodec.decode(JSONArray().put(item).toString().toByteArray(Charsets.UTF_8))
                    .single().copy(reportedReasoning = reported).also(::validateReportedReasoning)
            }
        }
        val message = messages("message").single()
        val tools = value.getJSONArray("tools")
        if (recoveryOwner != null) require((0 until tools.length()).none {
            tools.getJSONObject(it).optString("disposition") == ToolDisposition.IN_FLIGHT.name })
        val outputs = value.getJSONArray("outputs")
        require(tools.length() <= 10_000 && outputs.length() <= 10_000)
        val round = integer(value, "round"); require(round in 0..100)
        return Decoded(RunCheckpoint(message, if (context != null) projectedHistory() else messages("history"), ExecutionPhase.valueOf(string(value, "phase")),
            (0 until tools.length()).map { i -> val t = tools.getJSONObject(i)
                fields(t, "id", "name", "arguments", "disposition", "result")
                ToolRecord(ToolInvocation(ToolCallId(string(t, "id")), string(t, "name"), string(t, "arguments")),
                    ToolDisposition.valueOf(string(t, "disposition")), if (t.isNull("result")) null else string(t, "result")) },
            (0 until outputs.length()).map { i -> val o = outputs.getJSONObject(i)
                fields(o, "id", "output"); ToolOutput(ToolCallId(string(o, "id")), string(o, "output")) },
            messages("supplements"), round.toInt(), boolean(value, "request_unconfirmed"), boolean(value, "message_reconciled"),
            string(value, "owner"), integer(value, "revision"), pending, request, usage, usageRequests, transport, context).also(::validate), recoveryOwner)
    }
    private fun validate(value: RunCheckpoint) {
        require(value.owner.isNotBlank() && value.owner.length <= 200 && value.revision in 1 until Long.MAX_VALUE)
        require(value.round in 0..100 && value.messageReconciled)
        value.transport?.let { transport ->
            val request = value.request
            require(request != null && request.operation != RequestOperation.OPEN)
            require(transport.decoderState.length in 2..16_000_000)
            val parser = JSONTokener(transport.decoderState)
            val state = parser.nextValue()
            require(state is JSONObject && parser.nextClean() == '\u0000')
            require(string(state, "response_id") == transport.responseId && integer(state, "sequence") == transport.sequence)
        }
        val m = value.message
        require(m.role == Message.ASSISTANT_ROLE)
        value.request?.let { require(value.phase == ExecutionPhase.TERMINAL || it.owner == value.owner) }
        require(value.usageRequests.size <= 10_000)
        require(value.usageRequests.map { it.owner to it.requestId }.distinct().size == value.usageRequests.size)
        value.usageRequests.forEach {
            require(it.operation != RequestOperation.OPEN)
            require(value.phase == ExecutionPhase.TERMINAL || it.owner == value.owner)
        }
        value.context?.let { state ->
            state.capacity?.let { capacity ->
                require(capacity.segment < MessageHistory.expand(listOf(m)).count {
                    it.conversationId == m.conversationId && it.runId == m.runId && it.id == m.id
                })
            }
            require(state.compactions in 0..state.summaries.size)
            val main = value.usageRequests.map { it.owner to it.requestId }.toSet()
            listOfNotNull(state.input, state.calibration).forEach { input ->
                require(input.request in value.usageRequests)
            }
            state.pressure?.let { require(it in value.usageRequests) }
            require(state.summaries.size <= 100 && state.summaries.map { it.owner to it.requestId }.distinct().size == state.summaries.size)
            state.summaries.forEach { require(it.operation != RequestOperation.OPEN &&
                (value.phase == ExecutionPhase.TERMINAL || it.owner == value.owner) && (it.owner to it.requestId) !in main) }
            value.usage.filter { it.source == UsageRequestSource.SUMMARY && it.request.owner == value.owner }
                .forEach { require(it.request in state.summaries) }
        }
        require(value.usage.size <= 10_000)
        require(value.usage.map { it.request.owner to it.request.requestId }.distinct().size == value.usage.size)
        value.usage.forEach {
            require(value.phase == ExecutionPhase.TERMINAL || it.request.owner == value.owner ||
                it.source == UsageRequestSource.SUMMARY)
            require(it.request.operation != RequestOperation.OPEN)
        }
        require((m.status == RunStatus.RUNNING) == (value.phase != ExecutionPhase.TERMINAL))
        require(value.history.all { it.conversationId == m.conversationId })
        require(value.supplements.map { it.id }.distinct().size == value.supplements.size)
        require(value.supplements.all { it.conversationId == m.conversationId && it.runId == m.runId &&
            it.id != m.id && it.role == Message.USER_ROLE && it.status == RunStatus.COMPLETED &&
            it.error == null && it.historySegments.isEmpty() && it.blocks.none { block -> block is MessageBlock.Tool } })
        require(value.tools.size <= 10_000 && value.outputs.size <= 10_000)
        require(value.tools.map { it.invocation.id }.distinct().size == value.tools.size)
        value.tools.forEach { require(it.invocation.id.value.isNotBlank() && it.invocation.id.value.length <= 200 &&
            it.invocation.name.isNotBlank() && it.invocation.name.length <= 200)
            require(hasResult(it.disposition) == (it.result != null)) }
        require((value.phase == ExecutionPhase.WAITING_USER) == (value.pending != null))
        value.pending?.let { pending ->
            val record = value.tools.singleOrNull { it.invocation.id == pending.callId }
            require(record != null && record.invocation.name == ASK_USER_TOOL_NAME &&
                record.disposition == ToolDisposition.IN_FLIGHT)
            require(value.tools.count { it.disposition == ToolDisposition.IN_FLIGHT } == 1)
        }
        require(value.outputs.map { it.callId }.distinct().size == value.outputs.size)
        value.outputs.forEach { out -> require(value.tools.any { it.invocation.id == out.callId &&
            hasResult(it.disposition) && it.result == out.output }) }
    }
    private fun validateReportedReasoning(row: Message) = MessageHistory.validateSegment(
        MessageHistorySegment(row.id, row.role, row.blocks, row.protocolItems, row.createdAt, row.reportedReasoning))
    private fun hasResult(disposition: ToolDisposition) = disposition in setOf(
        ToolDisposition.SUCCEEDED, ToolDisposition.FAILED, ToolDisposition.NOT_EXECUTED)
    private fun encodeInput(value: RequestInputCheckpoint) = JSONObject()
        .put("request", encodeRequest(value.request)).put("local", value.localTokens)
        .put("billed", value.billedTokens ?: JSONObject.NULL).put("history_local", value.historyTokens)
    private fun decodeInput(value: JSONObject): RequestInputCheckpoint {
        fields(value, "request", "local", "billed", "history_local")
        return RequestInputCheckpoint(decodeRequest(value.get("request")), integer(value, "local"), nullableInteger(value, "billed"), integer(value, "history_local"))
    }
    private fun encodeRequest(value: RequestCheckpoint) = JSONObject()
        .put("request_id", value.requestId).put("attempt_id", value.attemptId)
        .put("owner", value.owner).put("operation", value.operation.name)
    private fun decodeRequest(raw: Any): RequestCheckpoint {
        require(raw is JSONObject)
        fields(raw, "request_id", "attempt_id", "owner", "operation")
        return RequestCheckpoint(string(raw, "request_id"), string(raw, "attempt_id"),
            string(raw, "owner"), RequestOperation.valueOf(string(raw, "operation")))
    }
    private fun nullableInteger(value: JSONObject, key: String): Long? =
        if (value.isNull(key)) null else integer(value, key)
    private fun fields(value: JSONObject, vararg keys: String) { require(value.keys().asSequence().toSet() == keys.toSet()) }
    private fun string(value: JSONObject, key: String) = (value.get(key) as? String) ?: error("checkpoint text required")
    private fun integer(value: JSONObject, key: String): Long {
        val n = value.get(key); require(n is Int || n is Long); return (n as Number).toLong()
    }
    private fun boolean(value: JSONObject, key: String) = (value.get(key) as? Boolean) ?: error("checkpoint boolean required")
}
