package app.daiyu.android.terminal

import android.content.Context
import android.content.ContextWrapper
import android.test.InstrumentationTestCase
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

@Suppress("DEPRECATION")
class TerminalCommandPagerTest : InstrumentationTestCase() {
    fun testCommandExportsPagerDefaultsAndKeepsTheRealNonzeroExit() {
        val root = File(instrumentation.targetContext.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
            override fun getCacheDir(): File = root
        }
        val registry = TerminalRegistry(context)
        registry.configure(UUID.randomUUID().toString(), TerminalConfiguration(workingDirectory = root,
            environment = mapOf("PAGER" to "unexpected-pager", "GIT_PAGER" to "unexpected-git-pager",
                "SYSTEMD_PAGER" to "unexpected-systemd-pager")))
        try {
            val session = registry.execute("terminal_open", JSONObject().put("request_id", "open")).getString("session_id")
            val result = registry.execute("terminal_exec", JSONObject().put("request_id", "command")
                .put("session_id", session).put("mode", "sync").put("timeout_ms", 10_000)
                .put("command", "/system/bin/sh -c 'printf \"%s\\n\" \"\$PAGER\" \"\$GIT_PAGER\" \"\$SYSTEMD_PAGER\"'; exit 1"))
            assertTrue(result.toString(), result.getBoolean("ok"))
            assertEquals("executed", result.getString("execution"))
            assertEquals(1, result.getInt("exit_code"))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            var page = result
            while (!page.optBoolean("output_complete") && System.nanoTime() < deadline) {
                Thread.sleep(5)
                page = registry.inspectJob(result.getString("job_id"))
            }
            assertTrue(page.toString(), page.getBoolean("output_complete"))
            assertEquals("cat\ncat\ncat\n", page.getString("stdout"))
        } finally {
            registry.close()
            assertTrue(registry.awaitOrdinaryTermination(3000))
            root.deleteRecursively()
        }
    }
    fun testHistoricalStatusesUseRealExitCodesAndRecoverLegacyJournalWithoutReplay() {
        val root = File(instrumentation.targetContext.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
            override fun getCacheDir(): File = root
        }
        val owner = UUID.randomUUID().toString()
        val registry = TerminalRegistry(context)
        registry.configure(UUID.randomUUID().toString(), TerminalConfiguration(workingDirectory = root), owner)
        try {
            val session = registry.execute("terminal_open", JSONObject().put("request_id", "open")).getString("session_id")
            for (code in listOf(0, 127)) {
                val result = registry.execute("terminal_exec", JSONObject().put("request_id", "status-$code")
                    .put("session_id", session).put("mode", "sync").put("timeout_ms", 10_000)
                    .put("command", "printf original-status; exit $code"))
                assertEquals(result.toString(), "executed", result.getString("execution"))
                val id = result.getString("job_id")
                val metadata = File(root, "terminal-jobs/$id.json")
                awaitCompletion(metadata)
                assertEquals(code, JSONObject(metadata.readText()).getJSONObject("completion").getInt("exit_code"))
                val history = TerminalJobHistoryReader(root)
                val persisted = history.list(owner).records.single { it.jobId == id }
                assertEquals(code, persisted.exitCode)
                assertEquals("exited", history.page(owner, persisted).getString("state"))
                // Remove only the new receipt to reproduce an existing dispatched legacy record.
                metadata.writeText(JSONObject(metadata.readText()).apply { remove("completion") }.toString())
                val bytes = metadata.readBytes().toList()
                val recovered = TerminalJobHistoryReader(root).list(owner).records.single { it.jobId == id }
                assertEquals(code, recovered.exitCode)
                val page = TerminalJobHistoryReader(root).page(owner, recovered)
                assertEquals("executed", page.getString("execution"))
                assertEquals(code, page.getInt("exit_code"))
                assertTrue(page.getBoolean("history_read_only"))
                assertFalse(page.has("pid")); assertFalse(page.has("session_id"))
                assertEquals(bytes, metadata.readBytes().toList())
                assertEquals("original-status", page.getString("stdout"))
            }
        } finally {
            registry.close()
            assertTrue(registry.awaitOrdinaryTermination(3000))
            root.deleteRecursively()
        }
    }

