package app.daiyu.android.terminal

import android.content.Context
import android.content.ContextWrapper
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class TerminalRegistryRetentionTest {
    private class TestProcess(active: Boolean = false, private val output: InputStream = ByteArrayInputStream("actual output".toByteArray())) : Process() {
        private val stopped = CountDownLatch(if (active) 1 else 0)
        override fun getInputStream() = output
        override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun isAlive() = stopped.count != 0L
        override fun waitFor(): Int { stopped.await(); return 0 }
        override fun waitFor(timeout: Long, unit: TimeUnit) = stopped.await(timeout, unit)
        override fun exitValue(): Int { check(!isAlive); return 0 }
        override fun destroy() { stopped.countDown() }
    }
    private fun fixture(test: (File, TerminalRegistry, String) -> Unit) {
        val root = Files.createTempDirectory("registry-retention").toFile()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = root
        }
        val registry = TerminalRegistry(context).apply {
            configure(UUID.randomUUID().toString(), TerminalConfiguration())
            backendOverride = SelectedBackend("android", 12345, false, listOf("not-executed"), emptyMap(), root, true)
            processPidReader = { 4242L }
            processStartReader = { "start" }
            startProcess = { TestProcess() }
        }
        try {
            val session = registry.execute("terminal_open", JSONObject().put("request_id", "open")).getString("session_id")
            test(root, registry, session)
        } finally {
            registry.close()
            check(registry.awaitOrdinaryTermination(5000))
            root.deleteRecursively()
        }
    }
    private fun command(session: String, index: Int, mode: String = "sync") = JSONObject()
        .put("request_id", "command-$index").put("session_id", session)
        .put("command", "printf retained-$index").put("mode", mode)

    @Test fun continuous130CommandsReclaimOldHistoryAndReplayNeverStartsAgain() = fixture { root, registry, session ->
        var starts = 0
        registry.startProcess = { starts++; TestProcess() }
        var firstId = ""
        repeat(130) { index ->
            val result = registry.execute("terminal_exec", command(session, index))
            assertEquals(result.toString(), "executed", result.optString("execution"))
            if (index == 0) firstId = result.getString("job_id")
        }
        assertEquals(130, starts)
        val directory = File(root, "terminal-jobs")
        assertEquals(64, directory.listFiles()!!.count { it.extension == "json" })
        assertFalse(File(directory, "$firstId.log").exists())
        val replay = registry.execute("terminal_exec", command(session, 0))
        assertTrue(replay.getBoolean("replayed_execution"))
        assertEquals(firstId, replay.getString("job_id"))
        assertEquals(130, starts)
    }
    @Test fun sixtyFourRunningAsyncJobsStillRejectNextBeforeDispatch() = fixture { root, registry, session ->
        var starts = 0
        registry.startProcess = { starts++; TestProcess(true) }
        repeat(64) { index ->
            assertEquals("dispatched", registry.execute("terminal_exec", command(session, index, "async")).optString("state"))
        }
        val rejected = registry.execute("terminal_exec", command(session, 64))
        assertEquals("job_or_log_retention_limit", rejected.optString("error"))
        assertEquals("not_executed", rejected.optString("execution"))
        assertEquals(64, starts)
        assertEquals(64, File(root, "terminal-jobs").listFiles()!!.count { it.extension == "json" })
    }
    @Test fun eofObservedBeforePumpFinishesDoesNotConsumeLaterOutput() = fixture { _, registry, session ->
        val release = CountDownLatch(1)
        val tail = ByteArrayInputStream("late output".toByteArray())
        val delayed = object : InputStream() {
            override fun read(): Int { release.await(); return tail.read() }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int { release.await(); return tail.read(bytes, offset, length) }
        }
        var starts = 0
        registry.startProcess = { if (starts++ == 0) TestProcess(output = delayed) else TestProcess() }
        try {
            val id = registry.execute("terminal_exec", command(session, 0, "async")).getString("job_id")
            repeat(63) { index -> registry.execute("terminal_exec", command(session, index + 1, "async")) }
            val early = registry.inspectJob(id)
            assertEquals(0L, early.getLong("total_bytes"))
            assertFalse(early.optBoolean("output_complete"))
            release.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (Thread.getAllStackTraces().keys.any { it.name == "terminal-log-$id" && it.isAlive }) {
                check(System.nanoTime() < deadline)
                Thread.yield()
            }
            assertEquals("job_or_log_retention_limit", registry.execute("terminal_exec", command(session, 64)).optString("error"))
            assertEquals("late output", registry.inspectJob(id).getString("stdout"))
            assertEquals("executed", registry.execute("terminal_exec", command(session, 65)).optString("execution"))
        } finally { release.countDown() }
    }
    @Test fun completedAsyncOutputIsPreservedUntilReadThenCanFreeCapacity() = fixture { root, registry, session ->
        repeat(64) { index -> registry.execute("terminal_exec", command(session, index, "async")) }
        assertEquals("job_or_log_retention_limit", registry.execute("terminal_exec", command(session, 64)).optString("error"))
        val jobs = registry.inspectJobs().getJSONArray("jobs")
        val id = jobs.getJSONObject(0).getString("job_id")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (Thread.getAllStackTraces().keys.any { it.name == "terminal-log-$id" && it.isAlive }) {
            check(System.nanoTime() < deadline) { "Output pump did not finish" }
            Thread.yield()
        }
        val page = registry.inspectJob(id)
        assertTrue(page.optBoolean("output_complete"))
        assertEquals("actual output", page.getString("stdout"))
        val next = registry.execute("terminal_exec", command(session, 65))
        assertEquals(next.toString(), "executed", next.optString("execution"))
        assertFalse(File(root, "terminal-jobs/$id.json").exists())
        assertFalse(File(root, "terminal-jobs/$id.log").exists())
    }
}
