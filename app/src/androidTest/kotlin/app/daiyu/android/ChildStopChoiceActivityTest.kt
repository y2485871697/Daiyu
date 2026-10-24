package app.daiyu.android

import androidx.compose.ui.test.*
import app.daiyu.android.delegation.*
import app.daiyu.android.domain.*
import app.daiyu.android.domain.delegation.ChildTaskControlReceipt
import app.daiyu.android.domain.delegation.ChildTaskPauseState
import app.daiyu.android.domain.delegation.ChildTaskStatus
import app.daiyu.android.model.catalog.CatalogCommitResult
import app.daiyu.android.model.catalog.CatalogModelParameters
import app.daiyu.android.model.catalog.SavedCatalogModel
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ChildStopChoiceActivityTest : WiredActivityTestCase() {
    private val parentEntered = CountDownLatch(1)
    private val parentRelease = CountDownLatch(1)
    private val childEntered = CountDownLatch(1)
    private val childRelease = CountDownLatch(1)
    private val childExited = CountDownLatch(1)
    private val childCancels = AtomicInteger()
    private val parentOpens = AtomicInteger()
    private val childOpens = AtomicInteger()

    override fun createFixture() = RunControlFixture(instrumentation.targetContext, modelFactory = { config ->
        object : ModelPort {
            override fun open(history: List<Message>): ModelSession = error("explicit definitions required")
            override fun open(history: List<Message>, definitions: List<ToolDefinition>): ModelSession {
                val child = config.model == "choice-child"
                if (child) {
                    check(definitions.isEmpty()); childOpens.incrementAndGet()
                } else {
                    check(definitions.any { it.name == "start_child_tasks" }); parentOpens.incrementAndGet()
                }
                var round = 0
                return object : ModelSession {
                    override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                        if (child) {
                            childEntered.countDown()
                            try {
                                check(childRelease.await(30, TimeUnit.SECONDS))
                                sink(ModelEvent.TextFinal("child", "child answer"))
                            } finally { childExited.countDown() }
                        } else if (round++ == 0) {
                            sink(ModelEvent.ToolReady(ToolInvocation(ToolCallId("choice-call"), "start_child_tasks",
                                """{"tasks":[{"profile_id":"choice-review","prompt":"review once"}]}""")))
                        } else {
                            check(outputs.single().output.contains("accepted"))
                            parentEntered.countDown()
                            check(parentRelease.await(30, TimeUnit.SECONDS))
                            sink(ModelEvent.TextFinal("parent", "parent answer"))
                        }
                    }
                    override fun cancel() {
                        if (child) { childCancels.incrementAndGet(); childRelease.countDown() }
                        else parentRelease.countDown()
                    }
                }
            }
        }
    })

    private fun stoppedOwner(): Message {
        node("main-stop-keep-children").assertDoesNotExist()
        node("main-stop-cancel-children").assertDoesNotExist()
        val child = SubAgentModel(UUID.randomUUID().toString(), "choice-child")
        val catalog = fixture.settings.modelCatalogSnapshot()
        assertEquals(CatalogCommitResult.COMMITTED, fixture.settings.modelCatalog.compareAndAppend(
            catalog.providerIdentity, catalog.revision, listOf(SavedCatalogModel(child.modelId, child.displayName,
                CatalogModelParameters(localIdentity = child.id)))) { false })
        val conversation = fixture.database.create("default")
        val preferences = ConversationSubAgentPreferences(fixture.application.getSharedPreferences("subagent_configuration", 0))
        val key = SubAgentConfigKey.Conversation(conversation.id.value)
        preferences.createConversation(key)
        assertTrue(preferences.update(key) { it.copy(enabled = true, profiles = listOf(
            SubAgentProfile("choice-review", "Reviewer", "review", providerId = "primary", modelId = child.id))) }
            is ConversationSubAgentPreferences.WriteResult.Saved)
        mainThread { activity.select(conversation) }
        assertNotNull(fixture.controller.sendWithChildren(conversation, "parent task"))
        val owner = checkNotNull(fixture.controller.active)
        assertTrue(childEntered.await(5, TimeUnit.SECONDS))
        assertTrue(parentEntered.await(5, TimeUnit.SECONDS))
        awaitUniqueTag("chat-control")
        node("chat-control").performTouchInput { longClick() }
        awaitControl("main stopped before choice") { fixture.controller.active == null }
        awaitUniqueTag("main-stop-keep-children")
        node("main-stop-cancel-children").assertIsDisplayed()
        assertEquals(1, parentOpens.get()); assertEquals(1, childOpens.get())
        val pending = fixture.controller.liveChildTasks(conversation.id).single()
        assertEquals(ChildTaskPauseState.PENDING, pending.pauseState)
        assertFalse(pending.stopRequested)
        assertEquals(0, childCancels.get())
        return owner
    }

    private fun awaitChoiceGone() {
        ui.waitUntil(5_000) {
            ui.onAllNodesWithTag("main-stop-keep-children").fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        }
        node("main-stop-cancel-children").assertDoesNotExist()
        assertNull(fixture.controller.childStopChoice())
    }

    private fun finishPreservedChild(owner: Message) {
        val retained = fixture.controller.liveChildTasks(owner.conversationId).single()
        assertEquals(ChildTaskPauseState.PENDING, retained.pauseState)
        assertFalse(retained.stopRequested); assertEquals(0, childCancels.get())
        assertEquals(ChildTaskControlReceipt.RESUMED, fixture.controller.resumeChildTask(retained.record))
        childRelease.countDown()
        assertTrue(childExited.await(5, TimeUnit.SECONDS))
        awaitControl("preserved child really drained") { !fixture.controller.hasLiveChildTasks() }
        assertEquals(ChildTaskStatus.COMPLETED, fixture.controller.childTasks(owner.conversationId).single().status)
        assertEquals(1, parentOpens.get()); assertEquals(1, childOpens.get())
    }

    fun testKeepPauseConsumesChoiceWithoutActivityRecreation() {
        val owner = stoppedOwner()
        val choice = checkNotNull(fixture.controller.childStopChoice())
        node("main-stop-keep-children").performClick()
        awaitChoiceGone()
        assertFalse(fixture.controller.finishChildStopChoice(choice, true))
        finishPreservedChild(owner)
    }

    fun testBackDismissPreservesCapturedChildAndUnmountsChoice() {
        val owner = stoppedOwner()
        awaitNodeWindowFocus(node("main-stop-keep-children"))
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        awaitChoiceGone()
        finishPreservedChild(owner)
    }

    fun testStopChoiceStopsOnlyCapturedChildAndWaitsForDrain() {
        val owner = stoppedOwner()
        val choice = checkNotNull(fixture.controller.childStopChoice())
        node("main-stop-cancel-children").performClick()
        awaitChoiceGone()
        assertTrue(childExited.await(5, TimeUnit.SECONDS))
        awaitControl("stopped child really drained") { !fixture.controller.hasLiveChildTasks() }
        assertEquals(ChildTaskStatus.UNKNOWN, fixture.controller.childTasks(owner.conversationId).single().status)
        assertFalse(fixture.controller.finishChildStopChoice(choice, true))
        assertEquals(1, parentOpens.get()); assertEquals(1, childOpens.get())
        assertTrue(childCancels.get() > 0)
    }

    override fun cleanupFixture() {
        parentRelease.countDown(); childRelease.countDown()
        super.cleanupFixture()
    }
}
