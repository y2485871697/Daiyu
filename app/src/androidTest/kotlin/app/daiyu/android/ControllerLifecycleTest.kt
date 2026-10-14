package app.daiyu.android

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.storage.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real Controller + SQLite + credential store; no network and no reflected state gates. */
@Suppress("DEPRECATION")
class ControllerLifecycleTest:InstrumentationTestCase() {
    private class FixtureApplication(base:Context):Application() {
        init {attachBaseContext(base)}
        var settingsRead:(()->Unit)?=null
        override fun getSharedPreferences(name:String,mode:Int):SharedPreferences {
            val raw=super.getSharedPreferences(name,mode)
            if(name!="model-settings") return raw
            return object:SharedPreferences by raw {
                override fun getAll():MutableMap<String,*> {
                    settingsRead?.also {settingsRead=null}?.invoke()
                    return raw.all
                }
            }
        }
    }
    private class Session:ModelSession {
        val entered=CountDownLatch(1)
        val release=CountDownLatch(1)
        val sink=AtomicReference<((ModelEvent)->Unit)?>()
        override fun next(outputs:List<ToolOutput>,sink:(ModelEvent)->Unit) {
            this.sink.set(sink);entered.countDown()
            check(release.await(10,TimeUnit.SECONDS))
            sink(ModelEvent.TextDelta("body","answer"))
        }
        override fun cancel() {release.countDown()}
    }
    private data class Fixture(val app:FixtureApplication,val db:ConversationDatabase,
        val settings:ModelSettingsStore,val alias:String,val controller:ChatController,
        val calls:AtomicInteger,val sessions:MutableList<Session>)
    private val fixtures=mutableListOf<Fixture>()
    private fun main(work:()->Unit) {
        val failure=AtomicReference<Throwable?>()
        instrumentation.runOnMainSync {try {work()} catch(t:Throwable) {failure.set(t)}}
        failure.get()?.let {throw it}
    }
    private fun awaitState(f:Fixture,predicate:()->Boolean) {
        val done=CountDownLatch(1)
        val listener:()->Unit={if(predicate()) done.countDown()}
        main {f.controller.observe(listener);listener()}
        try {assertTrue("controller state timeout",done.await(10,TimeUnit.SECONDS));instrumentation.waitForIdleSync()}
        finally {main {f.controller.unobserve(listener)}}
    }
    private fun fresh(awaitRecovery:Boolean=true,beforeController:(ConversationDatabase)->Unit={}):Fixture {
        val id=UUID.randomUUID().toString()
        val app=FixtureApplication(testContext(instrumentation.targetContext,"controller-lifecycle-$id-"))
        val db=ConversationDatabase(app)
        val alias="app.daiyu.android.test.lifecycle.$id"
        val settings=ModelSettingsStore(app,alias)
        settings.save(ModelSettings("https://example.invalid","fixture-model","fixture-key",false))
        beforeController(db)
        val calls=AtomicInteger();val sessions=mutableListOf<Session>()
        val controller=ChatController(app,db,settings,app.getSharedPreferences("conversation-ui",0),{
            ModelPort {
                calls.incrementAndGet()
                synchronized(sessions) {sessions.removeAt(0)}
            }
        })
        val f=Fixture(app,db,settings,alias,controller,calls,sessions)
        fixtures.add(f)
        if(awaitRecovery) awaitState(f) {controller.ready}
        return f
    }
    private fun session(f:Fixture)=Session().also {synchronized(f.sessions) {f.sessions.add(it)}}
    private fun rejects(work:()->Unit) {
        try {work();fail("runtime gate must reject")} catch(_:IllegalStateException) {}
    }
    override fun tearDown() {
        try {fixtures.forEach {f->
            synchronized(f.sessions) {f.sessions.forEach {it.release.countDown()}}
            f.controller.stop();f.controller.close();f.db.close()
            f.app.deleteDatabase("conversations.db")
            f.app.getSharedPreferences("model-settings",0).edit().clear().commit()
            f.app.getSharedPreferences("conversation-ui",0).edit().clear().commit()
            val keys=java.security.KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
            java.util.Collections.list(keys.aliases()).filter {it==f.alias || it.startsWith("${f.alias}.")}.forEach {keys.deleteEntry(it)}
        }} finally {super.tearDown()}
    }
    fun testReentrantSendAndDataTaskCannotReserveTwiceDuringSettingsRead() {
        val f=fresh();val c=f.db.create();f.db.draft(c.id,"retained until accepted")
        val s=session(f);var checks=0
        f.app.settingsRead={
            assertNull(f.controller.send(c,"duplicate"))
            rejects {f.controller.dataTask {fail("must not queue during prepare");"unexpected"}}
            rejects {f.controller.previewImportedConfiguration()}
            checks++
        }
        main {assertNotNull(f.controller.send(c,"first"))}
        assertEquals(1,checks);assertTrue(s.entered.await(5,TimeUnit.SECONDS))
        assertEquals(2,f.db.messages(c.id).size);assertEquals("",f.db.conversations().single().draft)
        main {assertNull(f.controller.send(c,"duplicate after receipt"))}
        assertEquals(1,f.calls.get());s.release.countDown();awaitState(f) {f.controller.active==null}
        assertEquals(2,f.db.messages(c.id).size)
    }
    fun testConcurrentSendRechecksGateBeforeSecondPersistentReceipt() {
        val f=fresh();val c=f.db.create();val s=session(f)
        val reading=CountDownLatch(1);val release=CountDownLatch(1)
        val firstResult=AtomicReference<Conversation?>();val secondResult=AtomicReference<Conversation?>()
        val failure=AtomicReference<Throwable?>()
        f.app.settingsRead={reading.countDown();check(release.await(10,TimeUnit.SECONDS))}
        val first=Thread {try {firstResult.set(f.controller.send(c,"first"))} catch(t:Throwable) {failure.set(t)}}
        val second=Thread {try {secondResult.set(f.controller.send(c,"second"))} catch(t:Throwable) {failure.set(t)}}
        first.start()
        try {
            assertTrue(reading.await(5,TimeUnit.SECONDS));second.start()
            // Both implementations block here: baseline on the settings lock AFTER its gate,
            // fixed Controller on its entry monitor BEFORE evaluating the second gate.
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
            while(second.state!=Thread.State.BLOCKED && second.isAlive && System.nanoTime()<deadline) Thread.yield()
            assertEquals(Thread.State.BLOCKED,second.state)
            release.countDown();first.join(5000);second.join(5000)
            assertFalse(first.isAlive);assertFalse(second.isAlive);failure.get()?.let {throw it}
            assertNotNull(firstResult.get());assertNull(secondResult.get())
            assertTrue(s.entered.await(5,TimeUnit.SECONDS));assertEquals(2,f.db.messages(c.id).size)
            assertEquals(1,f.calls.get())
        } finally {release.countDown();s.release.countDown();first.join(5000);if(second.state!=Thread.State.NEW) second.join(5000)}
        awaitState(f) {f.controller.active==null}
    }
    fun testBusyFailureRetainsDraftAndReleasesGateOnMainThread() {
        val f=fresh();val c=f.db.create();f.db.draft(c.id,"unsent")
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val threads=AtomicInteger()
        val listener:()->Unit={if(Looper.myLooper()!=Looper.getMainLooper()) threads.incrementAndGet()}
        main {f.controller.observe(listener);f.controller.dataTask(importing=true) {
            entered.countDown();check(release.await(10,TimeUnit.SECONDS));throw java.io.IOException("synthetic")
        }}
        try {
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            main {
                assertNull(f.controller.send(c,"unsent"))
                rejects {f.controller.dataTask {"must not run"}}
                rejects {f.controller.previewImportedConfiguration()}
            }
            assertEquals("unsent",f.db.conversations().single().draft);assertTrue(f.db.messages(c.id).isEmpty())
        } finally {release.countDown()}
        awaitState(f) {!f.controller.dataBusy}
        assertTrue(f.controller.dataNotice?.startsWith("导入失败")==true)
        assertEquals(0L,f.controller.dataRevision);assertEquals(0,threads.get());assertEquals(0,f.calls.get())
        main {f.controller.unobserve(listener)}
        val s=session(f);main {assertNotNull(f.controller.send(c,"unsent"))}
        assertTrue(s.entered.await(5,TimeUnit.SECONDS));s.release.countDown();awaitState(f) {f.controller.active==null}
    }
    fun testCredentialHistoryAndTransactionFailuresPreserveDraftWithoutModelOpen() {
        val f=fresh();val c=f.db.create();f.db.draft(c.id,"keep draft");f.db.draft("keep composer")
        f.app.getSharedPreferences("model-settings",0).edit().putBoolean("credential-rebind-required",true).commit()
        main {assertNull(f.controller.send(c,"keep draft"))}
        assertTrue(f.controller.problem?.contains("未发起网络请求")==true)
        f.settings.save(ModelSettings("https://example.invalid","fixture-model","fixture-key",false))
        // A real transaction failure must roll back clearing the conversation draft.
        f.db.writableDatabase.execSQL("CREATE TRIGGER reject_send BEFORE INSERT ON messages BEGIN SELECT RAISE(ABORT,'fixture'); END")
        main {assertNull(f.controller.send(c,"keep draft"));assertNull(f.controller.send(null,"keep composer"))}
        assertEquals("keep draft",f.db.conversations().single().draft);assertEquals("keep composer",f.db.draft())
        assertEquals(1,f.db.conversations().size);assertTrue(f.db.messages(c.id).isEmpty());assertNull(f.controller.active)
        f.db.writableDatabase.execSQL("DROP TRIGGER reject_send")
        // Corrupt a historical row only after accepting and finishing a normal run.
        val s=session(f);main {assertNotNull(f.controller.send(c,"accepted"))}
        assertTrue(s.entered.await(5,TimeUnit.SECONDS));s.release.countDown();awaitState(f) {f.controller.active==null}
        f.db.draft(c.id,"history read failure");f.db.writableDatabase.execSQL("UPDATE messages SET blocks='not-json'")
        main {assertNull(f.controller.send(c,"history read failure"))}
        assertTrue(f.controller.problem?.startsWith("读取历史失败")==true)
        assertEquals("history read failure",f.db.conversations().single().draft);assertEquals(1,f.calls.get())
    }
    fun testStartupGateWaitsForRealRecoveryAndNeverReplaysInterruptedRun() {
        val f=fresh(awaitRecovery=false) {db->
            val c=db.create();val run=RunId("interrupted")
            val user=Message(MessageId("interrupted-user"),c.id,run,"user",listOf(MessageBlock.Text("u","old request")),RunStatus.COMPLETED)
            val reply=Message(MessageId("interrupted-reply"),c.id,run,"assistant",emptyList(),RunStatus.RUNNING)
            db.send(user,reply);db.draft("pending draft")
            // Hold the writable SQLite connection on this instrumentation thread.
            // The Controller recovery worker cannot finish until endTransaction below.
            db.writableDatabase.beginTransaction()
        }
        try {
            assertFalse(f.controller.ready)
            f.app.settingsRead={fail("startup gate must precede settings read")}
            main {
                assertNull(f.controller.send(null,"pending draft"))
                rejects {f.controller.dataTask {"unexpected"}}
                rejects {f.controller.previewImportedConfiguration()}
            }
        } finally {f.db.writableDatabase.endTransaction();f.app.settingsRead=null}
        awaitState(f) {f.controller.ready}
        assertNull(f.controller.problem);assertNull(f.controller.active);assertEquals(0,f.calls.get())
        assertEquals("pending draft",f.db.draft())
        val messages=f.db.messages(f.db.conversations().single().id)
        assertEquals(RunStatus.INTERRUPTED,messages.last().second.status)
    }
    fun testCloseWhileRecoveryIsPendingCannotPublishReadyAfterShutdown() {
        val f=fresh(awaitRecovery=false) {db->db.draft("pending close");db.writableDatabase.beginTransaction()}
        val closing=CountDownLatch(1);val failure=AtomicReference<Throwable?>()
        val closer=Thread {closing.countDown();try {f.controller.close()} catch(t:Throwable) {failure.set(t)}}
        closer.start()
        try {
            assertTrue(closing.await(5,TimeUnit.SECONDS))
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
            while(closer.state!=Thread.State.TIMED_WAITING && closer.isAlive && System.nanoTime()<deadline) Thread.yield()
            // awaitTermination is reached only after closing the Controller's admission gate.
            assertEquals(Thread.State.TIMED_WAITING,closer.state)
        } finally {f.db.writableDatabase.endTransaction();closer.join(5000)}
        assertFalse(closer.isAlive);failure.get()?.let {throw it}
        instrumentation.waitForIdleSync();assertFalse(f.controller.ready);assertNull(f.controller.active)
        main {assertNull(f.controller.send(null,"pending close"));rejects {f.controller.dataTask {"unexpected"}}}
        assertEquals(0,f.calls.get())
    }
    fun testRecoveryFailureRejectsAllIdleOperationsWithoutReadingSettings() {
        val f=fresh {db->db.draft("preserved");db.writableDatabase.execSQL("DROP TABLE messages")}
        assertTrue(f.controller.problem?.startsWith("会话恢复失败")==true)
        f.app.settingsRead={fail("recovery gate must precede credential read")}
        main {
            assertNull(f.controller.send(null,"preserved"))
            rejects {f.controller.dataTask {"unexpected"}}
            rejects {f.controller.previewImportedConfiguration()}
        }
        assertEquals("preserved",f.db.draft());assertFalse(f.controller.dataBusy);assertEquals(0,f.calls.get())
    }
    fun testRemovedObserverIsSkippedInSameDispatchAndFailureDoesNotSuppressOthers() {
        val f=fresh();val removed=AtomicInteger();val delivered=CountDownLatch(1)
        val second:()->Unit={removed.incrementAndGet()}
        val first:()->Unit={f.controller.unobserve(second);throw IllegalStateException("observer failure")}
        val third:()->Unit={delivered.countDown()}
        main {
            f.controller.observe(first);f.controller.observe(second);f.controller.observe(third)
            f.controller.dataTask {"done"}
        }
        assertTrue(delivered.await(5,TimeUnit.SECONDS));awaitState(f) {!f.controller.dataBusy}
        assertEquals(0,removed.get())
        main {f.controller.unobserve(first);f.controller.unobserve(third)}
    }
    fun testCloseInvalidatesQueuedDataCompletionAndCannotReopenDatabaseThroughSend() {
        val f=fresh();f.db.draft("closed draft");val notices=AtomicInteger()
        main {
            f.controller.observe {notices.incrementAndGet()}
            f.controller.dataTask(importing=true) {"queued completion"}
            // Main looper cannot consume the completion before this close drains the worker.
            f.controller.close()
            assertFalse(f.controller.ready);assertFalse(f.controller.dataBusy);assertNull(f.controller.active)
            assertNull(f.controller.send(null,"closed draft"))
            rejects {f.controller.dataTask {"unexpected"}}
            rejects {f.controller.previewImportedConfiguration()}
            f.controller.close()
        }
        instrumentation.waitForIdleSync()
        assertEquals(0,notices.get());assertNull(f.controller.dataNotice);assertEquals(0L,f.controller.dataRevision)
        assertEquals(0,f.calls.get())
        val reopened=ConversationDatabase(f.app)
        try {assertEquals("closed draft",reopened.draft());assertTrue(reopened.conversations().isEmpty())}
        finally {reopened.close()}
    }
    fun testOldSessionCallbacksCannotPolluteNextConversationOrClosedController() {
        val f=fresh();val a=f.db.create();val b=f.db.create();f.db.draft(b.id,"B draft")
        val old=session(f);main {assertNotNull(f.controller.send(a,"A"))}
        assertTrue(old.entered.await(5,TimeUnit.SECONDS))
        main {assertNull(f.controller.send(b,"B draft"));f.controller.stop()}
        awaitState(f) {f.controller.active==null}
        assertEquals("B draft",f.db.conversations().first {it.id==b.id}.draft)
        val next=session(f);main {assertNotNull(f.controller.send(b,"B draft"))}
        assertTrue(next.entered.await(5,TimeUnit.SECONDS));val active=f.controller.active
        old.sink.get()!!.invoke(ModelEvent.TextDelta("late","must not appear"))
        assertEquals(active,f.controller.active);assertEquals(2,f.db.messages(b.id).size)
        main {f.controller.close()}
        next.sink.get()!!.invoke(ModelEvent.TextDelta("late","closed"))
        instrumentation.waitForIdleSync();assertNull(f.controller.active);assertFalse(f.controller.ready)
        assertEquals(2,f.calls.get())
    }
}
