package app.daiyu.android.integration.tools

import app.daiyu.android.domain.*
import app.daiyu.android.domain.tools.*
import app.daiyu.android.skills.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class RunCatalogToolsTest {
    private val owner = Message(MessageId("assistant"), ConversationId("conversation"), RunId("run"),
        "assistant", emptyList(), RunStatus.RUNNING)
    private val revision = "a".repeat(64)
    private val capabilities = SessionCapabilities("run", setOf(SkillRunSession.READ, SkillRunSession.RESOURCE))
    private val store = SkillStore(File("build/unused-skill-fixture"))
    private fun scope(auth: TaskAuthorization, body: String = "frozen body", permissions: Set<String> = capabilities.enabled) =
        SkillRunSession.create(auth, permissions, listOf(SkillRunSession.Entry("user:demo",
            CapturedSkill("demo", "Demo", revision, body, emptyMap(), body.length), store)))
    private fun call(id: String = "read", name: String = "skills_read", arguments: String =
        JSONObject().put("id", "user:demo").put("revision", revision).toString()) = ToolInvocation(ToolCallId(id), name, arguments)
    private fun confirmed(result: ToolDispatchResult) = result.outcome as DispatchOutcome.Confirmed
    private fun denied(block: () -> Unit) {
        try {block();fail("closed scope accepted")} catch (_: IllegalStateException) {}
    }
    @Test fun oneLazyCaptureSuppliesPromptAdvertisementDispatchAndReplay() {
        val captures = AtomicInteger();val ledger = InMemoryToolLedgerStore()
        lateinit var auth: TaskAuthorization;lateinit var snapshot: SkillRunSession
        val tools = RunCatalogTools(ToolRegistry(), capabilities, ToolLedger(ledger), owner.conversationId, owner.runId) { caps, task ->
            assertSame(capabilities,caps);captures.incrementAndGet();auth=task;scope(task).also {snapshot=it}
        }
        assertEquals(0,captures.get())
        val definitions=tools.definitions
        assertEquals(setOf("skills_list","skills_read","skills_read_resource"),definitions.map {it.name}.toSet())
        assertTrue(definitions.none {it.sideEffect})
        assertTrue(definitions.single {it.name=="skills_list"}.description.contains(revision))
        assertSame(definitions,tools.definitions);assertEquals(1,captures.get())
        val first=confirmed(tools.execute(owner,listOf(call())){false}.single())
        assertFalse(first.isError);assertEquals("frozen body",JSONObject(first.payload).getString("content"))
        assertTrue(confirmed(tools.execute(owner,listOf(call())){false}.single()).replayed)
        assertEquals(1,ledger.transaction {it.toList()}.size)
        try {snapshot.read(TaskAuthorization(auth.sessionId,auth.taskId),"user:demo",revision);fail("copied identity accepted")}
        catch (_:IllegalArgumentException) {}
        tools.close();denied {snapshot.read(auth,"user:demo",revision)}
        assertTrue(tools.execute(owner,listOf(call("after"))){false}.single().outcome is DispatchOutcome.NotExecuted)
        denied {tools.definitions};assertEquals(1,captures.get())
    }
    @Test fun malformedWholeBatchAndWriteNamesCannotReserveOrConsumeReads() {
        val ledger=InMemoryToolLedgerStore();lateinit var snapshot:SkillRunSession;lateinit var auth:TaskAuthorization
        val body="x".repeat(128*1024)
        val tools=RunCatalogTools(ToolRegistry(),capabilities,ToolLedger(ledger),owner.conversationId,owner.runId) {_,task ->
            auth=task;scope(task,body).also {snapshot=it}
        }
        tools.definitions
        for (bad in listOf(call("bad",arguments="{\"id\":\"user:demo\"}"),
            call("bad",name="skills_install_from_github",arguments="{}"),
            call("bad",name="skills_read_resource",arguments=JSONObject().put("id","user:demo").put("revision",revision).put("path","../x").toString()))) {
            assertTrue(tools.execute(owner,listOf(call(),bad)){false}.all {it.outcome is DispatchOutcome.NotExecuted})
            assertTrue(ledger.transaction {it.toList()}.isEmpty())
        }
        repeat(15) { assertEquals(body,snapshot.read(auth,"user:demo",revision)) }
        assertTrue(confirmed(tools.execute(owner,listOf(call())){false}.single()).isError) // shared budget is exhausted for another full body
        tools.close()
    }
    @Test fun preparationsNeverReadFilesAndEscapedReceiptHasIndependentLimit() {
        val body="\"".repeat(200*1024)
        val auth=TaskAuthorization("run","run")
        val snapshot=scope(auth,body)
        val tools=ScopedSkillTools.create(snapshot,auth)
        val read=tools.single {it.definition.name=="skills_read"}
        val args=read.definition.schema.parse(call().arguments)
        repeat(100) {read.prepare(args)}
        val result=read.prepare(args).execute() as ExecutionReceipt.Confirmed
        assertFalse(result.isError)
        val output=JSONObject(result.payload)
        assertEquals(body,output.getString("content"))
        assertTrue(result.payload.toByteArray().size>body.toByteArray().size)
        assertTrue(result.payload.toByteArray().size<2*1024*1024)
        snapshot.close()
    }
    @Test fun closeBeforePreparationNeverCaptures() {
        var captures=0
        val tools=RunCatalogTools(ToolRegistry(),capabilities,ToolLedger(InMemoryToolLedgerStore()),owner.conversationId,owner.runId) {_,task ->captures++;scope(task)}
        tools.close();denied {tools.definitions};assertEquals(0,captures)
    }
    @Test fun closeDuringCaptureDoesNotWaitOrPublishLateScope() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val failure=AtomicReference<Throwable?>();lateinit var snapshot:SkillRunSession;lateinit var auth:TaskAuthorization
        val tools=RunCatalogTools(ToolRegistry(),capabilities,ToolLedger(InMemoryToolLedgerStore()),owner.conversationId,owner.runId) {_,task ->
            auth=task;entered.countDown();check(release.await(5,TimeUnit.SECONDS));scope(task).also {snapshot=it}
        }
        val worker=Thread {try {tools.definitions;failure.set(AssertionError("revoked capture published"))} catch (_:IllegalStateException) {} catch(t:Throwable){failure.set(t)}}
        worker.start();assertTrue(entered.await(5,TimeUnit.SECONDS))
        try {tools.close()} finally {release.countDown()}
        worker.join(5000);assertFalse(worker.isAlive);failure.get()?.let {throw it}
        denied {snapshot.read(auth,"user:demo",revision)}
    }
    @Test fun failedCaptureCannotRecaptureAndWrongRunCannotDispatch() {
        var captures=0
        val tools=RunCatalogTools(ToolRegistry(),capabilities,ToolLedger(InMemoryToolLedgerStore()),owner.conversationId,owner.runId) {_,_ ->captures++;error("capture_failed")}
        repeat(2) {denied {tools.definitions}}
        assertEquals(1,captures)
        try {tools.execute(owner.copy(runId=RunId("other")),listOf(call())){false};fail("foreign run accepted")}
        catch (_:IllegalArgumentException) {}
    }
    @Test fun disabledOrEmptySnapshotsDoNotAdvertiseAndResourcePermissionCannotBeAdded() {
        for (permissions in listOf(emptySet(),setOf(SkillRunSession.READ))) {
            val tools=RunCatalogTools(ToolRegistry(),capabilities,ToolLedger(InMemoryToolLedgerStore()),owner.conversationId,owner.runId) {_,auth ->scope(auth,permissions=permissions)}
            assertEquals(if(permissions.isEmpty()) emptySet<String>() else setOf("skills_list","skills_read"),tools.definitions.map {it.name}.toSet())
            tools.close()
        }
    }
}
