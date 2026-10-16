package app.daiyu.android.terminal.install

import android.content.Context
import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.tools.TaskAuthorization
import app.daiyu.android.terminal.AndroidPtyHost
import app.daiyu.android.terminal.PtyRunOwner
import app.daiyu.android.terminal.PtyStartupFailure
import app.daiyu.android.terminal.TerminalConfiguration
import java.io.File
import java.util.UUID

/** Retains the process/cleanup capability. UNKNOWN is not a cleanup or publication receipt. */
class LinuxInstallProbeUnknown internal constructor(
    internal val retainedResource: Any,
    cause: Throwable? = null,
) : IllegalStateException("linux_install_probe_unknown_retained", cause)

internal data class InstallProbeSnapshot(val waitStatus: Int?, val stdout: String,
    val totalBytes: Int, val truncated: Boolean, val unknown: Boolean)
internal interface InstallProbeSession : AutoCloseable {
    fun snapshot(): InstallProbeSnapshot
    fun awaitTermination(timeoutMillis: Long): Boolean
}

/** Dedicated one-command PTY, NOT another TerminalUiOwner or a selected terminal session. */
class AndroidLinuxInstallProbeRunner internal constructor(
    private val launch: (TerminalConfiguration) -> InstallProbeSession,
    private val timeoutMillis: Long = 15_000,
) : LinuxInstallProbeRunner {
    constructor(context: Context) : this({ configuration -> openAndroid(context.applicationContext, configuration) })

    override fun run(configuration: TerminalConfiguration, command: String,
                     cancellation: LinuxInstallCancellation): LinuxProbeResult {
        require(command == "id -u" && configuration.backend == "linux" && !configuration.root)
        cancellation.check()
        val session = try { launch(configuration) }
        catch (failure: PtyStartupFailure) {
            throw LinuxInstallProbeUnknown(failure.cleanup, failure)
        }
        val cancelHook = cancellation.register { session.close() }
        var confirmed = false
        var last: InstallProbeSnapshot? = null
        try {
            val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
            while (System.nanoTime() < deadline) {
                val state = session.snapshot().also { last = it }
                check(!state.unknown && !state.truncated && state.totalBytes <= 4096) {
                    "linux_install_probe_protocol_or_output"
                }
                if (state.waitStatus != null && session.awaitTermination(100)) {
                    confirmed = true
                    cancellation.check()
                    val status = state.waitStatus
                    // waitpid raw status, not an exit code guessed from stdout.
                    check((status and 0x7f) == 0) { "linux_install_probe_signalled" }
                    return LinuxProbeResult((status ushr 8) and 0xff, state.stdout)
                }
                cancellation.check()
                Thread.sleep(20)
            }
            error("linux_install_probe_timeout")
        } catch (failure: Exception) {
            runCatching { session.close() }
            if (!confirmed) {
                // A captured wait status AND acknowledged helper termination are both required.
                // Closing the session fences reads; never turn a missing status into exit zero.
                confirmed = last?.let { !it.unknown && it.waitStatus != null } == true &&
                    runCatching { session.awaitTermination(1000) }.getOrDefault(false)
            }
            if (!confirmed) throw LinuxInstallProbeUnknown(session, failure)
            throw failure
        } finally {
            cancelHook.close()
            if (confirmed) runCatching { session.close() }
        }
    }

    companion object {
        private fun openAndroid(context: Context, configuration: TerminalConfiguration): InstallProbeSession {
            val linux = requireNotNull(configuration.linux)
            val id = UUID.randomUUID().toString()
            val authorization = TaskAuthorization("linux-install-probe", id)
            val owner = PtyRunOwner(ConversationId("linux-install-probe"), authorization, "install-probe", id)
            val session = AndroidPtyHost.open(context, owner, linux.shellArgv + listOf("-c", "id -u"),
                linux.environment, requireNotNull(configuration.workingDirectory),
                File(context.filesDir, "linux-install-probe-journal"), id)
            return object : InstallProbeSession {
                override fun snapshot(): InstallProbeSnapshot {
                    val value = session.read(authorization, 0, 4096)
                    return InstallProbeSnapshot(if (value.has("wait_status") && !value.isNull("wait_status"))
                        value.getInt("wait_status") else null, value.optString("stdout"),
                        value.optInt("total_bytes"), value.optBoolean("log_truncated"),
                        !value.optBoolean("ok") || value.optString("state") == "unknown")
                }
                override fun awaitTermination(timeoutMillis: Long) = session.awaitTermination(timeoutMillis)
                override fun close() = session.close()
            }
        }
    }
}
