package app.daiyu.android.model

import app.daiyu.android.diagnostics.RuntimeDiagnostics
import app.daiyu.android.domain.ModelEvent
import app.daiyu.android.domain.NativeSearchPhase
import org.json.JSONObject

/** Wire metadata and explicit server receipts only; never drives call activity. */
internal class VoiceSearchRequestTrace(protocol: ProviderProtocol, request: JSONObject?) {
    private val requestEpoch = RuntimeDiagnostics.currentEpoch()
    private val span = RuntimeDiagnostics.begin(RuntimeDiagnostics.Stage.VOICE_SEARCH)
    private var receipts = 0L
    private var responseComplete = false
    private var closed = false

    init {
        val code = when {
            request == null -> RuntimeDiagnostics.Code.SEARCH_WIRE_RESUME
            protocol == ProviderProtocol.CHAT_COMPLETIONS && request.optJSONObject("web_search_options") != null ->
                RuntimeDiagnostics.Code.SEARCH_WIRE_CHAT
            protocol == ProviderProtocol.RESPONSES && request.optJSONArray("tools")?.let { tools ->
                (0 until tools.length()).any { tools.optJSONObject(it)?.optString("type") in setOf("web_search", "web_search_preview", "deepseek_web_search") }
            } == true -> RuntimeDiagnostics.Code.SEARCH_WIRE_RESPONSES
            else -> RuntimeDiagnostics.Code.SEARCH_WIRE_NONE
        }
        RuntimeDiagnostics.event(RuntimeDiagnostics.Stage.VOICE_SEARCH, code, sourceEpoch = requestEpoch)
        mark(RuntimeDiagnostics.Code.SEARCH_SEND_ATTEMPTED)
    }

    @Synchronized fun sent() = mark(RuntimeDiagnostics.Code.SEARCH_SEND_COMPLETED)

    @Synchronized fun received(event: ModelEvent) {
        if (closed || event !is ModelEvent.NativeSearch) return
        receipts++
        val code = when (event.phase) {
            NativeSearchPhase.RUNNING -> RuntimeDiagnostics.Code.SEARCH_RUNNING
            NativeSearchPhase.COMPLETED -> RuntimeDiagnostics.Code.SEARCH_COMPLETED
            NativeSearchPhase.FAILED -> RuntimeDiagnostics.Code.SEARCH_FAILED
            NativeSearchPhase.INCOMPLETE -> RuntimeDiagnostics.Code.SEARCH_INCOMPLETE
        }
        mark(code, count = receipts)
    }

    @Synchronized fun completed() { if (!closed) responseComplete = true }

    @Synchronized fun close(cancelled: Boolean) {
        if (closed) return
        if (cancelled) mark(RuntimeDiagnostics.Code.CANCEL_REQUESTED)
        mark(if (responseComplete) RuntimeDiagnostics.Code.SEARCH_RESPONSE_ENDED
            else RuntimeDiagnostics.Code.SEARCH_RESPONSE_INTERRUPTED)
        if (receipts == 0L) mark(RuntimeDiagnostics.Code.SEARCH_NO_RECEIPT)
        closed = true
        span.end()
    }

    private fun mark(code: RuntimeDiagnostics.Code, count: Long = -1) {
        if (closed) return
        // Timing spans are intentionally best-effort (tryLock / bounded active spans).
        // Preserve categorical transport receipts even when no timing span was admitted.
        if (span.id == 0L) RuntimeDiagnostics.event(RuntimeDiagnostics.Stage.VOICE_SEARCH, code,
            count = count, sourceEpoch = requestEpoch)
        else span.mark(code, count = count)
    }

    companion object {
        // Numeric policy only: bit 0 voice, bit 1 native-search enabled, remaining bits protocol.
        fun policy(protocol: ProviderProtocol, options: ProviderRequestOptions, wire: JSONObject? = null) {
            val flags = (protocol.ordinal.toLong() shl 2) or
                (if (options.voiceTextOnly) 1L else 0L) or (if (options.openAiWebSearchEnabled) 2L else 0L)
            RuntimeDiagnostics.event(RuntimeDiagnostics.Stage.VOICE_SEARCH,
                if (wire == null) RuntimeDiagnostics.Code.SEARCH_POLICY_FROZEN else RuntimeDiagnostics.Code.SEARCH_POLICY_WIRE,
                count = flags, size = if (wire == null) -1L else wire.optJSONArray("tools")?.length()?.toLong() ?: 0L)
        }
    }

}
