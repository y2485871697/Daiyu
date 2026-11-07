package app.daiyu.android.model

import app.daiyu.android.domain.ModelEvent

/** Only adjacent text deltas are coalesced. Tool, usage and terminal events remain ordered barriers. */
internal class StreamEventBuffer(
    private val sink: (ModelEvent) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
    private val maxCharacters: Int = 512,
    private val maxNanos: Long = 250_000_000L,
) {
    private var pending: ModelEvent? = null
    private var started = 0L
    private var characters = 0
    private var failure: Exception? = null
    private var cumulativeItem: String? = null
    private var cumulativeLength = 0

    init { require(maxCharacters > 0 && maxNanos > 0) }

    fun accept(event: ModelEvent) {
        failure?.let { throw it }
        val previous = pending
        val merged = when {
            previous is ModelEvent.TextDelta && event is ModelEvent.TextDelta && previous.itemId == event.itemId ->
                previous.copy(text = previous.text + event.text)
            previous is ModelEvent.ReasoningDelta && event is ModelEvent.ReasoningDelta &&
                previous.itemId == event.itemId && previous.cumulative == event.cumulative &&
                (!event.cumulative || event.text.startsWith(previous.text)) ->
                if (event.cumulative) event else previous.copy(text = previous.text + event.text)
            else -> null
        }
        if (merged == null) flush()
        when (event) {
            is ModelEvent.TextDelta, is ModelEvent.ReasoningDelta -> {
                if (pending == null) started = nanoTime()
                pending = merged ?: event
                characters += when (event) {
                    is ModelEvent.TextDelta -> event.text.length
                    is ModelEvent.ReasoningDelta -> if (event.cumulative) {
                        val added = if (cumulativeItem == event.itemId)
                            (event.text.length - cumulativeLength).coerceAtLeast(0) else event.text.length
                        cumulativeItem = event.itemId
                        cumulativeLength = event.text.length
                        added
                    } else event.text.length
                    else -> 0
                }
                if (characters >= maxCharacters || nanoTime() - started >= maxNanos) flush()
            }
            else -> emit(event)
        }
    }

    fun flush() {
        failure?.let { throw it }
        val event = pending ?: return
        pending = null
        characters = 0
        emit(event)
    }

    fun discard() { pending = null; characters = 0 }

    private fun emit(event: ModelEvent) {
        try { sink(event) } catch (error: Exception) {
            failure = error
            discard()
            throw error
        }
    }
}
