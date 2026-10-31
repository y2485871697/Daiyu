package app.daiyu.android.context

import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.domain.tools.*
import app.daiyu.android.integration.tools.CatalogModelBridge
import app.daiyu.android.integration.tools.ScopedContextTools
import app.daiyu.android.model.MessagesRequest
import app.daiyu.android.model.NativeCompletionRequest
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.model.ResponsesRequest
import app.daiyu.android.model.sessiongateway.SessionGateway
import app.daiyu.android.model.sessiongateway.SessionGatewayContext
import app.daiyu.android.model.sessiongateway.SessionGatewayRule
import app.daiyu.android.storage.ConversationDatabase
import app.daiyu.android.storage.ModelSettings
import app.daiyu.android.storage.SQLiteRuntimeStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ContextToolDefinitionStabilityTest {
    private lateinit var database: ConversationDatabase
    private lateinit var runtime: SQLiteRuntimeStore
    private lateinit var service: ContextService
    private lateinit var registry: ContextRegistry

    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("conversations.db")
        database = ConversationDatabase(context)
        runtime = SQLiteRuntimeStore.acquire(database).also { it.recoverPending() }
        service = ContextService(context, database)
        registry = ContextRegistry(service)
    }

    @After fun cleanup() {
        if (::runtime.isInitialized) runtime.close()
        if (::database.isInitialized) database.close()
        RuntimeEnvironment.getApplication().deleteDatabase("conversations.db")
    }

    private data class Fixture(val owner: ConversationId, val runs: List<RunId>, val archive: String)

    private fun seed(): Fixture {
        val owner = database.create("default").id
        val runs = (0 until 4).map { RunId("run-$it-${owner.value}") }
        runs.forEachIndexed { index, run ->
            val user = Message(MessageId("user-${run.value}"), owner, run, "user",
                listOf(MessageBlock.Text("question", "事实$index ".repeat(80))), RunStatus.COMPLETED)
            val reply = user.copy(id = MessageId("reply-${run.value}"), role = "assistant",
                blocks = listOf(MessageBlock.Text("answer", "回答$index ".repeat(80))), status = RunStatus.RUNNING)
            database.send(user, reply)
            val request = RequestCheckpoint("request-$index-${owner.value}", "attempt-$index-${owner.value}", "owner-${owner.value}", RequestOperation.NEXT)
            runtime.commitCheckpoint(RunCheckpoint(reply.copy(status = RunStatus.COMPLETED), emptyList(),
                ExecutionPhase.TERMINAL, owner = request.owner, revision = 1, request = request,
                usage = listOf(RequestUsageCheckpoint(request, ModelEvent.Usage(100L * (index + 1), 20, 30)))))
        }
        val archive = checkNotNull(service.compress(owner, app.daiyu.android.domain.context.CompressionPolicy(keepTurns = 1),
            ContextSummarizer { "固定摘要" }))
        return Fixture(owner, runs, archive)
    }

    private fun tools(fixture: Fixture, run: RunId = fixture.runs.first(), archive: String? = fixture.archive,
                      scope: ContextRegistryScope = ContextRegistryScope(fixture.owner)) =
        ScopedContextTools.create(registry, scope, run, archive)

    private fun catalog(tools: List<RegisteredTool>) = ToolRegistry().also { registry -> tools.forEach { registry.register(it) } }.snapshot()
    private fun session(catalog: ToolCatalog) = SessionCapabilities("context-test", catalog.definitions.map { it.capability }.toSet())
    private fun definitions(fixture: Fixture, run: RunId = fixture.runs.first(), archive: String? = fixture.archive): List<app.daiyu.android.domain.ToolDefinition> {
        val catalog = catalog(tools(fixture, run, archive))
        return CatalogModelBridge(catalog, session(catalog), ToolLedger(InMemoryToolLedgerStore()), fixture.owner, run,
            TaskAuthorization("context-test", run.value)).definitions
    }

    private fun execute(tools: List<RegisteredTool>, name: String, json: String = "{}"): JSONObject {
        val tool = tools.single { it.definition.name == name }
        val receipt = tool.prepare(tool.definition.schema.parse(json)).execute() as ExecutionReceipt.Confirmed
        val result = JSONObject(receipt.payload)
        assertEquals(!result.getBoolean("ok"), receipt.isError)
        return result
    }

    private fun denied(result: JSONObject) {
        assertFalse(result.toString(), result.getBoolean("ok"))
        assertEquals("invalid_arguments_or_archive_unavailable", result.getString("error"))
        assertFalse(result.has("messages")); assertFalse(result.has("requests")); assertFalse(result.has("total"))
    }

    @Test fun completeDefinitionsStayEqualAcrossRunsConversationsAndArchiveLocators() {
        val a = seed(); val b = seed()
        val first = definitions(a)
        val next = definitions(a, a.runs.last(), b.archive)
        assertEquals(first, next)
        assertEquals(first, definitions(b))
        val metadata = catalog(tools(a)).definitions.map { ToolAdvertisement.from(it).toJson() }
        assertEquals(metadata, catalog(tools(b)).definitions.map { ToolAdvertisement.from(it).toJson() })
        for (identity in listOf(a.owner.value, b.owner.value, a.archive, b.archive) + a.runs.map { it.value } + b.runs.map { it.value }) {
            assertFalse(metadata.joinToString().contains(identity))
        }
        for (definition in first) {
            val schema = JSONObject(definition.parameters)
            assertEquals(0, schema.getJSONArray("required").length())
            assertFalse(schema.getBoolean("additionalProperties"))
        }
        assertEquals(listOf("context_usage"), tools(a, archive = null).map { it.definition.name })
        val runOnly = ContextRegistryScope(a.owner, authorizedRuns = setOf(ContextRunOwner(a.owner, a.runs.first())))
        assertEquals(listOf("context_usage"), tools(a, scope = runOnly).map { it.definition.name })
    }

    @Test fun everyProtocolSendsStableToolsAndSessionGatewayKeepsTheDurableConversation() {
        val a = seed()
        val first = definitions(a)
        val next = definitions(a, a.runs.last())
        fun wire(definitions: List<app.daiyu.android.domain.ToolDefinition>): List<JSONObject> = listOf(
            ResponsesRequest.body("gpt-fixture", JSONArray(), definitions),
            MessagesRequest.body("fixture", JSONArray(), definitions, 1000),
            NativeCompletionRequest.body(ModelSettings("https://example.com/v1", "fixture", "", false,
                protocol = ProviderProtocol.CHAT_COMPLETIONS), JSONArray(), definitions, "fixed instructions"),
            NativeCompletionRequest.body(ModelSettings("https://example.com", "fixture", "", false,
                protocol = ProviderProtocol.GEMINI), JSONArray(), definitions, "fixed instructions"))
        val before = wire(first); val after = wire(next)
        before.indices.forEach { index ->
            assertEquals(before[index].getJSONArray("tools").toString(), after[index].getJSONArray("tools").toString())
        }
        val context = SessionGatewayContext(a.owner)
        val rule = SessionGatewayRule()
        for (request in listOf(before.first(), after.first())) {
            SessionGateway.applyResponses(request, "https://example.com/v1/responses", rule, context)
            assertEquals(a.owner.value, request.getString("prompt_cache_key"))
        }
        assertEquals(mapOf("session-id" to a.owner.value, "thread-id" to a.owner.value),
            SessionGateway.headers("https://example.com/v1/responses", context, rule))
    }

    @Test fun omittedAndExplicitUsageTargetsKeepTheirOriginalMeanings() {
        val a = seed()
        val bound = tools(a, a.runs[1])
        assertEquals(200L, execute(bound, "context_usage").getLong("input_tokens"))
        assertEquals(100L, execute(bound, "context_usage", JSONObject().put("run_id", a.runs.first().value).toString()).getLong("input_tokens"))
        val branchOnly = JSONObject().put("branch_id", a.owner.value).toString()
        assertEquals(1000L, execute(bound, "context_usage", branchOnly).getLong("input_tokens"))
        val explicit = JSONObject().put("branch_id", a.owner.value).put("run_id", a.runs.last().value).toString()
        assertEquals(400L, execute(bound, "context_usage", explicit).getLong("input_tokens"))
        val usageTool = bound.single { it.definition.name == "context_usage" }
        val arguments = usageTool.definition.schema.parse("{}")
        usageTool.prepare(arguments)
        assertEquals("{}", arguments.canonicalJson())
        denied(execute(bound, "context_usage", "{\"run_id\":\"unknown\"}"))
        val shared = seed()
        val granted = tools(a, scope = ContextRegistryScope(a.owner, setOf(shared.owner)))
        assertEquals(1000L, execute(granted, "context_usage", JSONObject().put("branch_id", shared.owner.value).toString()).getLong("input_tokens"))
    }

    @Test fun identicalToolCallsAcrossRunsNeverReplayAnotherRunsResultOrWidenItsScope() {
        val a = seed(); val b = seed()
        val ledger = ToolLedger(InMemoryToolLedgerStore())
        fun dispatch(fixture: Fixture, run: RunId, json: String, call: String): DispatchOutcome {
            val catalog = catalog(tools(fixture, run))
            val runSession = SessionCapabilities(run.value, catalog.definitions.map { it.capability }.toSet())
            return ToolDispatcher(catalog, runSession, ledger).dispatch(TaskAuthorization(run.value, run.value),
                listOf(ToolInvocation(ToolCallId(call), "context_usage", json))).single().outcome
        }
        fun input(fixture: Fixture, run: RunId): Long {
            val result = dispatch(fixture, run, "{}", "same-call") as DispatchOutcome.Confirmed
            assertFalse(result.isError)
            return JSONObject(result.payload).getLong("input_tokens")
        }
        assertEquals(100L, input(a, a.runs.first()))
        assertEquals(400L, input(a, a.runs.last()))
        assertEquals(200L, input(b, b.runs[1]))
        val foreign = dispatch(b, b.runs[1], JSONObject().put("branch_id", a.owner.value).toString(), "foreign") as DispatchOutcome.Confirmed
        assertTrue(foreign.isError); denied(JSONObject(foreign.payload))
        database.delete(a.owner)
        val deleted = dispatch(a, a.runs.first(), "{}", "after-delete") as DispatchOutcome.Confirmed
        assertTrue(deleted.isError); denied(JSONObject(deleted.payload))
        assertEquals(200L, input(b, b.runs[1]))
    }

    @Test fun defaultsStillUseRunGrantsAndRejectForeignOrUnknownHostBindings() {
        val a = seed(); val b = seed()
        val grant = ContextRegistryScope(a.owner, authorizedRuns = setOf(ContextRunOwner(a.owner, a.runs.first())))
        val bound = tools(a, scope = grant)
        assertEquals(100L, execute(bound, "context_usage").getLong("input_tokens"))
        denied(execute(bound, "context_usage", JSONObject().put("branch_id", a.owner.value).toString()))
        denied(execute(bound, "context_usage", JSONObject().put("run_id", a.runs.last().value).toString()))
        denied(execute(tools(a, b.runs.first()), "context_usage"))
        denied(execute(tools(a, RunId("unknown-run")), "context_usage"))
        denied(execute(tools(a, scope = ContextRegistryScope(a.owner, authorizedRuns = emptySet())), "context_usage"))
        assertFalse(registry.forModel(ContextRegistryScope(a.owner)).execute("context_usage", JSONObject()).getBoolean("ok"))
    }

    @Test fun archiveDefaultsPreserveExplicitHistoricalIdsAndPersistedOwnerChecks() {
        val a = seed(); val b = seed()
        val bound = tools(a)
        assertEquals(a.archive, execute(bound, "context_archive_page").getString("archive_id"))
        val page = execute(bound, "context_archive_page", "{\"offset\":2,\"limit\":2}")
        assertEquals(2, page.getJSONArray("messages").length())
        val granted = tools(a, scope = ContextRegistryScope(a.owner, setOf(b.owner)))
        val explicit = JSONObject().put("archive_id", b.archive).toString()
        assertEquals(b.archive, execute(granted, "context_archive_page", explicit).getString("archive_id"))
        denied(execute(bound, "context_archive_page", explicit))
        denied(execute(tools(a, archive = b.archive), "context_archive_page"))
        denied(execute(tools(a, archive = "unknown-archive"), "context_archive_page"))
        database.writableDatabase.execSQL("UPDATE context_archives SET conversation_id=? WHERE id=?", arrayOf(a.owner.value, b.archive))
        denied(execute(tools(a, archive = b.archive), "context_archive_page"))
        denied(execute(tools(a, archive = b.archive), "context_archive_page", "{\"offset\":100}"))
    }

    @Test fun optionalIdsDoNotAcceptNullWrongTypesEmptyIdsOrForgedAuthority() {
        val a = seed()
        val bound = tools(a)
        for ((name, values) in mapOf(
            "context_usage" to listOf("{\"branch_id\":null}", "{\"run_id\":null}", "{\"branch_id\":42}", "{\"branch_id\":\"\"}",
                "{\"run_id\":\"\"}", "{\"scope\":{}}", "{\"conversation_id\":\"other\"}"),
            "context_archive_page" to listOf("{\"archive_id\":null}", "{\"archive_id\":42}", "{\"archive_id\":\"\"}",
                "{\"offset\":-1}", "{\"limit\":0}", "{\"limit\":201}", "{\"owner\":\"other\"}"))) {
            val schema = bound.single { it.definition.name == name }.definition.schema
            for (json in values) assertNotNull(json, runCatching { schema.parse(json) }.exceptionOrNull())
        }
    }
}
