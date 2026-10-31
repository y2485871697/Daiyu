package app.daiyu.android

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import app.daiyu.android.domain.MessageBlock
import app.daiyu.android.domain.RunStatus
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real MainActivity -> sendText -> preparation -> SQLite receipt -> Activity callback.
 * Model initialization and (where stated) real main-message delivery are gated;
 * neither sendText nor its result/callback is replaced by a fake.
 */
class TextSendActivityTest : WiredActivityTestCase() {
    private val preparing = CountDownLatch(1)
    private val releasePreparation = CountDownLatch(1)
    private val preparationCalls = AtomicInteger()
    private val preparationOnMain = AtomicBoolean()
    private val rejectPreparation = AtomicBoolean()
    private val laterPreparationGate = AtomicReference<CountDownLatch?>()

    internal override fun createFixture() = RunControlFixture(instrumentation.targetContext) {
        preparationCalls.incrementAndGet()
        preparationOnMain.set(Looper.myLooper() == Looper.getMainLooper())
        preparing.countDown()
        check(releasePreparation.await(30, TimeUnit.SECONDS)) { "test preparation gate timed out" }
        laterPreparationGate.get()?.let {
            check(it.await(30, TimeUnit.SECONDS)) { "test later preparation gate timed out" }
        }
        check(!rejectPreparation.get()) { "test model initialization rejected" }
    }

    override fun tearDown() {
        releasePreparation.countDown()
        laterPreparationGate.get()?.countDown()
        super.tearDown()
    }

    private fun awaitPreparing() {
        assertTrue("send must reach the worker preparation gate", preparing.await(5, TimeUnit.SECONDS))
        assertFalse("model preparation must not run on main", preparationOnMain.get())
        node("chat-draft").assertIsNotEnabled()
        node("chat-send").assertDoesNotExist()
        node("text-send-cancel").assertIsEnabled()
        assertEquals(0, fixture.opens.get())
        assertEquals(1, preparationCalls.get())
        node("chat-send-preview").assertIsDisplayed()
        ui.onNodeWithText("正在准备发送…").assertDoesNotExist()
        ui.onNodeWithText("正在准备上下文并核实发送结果").assertDoesNotExist()
        ui.onNodeWithText("取消发送准备").assertDoesNotExist()
        node("text-send-cancel").assertContentDescriptionEquals("取消发送")
        mainThread {
            val getter = MainActivity::class.java.declaredMethods.single { it.name == "getChatState" && it.parameterCount == 0 }
                .apply { isAccessible = true }
            val state = getter.invoke(activity) as app.daiyu.android.ui.screens.chat.conversation.ChatScreenState
            val preview = checkNotNull(state.submissionPreview)
            assertEquals(checkNotNull(fixture.controller.textSendState()).text, preview.text)
            assertNull("preparation is not a durable receipt", preview.settledAssistantId)
            assertFalse("the preview never enters authoritative history", state.turns.any { it.id == preview.key })
        }
    }

    private fun assertUserText(conversation: app.daiyu.android.domain.ConversationId, text: String) {
        val rows = fixture.database.messages(conversation)
        assertEquals("one durable user/assistant pair", 2, rows.size)
        val user = rows.single { it.second.role == "user" }.second
        assertEquals(RunStatus.COMPLETED, user.status)
        assertEquals(listOf(MessageBlock.Text("user", text)), user.blocks)
        assertEquals(user.runId, rows.single { it.second.role == "assistant" }.second.runId)
    }

    private fun finish(session: RunControlFixture.Session) {
        session.release.countDown()
        awaitControl("accepted run completed") { fixture.controller.active == null }
        assertEquals(1, fixture.opens.get())
        assertEquals(1, preparationCalls.get())
    }

