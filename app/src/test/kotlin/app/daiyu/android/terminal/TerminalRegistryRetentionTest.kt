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
    private fun context(root: File) = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir() = root
    }
    private fun fixture(test: (File, TerminalRegistry, String) -> Unit) {
        val root = Files.createTempDirectory("registry-retention").toFile()
        val registry = TerminalRegistry(context(root)).apply {
            configure(UUID.randomUUID().toString(), TerminalConfiguration())
            backendOverride = SelectedBackend("android", 12345, false, listOf("not-executed"), emptyMap(), root, true)
            processPidReader = { 4242L }
            processStartReader = { "start" }
            startProcess = { TestProcess() }
            daemonExecutable = { "fake-setsid" }
        }
        try {
            val session = registry.execute("terminal_open", JSONObject().put("request_id", "open-android")).getString("session_id")
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

    private fun awaitPump(id: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (Thread.getAllStackTraces().keys.any { it.name == "terminal-log-$id" && it.isAlive }) {
            check(System.nanoTime() < deadline) { "Output pump did not finish" }
            Thread.yield()
        }
    }
    private fun assertResourcesReleased(id: String) {
        val field = TerminalRegistry::class.java.getDeclaredField("jobs").apply { isAccessible = true }
        val job = (field.get(null) as Map<*, *>)[id]!!
        for (name in listOf("handle", "reader", "startupCleanup")) {
            val resource = job.javaClass.getDeclaredField(name).apply { isAccessible = true }
            assertNull(name, resource.get(job))
        }
    }

    @Test fun continuous130CommandsKeepAllHistoryAndReplayNeverStartsAgain() = fixture { root, registry, session ->
        var starts = 0
        registry.startProcess = { starts++; TestProcess() }
        var firstId = ""
        val allIds = mutableListOf<String>()
        repeat(130) { index ->
            val reopened = registry.execute("terminal_open", JSONObject().put("request_id", "open-android"))
            assertEquals(session, reopened.getString("session_id"))
            val result = registry.execute("terminal_exec", command(session, index))
            assertEquals(result.toString(), "executed", result.optString("execution"))
            allIds.add(result.getString("job_id"))
            if (index == 0) firstId = allIds.last()
        }
        assertEquals(130, starts)
        val directory = File(root, "terminal-jobs")
        assertEquals(130, directory.listFiles()!!.count { it.extension == "json" })
        assertEquals(allIds.toSet(), directory.listFiles()!!.filter { it.extension == "json" }.map { it.nameWithoutExtension }.toSet())
        assertEquals(allIds.toSet(), directory.listFiles()!!.filter { it.extension == "log" }.map { it.nameWithoutExtension }.toSet())
        val listed = registry.inspectJobs().getJSONArray("jobs")
        assertEquals(130, listed.length())
        assertEquals(allIds.toSet(), (0 until listed.length()).map { listed.getJSONObject(it).getString("job_id") }.toSet())
        assertEquals("actual output", registry.inspectJob(allIds.last()).getString("stdout"))
        assertEquals("actual output", File(directory, "$firstId.log").readText())
        assertEquals("actual output", registry.inspectJob(firstId).getString("stdout"))
        val replay = registry.execute("terminal_exec", command(session, 0))
        assertTrue(replay.getBoolean("replayed_execution"))
        assertEquals(firstId, replay.getString("job_id"))
        assertEquals(130, starts)
        val metadata = JSONObject(File(directory, "$firstId.json").readText())
        val durable = metadata.getJSONObject("completion")
        assertEquals(1, durable.getInt("format"))
        assertEquals(firstId, durable.getString("job_id"))
        assertEquals("executed", durable.getString("execution"))
        assertEquals(0, durable.getInt("exit_code"))
        assertTrue(durable.getBoolean("output_complete"))
        val reopened = TerminalRegistry(context(root)).apply {
            configure(metadata.getString("scope"), TerminalConfiguration())
            startProcess = { throw AssertionError("Replay must not launch") }
        }
        try {
            val restoredReplay = reopened.execute("terminal_exec", command(session, 0))
            assertTrue(restoredReplay.getBoolean("replayed_execution"))
            assertEquals(firstId, restoredReplay.getString("job_id"))
        } finally { reopened.close() }
        assertEquals(130, starts)
        assertTrue(File(directory, "$firstId.json").isFile)
    }

    @Test fun sixtyFiveRunningAsyncJobsAreNotRejectedByCumulativeQuota() = fixture { root, registry, session ->
        var starts = 0
        registry.startProcess = { starts++; TestProcess(true) }
        repeat(65) { index ->
            val result = registry.execute("terminal_exec", command(session, index, "async"))
            assertEquals(result.toString(), "dispatched", result.optString("state"))
        }
        assertEquals(65, starts)
        assertEquals(65, File(root, "terminal-jobs").listFiles()!!.count { it.extension == "json" })
    }

    @Test fun moreThan64DistinctSessionsCanOpen() = fixture { _, registry, _ ->
        val sessions = (0 until 80).map { index ->
            val result = registry.execute("terminal_open", JSONObject().put("request_id", "unique-open-$index"))
            assertTrue(result.toString(), result.getBoolean("ok"))
            result.getString("session_id")
        }
        assertEquals(80, sessions.toSet().size)
        assertEquals("executed", registry.execute("terminal_exec", command(sessions.first(), 0)).getString("execution"))
        assertEquals("executed", registry.execute("terminal_exec", command(sessions.last(), 1)).getString("execution"))
    }

    @Test fun persistedLogsAbove16MiBDoNotBlockDispatchOrGetDeleted() = fixture { root, registry, session ->
        val seed = registry.execute("terminal_exec", command(session, 0)).getString("job_id")
        val directory = File(root, "terminal-jobs")
        val template = File(directory, "$seed.json").readText()
        val bytes = ByteArray(262144) { 79 }
        val oldIds = (0 until 80).map {
            UUID.randomUUID().toString().also { id ->
                File(directory, "$id.json").writeText(JSONObject(template).put("job_id", id).toString())
                File(directory, "$id.log").writeBytes(bytes)
            }
        }
        assertTrue(directory.listFiles()!!.filter { it.extension == "log" }.sumOf { it.length() } > 16L * 1024 * 1024)
        val next = registry.execute("terminal_exec", command(session, 1))
        assertEquals(next.toString(), "executed", next.optString("execution"))
        oldIds.forEach { id ->
            assertTrue(File(directory, "$id.json").isFile)
            assertArrayEquals(bytes, File(directory, "$id.log").readBytes())
            assertEquals(262144L, registry.inspectJob(id).getLong("total_bytes"))
        }
        assertEquals(82, registry.inspectJobs().getJSONArray("jobs").length())
    }

    @Test fun eofObservedBeforePumpFinishesDoesNotLoseLaterOutput() = fixture { root, registry, session ->
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
            repeat(64) { registry.execute("terminal_exec", command(session, it + 1)) }
            val early = registry.inspectJob(id)
            assertEquals(0L, early.getLong("total_bytes"))
            assertFalse(early.optBoolean("output_complete"))
            release.countDown()
            awaitPump(id)
            assertEquals("executed", registry.execute("terminal_exec", command(session, 65)).optString("execution"))
            assertEquals("late output", registry.inspectJob(id).getString("stdout"))
            assertEquals("late output", File(root, "terminal-jobs/$id.log").readText())
        } finally { release.countDown() }
    }

    @Test fun unreadCompletedAsyncHistorySurvivesFurtherCommandsAndRepeatedReads() = fixture { root, registry, session ->
        val ids = (0 until 65).map { registry.execute("terminal_exec", command(session, it, "async")).getString("job_id") }
        val id = ids.first()
        awaitPump(id)
        assertEquals("executed", registry.execute("terminal_exec", command(session, 65)).optString("execution"))
        repeat(2) {
            val page = registry.inspectJob(id)
            assertTrue(page.optBoolean("output_complete"))
            assertEquals("actual output", page.getString("stdout"))
            assertEquals("executed", page.getString("execution"))
        }
        assertResourcesReleased(id)
        assertTrue(File(root, "terminal-jobs/$id.json").isFile)
        assertTrue(File(root, "terminal-jobs/$id.log").isFile)
    }

    @Test fun releasedCompletionStillStopsAndDrainsWithoutLosingExitStatus() = fixture { _, registry, session ->
        val id = registry.execute("terminal_exec", command(session, 0)).getString("job_id")
        awaitPump(id)
        registry.inspectJob(id)
        assertResourcesReleased(id)
        val stopped = registry.stopOwnedJob(id)
        assertTrue(stopped.getBoolean("stopped"))
        assertEquals("exited", stopped.getString("state"))
        assertEquals("executed", stopped.getString("execution"))
        assertEquals(0, stopped.getInt("exit_code"))
        val closed = registry.execute("terminal_close", JSONObject().put("request_id", "close").put("session_id", session))
        assertTrue(closed.getBoolean("cancellation_complete"))
        registry.close()
        assertTrue(registry.awaitOrdinaryTermination(5000))
        assertTrue(registry.awaitJobTermination(id, 5000))
        assertEquals("actual output", registry.inspectJob(id).getString("stdout"))
    }

    @Test fun finishedDaemonCanReleaseResourcesAndRetainInspectableHistoryAfterClose() = fixture { root, registry, session ->
        val id = registry.execute("terminal_exec", command(session, 0, "daemon")).getString("job_id")
        awaitPump(id)
        registry.inspectJob(id)
        assertResourcesReleased(id)
        registry.close()
        assertTrue(registry.awaitJobTermination(id, 5000))
        assertEquals("actual output", registry.inspectJob(id).getString("stdout"))
        assertTrue(File(root, "terminal-jobs/$id.json").isFile)
        assertTrue(File(root, "terminal-jobs/$id.log").isFile)
    }

    @Test fun confirmedStartupCleanupReleasesProcessButExecutionRemainsUnknown() = fixture { root, registry, session ->
        val process = TestProcess(true)
        val cleanup = PtyStartupCleanup(process) { process.destroy() }
        registry.startProcess = { throw PtyStartupFailure("injected", cleanup) }
        val result = registry.execute("terminal_exec", command(session, 0))
        assertEquals("unknown", result.getString("execution"))
        val id = result.getString("job_id")
        val stopped = registry.stopOwnedJob(id)
        assertTrue(stopped.getBoolean("stopped"))
        assertEquals("unknown", stopped.getString("execution"))
        assertTrue(stopped.isNull("exit_code"))
        assertResourcesReleased(id)
        registry.close()
        assertTrue(registry.awaitOrdinaryTermination(5000))
        assertTrue(registry.awaitJobTermination(id, 5000))
        assertEquals("unknown", registry.inspectJob(id).getString("execution"))
        assertTrue(File(root, "terminal-jobs/$id.json").isFile)
    }

    @Test fun releasedProcessWithIncompleteOutputNeverBecomesExecuted() = fixture { _, registry, session ->
        registry.startProcess = { TestProcess(output = object : InputStream() {
            override fun read(): Int = throw java.io.IOException("injected output failure")
        }) }
        val result = registry.execute("terminal_exec", command(session, 0))
        assertEquals("unknown", result.getString("execution"))
        val id = result.getString("job_id")
        awaitPump(id)
        assertEquals("unknown", registry.inspectJob(id).getString("execution"))
        assertResourcesReleased(id)
        assertEquals("unknown", registry.stopOwnedJob(id).getString("execution"))
    }
    @Test fun asyncAndDaemonNaturalExitPersistWithoutPollingOrFurtherCommands() = fixture { root, registry, session ->
        for ((index, mode) in listOf("async", "daemon").withIndex()) {
            val result = registry.execute("terminal_exec", command(session, index, mode))
            val id = result.getString("job_id")
            awaitPump(id)
            val metadata = JSONObject(File(root, "terminal-jobs/$id.json").readText())
            assertEquals(0, metadata.getJSONObject("completion").getInt("exit_code"))
            assertResourcesReleased(id)
        }
    }

    @Test fun failedCompletionSaveKeepsUnknownDiskEvidenceAndStillReleasesResources() = fixture { root, registry, session ->
        var saves = 0
        registry.beforeJobSave = { if (++saves == 4) throw java.io.IOException("injected completion save failure") }
        val result = registry.execute("terminal_exec", command(session, 0))
        val id = result.getString("job_id")
        awaitPump(id)
        assertEquals(4, saves)
        assertFalse(JSONObject(File(root, "terminal-jobs/$id.json").readText()).has("completion"))
        assertEquals("executed", registry.inspectJob(id).getString("execution"))
        assertResourcesReleased(id)
    }

}
