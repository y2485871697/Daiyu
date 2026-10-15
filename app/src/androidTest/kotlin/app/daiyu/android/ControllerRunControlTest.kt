package app.daiyu.android

import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.ExecutionPhase
import java.util.concurrent.TimeUnit

@Suppress("DEPRECATION")
class ControllerRunControlTest:InstrumentationTestCase() {
    private val fixtures=mutableListOf<RunControlFixture>()
    private fun fresh()=RunControlFixture(instrumentation.targetContext).also {
        fixtures.add(it);awaitControl("recovery ready") {it.controller.ready}
    }
    private fun start(f:RunControlFixture):Message {
        controlMain {assertNotNull(f.controller.send(null,"initial user"))}
        return checkNotNull(f.controller.active)
    }
    override fun tearDown() {
        try {fixtures.forEach {it.close()}} finally {super.tearDown()}
    }
    fun testPauseWaitsForExplicitResumeKeepsOwnerAndRejectsLateEvents() {
        val f=fresh();val first=f.session("cancelled-body");val second=f.session("resumed-body")
        val owner=start(f);assertTrue(first.entered.await(5,TimeUnit.SECONDS))
        f.database.draft(owner.conversationId,"unsent draft")
        controlMain {assertTrue(f.controller.pause(owner));assertTrue(f.controller.runState(owner)!!.pauseRequested)}
        assertTrue(first.cancelled.await(5,TimeUnit.SECONDS))
        awaitControl("paused boundary") {f.controller.runState(owner)?.phase==ExecutionPhase.PAUSED}
        first.sink.get()!!.invoke(ModelEvent.TextDelta("late","must-not-appear"))
        first.sink.get()!!.invoke(ModelEvent.Usage(999,999))
        controlMain {
            assertEquals(owner.id,f.controller.active!!.id);assertEquals(RunStatus.RUNNING,f.controller.active!!.status)
            assertEquals(1,f.opens.get());assertEquals("unsent draft",f.database.conversations().single().draft)
            assertTrue(f.controller.active!!.blocks.isEmpty());assertTrue(f.controller.resume(owner))
            assertFalse(f.controller.resume(owner))
        }
        assertTrue(second.entered.await(5,TimeUnit.SECONDS));second.release.countDown()
        awaitControl("resumed run completed") {f.controller.active==null}
        val saved=f.database.messages(owner.conversationId).last().second
        assertEquals(owner.id,saved.id);assertEquals(owner.runId,saved.runId);assertEquals(RunStatus.COMPLETED,saved.status)
        assertEquals("resumed-body",saved.blocks.filterIsInstance<MessageBlock.Text>().joinToString("") {it.text})
        assertEquals(2,f.opens.get());assertEquals(2,f.database.messages(owner.conversationId).size)
        assertEquals("unsent draft",f.database.conversations().single().draft)
    }
    fun testOwnerMismatchCannotPauseResumeOrStopCurrentRun() {
        val f=fresh();val first=f.session();val second=f.session();val owner=start(f);assertTrue(first.entered.await(5,TimeUnit.SECONDS))
        val wrong=listOf(owner.copy(id=MessageId("other")),owner.copy(runId=RunId("other")),
            owner.copy(conversationId=ConversationId("other")),owner.copy(role=Message.USER_ROLE))
        controlMain {wrong.forEach {
            assertNull(f.controller.runState(it));assertFalse(f.controller.pause(it));assertFalse(f.controller.resume(it));assertFalse(f.controller.stop(it))
        };assertFalse(f.controller.runState(owner)!!.pauseRequested)}
        assertEquals(1L,first.cancelled.count)
        controlMain {assertTrue(f.controller.pause(owner))}
        awaitControl("legitimate pause before wrong resume") {f.controller.runState(owner)?.phase==ExecutionPhase.PAUSED}
        controlMain {
            wrong.forEach {assertFalse(f.controller.resume(it))}
            assertTrue(f.controller.runState(owner)!!.pauseRequested);assertEquals(1,f.opens.get())
            assertTrue(f.controller.resume(owner))
        }
        assertTrue(second.entered.await(5,TimeUnit.SECONDS));second.release.countDown()
        awaitControl("unaffected run completes") {f.controller.active==null}
    }
    fun testStopWhilePausedPersistsStoppedWithoutReopening() {
        val f=fresh();val first=f.session();val owner=start(f);assertTrue(first.entered.await(5,TimeUnit.SECONDS))
        controlMain {assertTrue(f.controller.pause(owner))}
        awaitControl("paused before stop") {f.controller.runState(owner)?.phase==ExecutionPhase.PAUSED}
        controlMain {assertTrue(f.controller.stop(owner));assertFalse(f.controller.resume(owner))}
        awaitControl("stop finishes paused worker") {f.controller.active==null}
        assertEquals(RunStatus.STOPPED,f.database.messages(owner.conversationId).last().second.status)
        assertEquals(1,f.opens.get())
    }
    fun testStaleOwnerCannotControlNextRunInSameConversation() {
        val f=fresh();val first=f.session();val stale=start(f);first.release.countDown()
        awaitControl("first terminal") {f.controller.active==null}
        val next=f.session();val resumed=f.session();val conversation=f.database.conversations().single()
        controlMain {assertNotNull(f.controller.send(conversation,"next user"))}
        val owner=checkNotNull(f.controller.active);assertTrue(next.entered.await(5,TimeUnit.SECONDS))
        controlMain {
            assertNull(f.controller.runState(stale));assertFalse(f.controller.pause(stale));assertFalse(f.controller.resume(stale));assertFalse(f.controller.stop(stale))
            assertFalse(f.controller.runState(owner)!!.pauseRequested)
        }
        assertEquals(1L,next.cancelled.count)
        controlMain {assertTrue(f.controller.pause(owner))}
        awaitControl("next run paused") {f.controller.runState(owner)?.phase==ExecutionPhase.PAUSED}
        controlMain {
            assertFalse(f.controller.resume(stale));assertTrue(f.controller.runState(owner)!!.pauseRequested)
            assertEquals(2,f.opens.get());assertTrue(f.controller.resume(owner))
        }
        assertTrue(resumed.entered.await(5,TimeUnit.SECONDS));resumed.release.countDown()
        awaitControl("next owner unaffected") {f.controller.active==null}
        assertEquals(3,f.opens.get());assertEquals(4,f.database.messages(conversation.id).size)
        assertEquals(0L,first.entered.count)
    }
    fun testControlsRejectAfterTerminalWithoutTouchingRetainedDraft() {
        val f=fresh();val first=f.session();val owner=start(f);first.release.countDown()
        awaitControl("terminal") {f.controller.active==null}
        f.database.draft(owner.conversationId,"retained")
        controlMain {
            assertNull(f.controller.runState(owner));assertFalse(f.controller.pause(owner));assertFalse(f.controller.resume(owner));assertFalse(f.controller.stop(owner))
        }
        assertEquals("retained",f.database.conversations().single().draft);assertEquals(1,f.opens.get())
    }
    fun testCloseWakesPausedWorkerAndRejectsDelayedControls() {
        val f=fresh();val first=f.session();val owner=start(f);assertTrue(first.entered.await(5,TimeUnit.SECONDS))
        controlMain {assertTrue(f.controller.pause(owner))}
        awaitControl("paused before close") {f.controller.runState(owner)?.phase==ExecutionPhase.PAUSED}
        controlMain {
            f.controller.close()
            assertNull(f.controller.runState(owner));assertFalse(f.controller.pause(owner));assertFalse(f.controller.resume(owner));assertFalse(f.controller.stop(owner))
        }
        val reopened=app.daiyu.android.storage.ConversationDatabase(f.application)
        try {
            assertEquals(RunStatus.STOPPED,reopened.messages(owner.conversationId).last().second.status)
            assertEquals(2,reopened.messages(owner.conversationId).size)
        } finally {reopened.close()}
        assertEquals(1,f.opens.get())
    }

}