    fun testAcceptedReceiptClearsOnlyExactDraftAndRevokesOldEditor() {
        val text = "  第一行\n第二行末尾  "
        val session = fixture.session(holdSeconds = 120)
        setDraft(text); setSelection(2, 5)
        val oldEditor = retainedControls(activity)
        node("chat-send").performClick()
        awaitPreparing()
        assertDraft(text)
        assertEquals(text, fixture.database.draft())
        assertTrue("preparation is not a database receipt", fixture.database.conversations().isEmpty())

        releasePreparation.countDown()
        awaitTextSendResult(activity)
        assertTrue(session.entered.await(5, TimeUnit.SECONDS))
        assertDraft(""); assertSelection(0, 0)
        node("chat-draft").assertIsEnabled()
        val owner = checkNotNull(fixture.controller.active)
        assertUserText(owner.conversationId, text)
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("chat-send-preview").fetchSemanticsNodes().isEmpty() }
        assertEquals("", fixture.database.draft())
        assertEquals("", fixture.database.conversations().single().draft)
        assertEquals(owner.conversationId.value, fixture.controller.preferences.getString("selected", null))
        finish(session)

        // Same bytes do not authorize a pre-acceptance editor in the new generation.
        setDraft(text); setSelection(1, 3)
        mainThread {
            oldEditor.onTextChange("stale edit")
            oldEditor.onSelectionChange(0, 0)
            oldEditor.onSend(text)
        }
        awaitTextSendResult(activity)
        assertDraft(text); assertSelection(1, 3)
        assertEquals(text, fixture.database.conversations().single().draft)
        assertUserText(owner.conversationId, text)
        assertEquals(1, fixture.opens.get()); assertEquals(1, preparationCalls.get())
    }

    fun testRejectedCallbackRetainsExactDraftAndSelection() {
        rejectPreparation.set(true)
        val text = "  拒绝后保留\n尾部  "
        setDraft(text); setSelection(2, 6)
        node("chat-send").performClick()
        awaitPreparing()
        assertEquals(text, fixture.database.draft())
        releasePreparation.countDown()
        awaitTextSendResult(activity)

        node("chat-draft").assertIsEnabled()
        node("chat-send").assertIsEnabled()
        node("chat-send-preview").assertDoesNotExist()
        assertDraft(text); assertSelection(2, 6)
        assertEquals(text, fixture.database.draft())
        assertTrue(fixture.database.conversations().isEmpty())
        assertNull(fixture.controller.active)
        assertNotNull("the real preparation rejection is reported", fixture.controller.problem)
        assertEquals(0, fixture.opens.get()); assertEquals(1, preparationCalls.get())
    }

    fun testCancelButtonKeepsPendingDraftFrozenUntilWorkerExitsAndStaleButtonCannotCancelNextTicket() {
        val text = "  取消准备保留\n尾部  "
        node("text-send-cancel").assertDoesNotExist()
        setDraft(text); setSelection(2, 6)
        val oldEditor = retainedControls(activity)
        node("chat-send").performClick()
        awaitPreparing()
        node("text-send-cancel").assertIsEnabled()
        lateinit var submission: ChatController.TextSendState
        lateinit var pending: Any
        lateinit var staleCancel: () -> Unit
        lateinit var staleComposerCancel: () -> Unit
        mainThread {
            submission = checkNotNull(fixture.controller.textSendState())
            pending = checkNotNull(pendingSend(activity))
            val ticket = pending.javaClass.getDeclaredField("ticket").apply { isAccessible = true }.get(pending)
            assertSame("retain the ticket created by the real UI", submission.ticket, ticket)
            staleCancel = retainedCancel(activity, pending)
            staleComposerCancel = retainedControls(activity).onCancelSend
        }
        // Invoke the attached button, not controller.stop() or a synthetic UI callback.
        node("text-send-cancel").performTouchInput { click(center) }
        mainThread {
            assertSame("button must not clear the UI pending send", pending, pendingSend(activity))
            val retained = checkNotNull(fixture.controller.textSendState())
            assertSame(submission.ticket, retained.ticket)
            assertNull("cancel is not a completed rejection", retained.result)
            staleCancel() // repeated host cancellation is still not completion
            oldEditor.onSend(text)
            oldEditor.onTextChange("must not unfreeze the pending revision")
            assertFalse(fixture.controller.sendText(null, 90, 91, "duplicate") { fail("not admitted") })
        }
        node("chat-draft").assertIsNotEnabled()
        node("chat-send").assertDoesNotExist()
        node("text-send-cancel").assertIsEnabled()
        node("text-send-cancel").assertExists()
        assertDraft(text); assertSelection(2, 6)
        assertEquals(text, fixture.database.draft())
        assertTrue(fixture.database.conversations().isEmpty())
        assertEquals(0, fixture.opens.get()); assertEquals(1, preparationCalls.get())

        releasePreparation.countDown()
        awaitTextSendResult(activity)
        node("text-send-cancel").assertDoesNotExist()
        node("chat-draft").assertIsEnabled()
        node("chat-send").assertIsEnabled()
        assertDraft(text); assertSelection(2, 6)
        assertEquals(text, fixture.database.draft())
        assertTrue(fixture.database.conversations().isEmpty())
        mainThread {
            assertNull(pendingSend(activity))
            assertNull(fixture.controller.textSendState())
        }

        // The first button's host capability keeps its original pending owner/ticket.
        // Never fetch the replacement button's action: that would test the new owner instead.
        val nextGate = CountDownLatch(1)
        laterPreparationGate.set(nextGate)
        val nextText = "新的 ticket 可以发送"
        val session = fixture.session(holdSeconds = 120)
        setDraft(nextText)
        node("chat-send").performClick()
        awaitControl("second preparation entered") { preparationCalls.get() == 2 }
        mainThread {
            val nextSubmission = checkNotNull(fixture.controller.textSendState())
            assertNotSame(submission.ticket, nextSubmission.ticket)
            val nextPending = checkNotNull(pendingSend(activity))
            staleCancel()
            staleComposerCancel()
            assertSame(nextPending, pendingSend(activity))
            assertSame(nextSubmission, fixture.controller.textSendState())
        }
        node("chat-draft").assertIsNotEnabled()
        node("chat-send").assertDoesNotExist()
        node("text-send-cancel").assertIsEnabled()
        assertDraft(nextText)
        assertEquals(0, fixture.opens.get())
        nextGate.countDown()
        awaitTextSendResult(activity)
        assertTrue("stale cancel must not reject the new send", session.entered.await(5, TimeUnit.SECONDS))
        val owner = checkNotNull(fixture.controller.active)
        assertUserText(owner.conversationId, nextText)
        assertDraft("")
        node("text-send-cancel").assertDoesNotExist()
        session.release.countDown()
        awaitControl("accepted second run completed") { fixture.controller.active == null }
        assertEquals(1, fixture.opens.get()); assertEquals(2, preparationCalls.get())
    }

    fun testDoubleClickAndPendingRetainedControlsCannotResendOrReplaceDraft() {
        val text = "  只发送一次\n  "
        val other = fixture.database.create()
        val session = fixture.session(holdSeconds = 120)
        setDraft(text); setSelection(1, 3)
        val controls = retainedControls(activity)
        // Two invocations of the attached UI action in one main turn, before recomposition.
        node("chat-send").performSemanticsAction(SemanticsActions.OnClick) { click ->
            assertTrue(click()); assertTrue(click())
        }
        awaitPreparing()
        mainThread {
            controls.onSend(text)
            controls.onTextChange("must not replace the pending revision")
            try {
                activity.select(other)
                fail("selection must not replace a pending send owner")
            } catch (_: IllegalStateException) { }
        }
        assertDraft(text); assertSelection(1, 3)
        assertEquals(text, fixture.database.draft())
        assertTrue(fixture.database.messages(other.id).isEmpty())
        assertNull(fixture.controller.preferences.getString("selected", null))

        releasePreparation.countDown()
        awaitTextSendResult(activity)
        assertTrue(session.entered.await(5, TimeUnit.SECONDS))
        assertDraft(""); assertSelection(0, 0)
        val owner = checkNotNull(fixture.controller.active)
        assertUserText(owner.conversationId, text)
        finish(session)
        // Duplicate retained delivery after completion must not create a second turn either.
        mainThread { controls.onSend(text) }
        awaitTextSendResult(activity)
        assertUserText(owner.conversationId, text)
        assertTrue(fixture.database.messages(other.id).isEmpty())
        assertEquals(2, fixture.database.conversations().size)
        assertEquals(1, fixture.opens.get()); assertEquals(1, preparationCalls.get())
    }

    fun testRotationKeepsPendingEditorFrozenUntilLateRejectionIsConsumed() {
        val source = fixture.database.create("default")
        val target = fixture.database.create()
        val text = "  原会话发送\n  "
        val nextDraft = "  新 owner 草稿\n保留  "
        mainThread { activity.select(source) }
        setDraft(text); setSelection(1, 3)
        val oldEditor = retainedControls(activity)
        node("chat-send").performClick()
        awaitPreparing()
        val oldActivity = activity
        activity = recreateActivity(oldActivity)
        // f63a5f9: replacement adopts the same retained ticket, not a fresh editable draft.
        node("chat-draft").assertIsNotEnabled()
        node("chat-send").assertDoesNotExist()
        node("text-send-cancel").assertIsEnabled()
        mainThread {
            oldEditor.onTextChange("destroyed editor"); oldEditor.onSend(text)
            try {
                activity.select(target)
                fail("the replacement must retain the pending owner until its result")
            } catch (_: IllegalStateException) { }
        }
        assertDraft(text); assertSelection(1, 3)
        assertEquals(text, fixture.database.conversations().single { it.id == source.id }.draft)

        releasePreparation.countDown()
        awaitTextSendResult(activity) // The replacement, not the destroyed callback, consumes it.
        assertDraft(text); assertSelection(1, 3)
        node("chat-draft").assertIsEnabled()
        assertTrue(fixture.database.messages(source.id).isEmpty())
        mainThread { assertNull("the UI acknowledged the retained result", fixture.controller.textSendState()) }
        assertNull(fixture.controller.active)
        assertEquals(0, fixture.opens.get()); assertEquals(1, preparationCalls.get())

        mainThread { activity.select(target) }
        setDraft(nextDraft); setSelection(2, 5)
        mainThread { oldEditor.onTextChange(text); oldEditor.onSend(text) }
        awaitTextSendResult(activity)
        assertDraft(nextDraft); assertSelection(2, 5)
        assertEquals(target.id.value, fixture.controller.preferences.getString("selected", null))
        assertEquals(nextDraft, fixture.database.conversations().single { it.id == target.id }.draft)
        assertTrue(fixture.database.messages(target.id).isEmpty())
        assertEquals(text, fixture.database.conversations().single { it.id == source.id }.draft)
        assertEquals(2, fixture.database.conversations().size)
    }

    fun testUiSendCommittedReceiptSurvivesRecreationBeforeDelivery() =
        destroyAfterUiSendCommit(recreate = true)

    fun testUiSendCommittedReceiptSurvivesFinishAndRestartBeforeDelivery() =
        destroyAfterUiSendCommit(recreate = false)

    // Like ComposeActivityTestCase.retainedClick, retain the host capability rather than
    // Compose's attachment-dependent ClickableNode wrapper. Both host and pending are captured
    // now, not looked up when invoked after the next send. The real button must use this same
    // host method; see P09_UI_REGRESSION_SEAM.md for the required parent-owned extraction.
    private fun retainedCancel(host: MainActivity, pending: Any): () -> Unit {
        val cancel = MainActivity::class.java.declaredMethods.single {
            it.name == "cancelPendingTextSend" && it.parameterCount == 1 &&
                it.parameterTypes.single().isInstance(pending)
        }.apply { isAccessible = true }
        return {
            try { cancel.invoke(host, pending); Unit }
            catch (failure: java.lang.reflect.InvocationTargetException) { throw failure.targetException }
        }
    }

    private fun pendingSend(host: MainActivity): Any? = MainActivity::class.java.declaredMethods
        .single { it.name == "getPendingTextSend" && it.parameterCount == 0 }
        .apply { isAccessible = true }.invoke(host)

    private class DestructionReceiptProbe(private val target: MainActivity,
        private val controller: ChatController) : Application.ActivityLifecycleCallbacks {
        val destroyed = CountDownLatch(1)
        var state: ChatController.TextSendState? = null
            private set
        override fun onActivityDestroyed(activity: Activity) {
            if (activity === target) {
                // Application callback runs from super.onDestroy, AFTER Main's cancellation.
                state = controller.textSendState()
                destroyed.countDown()
            }
        }
        override fun onActivityCreated(activity: Activity, state: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
    }

    private fun destroyAfterUiSendCommit(recreate: Boolean) {
        val source = fixture.database.create("default")
        val text = "  UI 实际发送的精确原文\n末尾  "
        val newer = "  重建后新草稿\n不能被旧 callback 清除  "
        val session = fixture.session(holdSeconds = 120)
        mainThread { activity.select(source) }
        setDraft(text); setSelection(2, 5)
        val oldActivity = activity
        val oldEditor = retainedControls(oldActivity)
        val destruction = DestructionReceiptProbe(oldActivity, fixture.controller)

        // Admission MUST originate in the attached chat-send semantics, never in sendText.
        node("chat-send").performClick()
        awaitPreparing()
        lateinit var submission: ChatController.TextSendState
        lateinit var oldPending: Any
        lateinit var delivery: TextSendDeliveryGate
        mainThread {
            submission = checkNotNull(fixture.controller.textSendState())
            assertNull(submission.result)
            assertEquals(text, submission.text)
            assertEquals(source.id, submission.owner.conversationId)
            oldPending = checkNotNull(pendingSend(oldActivity))
            val ticket = oldPending.javaClass.getDeclaredField("ticket").apply { isAccessible = true }.get(oldPending)
            assertSame("retain the ticket created by the real UI", submission.ticket, ticket)
            delivery = TextSendDeliveryGate(fixture.controller)
            delivery.hold()
            oldActivity.application.registerActivityLifecycleCallbacks(destruction)
        }
        try {
            releasePreparation.countDown()
            // The same single-thread executor posts the result BEFORE starting this session.
            // Thus this latch proves both the real commit and the old callback's enqueue attempt.
            assertTrue(session.entered.await(5, TimeUnit.SECONDS))
            lateinit var receipt: ChatController.TextSendResult.Accepted
            mainThread {
                val committed = checkNotNull(fixture.controller.textSendState())
                assertSame(submission.ticket, committed.ticket)
                assertEquals(submission.owner, committed.owner)
                receipt = committed.result as ChatController.TextSendResult.Accepted
                assertEquals(submission.owner, receipt.owner)
                assertEquals(source.id, receipt.conversation.id)
                assertSame("old UI has not consumed the committed receipt", oldPending, pendingSend(oldActivity))
                assertTrue("real controller notifications/callback are deferred", delivery.heldCount > 0)
            }
            assertUserText(source.id, text)
            assertEquals("", fixture.database.conversations().single().draft)
            assertDraft(text); assertSelection(2, 5)
            node("chat-draft").assertIsNotEnabled()

            // Framework onDestroy runs while the SAME committed ticket is still UI-pending;
            // its cancelTextSend must not downgrade or lose Accepted.
            if (recreate) activity = recreateActivity(oldActivity)
            else {
                mainThread { oldActivity.finish() }
                ui.waitUntil(5_000) { oldActivity.isDestroyed }
                mainThread {
                    val retained = checkNotNull(fixture.controller.textSendState())
                    assertSame(submission.ticket, retained.ticket)
                    assertSame(receipt, retained.result)
                }
                activity = startMain()
            }
            assertTrue(oldActivity.isDestroyed)
            assertTrue("observe real framework destruction", destruction.destroyed.await(5, TimeUnit.SECONDS))
            val atDestruction = checkNotNull(destruction.state)
            assertSame("onDestroy must keep the UI-created ticket", submission.ticket, atDestruction.ticket)
            assertSame("onDestroy cancellation must preserve Accepted", receipt, atDestruction.result)
            awaitTextSendResult(activity) // synchronous replacement refresh adopts the real receipt
            assertDraft(""); assertSelection(0, 0)
            node("chat-draft").assertIsEnabled()
            assertEquals("", fixture.database.conversations().single().draft)
            assertEquals(source.id.value, fixture.controller.preferences.getString("selected", null))
            mainThread { assertSame(oldPending, pendingSend(oldActivity)) }

            // Queue still closed: install a new revision BEFORE delivering the original callback.
            setDraft(newer); setSelection(1, 4)
            val held = delivery.heldCount
            mainThread { assertEquals(held, delivery.release()) }
            assertEquals("all real deferred messages have now run on main", held, delivery.deliveredCount)
            mainThread {
                assertSame("destroyed callback must remain inert", oldPending, pendingSend(oldActivity))
                assertNull(pendingSend(activity))
                assertNull(fixture.controller.textSendState())
                oldEditor.onTextChange(text); oldEditor.onSend(text)
            }
            assertDraft(newer); assertSelection(1, 4)
            assertEquals(newer, fixture.database.conversations().single().draft)
            assertUserText(source.id, text)
            finish(session)
            activity = recreateActivity(activity)
            assertDraft(newer); assertSelection(1, 4)
            assertEquals(newer, fixture.database.conversations().single().draft)
            assertUserText(source.id, text)
        } finally {
            mainThread {
                oldActivity.application.unregisterActivityLifecycleCallbacks(destruction)
                delivery.close()
            }
        }
    }

    // These two tests intentionally seed sendText with NO Activity. They cover retained-state
    // adoption only, unlike the real chat-send / post-commit destruction cases above.
    fun testRetainedControllerTicketAdoptionFreezesReplacementUntilAccepted() =
        adoptRetainedControllerReceipt(beforeCompletion = true)

    fun testRetainedControllerAcceptedReceiptAdoptionDoesNotResurrectDraft() =
        adoptRetainedControllerReceipt(beforeCompletion = false)

    private fun adoptRetainedControllerReceipt(beforeCompletion: Boolean) {
        val source = fixture.database.create("default")
        val text = "  已提交的精确草稿\n  "
        mainThread { activity.select(source) }
        setDraft(text); setSelection(1, 4)
        val oldEditor = retainedControls(activity)
        val oldActivity = activity
        var generation = 0L
        var revision = 0L
        mainThread {
            generation = MainActivity::class.java.getDeclaredField("editorGeneration").apply { isAccessible = true }.getLong(oldActivity)
            revision = MainActivity::class.java.getDeclaredField("draftRevision").apply { isAccessible = true }.getLong(oldActivity)
            oldActivity.finish()
        }
        ui.waitUntil(5_000) { oldActivity.isDestroyed }

        // Seed a REAL retained controller ticket while there is no Activity. Reattach
        // either side of its DB receipt to cover adoption only, NOT a UI-originated send.
        val ticket = ChatController.TextSendTicket()
        val result = AtomicReference<ChatController.TextSendResult>()
        val completed = CountDownLatch(1)
        val session = fixture.session(holdSeconds = 120)
        mainThread {
            assertTrue(fixture.controller.sendText(source, generation, revision, text, ticket = ticket) {
                result.set(it); completed.countDown()
            })
        }
        assertTrue(preparing.await(5, TimeUnit.SECONDS))
        assertFalse(preparationOnMain.get())
        if (beforeCompletion) {
            activity = startMain()
            node("chat-draft").assertIsNotEnabled()
            node("chat-send").assertDoesNotExist()
            node("text-send-cancel").assertIsEnabled()
            assertDraft(text)
            assertEquals(text, fixture.database.conversations().single().draft)
            mainThread { assertSame(ticket, checkNotNull(fixture.controller.textSendState()).ticket) }
        }
        releasePreparation.countDown()
        assertTrue(completed.await(5, TimeUnit.SECONDS))
        assertTrue(result.get() is ChatController.TextSendResult.Accepted)
        assertTrue(session.entered.await(5, TimeUnit.SECONDS))
        assertUserText(source.id, text)
        assertEquals("", fixture.database.conversations().single().draft)
        if (!beforeCompletion) {
            mainThread { assertSame(ticket, checkNotNull(fixture.controller.textSendState()).ticket) }
            activity = startMain()
        }
        awaitTextSendResult(activity)
        assertDraft(""); assertSelection(0, 0)
        node("chat-draft").assertIsEnabled()
        mainThread { assertNull(fixture.controller.textSendState()) }
        assertEquals("", fixture.database.conversations().single().draft)
        activity = recreateActivity(activity)
        assertDraft(""); assertSelection(0, 0)
        assertEquals("", fixture.database.conversations().single().draft)
        assertUserText(source.id, text)
        finish(session)

        val newer = "  不应被旧结果清除\n  "
        setDraft(newer); setSelection(1, 4)
        mainThread { oldEditor.onTextChange(text); oldEditor.onSend(text) }
        awaitTextSendResult(activity)
        assertDraft(newer); assertSelection(1, 4)
        assertEquals(newer, fixture.database.conversations().single().draft)
        assertEquals(1, fixture.opens.get()); assertEquals(1, preparationCalls.get())
    }
}
