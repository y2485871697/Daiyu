package app.daiyu.android

import android.app.Application
import android.content.Context
import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.storage.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@Suppress("DEPRECATION")
internal class RunControlFixture(base:Context) : AutoCloseable {
    private class FixtureApplication(base:Context):Application() {init {attachBaseContext(base)}}
    val application:Application=FixtureApplication(testContext(base,"run-control-${UUID.randomUUID()}-"))
    val database=ConversationDatabase(application)
    private val alias="app.daiyu.android.test.run-control.${UUID.randomUUID()}"
    val settings=ModelSettingsStore(application,alias)
    val opens=AtomicInteger()
    private val sessions=mutableListOf<Session>()
    private val queued=java.util.ArrayDeque<Session>()
    val histories=mutableListOf<List<Message>>()
    val controller:ChatController
    init {
        settings.save(ModelSettings("https://example.invalid","run-control","synthetic-key",false))
        controller=ChatController(application,database,settings,application.getSharedPreferences("conversation-ui",0),{
            ModelPort {history ->
                opens.incrementAndGet()
                synchronized(queued) {histories.add(history);checkNotNull(queued.pollFirst())}
            }
        })
    }
    class Session(val text:String,held:Boolean) : ModelSession {
        val entered=CountDownLatch(1)
        val release=CountDownLatch(if(held) 1 else 0)
        val cancelled=CountDownLatch(1)
        val sink=AtomicReference<((ModelEvent)->Unit)?>()
        override fun next(outputs:List<ToolOutput>,sink:(ModelEvent)->Unit) {
            this.sink.set(sink);entered.countDown()
            check(release.await(10,TimeUnit.SECONDS))
            sink(ModelEvent.TextDelta("body",text))
        }
        override fun cancel() {cancelled.countDown();release.countDown()}
    }
    fun session(text:String="answer",held:Boolean=true)=Session(text,held).also {
        synchronized(queued) {sessions.add(it);queued.addLast(it)}
    }
    override fun close() {
        synchronized(queued) {sessions.forEach {it.release.countDown()}}
        controller.stop();controller.close()
        application.getSharedPreferences("model-settings",0).edit().clear().commit()
        application.getSharedPreferences("conversation-ui",0).edit().clear().commit()
        database.runtimeLockFile.delete()
        application.deleteDatabase("conversations.db")
        val keys=java.security.KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        java.util.Collections.list(keys.aliases()).filter {it==alias || it.startsWith("$alias.")}.forEach {keys.deleteEntry(it)}
    }
}

@Suppress("DEPRECATION")
internal fun InstrumentationTestCase.controlMain(work:()->Unit) {
    val problem=AtomicReference<Throwable?>()
    instrumentation.runOnMainSync {try {work()} catch(t:Throwable) {problem.set(t)}}
    problem.get()?.let {throw it}
}
@Suppress("DEPRECATION")
internal fun InstrumentationTestCase.awaitControl(description:String,predicate:()->Boolean) {
    val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
    var done=false
    while(!done && System.nanoTime()<deadline) {
        controlMain {done=predicate()}
        if(!done) Thread.sleep(10)
    }
    junit.framework.Assert.assertTrue(description,done)
}
