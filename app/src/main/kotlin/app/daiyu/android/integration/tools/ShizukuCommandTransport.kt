package app.daiyu.android.integration.tools

import app.daiyu.android.shizuku.*
import app.daiyu.android.terminal.TerminalConfiguration
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Internal terminal protocol adapter; authorization and deduplication remain in RunCommandTools. */
internal class ShizukuCommandTransport(
    private val configuration: TerminalConfiguration,
    private val expectedUid: Int?,
    private val connect: () -> ShizukuBackend?,
) : CommandTransport {
    private class Job(val requestId: String) {
        val output = ByteArrayOutputStream()
        var page: ShizukuCommandPage? = null
        var reason: String? = null
    }
    private val lock = Any()
    private val session = UUID.randomUUID().toString()
    private val jobs = linkedMapOf<String, Job>()
    private var backend: ShizukuBackend? = null
    private var closed = false
    private var attempted = false
    private var creating = false
    private var active = 0
    private var closeStarted = false
    private var cleanup: ShizukuCloseResult? = null
    private val drained = CountDownLatch(1)
    private var failure: String? = null
    init {
        require(configuration.backend == "shizuku" && !configuration.root && configuration.linux == null)
        require(expectedUid == null || expectedUid in setOf(0, 2000))
        require(configuration.workingDirectory?.isAbsolute == true && '\u0000' !in configuration.workingDirectory.path)
        require(configuration.environment.all { (key, value) -> key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) && '\u0000' !in value })
    }

    override fun execute(name: String, arguments: JSONObject): JSONObject {
        synchronized(lock) { if (closed) return rejected("shizuku_run_closed"); active++ }
        try {
            return when (name) {
                "terminal_open" -> open()
                "terminal_exec" -> executeCommand(arguments)
                else -> rejected("shizuku_terminal_operation_unsupported")
            }
        } finally { synchronized(lock) { active--; finishClose() } }
    }

    private fun open(): JSONObject {
        synchronized(lock) {
            if (closed) return rejected("shizuku_run_closed")
            failure?.let { return rejected(it) }
            backend?.let { return identity().put("ok", true).put("session_id", session) }
            if (attempted) return rejected("shizuku_backend_unavailable_no_retry")
            if (expectedUid == null) return rejected("shizuku_authorization_unavailable")
            attempted = true
            creating = true
        }
        try {
            val real = connect() ?: error("shizuku_backend_unavailable_no_retry")
            val stopped = synchronized(lock) {
                backend = real
                if (real.identity.uid != expectedUid) failure = "shizuku_identity_changed"
                closed || failure != null
            }
            if (stopped) { close(); return rejected(failure ?: "shizuku_run_closed") }
            return identity().put("ok", true).put("session_id", session)
        } catch (_: Exception) {
            synchronized(lock) { failure = "shizuku_backend_unavailable_no_retry" }
            return rejected("shizuku_backend_unavailable_no_retry")
        } finally {
            synchronized(lock) { creating = false }
            if (synchronized(lock) { closed }) requestClose()
        }
    }

    private fun executeCommand(arguments: JSONObject): JSONObject {
        val real = synchronized(lock) { backend } ?: return rejected("shizuku_terminal_not_open")
        if (arguments.optString("session_id") != session) return rejected("shizuku_session_mismatch")
        if (arguments.optString("mode") != "sync") return rejected("android_shizuku_jobs_not_supported")
        val command = try {
            val script = buildString {
                configuration.environment.toSortedMap().forEach { (key, value) -> append("export $key=${quote(value)}\n") }
                append("cd ${quote(checkNotNull(configuration.workingDirectory).path)} || exit 125\n")
                append(arguments.getString("command")); append('\n')
            }
            ShizukuCommand(arguments.getString("request_id"), script, arguments.getLong("timeout_ms"))
        } catch (_: Exception) { return rejected("shizuku_command_invalid") }
        val id = UUID.randomUUID().toString()
        val job = Job(command.requestId)
        synchronized(lock) {
            if (closed || failure != null) return rejected(failure ?: "shizuku_run_closed")
            if (jobs.size >= 64) return rejected("shizuku_command_limit")
            jobs[id] = job
        }
        try {
            val handle = when (val result = real.execute(command)) {
                is ShizukuDispatch.NotExecuted -> { synchronized(lock) { jobs.remove(id) }; return rejected(result.reason) }
                is ShizukuDispatch.Unknown -> return unknown(id, job, result.reason)
                is ShizukuDispatch.Dispatched -> result.handle
            }
            if (handle.requestId != command.requestId || handle.identity !== real.identity)
                return unknown(id, job, "shizuku_dispatch_identity_mismatch")
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(command.timeoutMillis + 5000)
            while (true) {
                if (synchronized(lock) { closed } || Thread.currentThread().isInterrupted || System.nanoTime() >= deadline)
                    return unknown(id, job, "shizuku_execution_interrupted")
                val offset = job.output.size()
                val page = real.poll(handle, offset, 16384)
                if (!page.validAt(offset, 16384)) return unknown(id, job, "shizuku_invalid_output_page")
                synchronized(lock) { job.output.write(page.bytes); job.page = page }
                when (val outcome = page.outcome) {
                    is ShizukuOutcome.Unknown -> return unknown(id, job, outcome.reason)
                    is ShizukuOutcome.Completed -> {
                        if (!page.outputComplete) return unknown(id, job, "shizuku_output_incomplete")
                        if (page.nextOffset == page.retainedBytes) return synchronized(lock) {
                            identity().put("ok", outcome.exitCode == 0).put("execution", "executed").put("job_id", id)
                                .put("exit_code", outcome.exitCode).put("output", job.output.toString(Charsets.UTF_8.name()))
                                .put("truncated", page.truncated).put("output_complete", true)
                        }
                    }
                    ShizukuOutcome.Running -> if (page.bytes.isEmpty()) Thread.sleep(25)
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return unknown(id, job, "shizuku_execution_interrupted")
        } catch (_: Exception) { return unknown(id, job, "shizuku_execution_unconfirmed") }
    }

    private fun unknown(id: String, job: Job, reason: String): JSONObject = synchronized(lock) {
        job.reason = reason
        failure = "shizuku_command_outcome_unknown_no_retry"
        identity().put("ok", false).put("execution", "unknown").put("job_id", id).put("error", reason)
    }

    /** Reads only retained diagnostics: never reconnects or issues a second command after UNKNOWN. */
    override fun read(id: String, offset: Long, limit: Int): JSONObject = synchronized(lock) {
        val job = jobs[id] ?: return rejected("job_not_owned_by_run")
        if (offset !in 0..262144 || limit !in 1..65536) return rejected("shizuku_output_range_invalid")
        val bytes = job.output.toByteArray()
        val start = minOf(offset.toInt(), bytes.size)
        val end = minOf(start + limit, bytes.size)
        val outcome = job.page?.outcome
        identity().put("ok", true).put("job_id", id).put("diagnostic_only", true)
            .put("execution", if (job.reason != null) "unknown" else if (outcome is ShizukuOutcome.Completed) "executed" else "unknown")
            .put("reason", job.reason).put("request_id", job.requestId)
            .put("output", String(bytes, start, end - start, Charsets.UTF_8))
            .put("offset", start).put("next_offset", end).put("retained_bytes", bytes.size)
            .put("truncated", job.page?.truncated ?: false).put("output_complete", job.reason == null && job.page?.outputComplete == true)
            .put("exit_code", (outcome as? ShizukuOutcome.Completed)?.exitCode)
    }

    private fun identity(): JSONObject = JSONObject().put("backend", "shizuku").also { json ->
        backend?.identity?.let { json.put("uid", it.uid).put("service_generation", it.serviceGeneration)
            .put("permission_generation", it.permissionGeneration) }
    }
    private fun rejected(reason: String) = JSONObject().put("ok", false).put("execution", "not_executed").put("error", reason)
    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    override fun close() { synchronized(lock) { closed = true }; requestClose() }
    private fun requestClose() {
        val real = synchronized(lock) {
            if (creating || closeStarted) { finishClose(); return }
            val value = backend
            if (value == null) {
                cleanup = if (attempted) ShizukuCloseResult.Unknown("shizuku_connection_cleanup_unconfirmed") else ShizukuCloseResult.Drained
                finishClose(); return
            }
            closeStarted = true
            cleanup = null
            value
        }
        try { real.closeWithResult { result -> synchronized(lock) { cleanup = result; finishClose() } } }
        catch (_: Exception) { synchronized(lock) { cleanup = ShizukuCloseResult.Unknown("shizuku_close_unconfirmed"); finishClose() } }
    }
    private fun finishClose() { if (closed && !creating && active == 0 && cleanup != null) drained.countDown() }
    override fun awaitTermination(timeoutMillis: Long): Boolean = drained.await(timeoutMillis, TimeUnit.MILLISECONDS) &&
        synchronized(lock) { cleanup === ShizukuCloseResult.Drained }
}
