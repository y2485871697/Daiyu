package app.daiyu.android

import android.app.Application
import android.content.Context
import android.content.Intent
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.compose.ui.test.*
import app.daiyu.android.domain.*
import app.daiyu.android.domain.question.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.domain.tools.LedgerState
import app.daiyu.android.integration.tools.SQLiteToolLedgerStore
import app.daiyu.android.question.PrivateQuestionStore
import app.daiyu.android.question.QuestionCardView
import app.daiyu.android.storage.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real public catalog, Activity, Controller, Runner, ledger and both durable stores. No network. */
@Suppress("DEPRECATION")
class QuestionBarrierIntegrationTest : ComposeActivityTestCase() {
    companion object {
        private const val CALL = "provider-question-call"
        private const val ARGS = """{"title":"Route choice","question":"Which route?","options":[{"id":"one","label":"First route"},{"id":"two","label":"Second route"}],"allow_other":false,"allow_delegation":false,"allow_note":false}"""
    }
    private class FixtureApplication(base:Context):Application() {init {attachBaseContext(base)}}
    private class Probe:ModelPort {
        val opens=AtomicInteger();val nexts=AtomicInteger()
        val failure=AtomicReference<Throwable?>()
        var beforeContinuation:(List<ToolOutput>)->Unit={}
        override fun open(history:List<Message>):ModelSession=error("public catalog required")
        override fun open(history:List<Message>,definitions:List<ToolDefinition>):ModelSession {
            check(opens.incrementAndGet()==1)
            check(definitions.count {it.name=="ask_user"}==1)
            return object:ModelSession {
                override fun next(outputs:List<ToolOutput>,sink:(ModelEvent)->Unit) {
                    try {when(nexts.incrementAndGet()) {
                        1->{check(outputs.isEmpty());sink(ModelEvent.ToolReady(ToolInvocation(ToolCallId(CALL),"ask_user",ARGS)))}
                        2->{check(outputs.size==1 && outputs.single().callId==ToolCallId(CALL));beforeContinuation(outputs);sink(ModelEvent.TextFinal("done","question complete"))}
                        else->error("duplicate continuation")
                    }} catch(t:Throwable) {failure.set(t);throw t}
                }
                override fun cancel() {}
            }
        }
    }
    private class Fixture(base:Context) {
        val id=UUID.randomUUID().toString()
        val app=FixtureApplication(testContext(base,"question-parent-$id-"))
        val db=ConversationDatabase(app)
        val alias="app.daiyu.android.test.question-parent.$id"
        val settings=ModelSettingsStore(app,alias)
        val probe=Probe()
        lateinit var c:ChatController
    }
    private val fixtures=mutableListOf<Fixture>()
    private lateinit var application:ChatApplication
    private lateinit var original:ChatController
    private var activity:MainActivity?=null
    private fun main(work:()->Unit) {
        val failure=AtomicReference<Throwable?>()
        instrumentation.runOnMainSync {try {work()} catch(t:Throwable) {failure.set(t)}}
        failure.get()?.let {throw it}
    }
    override fun setUp() {super.setUp();application=instrumentation.targetContext.applicationContext as ChatApplication;main {original=application.controller}}
    override fun tearDown() {
        try {
            main {activity?.finish();application.controller=original};instrumentation.waitForIdleSync()
            fixtures.forEach {f ->
                f.c.close();f.db.close();f.db.runtimeLockFile.delete()
                f.app.deleteDatabase("conversations.db");f.app.deleteDatabase("questions.db")
                f.app.getSharedPreferences("model-settings",0).edit().clear().commit()
                f.app.getSharedPreferences("conversation-ui",0).edit().clear().commit()
                val keys=java.security.KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
                java.util.Collections.list(keys.aliases()).filter {it==f.alias || it.startsWith("${f.alias}.")}.forEach {keys.deleteEntry(it)}
            }
        } finally {super.tearDown()}
    }
    private fun await(f:Fixture,description:String,predicate:()->Boolean) {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8)
        while(System.nanoTime()<deadline) {
            f.probe.failure.get()?.let {throw it}
            ui.waitForIdle()
            var done=false;main {done=predicate()}
            if(done) {instrumentation.waitForIdleSync();return}
            Thread.sleep(20)
        }
        fail(description)
    }
    private fun fresh(seed:(Fixture)->Unit={}):Fixture {
        val f=Fixture(instrumentation.targetContext);fixtures.add(f)
        f.settings.save(ModelSettings("https://example.invalid","question-fixture","synthetic-question-key",false))
        seed(f)
        f.c=ChatController(f.app,f.db,f.settings,f.app.getSharedPreferences("conversation-ui",0),{f.probe})
        await(f,"recovery ready") {f.c.ready};assertNull(f.c.problem);return f
    }
    private fun launch(f:Fixture,conversation:Conversation?=null) {
        main {application.controller=f.c}
        activity=instrumentation.startActivitySync(Intent(instrumentation.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        instrumentation.waitForIdleSync()
        if(conversation!=null) main {checkNotNull(activity).select(conversation)}
    }
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap {views(v.getChildAt(it))} else emptyList()
    private fun all()=views(checkNotNull(activity).window.decorView)
    private fun card()=all().filterIsInstance<QuestionCardView>().single()
    private fun submits(v:View)=views(v).filterIsInstance<Button>().filterNot {it is CompoundButton}
    private fun insideCard(v:View):Boolean {var p=v.parent;while(p!=null) {if(p is QuestionCardView) return true;p=p.parent};return false}
    private fun send(f:Fixture):Message {
        setDraft("Ask for a route");node("chat-send").performClick()
        return checkNotNull(f.c.active)
    }
    private fun checkpoint(f:Fixture,m:Message):RunCheckpoint=f.db.readableDatabase.rawQuery("SELECT payload FROM runtime_checkpoints WHERE message_id=?",arrayOf(m.id.value)).use {
        check(it.moveToFirst());RunCheckpointCodec.decode(it.getString(0))
    }
    private fun row(f:Fixture,id:QuestionIdentity):QuestionRecord=PrivateQuestionStore(f.app).use {checkNotNull(it.get(id.storageKey))}
    private fun drainWorker(f:Fixture) {
        val worker=ChatController::class.java.getDeclaredField("executor").apply {isAccessible=true}.get(f.c) as Executor
        val done=CountDownLatch(1);worker.execute {done.countDown()}
        assertTrue("same Controller slot must be free while waiting",done.await(5,TimeUnit.SECONDS))
        instrumentation.waitForIdleSync();f.probe.failure.get()?.let {throw it}
    }
    private fun waiting(f:Fixture,owner:Message):QuestionIdentity {
        await(f,"real public question card") {all().filterIsInstance<QuestionCardView>().singleOrNull()?.isShown==true}
        val saved=checkpoint(f,owner)
        assertEquals(ExecutionPhase.WAITING_USER,saved.phase);assertEquals(ToolCallId(CALL),saved.pending!!.callId)
        assertEquals(ToolDisposition.IN_FLIGHT,saved.tools.single().disposition)
        val id=QuestionIdentity(owner.conversationId.value,owner.runId.value,saved.owner,CALL)
        val record=row(f,id);assertNull(record.answer);assertFalse(record.interrupted)
        drainWorker(f);assertEquals(1,f.probe.opens.get());assertEquals(1,f.probe.nexts.get());return id
    }
    private fun recreate() {
        val old=checkNotNull(activity);val monitor=instrumentation.addMonitor(MainActivity::class.java.name,null,false)
        try {
            main {old.recreate()}
            activity=instrumentation.waitForMonitorWithTimeout(monitor,5000) as? MainActivity
            assertNotNull(activity);ui.waitForIdle();instrumentation.waitForIdleSync()
        } finally {instrumentation.removeMonitor(monitor)}
    }
    fun testPublicQuestionReleasesWorkerAndDurableAnswerContinuesExactlyOnce() {
        val f=fresh();launch(f);val owner=send(f);val id=waiting(f,owner)
        recreate();waiting(f,owner)
        val durable=AtomicInteger()
        f.probe.beforeContinuation={outputs ->
            assertEquals(QuestionAnswer.Selected("two"),row(f,id).answer)
            val saved=checkpoint(f,owner);assertNull(saved.pending)
            assertEquals(outputs.single().output,saved.message.blocks.filterIsInstance<MessageBlock.Tool>().single().result)
            val entries=SQLiteToolLedgerStore(f.db).use {store->store.transaction {it.toList()}}
            assertEquals(1,entries.size);assertEquals(LedgerState.CONFIRMED,entries.single().state);durable.incrementAndGet()
        }
        main {
            val c=card();val group=views(c).filterIsInstance<RadioGroup>().single();group.check(group.getChildAt(1).id)
            val submit=submits(c).single();assertTrue(submit.isEnabled);submit.performClick();submit.performClick()
        }
        await(f,"answered completion") {f.c.active==null};drainWorker(f)
        assertEquals(1,durable.get());assertEquals(1,f.probe.opens.get());assertEquals(2,f.probe.nexts.get())
        assertEquals(QuestionAnswer.Selected("two"),row(f,id).answer)
        PrivateQuestionStore(f.app).use {assertEquals(1,it.records().size)}
        val saved=checkpoint(f,owner);assertEquals(ExecutionPhase.TERMINAL,saved.phase);assertNull(saved.pending);assertEquals(RunStatus.COMPLETED,saved.message.status)
    }
    fun testStopFencesPendingCardAndNeverContinuesModel() {
        val f=fresh();launch(f);val owner=send(f);val id=waiting(f,owner)
        main {
            val c=card();val group=views(c).filterIsInstance<RadioGroup>().single();group.check(group.getChildAt(1).id)
            val old=submits(c).single();assertTrue(f.c.stop(owner));old.performClick();old.performClick()
        }
        await(f,"durable stop") {f.c.active==null};drainWorker(f)
        assertEquals(1,f.probe.opens.get());assertEquals(1,f.probe.nexts.get())
        val record=row(f,id);assertNull(record.answer);assertTrue(record.interrupted)
        val saved=checkpoint(f,owner);assertEquals(RunStatus.STOPPED,saved.message.status);assertNull(saved.pending)
    }
    fun testStopAfterAnswerQueuedBeforeResumeDrainsOriginalContinuation() {
        val f=fresh();launch(f);val owner=send(f);val id=waiting(f,owner)
        val worker=ChatController::class.java.getDeclaredField("executor").apply {isAccessible=true}.get(f.c) as Executor
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        worker.execute {entered.countDown();check(release.await(10,TimeUnit.SECONDS))}
        assertTrue(entered.await(5,TimeUnit.SECONDS))
        try {
            main {val c=card();val group=views(c).filterIsInstance<RadioGroup>().single();group.check(group.getChildAt(1).id);submits(c).single().performClick()}
            await(f,"answer resume queued in same blocked slot") {
                val current=ChatController::class.java.getDeclaredField("resources").apply {isAccessible=true}.get(f.c)
                current!=null && current.javaClass.getDeclaredField("busy").apply {isAccessible=true}.getBoolean(current)
            }
            assertEquals(QuestionAnswer.Selected("two"),row(f,id).answer)
            main {assertTrue(f.c.stop(owner))}
        } finally {release.countDown()}
        await(f,"queued resume stop drains") {f.c.active==null};drainWorker(f)
        assertEquals(1,f.probe.nexts.get());assertEquals(1,f.probe.opens.get())
        val saved=checkpoint(f,owner);assertEquals(RunStatus.STOPPED,saved.message.status);assertNull(saved.pending)
        assertEquals(QuestionAnswer.Selected("two"),row(f,id).answer)
    }
    fun testFailedStopRetainsResourcesAndExplicitRetryCompletesCleanup() {
        val f=fresh();launch(f);val owner=send(f);val id=waiting(f,owner)
        PrivateQuestionStore(f.app).use {store ->
            store.writableDatabase.execSQL("CREATE TRIGGER reject_question_stop BEFORE UPDATE OF interrupted ON questions WHEN NEW.interrupted=1 BEGIN SELECT RAISE(ABORT,'injected'); END")
            try {
                main {assertTrue(f.c.stop(owner))}
                await(f,"failed durable interruption retains retry") {f.c.canRetryQuestionCleanup(owner)}
                assertNotNull(f.c.active);assertEquals(ExecutionPhase.WAITING_USER,checkpoint(f,owner).phase)
                assertFalse(row(f,id).interrupted);assertNull(row(f,id).answer)
                main {assertNull(f.c.send(null,"must remain fenced"))}
            } finally {store.writableDatabase.execSQL("DROP TRIGGER reject_question_stop")}
        }
        ui.onNodeWithText("重试停止清理").performClick()
        await(f,"retry reaches durable stop") {f.c.active==null};drainWorker(f)
        assertNull(f.c.problem);assertTrue(row(f,id).interrupted)
        assertEquals(RunStatus.STOPPED,checkpoint(f,owner).message.status)
        assertEquals(1,f.probe.nexts.get());assertEquals(1,f.probe.opens.get())
    }
    fun testCloseFromMainDrainsPendingBeforeReleasingStore() {
        val f=fresh();launch(f);val owner=send(f);val id=waiting(f,owner)
        main {f.c.close();assertFalse(f.c.ready);assertNull(f.c.active)}
        f.c.close()
        val saved=checkpoint(f,owner)
        assertEquals(RunStatus.STOPPED,saved.message.status);assertNull(saved.pending)
        assertTrue(row(f,id).interrupted);assertNull(row(f,id).answer)
        assertEquals(1,f.probe.nexts.get());assertEquals(1,f.probe.opens.get())
    }
    private fun recovery(answer:QuestionAnswer?) {
        lateinit var old:RunCheckpoint;lateinit var id:QuestionIdentity
        val f=fresh {f ->
            val runtime=SQLiteRuntimeStore.acquire(f.db)
            try {
                runtime.recoverPending();val conversation=f.db.create();val run=RunId("lost-run")
                val user=Message(MessageId("lost-user"),conversation.id,run,"user",listOf(MessageBlock.Text("user","old question")),RunStatus.COMPLETED)
                val assistant=Message(MessageId("lost-assistant"),conversation.id,run,"assistant",emptyList(),RunStatus.RUNNING)
                f.db.send(user,assistant)
                val first=RunCheckpoint(assistant,listOf(user),ExecutionPhase.BOUNDARY,owner="lost-owner",revision=1)
                runtime.commitCheckpoint(first)
                val call=ToolInvocation(ToolCallId(CALL),"ask_user",ARGS)
                val inFlight=first.copy(message=assistant.copy(blocks=listOf(MessageBlock.Tool(call))),phase=ExecutionPhase.TOOL_EXECUTING,tools=listOf(ToolRecord(call,ToolDisposition.IN_FLIGHT)),requestUnconfirmed=true,revision=2)
                runtime.commitCheckpoint(inFlight)
                old=inFlight.copy(phase=ExecutionPhase.WAITING_USER,pending=PendingToolCheckpoint(call.id),revision=3);runtime.commitCheckpoint(old)
                id=QuestionIdentity(conversation.id.value,run.value,old.owner,CALL)
                PrivateQuestionStore(f.app).use {store->check(store.create(QuestionRecord(id.storageKey,AskUserContract.parse(ARGS))));if(answer!=null) check(store.answer(id.storageKey,answer))}
            } finally {f.db.close();runtime.close()}
        }
        launch(f,f.db.conversations().single())
        await(f,"recovered readonly card") {all().filterIsInstance<QuestionCardView>().singleOrNull()?.let {it.isShown && submits(it).isEmpty()}==true}
        drainWorker(f);assertNull(f.c.active);assertEquals(0,f.probe.opens.get());assertEquals(0,f.probe.nexts.get())
        val saved=checkpoint(f,old.message);assertEquals(RunStatus.INTERRUPTED,saved.message.status);assertEquals(ExecutionPhase.TERMINAL,saved.phase);assertNull(saved.pending)
        assertTrue(saved.owner!=old.owner);assertEquals(ToolDisposition.UNKNOWN,saved.tools.single().disposition)
        assertEquals(answer,row(f,id).answer);if(answer==null) assertTrue(row(f,id).interrupted)
        recreate();await(f,"recreated readonly card") {all().filterIsInstance<QuestionCardView>().singleOrNull()?.let {submits(it).isEmpty()}==true}
        drainWorker(f);assertEquals(0,f.probe.opens.get());assertEquals(0,f.probe.nexts.get())
    }
    fun testRecoveryOfPendingQuestionOnlyDisplaysAndDoesNotResend()=recovery(null)
    fun testRecoveryOfSavedAnswerOnlyDisplaysAndDoesNotAuthorizeContinuation()=recovery(QuestionAnswer.Selected("two"))
}
