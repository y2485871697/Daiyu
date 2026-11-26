package app.daiyu.android.integration.tools

import app.daiyu.android.assistants.*
import app.daiyu.android.domain.*
import app.daiyu.android.domain.tools.*
import app.daiyu.android.skills.SkillRunSession
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RunCatalogAssistantMemoryTest {
    @get:org.junit.Rule val liveOwners = app.daiyu.android.integration.tools.LiveRunnerOwners()
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
        tools.bindOwner(liveOwners.issue(message))
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

    @Test fun cachedReadIsReplayedOnlyWhileItsFrozenRevisionRemainsCurrent() {
        assertMemoryReplayFence(
            name = "assistant_memory_read", arguments = "{}",
            deniedCode = "assistant_memory_revision_conflict",
        ) { _, assets -> assets.replace("a", "new private text", "a-v2") }
    }

    @Test fun disablingMemoryBlocksCachedSearchWithoutReadingAssets() {
        assertMemoryReplayFence(
            name = "assistant_memory_search", arguments = "{\"query\":\"saved\"}",
            deniedCode = "assistant_memory_unavailable",
        ) { store, _ -> store.save(checkNotNull(store.read("a")).copy(memoryEnabled = false)) }
    }

    private fun assertMemoryReplayFence(
        name: String, arguments: String, deniedCode: String,
        revoke: (MemoryTestStore, MemoryTestAssets) -> Unit,
    ) {
        val store = MemoryTestStore(AssistantProfile("a", "A", ""))
        val assets = MemoryTestAssets("a" to "saved text")
        val context = store.capture("a", assets)
        val capabilities = SessionCapabilities("run", setOf(ScopedAssistantMemoryTools.READ))
        val tools = RunCatalogTools(ToolRegistry(), capabilities, ToolLedger(InMemoryToolLedgerStore()),
            message.conversationId, message.runId, memoryContext = context) { caps, task ->
            SkillRunSession.create(task, caps.enabled, emptyList())
        }
        tools.bindOwner(liveOwners.issue(message))
        try {
            assertTrue(tools.definitions.any { it.name == name })
            val call = ToolInvocation(ToolCallId("same-call"), name, arguments)
            fun invoke() = tools.execute(message, listOf(call)) { false }.single().outcome
            val first = invoke() as DispatchOutcome.Confirmed
            assertFalse(first.isError)
            assertFalse(first.replayed)
            assertTrue(first.payload.contains("saved text"))
            assertEquals(first.copy(replayed = true), invoke())
            revoke(store, assets)
            val reads = assets.reads.size
            assertEquals(DispatchOutcome.NotExecuted(deniedCode), invoke())
            if (deniedCode == "assistant_memory_unavailable") assertEquals(reads, assets.reads.size)
        } finally { tools.close() }
    }

    @Test fun optedInWriteAppendsOnceAndInvalidatesPreviousReadReceipt() = withWriter { tools, _, assets ->
        val read = ToolInvocation(ToolCallId("read-to-fence"), "assistant_memory_read", "{}")
        assertTrue(tools.execute(message, listOf(read)) { false }.single().outcome is DispatchOutcome.Confirmed)
        val write = memoryWrite("append", "Prefers metric units")
        val result = tools.execute(message, listOf(write)) { false }.single().outcome as DispatchOutcome.Confirmed
        assertFalse(result.isError)
        val replay = tools.execute(message, listOf(write)) { false }.single().outcome
        assertEquals(result.copy(replayed = true), replay)
        assertEquals("saved text\n\n- Prefers metric units\n", assets.memory("a").content)
        assertEquals(DispatchOutcome.NotExecuted("assistant_memory_write_already_attempted"), tools.execute(message,
            listOf(memoryWrite("second", "Prefers short replies"))) { false }.single().outcome)
        // Recreating the original contents/revision must not resurrect this run's old receipt.
        assets.replace("a", "saved text", "a-v1")
        assertEquals(DispatchOutcome.NotExecuted("assistant_memory_revision_conflict"),
            tools.execute(message, listOf(read)) { false }.single().outcome)
    }

    @Test fun disablingAndReEnablingCannotReviveFrozenWriteGrant() = withWriter { tools, store, assets ->
        val optedIn = checkNotNull(store.read("a"))
        store.save(optedIn.copy(memoryEnabled = false, autoMemoryWriteEnabled = false))
        store.save(optedIn)
        val reads = assets.reads.size
        assertEquals(DispatchOutcome.NotExecuted("assistant_memory_unavailable"), tools.execute(message,
            listOf(memoryWrite("revoked", "Prefers metric units"))) { false }.single().outcome)
        assertEquals(reads, assets.reads.size)
        assertEquals("saved text", assets.memory("a").content)
    }

    @Test fun invalidMemoryProposalMakesNoAssetChanges() = withWriter { tools, _, assets ->
        val reads = assets.reads.size
        assertEquals(DispatchOutcome.NotExecuted("preparation_failed"), tools.execute(message,
            listOf(memoryWrite("blocked", "my password: example"))) { false }.single().outcome)
        assertEquals(reads, assets.reads.size)
        assertEquals("saved text", assets.memory("a").content)
    }

    @Test fun exceptionAfterCommitIsUnknownAndCannotAppendAgain() = withWriter { tools, _, assets ->
        assets.afterSaveMemory = { error("receipt interrupted") }
        val call = memoryWrite("unknown", "Prefers metric units")
        assertTrue(tools.execute(message, listOf(call)) { false }.single().outcome is DispatchOutcome.Unknown)
        assertEquals(DispatchOutcome.Unknown("unknown_effect_protected"),
            tools.execute(message, listOf(call.copy(id = ToolCallId("reissued")))) { false }.single().outcome)
        assertEquals("saved text\n\n- Prefers metric units\n", assets.memory("a").content)
    }

    private fun memoryWrite(id: String, content: String) = ToolInvocation(ToolCallId(id), "assistant_memory_write",
        JSONObject().put("content", content).put("purpose", "durable_preference").toString())

    private fun withWriter(test: (RunCatalogTools, MemoryTestStore, MemoryTestAssets) -> Unit) {
        val store = MemoryTestStore(AssistantProfile("a", "A", "", autoMemoryWriteEnabled = true))
        val assets = MemoryTestAssets("a" to "saved text")
        val context = store.capture("a", assets)
        val caps = SessionCapabilities("run", setOf(ScopedAssistantMemoryTools.READ, ScopedAssistantMemoryTools.WRITE))
        val tools = RunCatalogTools(ToolRegistry(), caps, ToolLedger(InMemoryToolLedgerStore()),
            message.conversationId, message.runId, memoryContext = context) { capabilities, task ->
            SkillRunSession.create(task, capabilities.enabled, emptyList())
        }
        tools.bindOwner(liveOwners.issue(message))
        try {
            assertTrue(tools.definitions.any { it.name == "assistant_memory_write" })
            test(tools, store, assets)
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
        tools.bindOwner(liveOwners.issue(message))
        try {
            assertTrue(tools.definitions.isEmpty())
            assertTrue(assets.reads.isEmpty())
        } finally { tools.close() }
    }
}
