package app.daiyu.android

import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import android.test.InstrumentationTestCase
import app.daiyu.android.assistants.AssistantProfile
import app.daiyu.android.browser.BrowserSettings
import app.daiyu.android.browser.BrowserSettingsStore
import app.daiyu.android.browser.session.BrowserAccess
import app.daiyu.android.browser.session.BrowserOwnerKind
import app.daiyu.android.domain.Conversation
import app.daiyu.android.model.ProviderSnapshot
import app.daiyu.android.storage.ConversationDatabase
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real parent controller/SQLite/tickets. No replacement browser factory or fabricated acceptance. */
@Suppress("DEPRECATION")
class ControllerBrowserSendTest : InstrumentationTestCase() {
    private lateinit var fixture: RunControlFixture
    private lateinit var isolatedContext: Context
    private lateinit var root: File
    private lateinit var browserSettings: BrowserSettingsStore
    private val modelFactories = AtomicInteger()
    private val sessionOwners = java.util.Collections.synchronizedList(mutableListOf<app.daiyu.android.domain.ConversationId>())
    private val beforeOpen = AtomicReference<((List<app.daiyu.android.domain.Message>) -> Unit)?>()
    private val gates = mutableListOf<WorkerGate>()
    private val controller get() = fixture.controller

    private class WorkerGate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
    }

    override fun setUp() {
        super.setUp()
        val id = UUID.randomUUID().toString()
        root = File(instrumentation.targetContext.cacheDir, "browser-parent-$id").apply { mkdirs() }
        // ContextPreparationTest's private-files pattern, with the actual TextSendTicketTest fixture.
        // applicationContext must stay isolated too: BrowserSettingsStore uses that context.
        isolatedContext = object : ContextWrapper(testContext(instrumentation.targetContext, "browser-parent-$id-")) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
        }
        fixture = RunControlFixture(isolatedContext,
            onSession = { _, _, context ->
                sessionOwners.add(context.conversationId); assertNull(context.project)
            }, beforeOpen = { history -> beforeOpen.get()?.invoke(history) }) {
            modelFactories.incrementAndGet()
        }
        assertSame(isolatedContext, fixture.application.applicationContext)
        assertEquals(root, fixture.application.filesDir)
        awaitControl("controller ready") { controller.ready }
        assertNull(controller.problem)
        browserSettings = BrowserSettingsStore(fixture.application)
        browserSettings.save(BrowserSettings(BrowserAccess.READ_ONLY))
    }

    override fun tearDown() {
        try {
            gates.forEach { it.release.countDown() }
            if (::fixture.isInitialized) fixture.close()
            if (::isolatedContext.isInitialized) {
                isolatedContext.getSharedPreferences("browser-settings", 0).edit().clear().commit()
            }
            if (::root.isInitialized) root.deleteRecursively()
        } finally { super.tearDown() }
    }

    private fun await(latch: CountDownLatch) = assertTrue("controller callback timed out", latch.await(10, TimeUnit.SECONDS))

    private fun draft(conversation: Conversation? = null): BrowserDraftSnapshot {
        val text = "  exact browser draft\ntrailing whitespace  "
        if (conversation == null) fixture.database.draft(text) else fixture.database.draft(conversation.id, text)
        return BrowserDraftSnapshot(BrowserDraftOwner(conversation?.id, 17, 23), text)
    }

    private fun prepare(draft: BrowserDraftSnapshot, conversation: Conversation? = null,
                        writes: List<String> = emptyList()): ControllerBrowserPreparation {
        assertTrue("prepare must run off main", Looper.myLooper() != Looper.getMainLooper())
        val pending = controller.prepareBrowserSend(conversation, draft, writes)
        assertNotNull("real parent should reserve a preview", pending)
        return checkNotNull(pending)
    }

    private fun assertNoBrowserOrModel() {
        assertFalse("pre-open paths must not create a browser run directory", File(root, "browser-runs").exists())
        assertEquals("model factory must not run", 0, modelFactories.get())
        assertEquals("ModelPort.open must not run", 0, fixture.opens.get())
        assertNull(controller.active)
    }

    private fun assertUncommitted(draft: BrowserDraftSnapshot, database: ConversationDatabase = fixture.database) {
        val owner = draft.owner.conversationId
        if (owner == null) {
            assertEquals(draft.text, database.draft())
            assertTrue("no conversation may be created", database.conversations().isEmpty())
        } else {
            assertEquals(draft.text, database.conversations().single { it.id == owner }.draft)
        }
        database.readableDatabase.rawQuery("SELECT COUNT(*) FROM messages", null).use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals("neither user nor assistant row may be inserted", 0, rows.getInt(0))
        }
        assertNoBrowserOrModel()
    }

    /** Delay the existing worker, not the browser constructor or its result. Always released in teardown. */
    private fun holdWorker(): WorkerGate {
        val gate = WorkerGate().also { gates.add(it) }
        val field = ChatController::class.java.getDeclaredField("executor").apply { isAccessible = true }
        val executor = field.get(controller) as ExecutorService
        executor.execute {
            gate.entered.countDown()
            check(gate.release.await(30, TimeUnit.SECONDS)) { "test worker barrier timed out" }
        }
        await(gate.entered)
        return gate
    }

    fun testPrepareIsWorkerOnlyAndDoesNotOpenModelBrowserOrWriteMessages() {
        val draft = draft()
        controlMain {
            try {
                controller.prepareBrowserSend(null, draft, emptyList())
                fail("main-thread preparation must fail before reservation")
            } catch (expected: IllegalStateException) {
                assertEquals("browser_prepare_requires_worker", expected.message)
            }
            assertNull(controller.browserPreparation())
        }
        val pending = prepare(draft)
        assertSame(pending, controller.browserPreparation())
        assertEquals(ControllerBrowserPreparation.State.PREVIEW, pending.state)
        assertEquals(draft, pending.draft)
        assertEquals(BrowserAccess.READ_ONLY, pending.settings.access)
        assertTrue(pending.preview.writes.isEmpty())
        assertTrue("list_tabs" in pending.preview.allowedActions)
        assertNull(controller.textSendState())
        assertUncommitted(draft)
        controlMain { controller.rejectBrowser(pending) }
    }

    fun testDisabledBrowserRejectsKeepsDraftAndReleasesReservation() {
        val draft = draft()
        browserSettings.save(BrowserSettings(BrowserAccess.DISABLED))
        assertNull(controller.prepareBrowserSend(null, draft, emptyList()))
        assertEquals("BROWSER_DISABLED", controller.problem)
        assertNull(controller.browserPreparation())
        assertNull(controller.textSendState())
        assertUncommitted(draft)
        browserSettings.save(BrowserSettings(BrowserAccess.READ_ONLY))
        val replacement = prepare(draft) // Admission, not just a null accessor, proves slot release.
        controlMain { controller.rejectBrowser(replacement) }
        assertUncommitted(draft)
    }

    fun testNewComposerFreezesAssistantProviderSettingsWritesAndGeneratedIdsThroughConfirmation() {
        val store = controller.assistantStore
        val profile = AssistantProfile("frozen", "before", "original instructions", memoryEnabled = false,
            enabledSkillIds = listOf("skill-before"))
        store.save(profile)
        store.save(AssistantProfile("other", "other", "other instructions", memoryEnabled = false))
        store.select(profile.id)
        val config = fixture.settings.load()
        val provider = ProviderSnapshot.capture(config)
        browserSettings.save(BrowserSettings(BrowserAccess.FULL))
        val draft = draft()
        val raw = " {\"action\":\"new_tab\"} "
        val writes = mutableListOf(raw)
        val pending = prepare(draft, writes = writes)
        val identity = pending.identity
        val preview = pending.preview
        assertEquals(profile.id, identity.assistantId)
        assertTrue(identity.conversationId.value.isNotBlank())
        assertTrue(identity.runId.value.isNotBlank())
        assertTrue(identity.assistantMessageId.value.isNotBlank())
        assertEquals(identity.runId.value, preview.grant.owner.sessionId)
        assertEquals(BrowserOwnerKind.MAIN, preview.grant.owner.kind)

        store.select("other")
        store.save(profile.copy(name = "after", prompt = "changed instructions", enabledSkillIds = listOf("after")))
        fixture.settings.save(config.copy(endpoint = "https://changed.invalid", model = "changed-model", apiKey = "changed-key"))
        browserSettings.save(BrowserSettings(BrowserAccess.DISABLED))
        writes.clear()
        assertEquals(profile, pending.assistant)
        assertEquals(provider, pending.provider)
        assertEquals(config, pending.provider.asModelSettings())
        assertEquals(BrowserAccess.FULL, pending.settings.access)
        assertEquals(raw, pending.preview.writes.single().arguments)
        assertTrue("new_tab" in pending.preview.allowedActions)
        assertEquals(draft, pending.draft)
        controlMain { assertTrue(pending.confirm(preview, draft)) }
        assertSame(preview, pending.preview)
        assertEquals(identity, pending.identity)
        assertEquals(ControllerBrowserPreparation.State.CONFIRMED, pending.state)
        assertSame(pending, controller.browserPreparation())
        assertUncommitted(draft)
        controlMain { controller.rejectBrowser(pending) }
    }

    fun testExistingConversationUsesDurableAssistantBindingNotGlobalSelection() {
        val store = controller.assistantStore
        val bound = AssistantProfile("bound", "bound profile", "bound instructions", memoryEnabled = false)
        store.save(bound)
        store.save(AssistantProfile("selected", "selected profile", "different instructions", memoryEnabled = false))
        store.select("selected")
        val conversation = fixture.database.create(bound.id)
        val draft = draft(conversation)
        val pending = prepare(draft, conversation)
        assertEquals(bound, pending.assistant)
        assertEquals(bound.id, pending.identity.assistantId)
        assertEquals(conversation.id, pending.identity.conversationId)
        assertEquals(conversation, pending.conversation)
        controlMain {
            assertFalse("binding repair shares the exclusive slot",
                controller.bindConversationAssistant(conversation.id, bound.id, "selected"))
        }
        store.save(bound.copy(name = "edited", prompt = "edited instructions"))
        assertEquals(bound, pending.assistant)
        assertEquals(bound.id, fixture.database.assistantId(conversation.id))
        assertUncommitted(draft)
        controlMain { controller.rejectBrowser(pending) }
    }

    fun testPreviewRejectReleasesSlotForOrdinaryTextSend() {
        val draft = draft()
        val pending = prepare(draft)
        val callbacks = AtomicInteger()
        controlMain {
            assertFalse("preview alone is not confirmation",
                controller.sendBrowser(pending, draft, ChatController.TextSendTicket()) { callbacks.incrementAndGet() })
            assertNull(controller.textSendState())
            controller.rejectBrowser(pending)
            assertNull(controller.browserPreparation())
        }
        assertEquals(ControllerBrowserPreparation.State.DRAINED, pending.state)
        assertTrue(pending.isCancelled)
        assertUncommitted(draft)
        val ticket = ChatController.TextSendTicket()
        val done = CountDownLatch(1)
        val result = AtomicReference<ChatController.TextSendResult>()
        val session = fixture.session(held = false)
        controlMain {
            assertTrue(controller.sendText(null, draft.owner.editorGeneration, draft.owner.draftRevision, draft.text, ticket) {
                result.set(it); done.countDown()
            })
        }
        await(done)
        await(session.entered) // The runner starts after receipt publication on the same worker.
        // This is an ordinary text send using the existing model fixture, NOT browser acceptance.
        assertTrue(result.get() is ChatController.TextSendResult.Accepted)
        assertEquals(2, fixture.database.messages((result.get() as ChatController.TextSendResult.Accepted).conversation.id).size)
        assertEquals(1, modelFactories.get())
        assertEquals(1, fixture.opens.get())
        assertEquals(0, callbacks.get())
        assertFalse(File(root, "browser-runs").exists())
        controlMain { controller.acknowledgeTextSend(ticket) }
        awaitControl("ordinary text run completed") { controller.active == null }
    }

    fun testConfirmedBrowserSendPassesItsPersistentReceiptConversationToSessionFactory() {
        val draft = draft()
        val pending = prepare(draft)
        val session = fixture.session(held = false)
        val ticket = ChatController.TextSendTicket()
        val done = CountDownLatch(1)
        val result = AtomicReference<ChatController.TextSendResult>()
        controlMain {
            assertTrue(pending.confirm(pending.preview, draft))
            assertTrue(controller.sendBrowser(pending, draft, ticket) { result.set(it); done.countDown() })
        }
        await(done); await(session.entered)
        val accepted = result.get() as ChatController.TextSendResult.Accepted
        assertEquals(pending.identity.conversationId, accepted.conversation.id)
        assertEquals(listOf(accepted.conversation.id), sessionOwners)
        val rows = fixture.database.messages(accepted.conversation.id).map { it.second }
        assertTrue(rows.all { it.conversationId == accepted.conversation.id && it.runId == pending.identity.runId })
        controlMain { controller.acknowledgeTextSend(ticket) }
        awaitControl("browser run drained without opening tabs or requesting tools") { controller.active == null }
        assertEquals(1, fixture.opens.get()); assertEquals(1, modelFactories.get())
    }

    fun testPreviewReservationBlocksOrdinaryAndSecondBrowserSendsAndStaleReject() {
        val draft = draft()
        val pending = prepare(draft)
        val callbacks = AtomicInteger()
        assertNull("synchronous ordinary send must not enter", controller.send(null, draft.text))
        assertNull("second browser must not reserve", controller.prepareBrowserSend(null, draft, emptyList()))
        controlMain {
            assertFalse(controller.sendText(null, 17, 23, draft.text) { callbacks.incrementAndGet() })
            assertNull(controller.textSendState())
            assertSame(pending, controller.browserPreparation())
            controller.rejectBrowser(pending)
        }
        val replacement = prepare(draft)
        controlMain {
            controller.rejectBrowser(pending)
            assertSame("stale rejection cannot release a newer reservation", replacement, controller.browserPreparation())
            assertFalse(controller.sendText(null, 17, 23, draft.text) { callbacks.incrementAndGet() })
            controller.rejectBrowser(replacement)
        }
        assertEquals(0, callbacks.get())
        assertUncommitted(draft)
    }

    fun testStopBeforeSubmissionCancelsPreviewAndConfirmedReservationWithoutCommitting() {
        val draft = draft()
        for (confirmed in listOf(false, true)) {
            val pending = prepare(draft)
            controlMain {
                if (confirmed) assertTrue(pending.confirm(pending.preview, draft))
                controller.stop()
                assertNull(controller.browserPreparation())
                assertNull(controller.textSendState())
            }
            assertTrue(pending.isCancelled)
            assertEquals(ControllerBrowserPreparation.State.DRAINED, pending.state)
            assertUncommitted(draft)
        }
        val replacement = prepare(draft)
        controlMain { controller.rejectBrowser(replacement) }
    }

    fun testTicketCancelBeforeQueuedBrowserOpenRejectsAndRetainsResultUntilAcknowledged() {
        val draft = draft()
        val pending = prepare(draft)
        val gate = holdWorker()
        val ticket = ChatController.TextSendTicket()
        val owner = ChatController.TextSendOwner(null, 17, 23)
        val result = AtomicReference<ChatController.TextSendResult>()
        val done = CountDownLatch(1)
        val callbacks = AtomicInteger()
        controlMain {
            assertTrue(pending.confirm(pending.preview, draft))
            assertTrue(controller.sendBrowser(pending, draft, ticket) {
                assertEquals(Looper.getMainLooper(), Looper.myLooper())
                callbacks.incrementAndGet(); result.set(it); done.countDown()
            })
            val state = checkNotNull(controller.textSendState())
            assertSame(ticket, state.ticket)
            assertEquals(owner, state.owner)
            assertEquals(draft.text, state.text)
            assertNull(state.result)
            assertFalse(controller.cancelTextSend(ChatController.TextSendTicket()))
            assertTrue(controller.cancelTextSend(ticket))
            assertSame("queued worker still owns the reservation", pending, controller.browserPreparation())
            assertFalse(controller.sendText(null, 18, 24, draft.text) { fail("not admitted") })
        }
        assertUncommitted(draft)
        gate.release.countDown()
        await(done)
        assertEquals(ChatController.TextSendResult.Rejected(owner), result.get())
        assertEquals(1, callbacks.get())
        controlMain {
            assertNull(controller.browserPreparation())
            val retained = checkNotNull(controller.textSendState())
            assertSame(ticket, retained.ticket)
            assertEquals(draft.text, retained.text)
            assertSame(result.get(), retained.result)
            assertFalse(controller.sendText(null, 18, 24, draft.text) { fail("unacknowledged result") })
            controller.acknowledgeTextSend(ticket)
            assertNull(controller.textSendState())
        }
        assertUncommitted(draft)
        val replacement = prepare(draft)
        controlMain { controller.rejectBrowser(replacement) }
    }

    fun testCloseBeforeQueuedBrowserOpenRejectsAndPreservesDurableDraft() {
        val draft = draft()
        val pending = prepare(draft)
        val gate = holdWorker()
        val ticket = ChatController.TextSendTicket()
        val result = AtomicReference<ChatController.TextSendResult>()
        val done = CountDownLatch(1)
        controlMain {
            assertTrue(pending.confirm(pending.preview, draft))
            assertTrue(controller.sendBrowser(pending, draft, ticket) { result.set(it); done.countDown() })
            controller.close() // Main-thread close fences admission without waiting on the held worker.
            assertFalse(controller.ready)
            assertFalse(controller.sendBrowser(pending, draft, ChatController.TextSendTicket()) { fail("closed") })
        }
        assertTrue(pending.isCancelled)
        assertUncommitted(draft) // Storage is still pinned by the queued preparation.
        gate.release.countDown()
        await(done)
        controller.close() // Off-main waits for actual storage cleanup, not just a UI notification.
        assertEquals(ChatController.TextSendResult.Rejected(ChatController.TextSendOwner(null, 17, 23)), result.get())
        assertNull(controller.browserPreparation())
        val reopened = ConversationDatabase(fixture.application)
        try { assertUncommitted(draft, reopened) } finally { reopened.close() }
    }

    fun testFreshFilesRealHostThroughControllerReturnsDurableAcceptedPairBeforeModelOpen() {
        val draft = draft()
        val pending = prepare(draft)
        val session = fixture.session("browser accepted answer", held = true)
        assertNoBrowserOrModel()
        val checked = CountDownLatch(1)
        val openFailure = AtomicReference<Throwable?>()
        beforeOpen.set { history ->
            try {
                val rows = fixture.database.messages(pending.identity.conversationId).map { it.second }
                assertEquals("durable user + assistant precede ModelPort.open", 2, rows.size)
                assertTrue(rows.all { it.runId == pending.identity.runId })
                assertEquals(draft.text, rows.single { it.role == app.daiyu.android.domain.Message.USER_ROLE }
                    .blocks.filterIsInstance<app.daiyu.android.domain.MessageBlock.Text>().single().text)
                assertEquals(pending.identity.assistantMessageId,
                    rows.single { it.role == app.daiyu.android.domain.Message.ASSISTANT_ROLE }.id)
                assertTrue(history.isNotEmpty())
                assertTrue(File(root, "browser-runs/downloads").isDirectory)
                assertTrue(File(root, "browser-runs/cookie-offloads").isDirectory)
            } catch (failure: Throwable) { openFailure.set(failure) }
            finally { checked.countDown() }
        }
        val ticket = ChatController.TextSendTicket()
        val done = CountDownLatch(1)
        val result = AtomicReference<ChatController.TextSendResult>()
        controlMain {
            assertTrue(pending.confirm(pending.preview, draft))
            assertFalse(File(root, "browser-runs").exists())
            assertTrue(controller.sendBrowser(pending, draft, ticket) { result.set(it); done.countDown() })
        }
        await(done); await(checked); await(session.entered)
        openFailure.get()?.let { throw it }
        val accepted = result.get() as ChatController.TextSendResult.Accepted
        assertEquals(ChatController.TextSendOwner(null, 17, 23), accepted.owner)
        assertEquals(pending.identity.conversationId, accepted.conversation.id)
        assertEquals(ControllerBrowserPreparation.State.TRANSFERRED, pending.state)
        assertEquals(1, fixture.opens.get())
        controlMain { controller.acknowledgeTextSend(ticket) }
        session.release.countDown()
        awaitControl("real browser/model teardown") { controller.active == null }
        controller.close()
        val reopened = ConversationDatabase(fixture.application)
        try {
            val rows = reopened.messages(accepted.conversation.id).map { it.second }
            assertEquals(2, rows.size)
            assertEquals(pending.identity.assistantMessageId,
                rows.single { it.role == app.daiyu.android.domain.Message.ASSISTANT_ROLE }.id)
            assertEquals("", reopened.draft())
        } finally { reopened.close() }
    }

    fun testAcceptedParentLaunchesIndependentlyApprovedChildUsingRealAndroidAssembly() {
        val draft = draft()
        val pending = prepare(draft)
        val plan = checkNotNull(controller.prepareBrowserChild(pending, "explicit browser child",
            setOf("list_tabs"), emptyList()))
        val first = fixture.session("one answer", held = true)
        val second = fixture.session("another answer", held = true)
        val ticket = ChatController.TextSendTicket()
        val done = CountDownLatch(1)
        val result = AtomicReference<ChatController.TextSendResult>()
        controlMain {
            assertTrue(plan.confirm(plan.preview))
            assertTrue(pending.confirm(pending.preview, draft))
            assertTrue(controller.sendBrowser(pending, draft, ticket) { result.set(it); done.countDown() })
        }
        await(done); await(first.entered); await(second.entered)
        assertTrue(result.get() is ChatController.TextSendResult.Accepted)
        assertEquals(2, fixture.opens.get())
        assertEquals(listOf(pending.identity.conversationId, pending.identity.conversationId), sessionOwners)
        assertFalse(plan.drained)
        assertTrue(fixture.histories.any { history -> history.any { it.runId.value == plan.scope.owner.childRunId } })
        val journal = app.daiyu.android.storage.SQLiteChildTaskStore(fixture.database)
        val running = checkNotNull(journal.get(plan.scope.owner))
        assertEquals(app.daiyu.android.domain.delegation.ChildTaskStatus.RUNNING, running.status)
        assertEquals(pending.identity.assistantMessageId.value, running.parentMessageId)
        assertEquals(plan.scope.owner, running.owner)
        controlMain {
            assertTrue(controller.hasLiveChildTasks(pending.identity.conversationId))
            assertEquals(app.daiyu.android.domain.delegation.ChildTaskStop.NOT_FOUND,
                controller.stopChildTask(running.copy(owner = running.owner.copy(generation = running.owner.generation + 1))))
            assertEquals(app.daiyu.android.domain.delegation.ChildTaskStop.NOT_FOUND,
                controller.stopChildTask(running.copy(owner = running.owner.copy(childRunId = "sibling-child"))))
            assertFalse(plan.drained)
            controller.acknowledgeTextSend(ticket)
        }
        first.release.countDown(); second.release.countDown()
        awaitControl("child worker, transport and real native host drain") { plan.drained && controller.active == null }
        assertEquals(app.daiyu.android.domain.delegation.ChildTaskStatus.COMPLETED, journal.get(plan.scope.owner)?.status)
        controller.close()
        val reopened = ConversationDatabase(fixture.application)
        try {
            val durable = app.daiyu.android.storage.SQLiteChildTaskStore(reopened).get(plan.scope.owner)
            assertNotNull(durable)
            assertEquals(app.daiyu.android.domain.delegation.ChildTaskStatus.COMPLETED, durable?.status)
            assertEquals(2, reopened.messages(pending.identity.conversationId).size)
        } finally { reopened.close() }
    }

    fun testIndependentChildApprovalIsRequiredAndQueuedCancellationIsZeroIO() {
        val draft = draft()
        val pending = prepare(draft)
        val plan = checkNotNull(controller.prepareBrowserChild(pending, "independent child prompt",
            setOf("list_tabs"), emptyList()))
        assertSame(plan, pending.childPlan)
        assertNull(controller.prepareBrowserChild(pending, "second child", setOf("list_tabs"), emptyList()))
        val sibling = ControllerChildBrowserPlan.prepare(pending, "different frozen child", setOf("list_tabs"), emptyList())
        assertFalse(plan.confirm(sibling.preview))
        sibling.close()
        assertEquals(pending.identity.runId.value, plan.scope.owner.parentRunId)
        assertEquals(plan.scope.owner, plan.preview.childOwner)
        controlMain {
            assertFalse("parent click is not child consent", pending.confirm(pending.preview, draft))
            assertTrue(plan.confirm(plan.preview))
            assertFalse(plan.confirm(plan.preview))
            assertTrue(pending.confirm(pending.preview, draft))
        }
        assertUncommitted(draft)
        val gate = holdWorker()
        val ticket = ChatController.TextSendTicket()
        val done = CountDownLatch(1)
        val result = AtomicReference<ChatController.TextSendResult>()
        controlMain {
            assertTrue(controller.sendBrowser(pending, draft, ticket) { result.set(it); done.countDown() })
            assertTrue(controller.cancelTextSend(ticket))
        }
        gate.release.countDown(); await(done)
        assertTrue(result.get() is ChatController.TextSendResult.Rejected)
        assertUncommitted(draft)
        assertTrue(plan.drained)
        assertTrue(app.daiyu.android.storage.SQLiteChildTaskStore(fixture.database).list(pending.identity.conversationId.value).isEmpty())
        controlMain { controller.acknowledgeTextSend(ticket) }
    }

    fun testInvalidJsonNeverCreatesBrowserOrMessagePairAndEachFailureReleasesSlot() {
        browserSettings.save(BrowserSettings(BrowserAccess.FULL))
        val conversation = fixture.database.create("default")
        val draft = draft(conversation)
        // Malformed JSON, non-object JSON, and a trailing second value exercise real parsing.
        for (raw in listOf("{", "[]", "{\"action\":\"new_tab\"} {\"action\":\"new_tab\"}")) {
            assertNull(controller.prepareBrowserSend(conversation, draft, listOf(raw)))
            assertNotNull(controller.problem)
            assertNull(controller.browserPreparation())
            assertNull(controller.textSendState())
            assertUncommitted(draft)
            val replacement = prepare(draft, conversation)
            controlMain { controller.rejectBrowser(replacement) }
        }
    }
}
