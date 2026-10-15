package app.daiyu.android.terminal

import android.test.InstrumentationTestCase
import android.content.Context
import android.content.ContextWrapper
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger

/** No shell execution in these tests: unavailable paths and injected Process fault checks. */
@Suppress("DEPRECATION")
class TerminalRegistryTest:InstrumentationTestCase() {
    private class FakeProcess(private val output:InputStream=ByteArrayInputStream("actual-output".toByteArray()),private val waiting:CountDownLatch?=null):Process() {
        @Volatile private var alive=waiting!=null
        override fun getInputStream()=output
        override fun getErrorStream():InputStream=ByteArrayInputStream(ByteArray(0))
        override fun getOutputStream()=object:ByteArrayOutputStream() {
            override fun write(bytes:ByteArray,off:Int,len:Int) { throw AssertionError("must use bounded file stdin, not pipe delivery") }
        }
        override fun waitFor():Int { waiting?.await(); alive=false; return 0 }
        override fun waitFor(timeout:Long,unit:TimeUnit):Boolean {
            val done=waiting?.await(timeout,unit) ?: true
            if(done) alive=false
            return done
        }
        override fun exitValue():Int { if(alive) throw IllegalThreadStateException(); return 0 }
        override fun isAlive()=alive
        override fun destroy() { alive=false; waiting?.countDown() }
        override fun pid()=4242L
    }
    private fun isolatedContext(root:File)=object:ContextWrapper(instrumentation.targetContext) {
        override fun getApplicationContext():Context=this
        override fun getFilesDir():File=root
        override fun getCacheDir():File=root
    }
    private fun fakeRegistry(context:Context,scope:String):TerminalRegistry=TerminalRegistry(context).apply {
        configure(scope,TerminalConfiguration(),scope)
        backendOverride=SelectedBackend("android",12345,false,listOf("not-executed"),emptyMap(),context.filesDir,true)
    }
    private fun open(registry:TerminalRegistry,id:String="open")=registry.execute("terminal_open",JSONObject().put("request_id",id)).getString("session_id")
    private fun command(session:String,id:String="exec",mode:String="async")=JSONObject().put("request_id",id).put("session_id",session).put("command","printf test").put("mode",mode)
    fun testExpiredOpenReplayDoesNotRestoreClosedOrRestartedSession() {
        val root=File(instrumentation.targetContext.cacheDir,UUID.randomUUID().toString()).apply { mkdirs() }
        try {
            val context=isolatedContext(root); val scope=UUID.randomUUID().toString(); val registry=fakeRegistry(context,scope)
            val session=open(registry)
            registry.execute("terminal_close",JSONObject().put("request_id","close").put("session_id",session))
            assertEquals("expired",registry.execute("terminal_open",JSONObject().put("request_id","open")).getString("state"))
            open(registry,"open-live")
            val restarted=fakeRegistry(context,scope)
            val result=restarted.execute("terminal_open",JSONObject().put("request_id","open-live"))
            assertEquals("session_expired_not_reopened",result.getString("error")); assertEquals("expired",result.getString("state"))
        } finally { root.deleteRecursively() }
    }
    fun testAsyncDispatchedUnknownBoundedFileStdinAndOutputQuota() {
        val root=File(instrumentation.targetContext.cacheDir,UUID.randomUUID().toString()).apply { mkdirs() }
        try {
            val registry=fakeRegistry(isolatedContext(root),UUID.randomUUID().toString()); val session=open(registry); var starts=0
            registry.startProcess={ builder ->
                starts++
                assertEquals(ProcessBuilder.Redirect.Type.READ,builder.redirectInput().type())
                val script=builder.redirectInput().file(); assertTrue(script.length()<=131072)
                assertTrue(script.readText().contains("printf test"))
                assertEquals(384,android.system.Os.stat(script.path).st_mode and 511)
                val records=File(root,"terminal-jobs").listFiles()!!.filter { it.extension=="json" }
                assertEquals(1,records.size); assertEquals("pending",JSONObject(records.single().readText()).getString("state"))
                FakeProcess(ByteArrayInputStream(ByteArray(400000) { 65 }))
            }
            val args=command(session); val result=registry.execute("terminal_exec",args)
            assertEquals("unknown",result.getString("execution")); assertEquals("dispatched",result.getString("state"))
            val id=result.getString("job_id")
            var page=registry.execute("terminal_poll",JSONObject().put("job_id",id))
            repeat(100) { if(!page.optBoolean("output_complete")) { Thread.sleep(10); page=registry.execute("terminal_poll",JSONObject().put("job_id",id)) } }
            assertTrue(page.getBoolean("output_complete")); assertEquals(262144,page.getInt("total_bytes")); assertTrue(page.getBoolean("log_truncated"))
            registry.execute("terminal_exec",args)
            registry.execute("terminal_exec",JSONObject(args.toString()).put("request_id","equivalent"))
            assertEquals(1,starts)
        } finally { root.deleteRecursively() }
    }
    fun testMetadataFailureAfterDispatchRetainsReadableListedJobAndDoesNotRestart() {
        val root=File(instrumentation.targetContext.cacheDir,UUID.randomUUID().toString()).apply { mkdirs() }
        try {
            val scope=UUID.randomUUID().toString(); val context=isolatedContext(root); val registry=fakeRegistry(context,scope)
            val session=open(registry); var saves=0; var starts=0
            registry.beforeJobSave={ if(++saves==2) throw java.io.IOException("injected after start") }
            registry.startProcess={ starts++; FakeProcess() }
            val args=command(session); val result=registry.execute("terminal_exec",args); val id=result.getString("job_id")
            assertEquals("unknown",result.getString("execution")); assertEquals("job_metadata_commit_unknown_not_replayed",result.getString("error"))
            assertTrue(File(root,"terminal-jobs/$id.json").isFile)
            val listed=fakeRegistry(context,scope).execute("terminal_list",JSONObject()).getJSONArray("jobs")
            assertEquals(1,listed.length()); assertEquals(id,listed.getJSONObject(0).getString("job_id"))
            assertEquals(id,registry.execute("terminal_poll",JSONObject().put("job_id",id)).getString("job_id"))
            registry.execute("terminal_exec",JSONObject(args.toString()).put("request_id","retry")); assertEquals(1,starts)
        } finally { root.deleteRecursively() }
    }
    fun testSyncWaitDoesNotHoldRegistryOrJournalLockAndTimeoutIsUnknown() {
        val root=File(instrumentation.targetContext.cacheDir,UUID.randomUUID().toString()).apply { mkdirs() }
        val release=CountDownLatch(1); val started=CountDownLatch(1)
        try {
            val registry=fakeRegistry(isolatedContext(root),UUID.randomUUID().toString()); val session=open(registry)
            registry.startProcess={ started.countDown(); FakeProcess(waiting=release) }
            val result=AtomicReference<JSONObject>()
            val worker=Thread { result.set(registry.execute("terminal_exec",command(session,mode="sync").put("timeout_ms",1000))) }.apply { start() }
            assertTrue(started.await(1,TimeUnit.SECONDS))
            val before=System.nanoTime()
            assertTrue(registry.execute("terminal_list",JSONObject()).getBoolean("ok"))
            assertTrue(registry.execute("terminal_open",JSONObject().put("request_id","concurrent-open")).getBoolean("ok"))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-before)<750)
            worker.join(2000); assertFalse(worker.isAlive); assertEquals("unknown",result.get().getString("execution")); assertTrue(result.get().has("job_id"))
        } finally { release.countDown(); root.deleteRecursively() }
    }
    fun testPendingMetadataFailureNeverLaunchesButJobIdRemainsInspectable() {
        val root=File(instrumentation.targetContext.cacheDir,UUID.randomUUID().toString()).apply { mkdirs() }
        try {
            val registry=fakeRegistry(isolatedContext(root),UUID.randomUUID().toString()); val session=open(registry)
            registry.beforeJobSave={ throw java.io.IOException("injected pending failure") }
            registry.startProcess={ throw AssertionError("cannot launch before durable pending") }
            val result=registry.execute("terminal_exec",command(session)); val id=result.getString("job_id")
            assertEquals("not_executed",result.getString("execution"))
            assertEquals(id,registry.execute("terminal_poll",JSONObject().put("job_id",id)).getString("job_id"))
            val listed=registry.execute("terminal_list",JSONObject()).getJSONArray("jobs")
            assertEquals(1,listed.length()); assertEquals(id,listed.getJSONObject(0).getString("job_id"))
        } finally { root.deleteRecursively() }
    }
    fun testRetentionLimitPreventsLaunchAndDoesNotDeletePendingMetadata() {
        val root=File(instrumentation.targetContext.cacheDir,UUID.randomUUID().toString()).apply { mkdirs() }
        try {
            val registry=fakeRegistry(isolatedContext(root),UUID.randomUUID().toString()); val session=open(registry)
            val directory=File(root,"terminal-jobs")
            repeat(64) { File(directory,"$it.json").writeText("{\"state\":\"pending\"}") }
            registry.startProcess={ throw AssertionError("quota must reject before launch") }
            val result=registry.execute("terminal_exec",command(session))
            assertEquals("not_executed",result.getString("execution")); assertEquals("job_or_log_retention_limit",result.getString("error"))
            assertEquals(64,directory.listFiles()!!.size)
            repeat(193) { File(directory,"extra$it.other").writeText("") }
            assertEquals("job_directory_scan_limit",registry.execute("terminal_list",JSONObject()).getString("error"))
            assertEquals(257,directory.listFiles()!!.size)
        } finally { root.deleteRecursively() }
    }
    fun testOrphanLogsAndTwoDelayedDispatchesReserveFutureOutputQuota() {
        assertOrphanLogsAndTwoDelayedDispatches(false)
    }
    fun testOrphanLogsAndConcurrentDelayedDispatchesReserveFutureOutputQuota() {
        assertOrphanLogsAndTwoDelayedDispatches(true)
    }
    private fun assertOrphanLogsAndTwoDelayedDispatches(concurrent:Boolean) {
        val root=File(instrumentation.targetContext.cacheDir,UUID.randomUUID().toString()).apply { mkdirs() }
        val release=CountDownLatch(1); val begin=CountDownLatch(1); val starts=AtomicInteger()
        val results=List(2) { AtomicReference<JSONObject>() }; val workers=mutableListOf<Thread>()
        val registry=fakeRegistry(isolatedContext(root),UUID.randomUUID().toString())
        try {
            val session=open(registry); val directory=File(root,"terminal-jobs")
            val orphanContent=ByteArray(262144) { 79 }
            val orphans=List(63) { File(directory,"orphan-$it.log").apply { writeBytes(orphanContent) } }
            registry.startProcess={
                starts.incrementAndGet()
                val source=ByteArrayInputStream(ByteArray(262144) { 65 })
                val delayed=object:InputStream() {
                    override fun read():Int { release.await(); return source.read() }
                    override fun read(bytes:ByteArray,off:Int,len:Int):Int { release.await(); return source.read(bytes,off,len) }
                }
                FakeProcess(delayed,release)
            }
            fun dispatch(index:Int) {
                // Distinct commands ensure the journal equivalence guard does not mask the quota bug.
                results[index].set(registry.execute("terminal_exec",command(session,"delayed-$index").put("command","printf delayed-$index")))
            }
            if(concurrent) {
                repeat(2) { index -> workers.add(Thread { begin.await(); dispatch(index) }.apply { start() }) }
                begin.countDown(); workers.forEach { it.join(2000); assertFalse(it.isAlive) }
            } else { dispatch(0); dispatch(1) }
            assertEquals(1,starts.get())
            val dispatched=results.map { it.get() }.single { it.optString("state")=="dispatched" }
            val rejected=results.map { it.get() }.single { it.has("error") }
            assertEquals("unknown",dispatched.getString("execution"))
            assertEquals("job_or_log_retention_limit",rejected.getString("error"))
            assertEquals("not_executed",rejected.getString("execution"))
            // Neither process has emitted output yet: the next log's full allowance is reserved.
            assertEquals(63L*262144,directory.listFiles()!!.filter { it.extension=="log" }.sumOf { it.length() })
            release.countDown()
            val id=dispatched.getString("job_id")
            var page=registry.execute("terminal_poll",JSONObject().put("job_id",id))
            repeat(100) { if(!page.optBoolean("output_complete")) { Thread.sleep(10); page=registry.execute("terminal_poll",JSONObject().put("job_id",id)) } }
            assertTrue(page.getBoolean("output_complete")); assertEquals(262144,page.getInt("total_bytes"))
            assertEquals(16L*1024*1024,directory.listFiles()!!.filter { it.extension=="log" }.sumOf { it.length() })
            orphans.forEach { assertTrue(it.isFile); assertTrue(it.readBytes().contentEquals(orphanContent)) }
            assertEquals(63,orphans.count { !File(directory,"${it.nameWithoutExtension}.json").exists() })
        } finally {
            begin.countDown(); release.countDown(); workers.forEach { it.join(2000) }
            // Drain fake pumps before removing their private fixtures, even on assertion failure.
            results.forEach { result ->
                val id=result.get()?.optString("job_id").orEmpty()
                if(id.isNotEmpty()) repeat(100) {
                    if(!registry.execute("terminal_poll",JSONObject().put("job_id",id)).optBoolean("output_complete")) Thread.sleep(10)
                }
            }
            root.deleteRecursively()
        }
    }
    fun testCloseWhileStartIsBlockedCancelsLateProcess() {
        val root=File(instrumentation.targetContext.cacheDir,UUID.randomUUID().toString()).apply { mkdirs() }
        val entered=CountDownLatch(1); val release=CountDownLatch(1)
        val process=FakeProcess(waiting=CountDownLatch(1))
        val output=AtomicReference<JSONObject>()
        val registry=fakeRegistry(isolatedContext(root),UUID.randomUUID().toString())
        val session=open(registry)
        registry.processStartReader={ "fake-start" }
        registry.startProcess={ entered.countDown(); release.await(); process }
        val thread=Thread { output.set(registry.execute("terminal_exec",command(session))) }.apply { start() }
        try {
            assertTrue(entered.await(1,TimeUnit.SECONDS))
            registry.close()
            assertTrue(process.isAlive)
            assertEquals("run_session_closed",registry.execute("terminal_open",JSONObject().put("request_id","after-close")).getString("error"))
            release.countDown(); thread.join(2000)
            assertFalse(thread.isAlive); assertFalse(process.isAlive)
            assertEquals("unknown",output.get().getString("execution"))
        } finally { release.countDown(); thread.join(2000); root.deleteRecursively() }
    }

    fun testDefaultsAndChangedWaitModeCannotRedispatchUnknownCommand() {
        val root=File(instrumentation.targetContext.cacheDir,UUID.randomUUID().toString()).apply { mkdirs() }
        val release=CountDownLatch(1)
        try {
            val registry=fakeRegistry(isolatedContext(root),UUID.randomUUID().toString()); val session=open(registry)
            var starts=0
            registry.startProcess={ starts++; FakeProcess(waiting=release) }
            val first=command(session,"first","sync").put("timeout_ms",1)
            assertEquals("unknown",registry.execute("terminal_exec",first).getString("execution"))
            val second=command(session,"second","async").put("timeout_ms",120000)
            assertEquals("equivalent_outcome_unknown_not_replayed",registry.execute("terminal_exec",second).getString("error"))
            assertEquals(1,starts)
        } finally { release.countDown(); root.deleteRecursively() }
    }

    fun testLinuxNotReadyNeverFallsBackAndFixedEnvironment() {
        val registry=TerminalRegistry(instrumentation.targetContext)
        registry.configure(UUID.randomUUID().toString(),TerminalConfiguration(backend="linux"),"conversation-A")
        val result=registry.execute("terminal_open",JSONObject().put("request_id","open1"))
        assertFalse(result.getBoolean("ok")); assertEquals("linux_backend_not_configured",result.getString("error"))
        assertEquals("not_executed",result.getString("execution")); assertEquals("settings://terminal/linux",result.getString("installation_entry"))
        try { registry.configure("other",TerminalConfiguration()); fail("environment must remain fixed") } catch(_:IllegalStateException) { }
        val again=registry.execute("terminal_open",JSONObject().put("request_id","open1"))
        assertEquals("linux_backend_not_configured",again.getString("error"))
    }
    fun testRootUnavailableIsExplicitAndNoCommandExecution() {
        val registry=TerminalRegistry(instrumentation.targetContext)
        registry.configure(UUID.randomUUID().toString(),TerminalConfiguration(root=true,rootExecutable="/no-such-authorized-root-executable"))
        val result=registry.execute("terminal_open",JSONObject().put("request_id","root1"))
        assertFalse(result.getBoolean("ok")); assertEquals("root_unavailable",result.getString("error"))
        assertEquals("not_executed",result.getString("execution"))
    }
    fun testSchemasAndUnknownOwnershipRefusesStop() {
        val registry=TerminalRegistry(instrumentation.targetContext)
        assertEquals(TerminalRegistry.names.size,registry.schemas.length())
        for(i in 0 until registry.schemas.length()) {
            val schema=registry.schemas.getJSONObject(i)
            assertTrue(schema.has("name")); assertTrue(schema.has("parameters")); assertTrue(schema.has("sideEffect"))
        }
        registry.configure(UUID.randomUUID().toString(),TerminalConfiguration(backend="linux"))
        val stopped=registry.execute("terminal_stop",JSONObject().put("request_id","stop1").put("job_id",UUID.randomUUID().toString()))
        assertEquals("owner_process_handle_unavailable_not_stopped",stopped.getString("error"))
        assertTrue(registry.execute("terminal_list",JSONObject()).getJSONArray("jobs").length()==0)
        assertEquals("invalid_arguments",registry.execute("terminal_poll",JSONObject().put("job_id","x").put("limit",100000)).getString("error"))
    }
    fun testRunJournalCannotExposeCachedResultToDifferentOwnerOrConfiguration() {
        val root=File(instrumentation.targetContext.cacheDir,UUID.randomUUID().toString()).apply { mkdirs() }
        try {
            val context=isolatedContext(root); val scope=UUID.randomUUID().toString()
            val first=fakeRegistry(context,scope); val session=open(first)
            var starts=0
            first.startProcess={ starts++; FakeProcess() }
            val args=command(session,mode="sync")
            val original=first.execute("terminal_exec",args)
            assertTrue(original.has("job_id"))
            val other=TerminalRegistry(context)
            try { other.configure(scope,TerminalConfiguration(),"different-owner"); fail("must reject before journal replay") }
            catch (expected:IllegalStateException) { assertEquals("operation_scope_binding_mismatch",expected.message) }
            assertEquals("run_session_not_configured",other.execute("terminal_exec",args).getString("error"))
            val changed=TerminalRegistry(context)
            try { changed.configure(scope,TerminalConfiguration(environment=mapOf("MODE" to "changed")),scope); fail("frozen config must not change") }
            catch (expected:IllegalStateException) { assertEquals("operation_scope_binding_mismatch",expected.message) }
            val restored=fakeRegistry(context,scope)
            restored.startProcess={ throw AssertionError("must replay, not restart") }
            assertEquals(original.getString("job_id"),restored.execute("terminal_exec",args).getString("job_id"))
            assertEquals(1,starts)
        } finally { root.deleteRecursively() }
    }
    fun testSameOwnerAndRunStringsCannotReadOrCloseAnotherStorageDomain() {
        val root=File(instrumentation.targetContext.cacheDir,UUID.randomUUID().toString()).apply { mkdirs() }
        val release=CountDownLatch(1)
        var first:TerminalRegistry?=null
        try {
            val scope=UUID.randomUUID().toString()
            val a=File(root,"a").apply { mkdirs() }; val b=File(root,"b").apply { mkdirs() }
            val process=FakeProcess(waiting=release)
            first=fakeRegistry(isolatedContext(a),scope)
            first.processStartReader={ "fake-start" }; first.startProcess={ process }
            val id=first.execute("terminal_exec",command(open(first))).getString("job_id")
            val other=fakeRegistry(isolatedContext(b),scope)
            assertEquals("job_not_owned_by_conversation_or_run",other.execute("terminal_poll",JSONObject().put("job_id",id)).getString("error"))
            assertEquals(0,other.execute("terminal_list",JSONObject()).getJSONArray("jobs").length())
            other.close()
            assertTrue(process.isAlive)
            assertEquals("running",first.execute("terminal_poll",JSONObject().put("job_id",id)).getString("state"))
        } finally { release.countDown(); first?.close(); root.deleteRecursively() }
    }

}
