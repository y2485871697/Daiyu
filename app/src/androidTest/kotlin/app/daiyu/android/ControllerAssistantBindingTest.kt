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
        val balances = fixtureProviderBalances()
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
    private fun port() = CatalogFixturePort { object : ModelSession {
        override fun next(outputs: List<ToolOutput>, sink: (ModelEvent)->Unit) { sink(ModelEvent.TextFinal("answer", "done")) }
        override fun cancel() {}
    } }
    private fun fresh(legacy: (ModelSettings)->ModelPort = { error("legacy factory must not replace assistant factory") },
        sessionFactory: ((ProviderSnapshot, AssistantRunContext, app.daiyu.android.model.sessiongateway.SessionGatewayContext)->ModelPort)? = null,
        factory: ((ProviderSnapshot, AssistantRunContext)->ModelPort)? = { _, _ -> port() }): Fixture {
        val id=UUID.randomUUID().toString()
        val app=App(testContext(instrumentation.targetContext,"assistant-controller-$id-"))
        val database=ConversationDatabase(app)
        val alias="app.daiyu.android.assistant-test.$id"
        val settings=ModelSettingsStore(app,alias)
        settings.save(ModelSettings("https://example.invalid","fixture","key",false))
        val memory=MemoryProbe()
        val controller=ChatController(app,database,settings,app.getSharedPreferences("conversation-ui",0),legacy,
            assistantModel=factory,assistantAssets=memory,sessionModel=sessionFactory, providerBalanceHost = { app.balances })
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
            f.controller.stop(); f.controller.close(); f.app.balances.close(); f.database.close()
            f.app.deleteDatabase("conversations.db"); f.app.filesDir.deleteRecursively()
            f.app.getSharedPreferences("daiyu-assistants",0).edit().clear().commit()
            val keys=java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            java.util.Collections.list(keys.aliases()).filter { it.startsWith("${f.alias}.") }.forEach(keys::deleteEntry)
            listOf("pet", "vision").forEach { slot ->
                val raw = f.app.getSharedPreferences("independent-$slot-model", 0)
                raw.getString("key-alias", null)?.let { keys.deleteEntry(it) }
                raw.edit().clear().commit()
            }
            f.app.getSharedPreferences("independent-model-bindings", 0).edit().clear().commit()
        } } finally { super.tearDown() }
    }

    fun testReplyNamesRemainFrozenAfterRebindingAndProfileRename() {
        val frozen = java.util.Collections.synchronizedList(mutableListOf<AssistantRunContext>())
        val f = fresh(factory = { _, context -> frozen.add(context); port() })
        f.controller.assistantStore.save(AssistantProfile("first", "甲助手", ""))
        f.controller.assistantStore.save(AssistantProfile("second", "乙助手", ""))
        f.controller.assistantStore.select("first")
        f.database.draft("first")
        val conversation = checkNotNull(f.controller.send(null, "first"))
        await(f) { f.controller.active == null }
        assertTrue(f.database.bindAssistant(conversation.id, "first", "second"))
        f.controller.assistantStore.save(AssistantProfile("first", "已改名", ""))
        f.database.draft(conversation.id, "second")
        assertNotNull(f.controller.send(conversation, "second"))
        await(f) { f.controller.active == null }
        val messages = f.database.messages(conversation.id).map { it.second }.filter { it.role == Message.ASSISTANT_ROLE }
        assertEquals(listOf("甲助手", "乙助手"), messages.map { f.database.replyIdentity(it)?.name })
        assertEquals(frozen.map { it.assistantId }, messages.map { f.database.replyIdentity(it)?.assistantId })
        val reopened = ConversationDatabase(f.app)
        try { assertEquals(listOf("甲助手", "乙助手"), messages.map { reopened.replyIdentity(it)?.name }) }
        finally { reopened.close() }
    }

    fun testSessionFactoryGetsCommittedConversationAcrossFirstSendContinuationAndControllerRecreation() {
        val owners = java.util.Collections.synchronizedList(mutableListOf<ConversationId>())
        val frozen = java.util.Collections.synchronizedList(mutableListOf<AssistantRunContext>())
        val sessionFactory: (ProviderSnapshot, AssistantRunContext, app.daiyu.android.model.sessiongateway.SessionGatewayContext) -> ModelPort =
            { _, assistant, session ->
                owners.add(session.conversationId); frozen.add(assistant)
                assertNull(session.project)
                port()
            }
        val f = fresh(sessionFactory = sessionFactory, factory = { _, _ -> error("old seam must not replace session factory") })
        f.database.draft("first")
        val accepted = checkNotNull(f.controller.send(null, "first"))
        await(f) { f.controller.active == null }
        assertEquals(listOf(accepted.id), owners)
        val firstRows = f.database.messages(accepted.id).map { it.second }
        assertTrue(firstRows.all { it.conversationId == owners.single() && it.runId.value != owners.single().value })
        f.database.draft(accepted.id, "second")
        assertNotNull(f.controller.send(accepted, "second")); await(f) { f.controller.active == null }
        assertEquals(listOf(accepted.id, accepted.id), owners)
        f.database.draft("other")
        val other = checkNotNull(f.controller.send(null, "other")); await(f) { f.controller.active == null }
        assertTrue(other.id != accepted.id)
        assertEquals(listOf(accepted.id, accepted.id, other.id), owners)
        assertTrue(frozen.all { it.assistantId == "default" })
        f.controller.close()
        val reopened = ConversationDatabase(f.app)
        val restored = ChatController(f.app, reopened, ModelSettingsStore(f.app, f.alias),
            f.app.getSharedPreferences("conversation-ui", 0), { error("legacy fallback") },
            assistantAssets = f.memory, sessionModel = sessionFactory, providerBalanceHost = { f.app.balances })
        try {
            awaitControl("restored controller ready") { restored.ready }
            assertEquals(3, owners.size) // Recovery alone never starts historical network requests.
            reopened.draft(accepted.id, "after restart")
            assertNotNull(restored.send(accepted, "after restart"))
            awaitControl("restored conversation send completed") { restored.active == null }
            assertEquals(listOf(accepted.id, accepted.id, other.id, accepted.id), owners)
        } finally { restored.close(); reopened.close() }
    }

    fun testSessionFactoryDoesNotBypassMissingAssistantAdmission() {
        val calls = AtomicInteger()
        val f = fresh(sessionFactory = { _, _, _ -> calls.incrementAndGet(); port() })
        val unbound = f.database.create(); f.database.draft(unbound.id, "keep")
        assertNull(f.controller.send(unbound, "keep")); assertEquals(0, calls.get())
        assertTrue(f.database.messages(unbound.id).isEmpty())
        assertEquals("keep", f.database.conversations().single().draft)
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

    fun testProfileChangedDuringCaptureRejectsWithoutOpeningAndRetainsComposerAndBinding() {
        val factories=AtomicInteger(); val opened=AtomicInteger()
        val f=fresh(factory={ _, _ ->
            factories.incrementAndGet()
            CatalogFixturePort { opened.incrementAndGet(); error("must never open") }
        })
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
            // capture must reject a profile changed while its memory snapshot is being read.
            store.save(AssistantProfile("default","after","personality after",memoryEnabled=false,enabledSkillIds=listOf("after")))
            main { assertTrue(f.controller.bindConversationAssistant(existing.id,"default","other")) }
            assertEquals("composer must survive until accepted",f.database.draft())
        } finally { release.countDown(); worker.join(10000) }
        assertFalse(worker.isAlive); failure.get()?.let { throw it }
        assertNull(accepted.get()); assertNull(f.controller.active)
        assertTrue(f.controller.problem!!.contains("助手配置或记忆读取失败"))
        assertEquals("composer must survive until accepted",f.database.draft())
        assertEquals(listOf(existing.id),f.database.conversations().map { it.id })
        assertEquals("other",f.database.assistantId(existing.id))
        assertTrue(f.database.messages(existing.id).isEmpty())
        assertEquals(0,factories.get()); assertEquals(0,opened.get())
        assertEquals(1,f.memory.reads.get())
        assertEquals("other",store.selectedId.value)
    }

    fun testCompletedCaptureFreezesGlobalProfileAndMemoryAndAllowsUnrelatedBindingRepair() {
        val entered=CountDownLatch(1); val release=CountDownLatch(1)
        val captured=AtomicReference<AssistantRunContext?>()
        val f=fresh(factory={ _, assistant ->
            // This seam is reached only after capture's post-memory profile recheck succeeds.
            captured.set(assistant); entered.countDown()
            check(release.await(10,TimeUnit.SECONDS))
            port()
        })
        val store=f.controller.assistantStore
        store.save(AssistantProfile("default","before","personality before",createdAt=12,enabledSkillIds=listOf("before")))
        store.save(AssistantProfile("other","after","different"))
        val existing=f.database.create("default")
        f.database.draft("composer must survive until accepted")
        f.memory.load = { "memory before" }
        val accepted=AtomicReference<Conversation?>(); val failure=AtomicReference<Throwable?>()
        val worker=Thread { try { accepted.set(f.controller.send(null,"composer must survive until accepted")) } catch(t:Throwable) { failure.set(t) } }
        worker.start()
        try {
            assertTrue(entered.await(10,TimeUnit.SECONDS))
            store.select("other")
            store.save(AssistantProfile("default","after","personality after",memoryEnabled=false,enabledSkillIds=listOf("after")))
            f.memory.load = { "memory after" }
            main { assertTrue(f.controller.bindConversationAssistant(existing.id,"default","other")) }
            assertEquals("composer must survive until accepted",f.database.draft())
        } finally { release.countDown(); worker.join(10000) }
        assertFalse(worker.isAlive); failure.get()?.let { throw it }
        val conversation=checkNotNull(accepted.get())
        assertEquals("default",conversation.assistantId)
        await(f) { f.controller.active==null }
        val run=checkNotNull(captured.get())
        assertEquals("default",run.assistantId)
        assertEquals("before",run.name); assertEquals("personality before",run.prompt)
        assertTrue(run.memoryEnabled)
        assertEquals("memory before",run.resolvedMemory); assertEquals(listOf("before"),run.enabledSkillIds)
        // Frozen core is not permission to read again after memory has been disabled.
        try { checkNotNull(run.memoryReadPort).read(); fail("disabled memory read accepted") }
        catch (_:AssistantMemoryUnavailable) {}
        assertEquals(1,f.memory.reads.get())
        assertEquals("other",store.selectedId.value)
        assertEquals("other",f.database.assistantId(existing.id))
        assertTrue(f.database.messages(existing.id).isEmpty())
        assertEquals(RunStatus.COMPLETED,f.database.messages(conversation.id).last().second.status)
        assertEquals("",f.database.draft())
    }

    fun testDeletedDuringPreparationRollsBackWithoutOpeningAndRetainsComposer() {
        val opened = AtomicInteger()
        val factories = AtomicInteger()
        lateinit var f: Fixture
        f = fresh(factory = { _, assistant ->
            factories.incrementAndGet()
            if (assistant.assistantId == "default") {
                f.controller.assistantStore.delete("default")
                CatalogFixturePort { opened.incrementAndGet(); error("must never open") }
            } else port()
        })
        f.controller.assistantStore.save(AssistantProfile("other", "other", ""))
        f.database.draft("keep")
        assertNull(f.controller.send(null, "keep"))
        assertEquals(1, factories.get())
        assertNull(f.controller.active)
        assertNotNull(f.controller.problem)
        assertNull(f.controller.assistantStore.read("default"))
        assertEquals("other", f.controller.assistantStore.selectedId.value)
        assertEquals("keep", f.database.draft())
        assertTrue(f.database.conversations().isEmpty())
        assertEquals(0, opened.get())

        // A new explicit send must work after the rejected preparation releases its lane.
        f.controller.assistantStore.select("other")
        val accepted = checkNotNull(f.controller.send(null, "keep"))
        assertEquals("other", accepted.assistantId)
        await(f) { f.controller.active == null }
        assertEquals(2, factories.get())
        assertEquals("", f.database.draft())
        assertEquals(1, f.database.conversations().size)
        assertEquals(0, opened.get())
    }

    fun testCommittedPetEventUsesAcceptanceTitleAndActualPersistedTransition() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val f = fresh(factory = { _, _ -> CatalogFixturePort { object : ModelSession {
            override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
                sink(ModelEvent.TextFinal("answer", "done"))
            }
            override fun cancel() { release.countDown() }
        } } })
        val event = AtomicReference<app.daiyu.android.pet.PetAssistantTransition?>()
        val committed = CountDownLatch(1)
        val callbackFailure = AtomicReference<Throwable?>()
        val lease = f.controller.petTransitions.subscribe { transition ->
            try {
                app.daiyu.android.context.DatabaseTransactions { f.database.readableDatabase }.run { db ->
                    assertEquals(transition.after, f.database.storedMessage(db,
                        transition.after.conversationId, transition.after.id.value))
                }
                event.set(transition)
            } catch (failure: Throwable) {
                callbackFailure.set(failure)
            } finally {
                committed.countDown()
            }
        }
        try {
            val accepted = checkNotNull(f.controller.send(null, "completion event"))
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            f.database.rename(accepted.id, "renamed while running; not the acceptance title")
            f.controller.assistantStore.save(AssistantProfile("different", "Other selection", ""))
            f.controller.assistantStore.select("different")
            release.countDown()
            assertTrue(committed.await(10, TimeUnit.SECONDS))
            callbackFailure.get()?.let { throw it }
            val actual = checkNotNull(event.get())
            assertEquals(RunStatus.RUNNING, actual.before.status)
            assertEquals(RunStatus.COMPLETED, actual.after.status)
            assertEquals(actual.before.id, actual.after.id)
            assertEquals(accepted.title, actual.title)
            await(f) { f.controller.active == null }
        } finally { release.countDown(); lease.cancel() }
    }

    fun testEnabledVisionRouteIsIndependentAndDoesNotUpgradeMainImageCapability() {
        val mainOpens = AtomicInteger()
        val f = fresh(factory = { provider, _ ->
            assertEquals("fixture", provider.model)
            assertEquals(false, provider.imageInputSupported)
            CatalogFixturePort { history ->
                mainOpens.incrementAndGet()
                assertTrue(history.all { it.blocks.none { block -> block is MessageBlock.Image } })
                object : ModelSession {
                    override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
                        sink(ModelEvent.TextFinal("done", "ordinary text through auxiliary branch"))
                    }
                    override fun cancel() {}
                }
            }
        })
        f.controller.independentModels.save(IndependentModelSettings.Slot.VISION,
            ModelSettings("https://vision.invalid", "separate-vision-api", "separate-vision-key", false), null, true, true)
        f.controller.settings.modelCatalogSnapshot()
        f.controller.settings.modelCatalog.updateRegisteredModel(f.controller.settings.load().model) { current ->
            val record = checkNotNull(current)
            record.copy(settings = record.settings.copy(visionOverride = false))
        }
        val main = ProviderSnapshot.capture(f.controller.providers.freezeActive().settings)
        val route = f.controller.captureImageRoute(main)
        assertEquals(false, main.imageInputSupported)
        assertEquals("separate-vision-api", route.actual.model)
        assertEquals(true, route.actual.imageInputSupported)
        assertNotNull(route.auxiliaryGeneration)
        assertNotNull(f.controller.send(null, "text needs authorization too"))
        await(f) { f.controller.active == null }
        assertEquals(1, mainOpens.get())
    }

    fun testAuxiliaryConfigurationEditCancelsBlockedOpenButRetainsRunUntilWorkerDrains() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val lateCancels = AtomicInteger(); val nextCalls = AtomicInteger()
        val f = fresh(factory = { _, _ -> CatalogFixturePort {
            entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
            object : ModelSession {
                override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) { nextCalls.incrementAndGet() }
                override fun cancel() { lateCancels.incrementAndGet() }
            }
        } })
        val configuration = ModelSettings("https://vision.invalid", "vision-a", "vision-key", false)
        f.controller.independentModels.save(IndependentModelSettings.Slot.VISION, configuration, null, true, true)
        try {
            assertNotNull(f.controller.send(null, "hold open"))
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            f.controller.independentModels.save(IndependentModelSettings.Slot.VISION,
                configuration.copy(model = "vision-b"), null, true, true)
            assertNotNull(f.controller.active) // cancel is not worker/drain completion.
            release.countDown()
            await(f) { f.controller.active == null }
            assertEquals(0, nextCalls.get())
            assertTrue(lateCancels.get() >= 1)
        } finally { release.countDown() }
    }

    fun testExplicitAuxiliaryUnknownFailsWithoutCallingMainOrFallingBack() {
        val factories = AtomicInteger()
        val f = fresh(factory = { _, _ -> factories.incrementAndGet(); port() })
        f.controller.independentModels.save(IndependentModelSettings.Slot.VISION,
            ModelSettings("https://auxiliary.invalid", "vision-api", "independent-key", false), null, true, null)
        f.database.draft("retain unknown capability")
        assertNull(f.controller.send(null, "retain unknown capability"))
        assertEquals(0, factories.get())
        assertEquals("retain unknown capability", f.database.draft())
        assertTrue(f.controller.problem!!.contains("辅助视觉"))
        // Explicitly disabled is a separate ordinary text path, not catch-and-fallback.
        f.controller.independentModels.save(IndependentModelSettings.Slot.VISION,
            ModelSettings("https://auxiliary.invalid", "vision-api", "independent-key", false), null, false, null)
        assertNotNull(f.controller.send(null, "retain unknown capability"))
        await(f) { f.controller.active == null }
        assertEquals(1, factories.get())
    }

    fun testExistingInjectedFactoryStillOwnsPortConstruction() {
        val called=AtomicInteger()
        val f=fresh(legacy={ called.incrementAndGet(); port() },factory=null)
        assertNotNull(f.controller.send(null,"legacy injected port"))
        await(f) { f.controller.active==null }
        assertEquals(1,called.get())
    }
}
