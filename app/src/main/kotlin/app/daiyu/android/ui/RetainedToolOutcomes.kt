package app.daiyu.android.ui

import app.daiyu.android.domain.Message
import app.daiyu.android.domain.MessageBlock
import app.daiyu.android.domain.RunStatus
import app.daiyu.android.domain.runtime.ExecutionPhase
import app.daiyu.android.domain.runtime.ToolDisposition
import app.daiyu.android.domain.ToolInvocation
import app.daiyu.android.storage.ConversationDatabase
import app.daiyu.android.storage.SQLiteRuntimeStore
import app.daiyu.android.storage.SQLiteChildTaskStore
import app.daiyu.android.domain.delegation.ChildRunOwner
import app.daiyu.android.ui.screens.chat.conversation.childTaskReceiptOwners
import app.daiyu.android.question.QuestionCheckpointEvidence
import app.daiyu.android.ui.screens.chat.conversation.WorkStepState
import app.daiyu.android.ui.screens.chat.conversation.WorkStepOwnerUi
import app.daiyu.android.ui.screens.chat.conversation.ToolActivityUi
import app.daiyu.android.ui.screens.chat.conversation.ToolReceiptUi
import app.daiyu.android.ui.screens.chat.conversation.toolReceiptPresentation
import app.daiyu.android.domain.runtime.CommittedToolReceipts
import app.daiyu.android.domain.runtime.ToolRecord
import app.daiyu.android.domain.runtime.ExecutionState
import java.util.IdentityHashMap

/** 从 Runner 持久化回执读取工具结果状态。 */
internal object RetainedToolOutcomes {
    private data class Owner(val conversation: String, val run: String, val message: String)
    private data class Result(val invocation: ToolInvocation, val text: String?, val position: Int)

    internal data class Source(val conversation: String, val run: String, val message: String,
        val status: RunStatus, val tools: List<MessageBlock.Tool>)
    private fun sourceBlocks(message: Message): List<MessageBlock> =
        if (message.historySegments.isEmpty()) message.blocks else message.historySegments
            .filter { it.role == Message.ASSISTANT_ROLE }.flatMap { it.blocks }

    internal fun sources(messages: List<Message>, display: app.daiyu.android.storage.MessageDisplayWindow? = null): List<Source> =
        display?.rows?.mapNotNull { (_, row) ->
            if (row.role != Message.ASSISTANT_ROLE) return@mapNotNull null
            val blocks = if (row.segments.isEmpty()) row.blocks else row.segments
                .filter { it.role == Message.ASSISTANT_ROLE }.flatMap { it.blocks }
            val tools = blocks.filterIsInstance<MessageBlock.Tool>()
            if (tools.isEmpty()) null else Source(row.conversationId.value, row.runId.value, row.id.value, row.status, tools)
        } ?: messages.filter { it.role == Message.ASSISTANT_ROLE }
        .mapNotNull { message ->
            val tools = sourceBlocks(message).filterIsInstance<MessageBlock.Tool>()
            if (tools.isEmpty()) null else Source(message.conversationId.value, message.runId.value, message.id.value, message.status, tools)
        }

    internal data class ReadKey(val conversation: app.daiyu.android.domain.ConversationId?, val data: Long,
        val generation: Long, val sources: List<Source>, val execution: ExecutionState?) {
        // Live activity is reprojected; the durable evidence remains valid for exactly the same source.
        fun canDisplayFor(current: ReadKey): Boolean = copy(execution = current.execution) == current

        // A newer data revision requests fresh evidence; it does not revoke an identical terminal receipt.
        fun retainedPositions(current: ReadKey): (Message, MessageBlock.Tool) -> Int? {
            if (conversation != current.conversation || data > current.data)
                return { _, _ -> null }
            val loaded = sources.associateBy { Owner(it.conversation, it.run, it.message) }
            val positions = positions(current.sources)
            return reader@ { message, block ->
                val owner = Owner(message.conversationId.value, message.runId.value, message.id.value)
                val index = positions[owner]?.get(block) ?: return@reader null
                if (loaded[owner]?.tools?.getOrNull(index) == block) index else null
            }
        }
    }

