package app.daiyu.android.storage

import android.content.ContentValues
import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.*
import app.daiyu.android.testContext
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

@Suppress("DEPRECATION")
class SQLiteRuntimeStoreDatabaseTest:InstrumentationTestCase() {
    private val databases=mutableListOf<ConversationDatabase>()
    private val stores=mutableListOf<SQLiteRuntimeStore>()
    private val contexts=mutableListOf<android.content.Context>()
    private fun fresh():ConversationDatabase {
        val c=testContext(instrumentation.targetContext,"atomic-${UUID.randomUUID()}-");contexts+=c
        return ConversationDatabase(c).also {databases+=it}
    }
    private fun lease(db:ConversationDatabase)=SQLiteRuntimeStore.acquire(db).also {stores+=it;it.recoverPending()}
    private fun seed(db:ConversationDatabase):RunCheckpoint {
        val c=db.create("default").id;val run=RunId(UUID.randomUUID().toString())
        val user=Message(MessageId("u-${run.value}"),c,run,"user",listOf(MessageBlock.Text("u","question")),RunStatus.COMPLETED)
        val assistant=user.copy(id=MessageId("a-${run.value}"),role="assistant",status=RunStatus.RUNNING,blocks=emptyList())
        db.send(user,assistant)
        return RunCheckpoint(assistant,listOf(user),ExecutionPhase.BOUNDARY,owner="owner-${run.value}",revision=1)
    }
    private fun rejects(work:()->Unit) {try {work();fail("must reject")} catch(_:Exception) {}}
    private fun release(db:ConversationDatabase,store:SQLiteRuntimeStore) {db.close();store.close()}
    override fun tearDown() {
        try {databases.forEach {it.close()};stores.forEach {it.close()};contexts.forEach {it.deleteDatabase("conversations.db")}}
        finally {super.tearDown()}
    }
    fun testAtomicCommitCasReadAndLegacyFence() {
        val db=fresh();val runtime=lease(db);val first=seed(db)
        runtime.commitCheckpoint(first);val before=db.messages(first.message.conversationId)
        rejects {runtime.commitCheckpoint(first.copy(owner="foreign",revision=2))}
        rejects {runtime.commitCheckpoint(first.copy(revision=3))}
        rejects {db.save(first.message.copy(blocks=listOf(MessageBlock.Text("bad","late"))))}
        rejects {db.recover()}
        assertEquals(before,db.messages(first.message.conversationId))
        rejects {runtime.reconcileCheckpoint(first)}
        assertEquals(first,runtime.readCommittedCheckpoint(first))
        val done=first.copy(message=first.message.copy(status=RunStatus.COMPLETED),phase=ExecutionPhase.TERMINAL,revision=2)
        runtime.commitCheckpoint(done)
        assertEquals(done,runtime.readCommittedCheckpoint(done))
        rejects {runtime.commitCheckpoint(done.copy(revision=3))}
        assertEquals(done.message,db.messages(first.message.conversationId).last().second)
    }
    fun testCheckpointAndMessageFailuresRollbackBothRowsAfterReopen() {
        val db=fresh();var runtime=lease(db);val first=seed(db)
        db.writableDatabase.execSQL("CREATE TRIGGER fail_checkpoint BEFORE INSERT ON runtime_checkpoints BEGIN SELECT RAISE(ABORT,'checkpoint failure'); END")
        rejects {runtime.commitCheckpoint(first)}
        assertNull(runtime.readCommittedCheckpoint(first))
        assertEquals(first.message,db.messages(first.message.conversationId).last().second)
        db.writableDatabase.execSQL("DROP TRIGGER fail_checkpoint")
        runtime.commitCheckpoint(first)
        db.writableDatabase.execSQL("CREATE TRIGGER fail_message BEFORE UPDATE ON messages BEGIN SELECT RAISE(ABORT,'message failure'); END")
        rejects {runtime.commitCheckpoint(first.copy(message=first.message.copy(blocks=listOf(MessageBlock.Text("t","new"))),revision=2))}
        assertEquals(first,runtime.readCommittedCheckpoint(first.copy(revision=2)))
        db.writableDatabase.execSQL("DROP TRIGGER fail_message")
        release(db,runtime);runtime=SQLiteRuntimeStore.acquire(db).also {stores+=it}
        val recovered=runtime.reconcileCheckpoint(first)
        assertEquals(RunStatus.INTERRUPTED,recovered.message.status)
        assertTrue(recovered.message.blocks.isEmpty())
        assertEquals(recovered,runtime.reconcileCheckpoint(first))
    }
    fun testRecoveryUsesLatestPersistedFieldsNotStaleCallerAndKeepsUnknown() {
        val db=fresh();val runtime=lease(db);val first=seed(db);val call=ToolInvocation(ToolCallId("call"),"read","{}")
        runtime.commitCheckpoint(first)
        val active=first.copy(message=first.message.copy(blocks=listOf(MessageBlock.Text("t","accepted"),MessageBlock.Tool(call))),
            phase=ExecutionPhase.TOOL_EXECUTING,tools=listOf(ToolRecord(call,ToolDisposition.IN_FLIGHT)),requestUnconfirmed=true,revision=2,
            request=RequestCheckpoint("request", "attempt", first.owner, RequestOperation.NEXT),
            usage=listOf(RequestUsageCheckpoint(RequestCheckpoint("request", "attempt", first.owner, RequestOperation.NEXT), ModelEvent.Usage(null, 0, 7))))
        runtime.commitCheckpoint(active)
        release(db,runtime)
        val next=SQLiteRuntimeStore.acquire(db).also {stores+=it}
        val result=next.reconcileCheckpoint(first)
        assertEquals(RunStatus.INTERRUPTED,result.message.status);assertEquals(active.message.blocks,result.message.blocks)
        assertEquals(ToolDisposition.UNKNOWN,result.tools.single().disposition);assertTrue(result.requestUnconfirmed)
        assertEquals(3L,result.revision);assertTrue(result.owner!=active.owner)
        assertEquals(active.owner, SQLiteRuntimeStore.readQuestionCheckpoint(db.readableDatabase, first.message.id.value)!!.second)
        assertEquals(active.request,result.request);assertEquals(active.owner,result.request!!.owner)
        assertEquals(active.usage,result.usage)
        assertEquals(result,next.reconcileCheckpoint(first))
        next.recoverPending();rejects {next.commitCheckpoint(active.copy(revision=3))}
        rejects {runtime.commitCheckpoint(active.copy(revision=3))}
    }
    fun testUsageSharesAtomicCasAndFailedUpdatePreservesLastCommittedSnapshot() {
        val db=fresh();val runtime=lease(db);val first=seed(db)
        val request=RequestCheckpoint("request", "attempt", first.owner, RequestOperation.NEXT)
        val accepted=first.copy(request=request,usage=listOf(RequestUsageCheckpoint(request,ModelEvent.Usage(10,20,5))))
        runtime.commitCheckpoint(accepted)
        val replacement=accepted.copy(revision=2,usage=listOf(RequestUsageCheckpoint(
            request.copy(attemptId="resume",operation=RequestOperation.RESUME),ModelEvent.Usage(null,0,0))))
        rejects {runtime.commitCheckpoint(replacement.copy(owner="foreign"))}
        rejects {runtime.commitCheckpoint(replacement.copy(revision=3))}
        db.writableDatabase.execSQL("CREATE TRIGGER fail_usage BEFORE UPDATE ON runtime_checkpoints BEGIN SELECT RAISE(ABORT,'usage failure'); END")
        rejects {runtime.commitCheckpoint(replacement)}
        assertEquals(accepted,runtime.readCommittedCheckpoint(replacement))
        assertEquals(first.message,db.messages(first.message.conversationId).last().second)
        db.writableDatabase.execSQL("DROP TRIGGER fail_usage")
        runtime.commitCheckpoint(replacement)
        assertEquals(replacement,runtime.readCommittedCheckpoint(replacement))
        release(db,runtime)
        val reopened=SQLiteRuntimeStore.acquire(db).also {stores+=it}
        val recovered=reopened.reconcileCheckpoint(first)
        assertEquals(replacement.usage,recovered.usage)
        assertEquals(RunStatus.INTERRUPTED,recovered.message.status)
    }
    private fun payload(db:ConversationDatabase, id:MessageId):String = db.readableDatabase.rawQuery(
        "SELECT payload FROM runtime_checkpoints WHERE message_id=?",arrayOf(id.value))
        .use {check(it.moveToFirst());it.getString(0)}
    private fun transport(sequence:Long, text:String="accepted") = TransportCheckpoint("resp_sqlite",sequence,
        JSONObject().put("response_id","resp_sqlite").put("sequence",sequence)
            .put("parts",JSONObject().put("m",JSONObject().put("0",text)))
            .put("summaries",JSONObject()).put("calls",JSONArray()).put("chars",text.length).toString())

