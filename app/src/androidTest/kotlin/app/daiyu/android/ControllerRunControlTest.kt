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
        assertNotNull(f.controller.send(null,"initial user"))
        return checkNotNull(f.controller.active)
    }
    override fun tearDown() {
        try {fixtures.forEach {it.close()}} finally {super.tearDown()}
    }
    private fun targetField(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).let {
        it.isAccessible = true; it.get(target)
    }
    private fun targetContext(f: RunControlFixture, message: Message): app.daiyu.android.domain.tools.ToolExecutionContext {
        val catalog = checkNotNull(targetField(f.controller, "runTools"))
        val owner = targetField(catalog, "owner") as app.daiyu.android.domain.runtime.RunExecutionOwner
        val constructor = app.daiyu.android.domain.tools.ToolExecutionContext::class.java.declaredConstructors.single()
            .apply { isAccessible = true }
        return constructor.newInstance(owner, ToolInvocation(ToolCallId("target-notify"), "terminal_exec", "{}"),
            app.daiyu.android.domain.tools.TaskAuthorization("test", message.runId.value), { false }) as
            app.daiyu.android.domain.tools.ToolExecutionContext
    }
    private fun targetNotificationWindow(f: RunControlFixture) {
        val passed = java.util.concurrent.CountDownLatch(1)
        controlMain { android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ passed.countDown() }, 80L) }
        assertTrue(passed.await(30, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
        controlMain { assertEquals(false, targetField(f.controller, "changedDispatchPending")) }
    }
    private fun publishTarget(context: app.daiyu.android.domain.tools.ToolExecutionContext,
        target: app.daiyu.android.domain.runtime.TerminalExecutionTarget) {
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val worker = Thread { try { context.reportTerminalTarget(target) } catch (caught: Throwable) { failure.set(caught) } }
            .apply { isDaemon = true; start() }
        worker.join(30_000)
        assertFalse("target callback must return", worker.isAlive)
        failure.get()?.let { throw it }
    }
    fun testTerminalTargetNotifiesWithoutAnotherModelEvent() {
        val f = fresh(); val held = f.session(holdSeconds = 120, releaseOnCancel = false); val message = start(f)
        assertTrue(held.entered.await(30, TimeUnit.SECONDS))
        val context = targetContext(f, message)
        val target = app.daiyu.android.domain.runtime.TerminalExecutionTarget("android", 10444)
        targetNotificationWindow(f)
        val observed = java.util.concurrent.CountDownLatch(1)
        val listener: () -> Unit = {
            if (f.controller.runState(message)?.terminalTargets?.get(context.invocation) == target) observed.countDown()
        }
        controlMain { f.controller.observe(listener) }
        try {
            publishTarget(context, target)
            assertTrue("new target must notify after initial dispatch drained", observed.await(30, TimeUnit.SECONDS))
            controlMain {
                assertEquals(RunStatus.RUNNING, f.controller.active!!.status)
                assertEquals(1L, held.release.count)
                assertEquals(1, f.opens.get())
            }
        } finally { controlMain { f.controller.unobserve(listener) }; held.release.countDown() }
    }
    fun testFencedTerminalTargetCannotNotifyCurrentObservers() {
        val f = fresh(); val held = f.session(holdSeconds = 120, releaseOnCancel = false); val message = start(f)
        assertTrue(held.entered.await(30, TimeUnit.SECONDS))
        val context = targetContext(f, message)
        controlMain { assertTrue(f.controller.stop(message)) }
        assertTrue(held.cancelled.await(30, TimeUnit.SECONDS))
        targetNotificationWindow(f)
        val notifications = java.util.concurrent.atomic.AtomicInteger()
        val listener: () -> Unit = { notifications.incrementAndGet() }
        controlMain { f.controller.observe(listener) }
        try {
            publishTarget(context, app.daiyu.android.domain.runtime.TerminalExecutionTarget("android", 10444))
            targetNotificationWindow(f)
            assertEquals(0, notifications.get())
            assertEquals(1L, held.release.count)
        } finally { controlMain { f.controller.unobserve(listener) }; held.release.countDown() }
    }
    fun testOldTerminalOwnerCannotNotifyOrLabelNextRun() {
        val f = fresh(); val first = f.session(holdSeconds = 120); val previous = start(f)
        assertTrue(first.entered.await(30, TimeUnit.SECONDS))
        val oldContext = targetContext(f, previous)
        first.release.countDown()
        awaitControl("first terminal before target isolation") { f.controller.active == null }
        val next = f.session(holdSeconds = 120, releaseOnCancel = false)
        assertNotNull(f.controller.send(f.database.conversations().single(), "next user"))
        val current = checkNotNull(f.controller.active)
        assertTrue(next.entered.await(30, TimeUnit.SECONDS))
        targetNotificationWindow(f)
        val notifications = java.util.concurrent.atomic.AtomicInteger()
        val listener: () -> Unit = { notifications.incrementAndGet() }
        controlMain { f.controller.observe(listener) }
        try {
            publishTarget(oldContext, app.daiyu.android.domain.runtime.TerminalExecutionTarget("android", 0))
            targetNotificationWindow(f)
            assertEquals(0, notifications.get())
            controlMain {
                assertTrue(f.controller.runState(current)!!.terminalTargets.isEmpty())
                assertEquals(current.id, f.controller.active!!.id)
                assertEquals(1L, next.release.count)
            }
        } finally { controlMain { f.controller.unobserve(listener) }; next.release.countDown() }
    }

    fun testWorkerPausedBoundaryNotifiesChangedWithoutAnotherModelEvent() {
        val f=fresh();val first=f.session(releaseOnCancel=false);val owner=start(f)
        assertTrue(first.entered.await(30,TimeUnit.SECONDS))
        val pausedObserved=java.util.concurrent.CountDownLatch(1)
        val listener:()->Unit={
            if(f.controller.runState(owner)?.let {it.pauseRequested && it.phase==ExecutionPhase.PAUSED}==true)
                pausedObserved.countDown()
        }
        try {
            controlMain {f.controller.observe(listener);assertTrue(f.controller.pause(owner))}
            assertTrue(first.cancelled.await(30,TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            controlMain {
                assertEquals(ExecutionPhase.STREAMING,f.controller.runState(owner)!!.phase)
                assertEquals(1L,pausedObserved.count)
            }
            first.release.countDown()
            assertTrue("PAUSED must reach changed observer",pausedObserved.await(30,TimeUnit.SECONDS))
            controlMain {
                assertEquals(owner.id,f.controller.active!!.id);assertEquals(owner.runId,f.controller.active!!.runId)
                assertEquals(RunStatus.RUNNING,f.controller.active!!.status)
                assertEquals(ExecutionPhase.PAUSED,f.controller.runState(owner)!!.phase);assertEquals(1,f.opens.get())
            }
        } finally {controlMain {f.controller.unobserve(listener)};first.release.countDown()}
    }
    fun testPauseWaitsForExplicitResumeKeepsOwnerAndRejectsLateEvents() {
        val f=fresh();val first=f.session("cancelled-body");val second=f.session("resumed-body")
        val owner=start(f);assertTrue(first.entered.await(30,TimeUnit.SECONDS))
        f.database.draft(owner.conversationId,"unsent draft")
        controlMain {assertTrue(f.controller.pause(owner));assertTrue(f.controller.runState(owner)!!.pauseRequested)}
        assertTrue(first.cancelled.await(30,TimeUnit.SECONDS))
        awaitControl("paused boundary") {f.controller.runState(owner)?.phase==ExecutionPhase.PAUSED}
        first.sink.get()!!.invoke(ModelEvent.TextDelta("late","must-not-appear"))
        first.sink.get()!!.invoke(ModelEvent.Usage(999,999))
        controlMain {
            assertEquals(owner.id,f.controller.active!!.id);assertEquals(RunStatus.RUNNING,f.controller.active!!.status)
            assertEquals(1,f.opens.get());assertEquals("unsent draft",f.database.conversations().single().draft)
            assertTrue(f.controller.active!!.blocks.isEmpty());assertTrue(f.controller.resume(owner))
            assertFalse(f.controller.resume(owner))
        }
        assertTrue(second.entered.await(30,TimeUnit.SECONDS));second.release.countDown()
        awaitControl("resumed run completed") {f.controller.active==null}
        val saved=f.database.messages(owner.conversationId).last().second
        assertEquals(owner.id,saved.id);assertEquals(owner.runId,saved.runId);assertEquals(RunStatus.COMPLETED,saved.status)
        assertEquals("resumed-body",saved.blocks.filterIsInstance<MessageBlock.Text>().joinToString("") {it.text})
        assertEquals(2,f.opens.get());assertEquals(2,f.database.messages(owner.conversationId).size)
        assertEquals("unsent draft",f.database.conversations().single().draft)
    }
    fun testOwnerMismatchCannotPauseResumeOrStopCurrentRun() {
        val f=fresh();val first=f.session();val second=f.session();val owner=start(f);assertTrue(first.entered.await(30,TimeUnit.SECONDS))
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
        assertTrue(second.entered.await(30,TimeUnit.SECONDS));second.release.countDown()
        awaitControl("unaffected run completes") {f.controller.active==null}
    }
    fun testStopWhilePausedPersistsStoppedWithoutReopening() {
        val f=fresh();val first=f.session();val owner=start(f);assertTrue(first.entered.await(30,TimeUnit.SECONDS))
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
        assertNotNull(f.controller.send(conversation,"next user"))
        val owner=checkNotNull(f.controller.active);assertTrue(next.entered.await(30,TimeUnit.SECONDS))
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
        assertTrue(resumed.entered.await(30,TimeUnit.SECONDS));resumed.release.countDown()
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
        val f=fresh();val first=f.session();val owner=start(f);assertTrue(first.entered.await(30,TimeUnit.SECONDS))
        controlMain {assertTrue(f.controller.pause(owner))}
        awaitControl("paused before close") {f.controller.runState(owner)?.phase==ExecutionPhase.PAUSED}
        controlMain {
            f.controller.close()
            assertNull(f.controller.runState(owner));assertFalse(f.controller.pause(owner));assertFalse(f.controller.resume(owner));assertFalse(f.controller.stop(owner))
        }
        f.controller.close() // Off-main: wait for the already-requested worker/storage drain.
        val reopened=app.daiyu.android.storage.ConversationDatabase(f.application)
        try {
            assertEquals(RunStatus.STOPPED,reopened.messages(owner.conversationId).last().second.status)
            assertEquals(2,reopened.messages(owner.conversationId).size)
        } finally {reopened.close()}
        assertEquals(1,f.opens.get())
    }


    fun testPausedDurableSupplementKeepsDraftAndResumesWithOneHistoryMessage() {
        val f=fresh();val first=f.session();val second=f.session("after supplement")
        val owner=start(f);assertTrue(first.entered.await(30,TimeUnit.SECONDS))
        f.database.draft(owner.conversationId,"retained draft")
        controlMain {assertTrue(f.controller.pause(owner))}
        awaitControl("paused before supplement") {f.controller.runState(owner)?.phase==ExecutionPhase.PAUSED}
        val result=java.util.concurrent.atomic.AtomicReference<app.daiyu.android.domain.runtime.SupplementAdmission?>()
        val id=MessageId("durable-added")
        controlMain {assertTrue(f.controller.admitSupplement(owner,id,"added user") {result.set(it)})}
        awaitControl("durable supplement receipt") {result.get()!=null}
        assertEquals(app.daiyu.android.domain.runtime.SupplementAdmission.Accepted(id),result.get())
        assertEquals(3,f.database.messages(owner.conversationId).size)
        assertEquals("retained draft",f.database.conversations().single().draft)
        controlMain {assertFalse(f.controller.runState(owner)!!.pauseRequested)}
        assertTrue(second.entered.await(30,TimeUnit.SECONDS));second.release.countDown()
        awaitControl("supplemented run finishes") {f.controller.active==null}
        val expanded=MessageHistory.expand(f.database.messages(owner.conversationId).map {it.second})
        assertEquals(1,expanded.count {it.id==id});assertEquals(1,MessageHistory.expand(f.histories.last()).count {it.id==id})
        assertEquals(RunStatus.COMPLETED,f.database.messages(owner.conversationId).single {it.second.id==owner.id}.second.status)
        assertEquals("retained draft",f.database.conversations().single().draft)
    }
    fun testStreamingSupplementKeepsOwnerRejectsWrongOwnerAndRetainsDraft() {
        val f=fresh();val first=f.session();val second=f.session("after running supplement")
        val owner=start(f);assertTrue(first.entered.await(30,TimeUnit.SECONDS))
        f.database.draft(owner.conversationId,"unsent")
        val results=java.util.concurrent.LinkedBlockingQueue<app.daiyu.android.domain.runtime.SupplementAdmission>()
        controlMain {assertFalse(f.controller.admitSupplement(owner.copy(runId=RunId("wrong")),MessageId("wrong"),"added") {results.add(it)})}
        assertTrue(results.poll(5,TimeUnit.SECONDS) is app.daiyu.android.domain.runtime.SupplementAdmission.Rejected)
        val id=MessageId("streaming-added")
        controlMain {assertTrue(f.controller.admitSupplement(owner,id,"added") {results.add(it)})}
        awaitControl("streaming durable receipt") {results.isNotEmpty()}
        assertEquals(app.daiyu.android.domain.runtime.SupplementAdmission.Accepted(id),results.poll())
        assertTrue(second.entered.await(30,TimeUnit.SECONDS))
        assertEquals(owner.id,f.controller.active!!.id);assertEquals(owner.runId,f.controller.active!!.runId)
        assertEquals(1,MessageHistory.expand(f.histories.last()).count {it.id==id})
        controlMain {assertTrue(f.controller.stop(owner));assertFalse(f.controller.admitSupplement(owner,MessageId("stopped"),"added") {results.add(it)})}
        awaitControl("stopped receipt") {results.isNotEmpty()}
        assertTrue(results.poll() is app.daiyu.android.domain.runtime.SupplementAdmission.Rejected)
        awaitControl("stopped supplement run") {f.controller.active==null}
        assertEquals(3,f.database.messages(owner.conversationId).size);assertEquals("unsent",f.database.conversations().single().draft)
        assertEquals(2,f.opens.get())
    }

    fun testAcceptedPausedSupplementSurvivesStopAndCloseAfterAutomaticResume() {
        for(closeImmediately in listOf(false,true)) {
            val f=fresh();val first=f.session();val second=f.session();val owner=start(f);assertTrue(first.entered.await(30,TimeUnit.SECONDS))
            f.database.draft(owner.conversationId,"kept after close")
            controlMain {assertTrue(f.controller.pause(owner))}
            awaitControl("paused before retained admission") {f.controller.runState(owner)?.phase==ExecutionPhase.PAUSED}
            val result=java.util.concurrent.atomic.AtomicReference<app.daiyu.android.domain.runtime.SupplementAdmission?>()
            val id=MessageId("retained-added")
            controlMain {assertTrue(f.controller.admitSupplement(owner,id,"retained user") {result.set(it)})}
            awaitControl("retained admission committed") {result.get()!=null}
            assertEquals(app.daiyu.android.domain.runtime.SupplementAdmission.Accepted(id),result.get())
            assertTrue(second.entered.await(30,TimeUnit.SECONDS))
            if(!closeImmediately) {
                controlMain {assertTrue(f.controller.stop(owner))}
                awaitControl("retained admission stopped") {f.controller.active==null}
            }
            f.controller.close()
            val reopened=app.daiyu.android.storage.ConversationDatabase(f.application)
            try {
                val messages=reopened.messages(owner.conversationId).map {it.second}
                assertEquals(1,messages.count {it.id==id});assertEquals(RunStatus.STOPPED,messages.single {it.id==owner.id}.status)
                assertEquals("kept after close",reopened.conversations().single().draft)
            } finally {reopened.close()}
            assertEquals(2,f.opens.get())
        }
    }
}
