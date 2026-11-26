package app.daiyu.android.ui

import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.ExecutionPhase
import app.daiyu.android.domain.runtime.ExecutionState
import app.daiyu.android.ui.screens.chat.conversation.*
import org.junit.Assert.*
import org.junit.Test

class ToolReceiptSourceTest {
    private val call = ToolInvocation(ToolCallId("call"), "search_apps", "{}")
    private val message = Message(MessageId("message"), ConversationId("conversation"), RunId("run"),
        Message.ASSISTANT_ROLE, listOf(MessageBlock.Tool(call)), RunStatus.RUNNING)
    private fun source(value: Message) = RetainedToolOutcomes.sources(listOf(value))

    @Test fun bodyGrowthDoesNotInvalidateDurableReceiptEvidence() {
        assertEquals(source(message), source(message.copy(blocks = message.blocks + MessageBlock.Text("reply", "数🙂".repeat(1000)))))
    }
    @Test fun exactInvocationResultAndStatusChangesRequireFreshEvidence() {
        val block = message.blocks.single() as MessageBlock.Tool
        for (next in listOf(message.copy(status = RunStatus.COMPLETED), message.copy(runId = RunId("other")),
            message.copy(blocks = listOf(block.copy(result = "done"))),
            message.copy(blocks = listOf(block.copy(invocation = call.copy(arguments = "{\"changed\":true}")))))) {
            assertNotEquals(source(message), source(next))
        }
    }
    @Test fun repeatedToolRecordsKeepTheirOccurrenceInTheReceiptSource() {
        val first = MessageBlock.Tool(call.copy(id = ToolCallId("same")), "first")
        val second = MessageBlock.Tool(call.copy(id = ToolCallId("same")), "second")
        val repeated = message.copy(blocks = listOf(first, second))
        assertEquals(2, source(repeated).single().tools.size)
        assertNotEquals(source(message), source(repeated))
    }

    @Test fun historyOnlyToolsAreIncludedOnceWithoutTextInTheSignature() {
        val segment = MessageHistorySegment(message.id, Message.ASSISTANT_ROLE, message.blocks)
        val historical = message.copy(blocks = emptyList(), historySegments = listOf(segment))
        assertEquals(source(message), source(historical))
        assertEquals(1, source(message.copy(historySegments = listOf(segment))).single().tools.size)
        assertTrue(source(message.copy(role = Message.USER_ROLE)).isEmpty())
    }
    private fun key() = RetainedToolOutcomes.ReadKey(message.conversationId, 3L, 4L, source(message),
        ExecutionState(ExecutionPhase.STREAMING))

    @Test fun pauseRequestPausedResumeAndActiveCallChangesKeepTheSameDurableEvidenceDisplayable() {
        val loaded = key()
        val streaming = checkNotNull(loaded.execution)
        for (live in listOf(streaming, streaming.copy(pauseRequested = true),
            streaming.copy(phase = ExecutionPhase.PAUSED, pauseRequested = true),
            streaming.copy(activeToolCalls = setOf(call.id)), streaming.copy(reconnectAttempt = 2), null)) {
            assertTrue(loaded.canDisplayFor(loaded.copy(execution = live)))
        }
        assertNotEquals(loaded, loaded.copy(execution = streaming.copy(pauseRequested = true)))
    }

    @Test fun displayContinuityNeverCrossesOwnersRevisionsOrExactToolSources() {
        val loaded = key()
        val block = message.blocks.single() as MessageBlock.Tool
        val changedMessages = listOf(message.copy(conversationId = ConversationId("other")),
            message.copy(runId = RunId("other")), message.copy(id = MessageId("other")),
            message.copy(status = RunStatus.COMPLETED),
            message.copy(blocks = listOf(block.copy(result = "done"))),
            message.copy(blocks = listOf(block.copy(invocation = call.copy(id = ToolCallId("other"))))),
            message.copy(blocks = listOf(block.copy(invocation = call.copy(name = "other")))),
            message.copy(blocks = listOf(block.copy(invocation = call.copy(arguments = "{\"changed\":true}")))))
        for (other in listOf(loaded.copy(conversation = ConversationId("other")),
            loaded.copy(data = loaded.data + 1), loaded.copy(generation = loaded.generation + 1),
            loaded.copy(sources = emptyList())) + changedMessages.map { loaded.copy(sources = source(it)) }) {
            assertFalse(loaded.canDisplayFor(other))
        }
        assertTrue(loaded.canDisplayFor(loaded.copy(sources = source(message.copy(
            blocks = message.blocks + MessageBlock.Text("body", "more text"))))))
    }

