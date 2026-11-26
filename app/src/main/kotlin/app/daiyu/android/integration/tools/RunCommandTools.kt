package app.daiyu.android.integration.tools

import android.content.Context
import app.daiyu.android.domain.*
import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.*
import app.daiyu.android.terminal.*
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal interface CommandTransport : AutoCloseable {
    fun execute(name: String, arguments: JSONObject): JSONObject
    fun read(id: String, offset: Long, limit: Int): JSONObject
    fun readLinuxTmpPng(path: String, cancelled: () -> Boolean): ByteArray =
        throw UnsupportedOperationException("linux_tmp_png_read_unsupported")
    fun awaitTermination(timeoutMillis: Long): Boolean
}

/** A rejected metadata-only freeze must not remove the ordinary Android command tool. */
internal data class CommandLinuxBinding(val launch: TerminalPtyLaunch.Linux?, val state: TerminalLinuxState?) {
    companion object {
        fun freeze(state: TerminalLinuxState?, target: LinuxGenerationRef?,
            action: () -> TerminalPtyLaunch.Linux?): CommandLinuxBinding {
            val targetState = if (target == null || state?.selected == target) state else
                TerminalLinuxState("UNAVAILABLE", selected = target, error = "linux_workspace_generation_unavailable")
            return try { CommandLinuxBinding(action(), targetState) }
            catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
            catch (failure: Exception) {
                val reason = when (failure.message) {
                    "linux_runner_generation_unknown" -> "linux_runner_generation_unknown"
                    "linux_root_mode_required" -> "linux_root_mode_required"
                    else -> "linux_environment_binding_rejected"
                }
                CommandLinuxBinding(null, TerminalLinuxState(
                    if (reason == "linux_runner_generation_unknown") "RUNNER_UNKNOWN" else "UNAVAILABLE",
                    selected = target, error = reason))
            }
        }
    }
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
    private val linuxState: TerminalLinuxState? = null,
    private val shizukuUid: Int? = null,
    private val imageReceipt: ((ToolExecutionContext, ByteArray) -> String)? = null,
) : AutoCloseable {
    companion object {
        const val CAPABILITY = "terminal.command.consent"
        private const val POLL_MILLIS = 250L
        private const val RECEIPT_GRACE_MILLIS = 1000L
        internal fun androidConfiguration(context: Context, workingDirectory: java.io.File,
            mode: app.daiyu.android.runningmode.RunningMode = app.daiyu.android.runningmode.AndroidRunningModeStore.get(context).mode.value): TerminalConfiguration =
            androidConfiguration(mode,
                workingDirectory, mapOf("HOME" to context.filesDir.path, "TMPDIR" to context.cacheDir.path,
                    "PATH" to "/system/bin:/system/xbin"))

        internal fun androidConfiguration(mode: app.daiyu.android.runningmode.RunningMode,
            workingDirectory: java.io.File, environment: Map<String, String>,
            rootExecutable: () -> String? = app.daiyu.android.runningmode.AndroidRunningModeAuthorization::rootExecutable): TerminalConfiguration {
            val root = mode == app.daiyu.android.runningmode.RunningMode.ROOT
            return TerminalConfiguration(backend = if (mode == app.daiyu.android.runningmode.RunningMode.SHIZUKU) "shizuku" else "android", root = root, workingDirectory = workingDirectory, environment = environment.toMap(),
                rootExecutable = if (root) rootExecutable() ?: "/system/bin/su" else "/system/bin/su")
        }

        internal fun transport(configuration: TerminalConfiguration, shizukuUid: Int?,
            shizukuBackend: () -> app.daiyu.android.shizuku.ShizukuBackend?,
            terminal: () -> CommandTransport): CommandTransport = when (configuration.backend) {
            "shizuku" -> ShizukuCommandTransport(configuration, shizukuUid, shizukuBackend)
            "android", "linux" -> terminal()
            else -> error("terminal_backend_unsupported")
        }

        fun android(context: Context, owner: RunExecutionOwner, authorization: TaskAuthorization,
            linux: TerminalPtyLaunch.Linux?,
            workingDirectory: java.io.File = app.daiyu.android.workspace.PrivateWorkspacePaths(context.filesDir).ensureRoot(),
            linuxState: TerminalLinuxState? = null,
            androidConfiguration: TerminalConfiguration = androidConfiguration(context, workingDirectory),
            shizukuUid: Int? = null,
            shizukuBackend: () -> app.daiyu.android.shizuku.ShizukuBackend? = { null },
            imageReceipt: ((ToolExecutionContext, ByteArray) -> String)? = null,
            approve: (ToolExecutionContext, WriteEffect, String) -> Boolean): RunCommandTools {
            val configuration = androidConfiguration
            require(configuration.backend in setOf("android", "shizuku") && configuration.linux == null && configuration.workingDirectory == workingDirectory)
            return RunCommandTools(configuration, owner, authorization, linux, approve, create = { environment, frozen ->
                transport(frozen, shizukuUid, shizukuBackend) {
                    val registry = TerminalRegistry(context)
                    try { registry.configure("${owner.runId.value}-$environment", frozen, owner.conversationId.value) }
                    catch (failure: Exception) { registry.close(); throw failure }
                    object : CommandTransport {
                        override fun execute(name: String, arguments: JSONObject) = registry.execute(name, arguments)
                        override fun read(id: String, offset: Long, limit: Int) = registry.inspectJob(id, offset, limit)
                        override fun readLinuxTmpPng(path: String, cancelled: () -> Boolean) =
                            registry.readLinuxTmpPng(path, cancelled)
                        override fun close() = registry.close()
                        override fun awaitTermination(timeoutMillis: Long) = registry.awaitOrdinaryTermination(timeoutMillis)
                    }
                }
            }, linuxState = linuxState, shizukuUid = shizukuUid, imageReceipt = imageReceipt)
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
    private val linuxUnavailableReason = when (linuxState?.error) {
        "linux_runner_generation_unknown", "linux_root_mode_required", "linux_environment_binding_rejected",
        "linux_workspace_generation_unavailable" -> checkNotNull(linuxState.error)
        else -> when (linuxState?.state) {
        "RUNNER_UNKNOWN" -> "linux_runner_generation_unknown"
        "NOT_SELECTED" -> "linux_environment_not_selected"
        "BROKEN" -> "linux_environment_unavailable"
        "FILES_READY" -> "linux_environment_not_bound_to_run"
            else -> "linux_environment_not_ready_select_installed_environment"
        }
    }
    private val linuxDescription = linuxConfiguration?.linux?.let { "Linux 工作目录：${it.guestWorkingDirectory}" }
        ?: when (linuxState?.state) {
            "RUNNER_UNKNOWN" -> "Linux 状态：RUNNER_UNKNOWN（环境运行状态待确认，linux_runner_generation_unknown）；安装选择仍需保留，本次不可执行，不重装、不自动重试或切换环境"
            "NOT_SELECTED" -> "Linux 状态：NOT_SELECTED（未选择环境），本次不可执行"
            "BROKEN" -> "Linux 状态：BROKEN（配置或安装身份校验失败），本次不可执行"
            "UNAVAILABLE" -> "Linux 状态：UNAVAILABLE（本次环境绑定不可用，$linuxUnavailableReason），本次不可执行"
            "FILES_READY" -> "Linux 状态：FILES_READY（环境文件已就绪，但本次运行未绑定可执行环境）"
            else -> "Linux 状态：尚未取得可执行配置，不能据此推断安装状态"
        }
    private fun configuration(environment: String): TerminalConfiguration? =
        if (environment == "android") android else linuxConfiguration
    private fun target(environment: String, timeout: Long): String {
        val config = configuration(environment)
        val backend = config?.linux
        val frozen = JSONObject().put("conversation", owner.conversationId.value).put("run", owner.runId.value)
            .put("environment", environment).put("mode", "sync").put("timeout_ms", timeout)
            .put("backend", config?.backend).put("shizuku_uid", if (config?.backend == "shizuku") shizukuUid else null)
            .put("root", config?.root).put("root_executable", config?.rootExecutable)
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
            "在已选择的 Android ${if (android.backend == "shizuku") "Shizuku（仅真实授权服务，不回退应用 UID）" else if (android.root) "Root" else "应用 UID"} 身份或绑定 Linux 中执行命令。当前会话 Android 工作目录：${android.workingDirectory?.path}；$linuxDescription。已绑定时两者直通同一会话目录；Android 系统命令请选择 android，不在 Linux 内猜测执行。应用内默认许可按本次精确命令和环境放行，无需逐次确认；仍核验真实身份与系统权限，不自动提权、不自动安装、不切换环境。同步等待真实退出和完整输出；timeout_ms 是等待预算，另有最多约 1 秒的收尾查询。超出预算还没确认就返回 UNKNOWN，不代表命令没有执行；不得重发，凭 job_id 用 terminal_read 只读核实并继续不依赖该结果的工作。",
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
            val disclosure = "环境：$environment\n身份：${if (environment == "android") (if (android.backend == "shizuku") "Android Shizuku（预期 UID=$shizukuUid；连接失败不降级）" else if (android.root) "Android Root（实际 UID 必须为 0；失效不降级）" else "Android 应用 UID（非 Root）") else if (config?.linux?.rootScope != null) "chroot / Host Root（独立进程域，不回退 Android）" else "PRoot / Android 应用 UID（不回退 Android）"}\n" +
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
                    config == null -> ExecutionReceipt.NotExecuted(linuxUnavailableReason)
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
    }) + if (imageReceipt == null) emptyList() else listOf(object : RegisteredTool {
        override val definition = app.daiyu.android.domain.tools.ToolDefinition("read_image", 1,
            "实际查看当前已绑定 Linux 的 /tmp 下 PNG 抽帧图片，并把画面交给视觉模型；不操作或观察手机屏幕。" +
                "请先用 terminal_exec 在当前 Linux 抽帧，单张 PNG 不超过 128 KiB；超限时缩小尺寸重新抽帧。" +
                "这里只读取图片，不删除源文件。查看并完成分析后，用 Linux 命令删除本次临时目录及产物，保留原视频。",
            CAPABILITY, ToolEffect.READ_ONLY, ToolSchema(listOf(
                ArgumentField("path", ArgumentType.Text(maxLength = 2048, minimumLength = 10)))))
        override fun prepare(arguments: ToolArguments): PreparedTool {
            val path = arguments.text("path")
            require(path.startsWith("/tmp/") && path.endsWith(".png") && '\u0000' !in path &&
                path.removePrefix("/tmp/").split('/').all { it.isNotEmpty() && it != "." && it != ".." })
            return PreparedTool.contextual(admit = ::denied, execute = { call ->
                if (call.cancelled()) ExecutionReceipt.NotExecuted("image_read_cancelled")
                else try {
                    val png = readLinuxTmpPng(call, path)
                    if (call.cancelled() || denied(call) != null) ExecutionReceipt.NotExecuted("image_read_cancelled")
                    else ExecutionReceipt.Confirmed(checkNotNull(imageReceipt)(call, png))
                } catch (failure: LinuxTmpPngReadException) {
                    ExecutionReceipt.Confirmed(JSONObject().put("ok", false).put("error", failure.reason)
                        .put("message", failure.guidance).toString(), isError = true)
                }
            })
        }
    })

    internal fun readLinuxTmpPng(call: ToolExecutionContext, path: String): ByteArray =
        synchronized(executionLock) {
            check(denied(call) == null) { "terminal_read_image_context_denied" }
            if (call.cancelled()) throw java.util.concurrent.CancellationException("terminal_cancelled")
            val registry = synchronized(lock) {
                check(!closed && !unknown) { "terminal_closed_or_unknown" }
                checkNotNull(registries["linux"]) { "linux_terminal_exec_required_before_read_image" }
            }
            checkNotNull(linux) { linuxUnavailableReason }.revalidate()
            val bytes = registry.readLinuxTmpPng(path) {
                call.cancelled() || synchronized(lock) { closed }
            }
            check(denied(call) == null) { "terminal_read_image_context_denied" }
            if (call.cancelled()) throw java.util.concurrent.CancellationException("terminal_cancelled")
            bytes
        }

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
            var factoryEntered = false
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
                factoryEntered = true
                val created = create(environment, config)
                val late = synchronized(lock) { registries[environment] = created; closed }
                if (late) { created.close(); return ExecutionReceipt.NotExecuted("terminal_run_closed") }
                created
            } catch (failure: Exception) {
                synchronized(lock) { creationUnconfirmed = factoryEntered }
                throw failure
            } finally { synchronized(lock) { creating = false } }
        }
        val request = call.invocation.id.value.let { id -> MessageDigest.getInstance("SHA-256")
            .digest(id.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) } }
        if (call.cancelled()) return ExecutionReceipt.NotExecuted("terminal_cancelled_before_open")
        val opened = registry.execute("terminal_open", JSONObject().put("request_id", "open-$environment"))
        if (!opened.optBoolean("ok")) return ExecutionReceipt.NotExecuted(opened.optString("error", "terminal_open_failed"))
        if (call.cancelled() || synchronized(lock) { closed })
            return ExecutionReceipt.NotExecuted("terminal_cancelled_before_command")
        TerminalTargetMetadata.publish(call, opened)
        val waitStarted = System.nanoTime()
        var raw = registry.execute("terminal_exec", JSONObject().put("request_id", "exec-$request")
            .put("session_id", opened.getString("session_id")).put("command", command)
            .put("mode", "sync").put("timeout_ms", timeout))
        val waitNanos = TimeUnit.MILLISECONDS.toNanos(timeout + RECEIPT_GRACE_MILLIS)
        val jobId = raw.optString("job_id").takeIf { it.matches(Regex("[a-f0-9-]{36}")) }
        fun unconfirmed(): ExecutionReceipt.Unknown {
            synchronized(lock) { unknown = true }
            val code = "terminal_outcome_unknown_no_resend"
            return ExecutionReceipt.Unknown(if (jobId == null) code else "$code:job_id=$jobId")
        }
        while (jobId != null && raw.optBoolean("ok") && !raw.has("error") && raw.optBoolean("wait_pending") &&
            raw.optString("execution") == "unknown") {
            if (call.cancelled() || synchronized(lock) { closed }) break
            val remaining = waitNanos - (System.nanoTime() - waitStarted)
            if (remaining <= 0) break
            val next = try {
                TimeUnit.NANOSECONDS.sleep(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(POLL_MILLIS)))
                if (call.cancelled() || synchronized(lock) { closed }) break
                registry.read(jobId, 0, 16384)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return unconfirmed()
            } catch (_: Exception) {
                return unconfirmed()
            }
            // A failed or mismatched lookup cannot establish that the already dispatched job did not execute.
            if (!next.optBoolean("ok") || next.has("error") || next.optString("job_id") != jobId ||
                next.optString("execution") !in setOf("executed", "unknown")) return unconfirmed()
            raw = next
        }
        return when (raw.optString("execution")) {
            "executed" -> {
                val exit = raw.opt("exit_code") as? Number
                if (exit == null || exit.toDouble() != exit.toInt().toDouble()) {
                    synchronized(lock) { unknown = true }
                    ExecutionReceipt.Unknown("terminal_exit_evidence_missing_no_resend")
                } else ExecutionReceipt.Confirmed(raw.toString(), isError = !raw.optBoolean("ok") || exit.toInt() != 0)
            }
            "not_executed" -> ExecutionReceipt.NotExecuted(raw.optString("error", "terminal_not_executed"))
            else -> unconfirmed()
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
