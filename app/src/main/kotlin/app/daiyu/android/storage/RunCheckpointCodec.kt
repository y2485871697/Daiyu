package app.daiyu.android.storage

import app.daiyu.android.context.ArchiveCodec
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
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
        return JSONObject().put("version", 1).put("message", messages(listOf(value.message)))
            .put("history", messages(value.history)).put("phase", value.phase.name)
            .put("tools", JSONArray().apply { value.tools.forEach { record -> put(JSONObject()
                .put("id", record.invocation.id.value).put("name", record.invocation.name)
                .put("arguments", record.invocation.arguments).put("disposition", record.disposition.name)
                .put("result", record.result ?: JSONObject.NULL)) } })
            .put("outputs", JSONArray().apply { value.outputs.forEach { put(JSONObject()
                .put("id", it.callId.value).put("output", it.output)) } })
            .put("supplements", messages(value.supplements)).put("round", value.round)
            .put("request_unconfirmed", value.requestUnconfirmed).put("message_reconciled", value.messageReconciled)
            .put("owner", value.owner).put("revision", value.revision).toString().also {
                checkEncodedSize(it.toByteArray(Charsets.UTF_8).size, value.message.status == RunStatus.RUNNING)
            }
    }
    fun decode(text: String): RunCheckpoint {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val parser = JSONTokener(text)
        val value = parser.nextValue()
        require(value is JSONObject && parser.nextClean() == '\u0000')
        fields(value, "version", "message", "history", "phase", "tools", "outputs", "supplements", "round",
            "request_unconfirmed", "message_reconciled", "owner", "revision")
        require(integer(value, "version") == 1L)
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
            string(value, "owner"), integer(value, "revision")).also(::validate)
    }
    private fun validate(value: RunCheckpoint) {
        require(value.owner.isNotBlank() && value.owner.length <= 200 && value.revision in 1 until Long.MAX_VALUE)
        require(value.round in 0..100 && value.messageReconciled)
        val m = value.message
        require(m.role == Message.ASSISTANT_ROLE)
        require((m.status == RunStatus.RUNNING) == (value.phase != ExecutionPhase.TERMINAL))
        require(value.history.all { it.conversationId == m.conversationId })
        require(value.supplements.all { it.conversationId == m.conversationId && it.runId == m.runId &&
            it.id != m.id && it.role == Message.USER_ROLE && it.status == RunStatus.COMPLETED &&
            it.error == null && it.historySegments.isEmpty() })
        require(value.tools.size <= 10_000 && value.outputs.size <= 10_000)
        require(value.tools.map { it.invocation.id }.distinct().size == value.tools.size)
        value.tools.forEach { require(it.invocation.id.value.isNotBlank() && it.invocation.id.value.length <= 200 &&
            it.invocation.name.isNotBlank() && it.invocation.name.length <= 200)
            require((it.disposition == ToolDisposition.SUCCEEDED) == (it.result != null)) }
        require(value.outputs.map { it.callId }.distinct().size == value.outputs.size)
        value.outputs.forEach { out -> require(value.tools.any { it.invocation.id == out.callId &&
            it.disposition == ToolDisposition.SUCCEEDED && it.result == out.output }) }
    }
    private fun fields(value: JSONObject, vararg keys: String) { require(value.keys().asSequence().toSet() == keys.toSet()) }
    private fun string(value: JSONObject, key: String) = (value.get(key) as? String) ?: error("checkpoint text required")
    private fun integer(value: JSONObject, key: String): Long {
        val n = value.get(key); require(n is Int || n is Long); return (n as Number).toLong()
    }
    private fun boolean(value: JSONObject, key: String) = (value.get(key) as? Boolean) ?: error("checkpoint boolean required")
}