    @Test fun returnedSearchResultWaitsForEvidenceWithoutTrustingItsJson() {
        for (status in listOf(RunStatus.RUNNING, RunStatus.COMPLETED, RunStatus.STOPPED, RunStatus.INTERRUPTED)) {
            for (result in listOf("{\"ok\":true}", "{\"ok\":false}", "not a receipt", "")) {
                val returned = (message.blocks.single() as MessageBlock.Tool).copy(result = result)
                val source = message.copy(status = status, blocks = listOf(returned))
                val pending = RetainedToolOutcomes.pendingProjection(source, returned)!!
                assertEquals(ToolReceiptUi(WorkStepState.UNKNOWN, ToolActivityUi.UNKNOWN), pending)
                val turn = ChatTurnMapper.map(listOf(source), toolReceipt = { owner, tool ->
                    RetainedToolOutcomes.pendingProjection(owner, tool)
                }).single() as ChatTurnUi.Assistant
                val flow = turn.workProcess!!
                val step = flow.segments.flatMap { it.steps }.single()
                assertEquals(WorkStepState.UNKNOWN, step.state)
                assertEquals(ToolActivityUi.UNKNOWN, step.activity)
                assertEquals(1, flow.unconfirmedStepCount)
                assertEquals(1, flow.completedStepCount)
            }
        }
    }
    @Test fun returnedResultsAreUnknownWithoutDurableEvidenceEvenIfTheCallStillLooksActive() {
        val returned = (message.blocks.single() as MessageBlock.Tool).copy(result = "done")
        val source = message.copy(blocks = listOf(returned))
        val live = ExecutionState(phase = ExecutionPhase.TOOL_EXECUTING, activeToolCalls = setOf(call.id))
        fun activity(state: ExecutionState?, stopping: Boolean = false) =
            RetainedToolOutcomes.pendingProjection(source, returned, state, stopping)!!.activity
        assertEquals(ToolActivityUi.UNKNOWN, activity(null))
        assertEquals(ToolActivityUi.UNKNOWN, activity(live.copy(activeToolCalls = emptySet())))
        assertEquals(ToolActivityUi.UNKNOWN, activity(live.copy(activeToolCalls = setOf(ToolCallId("other")))))
        assertEquals(ToolActivityUi.UNKNOWN, activity(live))
        assertEquals(ToolActivityUi.UNKNOWN, activity(live.copy(phase = ExecutionPhase.WAITING_USER)))
        assertEquals(ToolActivityUi.UNKNOWN, activity(live.copy(pauseRequested = true)))
        assertEquals(ToolActivityUi.UNKNOWN, activity(live, stopping = true))
        assertEquals(ToolActivityUi.UNKNOWN, activity(live.copy(phase = ExecutionPhase.IDLE)))
        for (status in RunStatus.values().filter { it != RunStatus.RUNNING }) {
            assertEquals(ToolReceiptUi(WorkStepState.UNKNOWN, ToolActivityUi.UNKNOWN),
                RetainedToolOutcomes.pendingProjection(source.copy(status = status), returned, live, true))
        }
    }

    @Test fun noReturnedResultAndUserMessagesCannotInventARunningActivity() {
        val pending = message.blocks.single() as MessageBlock.Tool
        assertNull(RetainedToolOutcomes.pendingProjection(message, pending))
        assertNull(RetainedToolOutcomes.pendingProjection(message.copy(role = Message.USER_ROLE), pending.copy(result = "done")))
    }

    @Test fun oneChangedOrNewToolDoesNotInvalidateOtherExactTerminalSourcePositions() {
        val tools = List(33) { index -> MessageBlock.Tool(call.copy(id = ToolCallId("call-$index")), "receipt-$index") }
        val old = message.copy(blocks = tools)
        val loaded = key().copy(sources = source(old))
        val changed = tools.map { it.copy() }.toMutableList()
        changed[20] = changed[20].copy(result = "new-result")
        changed += MessageBlock.Tool(call.copy(id = ToolCallId("call-33")), "new")
        val currentMessage = old.copy(status = RunStatus.COMPLETED, blocks = changed)
        val current = loaded.copy(sources = source(currentMessage))
        assertFalse(loaded.canDisplayFor(current))
        val position = loaded.retainedPositions(current)
        changed.forEachIndexed { index, block ->
            assertEquals(if (index == 20 || index == 33) null else index, position(currentMessage, block))
        }
        assertEquals(0, loaded.retainedPositions(current.copy(data = current.data + 1))(currentMessage, changed.first()))
        assertEquals(0, loaded.retainedPositions(current.copy(generation = current.generation + 1))
            (currentMessage, changed.first()))
        for (invalid in listOf(current.copy(conversation = ConversationId("other")),
            current.copy(data = current.data - 1)))
            assertNull(loaded.retainedPositions(invalid)(currentMessage, changed.first()))
    }

