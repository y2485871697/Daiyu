package app.daiyu.android.voice.control

import app.daiyu.android.domain.ModelEvent
import app.daiyu.android.domain.NativeSearchPhase

internal data class VoiceNativeSearchState(val searching: Boolean = false, val completion: String? = null)

/** One accepted assistant run; duplicate terminal frames never replay the success animation. */
internal class VoiceNativeSearchActivity {
    private val phases = linkedMapOf<String, NativeSearchPhase>()
    private var serial = 0L
    var state = VoiceNativeSearchState()
        private set
    fun accept(event: ModelEvent.NativeSearch) {
        val previous = phases[event.itemId]
        if (previous == event.phase || previous != null && previous != NativeSearchPhase.RUNNING) return
        if (event.itemId !in phases && phases.size >= 256) return
        phases[event.itemId] = event.phase
        val running = phases.values.any { it == NativeSearchPhase.RUNNING }
        state = VoiceNativeSearchState(running, when {
            running -> null
            event.phase == NativeSearchPhase.COMPLETED -> "${++serial}:${event.itemId}"
            else -> null
        })
    }
}
