package app.daiyu.android.terminal

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Trusted host entrypoint; helpers must be packaged executables, never model-supplied paths. */
class PtySession internal constructor(
    private val process: Process,
    private val journal: OperationJournal,
    private val rootScoped: Boolean,
) : AutoCloseable {
    internal constructor(process: Process, journal: OperationJournal) : this(process, journal, false)
    private val readerFinished = CountDownLatch(1)
    private var helloSeen = false
    private var execErrorSeen = false
    private var truncationSeen = false
    @Volatile private var nativeScope: String? = null
    @Volatile private var rootDrained = false
    @Volatile private var protocolBroken = false
    private val lock = Any()
    private val ready = CountDownLatch(1)
    private val output = ByteArray(262144)
    private var retained = 0
    private var truncated = false
    @Volatile private var started = false
    @Volatile private var closed = false
    @Volatile private var failure: String? = null
    @Volatile private var waitStatus: Int? = null
    private val writer = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(16),
        { task -> Thread(task, "daiyu-pty-input").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    private val reader = Thread({ pump() }, "daiyu-pty-output").apply { isDaemon = true; start() }

    fun write(requestId: String, bytes: ByteArray, inputSequence: Long = 0): JSONObject {
        require(inputSequence >= 0)
        require(bytes.size <= 65536)
        val frozen = bytes.copyOf()
        val args = JSONObject().put("data_base64", java.util.Base64.getEncoder().encodeToString(frozen)).put("input_sequence", inputSequence)
        return journal.once(requestId, "pty_write", args, "input-$inputSequence") { control('W', frozen) }
    }

    fun resize(requestId: String, rows: Int, columns: Int, controlSequence: Long = 0): JSONObject {
        require(controlSequence >= 0)
        require(rows in 1..1000 && columns in 1..1000)
        val args = JSONObject().put("rows", rows).put("columns", columns).put("control_sequence", controlSequence)
        return journal.once(requestId, "pty_resize", args, "control-$controlSequence") {
            control('R', byteArrayOf((rows shr 8).toByte(), rows.toByte(), (columns shr 8).toByte(), columns.toByte()))
        }
    }

    fun interrupt(requestId: String, controlSequence: Long = 0): JSONObject {
        require(controlSequence >= 0)
        return journal.once(requestId, "pty_interrupt", JSONObject().put("control_sequence", controlSequence), "control-$controlSequence") { control('I', ByteArray(0)) }
    }

    /** Input delivery is not shell command completion. Never infer exit codes from output. */
    private fun control(type: Char, bytes: ByteArray): JSONObject {
        if (!started || closed || failure != null || waitStatus != null || rootDrained || !process.isAlive) return OperationJournal.failure("pty_session_unavailable")
        val future = try {
            writer.submit<JSONObject> {
                if (closed || failure != null || !started || waitStatus != null || rootDrained || !process.isAlive) return@submit OperationJournal.failure("pty_session_unavailable")
                try {
                    val stream = DataOutputStream(process.outputStream)
                    stream.writeByte(type.code); stream.writeInt(bytes.size); stream.write(bytes); stream.flush()
                    JSONObject().put("ok", true).put("execution", "executed").put("helper_input_written", true).put("pty_delivery", "unknown")
                        .put("command_completion", "unknown")
                } catch (_: Exception) {
                    failure = "pty_input_delivery_unknown"
                    JSONObject().put("ok", false).put("execution", "unknown").put("error", failure)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { return OperationJournal.failure("pty_input_queue_full") }
        return try { future.get(3, TimeUnit.SECONDS) }
        catch (error: Exception) {
            failure = "pty_input_delivery_unknown" // Fence the next queued task before waking the writer.
            future.cancel(true)
            if (error is InterruptedException) Thread.currentThread().interrupt()
            JSONObject().put("ok", false).put("execution", "unknown").put("error", failure)
        }
    }

    fun read(offset: Int = 0, limit: Int = 16384): JSONObject = synchronized(lock) {
        require(offset in 0..retained && limit in 1..65536)
        val bytes = output.copyOfRange(offset, minOf(retained, offset + limit))
        JSONObject().put("ok", true).put("stdout_base64", java.util.Base64.getEncoder().encodeToString(bytes))
            .put("stdout", String(bytes, Charsets.UTF_8)).put("offset", offset).put("next_offset", offset + bytes.size)
            .put("total_bytes", retained).put("has_more", offset + bytes.size < retained).put("log_truncated", truncated)
            .put("state", when {
                failure != null -> "unknown"
                rootScoped && rootDrained && !protocolBroken && !process.isAlive && readerFinished.count == 0L -> "exited"
                rootScoped && waitStatus != null -> "unknown"
                waitStatus != null -> "exited"
                closed -> "closed"
                started -> "running"
                else -> "starting"
            })
            .put("leader_exited", waitStatus != null)
            .put("root_scope", nativeScope ?: JSONObject.NULL)
            .put("scope_drain_confirmed", rootScoped && rootDrained && !protocolBroken && readerFinished.count == 0L)
            .put("error", failure ?: JSONObject.NULL).put("wait_status", waitStatus ?: JSONObject.NULL)
            .put("pty", true).put("persistent_shell", true)
    }

    private fun pump() {
        fun publishReady() {
            if (helloSeen && (!rootScoped || nativeScope != null) && failure == null) {
                started = true
                ready.countDown()
            }
        }
        try {
            DataInputStream(process.inputStream).use { input ->
                while (true) {
                    val type = input.read()
                    if (type < 0) break
                    check(!rootDrained) { "Frame after root drain" }
                    val length = input.readInt()
                    check(length in 0..65536) { "Invalid PTY frame length" }
                    val bytes = ByteArray(length)
                    input.readFully(bytes)
                    when (type.toChar()) {
                        'H' -> {
                            check(!helloSeen && (!rootScoped || (nativeScope != null && !execErrorSeen)))
                            check(String(bytes, Charsets.US_ASCII) == if (rootScoped) "DPTY2" else "DPTY1")
                            helloSeen = true
                            publishReady()
                        }
                        'S' -> {
                            check(rootScoped && nativeScope == null && !helloSeen && !execErrorSeen)
                            val value = String(bytes, Charsets.US_ASCII)
                            check(value.matches(Regex("daiyu-[a-f0-9]{32}")))
                            nativeScope = value
                            publishReady()
                        }
                        'O' -> {
                            check(started && waitStatus == null && (!rootScoped || !execErrorSeen))
                            synchronized(lock) {
                                val keep = minOf(bytes.size, output.size - retained)
                                bytes.copyInto(output, retained, 0, keep)
                                retained += keep
                                if (keep < bytes.size) truncated = true
                            }
                        }
                        'T' -> {
                            check(started && length == 0)
                            if (rootScoped) {
                                check(!truncationSeen && !execErrorSeen && waitStatus == null)
                                truncationSeen = true
                            }
                            synchronized(lock) { truncated = true }
                        }
                        'X' -> {
                            check(started && length == 4 && waitStatus == null && (!rootScoped || !execErrorSeen))
                            waitStatus = java.nio.ByteBuffer.wrap(bytes).int
                        }
                        'E' -> {
                            if (rootScoped) {
                                check(length == 4 && nativeScope != null && !execErrorSeen)
                                execErrorSeen = true
                            }
                            failure = "pty_exec_failed"
                            ready.countDown()
                        }
                        'D' -> {
                            check(rootScoped && (helloSeen || failure == "pty_exec_failed"))
                            val scope = checkNotNull(nativeScope)
                            val expected = "DROOT1 scope=$scope kill=1 populated=0 reaped=1 removed=1"
                            check(bytes.contentEquals(expected.toByteArray(Charsets.US_ASCII)))
                            rootDrained = true
                        }
                        else -> error("Invalid PTY frame")
                    }
                }
            }
            if (rootScoped) {
                if (!rootDrained && failure == null) failure = "pty_root_scope_drain_unknown"
            } else if (waitStatus == null && !closed && failure == null) {
                failure = "pty_helper_exit_unknown"
            }
        } catch (_: java.io.EOFException) {
            if (rootScoped) {
                protocolBroken = true
                failure = "pty_protocol_unknown"
            } else if (waitStatus == null && !closed && failure == null) {
                failure = "pty_helper_exit_unknown"
            }
        } catch (_: Exception) {
            protocolBroken = true
            failure = "pty_protocol_unknown"
            if (!rootScoped) runCatching { process.destroy() }
        } finally {
            ready.countDown()
            writer.shutdownNow()
            try { process.outputStream.close() } catch (_: Exception) { }
            readerFinished.countDown()
        }
    }

    override fun close() {
        synchronized(lock) { closed = true }
        writer.shutdownNow()
        // Keep retrying the owned Process after a failed close; closed only fences new input.
        var failure: Exception? = null
        try { if (!rootScoped) process.destroy() } catch (close: Exception) { failure = close }
        try { process.outputStream.close() } catch (close: Exception) {
            if (failure == null) failure = close else if (failure !== close) failure.addSuppressed(close)
        }
        failure?.let { throw it }
    }

    /** Worker-only join. close is non-waiting; a Linux lease cannot be released before true. */
    fun awaitTermination(timeoutMillis: Long): Boolean {
        require(timeoutMillis in 1..30_000)
        if (!rootScoped) return process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) return false
        val remaining = deadline - System.nanoTime()
        if (readerFinished.count != 0L &&
            (remaining <= 0 || !readerFinished.await(remaining, TimeUnit.NANOSECONDS))) return false
        return rootDrained && !protocolBroken
    }

    companion object {
        fun open(helper: File, argv: List<String>, environment: Map<String, String>, cwd: File,
                 journalDirectory: File, sessionScope: String, rows: Int = 24, columns: Int = 80): PtySession =
            openBound(helper, argv, environment, cwd, journalDirectory, sessionScope, rows, columns, null)

        internal fun openBound(helper: File, argv: List<String>, environment: Map<String, String>, cwd: File,
                 journalDirectory: File, sessionScope: String, rows: Int, columns: Int, binding: JSONObject?,
                 startProcess: (ProcessBuilder) -> Process = { it.start() },
                 createSession: ((Process, OperationJournal) -> PtySession)? = null,
                 createJournal: (File, String, JSONObject?) -> OperationJournal = { directory, scope, metadata ->
                     OperationJournal(directory, scope, binding = metadata)
                 }, rootScope: RootScopeLaunch? = null): PtySession {
            val frozenArgv = argv.toList()
            val frozenEnvironment = environment.toMap()
            require(helper.isAbsolute && helper.isFile && helper.canExecute()) { "pty_native_unavailable" }
            require(frozenArgv.isNotEmpty() && frozenArgv.first().startsWith('/') && frozenArgv.size <= 128 &&
                frozenArgv.all { it.isNotEmpty() && it.length <= 8192 && '\u0000' !in it })
            require(cwd.isAbsolute && cwd.isDirectory && rows in 1..1000 && columns in 1..1000)
            require(frozenEnvironment.size <= 128 && frozenEnvironment.all { (key, value) ->
                key.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,127}")) && value.length <= 8192 && '\u0000' !in value })
            if (rootScope != null) {
                require(frozenArgv == rootScope.guestArgv()) { "pty_root_command_not_supported" }
                require(frozenEnvironment.keys.all {
                    it in setOf("PATH", "HOME", "TMPDIR", "TERM", "LANG", "LC_ALL", "TZ")
                }) { "pty_root_environment_not_supported" }
            }
            val journal = createJournal(journalDirectory, sessionScope, binding)
            var created: PtySession? = null
            var cleanup: PtyStartupCleanup? = null
            var startupCause: Exception? = null
            fun failed(error: String, cause: Throwable? = startupCause): IllegalStateException {
                val owned = cleanup ?: return IllegalStateException(error, cause)
                val failure = PtyStartupFailure(error, owned, cause)
                try { owned.requestClose() } catch (close: Exception) { failure.addSuppressed(close) }
                return failure
            }
            val args = JSONObject().put("helper", helper.canonicalPath).put("argv", org.json.JSONArray(frozenArgv))
                .put("cwd", cwd.canonicalPath).put("environment", JSONObject(frozenEnvironment)).put("rows", rows).put("columns", columns)
            if (rootScope != null) {
                args.put("protocol", "DPTY2").put("root_scope", true)
                    .put("rootfs", rootScope.rootfs.path).put("su", rootScope.su.path)
            }
            val result = try { journal.once("open", "pty_open", args) {
                try {
                    fun quote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
                    val helperArgv = if (rootScope == null) {
                        listOf(helper.path, rows.toString(), columns.toString(), "--") + frozenArgv
                    } else {
                        listOf(helper.path, "--root-scope", rows.toString(), columns.toString(), "--") + rootScope.guestArgv()
                    }
                    val command = if (rootScope == null) helperArgv else
                        listOf(rootScope.su.path, "-c", "exec " +
                            (listOf("/system/bin/env", "-i") + frozenEnvironment.toSortedMap().map { (key, value) -> "$key=$value" } +
                                helperArgv).joinToString(" ") { quote(it) })
                    val builder = ProcessBuilder(command)
                        .directory(cwd).redirectError(File("/dev/null")).apply {
                            environment().clear(); environment().putAll(frozenEnvironment)
                        }
                    val process = startProcess(builder)
                    cleanup = PtyStartupCleanup(process, request = {
                        val session = created
                        if (session != null) session.close() else if (rootScope == null) process.destroy()
                    }, awaitOwned = if (rootScope == null) null else { timeout ->
                        val session = created
                        if (session != null && session.rootScoped) session.awaitTermination(timeout) else false
                    })
                    val session = createSession?.invoke(process, journal) ?: PtySession(process, journal, rootScope != null)
                    created = session
                    check(session.rootScoped == (rootScope != null)) { "pty_protocol_mode_mismatch" }
                    if (!session.ready.await(3, TimeUnit.SECONDS) || !session.started || session.failure != null) {
                        JSONObject().put("ok", false).put("execution", "unknown").put("error", "pty_startup_unknown_not_replayed")
                    } else JSONObject().put("ok", true).put("execution", "executed").put("session_scope", sessionScope)
                } catch (error: Exception) {
                    startupCause = error
                    if (error is InterruptedException) Thread.currentThread().interrupt()
                    JSONObject().put("ok", false).put("execution", "unknown").put("error", "pty_startup_unknown_not_replayed")
                }
            } } catch (failure: Throwable) {
                if (cleanup == null) throw failure
                throw failed("pty_startup_unknown_not_replayed", failure)
            }
            if (!result.optBoolean("ok") || created == null) {
                throw failed(result.optString("error", "pty_session_expired_not_reopened"))
            }
            return created!!
        }
    }
}