    @Test fun repeatedEqualToolsAcrossReloadedHistoryUseAbsoluteSourcePositionsNotSegmentLocalIndices() {
        val first = MessageBlock.Tool(call, "same")
        val second = first.copy()
        val old = message.copy(blocks = emptyList(), historySegments = listOf(
            MessageHistorySegment(message.id, Message.ASSISTANT_ROLE, listOf(first)),
            MessageHistorySegment(message.id, Message.ASSISTANT_ROLE, listOf(second))))
        val loaded = key().copy(sources = source(old))
        val currentMessage = old.copy(historySegments = old.historySegments.map { segment ->
            segment.copy(blocks = segment.blocks.map { (it as MessageBlock.Tool).copy() }) })
        val position = loaded.retainedPositions(loaded.copy(sources = source(currentMessage)))
        val expanded = MessageHistory.expand(listOf(currentMessage))
        expanded.forEachIndexed { index, segment ->
            assertEquals(index, position(segment, segment.blocks.single() as MessageBlock.Tool))
        }
        assertNull(position(expanded.first(), first))
        val removed = currentMessage.copy(historySegments = currentMessage.historySegments.take(1))
        val deleted = loaded.retainedPositions(loaded.copy(sources = source(removed)))
        assertNull(deleted(expanded.last(), expanded.last().blocks.single() as MessageBlock.Tool))
    }

    @Test fun independentlyDecodedSummaryAliasesNeverShiftTheAuthoritativeHistoryOrdinals() {
        val physical = MessageBlock.Tool(call, "receipt")
        val segment = physical.copy()
        assertEquals(physical, segment); assertNotSame(physical, segment)
        val historical = message.copy(blocks = listOf(physical), historySegments = listOf(
            MessageHistorySegment(message.id, Message.ASSISTANT_ROLE, listOf(segment))))
        val canonical = source(historical).single().tools
        assertEquals(1, canonical.size)
        assertSame(segment, canonical.single())
        assertEquals(source(message.copy(blocks = listOf(physical))), source(historical))
        val onlyUser = historical.copy(historySegments = listOf(MessageHistorySegment(
            MessageId("user"), Message.USER_ROLE, listOf(MessageBlock.Text("user", "supplement")))))
        assertTrue(source(onlyUser).isEmpty())
    }

    @Test fun anotherToolOrLaterModelRequestCannotRestartAReturnedCard() {
        val returned = (message.blocks.single() as MessageBlock.Tool).copy(result = "done")
        val source = message.copy(blocks = listOf(returned))
        for (phase in ExecutionPhase.values()) {
            val live = ExecutionState(phase, activeToolCalls = setOf(ToolCallId("different-tool")))
            val expected = ToolReceiptUi(WorkStepState.UNKNOWN, ToolActivityUi.UNKNOWN)
            assertEquals(expected, RetainedToolOutcomes.pendingProjection(source, returned, live))
            assertEquals(expected, RetainedToolOutcomes.pendingProjection(RunStatus.RUNNING, returned, live))
        }
    }

    @Test fun pendingReadCannotHideStoppedInterruptedReconnectingOrUnreturnedUnknownResults() {
        val returned = (message.blocks.single() as MessageBlock.Tool).copy(result = "done")
        for (status in listOf(RunStatus.STOPPED, RunStatus.INTERRUPTED)) {
            assertEquals(ToolReceiptUi(WorkStepState.UNKNOWN, ToolActivityUi.UNKNOWN),
                RetainedToolOutcomes.pendingProjection(status, returned))
        }
        assertEquals(ToolReceiptUi(WorkStepState.UNKNOWN, ToolActivityUi.UNKNOWN),
            RetainedToolOutcomes.pendingProjection(RunStatus.RUNNING, returned,
                ExecutionState(ExecutionPhase.RECONNECTING)))
        assertEquals(ToolReceiptUi(WorkStepState.UNKNOWN, ToolActivityUi.UNKNOWN),
            RetainedToolOutcomes.pendingProjection(RunStatus.RUNNING, returned, stopping = true))
        assertNull(RetainedToolOutcomes.pendingProjection(message, returned.copy(result = null)))
        assertNull(RetainedToolOutcomes.pendingProjection(message.copy(role = Message.USER_ROLE), returned))

    }

}
