package app.daiyu.android.ui

import app.daiyu.android.domain.Message
import app.daiyu.android.domain.MessageBlock
import app.daiyu.android.domain.RunStatus
import app.daiyu.android.domain.runtime.ExecutionPhase
import app.daiyu.android.domain.runtime.ToolDisposition
import app.daiyu.android.domain.ToolInvocation
import app.daiyu.android.storage.ConversationDatabase
import app.daiyu.android.storage.SQLiteRuntimeStore
import app.daiyu.android.question.QuestionCheckpointEvidence
import app.daiyu.android.ui.screens.chat.conversation.WorkStepState
import app.daiyu.android.ui.screens.chat.conversation.ToolReceiptUi
import app.daiyu.android.ui.screens.chat.conversation.toolReceiptPresentation
import app.daiyu.android.domain.runtime.ExecutionState

/** Read-only projection of the Runner's durable receipt disposition, never tool/model JSON text. */
internal object RetainedToolOutcomes {
    private data class Owner(val conversation: String, val run: String, val message: String)
    private data class Result(val invocation: ToolInvocation, val text: String?)

    internal data class Source(val conversation: String, val run: String, val message: String,
        val status: RunStatus, val tools: List<MessageBlock.Tool>)
    internal fun sources(messages: List<Message>): List<Source> = messages.filter { it.role == Message.ASSISTANT_ROLE }
        .mapNotNull { message ->
            val tools = (message.blocks + message.historySegments.flatMap { it.blocks }).filterIsInstance<MessageBlock.Tool>().distinct()
            if (tools.isEmpty()) null else Source(message.conversationId.value, message.runId.value, message.id.value, message.status, tools)
        }

    internal class Snapshot private constructor(private val records: Map<Owner, Map<Result, ToolDisposition>>,
        private val questions: Map<Owner, QuestionCheckpointEvidence>) {
        fun questionEvidence(message: Message): QuestionCheckpointEvidence? =
            questions[Owner(message.conversationId.value, message.runId.value, message.id.value)]

        fun projectionReader(liveState: (Message) -> ExecutionState? = { null }, stopping: (Message) -> Boolean = { false }):
            (Message, MessageBlock.Tool) -> ToolReceiptUi? = { message, block ->
            if (message.role != Message.ASSISTANT_ROLE) null else {
                val disposition = records[Owner(message.conversationId.value, message.runId.value, message.id.value)]
                    ?.get(Result(block.invocation, block.result))
                disposition?.let {
                    val current = liveState(message)
                    toolReceiptPresentation(it, liveOwner = current != null && message.status == RunStatus.RUNNING,
                        phase = current?.phase ?: ExecutionPhase.IDLE,
                        pauseRequested = current?.pauseRequested == true, stopRequested = stopping(message), runStatus = message.status,
                        activeCall = block.invocation.id in current?.activeToolCalls.orEmpty())
                }
            }
        }
        companion object {
            fun read(database: ConversationDatabase, messages: List<Message>): Snapshot {
                val questions = mutableMapOf<Owner, QuestionCheckpointEvidence>()
                val records = buildMap {
                    for (source in sources(messages)) {
                        val (snapshot, recoveryOwner) = runCatching {
                            SQLiteRuntimeStore.readQuestionCheckpoint(database.readableDatabase, source.message)
                        }.getOrNull() ?: continue
                        if (snapshot.message.id.value != source.message || snapshot.message.runId.value != source.run ||
                            snapshot.message.conversationId.value != source.conversation) continue
                        val owner = Owner(source.conversation, source.run, source.message)
                        if (snapshot.tools.any { it.invocation.name == app.daiyu.android.domain.question.ASK_USER_TOOL_NAME }) {
                            questions[owner] = QuestionCheckpointEvidence.from(snapshot, recoveryOwner)
                        }
                        put(owner, snapshot.tools.associate {
                            Result(it.invocation, it.result) to it.disposition
                        })
                    }
                }
                return Snapshot(records, questions.toMap())
            }
        }
    }

    fun projectionReader(database: ConversationDatabase, messages: List<Message>,
        liveState: (Message) -> ExecutionState? = { null },
        stopping: (Message) -> Boolean = { false }): (Message, MessageBlock.Tool) -> ToolReceiptUi? =
        Snapshot.read(database, messages).projectionReader(liveState, stopping)

    fun stateReader(database: ConversationDatabase, messages: List<Message>): (Message, MessageBlock.Tool) -> WorkStepState? {
        val projection = projectionReader(database, messages, liveState = { message ->
            if (message.status == RunStatus.RUNNING) ExecutionState() else null
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
