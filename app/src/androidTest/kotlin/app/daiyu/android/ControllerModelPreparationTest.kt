package app.daiyu.android

import android.app.Application
import android.content.Context
import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.storage.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@Suppress("DEPRECATION")
class ControllerModelPreparationTest : InstrumentationTestCase() {
    private val expectedBaseCatalog = setOf("get_battery_status", "context_usage", "ask_user",
        "skills_list", "skills_read", "skills_read_resource", "assistant_memory_read", "assistant_memory_search")
    private class FixtureApplication(base: Context) : Application() {
        private val privateFiles=File(base.cacheDir,"controller-model-files-${UUID.randomUUID()}").apply {check(mkdirs() || isDirectory)}
        init { attachBaseContext(base) }
        override fun getApplicationContext(): Context = this
        override fun getFilesDir():File=privateFiles
    }
    private data class Fixture(val app: FixtureApplication, val db: ConversationDatabase,
        val settings: ModelSettingsStore, val alias: String, val controller: ChatController)
    private val fixtures = mutableListOf<Fixture>()
    private fun main(work: () -> Unit) {
        val failure = AtomicReference<Throwable?>()
        instrumentation.runOnMainSync { try { work() } catch (t: Throwable) { failure.set(t) } }
        failure.get()?.let { throw it }
    }
    private fun awaitState(f: Fixture, predicate: () -> Boolean) {
        val done = CountDownLatch(1)
        val listener: () -> Unit = { if (predicate()) done.countDown() }
        main { f.controller.observe(listener); listener() }
        try {
            assertTrue("controller state timeout", done.await(10, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
        } finally { main { f.controller.unobserve(listener) } }
    }
    private fun fresh(interactiveEnabled: Boolean? = true, factory: (ModelSettings) -> ModelPort): Fixture {
        val id = UUID.randomUUID().toString()
        val app = FixtureApplication(testContext(instrumentation.targetContext, "controller-model-$id-"))
        val db = ConversationDatabase(app)
        val alias = "app.daiyu.android.test.model.$id"
        val settings = ModelSettingsStore(app, alias)
        settings.save(ModelSettings("https://example.invalid", "fixture-model", "fixture-key", true))
        val preferences = app.getSharedPreferences("conversation-ui", 0)
        if (interactiveEnabled != null) check(preferences.edit().putBoolean("interactive_mode", interactiveEnabled).commit())
        val controller = ChatController(app, db, settings, preferences, factory)
        val f = Fixture(app, db, settings, alias, controller)
        fixtures.add(f)
        awaitState(f) { controller.ready }
        assertNull(controller.problem)
        return f
    }
    private fun session(text: String) = object : ModelSession {
        override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
            sink(ModelEvent.TextDelta("body", text))
        }
        override fun cancel() {}
    }
    override fun tearDown() {
        try {
            fixtures.forEach { f ->
                f.controller.stop(); f.controller.close(); f.db.close()
                f.app.deleteDatabase("conversations.db"); f.app.filesDir.deleteRecursively()
                f.app.getSharedPreferences("model-settings", 0).edit().clear().commit()
                f.app.getSharedPreferences("conversation-ui", 0).edit().clear().commit()
                val keys = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                java.util.Collections.list(keys.aliases()).filter { it.startsWith("${f.alias}.") }
                    .forEach { keys.deleteEntry(it) }
            }
        } finally { super.tearDown() }
    }

    fun testFactoryFailurePreservesBothDraftsAndCanRetryWithoutGhostMessages() {
        var reject = true
        val factories = AtomicInteger(); val opens = AtomicInteger()
        val f = fresh {
            factories.incrementAndGet()
            if (reject) error("synthetic-secret-must-not-escape")
            ModelPort { opens.incrementAndGet(); session("accepted") }
        }
        val conversation = f.db.create("default")
        f.db.draft(conversation.id, "retained conversation draft"); f.db.draft("retained composer draft")
        val before = f.db.conversations()
        // Synchronous compatibility API is worker-only; UI uses owner-aware sendText.
        assertNull(f.controller.send(conversation, "retained conversation draft"))
        assertNull(f.controller.send(null, "retained composer draft"))
        assertEquals(before, f.db.conversations())
        assertTrue(f.db.messages(conversation.id).isEmpty())
        assertEquals("retained composer draft", f.db.draft())
        assertNull(f.controller.active)
        assertEquals(2, factories.get()); assertEquals(0, opens.get())
        assertTrue(f.controller.problem!!.contains("草稿保留"))
        assertFalse(f.controller.problem!!.contains("synthetic-secret"))
        reject = false
        assertNotNull(f.controller.send(conversation, "accepted"))
        awaitState(f) { f.controller.active == null }
        assertEquals(3, factories.get()); assertEquals(1, opens.get())
        assertEquals(2, f.db.messages(conversation.id).size)
        assertEquals(RunStatus.COMPLETED, f.db.messages(conversation.id).last().second.status)
        assertEquals("", f.db.conversations().single().draft)
    }

    fun testPreparationPrecedesReceiptAndUsesFrozenConfigurationAndTools() {
        lateinit var f: Fixture
        lateinit var conversation: Conversation
        val factories = AtomicInteger(); val opens = AtomicInteger()
        f = fresh { config ->
            factories.incrementAndGet()
            assertTrue(f.db.messages(conversation.id).isEmpty())
            assertEquals("keep until accepted", f.db.conversations().single().draft)
            assertNull(f.controller.send(conversation, "reentrant duplicate"))
            object : ModelPort {
                override fun open(history: List<Message>): ModelSession = error("explicit frozen tools required")
                override fun open(history: List<Message>, tools: Set<ReadOnlyTool>): ModelSession = error("legacy enums must not swallow catalog")
                override fun open(history: List<Message>, definitions: List<ToolDefinition>): ModelSession {
                    f.settings.save(config.copy(model="later-model", apiKey="later-key", batteryEnabled=false, deviceStatusEnabled=true))
                    opens.incrementAndGet()
                    assertEquals(expectedBaseCatalog, definitions.map { it.name }.toSet())
                    assertEquals(setOf("ask_user"), definitions.filter {it.sideEffect}.map {it.name}.toSet())
                    assertEquals("fixture-model", config.model)
                    assertEquals("fixture-key", config.apiKey)
                    val runTools = ChatController::class.java.getDeclaredField("runTools").apply { isAccessible = true }
                        .get(f.controller)
                    val bound = runTools.javaClass.getDeclaredField("owner").apply { isAccessible = true }.get(runTools)
                        as app.daiyu.android.domain.runtime.RunExecutionOwner
                    val assistant = f.db.messages(conversation.id).last().second
                    assertTrue(bound.owns(assistant))
                    f.db.readableDatabase.rawQuery(
                        "SELECT owner,conversation_id,run_id FROM runtime_checkpoints WHERE message_id=?",
                        arrayOf(assistant.id.value)).use { row ->
                        assertTrue(row.moveToFirst())
                        assertEquals(bound.ownerId, row.getString(0))
                        assertEquals(bound.conversationId.value, row.getString(1))
                        assertEquals(bound.runId.value, row.getString(2))
                    }
                    return session(config.model)
                }
            }
        }
        conversation = f.db.create("default"); f.db.draft(conversation.id, "keep until accepted")
        assertNotNull(f.controller.send(conversation, "accepted"))
        awaitState(f) { f.controller.active == null }
        assertEquals(1, factories.get()); assertEquals(1, opens.get())
        assertEquals("later-model", f.settings.load().model)
        val messages = f.db.messages(conversation.id)
        assertEquals(2, messages.size)
        assertEquals("fixture-model", messages.last().second.blocks.filterIsInstance<MessageBlock.Text>().single().text)
        assertEquals(RunStatus.COMPLETED, messages.last().second.status)
    }

    fun testConfigurationChangeDuringPreparationRejectsBeforeReceiptAndOpen() {
        lateinit var f: Fixture
        val opens = AtomicInteger()
        f = fresh { config ->
            f.settings.save(config.copy(model = "replacement-model"))
            ModelPort { opens.incrementAndGet(); session("must not run") }
        }
        val conversation = f.db.create("default")
        f.db.draft(conversation.id, "keep until fresh send")
        assertNull(f.controller.send(conversation, "keep until fresh send"))
        assertEquals(0, opens.get())
        assertTrue(f.db.messages(conversation.id).isEmpty())
        assertEquals("keep until fresh send", f.db.conversations().single().draft)
        assertTrue(f.controller.problem?.contains("提供商选择或配置已变化") == true)
    }

    fun testTransactionFailureNeverOpensPreparedPort() {
        val factories = AtomicInteger(); val opens = AtomicInteger()
        val f = fresh {
            factories.incrementAndGet()
            ModelPort { opens.incrementAndGet(); session("must not run") }
        }
        val conversation = f.db.create("default"); f.db.draft(conversation.id, "retained")
        f.db.writableDatabase.execSQL("CREATE TRIGGER reject_send BEFORE INSERT ON messages BEGIN SELECT RAISE(ABORT,'synthetic'); END")
        assertNull(f.controller.send(conversation, "retained"))
        assertEquals(1, factories.get()); assertEquals(0, opens.get())
        assertTrue(f.db.messages(conversation.id).isEmpty())
        assertEquals("retained", f.db.conversations().single().draft)
        assertNull(f.controller.active)
    }    private class CatalogProbe : ModelPort {
        var definitions = emptyList<ToolDefinition>()
        var history = emptyList<Message>()
        var batches = emptyList<List<ToolInvocation>>()
        var firstUsage: ModelEvent.Usage? = null
        val returned = java.util.concurrent.CopyOnWriteArrayList<List<ToolOutput>>()
        var beforeReturn: () -> Unit = {}
        var afterAccepted: () -> Unit = {}
        override fun open(history: List<Message>): ModelSession = error("catalog required")
        override fun open(history: List<Message>, tools: Set<ReadOnlyTool>): ModelSession = error("enum route forbidden")
        override fun open(history: List<Message>, definitions: List<ToolDefinition>): ModelSession {
            this.history=history.toList();this.definitions=definitions.toList()
            afterAccepted()
            return object : ModelSession {
                private var turn=0
                override fun next(outputs:List<ToolOutput>,sink:(ModelEvent)->Unit) {
                    if (turn == 0 && outputs.isEmpty()) firstUsage?.let { sink(it) }
                    if(outputs.isNotEmpty()) returned.add(outputs.toList())
                    if(turn<batches.size) {
                        batches[turn++].forEach {sink(ModelEvent.ToolReady(it))}
                        beforeReturn()
                    } else sink(ModelEvent.TextFinal("done","catalog complete"))
                }
                override fun cancel() {}
            }
        }
    }
    private data class Archived(val conversation:Conversation,val id:String,val original:List<Message>)
    private fun archived(f:Fixture):Archived {
        val conversation=f.db.create("default")
        repeat(3) {n ->
            val run=RunId(UUID.randomUUID().toString())
            val user=Message(MessageId("u-$run"),conversation.id,run,"user",
                listOf(MessageBlock.Text("u","事实$n ".repeat(100))),RunStatus.COMPLETED)
            val reply=user.copy(id=MessageId("a-$run"),role="assistant",status=RunStatus.RUNNING,
                blocks=listOf(MessageBlock.Text("a","确认$n ".repeat(100))))
            f.db.send(user,reply);f.db.save(reply.copy(status=RunStatus.COMPLETED))
        }
        val service=app.daiyu.android.context.ContextService(f.app,f.db)
        val original=service.displayHistory(conversation.id)
        val id=service.compress(conversation.id,app.daiyu.android.domain.context.CompressionPolicy(keepTurns=1),
            app.daiyu.android.context.ContextSummarizer {"事实摘要"})!!
        service.recordUsage(app.daiyu.android.domain.context.UsageRecord("receipt-${conversation.id.value}",
            original.first().runId.value,conversation.id.value,100,20))
        return Archived(conversation,id,original)
    }
    private fun call(id:String,name:String,args:org.json.JSONObject)=ToolInvocation(ToolCallId(id),name,args.toString())
    private fun finish(f:Fixture,conversation:Conversation) {
        assertNotNull(f.controller.send(conversation,"catalog check"))
        awaitState(f) {f.controller.active==null}
        assertEquals(RunStatus.COMPLETED,f.db.messages(conversation.id).last().second.status)
    }
    private fun ledger(f:Fixture)=app.daiyu.android.integration.tools.SQLiteToolLedgerStore(f.db).use {store ->
        store.transaction {it.toList()}
    }
    fun testInteractiveModeMissingPreferenceDoesNotAdvertiseOrExecuteQuestion() {
        val probe = CatalogProbe()
        val f = fresh(interactiveEnabled = null) { probe }
        val c = f.db.create("default")
        probe.batches = listOf(listOf(call("ungranted-question", "ask_user", org.json.JSONObject())))
        finish(f, c)
        assertFalse(f.controller.preferences.contains("interactive_mode"))
        assertTrue(probe.definitions.none { it.name == "ask_user" })
        assertFalse(org.json.JSONObject(probe.returned.single().single().output).getBoolean("ok"))
        assertTrue(ledger(f).isEmpty())
    }

    fun testInteractiveModeEnablingDuringPreparationOnlyAffectsNextRun() {
        val probe = CatalogProbe()
        lateinit var f: Fixture
        f = fresh(interactiveEnabled = false) {
            check(f.controller.preferences.edit().putBoolean("interactive_mode", true).commit())
            probe
        }
        val c = f.db.create("default")
        finish(f, c)
        assertTrue(probe.definitions.none { it.name == "ask_user" })
        finish(f, c)
        assertEquals(1, probe.definitions.count { it.name == "ask_user" })
    }

    fun testInteractiveModeDisablingDuringPreparationKeepsCurrentGrantOnly() {
        val probe = CatalogProbe()
        lateinit var f: Fixture
        f = fresh {
            check(f.controller.preferences.edit().putBoolean("interactive_mode", false).commit())
            probe
        }
        val c = f.db.create("default")
        finish(f, c)
        assertEquals(1, probe.definitions.count { it.name == "ask_user" })
        finish(f, c)
        assertTrue(probe.definitions.none { it.name == "ask_user" })
    }

    fun testCatalogDefinitionsUseFrozenSettingsAndHostLocators() {
        val probe=CatalogProbe();lateinit var f:Fixture
        f=fresh {config ->
            probe.afterAccepted = { f.settings.save(config.copy(batteryEnabled=false,deviceStatusEnabled=true)) }
            probe
        }
        val c=f.db.create("default");finish(f,c)
        assertEquals(expectedBaseCatalog,probe.definitions.map {it.name}.toSet())
        assertEquals(setOf("ask_user"),probe.definitions.filter {it.sideEffect}.map {it.name}.toSet())
        val usage=probe.definitions.single {it.name=="context_usage"}
        val reply=f.db.messages(c.id).last().second
        assertTrue(usage.description.contains(c.id.value));assertTrue(usage.description.contains(reply.runId.value))
        assertFalse(org.json.JSONObject(usage.parameters).getBoolean("additionalProperties"))
    }
    fun testCatalogBatchWaitsForTerminalAndPersistsOrderedReceipts() {
        val probe=CatalogProbe();val f=fresh {probe};val a=archived(f)
        val service=app.daiyu.android.context.ContextService(f.app,f.db)
        val prior=service.prepareSend(a.conversation.id).messages
        val ready=CountDownLatch(1);val release=CountDownLatch(1)
        probe.firstUsage = ModelEvent.Usage(100, 20, 0)
        probe.batches=listOf(listOf(call("archive","context_archive_page",org.json.JSONObject()
            .put("archive_id",a.id).put("offset",0).put("limit",2)),
            call("usage","context_usage",org.json.JSONObject().put("branch_id",a.conversation.id.value))))
        probe.beforeReturn={ready.countDown();check(release.await(10,TimeUnit.SECONDS))}
        try {
            assertNotNull(f.controller.send(a.conversation,"catalog check"))
            assertTrue(ready.await(5,TimeUnit.SECONDS))
            val pending=f.db.messages(a.conversation.id).last().second.blocks.filterIsInstance<MessageBlock.Tool>()
            assertEquals(2,pending.size);assertTrue(pending.all {it.result==null})
            assertTrue(ledger(f).isEmpty());assertTrue(probe.returned.isEmpty())
        } finally {release.countDown()}
        awaitState(f) {f.controller.active==null}
        val outputs=probe.returned.single()
        assertEquals(listOf(ToolCallId("archive"),ToolCallId("usage")),outputs.map {it.callId})
        val page=org.json.JSONObject(outputs[0].output);assertTrue(page.getBoolean("ok"))
        assertEquals(a.original.take(2),app.daiyu.android.context.ArchiveCodec.decode(
            page.getJSONArray("messages").toString().toByteArray(Charsets.UTF_8)))
        val usage = org.json.JSONObject(outputs[1].output)
        assertTrue(usage.getBoolean("ok"))
        assertEquals(1L, usage.getLong("requests"))
        assertEquals(1L, usage.getLong("reported_requests"))
        assertEquals(100L, usage.getLong("input_tokens"))
        assertEquals(20L, usage.getLong("output_tokens"))
        assertEquals(120L, usage.getLong("total_tokens"))
        assertEquals("provider_reported", usage.getString("source"))
        val reply=f.db.messages(a.conversation.id).last().second
        assertEquals(RunStatus.COMPLETED,reply.status)
        assertEquals(outputs.map {it.output},reply.blocks.filterIsInstance<MessageBlock.Tool>().map {it.result})
        assertEquals(prior,probe.history.dropLast(1))
        assertTrue(probe.definitions.single {it.name=="context_archive_page"}.description.contains(a.id))
        val entries=ledger(f);assertEquals(2,entries.size)
        assertTrue(entries.all {it.state==app.daiyu.android.domain.tools.LedgerState.CONFIRMED && !it.isError &&
            it.sessionId==reply.runId.value && it.taskId==reply.runId.value})
    }
    fun testCatalogRejectsAnotherConversationWithoutLeakingItsData() {
        val probe=CatalogProbe();val f=fresh {probe};val a=archived(f);val b=archived(f)
        probe.batches=listOf(listOf(call("foreign-archive","context_archive_page",org.json.JSONObject().put("archive_id",b.id)),
            call("foreign-usage","context_usage",org.json.JSONObject().put("branch_id",b.conversation.id.value))))
        finish(f,a.conversation)
        val outputs=probe.returned.single();assertEquals(2,outputs.size)
        outputs.forEach {
            val result=org.json.JSONObject(it.output);assertFalse(result.getBoolean("ok"))
            assertEquals("invalid_arguments_or_archive_unavailable",result.getString("error"))
            assertFalse(result.has("messages"));assertFalse(result.has("requests"));assertFalse(result.has("total"))
        }
        val entries=ledger(f);assertEquals(2,entries.size)
        assertTrue(entries.all {it.state==app.daiyu.android.domain.tools.LedgerState.CONFIRMED && it.isError})
        assertEquals(b.original,app.daiyu.android.context.ContextService(f.app,f.db).displayHistory(b.conversation.id))
    }
    fun testCatalogInvalidArgumentsRejectWholeBatchBeforeReservation() {
        val probe=CatalogProbe();val f=fresh {probe};val c=f.db.create("default")
        probe.batches=listOf(listOf(call("valid-first","get_battery_status",org.json.JSONObject()),
            call("forged-scope","context_usage",org.json.JSONObject().put("branch_id",c.id.value)
                .put("scope",org.json.JSONObject().put("conversation_id","forged-owner")))))
        finish(f,c)
        val outputs=probe.returned.single();assertEquals(2,outputs.size)
        outputs.forEach {
            val result=org.json.JSONObject(it.output);assertFalse(result.getBoolean("ok"));assertFalse(result.getBoolean("executed"))
            assertEquals("invalid_arguments",result.getString("error"))
        }
        assertTrue(ledger(f).isEmpty())
        assertEquals(outputs.map {it.output},f.db.messages(c.id).last().second.blocks.filterIsInstance<MessageBlock.Tool>().map {it.result})
    }

    private fun installSkill(f:Fixture, body:String="old-body", revision:String?=null):app.daiyu.android.skills.InstalledSkill =
        app.daiyu.android.skills.SkillStore(f.app).install(listOf(app.daiyu.android.skills.SkillBundle(mapOf(
            "SKILL.md" to "---\nname: demo\ndescription: Demo\n---\n$body".toByteArray(),
            "references/guide.md" to "guide".toByteArray()))),
            if(revision==null) emptyMap() else mapOf("demo" to revision)).single().also { installed ->
                val store = f.controller.assistantStore
                val before = checkNotNull(store.read("default"))
                val id = "user:${installed.name}"
                if (id !in before.enabledSkillIds) {
                    store.compareAndSet(before, before.copy(enabledSkillIds = before.enabledSkillIds + id))
                }
            }
    private fun skillArguments(revision:String)=org.json.JSONObject().put("id","user:demo").put("revision",revision)

    fun testSkillsActualCatalogFreezesIndexAndBodyButRejectsChangedResource() {
        val probe=CatalogProbe();val f=fresh {probe};val old=installSkill(f)
        val c=f.db.create("default");var changed=false
        probe.batches=listOf(listOf(call("skill-list","skills_list",org.json.JSONObject()),
            call("skill-body","skills_read",skillArguments(old.revision)),
            call("skill-resource","skills_read_resource",skillArguments(old.revision).put("path","references/guide.md"))))
        probe.beforeReturn={
            assertFalse(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())
            if(!changed) {
                changed=true
                app.daiyu.android.skills.SkillToolRegistry(f.app).enabled=false
                installSkill(f,"new-body",old.revision)
            }
        }
        finish(f,c)
        assertEquals(setOf("skills_list","skills_read","skills_read_resource"),probe.definitions.filter {it.name.startsWith("skills_")}.map {it.name}.toSet())
        assertEquals(setOf("ask_user"),probe.definitions.filter {it.sideEffect}.map {it.name}.toSet())
        assertTrue(probe.definitions.single {it.name=="skills_list"}.description.contains(old.revision))
        val outputs=probe.returned.single();assertEquals(3,outputs.size)
        val index=org.json.JSONObject(outputs[0].output);assertTrue(index.getBoolean("ok"))
        val listed = index.getJSONArray("skills")
        val demo = (0 until listed.length()).map { listed.getJSONObject(it) }.single { it.getString("id") == "user:demo" }
        assertEquals(old.revision, demo.getString("revision"))
        val ids = (0 until listed.length()).map { listed.getJSONObject(it).getString("id") }
        assertEquals(4, ids.size)
        assertEquals(setOf("builtin:self-improving-agent", "builtin:skill-creator", "builtin:skill-installer", "user:demo"), ids.toSet())
        val body=org.json.JSONObject(outputs[1].output);assertTrue(body.getBoolean("ok"));assertFalse(body.getBoolean("executable"))
        assertTrue(body.getString("content").endsWith("old-body"));assertFalse(body.getString("content").contains("new-body"))
        val resource=org.json.JSONObject(outputs[2].output);assertFalse(resource.getBoolean("ok"));assertEquals("skill_conflict",resource.getString("error"))
        val entries=ledger(f);assertEquals(3,entries.size);assertEquals(1,entries.count {it.isError})
        val reply=f.db.messages(c.id).last().second
        assertTrue(entries.all {it.sessionId==reply.runId.value && it.taskId==reply.runId.value})
        probe.batches=emptyList();probe.returned.clear();probe.beforeReturn={}
        finish(f,c)
        assertTrue(probe.definitions.none {it.name.startsWith("skills_")})
        assertEquals(3,ledger(f).size)
    }
    fun testSkillsInvalidWholeBatchDoesNotReserveAndInstallIsNotReachable() {
        val probe=CatalogProbe();val f=fresh {probe};val old=installSkill(f);val c=f.db.create("default")
        probe.batches=listOf(listOf(call("valid-skill","skills_read",skillArguments(old.revision)),
            call("bad-skill","skills_read_resource",skillArguments(old.revision).put("path","../secret"))))
        finish(f,c)
        probe.returned.single().forEach {output ->
            val result=org.json.JSONObject(output.output)
            assertFalse(result.getBoolean("ok"));assertFalse(result.getBoolean("executed"))
            assertEquals("preparation_failed",result.getString("error"))
        }
        assertTrue(ledger(f).isEmpty())
        probe.returned.clear();probe.batches=listOf(listOf(call("install","skills_install_from_github",org.json.JSONObject())))
        finish(f,c)
        assertEquals("unknown_tool",org.json.JSONObject(probe.returned.single().single().output).getString("error"))
        assertTrue(ledger(f).isEmpty())
        assertEquals(old.revision,app.daiyu.android.skills.SkillStore(f.app).list().single().revision)
    }
    fun testSkillsCaptureFailurePersistsFailedWithoutOpeningModelAndCanStartNextRun() {
        val opens=AtomicInteger();val f=fresh {ModelPort {opens.incrementAndGet();session("next-run")}}
        val broken=File(f.app.filesDir,"agent-builtin-skills-v1");broken.writeText("not a directory")
        val c=f.db.create("default")
        assertNotNull(f.controller.send(c,"accepted before worker capture"))
        awaitState(f) {f.controller.active==null}
        assertEquals(0,opens.get());assertEquals(RunStatus.FAILED,f.db.messages(c.id).last().second.status)
        assertTrue(ledger(f).isEmpty());assertTrue(broken.delete())
        finish(f,c);assertEquals(1,opens.get())
    }
    fun testStopBeforeSkillBatchExecutionPreventsReadAndLedgerReservation() {
        val probe=CatalogProbe();val f=fresh {probe};installSkill(f);val c=f.db.create("default")
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        probe.batches=listOf(listOf(call("stopped-skill","skills_list",org.json.JSONObject())))
        probe.beforeReturn={entered.countDown();check(release.await(10,TimeUnit.SECONDS))}
        try {
            assertNotNull(f.controller.send(c,"stop before read"))
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            main {f.controller.stop()}
            assertTrue(ledger(f).isEmpty())
        } finally {release.countDown()}
        awaitState(f) {f.controller.active==null}
        assertEquals(RunStatus.STOPPED,f.db.messages(c.id).last().second.status)
        assertTrue(ledger(f).isEmpty());assertTrue(probe.returned.isEmpty())
    }

}
