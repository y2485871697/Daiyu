package app.daiyu.android

import android.app.Application
import android.content.Context
import android.os.Looper
import android.test.InstrumentationTestCase
import app.daiyu.android.delegation.*
import app.daiyu.android.domain.*
import app.daiyu.android.domain.delegation.ChildTaskStatus
import app.daiyu.android.domain.delegation.ChildTaskStop
import app.daiyu.android.storage.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real Controller/Runner/store; reflection only observes retained handles, never changes state. */
@Suppress("DEPRECATION")
class ControllerChildRetentionTest : InstrumentationTestCase() {
    private class FixtureApplication(base: Context) : Application() { init { attachBaseContext(base) } }
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
        val parentOpens = AtomicInteger()
        val child = SubAgentModel("child-record", "child-api")
        val provider = SubAgentProvider("explicit-provider", "Fixture", listOf(child))
        val profile = SubAgentProfile("review", "Reviewer", "review", providerId = provider.id, modelId = child.id)
        val controller: ChatController
        lateinit var conversation: Conversation
        init {
            settings.save(ModelSettings("https://example.invalid", "parent-api", "synthetic-key", false))
            controller = ChatController(app, database, settings, app.getSharedPreferences("conversation-ui", 0), { config ->
                object : ModelPort {
                    override fun open(history: List<Message>): ModelSession = error("explicit definitions required")
                    override fun open(history: List<Message>, definitions: List<ToolDefinition>): ModelSession {
                        val isChild = config.model == "child-api"
                        if (isChild) { check(definitions.isEmpty()); childOpens.incrementAndGet() }
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
            })
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
            childRelease.countDown(); childCleanupRelease.countDown(); parentRelease.countDown(); controller.close()
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
            main { assertTrue(it.controller.bindChildSavedModel(it.conversation.id, it.profile.id, it.child.modelId, it.provider)) }
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
        })
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

    fun testRevokingSavedModelRetainsStoppedChildrenUntilExit() {
        val f = fresh()
        send(f); assertTrue(f.childEntered.await(5, TimeUnit.SECONDS))
        awaitChange(f, "parent complete before revoke") { f.controller.active == null }
        assertParentCompleted(f)
        main { f.controller.revokeChildSavedModel(f.conversation.id, f.profile.id) }
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
