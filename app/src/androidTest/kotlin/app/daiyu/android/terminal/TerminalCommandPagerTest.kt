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
}