    fun testTransportCheckpointRoundTripAndProcessRecoveryOnlyInterrupts() {
        val db=fresh();val runtime=lease(db);val first=seed(db)
        val request=RequestCheckpoint("request","post-attempt",first.owner,RequestOperation.NEXT)
        val accepted=first.copy(message=first.message.copy(blocks=listOf(MessageBlock.Text("m","accepted")),
            protocolItems=listOf("{\"type\":\"message\",\"id\":\"m\"}")),phase=ExecutionPhase.STREAMING,
            request=request,requestUnconfirmed=true,usageRequests=listOf(request),
            usage=listOf(RequestUsageCheckpoint(request,ModelEvent.Usage(10,2))),transport=transport(2))
        runtime.commitCheckpoint(accepted)
        val resumed=accepted.copy(revision=2,request=request.copy(attemptId="get-attempt",operation=RequestOperation.RESUME),
            transport=transport(3))
        runtime.commitCheckpoint(resumed)
        val raw=payload(db,first.message.id)
        assertEquals(7,JSONObject(raw).getInt("version"))
        assertEquals(resumed,RunCheckpointCodec.decode(raw))
        assertEquals(resumed,runtime.readCommittedCheckpoint(resumed))
        assertEquals(resumed.message,db.messages(first.message.conversationId).last().second)
        release(db,runtime)
        // Reacquiring the OS lease is process recovery, NOT a transport-resume admission.
        // Exercise the actual Runner -> ProcessRecovery entry with a model/tool trap.
        val next=SQLiteRuntimeStore.acquire(db).also {stores+=it}
        assertEquals(resumed,RunCheckpointCodec.decode(payload(db,first.message.id)))
        var opens=0;var tools=0
        val recovery=ChatRunner(ModelPort {opens++;error("recovery must not reconstruct a session")},
            object:ConversationStore {override fun save(message:Message)=error("atomic recovery only")},
            BatteryTool {tools++;error("recovery must not execute tools")},RunnerOptions(runtimeStore=next)){}
        val recoveredMessage=recovery.recover(first) // Deliberately stale caller, latest SQLite row wins.
        next.recoverPending()
        val recovered=next.reconcileCheckpoint(first)
        assertEquals(recovered.message,recoveredMessage);assertEquals(0,opens);assertEquals(0,tools)
        assertEquals(ExecutionPhase.TERMINAL,recovered.phase)
        assertEquals(RunStatus.INTERRUPTED,recovered.message.status)
        assertEquals(resumed.message.blocks,recovered.message.blocks)
        assertEquals(resumed.message.protocolItems,recovered.message.protocolItems)
        assertEquals(resumed.transport,recovered.transport)
        assertEquals(resumed.request,recovered.request)
        assertEquals(resumed.usageRequests,recovered.usageRequests)
        assertEquals(resumed.usage,recovered.usage)
        assertEquals(3L,recovered.revision);assertTrue(recovered.owner!=resumed.owner)
        assertEquals(recovered,RunCheckpointCodec.decode(payload(db,first.message.id)))
        assertEquals(recovered,next.reconcileCheckpoint(first))
        rejects {next.commitCheckpoint(resumed.copy(revision=3))}
    }

