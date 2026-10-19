package app.daiyu.android.ui

import app.daiyu.android.domain.Message
import app.daiyu.android.domain.MessageBlock
import app.daiyu.android.domain.ToolInvocation
import app.daiyu.android.storage.ConversationDatabase
import app.daiyu.android.storage.SQLiteRuntimeStore
import app.daiyu.android.ui.screens.chat.conversation.WorkStepState
import app.daiyu.android.ui.screens.chat.conversation.ToolReceiptUi
import app.daiyu.android.ui.screens.chat.conversation.toolReceiptPresentation
import app.daiyu.android.domain.runtime.ExecutionState

/** Read-only projection of the Runner's durable receipt disposition, never tool/model JSON text. */
internal object RetainedToolOutcomes {
    private data class Owner(val conversation: String, val run: String, val message: String)
    private data class Result(val invocation: ToolInvocation, val text: String?)

    fun projectionReader(database: ConversationDatabase, messages: List<Message>,
        liveState: (Message) -> ExecutionState? = { null },
        stopping: (Message) -> Boolean = { false }): (Message, MessageBlock.Tool) -> ToolReceiptUi? {
        val receipts = mutableMapOf<Owner, Map<Result, ToolReceiptUi>>()
        for (physical in messages.filter { it.role == Message.ASSISTANT_ROLE &&
            (it.blocks.any { block -> block is MessageBlock.Tool } ||
                it.historySegments.any { segment -> segment.blocks.any { block -> block is MessageBlock.Tool } }) }) {
            val snapshot = runCatching {
                SQLiteRuntimeStore.readCheckpointSnapshot(database.readableDatabase, physical.id.value)
            }.getOrNull() ?: continue
            if (snapshot.message.id != physical.id || snapshot.message.runId != physical.runId ||
                snapshot.message.conversationId != physical.conversationId) continue
            val current = liveState(physical)
            val results = snapshot.tools.map { record ->
                val state = toolReceiptPresentation(record.disposition,
                    liveOwner = current != null && physical.status == app.daiyu.android.domain.RunStatus.RUNNING,
                    phase = current?.phase ?: snapshot.phase, pauseRequested = current?.pauseRequested == true,
                    stopRequested = stopping(physical), runStatus = physical.status)
                Result(record.invocation, record.result) to state
            }.toMap()
            receipts[Owner(physical.conversationId.value, physical.runId.value, physical.id.value)] = results
        }
        return { message, block ->
            if (message.role != Message.ASSISTANT_ROLE) null else receipts[Owner(message.conversationId.value, message.runId.value, message.id.value)]
                ?.get(Result(block.invocation, block.result))
        }
    }

    fun stateReader(database: ConversationDatabase, messages: List<Message>): (Message, MessageBlock.Tool) -> WorkStepState? {
        val projection = projectionReader(database, messages, liveState = { message ->
            if (message.status == app.daiyu.android.domain.RunStatus.RUNNING) ExecutionState() else null
        })
        return { message, block -> projection(message, block)?.state }
    }

    fun reader(database: ConversationDatabase, messages: List<Message>): (Message, MessageBlock.Tool) -> Boolean? {
        val state = stateReader(database, messages)
        return { message, block -> when (state(message, block)) {
            WorkStepState.FAILED -> true
            WorkStepState.OK -> false
            else -> null
        } }
    }
}
