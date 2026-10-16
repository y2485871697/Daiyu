package app.daiyu.android

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.ExecutionPhase
import java.util.concurrent.TimeUnit

class RunControlActivityTest : WiredActivityTestCase() {
    private fun send(): Message {
        setDraft("initial user"); node("chat-send").performClick()
        return checkNotNull(fixture.controller.active)
    }
    fun testShortClickPausesRecreationRetainsDraftAndContinueCompletesSameOwner() {
        val first = fixture.session("cancelled"); val second = fixture.session("answer")
        val owner = send(); assertTrue(first.entered.await(5, TimeUnit.SECONDS))
        setDraft("unsent draft"); node("chat-control").performClick()
        awaitControl("worker paused") { fixture.controller.runState(owner)?.phase == ExecutionPhase.PAUSED }
        ui.waitForIdle(); assertDraft("unsent draft")
        assertEquals(RunStatus.RUNNING, fixture.controller.active!!.status)
        node("chat-control").assertContentDescriptionEquals("继续"); assertEquals(1, fixture.opens.get())
        activity = recreateActivity(activity)
        assertDraft("unsent draft"); node("chat-control").assertIsEnabled().assertContentDescriptionEquals("继续")
        assertEquals(owner.id, fixture.controller.active!!.id); assertEquals(1, fixture.opens.get())
        node("chat-control").performClick()
        assertTrue(second.entered.await(5, TimeUnit.SECONDS)); second.release.countDown()
        awaitControl("same run completes") { fixture.controller.active == null }
        ui.waitForIdle(); assertDraft("unsent draft"); node("chat-control").assertDoesNotExist()
        val saved = fixture.database.messages(owner.conversationId).last().second
        assertEquals(owner.id, saved.id); assertEquals(owner.runId, saved.runId); assertEquals(RunStatus.COMPLETED, saved.status)
        assertEquals(2, fixture.opens.get()); assertEquals(2, fixture.database.messages(owner.conversationId).size)
    }
    fun testOtherConversationCannotControlOwnerAndLongClickStopsWithoutPause() {
        val session = fixture.session(); val owner = send(); assertTrue(session.entered.await(5, TimeUnit.SECONDS))
        val ownedConversation = fixture.database.conversations().single(); val other = fixture.database.create()
        val staleTap = retainedClick(activity, "chat-control")
        val staleLong = retainedControls(activity).onPrimaryLongPress
        mainThread { activity.select(other) }; setDraft("other draft"); node("chat-control").assertDoesNotExist()
        mainThread { staleTap(); staleLong() }
        assertFalse(fixture.controller.runState(owner)!!.pauseRequested); assertEquals(1L, session.cancelled.count)
        mainThread { activity.select(ownedConversation) }; setDraft("owner draft")
        node("chat-control").performTouchInput { longClick() }
        awaitControl("long gesture stops without short pause") { fixture.controller.active == null }
        val saved = fixture.database.messages(owner.conversationId).last().second
        assertEquals(RunStatus.STOPPED, saved.status); assertEquals(1, fixture.opens.get())
        assertDraft("owner draft"); node("chat-control").assertDoesNotExist()
        assertEquals("other draft", fixture.database.conversations().single { it.id == other.id }.draft)
    }
    fun testGestureOwnerCannotRetargetNextRunBeforeRelease() {
        val first = fixture.session(); val old = send(); assertTrue(first.entered.await(5, TimeUnit.SECONDS))
        val target = node("chat-control")
        val oldLong = retainedControls(activity).onPrimaryLongPress
        target.performTouchInput { down(center) }
        first.release.countDown(); awaitControl("old owner completed") { fixture.controller.active == null }
        val second = fixture.session(); val conversation = fixture.database.conversations().single()
        mainThread { assertNotNull(fixture.controller.send(conversation, "next user")) }
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
