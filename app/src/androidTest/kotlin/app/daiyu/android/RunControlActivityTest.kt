package app.daiyu.android

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.ExecutionPhase
import java.util.concurrent.TimeUnit

class RunControlActivityTest : WiredActivityTestCase() {
    private fun send(): Message {
        setDraft("initial user"); node("chat-send").performClick()
        awaitTextSendResult(activity)
        assertDraft("")
        return checkNotNull(fixture.controller.active)
    }
    private fun awaitAppendEnabled() {
        ui.waitUntil(5_000) {
            ui.onAllNodes(hasTestTag("chat-send") and hasContentDescription("追加消息") and isEnabled())
                .fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1
        }
    }
    fun testShortClickPausesRecreationRetainsDraftAndContinueCompletesSameOwner() {
        val first = fixture.session("cancelled"); val second = fixture.session("answer")
        val owner = send(); assertTrue(first.entered.await(5, TimeUnit.SECONDS))
        node("chat-control").performClick()
        setDraft("unsent draft")
        awaitControl("worker paused") { fixture.controller.runState(owner)?.phase == ExecutionPhase.PAUSED }
        ui.waitForIdle(); assertDraft("unsent draft")
        assertEquals(RunStatus.RUNNING, fixture.controller.active!!.status)
        node("chat-control").assertDoesNotExist()
        awaitAppendEnabled()
        node("chat-send").assertIsEnabled().assertContentDescriptionEquals("追加消息")
        assertEquals(1, fixture.opens.get())
        activity = recreateActivity(activity)
        assertDraft("unsent draft")
        node("chat-control").assertDoesNotExist()
        awaitAppendEnabled()
        node("chat-send").assertIsEnabled().assertContentDescriptionEquals("追加消息")
        assertEquals(owner.id, fixture.controller.active!!.id); assertEquals(1, fixture.opens.get())
        setDraft("")
        node("chat-control").assertIsEnabled().assertContentDescriptionEquals("继续").performClick()
        assertTrue(second.entered.await(5, TimeUnit.SECONDS))
        setDraft("unsent draft")
        second.release.countDown()
        awaitControl("same run completes") { fixture.controller.active == null }
        ui.waitForIdle(); assertDraft("unsent draft"); node("chat-control").assertDoesNotExist()
        val saved = fixture.database.messages(owner.conversationId).last().second
        assertEquals(owner.id, saved.id); assertEquals(owner.runId, saved.runId); assertEquals(RunStatus.COMPLETED, saved.status)
        assertEquals(2, fixture.opens.get()); assertEquals(2, fixture.database.messages(owner.conversationId).size)
    }
    private fun streamingAppendAcrossRecreation(editAfterRecreate: Boolean) {
        val first = fixture.session("cancelled", releaseOnCancel = false)
        val second = fixture.session("after append")
        val owner = send(); assertTrue(first.entered.await(5, TimeUnit.SECONDS))
        setDraft("additional instruction")
        awaitAppendEnabled(); node("chat-send").performClick()
        assertTrue(first.cancelled.await(5, TimeUnit.SECONDS))
        val pendingId = checkNotNull(fixture.controller.supplementDraft.pending).messageId
        assertDraft("additional instruction")
        activity = recreateActivity(activity)
        assertDraft("additional instruction")
        assertEquals(pendingId, fixture.controller.supplementDraft.pending?.messageId)
        if (editAfterRecreate) setDraft("newer draft")
        first.release.countDown()
        assertTrue(second.entered.await(5, TimeUnit.SECONDS))
        awaitControl("retained supplement receipt consumed") { fixture.controller.supplementDraft.pending == null }
        ui.waitForIdle(); assertDraft(if (editAfterRecreate) "newer draft" else "")
        assertEquals(1, fixture.database.messages(owner.conversationId).count { it.second.id == pendingId })
        assertEquals(owner.runId, fixture.controller.active!!.runId)
        second.release.countDown()
        awaitControl("appended owner completes") { fixture.controller.active == null }
        assertEquals(2, fixture.opens.get())
    }
    fun testStreamingAppendReceiptSurvivesRecreationAndClearsOriginalDraftOnce() =
        streamingAppendAcrossRecreation(editAfterRecreate = false)
    fun testStreamingAppendReceiptAfterRecreationPreservesNewEdits() =
        streamingAppendAcrossRecreation(editAfterRecreate = true)

    fun testOtherConversationCannotControlOwnerAndLongClickStopsWithoutPause() {
        val session = fixture.session(holdSeconds = 120); val owner = send(); assertTrue(session.entered.await(5, TimeUnit.SECONDS))
        val ownedConversation = fixture.database.conversations().single(); val other = fixture.database.create()
        val staleTap = retainedClick(activity, "chat-control")
        val staleLong = retainedControls(activity).onPrimaryLongPress
        mainThread { activity.select(other) }; setDraft("other draft"); node("chat-control").assertDoesNotExist()
        mainThread { staleTap(); staleLong() }
        assertFalse(fixture.controller.runState(owner)!!.pauseRequested); assertEquals(1L, session.cancelled.count)
        mainThread { activity.select(ownedConversation) }; setDraft("")
        mainThread {
            staleTap(); staleLong()
            val active = checkNotNull(fixture.controller.active)
            assertEquals(owner.id, active.id); assertEquals(owner.runId, active.runId)
            assertEquals(owner.conversationId, active.conversationId)
            assertEquals(RunStatus.RUNNING, active.status)
            assertFalse(checkNotNull(fixture.controller.runState(owner)).pauseRequested)
            assertEquals(1L, session.cancelled.count)
        }
        node("chat-control").performTouchInput { longClick() }
        assertTrue("long press cancels owner session", session.cancelled.await(5, TimeUnit.SECONDS))
        awaitControl("long gesture stops without short pause") { fixture.controller.active == null }
        val saved = fixture.database.messages(owner.conversationId).last().second
        assertEquals(RunStatus.STOPPED, saved.status); assertEquals(1, fixture.opens.get())
        assertEquals(0L, session.cancelled.count)
        assertDraft(""); node("chat-control").assertDoesNotExist()
        assertEquals("other draft", fixture.database.conversations().single { it.id == other.id }.draft)
    }
    fun testGestureOwnerCannotRetargetNextRunBeforeRelease() {
        val first = fixture.session(); val old = send(); assertTrue(first.entered.await(5, TimeUnit.SECONDS))
        val target = node("chat-control")
        val oldLong = retainedControls(activity).onPrimaryLongPress
        target.performTouchInput { down(center) }
        first.release.countDown(); awaitControl("old owner completed") { fixture.controller.active == null }
        val second = fixture.session(); val conversation = fixture.database.conversations().single()
        assertNotNull(fixture.controller.send(conversation, "next user"))
        val next = checkNotNull(fixture.controller.active); assertTrue(second.entered.await(5, TimeUnit.SECONDS))
        ui.waitForIdle()
        mainThread { oldLong() }
        assertFalse(fixture.controller.runState(next)!!.pauseRequested); assertEquals(1L, second.cancelled.count)
        node("chat-control").performTouchInput { up() }
        assertFalse(fixture.controller.runState(next)!!.pauseRequested); assertEquals(1L, second.cancelled.count)
        second.release.countDown(); awaitControl("next run unaffected") { fixture.controller.active == null }
        assertEquals(RunStatus.COMPLETED, fixture.database.messages(old.conversationId).last().second.status)
        assertEquals(2, fixture.opens.get())
    }
}
