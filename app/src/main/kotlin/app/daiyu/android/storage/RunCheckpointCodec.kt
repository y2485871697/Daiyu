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
    fun encode(value: RunCheckpoint): String {
        validate(value)
        fun messages(rows: List<Message>) = JSONArray(String(ArchiveCodec.encode(rows), Charsets.UTF_8))
        return JSONObject().put("version", 4).put("message", messages(listOf(value.message)))
            .put("history", messages(value.history)).put("phase", value.phase.name)
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
            .put("usage", JSONArray().apply { value.usage.forEach { record -> put(JSONObject()
                .put("request", encodeRequest(record.request))
                .put("input_tokens", record.snapshot.inputTokens ?: JSONObject.NULL)
                .put("output_tokens", record.snapshot.outputTokens ?: JSONObject.NULL)
                .put("cached_tokens", record.snapshot.cachedTokens ?: JSONObject.NULL)) } })
            .put("pending", value.pending?.let { JSONObject().put("call_id", it.callId.value) }
                ?: JSONObject.NULL).toString().also {
                checkEncodedSize(it.toByteArray(Charsets.UTF_8).size, value.message.status == RunStatus.RUNNING)
            }
    }
    fun decode(text: String): RunCheckpoint {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val parser = JSONTokener(text)
        val value = parser.nextValue()
        require(value is JSONObject && parser.nextClean() == '\u0000')
        val version = integer(value, "version")
        require(version in 1L..4L)
        val keys = arrayOf("version", "message", "history", "phase", "tools", "outputs", "supplements", "round",
            "request_unconfirmed", "message_reconciled", "owner", "revision")
        fields(value, *(when (version) { 1L -> keys; 2L -> keys + "pending"; 3L -> keys + arrayOf("pending", "request");
            else -> keys + arrayOf("pending", "request", "usage") }))
        val pending = if (version == 1L || value.isNull("pending")) null else {
            val p = value.get("pending"); require(p is JSONObject)
            fields(p, "call_id")
            PendingToolCheckpoint(ToolCallId(string(p, "call_id")))
        }
        val request = if (version < 3L || value.isNull("request")) null else decodeRequest(value.get("request"))
        val usage = if (version < 4L) emptyList() else {
            val rows = value.getJSONArray("usage"); require(rows.length() <= 10_000)
            (0 until rows.length()).map { i ->
                val row = rows.getJSONObject(i)
                fields(row, "request", "input_tokens", "output_tokens", "cached_tokens")
                RequestUsageCheckpoint(decodeRequest(row.get("request")), ModelEvent.Usage(
                    nullableInteger(row, "input_tokens"), nullableInteger(row, "output_tokens"),
                    nullableInteger(row, "cached_tokens")))
            }
        }
        fun messages(key: String) = ArchiveCodec.decode(value.getJSONArray(key).toString().toByteArray(Charsets.UTF_8))
        val message = messages("message").single()
        val tools = value.getJSONArray("tools")
        val outputs = value.getJSONArray("outputs")
        require(tools.length() <= 10_000 && outputs.length() <= 10_000)
        val round = integer(value, "round"); require(round in 0..100)
        return RunCheckpoint(message, messages("history"), ExecutionPhase.valueOf(string(value, "phase")),
            (0 until tools.length()).map { i -> val t = tools.getJSONObject(i)
                fields(t, "id", "name", "arguments", "disposition", "result")
                ToolRecord(ToolInvocation(ToolCallId(string(t, "id")), string(t, "name"), string(t, "arguments")),
                    ToolDisposition.valueOf(string(t, "disposition")), if (t.isNull("result")) null else string(t, "result")) },
            (0 until outputs.length()).map { i -> val o = outputs.getJSONObject(i)
                fields(o, "id", "output"); ToolOutput(ToolCallId(string(o, "id")), string(o, "output")) },
            messages("supplements"), round.toInt(), boolean(value, "request_unconfirmed"), boolean(value, "message_reconciled"),
            string(value, "owner"), integer(value, "revision"), pending, request, usage).also(::validate)
    }
    private fun validate(value: RunCheckpoint) {
        require(value.owner.isNotBlank() && value.owner.length <= 200 && value.revision in 1 until Long.MAX_VALUE)
        require(value.round in 0..100 && value.messageReconciled)
        val m = value.message
        require(m.role == Message.ASSISTANT_ROLE)
        value.request?.let { require(value.phase == ExecutionPhase.TERMINAL || it.owner == value.owner) }
        require(value.usage.size <= 10_000)
        require(value.usage.map { it.request.owner to it.request.requestId }.distinct().size == value.usage.size)
        value.usage.forEach { require(value.phase == ExecutionPhase.TERMINAL || it.request.owner == value.owner) }
        require((m.status == RunStatus.RUNNING) == (value.phase != ExecutionPhase.TERMINAL))
        require(value.history.all { it.conversationId == m.conversationId })
        require(value.supplements.all { it.conversationId == m.conversationId && it.runId == m.runId &&
            it.id != m.id && it.role == Message.USER_ROLE && it.status == RunStatus.COMPLETED &&
            it.error == null && it.historySegments.isEmpty() })
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
    private fun hasResult(disposition: ToolDisposition) = disposition in setOf(
        ToolDisposition.SUCCEEDED, ToolDisposition.FAILED, ToolDisposition.NOT_EXECUTED)
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
