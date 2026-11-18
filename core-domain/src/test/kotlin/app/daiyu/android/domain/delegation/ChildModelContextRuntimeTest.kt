package app.daiyu.android.domain.delegation

import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import org.junit.Assert.*
import org.junit.Test

class ChildModelContextRuntimeTest {
    private val initial = Message(MessageId("child-message"), ConversationId("conversation"), RunId("child-run"),
        Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)
    private class Context(override val windowTokens: Long, private val header: String) : RunContextRuntime {
        override val automatic = false
        override val systemPrompt = "child system"
        override val initialMeter = ContextMeterCheckpoint(anchorHeader = "parent", anchorEstimate = 999999L)
        var started = 0; var ended = 0; var cancelled = 0
        override fun headerIdentity(tools: List<ToolDefinition>) = header + tools.joinToString { it.name }
        override fun localTokens(history: List<Message>, tools: List<ToolDefinition>) = 100L + tools.size
        override fun beginCompaction(boundary: Message) { started++ }
        override fun finishedCompaction() { ended++ }
        override fun cancel() { cancelled++ }
        override fun compact(history: List<Message>, billedTokens: Long?, localTokens: Long,
            request: RequestCheckpoint, observe: (ModelEvent.Usage) -> Unit,
            admit: () -> Unit, cancelled: () -> Boolean): List<Message>? = history
    }
    private fun definition(id: String, runtime: RunContextRuntime, made: () -> Unit = {}) =
        ChildModelDefinition(id, 0) { error("no network") }.withContext {
            assertEquals(initial, it); made(); runtime
        }
    @Test fun childForcesAutomaticAndNeverSeedsItsMeterFromAnotherRuntime() {
        val delegate = Context(272000, "child")
        val runtime = ChildModelContextRuntime(initial)
        runtime.prepare(definition("child", delegate))
        assertTrue(runtime.automatic); assertTrue(runtime.canCompact)
        assertEquals(272000L, runtime.windowTokens); assertNull(runtime.initialMeter)
        assertEquals("child system", runtime.systemPrompt)
        assertEquals(101L, runtime.localTokens(emptyList(), listOf(ToolDefinition("read", "d", "{}"))))
    }
    @Test fun switchingModelsReplacesPolicyButKeepsTheActiveCompactionLifecyclePaired() {
        val first = Context(272000, "a"); val second = Context(128000, "b")
        var creates = 0
        val a = definition("a", first) { creates++ }; val b = definition("b", second)
        val runtime = ChildModelContextRuntime(initial)
        runtime.prepare(a); runtime.prepare(a)
        assertEquals(1, creates)
        runtime.beginCompaction(initial)
        runtime.prepare(b)
        assertEquals(128000L, runtime.windowTokens)
        assertEquals("b", runtime.headerIdentity(emptyList()))
        runtime.cancel(); runtime.finishedCompaction()
        assertEquals(1, first.started); assertEquals(1, first.cancelled); assertEquals(1, first.ended)
        assertEquals(0, second.started)
        runtime.beginCompaction(initial); runtime.finishedCompaction(); assertEquals(1, second.ended)
    }
    @Test fun taskAssignmentAndTakeoverForwardTheSelectedContextFactory() {
        val a = definition("a", Context(272000, "a")); val b = definition("b", Context(128000, "b"))
        val assignment = ChildModelAssignment("a", a)
        assertEquals(272000L, assignment.definition.contextFor(initial)!!.windowTokens)
        assignment.reassign("b", b)
        assertEquals(128000L, assignment.definition.contextFor(initial)!!.windowTokens)
    }
}
