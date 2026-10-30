package app.daiyu.android.storage

import app.daiyu.android.context.CheckpointPreservation
import app.daiyu.android.domain.runtime.ExecutionPhase
import app.daiyu.android.domain.runtime.RunCheckpoint

internal data class RuntimeCheckpointStamp(
    val message: String, val conversation: String, val run: String, val owner: String,
    val process: String, val revision: Long, val phase: String,
) {
    companion object {
        fun of(checkpoint: RunCheckpoint, process: String) = RuntimeCheckpointStamp(
            checkpoint.message.id.value, checkpoint.message.conversationId.value, checkpoint.message.runId.value,
            checkpoint.owner, process, checkpoint.revision, checkpoint.phase.name,
        )
    }
}

/** One entry per OS lease; SQL must prove exact payload equality inside the writing transaction. */
internal class RuntimeCheckpointReuse(private val maxChars: Int = 1_048_576) {
    private class Entry(val stamp: RuntimeCheckpointStamp, val encoded: String, val facts: CheckpointPreservation)
    private var last: Entry? = null

    fun expectedPayload(message: String): String = last?.takeIf { it.stamp.message == message }?.encoded.orEmpty()

    fun preservation(stamp: RuntimeCheckpointStamp, exactPayload: Boolean): CheckpointPreservation? =
        last?.takeIf { exactPayload && it.stamp == stamp }?.facts

    /** Called only after the complete transaction has returned successfully. */
    fun committed(checkpoint: RunCheckpoint, process: String, encoded: String) {
        last = if (checkpoint.phase == ExecutionPhase.TERMINAL || encoded.length > maxChars) null else
            Entry(RuntimeCheckpointStamp.of(checkpoint, process), encoded, CheckpointPreservation.capture(checkpoint))
    }

    fun clear() { last = null }
}
