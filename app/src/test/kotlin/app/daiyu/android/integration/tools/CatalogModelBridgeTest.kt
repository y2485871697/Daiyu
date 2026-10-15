package app.daiyu.android.integration.tools

import app.daiyu.android.domain.*
import app.daiyu.android.domain.tools.*
import app.daiyu.android.domain.tools.ToolDefinition as RegisteredDefinition
import org.junit.Assert.*
import org.junit.Test

class CatalogModelBridgeTest {
    private val owner = Message(MessageId("a"), ConversationId("conversation"), RunId("run"),
        "assistant", emptyList(), RunStatus.RUNNING)
    private val schema = ToolSchema(listOf(ArgumentField("query", ArgumentType.Text(maxLength=8, minimumLength=2))))
    private val call = ToolInvocation(ToolCallId("call"), "lookup", "{\"query\":\"ab\"}")
    private fun bridge(execute: () -> ExecutionReceipt): CatalogModelBridge {
        val registry=ToolRegistry()
        for ((name, capability) in listOf("lookup" to "read", "disabled" to "disabled")) registry.register(object : RegisteredTool {
            override val definition=RegisteredDefinition(name,1,"read query",capability,ToolEffect.READ_ONLY,schema)
            override fun prepare(arguments:ToolArguments)=PreparedTool(execute=execute)
        })
        return CatalogModelBridge(registry.snapshot(),SessionCapabilities("session",setOf("read")),
            ToolLedger(InMemoryToolLedgerStore()),owner.conversationId,owner.runId,TaskAuthorization("session",owner.runId.value)).apply { bindOwner(runnerOwner(owner)) }
    }
    @Test fun canonicalFilteredSchemaAndReceiptReplayUseOneCatalogAndLedger() {
        var executions=0
        val bridge=bridge {executions++;ExecutionReceipt.Confirmed("{\"ok\":false}",true)}
        val definition=bridge.definitions.single()
        assertEquals("lookup",definition.name)
        assertEquals(schema.jsonSchema(),definition.parameters)
        assertFalse(definition.sideEffect)
        val first=bridge.execute(owner,listOf(call)){false}.single().outcome as DispatchOutcome.Confirmed
        val replay=bridge.execute(owner,listOf(call)){false}.single().outcome as DispatchOutcome.Confirmed
        assertTrue(first.isError)
        assertFalse(first.replayed)
        assertTrue(replay.replayed)
        assertEquals(first.payload,replay.payload)
        assertEquals(1,executions)
    }
    @Test fun wholeBatchRejectsInvalidUnknownAndDisabledBeforeAnyBackend() {
        var executions=0
        val bridge=bridge {executions++;ExecutionReceipt.Confirmed("ok")}
        for (bad in listOf(call.copy(arguments="{\"query\":\"a\"}"),call.copy(name="absent"),call.copy(name="disabled"))) {
            val receipts=bridge.execute(owner,listOf(call,bad.copy(id=ToolCallId("bad")))){false}
            assertTrue(receipts.all {it.outcome is DispatchOutcome.NotExecuted})
        }
        assertEquals(0,executions)
    }
    @Test fun unknownStopsRemainingBatchAndBlocksRetryWithoutNewExecution() {
        var executions=0
        val bridge=bridge {executions++;ExecutionReceipt.Unknown()}
        val batch=listOf(call,call.copy(id=ToolCallId("second")))
        val receipts=bridge.execute(owner,batch){false}
        assertTrue(receipts[0].outcome is DispatchOutcome.Unknown)
        assertTrue(receipts[1].outcome is DispatchOutcome.NotExecuted)
        assertTrue(bridge.execute(owner,batch){false}[0].outcome is DispatchOutcome.Unknown)
        assertEquals(1,executions)
    }
    @Test fun wrongRunAndSingleCallRouteCannotExecute() {
        var executions=0
        val bridge=bridge {executions++;ExecutionReceipt.Confirmed("ok")}
        try {bridge.execute(owner.copy(runId=RunId("other")),listOf(call)){false};fail("wrong owner accepted")}
        catch (_:IllegalArgumentException) {}
        try {bridge.execute(call);fail("single route accepted")} catch (_:IllegalStateException) {}
        assertTrue(bridge.execute(owner,listOf(call)){true}.single().outcome is DispatchOutcome.NotExecuted)
        assertEquals(0,executions)
    }
}
