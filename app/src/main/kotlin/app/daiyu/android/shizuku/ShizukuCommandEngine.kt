package app.daiyu.android.shizuku

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One service generation; retained IDs are never evicted and rerun. No PTY or daemon support. */
internal class ShizukuCommandEngine(private val launcher: () -> Process = {
    ProcessBuilder("/system/bin/sh").directory(File("/data/local/tmp")).redirectErrorStream(true).apply {
        environment().clear()
        environment().putAll(mapOf("PATH" to "/system/bin:/system/xbin", "HOME" to "/data/local/tmp", "TMPDIR" to "/data/local/tmp"))
    }.start()
}) : AutoCloseable {
    private val lock = Any()
    private val jobs = linkedMapOf<String, Job>()
    private val timer = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "shizuku-deadline").apply { isDaemon = true } }
    private var closed = false
    internal class Job(val id: String, val fingerprint: String) {
        val lock = Any()
        val output = ByteArrayOutputStream()
        var process: Process? = null
        var reason: String? = null
        var exitCode: Int? = null
        var outputComplete = false
        var truncated = false
        var runnerDone = false
        var activeIo = 0
    }
    sealed interface Admission {
        data object Accepted : Admission
        data class Rejected(val reason: String) : Admission
    }
    fun submit(id: String, script: ByteArray, timeoutMillis: Long): Admission {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        require(script.size in 1..131_072 && script.none { it == 0.toByte() })
        require(timeoutMillis in 1..120_000)
        val frozen = script.copyOf()
        val fingerprint = MessageDigest.getInstance("SHA-256").run {
            update(frozen); update(0.toByte()); update(timeoutMillis.toString().toByteArray(Charsets.US_ASCII))
            digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        val job = synchronized(lock) {
            if (closed) return Admission.Rejected("backend_closed")
            jobs[id]?.let { return if (it.fingerprint == fingerprint) Admission.Accepted else Admission.Rejected("request_id_conflict") }
            if (jobs.size >= 64) return Admission.Rejected("job_retention_limit")
            if (jobs.values.count { synchronized(it.lock) { !it.runnerDone || it.activeIo != 0 || it.process?.isAlive == true } } >= 4) return Admission.Rejected("live_job_limit")
            Job(id, fingerprint).also { jobs[id] = it }
        }
        try {
            val deadline = timer.schedule({ stop(job, "timeout_outcome_unknown") }, timeoutMillis, TimeUnit.MILLISECONDS)
            Thread({ try { runJob(job, frozen) } finally { deadline.cancel(false) } }, "shizuku-command").apply { isDaemon = true; start() }
        } catch (_: Exception) {
            synchronized(job.lock) { job.reason = "dispatch_outcome_unknown"; job.runnerDone = true }
        }
        return Admission.Accepted
    }
    private fun runJob(job: Job, script: ByteArray) {
        var process: Process? = null
        var writer: Thread? = null
        var reader: Thread? = null
        try {
            synchronized(job.lock) { if (job.reason != null) { job.runnerDone = true; return } }
            val child = launcher(); process = child
            val cancelled = synchronized(job.lock) { job.process = child; job.reason != null }
            if (cancelled) { stop(job, "cancel_requested"); return }
            writer = startIo(job, "shizuku-stdin") {
                // Stdin admission linearizes against stop; I/O never holds the job lock.
                if (synchronized(job.lock) { job.reason != null }) return@startIo
                try { child.outputStream.use { it.write(script); it.flush() } }
                catch (_: Exception) { stop(job, "stdin_delivery_unknown") }
            }
            reader = startIo(job, "shizuku-stdout") {
                try {
                    child.inputStream.use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer); if (count < 0) break
                            synchronized(job.lock) {
                                val keep = minOf(count, 262_144 - job.output.size())
                                if (keep > 0) job.output.write(buffer, 0, keep)
                                if (keep < count) job.truncated = true
                            }
                        }
                    }
                    synchronized(job.lock) { job.outputComplete = true }
                } catch (_: Exception) { stop(job, "output_outcome_unknown") }
            }
            val exit = child.waitFor()
            writer.join(500); reader.join(500)
            synchronized(job.lock) {
                if (writer.isAlive || reader.isAlive || !job.outputComplete) {
                    if (job.reason == null) job.reason = "io_completion_unknown"
                } else if (job.reason == null) job.exitCode = exit
            }
            if (writer.isAlive || reader.isAlive) {
                startIo(job, "shizuku-stream-close") {
                    runCatching { child.outputStream.close() }
                    runCatching { child.inputStream.close() }
                    runCatching { child.errorStream.close() }
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt(); stop(job, "wait_interrupted_unknown")
        } catch (_: Exception) { stop(job, "dispatch_outcome_unknown") }
        finally {
            if (process?.isAlive == true) runCatching { process.destroyForcibly() }
            synchronized(job.lock) { job.runnerDone = true }
        }
    }
    private fun startIo(job: Job, name: String, work: () -> Unit): Thread {
        synchronized(job.lock) { job.activeIo++ }
        val thread = Thread({
            try { work() } finally { synchronized(job.lock) { job.activeIo-- } }
        }, name).apply { isDaemon = true }
        try { thread.start() } catch (failure: Exception) {
            synchronized(job.lock) { job.activeIo-- }; throw failure
        }
        return thread
    }
    fun page(id: String, offset: Int, limit: Int): ShizukuCommandPage {
        require(offset >= 0 && limit in 1..65_536)
        val job = synchronized(lock) { checkNotNull(jobs[id]) { "unknown_request" } }
        return synchronized(job.lock) {
            val bytes = job.output.toByteArray(); require(offset <= bytes.size)
            val end = minOf(bytes.size, offset + limit)
            val outcome = job.reason?.let { ShizukuOutcome.Unknown(it) }
                ?: job.exitCode?.let { ShizukuOutcome.Completed(it) } ?: ShizukuOutcome.Running
            ShizukuCommandPage(outcome, bytes.copyOfRange(offset, end), offset, end, bytes.size, job.truncated, job.outputComplete)
        }
    }
    fun cancel(id: String): Boolean {
        val job = synchronized(lock) { jobs[id] } ?: return false
        stop(job, "cancel_requested"); return true
    }
    private fun stop(job: Job, reason: String) {
        val process = synchronized(job.lock) {
            if (job.exitCode != null) return
            if (job.reason == null) job.reason = reason
            job.process
        }
        if (process != null) {
            runCatching { process.destroy() }
            try { timer.schedule({ if (process.isAlive) runCatching { process.destroyForcibly() } }, 250, TimeUnit.MILLISECONDS) }
            catch (_: java.util.concurrent.RejectedExecutionException) { runCatching { process.destroyForcibly() } }
        }
    }
    fun isDrained(): Boolean = synchronized(lock) {
        closed && jobs.values.all { job -> synchronized(job.lock) {
            job.runnerDone && job.activeIo == 0 && job.process?.isAlive != true
        } }
    }

    override fun close() {
        val owned = synchronized(lock) { if (closed) return; closed = true; jobs.values.toList() }
        owned.forEach { stop(it, "backend_closed_unknown") }
        timer.shutdown()
    }
}
