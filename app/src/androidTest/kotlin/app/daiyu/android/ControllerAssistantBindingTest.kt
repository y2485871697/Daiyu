package app.daiyu.android

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.test.InstrumentationTestCase
import app.daiyu.android.assistants.*
import app.daiyu.android.domain.*
import app.daiyu.android.model.ProviderSnapshot
import app.daiyu.android.storage.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@Suppress("DEPRECATION")
class ControllerAssistantBindingTest : InstrumentationTestCase() {
    private class App(base: Context) : Application() {
        private val root=File(base.cacheDir,"assistant-controller-${UUID.randomUUID()}").apply { mkdirs() }
        init { attachBaseContext(base) }
        override fun getApplicationContext(): Context = this
        override fun getFilesDir() = root
    }
    private class MemoryProbe : AssistantAssets {
        val reads = AtomicInteger()
        var load: () -> String = { "captured memory" }
        override fun memory(id: String): AssistantMemorySnapshot { reads.incrementAndGet(); return AssistantMemorySnapshot(load(), "revision") }
        override fun avatarBitmap(fileName: String?): Bitmap? = null
        override fun saveAvatar(id: String, bitmap: Bitmap): String = error("unused")
        override fun copyAvatar(fileName: String, toId: String): String? = error("unused")
        override fun deleteAvatar(fileName: String?) = Unit
        override fun saveMemory(id: String, content: String, revision: String): AssistantMemorySnapshot = error("unused")
        override fun copyMemory(fromId: String, toId: String) = Unit
        override fun deleteMemory(id: String) = Unit
    }
    private class Fixture(val app: App, val database: ConversationDatabase, val controller: ChatController,
        val memory: MemoryProbe, val alias: String)
    private val fixtures=mutableListOf<Fixture>()
    private fun port() = ModelPort { object : ModelSession {
        override fun next(outputs: List<ToolOutput>, sink: (ModelEvent)->Unit) { sink(ModelEvent.TextFinal("answer", "done")) }
        override fun cancel() {}
    } }
    private fun fresh(legacy: (ModelSettings)->ModelPort = { error("legacy factory must not replace assistant factory") },
        factory: ((ProviderSnapshot, AssistantRunContext)->ModelPort)? = { _, _ -> port() }): Fixture {
        val id=UUID.randomUUID().toString()
        val app=App(testContext(instrumentation.targetContext,"assistant-controller-$id-"))
        val database=ConversationDatabase(app)
        val alias="app.daiyu.android.assistant-test.$id"
        val settings=ModelSettingsStore(app,alias)
        settings.save(ModelSettings("https://example.invalid","fixture","key",false))
        val memory=MemoryProbe()
        val controller=ChatController(app,database,settings,app.getSharedPreferences("conversation-ui",0),legacy,
            assistantModel=factory,assistantAssets=memory)
        val fixture=Fixture(app,database,controller,memory,alias)
        fixtures.add(fixture)
        await(fixture) { controller.ready }
        assertNull(controller.problem)
        return fixture
    }
    private fun main(action: ()->Unit) {
        val failure=AtomicReference<Throwable?>()
        instrumentation.runOnMainSync { try { action() } catch(t:Throwable) { failure.set(t) } }
        failure.get()?.let { throw it }
    }
    private fun await(f: Fixture, predicate: ()->Boolean) {
        val latch=CountDownLatch(1)
        val listener: ()->Unit = { if(predicate()) latch.countDown() }
        main { f.controller.observe(listener); listener() }
        try { assertTrue("controller timeout",latch.await(10,TimeUnit.SECONDS)); instrumentation.waitForIdleSync() }
        finally { main { f.controller.unobserve(listener) } }
    }
    override fun tearDown() {
        try { fixtures.forEach { f ->
            f.controller.stop(); f.controller.close(); f.database.close()
            f.app.deleteDatabase("conversations.db"); f.app.filesDir.deleteRecursively()
            f.app.getSharedPreferences("daiyu-assistants",0).edit().clear().commit()
            val keys=java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            java.util.Collections.list(keys.aliases()).filter { it.startsWith("${f.alias}.") }.forEach(keys::deleteEntry)
        } } finally { super.tearDown() }
    }

