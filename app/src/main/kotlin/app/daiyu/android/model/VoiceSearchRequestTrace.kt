package app.daiyu.android.model

import app.daiyu.android.diagnostics.RuntimeDiagnostics
import app.daiyu.android.domain.ModelEvent
import app.daiyu.android.domain.NativeSearchPhase
import org.json.JSONObject

/** Wire metadata and explicit server receipts only; never drives call activity. */
internal class VoiceSearchRequestTrace(protocol: ProviderProtocol, request: JSONObject?) {
    private val span = RuntimeDiagnostics.begin(RuntimeDiagnostics.Stage.VOICE_SEARCH)
    private var receipts = 0L
    private var responseComplete = false

    init {
        val code = when {
            request == null -> RuntimeDiagnostics.Code.SEARCH_WIRE_RESUME
            protocol == ProviderProtocol.CHAT_COMPLETIONS && request.optJSONObject("web_search_options") != null ->
                RuntimeDiagnostics.Code.SEARCH_WIRE_CHAT
            protocol == ProviderProtocol.RESPONSES && request.optJSONArray("tools")?.let { tools ->
                (0 until tools.length()).any { tools.optJSONObject(it)?.optString("type") in setOf("web_search", "web_search_preview") }
            } == true -> RuntimeDiagnostics.Code.SEARCH_WIRE_RESPONSES
            else -> RuntimeDiagnostics.Code.SEARCH_WIRE_NONE
        }
        span.mark(code)
        span.mark(RuntimeDiagnostics.Code.SEARCH_SEND_ATTEMPTED)
    }

    fun sent() = span.mark(RuntimeDiagnostics.Code.SEARCH_SEND_COMPLETED)

    fun received(event: ModelEvent) {
        if (event !is ModelEvent.NativeSearch) return
        receipts++
        val code = when (event.phase) {
            NativeSearchPhase.RUNNING -> RuntimeDiagnostics.Code.SEARCH_RUNNING
            NativeSearchPhase.COMPLETED -> RuntimeDiagnostics.Code.SEARCH_COMPLETED
            NativeSearchPhase.FAILED -> RuntimeDiagnostics.Code.SEARCH_FAILED
            NativeSearchPhase.INCOMPLETE -> RuntimeDiagnostics.Code.SEARCH_INCOMPLETE
        }
        span.mark(code, count = receipts)
    }

    fun completed() { responseComplete = true }

    fun close(cancelled: Boolean) {
        if (cancelled) span.mark(RuntimeDiagnostics.Code.CANCEL_REQUESTED)
        span.mark(if (responseComplete) RuntimeDiagnostics.Code.SEARCH_RESPONSE_ENDED
            else RuntimeDiagnostics.Code.SEARCH_RESPONSE_INTERRUPTED)
        if (receipts == 0L) span.mark(RuntimeDiagnostics.Code.SEARCH_NO_RECEIPT)
        span.end()
    }
}
