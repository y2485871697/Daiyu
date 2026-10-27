package app.daiyu.android.integration.tools

import android.content.Context
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.*
import app.daiyu.android.terminal.*
import org.json.JSONObject
import java.security.MessageDigest

internal interface CommandTransport : AutoCloseable {
    fun execute(name: String, arguments: JSONObject): JSONObject
    fun read(id: String, offset: Long, limit: Int): JSONObject
    fun awaitTermination(timeoutMillis: Long): Boolean
}

/** Ordinary chat commands use the same central ledger as every other tool. Each command gets
 * one exact host grant for its frozen bytes/backend under the app consent policy, not OS authority.
 * Neither a capability nor model JSON is itself a write grant. */
internal class RunCommandTools(
    private val android: TerminalConfiguration,
    private val owner: RunExecutionOwner,
    private val authorization: TaskAuthorization,
    private val linux: TerminalPtyLaunch.Linux?,
    private val approve: (ToolExecutionContext, WriteEffect, String) -> Boolean,
    private val create: (String, TerminalConfiguration) -> CommandTransport,
) : AutoCloseable {
    companion object {
        const val CAPABILITY = "terminal.command.consent"
        fun android(context: Context, owner: RunExecutionOwner, authorization: TaskAuthorization,
            linux: TerminalPtyLaunch.Linux?,
            workingDirectory: java.io.File = app.daiyu.android.workspace.PrivateWorkspacePaths(context.filesDir).ensureRoot(),
            approve: (ToolExecutionContext, WriteEffect, String) -> Boolean): RunCommandTools {
            val configuration = TerminalConfiguration(workingDirectory = workingDirectory,
                environment = mapOf("HOME" to context.filesDir.path, "TMPDIR" to context.cacheDir.path,
                    "PATH" to "/system/bin:/system/xbin"))
            return RunCommandTools(configuration, owner, authorization, linux, approve) { environment, frozen ->
                val registry = TerminalRegistry(context)
                try { registry.configure("${owner.runId.value}-$environment", frozen, owner.conversationId.value) }
                catch (failure: Exception) { registry.close(); throw failure }
                object : CommandTransport {
                    override fun execute(name: String, arguments: JSONObject) = registry.execute(name, arguments)
                    override fun read(id: String, offset: Long, limit: Int) = registry.inspectJob(id, offset, limit)
                    override fun close() = registry.close()
                    override fun awaitTermination(timeoutMillis: Long) = registry.awaitOrdinaryTermination(timeoutMillis)
                }
            }
        }
    }
    private val lock = Any()
    private var closed = false
    private var unknown = false
    private var linuxClaimed = false
    private val executionLock = Any()
    private var creating = false
    private var creationUnconfirmed = false
    private var closeFailed = false
    private val registries = mutableMapOf<String, CommandTransport>()
    private val linuxConfiguration = linux?.commandConfiguration()
    private fun configuration(environment: String): TerminalConfiguration? =
        if (environment == "android") android else linuxConfiguration
    private fun target(environment: String, timeout: Long): String {
        val config = configuration(environment)
        val backend = config?.linux
        val frozen = JSONObject().put("conversation", owner.conversationId.value).put("run", owner.runId.value)
            .put("environment", environment).put("mode", "sync").put("timeout_ms", timeout)
            .put("cwd", config?.workingDirectory?.path)
            .put("env", JSONObject(config?.environment?.toSortedMap().orEmpty()))
            .put("argv", org.json.JSONArray(backend?.shellArgv ?: listOf("/system/bin/sh")))
            .put("linux_env", JSONObject(backend?.environment?.toSortedMap().orEmpty()))
            .put("guest_cwd", backend?.guestWorkingDirectory)
            .put("mounts", JSONObject(backend?.sharedMounts?.toSortedMap().orEmpty()))
            .put("root_scope", backend?.rootScope?.binding() ?: JSONObject.NULL)
            .put("generation", if (environment == "linux") linux?.expected?.generation else null)
        return MessageDigest.getInstance("SHA-256").digest(frozen.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun denied(context: ToolExecutionContext): ExecutionReceipt.NotExecuted? = synchronized(lock) {
        when {
            closed -> ExecutionReceipt.NotExecuted("terminal_run_closed")
            context.owner !== owner -> ExecutionReceipt.NotExecuted("tool_run_owner_mismatch")
            context.authorization !== authorization -> ExecutionReceipt.NotExecuted("tool_run_authorization_mismatch")
            else -> null
        }
    }
    fun tools(): List<RegisteredTool> = listOf(object : RegisteredTool {
        override val definition = app.daiyu.android.domain.tools.ToolDefinition("terminal_exec", 1,
            "在 Android 应用身份或绑定 Linux 中执行命令。当前会话 Android 工作目录：${android.workingDirectory?.path}；Linux 工作目录：${linuxConfiguration?.linux?.guestWorkingDirectory ?: "未配置"}。两者直通同一会话目录；Android 系统命令请选择 android，不在 Linux 内猜测执行。应用内默认许可按本次精确命令和环境放行，无需逐次确认；仍核验真实身份与系统权限，不自动提权、不自动安装、不切换环境。同步等待真实输出，UNKNOWN不得重发，凭job_id用terminal_read读取。",
            CAPABILITY, ToolEffect.WRITE, ToolSchema(listOf(
                ArgumentField("command", ArgumentType.Text(maxLength = 32768, minimumLength = 1)),
                ArgumentField("environment", ArgumentType.Text(choices = setOf("android", "linux"))),
                ArgumentField("timeout_ms", ArgumentType.Integer(1, 120000), required = false))))
        override fun prepare(arguments: ToolArguments): PreparedTool {
            val command = arguments.text("command")
            require(command.isNotBlank() && '\u0000' !in command &&
                String(command.toByteArray(Charsets.UTF_8), Charsets.UTF_8) == command)
            val environment = arguments.text("environment")
            val timeout = if (arguments.contains("timeout_ms")) arguments.integer("timeout_ms") else 30000L
            val effect = WriteEffect("terminal.command", "exec", target(environment, timeout),
                java.util.Base64.getEncoder().encodeToString(command.toByteArray(Charsets.UTF_8)))
            val config = configuration(environment)
            val disclosure = "环境：$environment\n身份：${if (environment == "android") "Android 应用 UID（非 Root）" else if (config?.linux?.rootScope != null) "chroot / Host Root（独立进程域，不回退 Android）" else "PRoot / Android 应用 UID（不回退 Android）"}\n" +
                "工作目录：${config?.linux?.guestWorkingDirectory ?: config?.workingDirectory?.path}\n" +
                "环境变量：${config?.linux?.environment.orEmpty() + config?.environment.orEmpty()}\n" +
                "共享映射：${config?.linux?.sharedMounts.orEmpty()}\n等待上限：${timeout}ms\n\n完整命令：\n$command"
            return PreparedTool.approvedWrite(effect,
                approve = { call ->
                    val available = synchronized(lock) { !closed && !unknown } && config != null
                    if (available && denied(call) == null && !call.cancelled() && approve(call, effect, disclosure) &&
                        !call.cancelled() && denied(call) == null)
                        TaskAuthorization(authorization.sessionId, authorization.taskId, setOf(effect)) else null
                },
                admit = { call -> denied(call) ?: when {
                    config == null -> ExecutionReceipt.NotExecuted("linux_environment_not_ready_select_installed_environment")
                    else -> null
                } },
                execute = { call -> execute(call, effect, environment, command, timeout) })
        }
    }, object : RegisteredTool {
        override val definition = app.daiyu.android.domain.tools.ToolDefinition("terminal_read", 1,
            "读取本次运行原job的真实状态和分页输出；不执行、不重放命令，读取成功不解除UNKNOWN。",
            CAPABILITY, ToolEffect.READ_ONLY, ToolSchema(listOf(
                ArgumentField("job_id", ArgumentType.Text(maxLength = 36, minimumLength = 36)),
                ArgumentField("offset", ArgumentType.Integer(0, 2097152), required = false),
                ArgumentField("limit", ArgumentType.Integer(1, 65536), required = false))))
        override fun prepare(arguments: ToolArguments): PreparedTool {
            val id = arguments.text("job_id").also { require(it.matches(Regex("[a-f0-9-]{36}"))) }
            val offset = if (arguments.contains("offset")) arguments.integer("offset") else 0L
            val limit = (if (arguments.contains("limit")) arguments.integer("limit") else 16384L).toInt()
            return PreparedTool.contextual(admit = ::denied, execute = { call ->
                if (call.cancelled()) ExecutionReceipt.NotExecuted("terminal_cancelled")
                else {
                    val handles = synchronized(lock) { registries.values.toList() }
                    val result = handles.asSequence().map { it.read(id, offset, limit) }
                        .firstOrNull { it.optBoolean("ok") }
                    ExecutionReceipt.Confirmed((result ?: JSONObject().put("ok", false)
                        .put("error", "job_not_owned_by_run")).toString())
                }
            })
        }
    })
    private fun execute(call: ToolExecutionContext, effect: WriteEffect, environment: String,
        command: String, timeout: Long): ExecutionReceipt = synchronized(executionLock) {
        try { executeSerial(call, effect, environment, command, timeout) }
        catch (failure: Exception) {
            synchronized(lock) { unknown = true }
            throw failure
        }
    }
    private fun executeSerial(call: ToolExecutionContext, effect: WriteEffect, environment: String,
        command: String, timeout: Long): ExecutionReceipt {
        if (call.cancelled()) return ExecutionReceipt.NotExecuted("terminal_cancelled")
        val registry = synchronized(lock) {
            if (closed || unknown) return ExecutionReceipt.NotExecuted("terminal_closed_or_unknown")
            if (call.owner !== owner || call.authorization.allowedWrites != setOf(effect) ||
                call.authorization.sessionId != authorization.sessionId || call.authorization.taskId != authorization.taskId)
                return ExecutionReceipt.NotExecuted("terminal_exact_approval_required")
            registries[environment]
        } ?: run {
            synchronized(lock) {
                if (closed) return ExecutionReceipt.NotExecuted("terminal_run_closed")
                creating = true
            }
            try {
                if (environment == "linux") {
                    checkNotNull(linux).revalidate()
                    if (synchronized(lock) { closed }) return ExecutionReceipt.NotExecuted("terminal_run_closed")
                    if (!linuxClaimed) {
                        linux.claim()
                        linuxClaimed = true
                        linux.claimCommandExecution()
                    }
                }
                val config = checkNotNull(configuration(environment))
                val created = create(environment, config)
                val late = synchronized(lock) { registries[environment] = created; closed }
                if (late) { created.close(); return ExecutionReceipt.NotExecuted("terminal_run_closed") }
                created
            } catch (failure: Exception) {
                synchronized(lock) { creationUnconfirmed = true }
                throw failure
            } finally { synchronized(lock) { creating = false } }
        }
        val request = call.invocation.id.value.let { id -> MessageDigest.getInstance("SHA-256")
            .digest(id.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) } }
        if (call.cancelled()) return ExecutionReceipt.NotExecuted("terminal_cancelled_before_open")
        val opened = registry.execute("terminal_open", JSONObject().put("request_id", "open-$request"))
        if (!opened.optBoolean("ok")) return ExecutionReceipt.NotExecuted(opened.optString("error", "terminal_open_failed"))
        if (call.cancelled() || synchronized(lock) { closed })
            return ExecutionReceipt.NotExecuted("terminal_cancelled_before_command")
        val raw = registry.execute("terminal_exec", JSONObject().put("request_id", "exec-$request")
            .put("session_id", opened.getString("session_id")).put("command", command)
            .put("mode", "sync").put("timeout_ms", timeout))
        return when (raw.optString("execution")) {
            "executed" -> ExecutionReceipt.Confirmed(raw.toString())
            "not_executed" -> ExecutionReceipt.NotExecuted(raw.optString("error", "terminal_not_executed"))
            else -> {
                synchronized(lock) { unknown = true }
                ExecutionReceipt.Unknown("terminal_outcome_unknown_no_resend:job_id=${raw.optString("job_id", "unavailable")}")
            }
        }
    }
    fun markUnknown() = synchronized(lock) { unknown = true }
    fun observeOutcomes(results: List<ToolDispatchResult>) {
        if (results.any { it.outcome is DispatchOutcome.Unknown }) synchronized(lock) { unknown = true }
    }
    override fun close() {
        val handles = synchronized(lock) { closed = true; registries.values.toList() }
        var failure: Exception? = null
        handles.forEach { handle ->
            try { handle.close() }
            catch (error: Exception) {
                synchronized(lock) { closeFailed = true }
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }
    fun observeClose(done: (RunCatalogCloseResult) -> Unit) {
        Thread({
            val (handles, admitted) = synchronized(lock) {
                registries.values.toList() to (closed && !creating && !creationUnconfirmed && !closeFailed)
            }
            val drained = admitted && try { handles.all { it.awaitTermination(30000) } } catch (_: Exception) { false }
            if (drained) {
                try {
                    if (linuxClaimed) linux?.release() else linux?.close()
                    done(RunCatalogCloseResult.Drained)
                } catch (_: Exception) { done(RunCatalogCloseResult.Unknown("terminal_command_lease_unconfirmed")) }
            } else done(RunCatalogCloseResult.Unknown("terminal_command_drain_unconfirmed"))
        }, "terminal-command-drain").apply { isDaemon = true; start() }
    }
}
