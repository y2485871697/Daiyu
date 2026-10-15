package app.daiyu.android

import android.content.Intent
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.os.SystemClock
import android.widget.Button
import android.widget.EditText
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.ExecutionPhase
import java.util.concurrent.TimeUnit

@Suppress("DEPRECATION")
class RunControlActivityTest:InstrumentationTestCase() {
    private lateinit var fixture:RunControlFixture
    private lateinit var original:ChatController
    private var activity:MainActivity?=null
    private fun views(view:View):List<View> = listOf(view)+if(view is ViewGroup)
        (0 until view.childCount).flatMap {views(view.getChildAt(it))} else emptyList()
    private fun input()=views(checkNotNull(activity).window.decorView).filterIsInstance<EditText>().single()
    private fun button(label:String)=views(checkNotNull(activity).window.decorView).filterIsInstance<Button>().single {it.text.toString()==label}
    override fun setUp() {
        super.setUp()
        fixture=RunControlFixture(instrumentation.targetContext)
        awaitControl("controller ready") {fixture.controller.ready}
        controlMain {
            val app=instrumentation.targetContext.applicationContext as ChatApplication
            original=app.controller;app.controller=fixture.controller
        }
        activity=instrumentation.startActivitySync(Intent(instrumentation.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        instrumentation.waitForIdleSync()
    }
    override fun tearDown() {
        try {
            controlMain {activity?.finish()}
            instrumentation.waitForIdleSync()
            controlMain {(instrumentation.targetContext.applicationContext as ChatApplication).controller=original}
            fixture.close()
        } finally {super.tearDown()}
    }
    private fun send():Message {
        var owner:Message?=null
        controlMain {
            input().setText("initial user");button("发送").performClick()
            owner=checkNotNull(fixture.controller.active)
        }
        return checkNotNull(owner)
    }
    private fun touch(view:View,action:Int,started:Long) {
        val event=MotionEvent.obtain(started,SystemClock.uptimeMillis(),action,view.width/2f,view.height/2f,0)
        try {view.dispatchTouchEvent(event)} finally {event.recycle()}
    }
    private fun tap(view:View) {
        val started=SystemClock.uptimeMillis()
        touch(view,MotionEvent.ACTION_DOWN,started);touch(view,MotionEvent.ACTION_UP,started)
    }
    fun testShortClickPausesRecreationRetainsDraftAndContinueCompletesSameOwner() {
        val first=fixture.session("cancelled");val second=fixture.session("answer")
        val owner=send();assertTrue(first.entered.await(5,TimeUnit.SECONDS))
        awaitControl("pause button enabled") {button("暂停").isEnabled}
        controlMain {input().setText("unsent draft");tap(button("暂停"))}
        awaitControl("worker paused") {fixture.controller.runState(owner)?.phase==ExecutionPhase.PAUSED}
        controlMain {
            assertEquals("unsent draft",input().text.toString());assertEquals(RunStatus.RUNNING,fixture.controller.active!!.status)
            assertTrue(button("继续").isEnabled);assertEquals(1,fixture.opens.get())
        }
        val monitor=instrumentation.addMonitor(MainActivity::class.java.name,null,false)
        try {
            controlMain {checkNotNull(activity).recreate()}
            activity=instrumentation.waitForMonitorWithTimeout(monitor,5000) as? MainActivity
            assertNotNull("recreated activity",activity);instrumentation.waitForIdleSync()
        } finally {instrumentation.removeMonitor(monitor)}
        controlMain {
            assertEquals("unsent draft",input().text.toString());assertTrue(button("继续").isEnabled)
            assertEquals(owner.id,fixture.controller.active!!.id);assertEquals(1,fixture.opens.get())
            tap(button("继续"))
        }
        assertTrue(second.entered.await(5,TimeUnit.SECONDS));second.release.countDown()
        awaitControl("same run completes") {fixture.controller.active==null}
        controlMain {assertEquals("unsent draft",input().text.toString());assertFalse(button("暂停").isEnabled)}
        val saved=fixture.database.messages(owner.conversationId).last().second
        assertEquals(owner.id,saved.id);assertEquals(owner.runId,saved.runId);assertEquals(RunStatus.COMPLETED,saved.status)
        assertEquals(2,fixture.opens.get());assertEquals(2,fixture.database.messages(owner.conversationId).size)
    }
    fun testOtherConversationCannotControlOwnerAndLongClickStopsWithoutPause() {
        val session=fixture.session();val owner=send();assertTrue(session.entered.await(5,TimeUnit.SECONDS))
        val ownedConversation=fixture.database.conversations().single();val other=fixture.database.create()
        awaitControl("owner control available") {button("暂停").isEnabled}
        var target:Button?=null
        var started=0L
        controlMain {
            checkNotNull(activity).select(other);input().setText("other draft")
            assertFalse(button("暂停").isEnabled);button("暂停").performClick();button("暂停").performLongClick()
            assertFalse(fixture.controller.runState(owner)!!.pauseRequested);assertEquals(1L,session.cancelled.count)
            checkNotNull(activity).select(ownedConversation);input().setText("owner draft")
            target=button("暂停");assertTrue(checkNotNull(target).isEnabled)
            started=SystemClock.uptimeMillis();touch(checkNotNull(target),MotionEvent.ACTION_DOWN,started)
        }
        awaitControl("real long gesture stops without short pause") {fixture.controller.active==null}
        controlMain {touch(checkNotNull(target),MotionEvent.ACTION_UP,started)}
        instrumentation.waitForIdleSync()
        val saved=fixture.database.messages(owner.conversationId).last().second
        assertEquals(RunStatus.STOPPED,saved.status);assertEquals(1,fixture.opens.get())
        controlMain {assertEquals("owner draft",input().text.toString());assertFalse(button("暂停").isEnabled)}
        assertEquals("other draft",fixture.database.conversations().single {it.id==other.id}.draft)
    }
    fun testGestureOwnerCannotRetargetNextRunBeforeRelease() {
        val first=fixture.session();val old=send();assertTrue(first.entered.await(5,TimeUnit.SECONDS))
        awaitControl("first owner rendered") {button("暂停").isEnabled}
        var target:Button?=null
        var started=0L
        controlMain {
            target=button("暂停");started=SystemClock.uptimeMillis()
            touch(checkNotNull(target),MotionEvent.ACTION_DOWN,started)
        }
        first.release.countDown()
        awaitControl("old owner completed") {fixture.controller.active==null}
        val second=fixture.session();val conversation=fixture.database.conversations().single()
        controlMain {assertNotNull(fixture.controller.send(conversation,"next user"))}
        val next=checkNotNull(fixture.controller.active);assertTrue(second.entered.await(5,TimeUnit.SECONDS))
        awaitControl("next owner rendered") {button("暂停").isEnabled}
        controlMain {
            checkNotNull(target).performLongClick()
            assertFalse(fixture.controller.runState(next)!!.pauseRequested)
            assertEquals(1L,second.cancelled.count)
            touch(checkNotNull(target),MotionEvent.ACTION_UP,started)
        }
        instrumentation.waitForIdleSync()
        controlMain {
            assertFalse(fixture.controller.runState(next)!!.pauseRequested)
            assertEquals(1L,second.cancelled.count)
        }
        second.release.countDown();awaitControl("next run unaffected") {fixture.controller.active==null}
        assertEquals(RunStatus.COMPLETED,fixture.database.messages(old.conversationId).last().second.status)
        assertEquals(2,fixture.opens.get())
    }

}
