package app.daiyu.android

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import app.daiyu.android.domain.*
import app.daiyu.android.domain.question.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.domain.tools.LedgerState
import app.daiyu.android.integration.tools.SQLiteToolLedgerStore
import app.daiyu.android.question.PrivateQuestionStore
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
    private class FixtureApplication(base:Context):Application() {
        val balances = fixtureProviderBalances()
        init {attachBaseContext(base)}}
    private class Probe:CatalogFixturePort {
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
            finishExistingMainActivities()
            activity=null
            main {application.controller=original}
            fixtures.forEach {f ->
                f.c.close();f.app.balances.close();f.db.close();f.db.runtimeLockFile.delete()
                f.app.deleteDatabase("conversations.db");f.app.deleteDatabase("questions.db")
                f.app.getSharedPreferences("model-settings",0).edit().clear().commit()
                f.app.getSharedPreferences("conversation-ui",0).edit().clear().commit()
                val keys=java.security.KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
                java.util.Collections.list(keys.aliases()).filter {it==f.alias || it.startsWith("${f.alias}.")}.forEach {keys.deleteEntry(it)}
            }
        } finally {super.tearDown()}
    }
    private fun await(f:Fixture,description:String,diagnostic:()->String={""},predicate:()->Boolean) {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8)
        while(System.nanoTime()<deadline) {
            f.probe.failure.get()?.let {throw it}
            ui.waitForIdle()
            var done=false;main {done=predicate()}
            if(done) {instrumentation.waitForIdleSync();return}
            Thread.sleep(20)
        }
        fail("$description ${diagnostic()}")
    }
    private fun fresh(interactiveEnabled:Boolean=false,seed:(Fixture)->Unit={}):Fixture {
        val f=Fixture(instrumentation.targetContext);fixtures.add(f)
        f.settings.save(ModelSettings("https://example.invalid","question-fixture","synthetic-question-key",false))
        if (interactiveEnabled) check(f.app.getSharedPreferences("conversation-ui",0).edit().putBoolean("interactive_mode",true).commit())
        seed(f)
        f.c=ChatController(f.app,f.db,f.settings,f.app.getSharedPreferences("conversation-ui",0),{f.probe}, providerBalanceHost = { f.app.balances })
        await(f,"recovery ready") {f.c.ready};assertNull(f.c.problem);return f
    }
    private fun launch(f:Fixture,conversation:Conversation?=null) {
        finishExistingMainActivities()
        main {application.controller=f.c}
        activity=startMain()
        instrumentation.waitForIdleSync()
        if(conversation!=null) main {checkNotNull(activity).select(conversation)}
    }
    private fun waitForCard(readOnly:Boolean=false) {
        ui.waitUntil(timeoutMillis=8_000) {
            ui.onAllNodesWithTag("question-card").fetchSemanticsNodes().size==1 &&
                node("question-card").isDisplayed() &&
                (!readOnly || ui.onAllNodesWithTag("question-submit").fetchSemanticsNodes().isEmpty())
        }
        node("question-card").assertIsDisplayed()
        node("question-other").assertDoesNotExist()
        node("question-note").assertDoesNotExist()
        if(readOnly) {
            node("question-submit").assertDoesNotExist()
            node("question-option:0").assertDoesNotExist()
            node("question-option:1").assertDoesNotExist()
        }
    }
    // Invoke the attached semantics callback twice in one main-thread turn: recomposition must not
    // turn the duplicate-submit / stop race into a lookup of a new or already removed button.
    private fun submitAction():()->Boolean = node("question-submit").assertIsEnabled()
        .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    private fun send(f:Fixture):Message {
        setDraft("Ask for a route");node("chat-send").performClick()
        awaitTextSendResult(checkNotNull(activity))
        assertDraft("")
        return checkNotNull(f.c.active)
    }
    private fun checkpointOrNull(f:Fixture,m:Message):RunCheckpoint?=f.db.readableDatabase.rawQuery("SELECT payload FROM runtime_checkpoints WHERE message_id=?",arrayOf(m.id.value)).use {
        if(it.moveToFirst()) RunCheckpointCodec.decode(it.getString(0)) else null
    }
    private fun checkpoint(f:Fixture,m:Message):RunCheckpoint=checkNotNull(checkpointOrNull(f,m))
    private fun waitingDiagnostic(f:Fixture,owner:Message):String {
        val cardCount=ui.onAllNodesWithTag("question-card").fetchSemanticsNodes().size
        var details=""
        main {
            val saved=checkpointOrNull(f,owner)
            val identity=saved?.let {QuestionIdentity(owner.conversationId.value,owner.runId.value,it.owner,CALL)}
            val record=identity?.let {id->PrivateQuestionStore(f.app).use {it.get(id.storageKey)}}
            @Suppress("UNCHECKED_CAST")
            val shown=MainActivity::class.java.getDeclaredField("shown").apply {isAccessible=true}.get(checkNotNull(activity)) as List<Pair<Long,Message>>
            val message=shown.singleOrNull {it.second.id==owner.id}?.second
            val tools=message?.blocks?.filterIsInstance<MessageBlock.Tool>().orEmpty()
            val projection=tools.singleOrNull()?.let {f.c.questionProjection(checkNotNull(message),it.invocation)}
            details="checkpointPresent=${saved!=null} phase=${saved?.phase} pendingCallMatches=${saved?.pending?.callId==ToolCallId(CALL)} " +
                "checkpointTools=${saved?.tools?.size} recordPresent=${record!=null} answered=${record?.answer!=null} interrupted=${record?.interrupted} " +
                "activePresent=${f.c.active!=null} activeMatchesOwner=${f.c.active?.id==owner.id} controllerReady=${f.c.ready} problemPresent=${f.c.problem!=null} " +
                "ownerTurnPresent=${message!=null} ownerToolCount=${tools.size} projectionPresent=${projection!=null} canAnswer=${projection?.canAnswer} " +
                "bindingPresent=${projection?.binding!=null} composeCardCount=$cardCount"
        }
        return details
    }
    private fun row(f:Fixture,id:QuestionIdentity):QuestionRecord=PrivateQuestionStore(f.app).use {checkNotNull(it.get(id.storageKey))}
    private fun drainWorker(f:Fixture) {
        val worker=ChatController::class.java.getDeclaredField("executor").apply {isAccessible=true}.get(f.c) as Executor
        val done=CountDownLatch(1);worker.execute {done.countDown()}
        assertTrue("same Controller slot must be free while waiting",done.await(5,TimeUnit.SECONDS))
        instrumentation.waitForIdleSync();f.probe.failure.get()?.let {throw it}
    }
    private fun waiting(f:Fixture,owner:Message):QuestionIdentity {
        await(f,"question checkpoint WAITING_USER",{waitingDiagnostic(f,owner)}) {checkpointOrNull(f,owner)?.phase==ExecutionPhase.WAITING_USER}
        val pending=checkpoint(f,owner)
        assertEquals(owner.id,pending.message.id);assertEquals(owner.runId,pending.message.runId)
        assertEquals(owner.conversationId,pending.message.conversationId)
        val identity=QuestionIdentity(owner.conversationId.value,owner.runId.value,pending.owner,CALL)
        await(f,"durable public question record",{waitingDiagnostic(f,owner)}) {PrivateQuestionStore(f.app).use {it.get(identity.storageKey)!=null}}
        waitForCard()
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
    @Suppress("UNCHECKED_CAST")
    private fun assertExactQuestionOwner(f: Fixture, shown: Message, call: ToolInvocation, expected: QuestionIdentity) {
        val records = ChatController::class.java.getDeclaredField("questionHistory").apply { isAccessible = true }
            .get(f.c) as MutableMap<String, Pair<QuestionIdentity, QuestionRecord>>
        val original = checkNotNull(records[expected.storageKey])
        val unrelated = QuestionIdentity(expected.conversationId, expected.runId, "unrelated-owner", expected.callId)
        records[unrelated.storageKey] = unrelated to original.second.copy(callId = unrelated.storageKey)
        try {
            assertEquals(expected, f.c.questionProjection(shown, call)!!.identity)
            records.remove(expected.storageKey)
            assertNull(f.c.questionProjection(shown, call))
        } finally {
            records[expected.storageKey] = original
            records.remove(unrelated.storageKey)
        }
    }

    fun testPublicQuestionReleasesWorkerAndDurableAnswerContinuesExactlyOnce() {
        val f=fresh(interactiveEnabled=true);launch(f);val owner=send(f);val id=waiting(f,owner)
        main {
            val shown = f.db.messages(owner.conversationId).last().second
            val call = shown.blocks.filterIsInstance<MessageBlock.Tool>().single().invocation
            assertNotNull(f.c.questionProjection(shown, call))
            assertExactQuestionOwner(f, shown, call, id)
            assertNull(f.c.questionProjection(shown.copy(id = MessageId("different-owner")), call))
            assertNull(f.c.questionProjection(shown, call.copy(arguments = ARGS.replace("Route choice", "Changed question"))))
            assertNull(f.c.questionProjection(shown.copy(runId = RunId("different-run")), call))
        }
        node("question-option:0").performClick().assertIsSelected()
        val originalCardId=node("question-card").fetchSemanticsNode().id
        main {MainActivity::class.java.getDeclaredMethod("refresh").apply {isAccessible=true}.invoke(checkNotNull(activity))}
        ui.waitForIdle()
        assertEquals("same question refresh retains the Compose card",originalCardId,node("question-card").fetchSemanticsNode().id)
        node("question-option:0").assertIsSelected()
        recreate();waiting(f,owner)
        main { f.c.interactiveMode.setEnabled(false) }
        assertFalse(f.c.interactiveMode.snapshot().enabled)
        val durable=AtomicInteger()
        f.probe.beforeContinuation={outputs ->
            assertEquals(QuestionAnswer.Selected("two"),row(f,id).answer)
            val saved=checkpoint(f,owner);assertNull(saved.pending)
            assertEquals(outputs.single().output,saved.message.blocks.filterIsInstance<MessageBlock.Tool>().single().result)
            val entries=SQLiteToolLedgerStore(f.db).use {store->store.transaction {it.toList()}}
            assertEquals(1,entries.size);assertEquals(LedgerState.CONFIRMED,entries.single().state);durable.incrementAndGet()
        }
        node("question-option:1").performClick().assertIsSelected()
        val submit=submitAction()
        main {submit();submit()}
        await(f,"answered completion") {f.c.active==null};drainWorker(f)
        assertEquals(1,durable.get());assertEquals(1,f.probe.opens.get());assertEquals(2,f.probe.nexts.get())
        assertEquals(QuestionAnswer.Selected("two"),row(f,id).answer)
        PrivateQuestionStore(f.app).use {assertEquals(1,it.records().size)}
        val saved=checkpoint(f,owner);assertEquals(ExecutionPhase.TERMINAL,saved.phase);assertNull(saved.pending);assertEquals(RunStatus.COMPLETED,saved.message.status)
    }
    fun testStopFencesPendingCardAndNeverContinuesModel() {
        val f=fresh(interactiveEnabled=true);launch(f);val owner=send(f);val id=waiting(f,owner)
        node("question-option:1").performClick().assertIsSelected()
        val old=submitAction()
        main {assertTrue(f.c.stop(owner));old();old()}
        await(f,"durable stop") {f.c.active==null};drainWorker(f)
        assertEquals(1,f.probe.opens.get());assertEquals(1,f.probe.nexts.get())
        val record=row(f,id);assertNull(record.answer);assertTrue(record.interrupted)
        val saved=checkpoint(f,owner);assertEquals(RunStatus.STOPPED,saved.message.status);assertNull(saved.pending)
    }
    fun testStopAfterAnswerQueuedBeforeResumeDrainsOriginalContinuation() {
        val f=fresh(interactiveEnabled=true);launch(f);val owner=send(f);val id=waiting(f,owner)
        val worker=ChatController::class.java.getDeclaredField("executor").apply {isAccessible=true}.get(f.c) as Executor
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        worker.execute {entered.countDown();check(release.await(10,TimeUnit.SECONDS))}
        assertTrue(entered.await(5,TimeUnit.SECONDS))
        try {
            node("question-option:1").performClick().assertIsSelected()
            node("question-submit").assertIsEnabled().performClick()
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
        val f=fresh(interactiveEnabled=true);launch(f);val owner=send(f);val id=waiting(f,owner)
        PrivateQuestionStore(f.app).use {store ->
            store.writableDatabase.execSQL("CREATE TRIGGER reject_question_stop BEFORE UPDATE OF interrupted ON questions WHEN NEW.interrupted=1 BEGIN SELECT RAISE(ABORT,'injected'); END")
            try {
                main {assertTrue(f.c.stop(owner))}
                await(f,"failed durable interruption retains retry") {f.c.canRetryQuestionCleanup(owner)}
                assertNotNull(f.c.active);assertEquals(ExecutionPhase.WAITING_USER,checkpoint(f,owner).phase)
                assertFalse(row(f,id).interrupted);assertNull(row(f,id).answer)
                assertNull(f.c.send(null,"must remain fenced"))
            } finally {store.writableDatabase.execSQL("DROP TRIGGER reject_question_stop")}
        }
        node("question-retry-cleanup").performClick()
        await(f,"retry reaches durable stop") {f.c.active==null};drainWorker(f)
        assertNull(f.c.problem);assertTrue(row(f,id).interrupted)
        assertEquals(RunStatus.STOPPED,checkpoint(f,owner).message.status)
        assertEquals(1,f.probe.nexts.get());assertEquals(1,f.probe.opens.get())
    }
    fun testCloseFromMainDrainsPendingBeforeReleasingStore() {
        val f=fresh(interactiveEnabled=true);launch(f);val owner=send(f);val id=waiting(f,owner)
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
                runtime.recoverPending();val conversation=f.db.create("default");val run=RunId("lost-run")
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
        waitForCard(readOnly=true)
        if (answer == null) {
            node("question-toggle").assertTextContains("提问已中断 · Route choice")
            ui.onNodeWithText("提问已中断，当前不可作答").assertExists()
        } else {
            node("question-toggle").assertTextContains("已回答 · Route choice").performClick()
            ui.onNodeWithText("已选择：Second route").assertExists()
            node("question-option:0").assertDoesNotExist()
            node("question-option:1").assertDoesNotExist()
            node("question-submit").assertDoesNotExist()
        }
        drainWorker(f);assertNull(f.c.active);assertEquals(0,f.probe.opens.get());assertEquals(0,f.probe.nexts.get())
        val saved=checkpoint(f,old.message);assertEquals(RunStatus.INTERRUPTED,saved.message.status);assertEquals(ExecutionPhase.TERMINAL,saved.phase);assertNull(saved.pending)
        assertTrue(saved.owner!=old.owner);assertEquals(ToolDisposition.UNKNOWN,saved.tools.single().disposition)
        assertEquals(answer,row(f,id).answer);if(answer==null) assertTrue(row(f,id).interrupted)
        main {
            val shown = f.db.messages(old.message.conversationId).last().second
            val projection = f.c.questionProjection(shown, shown.blocks.filterIsInstance<MessageBlock.Tool>().single().invocation)
            assertNotNull(projection); assertFalse(projection!!.canAnswer); assertNull(projection.binding)
            assertEquals(old.owner, projection.identity.ownerId)
            assertExactQuestionOwner(f, shown, shown.blocks.filterIsInstance<MessageBlock.Tool>().single().invocation, id)
        }
        recreate();waitForCard(readOnly=true)
        drainWorker(f);assertEquals(0,f.probe.opens.get());assertEquals(0,f.probe.nexts.get())
    }
    fun testRecoveryOfPendingQuestionOnlyDisplaysAndDoesNotResend()=recovery(null)
    fun testRecoveryOfSavedAnswerOnlyDisplaysAndDoesNotAuthorizeContinuation()=recovery(QuestionAnswer.Selected("two"))
}
