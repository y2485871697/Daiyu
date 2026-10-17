package app.daiyu.android.integration.tools

import app.daiyu.android.assistants.*
import app.daiyu.android.domain.*
import app.daiyu.android.domain.tools.*
import app.daiyu.android.skills.SkillRunSession
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RunCatalogAssistantMemoryTest {
    private val message = Message(MessageId("assistant"), ConversationId("conversation"), RunId("run"),
        Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)

    @Test fun sameFrozenContextSuppliesPromptAndPrivateCatalogWithCloseFence() {
        val store = MemoryTestStore(AssistantProfile("a", "A", ""))
        val assets = MemoryTestAssets("a" to ("x".repeat(9000) + "unique-tail"))
        val context = AssistantRunContext.capture(store, "a", assets)
        assertTrue(context.systemPrompt.contains(AssistantMemoryLimits.TRUNCATION))
        assertFalse(context.systemPrompt.contains("unique-tail"))
        val capabilities = SessionCapabilities("run", setOf(ScopedAssistantMemoryTools.READ))
        val tools = RunCatalogTools(ToolRegistry(), capabilities, ToolLedger(InMemoryToolLedgerStore()),
            message.conversationId, message.runId, memoryContext = context) { caps, task ->
            SkillRunSession.create(task, caps.enabled, emptyList())
        }
        tools.bindOwner(runnerOwner(message))
        try {
            assertEquals(setOf("assistant_memory_read", "assistant_memory_search"), tools.definitions.map { it.name }.toSet())
            assertEquals(1, assets.reads.size)
            val call = ToolInvocation(ToolCallId("search"), "assistant_memory_search", "{\"query\":\"unique-tail\"}")
            val outcome = tools.execute(message, listOf(call)) { false }.single().outcome as DispatchOutcome.Confirmed
            assertFalse(outcome.isError)
            val body = JSONObject(outcome.payload)
            assertEquals("a", body.getString("assistant_id"))
            assertEquals(context.memoryRevision, body.getString("revision"))
            assertTrue(body.getJSONArray("hits").getJSONObject(0).getString("content").contains("unique-tail"))
            val reads = assets.reads.size
            tools.close()
            val denied = tools.execute(message, listOf(call.copy(id = ToolCallId("after-close")))) { false }.single()
            assertTrue(denied.outcome is DispatchOutcome.NotExecuted)
            assertEquals(reads, assets.reads.size)
        } finally { tools.close() }
    }

    @Test fun disabledMemoryHasNoPrivateCatalogToolsOrAssetReads() {
        val store = MemoryTestStore(AssistantProfile("a", "A", "", memoryEnabled = false))
        val assets = MemoryTestAssets("a" to "must not read")
        val context = AssistantRunContext.capture(store, "a", assets)
        val capabilities = SessionCapabilities("run", emptySet())
        val tools = RunCatalogTools(ToolRegistry(), capabilities, ToolLedger(InMemoryToolLedgerStore()),
            message.conversationId, message.runId, memoryContext = context) { caps, task ->
            SkillRunSession.create(task, caps.enabled, emptyList())
        }
        tools.bindOwner(runnerOwner(message))
        try {
            assertTrue(tools.definitions.isEmpty())
            assertTrue(assets.reads.isEmpty())
        } finally { tools.close() }
    }
}
