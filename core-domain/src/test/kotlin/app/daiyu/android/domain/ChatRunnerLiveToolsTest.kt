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
    @Test fun capacityCheckAndBothOpenAndNextUseTheSameToolSnapshot() {
        val catalog = Catalog()
        val original = catalog.tools
        val oversized = listOf(definition("oversized"))
        val checked = mutableListOf<List<ToolDefinition>>()
        val runtime = object : app.daiyu.android.domain.runtime.RunContextRuntime {
            override val automatic = false
            override val windowTokens = 100L
            override fun localTokens(history: List<Message>, tools: List<ToolDefinition>): Long {
                checked.add(tools)
                catalog.tools = oversized // Settings change after the budget sees its snapshot.
                return if (tools == original) 10 else 1000
            }
            override fun compact(history: List<Message>, billedTokens: Long?, localTokens: Long,
                request: app.daiyu.android.domain.runtime.RequestCheckpoint,
                observe: (ModelEvent.Usage) -> Unit, admit: () -> Unit, cancelled: () -> Boolean): List<Message>? = null
        }
        val port = object : ModelPort {
            override fun open(history: List<Message>) = error("catalog overload required")
            override fun open(history: List<Message>, definitions: List<ToolDefinition>): ModelSession {
                assertSame(checked.last(), definitions)
                assertEquals(original, definitions)
                catalog.tools = original
                return object : ModelSession {
                    override fun updateToolDefinitions(definitions: List<ToolDefinition>) {
                        assertSame(checked.last(), definitions)
                        assertEquals(original, definitions)
                    }
                    override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                        sink(ModelEvent.TextFinal("answer", "done"))
                    }
                    override fun cancel() {}
                }
            }
        }
        val result = ChatRunner(port, object : ConversationStore { override fun save(message: Message) {} }, catalog,
            RunnerOptions(contextRuntime = runtime)) {}.run(message, emptyList())
        assertEquals(RunStatus.COMPLETED, result.status)
        assertEquals(2, checked.size)
        assertEquals(oversized, catalog.tools)
    }

}
