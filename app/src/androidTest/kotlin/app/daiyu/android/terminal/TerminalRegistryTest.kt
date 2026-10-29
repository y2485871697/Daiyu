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

/** User commands never execute: backend rejection checks and injected Process faults. */
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
        var destroyFailure: java.io.IOException? = null
        var destroyAttempts = 0
        override fun destroy() {
            destroyAttempts++
            destroyFailure?.let { throw it }
            alive=false; waiting?.countDown()
        }
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
        processPidReader={ 4242L }
    }
    private fun open(registry:TerminalRegistry,id:String="open")=registry.execute("terminal_open",JSONObject().put("request_id",id)).getString("session_id")
    private fun command(session:String,id:String="exec",mode:String="async")=JSONObject().put("request_id",id).put("session_id",session).put("command","printf test").put("mode",mode)
    fun testIndependentHistoryReaderReadsOriginalRegistryMetadataWithoutUsingItsLiveHandle() {
        val root=File(instrumentation.targetContext.cacheDir,UUID.randomUUID().toString()).apply { mkdirs() }
        val owner=UUID.randomUUID().toString()
        val context=isolatedContext(root)
        val registry=fakeRegistry(context,owner)
        try {
            registry.startProcess={ FakeProcess() }
            val dispatched=registry.execute("terminal_exec",command(open(registry)))
            val id=dispatched.getString("job_id")
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
            while(!registry.inspectJob(id).optBoolean("output_complete") && System.nanoTime()<deadline) Thread.sleep(5)
            assertTrue(registry.inspectJob(id).getBoolean("output_complete"))
            val original=TerminalJobHistoryReader(context).list(owner).records.single()
            val page=TerminalJobHistoryReader(context).page(owner,original)
            assertEquals(id,original.jobId)
            assertEquals("actual-output",page.getString("stdout"))
            assertEquals("unknown_after_process_restart",page.getString("state"))
            assertTrue(page.isNull("exit_code")); assertEquals("unknown",page.getString("execution"))
        } finally { registry.close(); root.deleteRecursively() }
    }
    fun testOriginalRegistryRetriesCancellationAfterThrowingClose() {
        val root=File(instrumentation.targetContext.cacheDir,UUID.randomUUID().toString()).apply { mkdirs() }
        val registry=fakeRegistry(isolatedContext(root),UUID.randomUUID().toString())
        val process=FakeProcess(waiting=CountDownLatch(1))
        try {
            registry.processStartReader={ "owned" }
            registry.startProcess={ process }
            registry.execute("terminal_exec",command(open(registry)))
            process.destroyFailure=java.io.IOException("stop failed")
            try { registry.close(); fail("first stop must fail") }
            catch (_:java.io.IOException) { }
            assertTrue(process.isAlive)
            assertFalse(registry.awaitOrdinaryTermination(1))
            process.destroyFailure=null
            registry.close()
            assertTrue(process.destroyAttempts>=2)
            assertTrue(registry.awaitOrdinaryTermination(2000))
            assertEquals("run_session_closed", registry.execute("terminal_open",JSONObject().put("request_id","closed")).getString("error"))
        } finally { process.destroyFailure=null; registry.close(); root.deleteRecursively() }
    }

    fun testCloseDuringStartStillRetainsOutputPumpWhenCancellationThrows() {
        val root=File(instrumentation.targetContext.cacheDir,UUID.randomUUID().toString()).apply { mkdirs() }
        val registry=fakeRegistry(isolatedContext(root),UUID.randomUUID().toString())
        val process=FakeProcess(waiting=CountDownLatch(1))
        try {
            registry.processStartReader={ "owned" }
            process.destroyFailure=java.io.IOException("stop failed during dispatch")
            registry.startProcess={ registry.close(); process }
            val result=registry.execute("terminal_exec",command(open(registry)))
            assertEquals("unknown",result.getString("execution"))
            assertTrue(process.isAlive)
            assertFalse(registry.awaitOrdinaryTermination(1))
            process.destroyFailure=null
            registry.close()
            assertTrue(registry.awaitOrdinaryTermination(2000))
            assertTrue(registry.inspectJobs().getJSONArray("jobs").getJSONObject(0).getBoolean("output_complete"))
        } finally { process.destroyFailure=null; registry.close(); root.deleteRecursively() }
    }

    fun testClosedOrdinaryScopeWaitsForActualOutputPumpNotJustExitCode() {
        val root=File(instrumentation.targetContext.cacheDir,UUID.randomUUID().toString()).apply { mkdirs() }
        val entered=CountDownLatch(1); val release=CountDownLatch(1)
        val registry=fakeRegistry(isolatedContext(root),UUID.randomUUID().toString())
        try {
            registry.processStartReader={ "owned" }
            registry.startProcess={ FakeProcess(object:InputStream() {
                override fun read():Int { entered.countDown(); release.await(5,TimeUnit.SECONDS); return -1 }
            }) }
            val result=registry.execute("terminal_exec",command(open(registry)))
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            registry.close()
            assertFalse(registry.awaitOrdinaryTermination(1))
            assertEquals(result.getString("job_id"),registry.inspectJob(result.getString("job_id")).getString("job_id"))
            release.countDown()
            assertTrue(registry.awaitOrdinaryTermination(2000))
        } finally { release.countDown(); registry.close(); root.deleteRecursively() }
    }
    fun testCloseDuringPendingProcessStartCannotReportDrainedAndLateProcessIsOwned() {
        val root=File(instrumentation.targetContext.cacheDir,UUID.randomUUID().toString()).apply { mkdirs() }
        val entered=CountDownLatch(1); val release=CountDownLatch(1)
        val registry=fakeRegistry(isolatedContext(root),UUID.randomUUID().toString())
        val process=FakeProcess(waiting=CountDownLatch(1))
        val failure=AtomicReference<Throwable>()
        val session=open(registry)
        registry.processStartReader={ "owned" }
        registry.startProcess={ entered.countDown(); check(release.await(5,TimeUnit.SECONDS)); process }
        val worker=Thread { try { registry.execute("terminal_exec",command(session)) } catch (caught:Throwable) { failure.set(caught) } }
        try {
            worker.start(); assertTrue(entered.await(5,TimeUnit.SECONDS))
            registry.close(); assertFalse(registry.awaitOrdinaryTermination(1))
            release.countDown(); worker.join(5000)
            assertFalse(worker.isAlive); failure.get()?.let { throw it }
            assertFalse(process.isAlive)
            assertTrue(registry.awaitOrdinaryTermination(2000))
        } finally { release.countDown(); worker.join(5000); registry.close(); root.deleteRecursively() }
    }
    fun testDaemonUsesSetsidPreservesLiveHandleAcrossRunCloseAndExplicitStopDoesNotInventDescendantExit() {
        val root=File(instrumentation.targetContext.cacheDir,UUID.randomUUID().toString()).apply { mkdirs() }
        val registry=fakeRegistry(isolatedContext(root),UUID.randomUUID().toString())
        val process=FakeProcess(waiting=CountDownLatch(1))
        try {
            registry.daemonExecutable={ "/system/bin/setsid" } // fake Process only, never invoke it
            registry.processStartReader={ "owned" }
            registry.startProcess={ builder ->
                assertEquals(listOf("/system/bin/setsid","-w","--","not-executed"),builder.command())
                process
            }
            val result=registry.execute("terminal_exec",command(open(registry),mode="daemon"))
            assertEquals("unknown",result.getString("execution"))
            assertEquals("independent_of_registry_only_while_same_app_process_lives",result.getString("lifetime"))
            val id=result.getString("job_id")
            registry.close()
            assertTrue(registry.awaitOrdinaryTermination(1000))
            assertTrue(process.isAlive)
            assertEquals("running",registry.inspectJob(id).getString("state"))
            val stopped=registry.stopOwnedJob(id)
            assertTrue(stopped.getBoolean("stopped"))
            assertTrue(stopped.getString("stop_scope").contains("descendants not claimed stopped"))
        } finally { process.destroy(); registry.close(); root.deleteRecursively() }
    }
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
            registry.close()
            assertTrue(registry.awaitOrdinaryTermination(1000))
            val listed=registry.inspectJobs().getJSONArray("jobs")
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
    fun testUntrustedRootTransportIsExplicitAndNoCommandExecution() {
        val uid=android.os.Process.myUid()
        assertTrue("instrumentation must run as an application UID",uid>=10000)
        val root=File(instrumentation.targetContext.cacheDir,"untrusted-root-${UUID.randomUUID()}")
        var registry:TerminalRegistry?=null
        try {
            assertTrue(root.mkdirs())
            val executable=File(root,"su")
            executable.writeText("# Untrusted transport fixture; never execute.\n")
            assertTrue(executable.setExecutable(true,true))
            val stat=android.system.Os.lstat(executable.absolutePath)
            assertEquals(uid,stat.st_uid)
            assertTrue(android.system.OsConstants.S_ISREG(stat.st_mode))
            assertTrue(stat.st_mode and 0x49 != 0)
            val subject=TerminalRegistry(isolatedContext(root))
            registry=subject
            subject.startProcess={ throw AssertionError("untrusted transport must not start a user command") }
            subject.startRootProcess={ _, _, _ -> throw AssertionError("untrusted transport must not start a root command") }
            subject.configure(UUID.randomUUID().toString(),TerminalConfiguration(root=true,rootExecutable=executable.absolutePath))
            val result=subject.execute("terminal_open",JSONObject().put("request_id","untrusted-root1"))
            assertFalse(result.getBoolean("ok"))
            assertEquals("backend_path_su_transport_identity_untrusted",result.getString("error"))
            assertEquals("not_executed",result.getString("execution"))
            assertFalse(result.has("session_id")); assertFalse(result.has("job_id"))
            val listed=subject.execute("terminal_list",JSONObject())
            assertTrue(listed.getBoolean("ok")); assertEquals(0,listed.getJSONArray("jobs").length())
        } finally {
            try { registry?.close() } finally { root.deleteRecursively() }
        }
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
    fun testUnknownProcessPidCannotReadStartIdentityOrStop() {
        val root=File(instrumentation.targetContext.cacheDir,UUID.randomUUID().toString()).apply { mkdirs() }
        val process=FakeProcess(waiting=CountDownLatch(1))
        val registry=fakeRegistry(isolatedContext(root),UUID.randomUUID().toString())
        try {
            registry.processPidReader={ -1L }
            registry.processStartReader={ throw AssertionError("unknown PID must not read process identity") }
            registry.startProcess={ process }
            val id=registry.execute("terminal_exec",command(open(registry))).getString("job_id")
            val result=registry.execute("terminal_stop",JSONObject().put("request_id","stop").put("job_id",id))
            assertEquals("process_owner_unverified_not_stopped",result.getString("error"))
            assertEquals("unknown",result.getString("execution")); assertTrue(process.isAlive)
            registry.close(); assertTrue(process.isAlive)
        } finally { process.destroy(); registry.close(); root.deleteRecursively() }
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

    private fun rootRegistry(root: File): TerminalRegistry {
        val su = File(root, "su").apply { writeText("fixture; never executed"); setExecutable(true) }
        val rootfs = File(root, "rootfs").apply { mkdir() }
        val scope = RootScopeLaunch(rootfs.canonicalFile, su.canonicalFile)
        return fakeRegistry(isolatedContext(root), UUID.randomUUID().toString()).apply {
            backendOverride = SelectedBackend("linux:fixture", 0, true, scope.guestArgv(), emptyMap(), root, true,
                rootScope = scope)
            startProcess = { throw AssertionError("root command must not use plain builder") }
            daemonExecutable = { throw AssertionError("root daemon must not use Android setsid") }
        }
    }
    fun testChrootSyncAsyncAndDaemonUseOnlyRootScopeTransport() {
        for (mode in listOf("sync", "async", "daemon")) {
            val root = File(instrumentation.targetContext.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
            val registry = rootRegistry(root)
            var starts = 0
            try {
                registry.startRootProcess = { selected, command, _ ->
                    starts++; assertNotNull(selected.rootScope); assertEquals("printf test", command); FakeProcess()
                }
                val session = open(registry)
                val args = command(session, mode = mode)
                val result = registry.execute("terminal_exec", args)
                assertTrue(result.has("job_id")); assertTrue(result.getBoolean("host_root"))
                assertEquals(result.getString("job_id"), registry.execute("terminal_exec", args).getString("job_id"))
                assertEquals(1, starts)
            } finally { registry.close(); root.deleteRecursively() }
        }
    }
    fun testChrootStartupCleanupRetainedAfterNoProcessReturned() {
        val root = File(instrumentation.targetContext.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        val registry = rootRegistry(root)
        var drained = false
        try {
            registry.startRootProcess = { _, _, _ ->
                throw PtyStartupFailure("fixture", PtyStartupCleanup(FakeProcess(), {}, { drained }))
            }
            val result = registry.execute("terminal_exec", command(open(registry)))
            assertEquals("unknown", result.getString("execution"))
            assertEquals("pty_startup_unknown_not_replayed", result.getString("error"))
            registry.close()
            assertFalse(registry.awaitOrdinaryTermination(20))
            drained = true
            assertTrue(registry.awaitOrdinaryTermination(2000))
        } finally { drained = true; registry.close(); root.deleteRecursively() }
    }
    fun testChrootDrainWithoutLeaderStatusKeepsExitUnknownAndQueriesReadable() {
        val root = File(instrumentation.targetContext.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        val registry = rootRegistry(root)
        val scope = "daiyu-" + "d".repeat(32)
        fun frame(type: Char, text: String): ByteArray = ByteArrayOutputStream().also {
            val bytes = text.toByteArray()
            java.io.DataOutputStream(it).apply { writeByte(type.code); writeInt(bytes.size); write(bytes) }
        }.toByteArray()
        val bytes = frame('S', scope) + frame('H', "DPTY2") +
            frame('D', "DROOT1 scope=$scope kill=1 populated=0 reaped=1 removed=1")
        val process = RootScopeCommandProcess(PtySession(FakeProcess(ByteArrayInputStream(bytes)),
            OperationJournal(File(root, "pty"), "command"), true))
        try {
            registry.startRootProcess = { _, _, _ -> process }
            val id = registry.execute("terminal_exec", command(open(registry))).getString("job_id")
            assertTrue(process.waitFor(2000, TimeUnit.MILLISECONDS))
            val page = registry.inspectJob(id)
            assertTrue(page.isNull("exit_code")); assertEquals("unknown", page.getString("execution"))
            assertTrue(page.getBoolean("scope_drain_confirmed"))
            assertTrue(registry.stopOwnedJob(id).getBoolean("stopped"))
            registry.close(); assertTrue(registry.awaitOrdinaryTermination(2000))
        } finally { registry.close(); root.deleteRecursively() }
    }

}
