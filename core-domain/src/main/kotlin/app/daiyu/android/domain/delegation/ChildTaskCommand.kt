package app.daiyu.android.domain.delegation

import java.nio.ByteBuffer
import java.security.MessageDigest

/** Every command creates a new request; it never edits or replays a provider session. */
enum class ChildTaskCommandKind { APPEND, REPLACE, REDISPATCH }

data class ChildTaskCommand(
    val callId: String,
    val source: ChildTaskRecord,
    val kind: ChildTaskCommandKind,
    val profileId: String,
    val instruction: String,
    val acknowledgePossibleExecution: Boolean = false
) {
    init {
        require(callId.isNotBlank() && callId.length <= 200)
        require(profileId.isNotBlank() && profileId.length <= 200)
        require(instruction.isNotBlank() && instruction.length <= 4096 && instruction.toByteArray().size <= 16384)
    }

    fun fingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val o = source.owner
        listOf(o.conversationId, o.parentRunId, o.taskGroupId, o.generation.toString(), o.childRunId,
            o.assistantMessageId, source.parentMessageId, source.parentOwnerId, source.revision.toString(),
            kind.name, profileId, instruction, acknowledgePossibleExecution.toString()).forEach {
            val bytes = it.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun validateSource(current: ChildTaskRecord): String? {
        if (current.owner != source.owner || current.parentOwnerId != source.parentOwnerId ||
            current.parentMessageId != source.parentMessageId || current.revision != source.revision) return "child_source_stale"
        return when {
            kind == ChildTaskCommandKind.REDISPATCH && current.status !in setOf(ChildTaskStatus.NOT_EXECUTED, ChildTaskStatus.UNKNOWN) -> "child_not_failed"
            kind == ChildTaskCommandKind.APPEND && current.status in setOf(ChildTaskStatus.NOT_EXECUTED, ChildTaskStatus.UNKNOWN) -> "child_append_requires_result"
            (current.status == ChildTaskStatus.UNKNOWN || (kind == ChildTaskCommandKind.REPLACE &&
                current.status in setOf(ChildTaskStatus.QUEUED, ChildTaskStatus.RUNNING))) && !acknowledgePossibleExecution -> "child_execution_confirmation_required"
            else -> null
        }
    }
}

sealed interface ChildTaskCommandReceipt {
    data class Accepted(val task: ChildTaskRecord) : ChildTaskCommandReceipt
    data class Rejected(val code: String) : ChildTaskCommandReceipt
    data class Unconfirmed(val code: String) : ChildTaskCommandReceipt
}