    fun testExistingV6CheckpointWithoutTransportSurvivesReopenAndRecovery() {
        val db=fresh();val runtime=lease(db);val first=seed(db)
        val request=RequestCheckpoint("old-request","old-attempt",first.owner,RequestOperation.NEXT)
        val old=first.copy(phase=ExecutionPhase.STREAMING,request=request,requestUnconfirmed=true,
            message=first.message.copy(blocks=listOf(MessageBlock.Text("m","v6 partial"))),
            usageRequests=listOf(request),usage=listOf(RequestUsageCheckpoint(request,ModelEvent.Usage(4,1))))
        runtime.commitCheckpoint(old)
        val v6=JSONObject(payload(db,first.message.id))
        assertEquals(6,v6.getInt("version"));assertFalse(v6.has("transport"))
        assertEquals(old,RunCheckpointCodec.decode(v6.toString()))
        release(db,runtime)
        val next=lease(db)
        val recovered=next.reconcileCheckpoint(first)
        assertEquals(RunStatus.INTERRUPTED,recovered.message.status)
        assertEquals(old.message.blocks,recovered.message.blocks)
        assertEquals(old.request,recovered.request);assertEquals(old.usage,recovered.usage)
        assertEquals(old.usageRequests,recovered.usageRequests);assertNull(recovered.transport)
        val persisted=JSONObject(payload(db,first.message.id))
        assertEquals(6,persisted.getInt("version"));assertFalse(persisted.has("transport"))
        assertEquals(recovered,RunCheckpointCodec.decode(persisted.toString()))
    }

