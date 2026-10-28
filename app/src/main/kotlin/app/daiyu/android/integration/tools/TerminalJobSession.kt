package app.daiyu.android.integration.tools

import android.content.Context
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.*
import app.daiyu.android.terminal.*
import org.json.JSONObject
import java.security.MessageDigest

/** Separate from PTY. A finite, host-authored fixed-command job, never model-authored shell JSON. */
internal class TerminalJobPlan(
    val conversationId: ConversationId,
    val runId: RunId,
    val launch: TerminalPtyLaunch,
    val command: String,
    val mode: String,
    private val workspacePin: AutoCloseable? = null,
    androidConfiguration: TerminalConfiguration? = null,
) {
    private val workspaceReleased = java.util.concurrent.atomic.AtomicBoolean(false)
    fun releaseWorkspace() { if (workspaceReleased.compareAndSet(false, true)) workspacePin?.close() }
    val sessionId = runId.value
    internal val transportScope = java.util.UUID.randomUUID().toString()
    private var claimed = false
    @Synchronized fun claim() {
        check(!claimed) { "terminal_job_plan_already_claimed" }
        claimed = true
        (launch as? TerminalPtyLaunch.Linux)?.claim()
    }
    val configuration: TerminalConfiguration = when (launch) {
        is TerminalPtyLaunch.Android -> {
            require(launch.argv == listOf("/system/bin/sh")) { "terminal_job_android_app_shell_only" }
            (androidConfiguration ?: TerminalConfiguration(workingDirectory = launch.cwd, environment = launch.environment)).also {
                require(it.backend != "shizuku") { "android_shizuku_jobs_not_supported" }
                require(it.backend == "android" && it.linux == null && it.workingDirectory == launch.cwd &&
                    it.environment == launch.environment) { "terminal_job_android_configuration_mismatch" }
            }
        }
        is TerminalPtyLaunch.Linux -> launch.commandConfiguration()
    }
    val effect: WriteEffect
    init {
        require(mode in setOf("async", "daemon"))
        require(command.isNotBlank() && command.length <= 32768 && '\u0000' !in command)
        require(String(command.toByteArray(Charsets.UTF_8), Charsets.UTF_8) == command)
        require(configuration.backend != "linux" || !configuration.root)
        require(mode != "daemon" || !configuration.root) { "android_root_daemon_not_supported" }
        require(mode != "daemon" || launch is TerminalPtyLaunch.Android || (launch as? TerminalPtyLaunch.Linux)?.rootScope != null) { "linux_daemon_not_supported" }
        effect = effectFor(conversationId, runId, configuration, command, mode)
    }
    companion object {
        internal fun effectFor(conversationId: ConversationId, runId: RunId,
            configuration: TerminalConfiguration, command: String, mode: String): WriteEffect {
        val backend = configuration.linux
        val description = JSONObject().put("conversation", conversationId.value).put("run", runId.value)
            .put("backend", configuration.backend).put("cwd", configuration.workingDirectory?.path)
            .put("root", configuration.root).put("root_executable", configuration.rootExecutable)
            .put("environment", JSONObject(configuration.environment.toSortedMap()))
            .put("argv", org.json.JSONArray(backend?.shellArgv ?: listOf("/system/bin/sh")))
            .put("linux_environment", JSONObject(backend?.environment?.toSortedMap().orEmpty()))
            .put("guest_cwd", backend?.guestWorkingDirectory)
            .put("mounts", JSONObject(backend?.sharedMounts?.toSortedMap().orEmpty()))
            .put("root_scope", backend?.rootScope?.binding() ?: JSONObject.NULL)
        val target = MessageDigest.getInstance("SHA-256").digest(description.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return WriteEffect("terminal.job", "start", target, "$mode:" +
            java.util.Base64.getEncoder().encodeToString(command.toByteArray(Charsets.UTF_8)))
        }

    }
}

/** Narrow injectable backend. No second dispatcher, no caller-selected executable or PID. */
internal interface TerminalJobTransport : AutoCloseable {
    fun execute(name: String, arguments: JSONObject): JSONObject
    fun inspect(id: String): JSONObject
    fun inspectTail(id: String): JSONObject = inspect(id)
    fun stop(id: String): JSONObject
    fun awaitOrdinaryTermination(timeoutMillis: Long): Boolean
    fun awaitJobTermination(id: String, timeoutMillis: Long): Boolean = false
}

/** Factory failed before any process or probe was started; unknown factories must not use this. */
internal class TerminalJobNotStarted(cause: Throwable) : IllegalStateException(cause.message, cause)

/** Retained by Controller even after catalog drain: Android daemon belongs to conversation, not run.
 * No restoration of live handles/authority across app restart. All blocking work is worker-only. */
internal class TerminalJobSession private constructor(
    val plan: TerminalJobPlan,
    val authorization: TaskAuthorization,
    private val create: () -> TerminalJobTransport,
) : AutoCloseable {
    private val lock = Any()
    private var owner: RunExecutionOwner? = null
    private var backend: TerminalJobTransport? = null
    private var closed = false
    private var active = false
    private var attempted = false
    private var definitelyNotStarted = false
    private var unknown = false
    private var creationUnconfirmed = false
    private var stopPending = 0
    private var stopThread: Thread? = null
    private var stopFailed = false
    private var drained = false
    private var joining = false
    private var workspaceObservationStarted = false
    private var job: String? = null
    val jobId: String? get() = synchronized(lock) { job }

    fun bind(owner: RunExecutionOwner, capabilities: SessionCapabilities, token: TaskAuthorization) = synchronized(lock) {
        require(token === authorization && owner.conversationId == plan.conversationId && owner.runId == plan.runId &&
            capabilities.sessionId == plan.sessionId) { "tool_run_owner_mismatch" }
        check(!closed && (this.owner == null || this.owner === owner)) { "terminal_job_owner_closed_or_replaced" }
        this.owner = owner
    }

    private fun denied(context: ToolExecutionContext): ExecutionReceipt.NotExecuted? = when {
        closed -> ExecutionReceipt.NotExecuted("terminal_job_run_closed")
        context.owner !== owner || owner == null -> ExecutionReceipt.NotExecuted("tool_run_owner_mismatch")
        context.authorization !== authorization -> ExecutionReceipt.NotExecuted("tool_run_authorization_mismatch")
        else -> null
    }

    fun tools(capabilities: SessionCapabilities): List<RegisteredTool> {
        if (CAPABILITY !in capabilities.enabled) return emptyList()
        val schema = ToolSchema(emptyList())
        fun tool(name: String, write: Boolean) = object : RegisteredTool {
            override val definition = app.daiyu.android.domain.tools.ToolDefinition(name, 1,
                if (write) "启动本次精确批准的固定命令job；async/daemon派发后UNKNOWN不重发，凭原job查询。daemon支持Android应用身份setsid或chroot独立root scope；仅同app进程保留句柄。"
                else "读取原job首个16KiB日志及真实状态，不执行、不重放、不猜测退出。",
                CAPABILITY, if (write) ToolEffect.WRITE else ToolEffect.READ_ONLY, schema)
            override fun prepare(arguments: ToolArguments): PreparedTool = PreparedTool.contextual(
                if (write) plan.effect else null,
                admit = { context -> synchronized(lock) {
                    denied(context) ?: if (context.invocation.name != name ||
                        runCatching { schema.parse(context.invocation.arguments).canonicalJson() }.getOrNull() != arguments.canonicalJson())
                        ExecutionReceipt.NotExecuted("tool_invocation_mismatch") else null
                } },
                execute = { context -> if (write) start(context) else read(context) })
        }
        return listOf(tool(START, true), tool(READ, false))
    }

    internal fun start(context: ToolExecutionContext): ExecutionReceipt {
        // External cancellation never runs under our monitor. Recheck identity/close at claim.
        val cancelled = context.cancelled()
        synchronized(lock) {
            denied(context)?.let { return it }
            if (cancelled) return ExecutionReceipt.NotExecuted("terminal_job_cancelled")
            if (unknown || attempted) return ExecutionReceipt.Unknown("terminal_job_no_resend")
            if (plan.effect !in authorization.allowedWrites) return ExecutionReceipt.NotExecuted("task_write_not_authorized")
            attempted = true; active = true
        }
        var factoryEntered = false
        try {
            (plan.launch as? TerminalPtyLaunch.Linux)?.let { launch ->
                launch.revalidate()
                launch.claimCommandExecution()
            }
            factoryEntered = true
            val transport = create()
            val late = synchronized(lock) { backend = transport; closed }
            if (late) { synchronized(lock) { definitelyNotStarted = true }; stopTransport(transport); return ExecutionReceipt.NotExecuted("terminal_job_cancelled_before_open") }
            val opened = transport.execute("terminal_open", JSONObject().put("request_id", "open"))
            if (!opened.optBoolean("ok")) return receipt(opened)
            val raw = transport.execute("terminal_exec", JSONObject().put("request_id", "command")
                .put("session_id", opened.getString("session_id")).put("command", plan.command).put("mode", plan.mode))
            synchronized(lock) { job = raw.optString("job_id").takeIf { it.matches(Regex("[a-f0-9-]{36}")) } }
            return receipt(raw)
        } catch (failure: TerminalJobNotStarted) {
            synchronized(lock) { definitelyNotStarted = true }
            if (failure.cause is InterruptedException) Thread.currentThread().interrupt()
            return ExecutionReceipt.NotExecuted("terminal_job_backend_not_started")
        } catch (failure: Exception) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            synchronized(lock) {
                if (!factoryEntered) definitelyNotStarted = true
                else if (backend == null) creationUnconfirmed = true
            }
            markUnknown()
            return ExecutionReceipt.Unknown("terminal_job_start_unconfirmed_no_resend")
        } finally { synchronized(lock) { active = false } }
    }
    private fun receipt(raw: JSONObject): ExecutionReceipt {
        if (raw.optString("execution") == "not_executed") {
            synchronized(lock) { definitelyNotStarted = true }
            return ExecutionReceipt.NotExecuted(raw.optString("error", "terminal_job_not_dispatched"))
        }
        if (raw.optBoolean("ok") && raw.optString("state") == "dispatched" &&
            jobId != null && raw.optString("job_id") == jobId) {
            return ExecutionReceipt.Confirmed(JSONObject(raw.toString()).put("execution", "accepted")
                .put("accepted", true).put("completed", false).toString())
        }
        markUnknown()
        return ExecutionReceipt.Unknown("terminal_job_dispatched_or_unconfirmed:${jobId ?: "no_handle"}")
    }
    private fun read(context: ToolExecutionContext): ExecutionReceipt {
        val cancelled = context.cancelled()
        synchronized(lock) { denied(context)?.let { return it } }
        if (cancelled) return ExecutionReceipt.NotExecuted("terminal_job_cancelled")
        return ExecutionReceipt.Confirmed(inspectOnWorker().toString())
    }
    fun observeOutcomes(results: List<ToolDispatchResult>) {
        if (results.any { it.outcome is DispatchOutcome.Unknown }) markUnknown()
    }
    private fun markUnknown() = synchronized(lock) { unknown = true }

    /** Original retained object only. These are UI diagnostics, not new model/run authorization. */
    fun inspectOnWorker(): JSONObject {
        val pair = synchronized(lock) { backend to job }
        return if (pair.first != null && pair.second != null) pair.first!!.inspect(pair.second!!)
        else JSONObject().put("ok", false).put("execution", "unknown").put("error", "terminal_job_not_dispatched_or_unconfirmed")
    }
    fun logTailOnWorker(): JSONObject {
        val pair = synchronized(lock) { backend to job }
        return if (pair.first != null && pair.second != null) pair.first!!.inspectTail(pair.second!!)
        else JSONObject().put("ok", false).put("execution", "unknown").put("error", "terminal_job_not_dispatched_or_unconfirmed")
    }
    fun stopOnWorker(): JSONObject {
        val pair = synchronized(lock) { backend to job }
        return if (pair.first != null && pair.second != null) pair.first!!.stop(pair.second!!)
        else JSONObject().put("ok", false).put("execution", "unknown").put("error", "terminal_job_handle_unavailable")
    }
    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            val owned = backend ?: return
            stopPending++
            // Only thread publication/start under the monitor, never Registry/Process I/O.
            stopThread = Thread({
                try { owned.close() } catch (_: Exception) { synchronized(lock) { stopFailed = true } }
                finally { synchronized(lock) { stopPending-- } }
            }, "terminal-job-stop").apply { isDaemon = true; start() }
        }
    }
    private fun stopTransport(transport: TerminalJobTransport) {
        try { transport.close() } catch (_: Exception) { synchronized(lock) { stopFailed = true } }
    }
    fun awaitCloseOnWorker(timeoutMillis: Long): Boolean {
        require(timeoutMillis in 1..30000)
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val stopping = synchronized(lock) { stopThread }
        if (stopping != null && stopping !== Thread.currentThread()) stopping.join(timeoutMillis)
        val owned = synchronized(lock) {
            if (drained) return true
            if (!closed || active || stopPending != 0 || creationUnconfirmed || joining) return false
            joining = true
            backend
        }
        var complete = false
        try {
            if (owned != null) {
                if (synchronized(lock) { stopFailed }) {
                    owned.close()
                    synchronized(lock) { stopFailed = false }
                }
                val remaining = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                if (remaining <= 0 || !owned.awaitOrdinaryTermination(remaining.coerceAtMost(30000))) return false
            }
            val daemon = synchronized(lock) { job }
            if (plan.mode != "daemon" || synchronized(lock) { !attempted || definitelyNotStarted }) {
                (plan.launch as? TerminalPtyLaunch.Linux)?.release()
                plan.releaseWorkspace()
            }
            else if (daemon != null && owned != null) {
                val observe = synchronized(lock) {
                    if (workspaceObservationStarted) false else { workspaceObservationStarted = true; true }
                }
                if (observe) Thread({
                    try {
                        while (!owned.awaitJobTermination(daemon, 30_000)) Thread.sleep(1000)
                        (plan.launch as? TerminalPtyLaunch.Linux)?.release()
                        plan.releaseWorkspace()
                    } catch (_: Exception) { /* Unconfirmed daemon retains its workspace pin. */ }
                }, "terminal-daemon-workspace").apply { isDaemon = true; start() }
            }
            complete = true
            return true
        } finally { synchronized(lock) { joining = false; if (complete) drained = true } }
    }
    fun observeClose(done: (RunCatalogCloseResult) -> Unit) {
        Thread({
            val complete = try { awaitCloseOnWorker(30_000) } catch (_: Exception) { false }
            done(if (complete) RunCatalogCloseResult.Drained else RunCatalogCloseResult.Unknown("terminal_job_drain_unconfirmed"))
        }, "terminal-job-drain").apply { isDaemon = true; start() }
    }
    companion object {
        const val CAPABILITY = "terminal.job.approved_command"
        const val START = "terminal_job_start"
        const val READ = "terminal_job_read"
        fun approve(context: Context, plan: TerminalJobPlan, authorization: TaskAuthorization): TerminalJobSession =
            approved(plan, authorization) {
                val registry = try { TerminalRegistry(context) }
                catch (failure: Exception) { throw TerminalJobNotStarted(failure) }
                // retain even if configure throws: no command has been started yet.
                try { registry.configure(plan.transportScope, plan.configuration, plan.conversationId.value) }
                catch (failure: Throwable) {
                    try { registry.close() }
                    catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
                    throw TerminalJobNotStarted(failure)
                }
                object : TerminalJobTransport {
                    override fun execute(name: String, arguments: JSONObject) = registry.execute(name, arguments)
                    override fun inspect(id: String) = registry.inspectJob(id)
                    override fun inspectTail(id: String): JSONObject {
                        val head = registry.inspectJob(id, 0, 1)
                        if (!head.optBoolean("ok")) return head
                        return registry.inspectJob(id, (head.optLong("total_bytes") - 65536).coerceAtLeast(0), 65536)
                    }
                    override fun stop(id: String) = registry.stopOwnedJob(id)
                    override fun close() = registry.close()
                    override fun awaitOrdinaryTermination(timeoutMillis: Long) = registry.awaitOrdinaryTermination(timeoutMillis)
                    override fun awaitJobTermination(id: String, timeoutMillis: Long) = registry.awaitJobTermination(id, timeoutMillis)
                }
            }
        internal fun approved(plan: TerminalJobPlan, authorization: TaskAuthorization,
            create: () -> TerminalJobTransport): TerminalJobSession {
            require(authorization.sessionId == plan.sessionId && authorization.taskId == plan.runId.value &&
                plan.effect in authorization.allowedWrites) { "terminal_job_exact_approval_required" }
            plan.claim()
            return TerminalJobSession(plan, authorization, create)
        }
    }
}