    private fun awaitCompletion(metadata: File) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!JSONObject(metadata.readText()).has("completion")) {
            assertTrue("Completion must persist without polling a registry", System.nanoTime() < deadline)
            Thread.sleep(5)
        }
    }
    fun testAsyncCompletionPersistsWithoutAnyPollOrFurtherCommand() {
        val root = File(instrumentation.targetContext.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
            override fun getCacheDir(): File = root
        }
        val owner = UUID.randomUUID().toString()
        val registry = TerminalRegistry(context)
        registry.configure(UUID.randomUUID().toString(), TerminalConfiguration(workingDirectory = root), owner)
        try {
            val session = registry.execute("terminal_open", JSONObject().put("request_id", "open")).getString("session_id")
            val result = registry.execute("terminal_exec", JSONObject().put("request_id", "async")
                .put("session_id", session).put("mode", "async").put("command", "printf async-status; exit 1"))
            assertEquals("dispatched", result.getString("state"))
            val metadata = File(root, "terminal-jobs/${result.getString("job_id")}.json")
            awaitCompletion(metadata)
            File(root, "terminal-journal").deleteRecursively()
            val history = TerminalJobHistoryReader(root)
            val record = history.list(owner).records.single()
            assertEquals(1, record.exitCode)
            assertEquals("async-status", history.page(owner, record).getString("stdout"))
        } finally {
            registry.close()
            assertTrue(registry.awaitOrdinaryTermination(3000))
            root.deleteRecursively()
        }
    }

    fun testLegacyJournalLinksAndFifoAreRejectedWithoutLosingReadOnlyLogs() {
        val root = File(instrumentation.targetContext.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        val jobs = File(root, "terminal-jobs").apply { mkdirs() }
        val journals = File(root, "terminal-journal").apply { mkdirs() }
        val id = UUID.randomUUID().toString()
        val owner = UUID.randomUUID().toString()
        val metadata = JSONObject().put("job_id", id).put("owner", owner).put("scope", "original-scope")
            .put("session_id", UUID.randomUUID().toString()).put("backend", "android").put("mode", "sync")
        File(jobs, "$id.json").writeText(metadata.toString())
        File(jobs, "$id.log").writeText("original read-only log")
        val journal = File(journals, "original-scope.json")
        val result = JSONObject(metadata.toString()).put("ok", true).put("state", "exited")
            .put("execution", "executed").put("exit_code", 0).put("output_complete", true)
        val outside = File(root, "original-scope.json").apply { writeText(JSONObject().put("format", 4)
            .put("binding", "a".repeat(64)).put("lifecycle", "active").put("entries", JSONObject()
                .put("original", JSONObject().put("state", "committed").put("result", result))).toString()) }
        try {
            java.nio.file.Files.createSymbolicLink(journal.toPath(), outside.toPath())
            var history = TerminalJobHistoryReader(root)
            var record = history.list(owner).records.single()
            assertNull(record.exitCode)
            assertEquals("original read-only log", history.page(owner, record).getString("stdout"))
            java.nio.file.Files.delete(journal.toPath())
            android.system.Os.mkfifo(journal.path, 384)
            history = TerminalJobHistoryReader(root)
            record = history.list(owner).records.single()
            assertNull(record.exitCode)
            assertEquals("unknown", history.page(owner, record).getString("execution"))
            java.nio.file.Files.delete(journal.toPath())
            assertTrue(journals.delete())
            java.nio.file.Files.createSymbolicLink(journals.toPath(), root.toPath())
            assertNull(TerminalJobHistoryReader(root).list(owner).records.single().exitCode)
            java.nio.file.Files.delete(journals.toPath())
        } finally {
            java.nio.file.Files.deleteIfExists(journal.toPath())
            if (java.nio.file.Files.isSymbolicLink(journals.toPath())) java.nio.file.Files.delete(journals.toPath())
            root.deleteRecursively()
        }
    }

}