    fun testAcknowledgedTransportThenSupplementOpensFreshSessionWithoutOldCursor() {
        val db=fresh();val runtime=lease(db);val first=seed(db)
        val writes=CopyOnWriteArrayList<RunCheckpoint>()
        val paused=CountDownLatch(1)
        val admitted=CountDownLatch(1)
        val resultOfAdmission=java.util.concurrent.atomic.AtomicReference<SupplementAdmission>()
        // Keep the real SQLite codec/CAS/atomic supplement writer; a memory-only store would
        // miss the invalid OPEN + previous transport checkpoint combination this guards.
        val observed=object:AtomicSupplementRuntimeStore by runtime {
            override fun commitCheckpoint(checkpoint:RunCheckpoint) {
                runtime.commitCheckpoint(checkpoint)
                writes.add(RunCheckpointCodec.decode(payload(db,first.message.id)))
                if(checkpoint.phase==ExecutionPhase.PAUSED) paused.countDown()
            }
        }
        val extra=first.history.single().copy(id=MessageId("extra-${first.message.runId.value}"),
            blocks=listOf(MessageBlock.Text("u","additional question")))
        var opens=0;var resumes=0
        lateinit var runner:ChatRunner
        val port=ModelPort { history ->
            opens++
            if(opens==1) object:CheckpointedResumableModelSession {
                override val canResume=true
                override fun next(outputs:List<ToolOutput>,sink:(ModelEvent)->Unit)=error("must use ACK")
                override fun resume(sink:(ModelEvent)->Unit)=error("must not resume an abandoned session")
                override fun resumeAcknowledged(sink:(ModelEventBatch)->ModelDeliveryAck) {resumes++;error("must not GET after supplement")}
                override fun nextAcknowledged(outputs:List<ToolOutput>,sink:(ModelEventBatch)->ModelDeliveryAck) {
                    assertEquals(ModelDeliveryAck.ACCEPTED,sink(ModelEventBatch(
                        listOf(ModelEvent.TextDelta("m","accepted")),transport(1))))
                    assertTrue(runner.pause())
                }
                override fun disconnectTransport() {}
                override fun cancel() {}
            } else {
                assertEquals(2,opens)
                assertTrue(MessageHistory.expand(history).any {it.id==extra.id})
                object:ModelSession {
                    override fun next(outputs:List<ToolOutput>,sink:(ModelEvent)->Unit) {sink(ModelEvent.TextFinal("m","fresh answer"))}
                    override fun cancel() {}
                }
            }
        }
        runner=ChatRunner(port,object:ConversationStore {override fun save(message:Message)=error("atomic only")},
            BatteryTool {error("no tools")},RunnerOptions(runtimeStore=observed)){}
        val task=FutureTask {runner.run(first.message,first.history)}
        val worker=Thread(task).apply {isDaemon=true;start()}
        try {
            assertTrue("ACKed run never reached paused boundary",paused.await(10,TimeUnit.SECONDS))
            assertTrue(writes.any {it.transport?.sequence==1L})
            assertTrue(runner.admitSupplement(extra) {resultOfAdmission.set(it);admitted.countDown()})
            assertTrue(admitted.await(10,TimeUnit.SECONDS))
            assertTrue(resultOfAdmission.get() is SupplementAdmission.Accepted)
            assertTrue(runner.resume())
            val answer=task.get(10,TimeUnit.SECONDS)
            assertEquals(RunStatus.COMPLETED,answer.status)
            assertEquals(2,opens);assertEquals(0,resumes)
            val openWrites=writes.filter {it.request?.operation==RequestOperation.OPEN}
            assertEquals(2,openWrites.map {it.request!!.requestId}.distinct().size)
            assertTrue(openWrites.all {it.transport==null})
            assertEquals(extra,db.messages(extra.conversationId).single {it.second.id==extra.id}.second)
            val done=RunCheckpointCodec.decode(payload(db,first.message.id))
            assertEquals(answer,done.message);assertNull(done.transport)
        } finally {runner.stop();worker.join(10000);assertFalse("runner worker leaked",worker.isAlive)}
    }

