package app.daiyu.android

import android.app.Application
import android.content.Context
import android.test.InstrumentationTestCase
import app.daiyu.android.delegation.*
import app.daiyu.android.domain.*
import app.daiyu.android.domain.delegation.ChildTaskStatus
import app.daiyu.android.model.catalog.CatalogCommitResult
import app.daiyu.android.model.catalog.CatalogModelParameters
import app.daiyu.android.model.catalog.SavedCatalogModel
import app.daiyu.android.storage.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Suppress("DEPRECATION")
class ControllerChildModelsTest : InstrumentationTestCase() {
    private class FixtureApplication(base: Context) : Application() { init { attachBaseContext(base) } }
    private class Fixture(base: Context, val held: Boolean = false, val background: Boolean = false,
        val sessionAware: Boolean = false, val childModel: String = "child-api") {
        private val balances = fixtureProviderBalances()
        val sessionOwners = java.util.Collections.synchronizedList(mutableListOf<Pair<String, ConversationId>>())
        val id = UUID.randomUUID().toString()
        val app = FixtureApplication(testContext(base, "controller-child-$id-"))
        val database = ConversationDatabase(app)
        val alias = "app.daiyu.android.test.child.$id"
        val settings = ModelSettingsStore(app, alias)
        val childOpened = AtomicInteger(); val parentOpened = AtomicInteger()
        val childSettings = java.util.concurrent.CopyOnWriteArrayList<ModelSettings>()
        val entered = CountDownLatch(1); val cancelled = CountDownLatch(1); val release = CountDownLatch(if (held) 1 else 0)
        val child = SubAgentModel(UUID.randomUUID().toString(), childModel)
        val provider = SubAgentProvider("primary", "Fixture", listOf(child))
        val profile = SubAgentProfile("review", "Reviewer", "review", providerId = provider.id, modelId = child.id)
        val preferences = ConversationSubAgentPreferences(app.getSharedPreferences("subagent_configuration", 0))
        val toolName = if (background) "start_child_tasks" else "run_child_models"
        var expectedDelegation = true
        val controller: ChatController
        lateinit var conversation: Conversation
        init {
            settings.save(ModelSettings("https://example.invalid", "parent-api", "synthetic-key", false))
            val catalog = settings.modelCatalogSnapshot()
            check(settings.modelCatalog.compareAndAppend(catalog.providerIdentity, catalog.revision,
                listOf(SavedCatalogModel(child.modelId, child.displayName,
                    CatalogModelParameters(localIdentity = child.id)))) { false }
                == CatalogCommitResult.COMMITTED)
            val factory: (ModelSettings) -> ModelPort = { config ->
                object : CatalogFixturePort {
                    override fun open(history: List<Message>): ModelSession = error("explicit definitions required")
                    override fun open(history: List<Message>, definitions: List<ToolDefinition>): ModelSession {
                        val isChild = config.model == child.modelId
                        if (isChild) { check(definitions.isEmpty()); childSettings += config; childOpened.incrementAndGet() }
                        else {
                            check(config.model == "parent-api"); parentOpened.incrementAndGet()
                            check(definitions.any { it.name == "run_child_models" } == expectedDelegation)
                            check(definitions.any { it.name == "start_child_tasks" } == expectedDelegation)
                        }
                        var rounds = 0
                        return object : ModelSession {
                            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                                if (isChild) {
                                    entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); sink(ModelEvent.Usage(7, 3)); sink(ModelEvent.TextFinal("child", "independent answer"))
                                } else if (!expectedDelegation) sink(ModelEvent.TextFinal("parent", "delegation disabled"))
                                else if (rounds++ == 0) sink(ModelEvent.ToolReady(ToolInvocation(ToolCallId("child-call"), toolName,
                                    """{"tasks":[{"profile_id":"review","prompt":"review task"}]}""")))
                                else {
                                    check(outputs.single().callId == ToolCallId("child-call"))
                                    check(outputs.single().output.contains(if (background) "accepted" else "independent answer")); sink(ModelEvent.TextFinal("parent", "combined answer"))
                                }
                            }
                            override fun cancel() { if (isChild) cancelled.countDown() }
                        }
                    }
                }
            }
            controller = ChatController(app, database, settings, app.getSharedPreferences("conversation-ui", 0),
                if (sessionAware) { _ -> error("session-aware path must not fall back") } else factory,
                sessionModel = if (!sessionAware) null else { p, _, session ->
                    sessionOwners.add(p.model to session.conversationId)
                    factory(p.asModelSettings())
                }, providerBalanceHost = { balances })
        }
        fun prepare() {
            conversation = database.create("default"); database.draft(conversation.id, "preserve draft")
            val owner = SubAgentConfigKey.Conversation(conversation.id.value)
            preferences.createConversation(owner)
            check(preferences.update(owner) { it.copy(enabled = true, profiles = listOf(profile)) } is ConversationSubAgentPreferences.WriteResult.Saved)
        }
        fun close() {
            release.countDown(); controller.stop(); controller.close(); balances.close()
            app.getSharedPreferences("model-settings", 0).edit().clear().commit()
            app.getSharedPreferences("conversation-ui", 0).edit().clear().commit()
            app.getSharedPreferences("subagent_configuration", 0).edit().clear().commit()
            database.runtimeLockFile.delete(); app.deleteDatabase("conversations.db")
            val keys = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            java.util.Collections.list(keys.aliases()).filter { it == alias || it.startsWith("$alias.") }.forEach { keys.deleteEntry(it) }
        }
    }
    private val fixtures = mutableListOf<Fixture>()
    private fun fresh(held: Boolean = false, background: Boolean = false, sessionAware: Boolean = false, childModel: String = "child-api") =
        Fixture(instrumentation.targetContext, held, background, sessionAware, childModel).also {
        fixtures.add(it); awaitControl("recovery ready") { it.controller.ready }; it.prepare()
    }
    override fun tearDown() { try { fixtures.forEach { it.close() } } finally { super.tearDown() } }

    private fun setCollaboration(f: Fixture, enabled: Boolean) {
        f.expectedDelegation = enabled
        check(f.preferences.update(SubAgentConfigKey.Conversation(f.conversation.id.value)) {
            it.copy(enabled = enabled)
        } is ConversationSubAgentPreferences.WriteResult.Saved)
    }
    private fun finishParent(f: Fixture) {
        awaitControl("parent completed") { f.controller.active == null && !f.controller.dataBusy }
        assertNull(f.controller.problem)
        val parent = f.database.messages(f.conversation.id).last().second
        assertEquals(Message.ASSISTANT_ROLE, parent.role)
        assertEquals(RunStatus.COMPLETED, parent.status)
        assertNull(parent.error)
        assertEquals(if (f.expectedDelegation) "combined answer" else "delegation disabled",
            parent.blocks.filterIsInstance<MessageBlock.Text>().single().text)
    }
    private fun resend(f: Fixture, editing: Boolean) {
        val snapshotReady = CountDownLatch(1)
        val snapshot = java.util.concurrent.atomic.AtomicReference<app.daiyu.android.storage.MessageActionSnapshot>()
        controlMain { assertTrue(f.controller.loadMessageActionSnapshot(f.conversation.id) { snapshot.set(it); snapshotReady.countDown() }) }
        assertTrue(snapshotReady.await(10, TimeUnit.SECONDS))
        val rows = f.database.messages(f.conversation.id).map { it.second }
        val row = if (editing) rows.first { it.role == Message.USER_ROLE } else rows.last { it.role == Message.ASSISTANT_ROLE }
        val resultReady = CountDownLatch(1)
        val result = java.util.concurrent.atomic.AtomicReference<app.daiyu.android.MessageActionResult>()
        controlMain { assertTrue(f.controller.messageAction(
            if (editing) app.daiyu.android.MessageAction.SAVE_EDIT else app.daiyu.android.MessageAction.REGENERATE,
            snapshot.get().targets.getValue(row.id.value), replacementText = if (editing) "edited child task" else null) {
                result.set(it); resultReady.countDown()
            }) }
        assertTrue(resultReady.await(10, TimeUnit.SECONDS))
        assertEquals(app.daiyu.android.RegenerationSendOutcome.ACCEPTED,
            (result.get() as app.daiyu.android.MessageActionResult.Regenerated).outcome)
        finishParent(f)
        assertEquals(1, f.database.conversations().size)
        assertEquals(rows.first().id, f.database.messages(f.conversation.id).first().second.id)
        if (editing) assertEquals("edited child task", f.database.messages(f.conversation.id).first().second.blocks
            .filterIsInstance<MessageBlock.Text>().single().text)
    }
    fun testOrdinarySendUsesSavedCollaborationForAnEmptyExistingConversation() {
        for (enabled in listOf(true, false)) {
            val f = fresh(); setCollaboration(f, enabled)
            assertNotNull(f.controller.send(f.conversation, "ordinary parent")); finishParent(f)
            assertEquals(if (enabled) 1 else 0, f.childOpened.get())
            assertEquals(f.conversation.id, f.database.conversations().single().id)
        }
    }
    fun testRegenerateAndEditRereadEnabledConfigurationInsteadOfDroppingDelegation() {
        for (editing in listOf(false, true)) {
            val f = fresh(); setCollaboration(f, false)
            assertNotNull(f.controller.send(f.conversation, "original task")); finishParent(f)
            assertEquals(0, f.childOpened.get())
            setCollaboration(f, true); resend(f, editing)
            assertEquals(1, f.childOpened.get()); assertEquals(2, f.parentOpened.get())
            val row = f.database.messages(f.conversation.id).last().second
            val task = f.controller.childTasks(f.conversation.id).single()
            assertEquals(row.runId.value, task.owner.parentRunId)
            assertEquals(row.id.value, task.parentMessageId)
        }
    }
    fun testRegenerateAndEditDoNotReenableClosedCollaborationFromHistory() {
        for (editing in listOf(false, true)) {
            val f = fresh()
            assertNotNull(f.controller.send(f.conversation, "original child")); finishParent(f)
            assertEquals(1, f.childOpened.get())
            setCollaboration(f, false); resend(f, editing)
            assertEquals(1, f.childOpened.get()); assertEquals(2, f.parentOpened.get())
            assertFalse(f.preferences.snapshot(SubAgentConfigKey.Conversation(f.conversation.id.value)).enabled)
        }
    }
    fun testNamedChildEntryRespectsTheConversationOffSwitch() {
        val f = fresh(); setCollaboration(f, false)
        assertNotNull(f.controller.sendWithChildren(f.conversation, "disabled child")); finishParent(f)
        assertEquals(0, f.childOpened.get())
    }

    fun testSessionAwareChildAndParentToolContinuationUseTheDurableParentConversation() {
        val f = fresh(sessionAware = true)
        assertNotNull(f.controller.sendWithChildren(f.conversation, "session parent"))
        awaitControl("parent tool continuation completed") { f.controller.active == null }
        assertNull(f.controller.problem)
        assertEquals(1, f.parentOpened.get()); assertEquals(1, f.childOpened.get())
        assertEquals(setOf("parent-api", "child-api"), f.sessionOwners.map { it.first }.toSet())
        assertTrue(f.sessionOwners.all { it.second == f.conversation.id })
        val task = f.controller.childTasks(f.conversation.id).single()
        assertEquals(f.conversation.id.value, task.owner.conversationId)
        assertFalse(task.owner.childRunId == f.conversation.id.value)
        val assistant = f.database.messages(f.conversation.id).map { it.second }.single { it.role == Message.ASSISTANT_ROLE }
        assertTrue(assistant.blocks.filterIsInstance<MessageBlock.Tool>().single().result!!.contains("independent answer"))
        val command = app.daiyu.android.domain.delegation.ChildTaskCommand("gateway-append-${f.id}", task,
            app.daiyu.android.domain.delegation.ChildTaskCommandKind.APPEND, f.profile.id, "additional constraint")
        val accepted = f.controller.directChildTask(command)
        assertTrue(accepted is app.daiyu.android.domain.delegation.ChildTaskCommandReceipt.Accepted)
        awaitControl("direct child append drained") { !f.controller.hasLiveChildTasks() }
        assertEquals(2, f.childOpened.get()); assertEquals(1, f.parentOpened.get())
        assertTrue(f.sessionOwners.all { it.second == f.conversation.id })
        assertEquals(3, f.sessionOwners.size)
    }

    fun testSessionAwareMissingSavedSelectionRejectsWithoutAnyFactory() {
        val f = fresh(sessionAware = true)
        f.preferences.update(SubAgentConfigKey.Conversation(f.conversation.id.value)) {
            it.copy(profiles = listOf(f.profile.copy(modelId = "deleted-selection")))
        }
        assertNull(f.controller.sendWithChildren(f.conversation, "missing selection"))
        assertTrue(f.sessionOwners.isEmpty()); assertEquals(0, f.parentOpened.get()); assertEquals(0, f.childOpened.get())
        assertTrue(f.database.messages(f.conversation.id).isEmpty())
        assertEquals("preserve draft", f.database.conversations().single { it.id == f.conversation.id }.draft)
    }

    fun testSavedConfigurationRunsWithoutShieldAndPersistsParentReceiptAndTaskRecord() {
        val f = fresh()
        assertNotNull(f.controller.sendWithChildren(f.conversation, "parent task"))
        awaitControl("completed with child receipt") { f.controller.active == null }
        assertNull(f.controller.problem); assertEquals(1, f.parentOpened.get()); assertEquals(1, f.childOpened.get())
        val rows = f.database.messages(f.conversation.id).map { it.second }
        assertEquals(2, rows.size)
        val assistant = rows.single { it.role == Message.ASSISTANT_ROLE }
        assertEquals(RunStatus.COMPLETED, assistant.status)
        assertTrue(assistant.blocks.filterIsInstance<MessageBlock.Tool>().single().result!!.contains("independent answer"))
        val task = f.controller.childTasks(f.conversation.id).single()
        assertEquals(ChildTaskStatus.COMPLETED, task.status); assertEquals("independent answer", task.text)
        assertEquals(assistant.id.value, task.parentMessageId)
        assertEquals(assistant.runId.value, task.owner.parentRunId)
        assertEquals("review", task.profileId); assertEquals("review task", task.prompt)
        assertTrue(f.controller.childTasks(ConversationId("other")).isEmpty())
        val reopened = ConversationDatabase(f.app)
        try {
            assertEquals(rows, reopened.messages(f.conversation.id).map { it.second })
            assertEquals(listOf(task), SQLiteChildTaskStore(reopened).list(f.conversation.id.value, 20))
        } finally { reopened.close() }
    }

    fun testUnavailableSavedSelectionsNeverOpenOrConsumeDraft() {
        val f = fresh()
        val key = SubAgentConfigKey.Conversation(f.conversation.id.value)
        for (profile in listOf(f.profile.copy(providerId = "deleted-provider"), f.profile.copy(modelId = "deleted-model"))) {
            f.preferences.update(key) { it.copy(profiles = listOf(profile)) }
            assertNull(f.controller.sendWithChildren(f.conversation, "unavailable selection"))
        }
        assertEquals(0, f.parentOpened.get()); assertEquals(0, f.childOpened.get())
        assertTrue(f.database.messages(f.conversation.id).isEmpty())
        assertEquals("preserve draft", f.database.conversations().single { it.id == f.conversation.id }.draft)
    }

    fun testChildOffChoiceReachesTheSelectedModelWithoutLocalRejection() {
        val f = fresh(childModel = "gpt-6.1-sol")
        val key = SubAgentConfigKey.Conversation(f.conversation.id.value)
        f.preferences.update(key) { it.copy(profiles = listOf(f.profile.copy(reasoning = ReasoningEffort.OFF))) }
        assertNotNull(f.controller.sendWithChildren(f.conversation, "retain child choice"))
        awaitControl("child off complete") { f.controller.active == null }
        assertEquals(ReasoningEffort.OFF, f.preferences.snapshot(key).profiles.single().reasoning)
        assertEquals(1, f.parentOpened.get()); assertEquals(1, f.childOpened.get())
        assertEquals("none", org.json.JSONObject(f.childSettings.single().requestOptions.customBody)
            .getJSONObject("reasoning").getString("effort"))
        assertTrue(f.database.messages(f.conversation.id).isNotEmpty())
    }

    fun testStopCancelsChildAndRetainsResourcesUntilActualExit() {
        val f = fresh(true)
        assertNotNull(f.controller.sendWithChildren(f.conversation, "held parent"))
        assertTrue(f.entered.await(5, TimeUnit.SECONDS))
        val owner = checkNotNull(f.controller.active)
        controlMain { assertTrue(f.controller.stop(owner)) }
        assertTrue(f.cancelled.await(5, TimeUnit.SECONDS))
        controlMain { assertNotNull(f.controller.active) }
        assertNull(f.controller.sendWithChildren(f.conversation, "blocked next"))
        f.release.countDown(); awaitControl("child drained and parent released") { f.controller.active == null }
        assertEquals(1, f.parentOpened.get()); assertEquals(1, f.childOpened.get())
        assertEquals(RunStatus.STOPPED, f.database.messages(f.conversation.id).single { it.second.id == owner.id }.second.status)
        assertEquals(ChildTaskStatus.UNKNOWN, f.controller.childTasks(f.conversation.id).single().status)
    }
    fun testBackgroundChildOutlivesParentAndPersistsAttributedUsage() {
        val f = fresh(held = true, background = true)
        assertNotNull(f.controller.sendWithChildren(f.conversation, "background parent"))
        assertTrue(f.entered.await(5, TimeUnit.SECONDS))
        awaitControl("parent released while child is live") { f.controller.active == null }
        assertTrue(f.controller.hasLiveChildTasks(f.conversation.id))
        assertFalse(f.cancelled.await(100, TimeUnit.MILLISECONDS))
        controlMain {
            try { f.controller.dataTask { "must not run" }; fail("backup must wait for live children") }
            catch (_: IllegalStateException) { }
        }
        f.release.countDown()
        awaitControl("background child actually drained") { !f.controller.hasLiveChildTasks() }
        val record = f.controller.childTasks(f.conversation.id).single()
        assertEquals(ChildTaskStatus.COMPLETED, record.status)
        assertEquals("independent answer", record.text)
        val usage = f.controller.recordedRequestUsage(f.conversation.id)
        assertEquals(1L, usage.summary.child.requestSnapshotCount)
        assertEquals(7L, usage.summary.child.input.reportedTokens)
        assertEquals(3L, usage.summary.child.output.reportedTokens)
        assertEquals(1, f.childOpened.get())
    }

    fun testIndependentStopKeepsLeaseUntilChildActuallyExits() {
        val f = fresh(held = true, background = true)
        assertNotNull(f.controller.sendWithChildren(f.conversation, "background stop"))
        assertTrue(f.entered.await(5, TimeUnit.SECONDS))
        awaitControl("parent released") { f.controller.active == null }
        val record = f.controller.childTasks(f.conversation.id).single()
        controlMain { assertEquals(app.daiyu.android.domain.delegation.ChildTaskStop.REQUESTED, f.controller.stopChildTask(record)) }
        assertTrue(f.cancelled.await(5, TimeUnit.SECONDS))
        assertTrue(f.controller.hasLiveChildTasks())
        val second = ConversationDatabase(f.app)
        try {
            try { SQLiteRuntimeStore.acquire(second).close(); fail("live controller owns the lease") }
            catch (_: IllegalStateException) { }
        } finally { second.close() }
        f.release.countDown()
        awaitControl("stopped child actually drained") { !f.controller.hasLiveChildTasks() }
        assertEquals(ChildTaskStatus.UNKNOWN, f.controller.childTasks(f.conversation.id).single().status)
        assertEquals(1, f.childOpened.get())
    }

}
