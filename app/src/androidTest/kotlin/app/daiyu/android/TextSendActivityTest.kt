package app.daiyu.android

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import app.daiyu.android.domain.MessageBlock
import app.daiyu.android.domain.RunStatus
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** 检查 MainActivity 发送经准备、SQLite 回执到 Activity 回调的流程。
 * 通过阻塞模型初始化及指定的主线程消息投递控制测试时序。
 */
private class TextSendMotionScale : androidx.compose.ui.MotionDurationScale {
    override var scaleFactor = 0f
}

class TextSendActivityTest private constructor(
    private val motionScale: TextSendMotionScale,
) : WiredActivityTestCase(motionScale) {
    constructor() : this(TextSendMotionScale())

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
        assertDraft("")
        assertSelection(0, 0)
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
            val entrance = checkNotNull(state.userMessageEntrance)
            assertEquals(preview.key, entrance.key)
            assertEquals(preview.key, entrance.messageId)
            assertNull("preparation is not a durable receipt", preview.settledAssistantId)
            assertFalse("the preview never enters authoritative history", state.turns.any { it.id == preview.key })
        }
    }

    private fun assertPendingDraftRetained(text: String, start: Int, end: Int) {
        assertDraft("")
        assertSelection(0, 0)
        mainThread {
            fun retained(name: String) = if (name == "draft") MainActivity::class.java.getDeclaredMethod("getDraft")
                .apply { isAccessible = true }.invoke(activity)
                else MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
            assertEquals(text, retained("draft"))
            assertEquals(start, retained("selectionStart"))
            assertEquals(end, retained("selectionEnd"))
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
        assertPendingDraftRetained(text, 2, 5)
        val entrance = MainActivity::class.java.getDeclaredField("userMessageEntrance")
            .apply { isAccessible = true }.get(activity)
        assertNotNull(entrance)
        assertEquals(text, fixture.database.draft())
        assertTrue("preparation is not a database receipt", fixture.database.conversations().isEmpty())

        releasePreparation.countDown()
        awaitTextSendResult(activity)
        assertTrue(session.entered.await(5, TimeUnit.SECONDS))
        assertDraft(""); assertSelection(0, 0)
        node("chat-draft").assertIsEnabled()
        val owner = checkNotNull(fixture.controller.active)
        assertUserText(owner.conversationId, text)
        mainThread {
            val current = MainActivity::class.java.getDeclaredField("userMessageEntrance")
                .apply { isAccessible = true }.get(activity)
                as app.daiyu.android.ui.screens.chat.conversation.UserMessageEntrance
            assertSame("preview and durable row share one entrance", entrance, current)
            assertEquals(fixture.database.messages(owner.conversationId).single {
                it.second.role == "user"
            }.second.id.value, current.messageId)
        }
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

    fun testFirstTextAcceptanceKeepsVideoMenuLiveAcrossRealPickerCancellation() {
        assertTrue(fixture.database.conversations().isEmpty())
        val session = fixture.session(holdSeconds = 120)
        setDraft("first text before video")
        node("chat-send").performClick()
        awaitPreparing()
        releasePreparation.countDown()
        awaitTextSendResult(activity)
        assertTrue(session.entered.await(5, TimeUnit.SECONDS))
        val conversation = fixture.database.conversations().single()
        assertEquals(conversation.id.value, fixture.controller.preferences.getString("selected", null))
        mainThread {
            val host = fixture.controller.videoHost
            assertEquals(conversation.id.value, host.state.conversation)
            val before = host.state
            invokeHost(activity, "publishChat")
            invokeHost(activity, "publishChat")
            assertEquals(before.owner, host.state.owner)
            assertEquals(before.visit, host.state.visit)
        }
        val caption = "caption retained after picker cancellation"
        setDraft(caption)
        val launches = AtomicInteger()
        val picker = object : android.app.Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: android.content.Intent): android.app.Instrumentation.ActivityResult? {
                if (intent.action == android.content.Intent.ACTION_GET_CONTENT && intent.type == "video/*") {
                    assertTrue(intent.getBooleanExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, false))
                    launches.incrementAndGet()
                }
                return null // Observe only: the actual system picker must run and return its own result.
            }
        }
        instrumentation.addMonitor(picker)
        var testFailure: Throwable? = null
        try {
            repeat(2) { index ->
                if (index == 0) assertNotNull(fixture.controller.active)
                else {
                    finish(session)
                    mainThread {
                        val before = fixture.controller.videoHost.state
                        invokeHost(activity, "publishChat")
                        assertEquals(before.visit, fixture.controller.videoHost.state.visit)
                    }
                }
                ui.onNodeWithContentDescription("附件").performClick()
                node("chat-attachment:ATTACH_VIDEO").assertIsDisplayed().assertIsEnabled().performClick()
                ui.waitUntil(5_000) { launches.get() == index + 1 }
                awaitControl("actual picker pauses or stops the chat Activity") {
                    !activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
                }
                // onPause precedes the picker's focused window; BACK must wait for that window.
                awaitHomeWindowSettled()
                assertTrue(instrumentation.uiAutomation.performGlobalAction(
                    android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
                awaitControl("actual picker returns to the same chat") {
                    activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) &&
                        invokeHost(activity, "getVideoPickEntry") == null
                }
                ui.waitForIdle()
                node("video_attachments_selecting").assertDoesNotExist()
                assertDraft(caption)
                mainThread {
                    assertEquals(conversation.id.value, fixture.controller.videoHost.state.conversation)
                    assertTrue(fixture.controller.videoHost.state.items.isEmpty())
                    assertTrue(fixture.controller.videoHost.state.visible)
                }
            }
            assertEquals(2, launches.get())
        } catch (failure: Throwable) {
            testFailure = failure
            throw failure
        } finally {
            session.release.countDown()
            instrumentation.removeMonitor(picker)
            try {
                var pickerStillOpen = false
                controlMain {
                    pickerStillOpen = launches.get() > 0 && !activity.isFinishing && !activity.isDestroyed &&
                        !activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
                }
                if (pickerStillOpen) {
                    assertTrue(instrumentation.uiAutomation.performGlobalAction(
                        android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
                    awaitControl("close remaining test picker") {
                        activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
                    }
                }
            } catch (cleanupFailure: Throwable) {
                val original = testFailure
                if (original != null) original.addSuppressed(cleanupFailure) else throw cleanupFailure
            }
        }
    }

    fun testCommittedReceiptKeepsPreparingControlUntilUiConsumesIt() = verifyUnconsumedReceipt(false)

    fun testRejectedReceiptKeepsPreparingControlUntilUiConsumesIt() = verifyUnconsumedReceipt(true)

    private fun verifyUnconsumedReceipt(rejected: Boolean) {
        rejectPreparation.set(rejected)
        val text = "  retained until receipt consumption  "
        val session = if (rejected) null else fixture.session(holdSeconds = 120)
        setDraft(text); setSelection(2, 6)
        node("chat-send").performClick()
        awaitPreparing()
        lateinit var submission: ChatController.TextSendState
        lateinit var delivery: TextSendDeliveryGate
        mainThread {
            submission = checkNotNull(fixture.controller.textSendState())
            delivery = TextSendDeliveryGate(fixture.controller)
            delivery.hold()
        }
        try {
            releasePreparation.countDown()
            if (session != null) assertTrue(session.entered.await(5, TimeUnit.SECONDS))
            else awaitControl("worker receipt produced before UI delivery") {
                fixture.controller.textSendState()?.result != null
            }
            mainThread {
                val result = checkNotNull(fixture.controller.textSendState()).result
                assertTrue(if (rejected) result is ChatController.TextSendResult.Rejected
                    else result is ChatController.TextSendResult.Accepted)
                assertNotNull(pendingSend(activity))
                assertTrue(delivery.heldCount > 0)
                val publish = MainActivity::class.java.declaredMethods.single {
                    it.name == "publishChat" && it.parameterCount == 2
                }.apply { isAccessible = true }
                publish.invoke(activity, false, null)
                val getter = MainActivity::class.java.declaredMethods.single {
                    it.name == "getChatState" && it.parameterCount == 0
                }.apply { isAccessible = true }
                val projected = getter.invoke(activity) as app.daiyu.android.ui.screens.chat.conversation.ChatScreenState
                assertEquals(submission.ticket.previewKey, projected.composer.preparingSendKey)
                assertEquals("", projected.composer.text)
                assertEquals(0, projected.composer.selectionStart)
                assertEquals(0, projected.composer.selectionEnd)
                val retainedDraft = MainActivity::class.java.getDeclaredMethod("getDraft").apply { isAccessible = true }
                assertEquals("The exact source draft remains available for rejection", text, retainedDraft.invoke(activity))
                assertFalse(projected.composer.enabled)
            }
            ui.waitForIdle()
            node("chat-send").assertDoesNotExist()
            node("chat-control").assertDoesNotExist()
            node("text-send-cancel").assertIsEnabled().performClick()
            mainThread {
                assertSame(submission.ticket, checkNotNull(fixture.controller.textSendState()).ticket)
                assertNotNull("late cancel cannot discard the unconsumed receipt", fixture.controller.textSendState()?.result)
                assertTrue(delivery.release() > 0)
            }
            awaitTextSendResult(activity)
            node("text-send-cancel").assertDoesNotExist()
            if (rejected) {
                assertDraft(text); assertSelection(2, 6)
                node("chat-send").assertIsEnabled()
                assertEquals(0, fixture.opens.get())
            } else {
                assertDraft(""); assertSelection(0, 0)
                node("chat-control").assertIsEnabled().assertContentDescriptionEquals("暂停")
                finish(checkNotNull(session))
            }
        } finally { mainThread { delivery.close() }; session?.release?.countDown() }
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
        assertPendingDraftRetained(text, 2, 6)
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
        assertPendingDraftRetained(nextText, nextText.length, nextText.length)
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
        assertPendingDraftRetained(text, 1, 3)
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
        // The replacement adopts the same retained ticket, not a fresh editable draft.
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
        assertPendingDraftRetained(text, 1, 3)
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
    // host method; see P09_UI_REGRESSION_SEAM.md.
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
            assertPendingDraftRetained(text, 2, 5)
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
            assertPendingDraftRetained(text, 1, 4)
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
    private data class SendFrame(val clock: Long, val bubble: androidx.compose.ui.geometry.Rect,
        val actions: androidx.compose.ui.geometry.Rect, val contrast: Float, val actionInk: Float)

    private fun currentSendState(): app.daiyu.android.ui.screens.chat.conversation.ChatScreenState {
        var result: app.daiyu.android.ui.screens.chat.conversation.ChatScreenState? = null
        instrumentation.runOnMainSync {
            result = MainActivity::class.java.declaredMethods.single {
                it.name == "getChatState" && it.parameterCount == 0
            }.apply { isAccessible = true }.invoke(activity) as app.daiyu.android.ui.screens.chat.conversation.ChatScreenState
        }
        return checkNotNull(result)
    }

    private fun sendFrame(key: String, advance: Long): SendFrame {
        ui.mainClock.advanceTimeBy(advance)
        ui.waitForIdle()
        val clock = ui.mainClock.currentTime
        val viewport = ui.onNodeWithTag("chat-messages")
        val origin = viewport.fetchSemanticsNode().boundsInRoot
        val bubble = ui.onNodeWithTag("message-user-bubble:$key", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val actions = ui.onNodeWithTag("message-user-actions:$key", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val bitmap = captureScreenNode(viewport, advanceAnimationClock = false)
        val pixels = bitmap.toPixelMap()
        assertEquals(clock, ui.mainClock.currentTime)
        val density = activity.resources.displayMetrics.density
        val x = (bubble.right - 24f * density - origin.left).toInt()
        val y = (bubble.top + 4f * density - origin.top).toInt()
        assertTrue("the actual bubble patch must be on screen", x > 0 && y > 0 && x < pixels.width && y < pixels.height)
        fun difference(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color) =
            kotlin.math.abs(a.red - b.red) + kotlin.math.abs(a.green - b.green) + kotlin.math.abs(a.blue - b.blue)
        val contrast = difference(pixels[x, y], pixels[2, y])
        val left = (actions.left - origin.left).toInt().coerceAtLeast(0)
        val top = (actions.top - origin.top).toInt().coerceAtLeast(0)
        val right = (actions.right - origin.left).toInt().coerceAtMost(pixels.width)
        val bottom = (actions.bottom - origin.top).toInt().coerceAtMost(pixels.height)
        assertTrue("action slot must stay above the composer", bottom > top && right > left)
        var actionInk = 0f
        for (py in top until bottom) for (px in left until right) actionInk += difference(pixels[px, py], pixels[2, py])
        val directory = java.io.File(activity.getExternalFilesDir(null), "visual-diagnostics/real-send-$name").apply { mkdirs() }
        java.io.File(directory, "$clock.png").outputStream().use {
            check(bitmap.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
        val frame = SendFrame(clock, bubble, actions, contrast, actionInk)
        java.io.File(directory, "frames.txt").appendText("$frame\n")
        return frame
    }

    fun testRealTouchSendPaintsBubbleAndActionIntermediateFramesBeforeReceipt() {
        mainThread { motionScale.scaleFactor = 1f }
        setDraft("真实发送动画回归")
        dismissIme()
        awaitImeFrame(node("chat-draft"), visible = false)
        ui.mainClock.autoAdvance = false
        try {
            node("chat-send").performTouchInput { click(center) }
            assertTrue(preparing.await(5, TimeUnit.SECONDS))
            val key = checkNotNull(currentSendState().submissionPreview).key
            val start = sendFrame(key, 48)
            val middle = sendFrame(key, 128)
            val end = sendFrame(key, 400)
            assertEquals(0, fixture.opens.get())
            assertTrue(fixture.database.conversations().isEmpty())
            assertTrue("real send must expand across drawn frames", end.bubble.width > start.bubble.width + 1f)
            assertTrue("real send must fade by visible pixel amounts", end.contrast > start.contrast + 2f / 255f)
            assertTrue("the intermediate bubble is neither initial nor final",
                middle.contrast > start.contrast + 1f / 255f && middle.contrast < end.contrast - 1f / 255f)
            assertTrue("actions must fade in, not appear at the terminal opacity", middle.actionInk > start.actionInk + 1f &&
                end.actionInk > middle.actionInk + 1f)
            assertEquals(start.actions, middle.actions)
            assertEquals(start.actions, end.actions)
        } finally {
            releasePreparation.countDown()
            ui.mainClock.autoAdvance = true
        }
    }

    fun testFastRealReceiptKeepsTheSamePaintedEntranceAcrossNewConversationPage() {
        mainThread { motionScale.scaleFactor = 1f }
        val session = fixture.session(holdSeconds = 120)
        setDraft("新会话快速回执入场")
        dismissIme()
        awaitImeFrame(node("chat-draft"), visible = false)
        ui.mainClock.autoAdvance = false
        try {
            node("chat-send").performTouchInput { click(center) }
            assertTrue(preparing.await(5, TimeUnit.SECONDS))
            val preview = checkNotNull(currentSendState().submissionPreview)
            val entrance = checkNotNull(currentSendState().userMessageEntrance)
            val before = sendFrame(preview.key, 48)
            releasePreparation.countDown()
            assertTrue(session.entered.await(5, TimeUnit.SECONDS))
            ui.waitUntil(5_000) { currentSendState().turns.any { it is app.daiyu.android.ui.screens.chat.conversation.ChatTurnUi.User } }
            val user = currentSendState().turns.filterIsInstance<app.daiyu.android.ui.screens.chat.conversation.ChatTurnUi.User>().single()
            assertSame("accepted page must retain the same send entrance", entrance, currentSendState().userMessageEntrance)
            val after = sendFrame(user.listKey, 96)
            val end = sendFrame(user.listKey, 400)
            assertNotNull(currentSendState().conversationKey)
            assertTrue("page handoff must not reset the animated extent", after.bubble.width >= before.bubble.width - .75f)
            assertTrue("the newly committed page must still contain painted intermediate frames", end.contrast > after.contrast + 1f / 255f)
            assertTrue(after.contrast >= before.contrast - 1f / 255f)
            ui.onAllNodesWithTag("message-user-bubble:${preview.key}", useUnmergedTree = true).assertCountEquals(0)
            assertEquals(1, fixture.opens.get())
            assertUserText(checkNotNull(fixture.controller.active).conversationId, "新会话快速回执入场")
        } finally {
            releasePreparation.countDown()
            session.release.countDown()
            ui.mainClock.autoAdvance = true
        }
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    fun testRealSendAutomaticallyClosesImeWithoutRebuildingOrJumpingTheActionButton() {
        mainThread { motionScale.scaleFactor = 1f }
        val keys = listOf("animator_duration_scale", "window_animation_scale", "transition_animation_scale")
        val previous = keys.associateWith { android.provider.Settings.Global.getString(activity.contentResolver, it) }
        fun setAnimation(key: String, value: String?) {
            check(key in keys)
            check(value == null || value.toFloatOrNull() != null)
            val command = if (value == null) "settings delete global $key" else "settings put global $key $value"
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
                .use { it.readBytes() }
        }
        data class NativeFrame(val nanos: Long, val ime: Int, val attached: Boolean, val nativeDraw: Boolean,
            val rowBottom: Float, val rightInset: Float, val topInset: Float)
        val frames = mutableListOf<NativeFrame>()
        var observedView: android.view.View? = null
        var listener: android.view.ViewTreeObserver.OnPreDrawListener? = null
        try {
            keys.forEach {
                setAnimation(it, "1")
                assertEquals("native animations must actually be enabled", 1f,
                    android.provider.Settings.Global.getFloat(activity.contentResolver, it), .001f)
            }
            setDraft("输入法与发送联动\n第二行草稿\n第三行草稿\n第四行草稿")
            node("chat-draft").performClick()
            awaitImeFrame(node("chat-draft"), visible = true)
            val row = node("chat-composer-bottom").fetchSemanticsNode()
            val button = node("chat-send").fetchSemanticsNode()
            val view = (row.root as androidx.compose.ui.platform.ViewRootForTest).view
            observedView = view
            fun record(nativeDraw: Boolean) {
                val attached = row.layoutInfo.isPlaced && button.layoutInfo.isPlaced &&
                    row.layoutInfo.coordinates.isAttached && button.layoutInfo.coordinates.isAttached
                val ime = androidx.core.view.ViewCompat.getRootWindowInsets(view)
                    ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom ?: -1
                val rowPosition = if (attached) row.positionOnScreen else androidx.compose.ui.geometry.Offset.Zero
                val buttonPosition = if (attached) button.positionOnScreen else androidx.compose.ui.geometry.Offset.Zero
                frames += NativeFrame(System.nanoTime(), ime, attached, nativeDraw, rowPosition.y + row.size.height,
                    rowPosition.x + row.size.width - buttonPosition.x - button.size.width, buttonPosition.y - rowPosition.y)
            }
            val recorder = android.view.ViewTreeObserver.OnPreDrawListener { record(true); true }
            listener = recorder
            instrumentation.runOnMainSync { view.viewTreeObserver.addOnPreDrawListener(recorder); record(false) }
            ui.mainClock.autoAdvance = false
            node("chat-send").performTouchInput { click(center) }
            assertTrue(preparing.await(5, TimeUnit.SECONDS))
            val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
            repeat(40) {
                assertTrue("native recording must finish before the preparation gate expires",
                    android.os.SystemClock.elapsedRealtime() < deadline)
                ui.mainClock.advanceTimeByFrame()
                ui.waitForIdle()
                val drawn = CountDownLatch(1)
                instrumentation.runOnMainSync {
                    android.view.Choreographer.getInstance().postFrameCallback { drawn.countDown() }
                    view.invalidate()
                }
                val remaining = deadline - android.os.SystemClock.elapsedRealtime()
                assertTrue("native recording exceeded its total budget", remaining > 0)
                assertTrue("native frame must be delivered", drawn.await(minOf(2_000L, remaining), TimeUnit.MILLISECONDS))
            }
            awaitImeFrame(node("chat-draft"), visible = false)
            instrumentation.runOnMainSync { view.viewTreeObserver.removeOnPreDrawListener(recorder); record(false) }
            listener = null
            assertTrue("must record the visible native keyboard", frames.first().ime > 0)
            assertEquals("send itself must hide the keyboard", 0, frames.last().ime)
            assertTrue("keep the primary button instance through send preparation", frames.all { it.attached })
            val first = frames.first()
            val nativeFrames = frames.filter { it.nativeDraw }
            assertTrue("endpoint snapshots cannot substitute for actual native draws", nativeFrames.size >= 5)
            if (android.os.Build.VERSION.SDK_INT >= 30) assertTrue(
                "animated IME travel must have an actual intermediate drawn action-row position",
                nativeFrames.any { it.rowBottom > first.rowBottom + 2f && it.rowBottom < frames.last().rowBottom - 2f })
            frames.forEach {
                assertEquals("IME travel must not move the primary button within its action slot", first.rightInset, it.rightInset, 1.5f)
                assertEquals("editor collapse must not jump the primary button within its action slot", first.topInset, it.topInset, 1.5f)
            }
            assertTrue("the action row must travel down as the keyboard closes", frames.last().rowBottom > first.rowBottom + 24f)
            val key = checkNotNull(currentSendState().submissionPreview).key
            ui.onNodeWithTag("message-user-bubble:$key", useUnmergedTree = true).assertIsDisplayed()
            val directory = java.io.File(activity.getExternalFilesDir(null), "visual-diagnostics/real-send-$name").apply { mkdirs() }
            java.io.File(directory, "native-frames.txt").writeText(frames.joinToString("\n"))
            sendFrame(key, 16)
        } finally {
            val view = observedView
            val observer = listener
            if (view != null && observer != null) instrumentation.runOnMainSync {
                if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnPreDrawListener(observer)
            }
            releasePreparation.countDown()
            ui.mainClock.autoAdvance = true
            previous.forEach { (key, value) ->
                setAnimation(key, value)
                assertEquals("restore native animation setting $key", value,
                    android.provider.Settings.Global.getString(activity.contentResolver, key))
            }
        }
    }

}