    fun testNullAndDeletedBindingRejectWithoutFactoryOrMemoryAndKeepDraft() {
        val factories=AtomicInteger()
        val f=fresh(factory={ _, _ -> factories.incrementAndGet(); port() })
        val unbound=f.database.create()
        f.database.draft(unbound.id,"old draft")
        f.controller.assistantStore.save(AssistantProfile("other","其他",""))
        f.controller.assistantStore.select("other")
        assertNull(f.controller.send(unbound,"old draft"))
        assertTrue(f.controller.problem!!.contains("未绑定"))
        assertEquals("old draft",f.database.conversations().single().draft)
        val deleted=f.database.create("default")
        f.database.draft(deleted.id,"deleted draft")
        f.controller.assistantStore.delete("default")
        assertNull(f.controller.send(deleted,"deleted draft"))
        assertTrue(f.controller.problem!!.contains("已删除"))
        assertEquals("deleted draft",f.database.conversations().single { it.id==deleted.id }.draft)
        assertEquals("default",f.database.assistantId(deleted.id))
        assertEquals(0,factories.get()); assertEquals(0,f.memory.reads.get())
    }

    fun testDatabaseBindingWinsOverGlobalAndStaleConversationObject() {
        val captured=mutableListOf<String>()
        val f=fresh(factory={ _, assistant -> captured.add(assistant.assistantId); port() })
        val stale=f.database.create("default")
        f.controller.assistantStore.save(AssistantProfile("other","其他","",memoryEnabled=false))
        f.controller.assistantStore.select("other")
        assertNotNull(f.controller.send(stale,"first"))
        await(f) { f.controller.active==null }
        assertEquals(listOf("default"),captured)
        main { assertTrue(f.controller.bindConversationAssistant(stale.id,"default","other")) }
        assertNotNull(f.controller.send(stale,"second"))
        await(f) { f.controller.active==null }
        assertEquals(listOf("default","other"),captured)
        assertEquals(1,f.memory.reads.get()) // Disabled memory never performs the read.
    }

    fun testPreparationFreezesGlobalProfileAndMemoryAndBlocksBindingRepair() {
        val captured=AtomicReference<AssistantRunContext?>()
        val f=fresh(factory={ _, assistant -> captured.set(assistant); port() })
        val store=f.controller.assistantStore
        store.save(AssistantProfile("default","before","personality before",createdAt=12,enabledSkillIds=listOf("before")))
        store.save(AssistantProfile("other","after","different"))
        val existing=f.database.create("default")
        f.database.draft("composer must survive until accepted")
        val entered=CountDownLatch(1); val release=CountDownLatch(1)
        f.memory.load = { entered.countDown(); check(release.await(10,TimeUnit.SECONDS)); "memory before" }
        val accepted=AtomicReference<Conversation?>(); val failure=AtomicReference<Throwable?>()
        val worker=Thread { try { accepted.set(f.controller.send(null,"composer must survive until accepted")) } catch(t:Throwable) { failure.set(t) } }
        worker.start()
        try {
            assertTrue(entered.await(10,TimeUnit.SECONDS))
            store.select("other")
            store.save(AssistantProfile("default","after","personality after",memoryEnabled=false,enabledSkillIds=listOf("after")))
            main { assertFalse(f.controller.bindConversationAssistant(existing.id,"default","other")) }
            assertEquals("composer must survive until accepted",f.database.draft())
        } finally { release.countDown(); worker.join(10000) }
        assertFalse(worker.isAlive); failure.get()?.let { throw it }
        assertEquals("default",accepted.get()?.assistantId)
        await(f) { f.controller.active==null }
        val run=checkNotNull(captured.get())
        assertEquals("before",run.name); assertEquals("personality before",run.prompt)
        assertEquals("memory before",run.resolvedMemory); assertEquals(listOf("before"),run.enabledSkillIds)
        assertEquals(1,f.memory.reads.get())
        assertEquals("other",store.selectedId.value)
        assertEquals("",f.database.draft())
    }

    fun testDeletedDuringPreparationRollsBackWithoutOpeningAndRetainsComposer() {
        val opened=AtomicInteger()
        lateinit var f: Fixture
        f=fresh(factory={ _, _ ->
            f.controller.assistantStore.delete("default")
            ModelPort { opened.incrementAndGet(); error("must never open") }
        })
        f.controller.assistantStore.save(AssistantProfile("other","other",""))
        f.database.draft("keep")
        assertNull(f.controller.send(null,"keep"))
        assertEquals("keep",f.database.draft())
        assertTrue(f.database.conversations().isEmpty()); assertEquals(0,opened.get())
    }

    fun testExistingInjectedFactoryStillOwnsPortConstruction() {
        val called=AtomicInteger()
        val f=fresh(legacy={ called.incrementAndGet(); port() },factory=null)
        assertNotNull(f.controller.send(null,"legacy injected port"))
        await(f) { f.controller.active==null }
        assertEquals(1,called.get())
    }
}
