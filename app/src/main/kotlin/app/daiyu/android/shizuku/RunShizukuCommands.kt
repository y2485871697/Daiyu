package app.daiyu.android.shizuku

import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** A host-selected backend for one run. Every shell command requires an exact WRITE grant. */
internal class RunShizukuCommands(
    private val backend: ShizukuBackend,
    private val admission: () -> Boolean = { true },
    private val commandApproved: (WriteEffect) -> Boolean = { true },
    private val onReceipt: (ExecutionReceipt) -> Unit = {},
    private val expectedSessionId: String? = null,
    private val expectedRunId: String? = null
) : AutoCloseable {
    private val lock = Any()
    private var owner: RunExecutionOwner? = null
    private var authorization: TaskAuthorization? = null
    private var capabilities: SessionCapabilities? = null
    private var closed = false
    private var active = 0
    private var cleanup: ShizukuCloseResult? = null
    private var observer: ((ShizukuCloseResult) -> Unit)? = null

    fun bind(owner: RunExecutionOwner, capabilities: SessionCapabilities, authorization: TaskAuthorization) = synchronized(lock) {
        check(!closed && admission()) { "run_closed" }
        require((expectedSessionId == null || capabilities.sessionId == expectedSessionId) &&
            (expectedRunId == null || owner.runId.value == expectedRunId)) { "shizuku_approval_run_mismatch" }
        require(owner.runId.value == authorization.taskId && capabilities.sessionId == authorization.sessionId) {
            "tool_run_owner_mismatch"
        }
        if (this.owner != null) {
            check(this.owner === owner && this.authorization === authorization && this.capabilities === capabilities) {
                "shizuku_run_already_bound"
            }
        } else {
            this.owner = owner
            this.authorization = authorization
            this.capabilities = capabilities
        }
    }

    fun tool(): RegisteredTool = object : RegisteredTool {
        override val definition = ToolDefinition(TOOL_NAME, 1,
            "在用户明确选择的 Shizuku 身份下执行一条精确批准的 shell 命令；可能修改设备。未知结果不重试，不支持后台常驻。",
            CAPABILITY, ToolEffect.WRITE, SCHEMA)

        override fun prepare(arguments: ToolArguments): PreparedTool {
            val script = arguments.text("script")
            val timeout = if (arguments.contains("timeout_ms")) arguments.integer("timeout_ms") else 30_000L
            require(script.isNotBlank() && '\u0000' !in script)
            val effect = writeEffect(backend.identity.uid, script, timeout)
            return PreparedTool.contextual(effect) {
                execute(it, script, timeout, effect).also { receipt -> runCatching { onReceipt(receipt) } }
            }
        }
    }

    private fun execute(context: ToolExecutionContext, script: String, timeout: Long, effect: WriteEffect): ExecutionReceipt {
        val bound = synchronized(lock) {
            if (closed || !admission()) return ExecutionReceipt.NotExecuted("run_closed")
            val current = owner ?: return ExecutionReceipt.NotExecuted("shizuku_run_not_bound")
            if (context.owner !== current) return ExecutionReceipt.NotExecuted("tool_run_owner_mismatch")
            if (context.authorization !== authorization) return ExecutionReceipt.NotExecuted("tool_run_authorization_mismatch")
            if (CAPABILITY !in checkNotNull(capabilities).enabled) return ExecutionReceipt.NotExecuted("capability_disabled")
            if (!commandApproved(effect)) return ExecutionReceipt.NotExecuted("shizuku_command_not_approved")
            if (effect !in context.authorization.allowedWrites) return ExecutionReceipt.NotExecuted("task_write_not_authorized")
            if (context.invocation.name != TOOL_NAME) return ExecutionReceipt.NotExecuted("tool_invocation_mismatch")
            val actual = try { SCHEMA.parse(context.invocation.arguments) } catch (_: RuntimeException) {
                return ExecutionReceipt.NotExecuted("invalid_arguments")
            }
            val actualTimeout = if (actual.contains("timeout_ms")) actual.integer("timeout_ms") else 30_000L
            if (actual.text("script") != script || actualTimeout != timeout) return ExecutionReceipt.NotExecuted("tool_invocation_mismatch")
            active++
            current
        }
        var sent = false
        try {
            if (stopped(context)) return ExecutionReceipt.NotExecuted("cancelled_before_execution")
            val requestId = requestId(bound, context.invocation.id.value)
            val command = ShizukuCommand(requestId, script, timeout)
            sent = true
            val handle = when (val result = backend.execute(command)) {
                is ShizukuDispatch.NotExecuted -> return ExecutionReceipt.NotExecuted(result.reason)
                is ShizukuDispatch.Unknown -> { close(); return ExecutionReceipt.Unknown(result.reason) }
                is ShizukuDispatch.Dispatched -> result.handle
            }
            if (handle.requestId != requestId || handle.identity !== backend.identity) {
                close(); return ExecutionReceipt.Unknown("shizuku_dispatch_identity_mismatch")
            }
            val output = ByteArrayOutputStream()
            var offset = 0
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout + 5_000)
            while (true) {
                if (stopped(context) || System.nanoTime() >= deadline) {
                    close(); return ExecutionReceipt.Unknown("shizuku_execution_interrupted")
                }
                val page = backend.poll(handle, offset, 16_384)
                if (page.offset != offset || page.nextOffset != offset + page.bytes.size || page.bytes.size > 16_384 ||
                    page.retainedBytes !in page.nextOffset..262_144 ||
                    page.bytes.size != minOf(16_384, page.retainedBytes - offset)) {
                    close(); return ExecutionReceipt.Unknown("shizuku_invalid_output_page")
                }
                output.write(page.bytes)
                offset = page.nextOffset
                when (val outcome = page.outcome) {
                    is ShizukuOutcome.Unknown -> { close(); return ExecutionReceipt.Unknown(outcome.reason) }
                    is ShizukuOutcome.Completed -> {
                        if (!page.outputComplete) {
                            close(); return ExecutionReceipt.Unknown("shizuku_output_incomplete")
                        }
                        if (offset == page.retainedBytes) {
                            val payload = JSONObject().put("ok", outcome.exitCode == 0).put("exit_code", outcome.exitCode)
                                .put("output", output.toString(Charsets.UTF_8.name())).put("truncated", page.truncated)
                                .put("backend", "shizuku").put("uid", backend.identity.uid).toString()
                            return ExecutionReceipt.Confirmed(payload, isError = outcome.exitCode != 0)
                        }
                    }
                    ShizukuOutcome.Running -> if (page.bytes.isEmpty()) Thread.sleep(25)
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            close()
            return if (sent) ExecutionReceipt.Unknown("shizuku_execution_interrupted")
                else ExecutionReceipt.NotExecuted("cancelled_before_execution")
        } catch (_: Exception) {
            close()
            return if (sent) ExecutionReceipt.Unknown("shizuku_execution_unconfirmed")
                else ExecutionReceipt.NotExecuted("shizuku_command_invalid")
        } finally {
            val notification = synchronized(lock) {
                active--
                readyObserver()
            }
            notification?.let { (callback, result) -> callback(result) }
        }
    }

    private fun stopped(context: ToolExecutionContext): Boolean =
        synchronized(lock) { closed || !admission() } || context.cancelled()

    override fun close() {
        val begin = synchronized(lock) { if (closed) false else { closed = true; true } }
        if (!begin) return
        try {
            backend.closeWithResult { result ->
                val notification = synchronized(lock) { cleanup = result; readyObserver() }
                notification?.let { (callback, value) -> callback(value) }
            }
        } catch (_: Exception) {
            val notification = synchronized(lock) {
                cleanup = ShizukuCloseResult.Unknown("shizuku_close_unconfirmed")
                readyObserver()
            }
            notification?.let { (callback, value) -> callback(value) }
        }
    }

    fun closeWithResult(onClosed: (ShizukuCloseResult) -> Unit) {
        val notification = synchronized(lock) {
            check(observer == null) { "shizuku_close_already_observed" }
            observer = onClosed
            readyObserver()
        }
        close()
        notification?.let { (callback, value) -> callback(value) }
    }

    private fun readyObserver(): Pair<(ShizukuCloseResult) -> Unit, ShizukuCloseResult>? {
        val result = cleanup ?: return null
        if (active != 0) return null
        val callback = observer ?: return null
        observer = null
        return callback to result
    }

    companion object {
        const val TOOL_NAME = "shizuku_execute"
        const val CAPABILITY = "android.shizuku.execute"
        private val SCHEMA = ToolSchema(listOf(
            ArgumentField("script", ArgumentType.Text(16_384, minimumLength = 1)),
            ArgumentField("timeout_ms", ArgumentType.Integer(1, 120_000), required = false)
        ))

        fun writeEffect(uid: Int, script: String, timeoutMillis: Long = 30_000): WriteEffect {
            require(uid == 0 || uid == 2000)
            require(script.isNotBlank() && '\u0000' !in script && script.codePointCount(0, script.length) <= 16_384)
            require(timeoutMillis in 1..120_000)
            return WriteEffect("android.shizuku", "execute", "uid:$uid", "$timeoutMillis\n$script")
        }

        private fun requestId(owner: RunExecutionOwner, callId: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            for (part in listOf(owner.ownerId, callId)) {
                val bytes = part.toByteArray(Charsets.UTF_8)
                digest.update("${bytes.size}:".toByteArray(Charsets.US_ASCII))
                digest.update(bytes)
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}
