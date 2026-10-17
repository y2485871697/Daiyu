package app.daiyu.android.ui

import app.daiyu.android.domain.Message
import app.daiyu.android.domain.MessageBlock
import app.daiyu.android.domain.ToolInvocation
import app.daiyu.android.domain.runtime.ToolDisposition
import app.daiyu.android.storage.ConversationDatabase
import app.daiyu.android.storage.SQLiteRuntimeStore

/** Read-only projection of the Runner's durable receipt disposition, never tool/model JSON text. */
internal object RetainedToolOutcomes {
    private data class Owner(val conversation: String, val run: String, val message: String)
    private data class Result(val invocation: ToolInvocation, val text: String?)

    fun reader(database: ConversationDatabase, messages: List<Message>): (Message, MessageBlock.Tool) -> Boolean? {
        val receipts = mutableMapOf<Owner, Map<Result, Boolean>>()
        for (physical in messages.filter { it.role == Message.ASSISTANT_ROLE &&
            (it.blocks.any { block -> block is MessageBlock.Tool } ||
                it.historySegments.any { segment -> segment.blocks.any { block -> block is MessageBlock.Tool } }) }) {
            val snapshot = runCatching {
                SQLiteRuntimeStore.readCheckpointSnapshot(database.readableDatabase, physical.id.value)
            }.getOrNull() ?: continue
            if (snapshot.message.id != physical.id || snapshot.message.runId != physical.runId ||
                snapshot.message.conversationId != physical.conversationId) continue
            val results = snapshot.tools.mapNotNull { record ->
                val error = when (record.disposition) {
                    ToolDisposition.FAILED -> true // Confirmed(isError=true) is persisted as FAILED by Runner.
                    ToolDisposition.SUCCEEDED -> false
                    else -> null // In particular UNKNOWN is not relabelled or made replayable here.
                }
                error?.let { Result(record.invocation, record.result) to it }
            }.toMap()
            val ids = listOf(physical.id) + physical.historySegments.filter {
                it.role == Message.ASSISTANT_ROLE
            }.map { it.messageId }
            for (id in ids) receipts[Owner(physical.conversationId.value, physical.runId.value, id.value)] = results
        }
        return { message, block ->
            receipts[Owner(message.conversationId.value, message.runId.value, message.id.value)]
                ?.get(Result(block.invocation, block.result))
        }
    }
}
