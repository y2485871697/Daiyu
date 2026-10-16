package app.daiyu.android.domain.delegation

import app.daiyu.android.domain.runtime.RunExecutionOwner

enum class ChildTaskStatus { QUEUED, RUNNING, COMPLETED, NOT_EXECUTED, UNKNOWN }

data class ChildTaskRecord(
    val owner: ChildRunOwner,
    val parentMessageId: String,
    val parentOwnerId: String,
    val index: Int,
    val profileId: String,
    val prompt: String,
    val status: ChildTaskStatus = ChildTaskStatus.QUEUED,
    val text: String = "",
    val detail: String? = null
) {
    init {
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
    fun started(task: ChildTaskRecord)
    fun finished(task: ChildTaskRecord, status: ChildTaskStatus, text: String = "", detail: String? = null)
    fun list(conversationId: String, limit: Int = 20): List<ChildTaskRecord>
}
