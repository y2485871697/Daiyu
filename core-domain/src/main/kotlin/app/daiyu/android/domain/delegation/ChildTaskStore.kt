package app.daiyu.android.domain.delegation

import app.daiyu.android.domain.runtime.RunExecutionOwner

enum class ChildTaskStatus { QUEUED, RUNNING, COMPLETED, NOT_EXECUTED, UNKNOWN }

data class ChildTaskDisplay(val agentName: String, val modelId: String, val modelName: String) {
    init {
        require(agentName.isNotBlank() && agentName.length <= 80)
        require(modelId.isNotBlank() && modelId.length <= 4096)
        require(modelName.isNotBlank() && modelName.length <= 4096)
    }
}

data class ChildTaskRecord(
    val owner: ChildRunOwner,
    val parentMessageId: String,
    val parentOwnerId: String,
    val index: Int,
    val profileId: String,
    val prompt: String,
    val status: ChildTaskStatus = ChildTaskStatus.QUEUED,
    val text: String = "",
    val detail: String? = null,
    /** Command CAS revision; execution status is compared separately. */
    val revision: Long = 0,
    val sourceTaskId: String? = null,
    val commandKind: ChildTaskCommandKind? = null,
    val display: ChildTaskDisplay? = null
) {
    init {
        require(revision >= 0)
        require((sourceTaskId == null) == (commandKind == null))
        require(sourceTaskId == null || (sourceTaskId.isNotBlank() && sourceTaskId.length <= 200))
        require(parentMessageId.isNotBlank() && parentMessageId.length <= 200)
        require(parentOwnerId.isNotBlank() && parentOwnerId.length <= 200)
        require(index in 0..7 && profileId.isNotBlank() && profileId.length <= 200)
        require(prompt.isNotBlank() && prompt.length <= 4096 && prompt.toByteArray(Charsets.UTF_8).size <= 16 * 1024)
        require(text.toByteArray(Charsets.UTF_8).size <= 64 * 1024)
        require(detail == null || (detail.isNotBlank() && detail.length <= 200))
        require(if (status == ChildTaskStatus.COMPLETED) text.isNotBlank() && detail == null else text.isEmpty())
    }
}

/** Durable observation, not permission to resume or replay a model request. */
interface ChildTaskStore {
    fun enqueue(parent: RunExecutionOwner, tasks: List<ChildTaskRecord>)
    /** Null only when no durable tombstone exists. Unsupported stores fail closed. */
    fun commandReceipt(command: ChildTaskCommand): ChildTaskCommandReceipt? =
        ChildTaskCommandReceipt.Rejected("child_commands_unsupported")
    fun get(owner: ChildRunOwner): ChildTaskRecord? = null
    /** Atomically CAS source revision, insert successor and write the call tombstone. */
    fun enqueueCommand(parent: RunExecutionOwner, command: ChildTaskCommand, successor: ChildTaskRecord): ChildTaskCommandReceipt =
        ChildTaskCommandReceipt.Rejected("child_commands_unsupported")
    fun started(task: ChildTaskRecord)
    /** Actual request selection; never overwrites identity or grants replay permission. */
    fun selected(task: ChildTaskRecord, display: ChildTaskDisplay?) {}
    fun finished(task: ChildTaskRecord, status: ChildTaskStatus, text: String = "", detail: String? = null)
    fun list(conversationId: String, limit: Int = 20): List<ChildTaskRecord>
}