    fun testExistingTerminalWinsRecoveryAndDeleteCascades() {
        val db=fresh();val runtime=lease(db);val first=seed(db);runtime.commitCheckpoint(first)
        db.writableDatabase.update("messages",ContentValues().apply {put("status","COMPLETED")},"id=?",arrayOf(first.message.id.value))
        release(db,runtime)
        val next=SQLiteRuntimeStore.acquire(db).also {stores+=it}
        val result=next.reconcileCheckpoint(first);assertEquals(RunStatus.COMPLETED,result.message.status)
        assertEquals(result,next.reconcileCheckpoint(first));next.recoverPending()
        db.delete(first.message.conversationId)
        assertEquals(0,db.readableDatabase.rawQuery("SELECT count(*) FROM runtime_checkpoints",null).use {it.moveToFirst();it.getInt(0)})
        assertNull(next.readCommittedCheckpoint(result));rejects {next.commitCheckpoint(first.copy(revision=2))}
        assertTrue(db.conversations().isEmpty())
    }
    fun testSameProcessLockBusyDoesNotOpenOrReleaseSecondDatabase() {
        val db=fresh();val runtime=lease(db);val first=seed(db);runtime.commitCheckpoint(first)
        val context=contexts.last();val other=ConversationDatabase(context).also {databases+=it}
        assertFalse(other.runtimeLockFile.path.isBlank())
        rejects {SQLiteRuntimeStore.acquire(other)};other.close()
        rejects {SQLiteRuntimeStore.acquire(other)}
        assertEquals(first,runtime.readCommittedCheckpoint(first))
        release(db,runtime)
        val replacement=lease(other)
        assertEquals(RunStatus.INTERRUPTED,other.messages(first.message.conversationId).last().second.status)
        rejects {runtime.commitCheckpoint(first.copy(revision=2))};replacement.close()
    }
    fun testRunnerOnlyAtomicWriterAndCheckpointBeforeEveryTool() {
        val db=fresh();val runtime=lease(db);val first=seed(db);var round=0;var executions=0
        val call=ToolInvocation(ToolCallId("read-call"),"read","{}")
        var terminalAttempts=0;var reads=0
        val losingReceipt=object:AtomicRuntimeStore by runtime {
            override fun commitCheckpoint(checkpoint:RunCheckpoint) {
                if(checkpoint.phase==ExecutionPhase.TERMINAL) terminalAttempts++
                runtime.commitCheckpoint(checkpoint)
                if(checkpoint.phase==ExecutionPhase.TERMINAL) throw IllegalStateException("lost receipt")
            }
            override fun readCommittedCheckpoint(checkpoint:RunCheckpoint):RunCheckpoint? {
                reads++;return runtime.readCommittedCheckpoint(checkpoint)
            }
            override fun reconcileCheckpoint(checkpoint:RunCheckpoint):RunCheckpoint=error("must not recover live owner")
        }
        val runner=ChatRunner(ModelPort {object:ModelSession {
            override fun next(outputs:List<ToolOutput>,sink:(ModelEvent)->Unit) {
                if(round++==0) sink(ModelEvent.ToolReady(call)) else {
                    assertEquals("saved",outputs.single().output);sink(ModelEvent.TextFinal("t","answer"))
                }
            }
            override fun cancel() {}
        }},object:ConversationStore {override fun save(message:Message)=error("legacy must not write")},BatteryTool {
            val raw=db.readableDatabase.rawQuery("SELECT payload FROM runtime_checkpoints",null).use {it.moveToFirst();it.getString(0)}
            assertEquals(ToolDisposition.IN_FLIGHT,RunCheckpointCodec.decode(raw).tools.single().disposition)
            executions++;"saved"
        },RunnerOptions(runtimeStore=losingReceipt)){}
        val result=runner.run(first.message,first.history);assertEquals(RunStatus.COMPLETED,result.status);assertEquals(1,executions);assertEquals(1,terminalAttempts);assertEquals(1,reads)
        assertEquals(result,db.messages(result.conversationId).last().second)
        val raw=db.readableDatabase.rawQuery("SELECT payload FROM runtime_checkpoints",null).use {it.moveToFirst();it.getString(0)}
        val done=RunCheckpointCodec.decode(raw);assertEquals(result,done.message);assertEquals(ExecutionPhase.TERMINAL,done.phase)
        release(db,runtime);val next=lease(db)
        assertEquals(done,next.reconcileCheckpoint(done));assertEquals(1,executions)
    }
    fun testLargeMessageAndCheckpointReadAreLosslessBeyondCursorWindow() {
        val db=fresh();val runtime=lease(db);val first=seed(db)
        val text="x".repeat(2_200_000)+"末尾😀"
        val message=first.message.copy(blocks=listOf(MessageBlock.Text("large",text)),error="one\u0000two")
        val full=first.copy(message=message,history=first.history+message.copy(status=RunStatus.COMPLETED))
        runtime.commitCheckpoint(full)
        assertEquals(full,runtime.readCommittedCheckpoint(full))
        assertEquals(message,db.messages(message.conversationId).last().second)
        assertEquals(message,app.daiyu.android.context.ContextService.read(db.readableDatabase,message.conversationId).last())
        release(db,runtime);val next=lease(db)
        val recovered=next.reconcileCheckpoint(first)
        assertEquals(text,(recovered.message.blocks.single() as MessageBlock.Text).text)
        assertEquals(full.history,recovered.history)
    }
    fun testVersionFiveUpgradePreservesSeqHistoryAndDraft() {
        val db=fresh();val first=seed(db)
        val text=first.message.copy(blocks=listOf(MessageBlock.Text("t","old")),historySegments=listOf(
            MessageHistorySegment(first.message.id,"assistant",listOf(MessageBlock.Text("t","old")))))
        db.save(text);db.draft(first.message.conversationId,"draft");val before=db.messages(first.message.conversationId)
        db.writableDatabase.execSQL("DROP TABLE runtime_checkpoints");app.daiyu.android.removeAssistantSchemaForLegacyFixture(db.writableDatabase);db.writableDatabase.version=5;db.close()
        val runtime=lease(db)
        assertEquals(25, db.readableDatabase.version);val restored=db.messages(first.message.conversationId)
        assertEquals(before.map {it.first},restored.map {it.first});assertEquals(text.blocks,restored.last().second.blocks)
        assertEquals(text.historySegments,restored.last().second.historySegments)
        assertEquals("draft",db.conversations().single().draft)
        assertEquals(RunStatus.INTERRUPTED,restored.last().second.status);runtime.close()
    }
    fun testMalformedCheckpointFailsRecoveryWithoutPartialInterrupt() {
        val db=fresh();val runtime=lease(db);val first=seed(db);runtime.commitCheckpoint(first)
        db.writableDatabase.execSQL("UPDATE runtime_checkpoints SET payload='{}'")
        release(db,runtime);val next=SQLiteRuntimeStore.acquire(db).also {stores+=it}
        rejects {next.recoverPending()}
        assertEquals(first.message,db.messages(first.message.conversationId).last().second)
    }
}
