package app.daiyu.android

import android.app.Application
import android.content.Context
import android.os.Looper
import android.test.InstrumentationTestCase
import app.daiyu.android.delegation.*
import app.daiyu.android.domain.*
import app.daiyu.android.domain.delegation.ChildTaskStatus
import app.daiyu.android.domain.delegation.ChildTaskCommand
import app.daiyu.android.domain.delegation.ChildTaskCommandKind
import app.daiyu.android.domain.delegation.ChildTaskCommandReceipt
import app.daiyu.android.domain.delegation.ChildTaskStop
import app.daiyu.android.model.catalog.CatalogCommitResult
import app.daiyu.android.model.catalog.CatalogModelParameters
import app.daiyu.android.model.catalog.SavedCatalogModel
import app.daiyu.android.model.catalog.selectionFor
import app.daiyu.android.storage.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real Controller/Runner/store; reflection only observes retained handles, never changes state. */
@Suppress("DEPRECATION")
class ControllerChildRetentionTest : InstrumentationTestCase() {
    private class FixtureApplication(base: Context) : Application() {
        val balances = fixtureProviderBalances()
        init { attachBaseContext(base) } }
    private class Fixture(base: Context, val submit: Boolean = true, holdParent: Boolean = false,
        holdChildCleanup: Boolean = false) {
        val id = UUID.randomUUID().toString()
        val app = FixtureApplication(testContext(base, "controller-retention-$id-"))
        val database = ConversationDatabase(app)
        val alias = "app.daiyu.android.test.retention.$id"
        val settings = ModelSettingsStore(app, alias)
        val childEntered = CountDownLatch(1)
        val childCancelled = CountDownLatch(1)
        val childRelease = CountDownLatch(1)
        val childExited = CountDownLatch(1)
        val childTimeouts = AtomicInteger()
        val childWorker = AtomicReference<Thread>()
        val childCleanupRelease = CountDownLatch(if (holdChildCleanup) 1 else 0)
        val childCleanupExited = CountDownLatch(1)
        val childCleanupTimeouts = AtomicInteger()
        val childCancels = AtomicInteger()
        val parentEntered = CountDownLatch(1)
        val parentRelease = CountDownLatch(if (holdParent) 1 else 0)
        val childOpens = AtomicInteger()
        val childHistories = java.util.Collections.synchronizedList(mutableListOf<List<Message>>())
        val parentOpens = AtomicInteger()
        val child = SubAgentModel(UUID.randomUUID().toString(), "child-api")
        val provider = SubAgentProvider("primary", "Fixture", listOf(child))
        val profile = SubAgentProfile("review", "Reviewer", "review", providerId = provider.id, modelId = child.id)
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
                object : CatalogFixturePort {
                    override fun open(history: List<Message>): ModelSession = error("explicit definitions required")
                    override fun open(history: List<Message>, definitions: List<ToolDefinition>): ModelSession {
                        val isChild = config.model == "child-api"
                        if (isChild) { check(definitions.isEmpty()); childHistories.add(history.toList()); childOpens.incrementAndGet() }
                        else { check(definitions.any { it.name == "start_child_tasks" }); parentOpens.incrementAndGet() }
                        var round = 0
                        return object : ModelSession {
                            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                                if (isChild) {
                                    childWorker.set(Thread.currentThread())
                                    childEntered.countDown()
                                    try {
                                        val released = childRelease.await(30, TimeUnit.SECONDS)
                                        if (!released) childTimeouts.incrementAndGet()
                                        check(released) { "child fixture timed out instead of being released" }
                                        sink(ModelEvent.TextFinal("child", "retained answer"))
                                    } finally { childExited.countDown() }
                                } else if (submit && round++ == 0) {
                                    sink(ModelEvent.ToolReady(ToolInvocation(ToolCallId("retained-call"), "start_child_tasks",
                                        """{"tasks":[{"profile_id":"review","prompt":"review once"}]}""")))
                                } else {
                                    if (submit) check(outputs.single().output.contains("accepted"))
                                    parentEntered.countDown()
                                    check(parentRelease.await(10, TimeUnit.SECONDS))
                                    sink(ModelEvent.TextFinal("parent", "parent answer"))
                                }
                            }
                            override fun cancel() {
                                if (!isChild) { parentRelease.countDown(); return }
                                childCancels.incrementAndGet()
                                childCancelled.countDown()
                                try {
                                    val released = childCleanupRelease.await(30, TimeUnit.SECONDS)
                                    if (!released) childCleanupTimeouts.incrementAndGet()
                                    check(released) { "child cleanup timed out instead of being released" }
                                } finally { childCleanupExited.countDown() }
                            }
                        }
                    }
                }
            }, providerBalanceHost = { app.balances })
        }
        fun prepare() {
            conversation = database.create("default")
            val owner = SubAgentConfigKey.Conversation(conversation.id.value)
            val preferences = ConversationSubAgentPreferences(app.getSharedPreferences("subagent_configuration", 0))
            preferences.createConversation(owner)
            check(preferences.update(owner) { it.copy(enabled = true, profiles = listOf(profile)) }
                is ConversationSubAgentPreferences.WriteResult.Saved)
        }
        fun dispose() {
            childRelease.countDown(); childCleanupRelease.countDown(); parentRelease.countDown(); controller.close(); app.balances.close()
            app.getSharedPreferences("model-settings", 0).edit().clear().commit()
            app.getSharedPreferences("conversation-ui", 0).edit().clear().commit()
            app.getSharedPreferences("subagent_configuration", 0).edit().clear().commit()
            database.runtimeLockFile.delete(); app.deleteDatabase("conversations.db")
            val keys = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            java.util.Collections.list(keys.aliases()).filter { it == alias || it.startsWith("$alias.") }.forEach { keys.deleteEntry(it) }
        }
    }
    private val fixtures = mutableListOf<Fixture>()
    private fun main(work: () -> Unit) {
        val failure = AtomicReference<Throwable?>()
        instrumentation.runOnMainSync { try { work() } catch (t: Throwable) { failure.set(t) } }
        failure.get()?.let { throw it }
    }
    private fun awaitChange(f: Fixture, label: String, predicate: () -> Boolean) {
        val done = CountDownLatch(1)
        val listener: () -> Unit = { if (predicate()) done.countDown() }
        main { f.controller.observe(listener); listener() }
        try { assertTrue(label, done.await(5, TimeUnit.SECONDS)) }
        finally { main { f.controller.unobserve(listener) } }
    }
    private fun fresh(submit: Boolean = true, holdParent: Boolean = false, holdChildCleanup: Boolean = false): Fixture =
        Fixture(instrumentation.targetContext, submit, holdParent, holdChildCleanup).also {
            fixtures.add(it); awaitChange(it, "recovery") { it.controller.ready }; it.prepare()
        }
    private fun send(f: Fixture) = assertNotNull(f.controller.sendWithChildren(f.conversation, "parent task"))
    private fun retained(f: Fixture): Int = synchronized(f.controller) {
        val field = ChatController::class.java.getDeclaredField("backgroundRuns").apply { isAccessible = true }
        (field.get(f.controller) as Map<*, *>).size
    }
    private fun assertParentCompleted(f: Fixture) {
        val parent = f.database.messages(f.conversation.id).map { it.second }.last { it.role == Message.ASSISTANT_ROLE }
        assertEquals(RunStatus.COMPLETED, parent.status)
        assertEquals("parent answer", parent.blocks.filterIsInstance<MessageBlock.Text>().joinToString("") { it.text })
        if (f.submit) {
            val receipt = org.json.JSONObject(checkNotNull(parent.blocks.filterIsInstance<MessageBlock.Tool>().single().result))
            assertTrue(receipt.getBoolean("accepted")); assertFalse(receipt.getBoolean("completed"))
        }
        assertNull(f.controller.problem)
    }
    private fun assertChildStillBlocked(f: Fixture) {
        assertEquals("cancel must not stand in for actual provider exit", 1L, f.childExited.count)
        assertEquals("fixture timeout must not drain the child", 0, f.childTimeouts.get())
    }
    private fun releaseChild(f: Fixture) {
        f.childRelease.countDown()
        assertTrue("provider must actually exit", f.childExited.await(5, TimeUnit.SECONDS))
        assertEquals("fixture must be released, not time out", 0, f.childTimeouts.get())
    }
    private fun finishChildWithCleanupBlocked(f: Fixture) {
        releaseChild(f)
        assertTrue("transport cleanup must start independently", f.childCancelled.await(5, TimeUnit.SECONDS))
        // next() returning alone is not worker exit. The batch shuts down this real worker
        // after recording its result, even while asynchronous transport cleanup is blocked.
        val worker = checkNotNull(f.childWorker.get())
        worker.join(5000)
        assertFalse("child worker must exit before cleanup is released", worker.isAlive)
        assertFalse("worker exit must not complete blocked cleanup", f.childCleanupExited.await(100, TimeUnit.MILLISECONDS))
        main { } // Process any release notification already posted by the exited worker.
        assertEquals(ChildTaskStatus.COMPLETED, f.controller.childTasks(f.conversation.id).single().status)
        assertEquals(1L, f.childCleanupRelease.count)
        assertEquals(1L, f.childCleanupExited.count)
        assertEquals(0, f.childCleanupTimeouts.get())
        assertEquals(1, f.childCancels.get())
        assertEquals(1, retained(f)); assertTrue(f.controller.hasLiveChildTasks()); assertLeaseHeld(f)
    }
    private fun assertLeaseHeld(f: Fixture) {
        val another = ConversationDatabase(f.app)
        try {
            try { SQLiteRuntimeStore.acquire(another).close(); fail("runtime lease released before child worker and transport cleanup drain") }
            catch (_: IllegalStateException) { }
        } finally { another.close() }
    }
    override fun tearDown() {
        try { fixtures.forEach { it.dispose() } } finally { super.tearDown() }
    }

    private fun deleteModel(f: Fixture, apiModel: String) {
        val ticket = checkNotNull(checkNotNull(f.settings.localModelCatalogSnapshot()).selectionFor(apiModel))
        val done = CountDownLatch(1)
        val result = AtomicReference<app.daiyu.android.model.catalog.CatalogLocalEditResult>()
        main { f.controller.deleteConfiguredCatalogModels(listOf(ticket), { false }) { result.set(it); done.countDown() } }
        assertTrue("deletion must commit while tasks are still active", done.await(5, TimeUnit.SECONDS))
        assertEquals(app.daiyu.android.model.catalog.CatalogLocalEditResult.APPLIED, result.get())
    }

    fun testDeletingCurrentMainModelRetainsOnlyBusyConversationUntilItsOriginalRunEnds() {
        val f = fresh(submit = false, holdParent = true)
        val idle = f.database.create("default")
        send(f); assertTrue(f.parentEntered.await(5, TimeUnit.SECONDS))
        deleteModel(f, "parent-api")
        assertEquals("", f.settings.load().model)
        assertEquals("parent-api", f.controller.retainedDeletedMainModel(f.conversation.id.value))
        assertNull(f.controller.retainedDeletedMainModel(idle.id.value))
        assertEquals(1, f.parentOpens.get())
        f.parentRelease.countDown()
        awaitChange(f, "original main task drains") { f.controller.active == null && f.controller.retainedDeletedMainModel(f.conversation.id.value) == null }
        assertParentCompleted(f)
        assertEquals(1, f.parentOpens.get())
        assertEquals("", f.settings.load().model)
    }

    fun testDeletedChildModelClearsOtherSessionAndIdleCardThenWaitsForPhysicalCleanup() {
        val f = fresh(holdChildCleanup = true)
        val preferences = ConversationSubAgentPreferences(f.app.getSharedPreferences("subagent_configuration", 0))
        val busy = SubAgentConfigKey.Conversation(f.conversation.id.value)
        val idle = SubAgentConfigKey.Conversation(f.database.create("default").id.value)
        preferences.createConversation(idle)
        preferences.update(idle) { it.copy(profiles = listOf(f.profile)) }
        preferences.update(busy) { it.copy(profiles = listOf(f.profile, f.profile.copy(id = "unused"))) }
        send(f); assertTrue(f.childEntered.await(5, TimeUnit.SECONDS))
        awaitChange(f, "parent ended") { f.controller.active == null }
        deleteModel(f, "child-api")
        assertTrue(preferences.snapshot(idle).profiles.isEmpty())
        assertEquals(listOf(f.profile.id), preferences.snapshot(busy).profiles.map { it.id })
        assertEquals(0, f.childCancels.get())
        finishChildWithCleanupBlocked(f)
        assertEquals(listOf(f.profile.id), preferences.snapshot(busy).profiles.map { it.id })
        f.childCleanupRelease.countDown()
        awaitChange(f, "last child transport drains and its card is removed") { preferences.snapshot(busy).profiles.isEmpty() }
        assertEquals(1, f.childOpens.get())
        assertEquals(ChildTaskStatus.COMPLETED, f.controller.childTasks(f.conversation.id).single().status)
    }

    fun testChildAndPhysicalCleanupOnlyProtectTheirOwnConversationConfiguration() {
        val f = fresh(holdChildCleanup = true)
        val viewing = f.database.create("default")
        f.controller.assistantStore.save(app.daiyu.android.assistants.AssistantProfile("other", "Other", ""))
        send(f); assertTrue(f.childEntered.await(5, TimeUnit.SECONDS))
        awaitChange(f, "parent ended") { f.controller.active == null }
        val originalModel = f.database.modelBinding(f.conversation.id)
        val registry = f.controller.providers.snapshot()
        val globalSettings = f.settings.load()
        fun verify() {
            val effort = if (f.controller.reasoningPreference.snapshot(viewing.id.value) == ReasoningEffort.HIGH) ReasoningEffort.LOW else ReasoningEffort.HIGH
            val cancels = f.childCancels.get()
            main {
                assertTrue(f.controller.taskConfigurationBusy(f.conversation.id))
                assertFalse(f.controller.taskConfigurationBusy(viewing.id))
                assertFalse(f.controller.bindConversationAssistant(f.conversation.id, "default", "other"))
                assertFalse(f.controller.setChatReasoning(f.conversation.id.value, ReasoningEffort.MAX))
                val expected = f.database.assistantId(viewing.id)
                val replacement = if (expected == "default") "other" else "default"
                assertTrue(f.controller.bindConversationAssistant(viewing.id, expected, replacement))
                assertEquals(replacement, f.database.assistantId(viewing.id))
                assertFalse(f.controller.dataBusy)
            }
            val (editor, editing) = f.controller.conversationModelEditing(viewing.id)
            val global = f.controller.providers.withEditor(editor) { it.load() }
            val ticket = checkNotNull(f.controller.providers.catalogSnapshot(editor).selectionFor("child-api"))
            val done = CountDownLatch(1); val saved = AtomicReference<Boolean>()
            main { f.controller.selectActiveProviderCatalogModel(editor, editing.settings, editor, global, ticket,
                cancelled = { false }, completed = { saved.set(it); done.countDown() }, conversation = viewing.id) }
            assertTrue("B model selection must finish before A child cleanup", done.await(5, TimeUnit.SECONDS))
            assertEquals(true, saved.get())
            assertEquals(ConversationModelBinding(editor.slotId, ticket.record.settings.localIdentity), f.database.modelBinding(viewing.id))
            main {
                assertTrue(f.controller.setChatReasoning(viewing.id.value, effort))
                assertEquals(effort, f.controller.reasoningPreference.snapshot(viewing.id.value))
                val (current, currentSettings) = f.controller.conversationModelEditing(f.conversation.id)
                try {
                    f.controller.selectActiveProviderCatalogModel(current, currentSettings.settings, editor, global, ticket,
                        cancelled = { false }, completed = { fail("busy A model queued") }, conversation = f.conversation.id)
                    fail("busy A model accepted")
                } catch (failure: IllegalStateException) { assertEquals("请先结束任务", failure.message) }
            }
            assertEquals(originalModel, f.database.modelBinding(f.conversation.id))
            assertEquals(registry, f.controller.providers.snapshot())
            assertEquals(globalSettings, f.settings.load())
            assertEquals("default", f.controller.assistantStore.selectedId.value)
            assertEquals(cancels, f.childCancels.get())
            assertTrue(f.controller.hasLiveChildTasks(f.conversation.id))
            assertFalse(f.controller.dataBusy)
        }
        verify()
        finishChildWithCleanupBlocked(f)
        verify()
        f.childCleanupRelease.countDown()
        awaitChange(f, "physical cleanup drained") { !f.controller.hasLiveChildTasks(f.conversation.id) }
        main { assertFalse(f.controller.taskConfigurationBusy(f.conversation.id)) }
    }

    fun testControllerAppendUsesNewOwnerAndWaitsForOriginalWorkerAndCleanup() {
        val f = fresh(holdChildCleanup = true)
        send(f); assertTrue(f.childEntered.await(5, TimeUnit.SECONDS))
        awaitChange(f, "parent ended") { f.controller.active == null }
        val original = f.controller.childTasks(f.conversation.id).single()
        val command = ChildTaskCommand("records-append-${f.id}", original, ChildTaskCommandKind.APPEND, f.profile.id, "review this additional constraint")
        f.database.draft(f.conversation.id, "keep this conversation draft")
        val beforeCommand = System.currentTimeMillis()
        val accepted = f.controller.directChildTask(command) as ChildTaskCommandReceipt.Accepted
        val afterCommand = System.currentTimeMillis()
        assertTrue(accepted.task.owner.parentRunId != original.owner.parentRunId)
        val parentMessages = f.database.messages(f.conversation.id).map { it.second }
            .filter { it.runId.value == accepted.task.owner.parentRunId }
        assertEquals(2, parentMessages.size)
        val commandUser = parentMessages.single { it.role == Message.USER_ROLE }
        val commandAssistant = parentMessages.single { it.role == Message.ASSISTANT_ROLE }
        assertTrue(checkNotNull(commandUser.createdAt) in beforeCommand..afterCommand)
        assertEquals(commandUser.createdAt, commandAssistant.createdAt)
        assertEquals(RunStatus.COMPLETED, commandAssistant.status)
        assertTrue(commandAssistant.historySegments.isNotEmpty())
        assertTrue(commandAssistant.historySegments.all { it.createdAt == commandAssistant.createdAt })
        f.database.readableDatabase.rawQuery("SELECT draft FROM conversations WHERE id=?", arrayOf(f.conversation.id.value)).use {
            assertTrue(it.moveToFirst()); assertEquals("keep this conversation draft", it.getString(0))
        }
        assertEquals(original.owner.childRunId, accepted.task.sourceTaskId)
        assertEquals(1, f.parentOpens.get()); assertEquals(1, f.childOpens.get())
        releaseChild(f)
        assertTrue(f.childCancelled.await(5, TimeUnit.SECONDS))
        checkNotNull(f.childWorker.get()).join(5000)
        assertEquals("successor must not open while old cleanup remains blocked", 1, f.childOpens.get())
        assertTrue(f.controller.hasLiveChildTasks())
        f.childCleanupRelease.countDown()
        awaitChange(f, "both requests truly drained") { !f.controller.hasLiveChildTasks() }
        val rows = f.controller.childTasks(f.conversation.id)
        assertEquals(2, rows.size); assertEquals(2, f.childOpens.get()); assertEquals(1, f.parentOpens.get())
        val sentText = f.childHistories[1].flatMap { it.blocks }.filterIsInstance<MessageBlock.Text>().map { it.text }
        assertTrue(sentText.contains(original.prompt)); assertTrue(sentText.contains("retained answer")); assertTrue(sentText.contains(command.instruction))
        val preserved = rows.single { it.owner == original.owner }
        assertEquals(original.prompt, preserved.prompt); assertEquals("retained answer", preserved.text)
        assertEquals(original.revision + 1, preserved.revision)
        assertEquals(ChildTaskStatus.COMPLETED, rows.single { it.owner == accepted.task.owner }.status)
        assertEquals(accepted.task.owner, (f.controller.directChildTask(command) as ChildTaskCommandReceipt.Accepted).task.owner)
        assertEquals(parentMessages, f.database.messages(f.conversation.id).map { it.second }
            .filter { it.runId.value == accepted.task.owner.parentRunId })
        assertEquals(2, f.childOpens.get())
        assertEquals(ChildTaskCommandReceipt.Rejected("child_request_conflict"), f.controller.directChildTask(command.copy(instruction = "changed")))
    }

    fun testControllerReplacementStopsButDoesNotRecycleOldTransportReservation() {
        val f = fresh(holdChildCleanup = true)
        send(f); assertTrue(f.childEntered.await(5, TimeUnit.SECONDS))
        awaitChange(f, "parent ended") { f.controller.active == null }
        val original = f.controller.childTasks(f.conversation.id).single()
        val command = ChildTaskCommand("records-replace-${f.id}", original, ChildTaskCommandKind.REPLACE, f.profile.id, "replacement input")
        assertEquals(ChildTaskCommandReceipt.Rejected("child_execution_confirmation_required"), f.controller.directChildTask(command))
        val accepted = f.controller.directChildTask(command.copy(acknowledgePossibleExecution = true)) as ChildTaskCommandReceipt.Accepted
        assertTrue(f.childCancelled.await(5, TimeUnit.SECONDS))
        assertEquals(1, f.childOpens.get()); assertTrue(f.controller.hasLiveChildTasks())
        releaseChild(f); checkNotNull(f.childWorker.get()).join(5000)
        assertEquals(1, f.childOpens.get())
        assertEquals(1L, f.childCleanupExited.count)
        f.childCleanupRelease.countDown()
        awaitChange(f, "replacement drained") { !f.controller.hasLiveChildTasks() }
        assertEquals(2, f.childOpens.get()); assertEquals(1, f.parentOpens.get())
        val rows = f.controller.childTasks(f.conversation.id)
        assertEquals(original.prompt, rows.single { it.owner == original.owner }.prompt)
        assertEquals(ChildTaskStatus.UNKNOWN, rows.single { it.owner == original.owner }.status)
        val replacement = rows.single { it.owner == accepted.task.owner }
        assertEquals("replacement input", replacement.prompt); assertEquals(original.owner.childRunId, replacement.sourceTaskId)
        assertEquals(ChildTaskStatus.COMPLETED, replacement.status)
    }

    fun testControllerUnknownRedispatchRequiresSingleExplicitConfirmationAndPreservesSource() {
        val f = fresh()
        send(f); assertTrue(f.childEntered.await(5, TimeUnit.SECONDS))
        awaitChange(f, "parent ended") { f.controller.active == null }
        val running = f.controller.childTasks(f.conversation.id).single()
        assertEquals(ChildTaskStop.REQUESTED, f.controller.stopChildTask(running))
        releaseChild(f)
        awaitChange(f, "old request drained") { !f.controller.hasLiveChildTasks() }
        val unknown = f.controller.childTasks(f.conversation.id).single()
        assertEquals(ChildTaskStatus.UNKNOWN, unknown.status)
        val command = ChildTaskCommand("records-redispatch-${f.id}", unknown, ChildTaskCommandKind.REDISPATCH, f.profile.id, unknown.prompt)
        assertEquals(ChildTaskCommandReceipt.Rejected("child_execution_confirmation_required"), f.controller.directChildTask(command))
        assertEquals(1, f.childOpens.get())
        val confirmed = command.copy(acknowledgePossibleExecution = true)
        val dispatched = f.controller.directChildTask(confirmed) as ChildTaskCommandReceipt.Accepted
        awaitChange(f, "new request drained") { !f.controller.hasLiveChildTasks() }
        assertEquals(2, f.childOpens.get()); assertEquals(1, f.parentOpens.get())
        assertEquals(unknown.copy(revision = unknown.revision + 1), f.controller.childTasks(f.conversation.id).single { it.owner == unknown.owner })
        assertEquals(dispatched.task.owner, (f.controller.directChildTask(confirmed) as ChildTaskCommandReceipt.Accepted).task.owner)
        assertEquals(2, f.childOpens.get())
    }

    fun testControllerPauseAndResumeKeepTheOriginalLiveChildAndRejectWrongOwner() {
        val f = fresh()
        send(f)
        assertTrue(f.childEntered.await(5, TimeUnit.SECONDS))
        awaitChange(f, "parent completed") { f.controller.active == null }
        val record = f.controller.childTasks(f.conversation.id).single()
        val wrong = record.copy(owner = record.owner.copy(conversationId = "different"))
        assertEquals(app.daiyu.android.domain.delegation.ChildTaskControlReceipt.STALE, f.controller.resumeChildTask(wrong))
        assertEquals(app.daiyu.android.domain.delegation.ChildTaskControlReceipt.PENDING, f.controller.pauseChildTask(record))
        assertEquals(app.daiyu.android.domain.delegation.ChildTaskPauseState.PENDING,
            f.controller.liveChildTasks(f.conversation.id).single().pauseState)
        assertTrue(f.controller.liveChildTasks(ConversationId("different")).isEmpty())
        assertEquals(app.daiyu.android.domain.delegation.ChildTaskControlReceipt.RESUMED, f.controller.resumeChildTask(record))
        assertEquals(app.daiyu.android.domain.delegation.ChildTaskPauseState.ACTIVE,
            f.controller.liveChildTasks(f.conversation.id).single().pauseState)
        assertEquals(1, f.childOpens.get())
        assertEquals(ChildTaskStop.REQUESTED, f.controller.stopChildTask(record))
        assertEquals(app.daiyu.android.domain.delegation.ChildTaskControlReceipt.STALE, f.controller.resumeChildTask(record))
        releaseChild(f)
        awaitChange(f, "stopped child drained") { !f.controller.hasLiveChildTasks() }
    }

    fun testUnusedChildBindingsAreReleasedAcrossNormalParents() {
        val f = fresh(submit = false)
        repeat(4) {
            send(f)
            awaitChange(f, "unused binding must release on parent cleanup") { f.controller.active == null && retained(f) == 0 }
            assertParentCompleted(f)
        }
        assertEquals(4, f.parentOpens.get()); assertEquals(0, f.childOpens.get())
        assertTrue(f.controller.childTasks(f.conversation.id).isEmpty())
    }

    fun testChildOutlivesParentThenReleaseNotifiesAndHistorySurvives() {
        val f = fresh()
        send(f); assertTrue(f.childEntered.await(5, TimeUnit.SECONDS))
        awaitChange(f, "parent completes without cancelling child") { f.controller.active == null }
        assertParentCompleted(f)
        assertEquals(1, retained(f)); assertTrue(f.controller.hasLiveChildTasks())
        assertFalse(f.childCancelled.await(100, TimeUnit.MILLISECONDS)); assertChildStillBlocked(f)
        main {
            try { f.controller.dataTask { error("live child must block data task") }; fail("data task admitted") }
            catch (_: IllegalStateException) { }
        }
        val delivered = CountDownLatch(1)
        val wrongThread = AtomicInteger()
        val listener: () -> Unit = {
            if (Looper.myLooper() != Looper.getMainLooper()) wrongThread.incrementAndGet()
            if (retained(f) == 0 && !f.controller.hasLiveChildTasks()) delivered.countDown()
        }
        main { f.controller.observe(listener) }
        try {
            releaseChild(f)
            assertTrue("release must produce a final observer notification", delivered.await(5, TimeUnit.SECONDS))
        } finally { main { f.controller.unobserve(listener) } }
        assertEquals(0, wrongThread.get()); assertEquals(0, retained(f))
        val record = f.controller.childTasks(f.conversation.id).single()
        assertEquals(ChildTaskStatus.COMPLETED, record.status); assertEquals("retained answer", record.text)
        main { f.controller.dataTask { "children drained" } }
        awaitChange(f, "data task unblocked") { !f.controller.dataBusy }
        assertEquals(record, f.controller.childTasks(f.conversation.id).single())
        assertEquals(1, f.childOpens.get())
    }

    fun testChildSettlementDoesNotReleaseStillAcceptingParent() {
        val f = fresh(holdParent = true)
        send(f)
        assertTrue(f.childEntered.await(5, TimeUnit.SECONDS)); assertTrue(f.parentEntered.await(5, TimeUnit.SECONDS))
        releaseChild(f)
        awaitChange(f, "child settled before parent") { !f.controller.hasLiveChildTasks() }
        assertNotNull(f.controller.active); assertEquals(1, retained(f))
        f.parentRelease.countDown()
        awaitChange(f, "parent seal releases settled child handle") { f.controller.active == null && retained(f) == 0 }
        assertParentCompleted(f)
        assertEquals(ChildTaskStatus.COMPLETED, f.controller.childTasks(f.conversation.id).single().status)
    }

    fun testIndependentStopRetainsHandleUntilExitAndUnknownIsNeverReplayed() {
        val f = fresh()
        send(f); assertTrue(f.childEntered.await(5, TimeUnit.SECONDS))
        awaitChange(f, "parent complete") { f.controller.active == null }
        assertParentCompleted(f)
        val record = f.controller.childTasks(f.conversation.id).single()
        main { assertEquals(ChildTaskStop.REQUESTED, f.controller.stopChildTask(record)) }
        assertTrue(f.childCancelled.await(5, TimeUnit.SECONDS)); assertChildStillBlocked(f)
        assertEquals(1, retained(f)); assertTrue(f.controller.hasLiveChildTasks()); assertLeaseHeld(f)
        releaseChild(f)
        awaitChange(f, "stopped provider actually exits") { retained(f) == 0 && !f.controller.hasLiveChildTasks() }
        assertEquals(ChildTaskStatus.UNKNOWN, f.controller.childTasks(f.conversation.id).single().status)
        main { assertEquals(ChildTaskStop.NOT_FOUND, f.controller.stopChildTask(record)) }
        f.controller.close()
        val reopened = ConversationDatabase(f.app)
        val replacementFactories = AtomicInteger()
        val replacement = ChatController(f.app, reopened, f.settings, f.app.getSharedPreferences("conversation-ui", 0), {
            replacementFactories.incrementAndGet()
            error("UNKNOWN history must not open a provider")
        }, providerBalanceHost = { f.app.balances })
        try {
            val ready = CountDownLatch(1)
            val listener: () -> Unit = { if (replacement.ready) ready.countDown() }
            main { replacement.observe(listener); listener() }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            main { replacement.unobserve(listener) }
            assertNull(replacement.problem)
            assertEquals(ChildTaskStatus.UNKNOWN, replacement.childTasks(f.conversation.id).single().status)
            assertEquals(0, replacementFactories.get())
            assertEquals(1, f.childOpens.get())
        } finally { replacement.close() }
    }

    private fun assertParentStopRetainsChildren(stop: (Fixture, Message) -> Unit) {
        val f = fresh(holdParent = true)
        send(f)
        assertTrue(f.childEntered.await(5, TimeUnit.SECONDS)); assertTrue(f.parentEntered.await(5, TimeUnit.SECONDS))
        val owner = checkNotNull(f.controller.active)
        main { stop(f, owner) }
        assertTrue(f.childCancelled.await(5, TimeUnit.SECONDS)); assertChildStillBlocked(f)
        awaitChange(f, "stopped parent releases independently") { f.controller.active == null }
        assertEquals(1, retained(f)); assertTrue(f.controller.hasLiveChildTasks()); assertLeaseHeld(f)
        releaseChild(f)
        awaitChange(f, "parent stop still waits for real child exit") { retained(f) == 0 && !f.controller.hasLiveChildTasks() }
        assertEquals(ChildTaskStatus.UNKNOWN, f.controller.childTasks(f.conversation.id).single().status)
        assertEquals(1, f.childOpens.get())
    }

    fun testOwnedParentStopRetainsChildrenUntilExit() = assertParentStopRetainsChildren { f, owner ->
        assertTrue(f.controller.stop(owner))
    }

    fun testUnqualifiedParentStopRetainsChildrenUntilExit() = assertParentStopRetainsChildren { f, _ ->
        f.controller.stop()
    }

    fun testStoppingSavedModelTaskRetainsChildrenUntilExit() {
        val f = fresh()
        send(f); assertTrue(f.childEntered.await(5, TimeUnit.SECONDS))
        awaitChange(f, "parent complete before revoke") { f.controller.active == null }
        assertParentCompleted(f)
        val record = f.controller.childTasks(f.conversation.id).single()
        main { assertEquals(ChildTaskStop.REQUESTED, f.controller.stopChildTask(record)) }
        assertTrue(f.childCancelled.await(5, TimeUnit.SECONDS)); assertChildStillBlocked(f)
        assertEquals(1, retained(f)); assertTrue(f.controller.hasLiveChildTasks())
        releaseChild(f)
        awaitChange(f, "revoked child exits") { retained(f) == 0 && !f.controller.hasLiveChildTasks() }
        assertEquals(ChildTaskStatus.UNKNOWN, f.controller.childTasks(f.conversation.id).single().status)
    }

    fun testExitedChildWorkerRetainsHandleUntilTransportCleanupReturns() {
        val f = fresh(holdChildCleanup = true)
        send(f); assertTrue(f.childEntered.await(5, TimeUnit.SECONDS))
        awaitChange(f, "parent complete before child cleanup") { f.controller.active == null }
        assertParentCompleted(f)
        try {
            finishChildWithCleanupBlocked(f)
        } finally { f.childCleanupRelease.countDown() }
        assertTrue("transport cleanup must return", f.childCleanupExited.await(5, TimeUnit.SECONDS))
        awaitChange(f, "cleanup, not worker exit, releases the handle") {
            retained(f) == 0 && !f.controller.hasLiveChildTasks()
        }
        assertEquals(0, f.childCleanupTimeouts.get()); assertEquals(1, f.childCancels.get())
        val record = f.controller.childTasks(f.conversation.id).single()
        assertEquals(ChildTaskStatus.COMPLETED, record.status); assertEquals("retained answer", record.text)
        assertEquals(1, f.childOpens.get())
    }

    fun testRepeatedCloseSharesBlockedTransportCleanupAndReleasesLeaseOnce() {
        val f = fresh(holdChildCleanup = true)
        send(f); assertTrue(f.childEntered.await(5, TimeUnit.SECONDS))
        awaitChange(f, "parent complete before close") { f.controller.active == null }
        assertParentCompleted(f)
        val closeStarted = CountDownLatch(2)
        val closeDone = CountDownLatch(2)
        val failure = AtomicReference<Throwable?>()
        val closers = List(2) {
            Thread {
                closeStarted.countDown()
                try { f.controller.close() } catch (t: Throwable) { failure.compareAndSet(null, t) }
                finally { closeDone.countDown() }
            }
        }
        try {
            finishChildWithCleanupBlocked(f)
            main { f.controller.close() }
            closers.forEach { it.start() }
            assertTrue("both repeated closes must start", closeStarted.await(5, TimeUnit.SECONDS))
            assertFalse("all closes must wait for the same cleanup", closeDone.await(100, TimeUnit.MILLISECONDS))
            assertEquals("neither close may return early", 2L, closeDone.count)
            assertEquals(1L, f.childCleanupExited.count); assertEquals(1, f.childCancels.get())
            assertEquals(1, retained(f)); assertLeaseHeld(f)
        } finally { f.childCleanupRelease.countDown() }
        assertTrue("transport cleanup must return", f.childCleanupExited.await(5, TimeUnit.SECONDS))
        assertTrue("one cleanup release must complete every close", closeDone.await(5, TimeUnit.SECONDS))
        closers.forEach { it.join(1000) }
        failure.get()?.let { throw it }
        assertEquals(0, retained(f)); assertFalse(f.controller.hasLiveChildTasks())
        assertEquals(0, f.childCleanupTimeouts.get()); assertEquals(1, f.childCancels.get())
        val reopened = ConversationDatabase(f.app)
        try {
            val runtime = SQLiteRuntimeStore.acquire(reopened)
            try {
                repeat(3) { f.controller.close() }
                main { } // Drain the repeated close callbacks before checking the replacement lease.
                assertLeaseHeld(f)
                assertEquals(0, retained(f)); assertEquals(1, f.childCancels.get())
                assertEquals(ChildTaskStatus.COMPLETED,
                    SQLiteChildTaskStore(reopened).list(f.conversation.id.value, 20).single().status)
            } finally { runtime.close() }
        } finally { reopened.close() }
        assertEquals(1, f.childOpens.get())
    }

    fun testCloseKeepsRuntimeLeaseUntilBackgroundProviderExits() {
        val f = fresh()
        send(f); assertTrue(f.childEntered.await(5, TimeUnit.SECONDS))
        awaitChange(f, "parent complete") { f.controller.active == null }
        assertParentCompleted(f)
        val closeDone = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val closer = Thread {
            try { f.controller.close() } catch (t: Throwable) { failure.set(t) }
            finally { closeDone.countDown() }
        }
        closer.start()
        try {
            assertTrue(f.childCancelled.await(5, TimeUnit.SECONDS)); assertChildStillBlocked(f)
            assertFalse(closeDone.await(100, TimeUnit.MILLISECONDS)); assertEquals(1, retained(f)); assertLeaseHeld(f)
        } finally { f.childRelease.countDown() }
        assertTrue("provider must actually exit", f.childExited.await(5, TimeUnit.SECONDS))
        assertEquals(0, f.childTimeouts.get())
        assertTrue("close must complete after provider exit", closeDone.await(5, TimeUnit.SECONDS))
        closer.join(1000); failure.get()?.let { throw it }; assertEquals(0, retained(f))
        val reopened = ConversationDatabase(f.app)
        try {
            val runtime = SQLiteRuntimeStore.acquire(reopened)
            // Acquiring a lease is not recovery; read history without runtime.childTasks' recovery gate.
            try { assertEquals(ChildTaskStatus.UNKNOWN, SQLiteChildTaskStore(reopened).list(f.conversation.id.value, 20).single().status) }
            finally { runtime.close() }
        } finally { reopened.close() }
        assertEquals(1, f.childOpens.get())
    }
}