    private fun positions(sources: List<Source>): Map<Owner, IdentityHashMap<MessageBlock.Tool, Int>> =
        sources.associate { source ->
            Owner(source.conversation, source.run, source.message) to IdentityHashMap<MessageBlock.Tool, Int>().apply {
                source.tools.forEachIndexed { index, block -> put(block, index) }
            }
        }

    private fun outcomes(message: Message, tools: List<ToolRecord>): Map<Result, ToolDisposition> {
        val projected = sourceBlocks(message)
        val visibleUnreturned = projected.filterIsInstance<MessageBlock.Tool>()
            .filter { it.result == null }.toSet()
        val physical = message.blocks.filter {
            it !is MessageBlock.Tool || it.result != null || it in visibleUnreturned
        }
        val tailStart = projected.size - physical.size
        if (tailStart < 0 || projected.subList(tailStart, projected.size) != physical) return emptyMap()
        val projectedTools = projected.filterIsInstance<MessageBlock.Tool>()
        val physicalOffset = projected.take(tailStart).count { it is MessageBlock.Tool }
        val ledger = tools.associateBy { it.invocation.id }
        val occurrences = projectedTools.groupingBy { it.invocation.id }.eachCount()
        return projectedTools.mapIndexedNotNull { position, block ->
            val record = ledger[block.invocation.id]?.takeIf {
                it.invocation == block.invocation && it.result == block.result &&
                    (position >= physicalOffset || (block.result != null && occurrences[block.invocation.id] == 1 &&
                        it.disposition in setOf(ToolDisposition.SUCCEEDED, ToolDisposition.FAILED, ToolDisposition.NOT_EXECUTED)))
            } ?: return@mapIndexedNotNull null
            Result(block.invocation, block.result, position) to record.disposition
        }.toMap()
    }

    fun committedReader(receipts: CommittedToolReceipts?, sources: List<Source>):
        (WorkStepOwnerUi, MessageBlock.Tool) -> ToolReceiptUi? {
        receipts ?: return { _, _ -> null }
        val message = receipts.message
        val source = sources.firstOrNull {
            it.conversation == message.conversationId.value && it.run == message.runId.value && it.message == message.id.value
        } ?: return { _, _ -> null }
        val owner = WorkStepOwnerUi(source.conversation, source.run, source.message)
        val positions = IdentityHashMap<MessageBlock.Tool, Int>().apply {
            source.tools.forEachIndexed { index, block -> put(block, index) }
        }
        val committed = outcomes(message, receipts.tools)
        return reader@ { identity, block ->
            if (identity != owner || block.result == null) return@reader null
            val index = positions[block] ?: return@reader null
            val disposition = committed[Result(block.invocation, block.result, index)] ?: return@reader null
            when (disposition) {
                ToolDisposition.SUCCEEDED, ToolDisposition.FAILED, ToolDisposition.NOT_EXECUTED ->
                    toolReceiptPresentation(disposition, liveOwner = false, phase = ExecutionPhase.IDLE, runStatus = source.status)
                else -> null
            }
        }
    }

