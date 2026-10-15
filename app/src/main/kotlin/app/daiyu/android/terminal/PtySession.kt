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
class PtySession internal constructor(private val process: Process, private val journal: OperationJournal) : AutoCloseable {
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
        if (!started || closed || failure != null || !process.isAlive) return OperationJournal.failure("pty_session_unavailable")
        val future = try {
            writer.submit<JSONObject> {
                if (closed || failure != null || !started || !process.isAlive) return@submit OperationJournal.failure("pty_session_unavailable")
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
            .put("state", when { failure != null -> "unknown"; waitStatus != null -> "exited"; closed -> "closed"; started -> "running"; else -> "starting" })
            .put("error", failure ?: JSONObject.NULL).put("wait_status", waitStatus ?: JSONObject.NULL)
            .put("pty", true).put("persistent_shell", true)
    }

    private fun pump() {
        try {
            DataInputStream(process.inputStream).use { input ->
                while (true) {
                    val type = input.readUnsignedByte()
                    val length = input.readInt()
                    check(length in 0..65536) { "Invalid PTY frame length" }
                    val bytes = ByteArray(length); input.readFully(bytes)
                    when (type.toChar()) {
                        'H' -> { check(!started && String(bytes, Charsets.US_ASCII) == "DPTY1"); started = true; ready.countDown() }
                        'O' -> { check(started && waitStatus == null); synchronized(lock) {
                            val keep = minOf(bytes.size, output.size - retained)
                            bytes.copyInto(output, retained, 0, keep); retained += keep
                            if (keep < bytes.size) truncated = true
                        } }
                        'T' -> { check(started && length == 0); synchronized(lock) { truncated = true } }
                        'X' -> { check(started && length == 4 && waitStatus == null); waitStatus = java.nio.ByteBuffer.wrap(bytes).int }
                        'E' -> { failure = "pty_exec_failed"; ready.countDown() }
                        else -> error("Invalid PTY frame")
                    }
                }
            }
        } catch (_: java.io.EOFException) {
            if (waitStatus == null && !closed && failure == null) failure = "pty_helper_exit_unknown"
        } catch (_: Exception) { failure = "pty_protocol_unknown"; process.destroy() }
        finally {
            ready.countDown()
            writer.shutdownNow()
            try { process.outputStream.close() } catch (_: Exception) { }
        }
    }

    override fun close() {
        synchronized(lock) { if (closed) return; closed = true }
        writer.shutdownNow()
        // Helper catches TERM and cleans its owned foreground/session group, then reaps the shell.
        process.destroy()
        try { process.outputStream.close() } catch (_: Exception) { }
    }

    /** Worker-only join. close is non-waiting; a Linux lease cannot be released before true. */
    fun awaitTermination(timeoutMillis: Long): Boolean {
        require(timeoutMillis in 1..30_000)
        return process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
    }

    companion object {
        fun open(helper: File, argv: List<String>, environment: Map<String, String>, cwd: File,
                 journalDirectory: File, sessionScope: String, rows: Int = 24, columns: Int = 80): PtySession =
            openBound(helper, argv, environment, cwd, journalDirectory, sessionScope, rows, columns, null)

        internal fun openBound(helper: File, argv: List<String>, environment: Map<String, String>, cwd: File,
                 journalDirectory: File, sessionScope: String, rows: Int, columns: Int, binding: JSONObject?): PtySession {
            val frozenArgv = argv.toList()
            val frozenEnvironment = environment.toMap()
            require(helper.isAbsolute && helper.isFile && helper.canExecute()) { "pty_native_unavailable" }
            require(frozenArgv.isNotEmpty() && frozenArgv.first().startsWith('/') && frozenArgv.size <= 128 &&
                frozenArgv.all { it.isNotEmpty() && it.length <= 8192 && '\u0000' !in it })
            require(cwd.isAbsolute && cwd.isDirectory && rows in 1..1000 && columns in 1..1000)
            require(frozenEnvironment.size <= 128 && frozenEnvironment.all { (key, value) ->
                key.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,127}")) && value.length <= 8192 && '\u0000' !in value })
            val journal = OperationJournal(journalDirectory, sessionScope, binding = binding)
            var created: PtySession? = null
            val args = JSONObject().put("helper", helper.canonicalPath).put("argv", org.json.JSONArray(frozenArgv))
                .put("cwd", cwd.canonicalPath).put("environment", JSONObject(frozenEnvironment)).put("rows", rows).put("columns", columns)
            val result = journal.once("open", "pty_open", args) {
                var process: Process? = null
                try {
                    process = ProcessBuilder(listOf(helper.path, rows.toString(), columns.toString(), "--") + frozenArgv)
                        .directory(cwd).redirectError(File("/dev/null")).apply {
                            environment().clear(); environment().putAll(frozenEnvironment)
                        }.start()
                    val session = PtySession(process, journal)
                    created = session
                    if (!session.ready.await(3, TimeUnit.SECONDS) || !session.started || session.failure != null) {
                        session.close()
                        JSONObject().put("ok", false).put("execution", "unknown").put("error", "pty_startup_unknown_not_replayed")
                    } else JSONObject().put("ok", true).put("execution", "executed").put("session_scope", sessionScope)
                } catch (error: Exception) {
                    created?.close(); process?.destroy()
                    if (error is InterruptedException) Thread.currentThread().interrupt()
                    JSONObject().put("ok", false).put("execution", "unknown").put("error", "pty_startup_unknown_not_replayed")
                }
            }
            if (!result.optBoolean("ok") || created == null) {
                created?.close()
                throw IllegalStateException(result.optString("error", "pty_session_expired_not_reopened"))
            }
            return created!!
        }
    }
}
