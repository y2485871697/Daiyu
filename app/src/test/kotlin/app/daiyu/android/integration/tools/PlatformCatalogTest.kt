package app.daiyu.android.integration.tools

import app.daiyu.android.domain.*
import app.daiyu.android.domain.tools.*
import app.daiyu.android.platform.PlatformReadBackend
import app.daiyu.android.platform.PlatformReadTool
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class PlatformCatalogTest {
    private class Fixture(enabled: Set<PlatformReadTool> = PlatformReadTool.entries.toSet(), bind: Boolean = true) : AutoCloseable {
        val message = Message(MessageId("assistant"), ConversationId("conversation"), RunId("run"),
            Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)
        val owner = runnerOwner(message)
        val authorization = TaskAuthorization("run", "run")
        val capabilities = SessionCapabilities("run", AndroidToolRegistry.capabilitiesFor(emptySet(), enabled))
        val reads = AtomicInteger()
        val resources = RunDeviceResources(BatteryTool { error("legacy_backend_must_not_run") },
            PlatformReadBackend { _, _, _ -> reads.incrementAndGet(); """{"ok":true,"scope":"test_only"}""" })
        val store = InMemoryToolLedgerStore()
        val catalog = AndroidToolRegistry.create(resources).snapshot()
        val dispatcher = ToolDispatcher(catalog, capabilities, ToolLedger(store))
        init { if (bind) resources.bind(owner, authorization, capabilities) }
        fun call(tool: PlatformReadTool, args: String = "{}", id: String = tool.toolName) = ToolInvocation(ToolCallId(id), tool.toolName, args)
        fun dispatch(call: ToolInvocation, task: TaskAuthorization = authorization, cancelled: () -> Boolean = { false }) =
            dispatcher.dispatchOwned(task, listOf(call), owner, cancelled).single().outcome
        override fun close() = resources.close()
    }

    @Test fun platformLeavesAreExplicitReadOnlyCapabilitiesNotLegacyToggleExpansion() {
        assertEquals(2, AndroidToolRegistry.capabilitiesFor(ReadOnlyTool.entries.toSet()).size)
        assertEquals(PlatformReadTool.entries.size, PlatformReadTool.entries.map { it.capability }.toSet().size)
        Fixture(enabled = setOf(PlatformReadTool.ENVIRONMENT)).use { f ->
            assertEquals(listOf("device_environment"), f.catalog.available(f.capabilities).map { it.name })
            assertTrue(f.catalog.definitions.all { it.effect == ToolEffect.READ_ONLY })
            assertEquals(DispatchOutcome.NotExecuted("capability_disabled"), f.dispatch(f.call(PlatformReadTool.NETWORK)))
            assertEquals(0, f.reads.get())
            assertTrue(f.dispatch(f.call(PlatformReadTool.ENVIRONMENT)) is DispatchOutcome.Confirmed)
        }
    }

    @Test fun readsRequireExactTaskAndBoundOwnerAndDoNotResampleReplay() {
        Fixture().use { f ->
            val call = f.call(PlatformReadTool.ENVIRONMENT)
            assertEquals(DispatchOutcome.NotExecuted("tool_execution_context_required"),
                f.dispatcher.dispatch(f.authorization, listOf(call)).single().outcome)
            assertEquals(DispatchOutcome.NotExecuted("tool_run_authorization_mismatch"),
                f.dispatch(call, TaskAuthorization("run", "run")))
            assertEquals(0, f.reads.get())
            assertTrue(f.dispatch(call) is DispatchOutcome.Confirmed)
            assertTrue((f.dispatch(call) as DispatchOutcome.Confirmed).replayed)
            assertEquals(DispatchOutcome.NotExecuted("tool_run_authorization_mismatch"),
                f.dispatch(call, TaskAuthorization("run", "run")))
            assertEquals(DispatchOutcome.NotExecuted("tool_run_owner_mismatch"),
                f.dispatcher.dispatchOwned(f.authorization, listOf(call), runnerOwner(f.message)).single().outcome)
            assertEquals(1, f.reads.get())
            f.resources.close()
            assertEquals(DispatchOutcome.NotExecuted("run_closed"), f.dispatch(f.call(PlatformReadTool.ENVIRONMENT, id = "after-close")))
        }
        Fixture(bind = false).use { f ->
            assertEquals(DispatchOutcome.NotExecuted("device_run_not_bound"), f.dispatch(f.call(PlatformReadTool.ENVIRONMENT)))
            assertEquals(0, f.reads.get())
        }
    }

    @Test fun malformedSchemaAndCalendarIntervalRejectWholeBatchBeforeAnyRead() {
        Fixture().use { f ->
            val invalid = listOf(
                f.call(PlatformReadTool.CONTACTS, """{"query":" "}"""),
                f.call(PlatformReadTool.CALENDAR, """{"start_ms":4,"end_ms":3}"""),
                f.call(PlatformReadTool.CALENDAR, """{"start_ms":0,"end_ms":2678400001}"""),
                f.call(PlatformReadTool.MEDIA, """{"kind":"image","limit":51}"""),
                f.call(PlatformReadTool.APPS, """{"query":"x","permission":true}"""),
                f.call(PlatformReadTool.FILE_TEXT, """{"uri":""}"""))
            invalid.forEachIndexed { index, call ->
                val outcomes = f.dispatcher.dispatchOwned(f.authorization,
                    listOf(f.call(PlatformReadTool.ENVIRONMENT, id = "first-$index"), call), f.owner)
                assertTrue(outcomes.all { it.outcome == DispatchOutcome.NotExecuted("invalid_arguments") })
            }
            assertEquals(0, f.reads.get())
            assertTrue(f.store.transaction { it.isEmpty() })
        }
    }

    @Test fun cancelledCallsDoNotRead() {
        Fixture().use { f ->
            assertTrue(f.dispatch(f.call(PlatformReadTool.ENVIRONMENT), cancelled = { true }) is DispatchOutcome.NotExecuted)
            assertEquals(0, f.reads.get())
        }
    }

    @Test fun schemasRejectUnknownFieldsAndBoundAllCollectionsAtFifty() {
        for (tool in PlatformReadTool.entries) {
            assertTrue(tool.schema.jsonSchema().contains("\"additionalProperties\":false"))
            if (tool.schema.fields.any { it.name == "limit" }) {
                assertEquals(ArgumentType.Integer(1, 50), tool.schema.fields.single { it.name == "limit" }.type)
            }
        }
    }
}
