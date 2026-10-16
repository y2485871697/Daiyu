package app.daiyu.android.voice.duplex

import org.json.JSONObject

/** ASR started is speculative (including echo); only lexical speech confirms a turn. */
internal class DuplexInterruptionGate {
    private var confirmed = false
    private var candidate = ""
    fun started() { confirmed = false; candidate = "" }
    fun transcript(text: String, completed: Boolean, onConfirmed: () -> Unit): String? {
        val current = text.takeIf(::hasSpeech) ?: candidate.takeIf { completed }
        if (current == null || !hasSpeech(current)) return null
        candidate = current
        if (!confirmed) { confirmed = true; onConfirmed() }
        return current
    }
    private fun hasSpeech(text: String) = text.any(Char::isLetterOrDigit)
}

/** Two-pass hypotheses replace, never append; avoid trailing-punctuation flicker. */
internal object DuplexAsrHypothesis {
    fun display(previous: String, incoming: String, completed: Boolean): String {
        if (completed) return incoming.ifEmpty { previous }
        if (incoming.isEmpty()) return previous
        if (strip(previous) == strip(incoming) && incoming.length < previous.length) return previous
        return incoming
    }
    private fun strip(text: String): String = text.dropLastWhile { c ->
        c.isWhitespace() || (!c.isLetterOrDigit() && Character.getType(c) in setOf(
            Character.OTHER_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(),
            Character.CONNECTOR_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt())) || c in "…—～"
    }
}

/** Extracted from DuplexTextStream, without the old logger/config dependencies. */
internal class DuplexTextStream {
    var text = ""; private set
    private var responseId = ""
    fun reset() { text = ""; responseId = "" }
    fun accept(event: JSONObject): String {
        val id = event.optString("response_id")
        // A late first ID must not erase the preceding ID-less deltas.
        if (id.isNotEmpty() && responseId.isNotEmpty() && id != responseId) reset()
        if (id.isNotEmpty()) responseId = id
        val fragment = DoubaoDuplexProtocol.outputText(event)
        if (event.optString("type") == "response.output_text.done") {
            if (fragment.isNotEmpty()) text = fragment
        } else {
            check(text.length + fragment.length <= 100_000) { "duplex_text_limit" }
            text += fragment
        }
        check(text.length <= 100_000) { "duplex_text_limit" }
        return text
    }
}