    internal class Snapshot private constructor(private val records: Map<Owner, Map<Result, ToolDisposition>>,
        private val questions: Map<Owner, QuestionCheckpointEvidence>,
        private val blockPositions: Map<Owner, IdentityHashMap<MessageBlock.Tool, Int>>,
        val childAgentNames: Map<ChildRunOwner, String>) {
        fun questionEvidence(message: Message): QuestionCheckpointEvidence? =
            questionEvidence(message.conversationId.value, message.runId.value, message.id.value)

        fun questionEvidence(conversation: String, run: String, message: String): QuestionCheckpointEvidence? =
            questions[Owner(conversation, run, message)]

        private fun position(message: Message, block: MessageBlock.Tool): Int {
            val tools = sourceBlocks(message).filterIsInstance<MessageBlock.Tool>()
            return tools.indexOfFirst { it === block }.takeIf { it >= 0 }
                ?: tools.indexOf(block).takeIf { it >= 0 } ?: 0
        }

        fun displayReader(loaded: ReadKey, current: ReadKey,
            liveState: (Message) -> ExecutionState? = { null }, stopping: (Message) -> Boolean = { false }):
            (Message, MessageBlock.Tool) -> ToolReceiptUi? {
            if (loaded.canDisplayFor(current)) return projectionReader(liveState, stopping, current.sources)
            val position = loaded.retainedPositions(current)
            return reader@ { message, block ->
                if (message.role != Message.ASSISTANT_ROLE) return@reader null
                val index = position(message, block) ?: return@reader null
                val owner = Owner(message.conversationId.value, message.runId.value, message.id.value)
                val disposition = records[owner]?.get(Result(block.invocation, block.result, index))
                if (disposition == ToolDisposition.SUCCEEDED || disposition == ToolDisposition.FAILED ||
                    disposition == ToolDisposition.NOT_EXECUTED)
                    toolReceiptPresentation(disposition, liveOwner = false, phase = ExecutionPhase.IDLE,
                        runStatus = message.status) else null
            }
        }

        fun displayRowReader(loaded: ReadKey, current: ReadKey,
            liveState: (WorkStepOwnerUi) -> ExecutionState? = { null },
            stopping: (WorkStepOwnerUi) -> Boolean = { false }):
            (WorkStepOwnerUi, MessageBlock.Tool) -> ToolReceiptUi? {
            val compatible = loaded.canDisplayFor(current)
            if (!compatible && (loaded.conversation != current.conversation || loaded.data > current.data)) return { _, _ -> null }
            val loadedSources = loaded.sources.associateBy { Owner(it.conversation, it.run, it.message) }
            val currentSources = current.sources.associateBy { Owner(it.conversation, it.run, it.message) }
            val currentPositions = positions(current.sources)
            return reader@ { identity, block ->
                val owner = Owner(identity.conversationId, identity.runId, identity.messageId)
                val source = currentSources[owner] ?: return@reader null
                val index = currentPositions[owner]?.get(block) ?: return@reader null
                if (loadedSources[owner]?.tools?.getOrNull(index) != block) return@reader null
                val disposition = records[owner]?.get(Result(block.invocation, block.result, index)) ?: return@reader null
                if (!compatible && disposition != ToolDisposition.SUCCEEDED && disposition != ToolDisposition.FAILED &&
                    disposition != ToolDisposition.NOT_EXECUTED) return@reader null
                val live = if (compatible) liveState(identity) else null
                toolReceiptPresentation(disposition, liveOwner = live != null && source.status == RunStatus.RUNNING,
                    phase = live?.phase ?: ExecutionPhase.IDLE, pauseRequested = live?.pauseRequested == true,
                    stopRequested = compatible && stopping(identity), runStatus = source.status,
                    activeCall = block.invocation.id in live?.activeToolCalls.orEmpty())
            }
        }

        fun projectionReader(liveState: (Message) -> ExecutionState? = { null }, stopping: (Message) -> Boolean = { false },
            currentSources: List<Source>? = null):
            (Message, MessageBlock.Tool) -> ToolReceiptUi? {
            val currentPositions = currentSources?.let(::positions) ?: blockPositions
            return reader@ { message, block ->
            if (message.role != Message.ASSISTANT_ROLE) null else {
                val owner = Owner(message.conversationId.value, message.runId.value, message.id.value)
                val sourcePosition = currentPositions[owner]?.get(block)
                    ?: if (currentSources == null) position(message, block) else return@reader null
                val disposition = records[owner]
                    ?.get(Result(block.invocation, block.result, sourcePosition))
                disposition?.let {
                    val current = liveState(message)
                    toolReceiptPresentation(it, liveOwner = current != null && message.status == RunStatus.RUNNING,
                        phase = current?.phase ?: ExecutionPhase.IDLE,
                        pauseRequested = current?.pauseRequested == true, stopRequested = stopping(message), runStatus = message.status,
                        activeCall = block.invocation.id in current?.activeToolCalls.orEmpty())
                }
            }
            }
        }
        companion object {
            fun read(database: ConversationDatabase, messages: List<Message>,
                display: app.daiyu.android.storage.MessageDisplayWindow? = null): Snapshot {
                val questions = mutableMapOf<Owner, QuestionCheckpointEvidence>()
                val blockPositions = mutableMapOf<Owner, IdentityHashMap<MessageBlock.Tool, Int>>()
                val childAgentNames = mutableMapOf<ChildRunOwner, String>()
                val children = SQLiteChildTaskStore(database)
                val lookedUp = mutableSetOf<ChildRunOwner>()
                val records = buildMap {
                    for (source in sources(messages, display)) {
                        source.tools.forEach { block ->
                            childTaskReceiptOwners(source.conversation, block.invocation.name,
                                block.invocation.arguments, block.result).forEach { owner ->
                                if (lookedUp.add(owner)) runCatching { children.get(owner)?.display?.agentName }
                                    .getOrNull()?.let { childAgentNames[owner] = it }
                            }
                        }
                        val (snapshot, recoveryOwner) = runCatching {
                            SQLiteRuntimeStore.readQuestionCheckpoint(database.readableDatabase, source.message)
                        }.getOrNull() ?: continue
                        if (snapshot.message.id.value != source.message || snapshot.message.runId.value != source.run ||
                            snapshot.message.conversationId.value != source.conversation) continue
                        val owner = Owner(source.conversation, source.run, source.message)
                        blockPositions.getOrPut(owner) { IdentityHashMap() }.also { positions ->
                            source.tools.forEachIndexed { position, block -> positions[block] = position }
                        }
                        if (snapshot.tools.any { it.invocation.name == app.daiyu.android.domain.question.ASK_USER_TOOL_NAME }) {
                            questions[owner] = QuestionCheckpointEvidence.from(snapshot, recoveryOwner)
                        }
                        put(owner, outcomes(snapshot.message, snapshot.tools))
                    }
                }
                return Snapshot(records, questions.toMap(), blockPositions, childAgentNames.toMap())
            }
        }
    }

    /** Receipt IO is presentation-only; only exact durable evidence can confirm an outcome. */
    fun pendingProjection(message: Message, block: MessageBlock.Tool,
        liveState: ExecutionState? = null, stopping: Boolean = false): ToolReceiptUi? =
        if (message.role != Message.ASSISTANT_ROLE) null else
            pendingProjection(message.status, block, liveState, stopping)

    // The caller must match conversation/run/message before supplying liveState.
    fun pendingProjection(status: RunStatus, block: MessageBlock.Tool,
        liveState: ExecutionState? = null, stopping: Boolean = false): ToolReceiptUi? {
        if (block.result == null) {
            if (status != RunStatus.RUNNING || liveState == null || block.invocation.id !in liveState.activeToolCalls ||
                liveState.phase !in setOf(ExecutionPhase.TOOL_EXECUTING, ExecutionPhase.WAITING_USER)) return null
            return toolReceiptPresentation(ToolDisposition.READY, true, liveState.phase,
                liveState.pauseRequested, stopping, status, activeCall = true)
        }
        // The tool has returned. Pending receipt IO and run-level activity cannot make it execute again.
        return ToolReceiptUi(WorkStepState.UNKNOWN, ToolActivityUi.UNKNOWN)
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
