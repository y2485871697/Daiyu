package app.daiyu.android.domain

import app.daiyu.android.domain.runtime.ReconnectPolicy
import app.daiyu.android.domain.runtime.RunnerOptions
import org.junit.Assert.*
import org.junit.Test

class ChatRunnerLiveToolsTest {
    private val message = Message(MessageId("a"), ConversationId("c"), RunId("r"), "assistant", emptyList(), RunStatus.RUNNING)
    private fun definition(name: String) = ToolDefinition(name, name, """{"type":"object"}""")
    private class Catalog : CatalogTool {
        var tools = listOf(ToolDefinition("old_tool", "old", """{"type":"object"}"""))
        override val definitions get() = tools
        override fun execute(invocation: ToolInvocation): String { tools = emptyList(); return "removed" }
    }
    @Test fun sameSessionRefreshesBeforeNextToolRoundAndKeepsAllOutputs() {
        val catalog = Catalog(); val updates = mutableListOf<List<ToolDefinition>>(); var opens = 0; var requests = 0
        val session = object : ModelSession {
            override fun updateToolDefinitions(definitions: List<ToolDefinition>) { updates.add(definitions) }
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                requests++
                if (requests == 1) {
                    assertEquals(listOf("old_tool"), updates.last().map { it.name })
                    sink(ModelEvent.ToolReady(ToolInvocation(ToolCallId("call"), "old_tool", "{}")))
                } else {
                    assertTrue(updates.last().isEmpty()); assertEquals(listOf(ToolOutput(ToolCallId("call"), "removed")), outputs)
                    sink(ModelEvent.TextFinal("answer", "done"))
                }
            }
            override fun cancel() {}
        }
        val port = object : ModelPort {
            override fun open(history: List<Message>) = error("catalog overload required")
            override fun open(history: List<Message>, definitions: List<ToolDefinition>): ModelSession { opens++; return session }
        }
        val result = ChatRunner(port, object : ConversationStore { override fun save(message: Message) {} }, catalog) {}.run(message, emptyList())
        assertEquals(RunStatus.COMPLETED, result.status); assertEquals(1, opens); assertEquals(2, requests)
        assertEquals(listOf(listOf("old_tool"), emptyList<String>()), updates.map { list -> list.map { it.name } })
    }
    @Test fun currentRequestRetryNeverRefreshesDefinitionsOrReopensSession() {
        val catalog = Catalog(); val updates = mutableListOf<List<ToolDefinition>>(); var opens = 0; var resumes = 0
        val session = object : RequestRetryModelSession {
            override val canResume = true
            override fun updateToolDefinitions(definitions: List<ToolDefinition>) { updates.add(definitions) }
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                catalog.tools = listOf(definition("replacement"))
                throw ModelRequestException(ModelFailure.NETWORK)
            }
            override fun resume(sink: (ModelEvent) -> Unit) {
                resumes++; assertEquals(listOf("old_tool"), updates.single().map { it.name }); sink(ModelEvent.TextFinal("answer", "done"))
            }
            override fun disconnectTransport() {}
            override fun cancel() {}
        }
        val port = object : ModelPort {
            override fun open(history: List<Message>) = error("catalog overload required")
            override fun open(history: List<Message>, definitions: List<ToolDefinition>): ModelSession { opens++; return session }
        }
        val result = ChatRunner(port, object : ConversationStore { override fun save(message: Message) {} }, catalog,
            RunnerOptions(reconnect = ReconnectPolicy(maxAttempts = 1))) {}.run(message, emptyList())
        assertEquals(RunStatus.COMPLETED, result.status); assertEquals(1, opens); assertEquals(1, resumes); assertEquals(1, updates.size)
    }
    @Test fun openedRequestKeepsItsToolSnapshotForNextAndInputEstimate() {
        val catalog = Catalog()
        val original = catalog.tools
        val oversized = listOf(definition("oversized"))
        val measured = mutableListOf<List<ToolDefinition>>()
        var openedWith: List<ToolDefinition>? = null
        var updatedWith: List<ToolDefinition>? = null
        var updates = 0
        val runtime = object : app.daiyu.android.domain.runtime.RunContextRuntime {
            override val automatic = true
            override val windowTokens = 100L
            override val pressureThresholdTokens: Long? = 90L
            override fun fixedSurfaceTokens(history: List<Message>): Long = 0L
            override fun localTokens(history: List<Message>, tools: List<ToolDefinition>): Long {
                measured.add(tools)
                return 1000L // The local estimate must not trigger automatic compaction.
            }
            override fun compact(history: List<Message>, billedTokens: Long?, localTokens: Long,
                request: app.daiyu.android.domain.runtime.RequestCheckpoint,
                observe: (ModelEvent.Usage) -> Unit, admit: () -> Unit, cancelled: () -> Boolean): List<Message>? = error("no confirmed cloud input")
        }
        val port = object : ModelPort {
            override fun open(history: List<Message>) = error("catalog overload required")
            override fun open(history: List<Message>, definitions: List<ToolDefinition>): ModelSession {
                openedWith = definitions
                catalog.tools = oversized // Settings change after this request is prepared.
                return object : ModelSession {
                    override fun updateToolDefinitions(definitions: List<ToolDefinition>) {
                        updates++
                        updatedWith = definitions
                    }
                    override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                        sink(ModelEvent.InputEstimate(1000))
                        sink(ModelEvent.TextFinal("answer", "done"))
                    }
                    override fun cancel() {}
                }
            }
        }
        val result = ChatRunner(port, object : ConversationStore { override fun save(message: Message) {} }, catalog,
            RunnerOptions(contextRuntime = runtime)) {}.run(message, emptyList())
        assertEquals(RunStatus.COMPLETED, result.status)
        assertEquals(original, openedWith)
        assertSame(openedWith, updatedWith)
        assertSame(openedWith, measured.single())
        assertEquals(1, updates)
        assertEquals(oversized, catalog.tools)
    }

}
