package app.daiyu.android.model.usage

import android.app.Application
import android.content.Context
import android.test.InstrumentationTestCase
import app.daiyu.android.*
import app.daiyu.android.delegation.*
import app.daiyu.android.domain.*
import app.daiyu.android.domain.delegation.ChildTaskStatus
import app.daiyu.android.model.catalog.CatalogCommitResult
import app.daiyu.android.model.catalog.CatalogModelParameters
import app.daiyu.android.model.catalog.SavedCatalogModel
import app.daiyu.android.storage.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@Suppress("DEPRECATION")
class ControllerUsageAttributionTest : InstrumentationTestCase() {
    private class FixtureApplication(base: Context) : Application() { init { attachBaseContext(base) } }
    private class Fixture(base: Context, val failChild: Boolean = false) {
        val id = UUID.randomUUID().toString()
        val app = FixtureApplication(testContext(base, "controller-usage-$id-"))
        val database = ConversationDatabase(app)
        val alias = "app.daiyu.android.test.usage.$id"
        val settings = ModelSettingsStore(app, alias)
        val childOpened = AtomicInteger(); val parentOpened = AtomicInteger()
        val child = SubAgentModel(UUID.randomUUID().toString(), "child-api")
        val provider = SubAgentProvider("primary", "Fixture", listOf(child))
        val profile = SubAgentProfile("review", "Reviewer", "review", providerId = provider.id, modelId = child.id)
        val preferences = ConversationSubAgentPreferences(app.getSharedPreferences("subagent_configuration", 0))
        val controller: ChatController
        lateinit var conversation: Conversation
        init {
            settings.save(ModelSettings("https://example.invalid", "parent-api", "synthetic-key", false))
            val catalog = settings.modelCatalogSnapshot()
            check(settings.modelCatalog.compareAndAppend(catalog.providerIdentity, catalog.revision,
                listOf(SavedCatalogModel(child.modelId, child.displayName,
                    CatalogModelParameters(localIdentity = child.id)))) { false }
                == CatalogCommitResult.COMMITTED)
            controller = ChatController(app, database, settings, app.getSharedPreferences("conversation-ui", 0), { config ->
                object : ModelPort {
                    override fun open(history: List<Message>): ModelSession = error("explicit definitions required")
                    override fun open(history: List<Message>, definitions: List<ToolDefinition>): ModelSession {
                        val isChild = config.model == "child-api"
                        if (isChild) { check(definitions.isEmpty()); childOpened.incrementAndGet() }
                        else { check(config.model == "parent-api"); parentOpened.incrementAndGet(); check(definitions.any { it.name == "run_child_models" }) }
                        var rounds = 0
                        return object : ModelSession {
                            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                                if (isChild) {
                                    sink(ModelEvent.Usage(2, 9, 1))
                                    sink(ModelEvent.Usage(5, null, 2))
                                    if (failChild) throw ModelRequestException(ModelFailure.NETWORK)
                                    sink(ModelEvent.TextFinal("child", "independent answer"))
                                } else if (rounds++ == 0) {
                                    sink(ModelEvent.Usage(10, 2, 4))
                                    sink(ModelEvent.ToolReady(ToolInvocation(ToolCallId("child-call"), "run_child_models",
                                        """{"tasks":[{"profile_id":"review","prompt":"review task"}]}""")))
                                }
                                else {
                                    check(outputs.single().callId == ToolCallId("child-call"))
                                    check(outputs.single().output.contains("independent answer")); sink(ModelEvent.Usage(10, 4, 4)); sink(ModelEvent.TextFinal("parent", "combined answer"))
                                }
                            }
                            override fun cancel() {}
                        }
                    }
                }
            })
        }
        fun prepare() {
            conversation = database.create("default"); database.draft(conversation.id, "preserve draft")
            val owner = SubAgentConfigKey.Conversation(conversation.id.value)
            preferences.createConversation(owner)
            check(preferences.update(owner) { it.copy(enabled = true, profiles = listOf(profile)) } is ConversationSubAgentPreferences.WriteResult.Saved)
        }
        fun close() {
            controller.stop(); controller.close()
            app.getSharedPreferences("model-settings", 0).edit().clear().commit()
            app.getSharedPreferences("conversation-ui", 0).edit().clear().commit()
            app.getSharedPreferences("subagent_configuration", 0).edit().clear().commit()
            database.runtimeLockFile.delete(); app.deleteDatabase("conversations.db")
            val keys = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            java.util.Collections.list(keys.aliases()).filter { it == alias || it.startsWith("$alias.") }.forEach { keys.deleteEntry(it) }
        }
    }
    private val fixtures = mutableListOf<Fixture>()
    private fun fresh(failChild: Boolean = false) = Fixture(instrumentation.targetContext, failChild).also {
        fixtures.add(it); awaitControl("recovery ready") { it.controller.ready }; it.prepare()
    }
    override fun tearDown() { try { fixtures.forEach { it.close() } } finally { super.tearDown() } }

    private fun send(f: Fixture) {
        assertNotNull(f.controller.sendWithChildren(f.conversation, "parent task"))
        awaitControl("child and parent complete") { f.controller.active == null }
    }
    fun testControllerCollectsOwnAndChildRequestsAndReadDoesNotReopenModels() {
        val f = fresh(); send(f)
        assertNull(f.controller.problem)
        val stats = f.controller.recordedRequestUsage(f.conversation.id)
        assertEquals(2L, stats.summary.main.requestSnapshotCount)
        assertEquals(1L, stats.summary.child.requestSnapshotCount)
        assertEquals(25L, stats.summary.combined.input.reportedTokens)
        assertEquals(6L, stats.summary.combined.output.reportedTokens)
        assertEquals(1L, stats.summary.combined.output.unknownRequestCount)
        assertEquals(10L, stats.summary.combined.cached.reportedTokens)
        assertTrue(stats.scanComplete)
        assertEquals(0L, f.controller.recordedRequestUsage(ConversationId("missing")).summary.combined.requestSnapshotCount)
        val rows = f.database.messages(f.conversation.id).map { it.second }
        val again = f.controller.recordedRequestUsage(f.conversation.id)
        assertEquals(stats, again); assertEquals(rows, f.database.messages(f.conversation.id).map { it.second })
        assertEquals(1, f.parentOpened.get()); assertEquals(1, f.childOpened.get())
        controlMain {
            try { f.controller.recordedRequestUsage(f.conversation.id); fail("read must run on a worker") }
            catch (_: IllegalStateException) {}
        }
        val reopened = ConversationDatabase(f.app)
        try {
            val persisted = app.daiyu.android.context.DatabaseTransactions { reopened.readableDatabase }.run {
                RecordedUsageReader.readAttributed(it, f.conversation.id)
            }
            assertEquals(stats, persisted)
        } finally { reopened.close() }
    }
    fun testFailedChildKeepsReportedUsageAndUnknownStateWithoutReplay() {
        val f = fresh(failChild = true); send(f)
        assertEquals(ChildTaskStatus.UNKNOWN, f.controller.childTasks(f.conversation.id).single().status)
        val stats = f.controller.recordedRequestUsage(f.conversation.id)
        assertEquals(1L, stats.summary.main.requestSnapshotCount)
        assertEquals(1L, stats.summary.child.requestSnapshotCount)
        assertEquals(5L, stats.summary.child.input.reportedTokens)
        assertNull(stats.summary.child.output.reportedTokens)
        assertEquals(1, f.childOpened.get()); assertEquals(1, f.parentOpened.get())
    }
}
