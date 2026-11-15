package app.daiyu.android

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
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
    private var delegateChildren = true

    override fun createFixture() = RunControlFixture(instrumentation.targetContext, modelFactory = { config ->
        object : ModelPort {
            override fun open(history: List<Message>): ModelSession = error("explicit definitions required")
            override fun open(history: List<Message>, definitions: List<ToolDefinition>): ModelSession {
                val child = config.model == "choice-child"
                if (child) {
                    check(definitions.isEmpty()); childOpens.incrementAndGet()
                } else {
                    if (delegateChildren) check(definitions.any { it.name == "start_child_tasks" })
                    parentOpens.incrementAndGet()
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
                        } else if (delegateChildren && round++ == 0) {
                            sink(ModelEvent.ToolReady(ToolInvocation(ToolCallId("choice-call"), "start_child_tasks",
                                """{"tasks":[{"profile_id":"choice-review","prompt":"review once"}]}""")))
                        } else {
                            if (delegateChildren) check(outputs.single().output.contains("accepted"))
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

    private fun runningOwner(withChildren: Boolean = true): Message {
        delegateChildren = withChildren
        node("main-stop-keep-children").assertDoesNotExist()
        node("main-stop-cancel-children").assertDoesNotExist()
        val conversation = fixture.database.create("default")
        if (withChildren) {
            val child = SubAgentModel(UUID.randomUUID().toString(), "choice-child")
            val catalog = fixture.settings.modelCatalogSnapshot()
            assertEquals(CatalogCommitResult.COMMITTED, fixture.settings.modelCatalog.compareAndAppend(
                catalog.providerIdentity, catalog.revision, listOf(SavedCatalogModel(child.modelId, child.displayName,
                    CatalogModelParameters(localIdentity = child.id)))) { false })
            val preferences = ConversationSubAgentPreferences(fixture.application.getSharedPreferences("subagent_configuration", 0))
            val key = SubAgentConfigKey.Conversation(conversation.id.value)
            preferences.createConversation(key)
            assertTrue(preferences.update(key) { it.copy(enabled = true, profiles = listOf(
                SubAgentProfile("choice-review", "Reviewer", "review", providerId = "primary", modelId = child.id))) }
                is ConversationSubAgentPreferences.WriteResult.Saved)
        }
        mainThread { activity.select(conversation) }
        assertNotNull(fixture.controller.sendWithChildren(conversation, "parent task"))
        val owner = checkNotNull(fixture.controller.active)
        if (withChildren) assertTrue(childEntered.await(5, TimeUnit.SECONDS))
        assertTrue(parentEntered.await(5, TimeUnit.SECONDS))
        return owner
    }

    private fun stopParent() {
        awaitUniqueTag("chat-control")
        node("chat-control").performTouchInput { longClick() }
        awaitControl("main stopped before choice") { fixture.controller.active == null }
    }

    private fun stoppedOwner(): Message {
        val owner = runningOwner()
        stopParent()
        awaitUniqueTag("main-stop-keep-children")
        node("main-stop-cancel-children").assertIsDisplayed()
        node("main-stop-continue-children").assertIsDisplayed()
        ui.onNodeWithText("子任务处理").assertIsDisplayed()
        listOf("main-stop-continue-children" to "继续", "main-stop-keep-children" to "暂停",
            "main-stop-cancel-children" to "停止").forEach { (tag, label) ->
            node(tag).assertHasClickAction().assertTextEquals(label)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
        }
        val continueY = node("main-stop-continue-children").fetchSemanticsNode().boundsInRoot.top
        val pauseY = node("main-stop-keep-children").fetchSemanticsNode().boundsInRoot.top
        val stopY = node("main-stop-cancel-children").fetchSemanticsNode().boundsInRoot.top
        assertTrue(continueY < pauseY && pauseY < stopY)
        assertEquals(1, parentOpens.get()); assertEquals(1, childOpens.get())
        val pending = fixture.controller.liveChildTasks(owner.conversationId).single()
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

    private fun assertNoStopChoice() {
        ui.waitForIdle()
        assertNull(fixture.controller.childStopChoice())
        node("child-stop-choice-sheet").assertDoesNotExist()
        listOf("main-stop-continue-children", "main-stop-keep-children", "main-stop-cancel-children").forEach {
            node(it).assertDoesNotExist()
        }
    }

    fun testStopWithoutChildTasksDoesNotShowChoice() {
        runningOwner(withChildren = false)
        stopParent()
        assertNoStopChoice()
        assertEquals(1, parentOpens.get()); assertEquals(0, childOpens.get())
    }

    fun testStopAfterAllChildrenCompletedDoesNotShowChoice() {
        val owner = runningOwner()
        childRelease.countDown()
        assertTrue(childExited.await(5, TimeUnit.SECONDS))
        awaitControl("completed child history is not running") { !fixture.controller.hasLiveChildTasks() }
        stopParent()
        assertNoStopChoice()
        assertEquals(ChildTaskStatus.COMPLETED, fixture.controller.childTasks(owner.conversationId).single().status)
        assertEquals(1, parentOpens.get()); assertEquals(1, childOpens.get())
    }

    fun testStopWithOnlyManuallyPausedChildDoesNotShowChoiceOrResumeIt() {
        val owner = runningOwner()
        val child = fixture.controller.liveChildTasks(owner.conversationId).single().record
        assertEquals(ChildTaskControlReceipt.PENDING, fixture.controller.pauseChildTask(child))
        stopParent()
        assertNoStopChoice()
        finishPreservedChild(owner)
    }

    fun testStopWithOnlyStoppedChildHistoryDoesNotShowChoice() {
        val owner = runningOwner()
        val child = fixture.controller.liveChildTasks(owner.conversationId).single().record
        fixture.controller.stopChildTask(child)
        assertTrue(childExited.await(5, TimeUnit.SECONDS))
        awaitControl("stopped child drained") { !fixture.controller.hasLiveChildTasks() }
        stopParent()
        assertNoStopChoice()
        assertEquals(ChildTaskControlReceipt.STALE, fixture.controller.resumeChildTask(child))
        assertEquals(ChildTaskStatus.UNKNOWN, fixture.controller.childTasks(owner.conversationId).single().status)
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

    fun testContinueChoiceResumesOriginalChildWithoutReopeningParentOrChild() {
        val owner = stoppedOwner()
        val child = fixture.controller.liveChildTasks(owner.conversationId).single().record.owner
        val choice = checkNotNull(fixture.controller.childStopChoice())
        node("main-stop-continue-children").performClick()
        awaitChoiceGone()
        val retained = fixture.controller.liveChildTasks(owner.conversationId).single()
        assertEquals(child, retained.record.owner)
        assertEquals(ChildTaskPauseState.ACTIVE, retained.pauseState)
        assertEquals(0, childCancels.get())
        assertFalse(fixture.controller.finishChildStopChoice(choice, true))
        childRelease.countDown()
        assertTrue(childExited.await(5, TimeUnit.SECONDS))
        awaitControl("continued original child drains") { !fixture.controller.hasLiveChildTasks() }
        assertEquals(1, parentOpens.get()); assertEquals(1, childOpens.get())
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
