package app.daiyu.android.terminal

import android.content.Context
import app.daiyu.android.runningmode.AndroidRunningModeStore
import app.daiyu.android.runningmode.RunningMode
import app.daiyu.android.workspace.PrivateWorkspacePaths
import app.daiyu.android.domain.tools.TaskAuthorization
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** A parent-created, trusted run. Never construct this from tool/model arguments.
 * Use a fresh durable scope for a NEW user run; retrying an old scope never relaunches it. */
data class TerminalOpenRequest(
    val owner: PtyRunOwner,
    val sessionScope: String,
    val linux: LinuxGenerationRef? = null,
)

data class TerminalLinuxChoice(val reference: LinuxGenerationRef, val label: String,
    val executionMode: LinuxExecutionMode = LinuxExecutionMode.PROOT)
data class TerminalLinuxState(
    val state: String = "NOT_CHECKED",
    val selected: LinuxGenerationRef? = null,
    val distribution: String? = null,
    val choices: List<TerminalLinuxChoice> = emptyList(),
    val error: String? = null,
)

data class TerminalEnvironmentState(
    val phase: String = "IDLE",
    val environment: String? = null,
    val output: String = "",
    val outputTruncated: Boolean = false,
    val outputStart: Long = 0,
    val waitStatus: Int? = null,
    val terminationConfirmed: Boolean = false,
    val ownsResources: Boolean = false,
    val acceptsInput: Boolean = false,
    val disposed: Boolean = false,
    val error: String? = null,
    val lastOperation: String? = null,
    val linux: TerminalLinuxState = TerminalLinuxState(),
) {
    val canOpen: Boolean get() = !ownsResources && !disposed
    // Only a real helper X frame is a shell exit status. Closing is NOT exit code zero.
    val exitCode: Int? get() = waitStatus?.takeIf { (it and 0x7f) == 0 }?.ushr(8)
    val exitSignal: Int? get() = waitStatus?.let { (it and 0x7f).takeIf { signal -> signal != 0 && signal != 0x7f } }
}

data class TerminalOperationResult(val ok: Boolean, val execution: String, val error: String? = null)

/** Immutable event identity. Keep this object to retry; do not turn a retry into a new keystroke. */
class TerminalInput internal constructor(
    internal val runToken: Any,
    internal val authorization: TaskAuthorization,
    val requestId: String,
    val sequence: Long,
    internal val text: String,
)

/**
 * UI-owned host, not a command-execution tool or root backend. Construction and observation do
 * not open a PTY. All filesystem, PTY, status, close and join operations use the dedicated worker.
 * Keep one instance above Compose/navigation recreation. close() disposes it asynchronously;
 * its worker remains responsible for late creations/startup cleanup until actual process exit.
 */
class TerminalEnvironmentHost internal constructor(
    private val backend: TerminalEnvironmentBackend,
    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "daiyu-terminal-host").apply { isDaemon = true }
    },
) : AutoCloseable {
    private class Run(val request: TerminalOpenRequest) {
        val token = Any()
        var sequence = 0L
        var controlSequence = 0L
        var pendingUtf8 = ByteArray(0)
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
        @Volatile var closeRequested = false
        var resource: TerminalOwnedResource? = null // worker only
        var closeAttempted = false
        var startupFailure = false
        var exitDrainPolls = 0
    }

    private val lock = Any()
    private var run: Run? = null
    private var disposed = false
    private val mutableState = MutableStateFlow(TerminalEnvironmentState())
    val state: StateFlow<TerminalEnvironmentState> = mutableState.asStateFlow()

    /** Read-only inspection, safe on entry/re-entry. Does not acquire a lease or launch anything. */
    fun refreshLinux() = enqueue {
        try {
            val linux = backend.linuxStatus()
            publish { it.copy(linux = linux) }
        }
        catch (_: Exception) { publish { it.copy(linux = it.linux.copy(state = "BROKEN", error = "linux_inspection_failed")) } }
    }

    /** Select ONLY a configuration supplied by the trusted parent. No installation or download. */
    fun selectLinux(reference: LinuxGenerationRef?): CompletableFuture<TerminalOperationResult> = operation {
        if (synchronized(lock) { run != null }) return@operation refusal("terminal_in_use")
        backend.selectLinux(reference)
        val linux = backend.linuxStatus()
        publish { it.copy(linux = linux) }
        TerminalOperationResult(true, "not_executed")
    }

    /** Explicit user action after parent authorization. Returns false if a run/cleanup still owns resources. */
    fun open(request: TerminalOpenRequest): Boolean {
        require(request.sessionScope.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        val owner = request.owner
        require(if (request.linux == null) owner.environmentId == ANDROID_ENVIRONMENT && owner.generation == ANDROID_GENERATION
            else owner.environmentId == request.linux.environmentId && owner.generation == request.linux.generation)
        synchronized(lock) {
            if (disposed || run != null) return false
            val next = Run(request)
            run = next
            mutableState.value = mutableState.value.copy(phase = "OPENING",
                environment = "${if (request.linux == null) "Android" else "Linux"}: ${owner.environmentId}/${owner.generation}",
                output = "", outputTruncated = false, outputStart = 0,
                waitStatus = null, terminationConfirmed = false, ownsResources = true, acceptsInput = false,
                error = null, lastOperation = null)
            worker.execute { start(next) }
        }
        return true
    }

    /** Allocating an event is non-I/O; authorization is the exact original object, not equal IDs. */
    fun prepareInput(caller: TaskAuthorization, text: String): TerminalInput? = synchronized(lock) {
        val current = run ?: return@synchronized null
        if (disposed || current.closeRequested || caller !== current.request.owner.authorization ||
            !mutableState.value.acceptsInput || text.toByteArray(Charsets.UTF_8).size > 65536) return@synchronized null
        val sequence = current.sequence++
        TerminalInput(current.token, caller, "input-$sequence", sequence, text)
    }

    fun send(caller: TaskAuthorization, text: String): CompletableFuture<TerminalOperationResult> {
        val input = prepareInput(caller, text)
        if (input == null) {
            publish { it.copy(lastOperation = "terminal_input_or_owner_unavailable") }
            return CompletableFuture.completedFuture(refusal("terminal_input_or_owner_unavailable"))
        }
        return submit(caller, input)
    }

    fun submit(caller: TaskAuthorization, input: TerminalInput): CompletableFuture<TerminalOperationResult> = operation {
        val current = synchronized(lock) { run }
        if (current == null || caller !== current.request.owner.authorization || caller !== input.authorization ||
            input.runToken !== current.token) return@operation refusal("pty_run_owner_mismatch")
        if (current.closeRequested || !state.value.acceptsInput) return@operation refusal("pty_run_closed")
        val result = try {
            checkNotNull(current.resource).write(caller, input.requestId, input.sequence, input.text.toByteArray(Charsets.UTF_8))
        } catch (_: Exception) {
            // Once handed to the PTY a thrown write is not evidence of non-execution.
            current.closeRequested = true
            publish { it.copy(phase = "UNKNOWN", acceptsInput = false, lastOperation = "pty_input_delivery_unknown") }
            return@operation TerminalOperationResult(false, "unknown", "pty_input_delivery_unknown")
        }
        val answer = TerminalOperationResult(result.optBoolean("ok"), result.optString("execution", "unknown"),
            result.optString("error").takeIf { it.isNotEmpty() })
        if (!answer.ok && answer.execution == "unknown") current.closeRequested = true
        publish { it.copy(lastOperation = answer.error ?: "input_written_command_completion_unknown",
            acceptsInput = it.acceptsInput && !current.closeRequested,
            phase = if (current.closeRequested) "UNKNOWN" else it.phase) }
        answer
    }

    fun resize(caller: TaskAuthorization, rows: Int, columns: Int): CompletableFuture<TerminalOperationResult> = operation {
        val current = synchronized(lock) { run }
        if (current == null || caller !== current.request.owner.authorization || current.closeRequested ||
            !state.value.acceptsInput) return@operation refusal("pty_run_owner_mismatch")
        if (rows !in 1..500 || columns !in 1..500) return@operation refusal("invalid_terminal_size")
        val sequence = current.controlSequence++
        val result = checkNotNull(current.resource).resize(caller, "resize-$sequence", sequence, rows, columns)
        TerminalOperationResult(result.optBoolean("ok"), result.optString("execution", "unknown"),
            result.optString("error").takeIf { it.isNotEmpty() })
    }

    /** Owner-checked close request, never a blocking join. It also fences a creation still in flight. */
    fun requestClose(caller: TaskAuthorization): Boolean = synchronized(lock) {
        val current = run ?: return@synchronized false
        if (caller !== current.request.owner.authorization) return@synchronized false
        current.closeRequested = true
        mutableState.value = mutableState.value.copy(phase = "CLOSING", acceptsInput = false)
        true
    }

    /** Parent teardown only, not a model-facing operation. No cancellation of the ownership worker. */
    override fun close() {
        synchronized(lock) {
            if (disposed) return
            disposed = true
            run?.closeRequested = true
            mutableState.value = mutableState.value.copy(disposed = true, acceptsInput = false,
                phase = if (run != null) "CLOSING" else mutableState.value.phase)
            if (run == null) worker.shutdown()
        }
    }

    private fun start(current: Run) {
        if (current.closeRequested) { finish(current, "CLOSED_BEFORE_START", false); return }
        try {
            current.resource = backend.open(current.request)
            publish { it.copy(phase = if (current.closeRequested) "CLOSING" else "RUNNING",
                acceptsInput = !current.closeRequested && !it.disposed) }
        } catch (failure: TerminalOwnedStartupFailure) {
            current.resource = failure.resource
            current.startupFailure = true
            current.closeRequested = true
            publish { it.copy(phase = "CLOSING", acceptsInput = false, error = "pty_startup_unknown_cleanup_pending") }
        } catch (_: Exception) {
            publish { it.copy(error = "pty_open_failed_or_not_replayed") }
            finish(current, "FAILED", false)
            return
        }
        poll(current)
    }

    private fun poll(current: Run) {
        val resource = checkNotNull(current.resource)
        try {
            if (current.closeRequested) {
                // Retry a throwing close; never discard its resource/lease on failure or timeout.
                if (!current.closeAttempted) {
                    // PtyRunSession intentionally fences reads after close; take one last snapshot
                    // first. Failure to read must never prevent the shutdown request itself.
                    if (!current.startupFailure) try { read(current, resource) } catch (_: Exception) { }
                    resource.close()
                    current.closeAttempted = true
                }
            } else {
                read(current, resource)
            }
            if (resource.awaitTermination(50)) {
                // A natural exit may have arrived while waiting. Keep all final output we can read.
                if (!current.closeRequested) read(current, resource)
                // waitFor joins the helper Process, not its reader thread. Give that existing
                // reader time to publish its final X/EOF frame; do not invent a shell result.
                if (!current.closeRequested && state.value.waitStatus == null &&
                    state.value.phase == "RUNNING" && current.exitDrainPolls++ < 20) {
                    worker.schedule({ poll(current) }, 50, TimeUnit.MILLISECONDS)
                    return
                }
                finish(current, if (current.startupFailure) "FAILED" else if (state.value.waitStatus != null) "EXITED" else "UNKNOWN", true)
                return
            }
        } catch (_: Exception) {
            publish { it.copy(error = "pty_io_or_cleanup_unknown", acceptsInput = false, phase = "UNKNOWN") }
            current.closeRequested = true
        }
        worker.schedule({ poll(current) }, 100, TimeUnit.MILLISECONDS)
    }

    private fun read(current: Run, resource: TerminalOwnedResource) {
        val available = resource.readAvailable(current.request.owner.authorization)
        if (available != null) {
            check(available.optBoolean("ok")) { "pty_read_failed" }
            val bytes = Base64.getDecoder().decode(available.getString("stdout_base64"))
            val dropped = available.optBoolean("ui_dropped")
            if (dropped) { current.pendingUtf8 = ByteArray(0); current.decoder.reset() }
            val input = java.nio.ByteBuffer.wrap(current.pendingUtf8 + bytes)
            val chars = java.nio.CharBuffer.allocate(input.remaining() + 2)
            current.decoder.decode(input, chars, false)
            current.pendingUtf8 = ByteArray(input.remaining()).also { input.get(it) }
            chars.flip()
            val delta = chars.toString()
            val phase = when (available.optString("state")) { "exited" -> "EXITED"; "unknown" -> "UNKNOWN"; else -> "RUNNING" }
            val wait = if (available.isNull("wait_status")) null else available.getInt("wait_status")
            publish {
                val appended = it.appendOutput(delta)
                appended.copy(outputTruncated = appended.outputTruncated || dropped,
                    waitStatus = wait, phase = if (current.closeRequested) "CLOSING" else phase,
                    acceptsInput = phase == "RUNNING" && !current.closeRequested && !it.disposed,
                    error = if (available.isNull("error")) it.error else available.optString("error"))
            }
            return
        }
        // The underlying bounded PTY log is at most 256 KiB. Decode a complete byte prefix each
        // time, avoiding corruption of UTF-8 sequences split across frames/read pages.
        val bytes = java.io.ByteArrayOutputStream()
        var offset = 0
        var result: JSONObject
        do {
            result = resource.read(current.request.owner.authorization, offset, 65536)
            check(result.optBoolean("ok")) { "pty_read_failed" }
            val chunk = Base64.getDecoder().decode(result.getString("stdout_base64"))
            check(bytes.size() + chunk.size <= 262144)
            bytes.write(chunk)
            val next = result.getInt("next_offset")
            check(next == offset + chunk.size)
            offset = next
        } while (result.optBoolean("has_more"))
        val wait = if (result.isNull("wait_status")) null else result.getInt("wait_status")
        val phase = when (result.optString("state")) { "exited" -> "EXITED"; "unknown" -> "UNKNOWN"; else -> "RUNNING" }
        publish { it.copy(output = String(bytes.toByteArray(), Charsets.UTF_8), outputTruncated = result.optBoolean("log_truncated"),
            waitStatus = wait, phase = if (current.closeRequested) "CLOSING" else phase,
            acceptsInput = phase == "RUNNING" && !current.closeRequested && !it.disposed,
            error = if (result.isNull("error")) it.error else result.optString("error")) }
    }

    private fun TerminalEnvironmentState.appendOutput(delta: String): TerminalEnvironmentState {
        val combined = output + delta
        var removed = (combined.length - 262144).coerceAtLeast(0)
        if (removed > 0 && removed < combined.length && combined[removed].isLowSurrogate()) removed++
        return copy(output = combined.substring(removed), outputStart = outputStart + removed,
            outputTruncated = outputTruncated || removed > 0)
    }

    private fun finish(current: Run, phase: String, confirmed: Boolean) = synchronized(lock) {
        check(run === current)
        if (current.pendingUtf8.isNotEmpty()) {
            val tail = java.nio.CharBuffer.allocate(current.pendingUtf8.size + 2)
            current.decoder.decode(java.nio.ByteBuffer.wrap(current.pendingUtf8), tail, true)
            current.decoder.flush(tail)
            tail.flip()
            mutableState.value = mutableState.value.appendOutput(tail.toString())
            current.pendingUtf8 = ByteArray(0)
        }
        // This is the only point an owned resource may be forgotten, and callers with a resource
        // reach it ONLY after awaitTermination returned true. The no-launch paths own nothing.
        run = null
        current.resource = null
        mutableState.value = mutableState.value.copy(phase = phase, terminationConfirmed = confirmed,
            ownsResources = false, acceptsInput = false)
        if (disposed) worker.shutdown()
    }

    private fun publish(change: (TerminalEnvironmentState) -> TerminalEnvironmentState) = synchronized(lock) {
        mutableState.value = change(mutableState.value)
    }

    private fun enqueue(block: () -> Unit) = synchronized(lock) {
        if (!disposed) worker.execute(block)
    }

    private fun operation(block: () -> TerminalOperationResult): CompletableFuture<TerminalOperationResult> {
        val future = CompletableFuture<TerminalOperationResult>()
        synchronized(lock) {
            if (disposed) { future.complete(refusal("terminal_host_disposed")); return future }
            worker.execute {
                try { future.complete(block()) }
                catch (_: Exception) {
                    publish { it.copy(lastOperation = "terminal_operation_failed") }
                    future.complete(refusal("terminal_operation_failed"))
                }
            }
        }
        return future
    }

    companion object {
        const val ANDROID_ENVIRONMENT = "android-app"
        const val ANDROID_GENERATION = "v1"
        private fun refusal(error: String) = TerminalOperationResult(false, "not_executed", error)

        /** No shell, filesystem inspection, helper lookup or installation occurs in this factory. */
        fun android(context: Context, trustedInstallations: List<LinuxInstallation> = emptyList()): TerminalEnvironmentHost =
            TerminalEnvironmentHost(AndroidTerminalEnvironmentBackend(context.applicationContext,
                trustedInstallations.map { it.copy(sharedMounts = it.sharedMounts.toMap()) }))
    }
}

/** Internal seams keep lifecycle tests independent of Android/native process timing. */
internal interface TerminalEnvironmentBackend {
    fun open(request: TerminalOpenRequest): TerminalOwnedResource
    fun linuxStatus(): TerminalLinuxState
    fun selectLinux(reference: LinuxGenerationRef?)
}

internal interface TerminalOwnedResource {
    fun read(caller: TaskAuthorization, offset: Int, limit: Int): JSONObject
    fun write(caller: TaskAuthorization, requestId: String, sequence: Long, bytes: ByteArray): JSONObject
    fun readAvailable(caller: TaskAuthorization): JSONObject? = null
    fun resize(caller: TaskAuthorization, requestId: String, sequence: Long, rows: Int, columns: Int): JSONObject =
        JSONObject().put("ok", false).put("execution", "not_executed").put("error", "pty_resize_unavailable")
    fun close()
    fun awaitTermination(timeoutMillis: Long): Boolean
}

internal class TerminalOwnedStartupFailure(val resource: TerminalOwnedResource, cause: Exception) : Exception(cause)

private class AndroidTerminalEnvironmentBackend(
    private val context: Context,
    private val installations: List<LinuxInstallation>,
) : TerminalEnvironmentBackend {
    // Lazy properties are first resolved on the worker, not on page construction.
    private val root by lazy { File(context.filesDir, "user-terminal") }
    private val selection by lazy { TerminalTrustedLinuxSelection(File(root, "linux-selection"), installations,
        chrootRoot = File(context.filesDir.canonicalFile, "linux-chroot-installations")) }

    override fun linuxStatus(): TerminalLinuxState = selection.status()

    private fun requireMode(reference: LinuxGenerationRef?) {
        if (reference != null && installations.any {
                it.environmentId == reference.environmentId && it.generation == reference.generation && it.executionMode == LinuxExecutionMode.CHROOT
            }) check(AndroidRunningModeStore.get(context).mode.value == RunningMode.ROOT) { "linux_root_mode_required" }
    }

    override fun selectLinux(reference: LinuxGenerationRef?) {
        requireMode(reference)
        selection.select(reference)
    }

    override fun open(request: TerminalOpenRequest): TerminalOwnedResource {
        try {
            if (request.linux != null) {
                requireMode(request.linux)
                // Validate before creating home/tmp/journal or looking up the PTY helper. The
                // guard lease prevents a selection change until openLinux acquires its own lease.
                return selection.withSelection(request.linux) { store ->
                    val cwd = File(root, "home").apply { check(mkdirs() || isDirectory) }
                    val temp = File(root, "tmp").apply { check(mkdirs() || isDirectory) }
                    val session = AndroidPtyHost.openLinux(context, request.owner, store, request.linux,
                        temp, cwd, File(root, "journal"), request.sessionScope)
                    TerminalResource(session::read, session::write, session::close, session::awaitTermination, session::resize, session::readAvailable)
                }
            }
            val home = File(root, "home").apply { check(mkdirs() || isDirectory) }
            val cwd = PrivateWorkspacePaths(context.filesDir).ensureRoot()
            val journal = File(root, "journal")
            // Fixed Android app-UID shell. No su, Shizuku, arbitrary backend/argv or background job API.
            val session = AndroidPtyHost.open(context, request.owner, listOf("/system/bin/sh"),
                mapOf("HOME" to home.absolutePath, "TMPDIR" to home.absolutePath, "PATH" to "/system/bin:/system/xbin",
                    "TERM" to "xterm-256color"), cwd, journal, request.sessionScope)
            return TerminalResource(session::read, session::write, session::close, session::awaitTermination, session::resize, session::readAvailable)
        } catch (failure: LinuxPtyStartupFailure) {
            throw TerminalOwnedStartupFailure(cleanup(failure.cleanup::requestClose, failure.cleanup::awaitTermination), failure)
        } catch (failure: PtyStartupFailure) {
            throw TerminalOwnedStartupFailure(cleanup(failure.cleanup::requestClose, failure.cleanup::awaitTermination), failure)
        }
    }

    private fun cleanup(close: () -> Unit, await: (Long) -> Boolean) = TerminalResource(
        { _, _, _ -> error("startup_cleanup_not_readable") }, { _, _, _, _ -> error("startup_cleanup_not_writable") }, close, await)
}

/** Parent trust gate shared by status and launch; a persisted selection is not an authority. */
internal class TerminalTrustedLinuxSelection(
    private val directory: File,
    installations: List<LinuxInstallation>,
    private val directorySync: (File) -> Unit = TerminalDurability::syncDirectory,
    private val fileKey: (java.nio.file.Path) -> Any? = ::strictLinuxFileKey,
    private val chrootRoot: File? = null,
) {
    private val installations = installations.map { it.copy(sharedMounts = it.sharedMounts.toMap()) }
    private val store by lazy { LinuxEnvironmentStore(directory, directorySync, fileKey, chrootRoot) }
    private val choices get() = installations.map {
        TerminalLinuxChoice(LinuxGenerationRef(it.environmentId, it.generation), it.distribution, it.executionMode)
    }

    fun status(): TerminalLinuxState {
        // Do not even inspect a stale selection when the parent supplies no authority.
        if (installations.isEmpty()) return TerminalLinuxState("UNAVAILABLE", error = "linux_trusted_installation_unavailable")
        if (!directory.exists()) return TerminalLinuxState("NOT_SELECTED", choices = choices)
        return try {
            val status = store.status()
            if (status.getString("state") != "FILES_READY") {
                TerminalLinuxState(status.getString("state"), choices = choices,
                    error = status.optString("error").takeIf { it.isNotEmpty() })
            } else {
                val reference = LinuxGenerationRef(status.getString("environment_id"), status.getString("generation"))
                withSelection(reference) {
                    val trusted = trusted(reference)
                    TerminalLinuxState("FILES_READY", reference, trusted.distribution, choices)
                }
            }
        } catch (_: LinuxRunnerGenerationUnknown) {
            TerminalLinuxState("RUNNER_UNKNOWN", choices = choices, error = "linux_runner_generation_unknown")
        } catch (_: Exception) {
            TerminalLinuxState("BROKEN", choices = choices, error = "linux_trusted_installation_mismatch")
        }
    }

    fun select(reference: LinuxGenerationRef?) {
        if (reference == null) store.clearSelection() else store.select(trusted(reference))
    }

    /** The action (including all working-directory creation) runs only after trust validation. */
    fun <T> withSelection(reference: LinuxGenerationRef, action: (LinuxEnvironmentStore) -> T): T {
        val trusted = trusted(reference)
        check(directory.isDirectory) { "linux_environment_not_selected" }
        return store.acquire(reference).use { lease ->
            lease.assertMatches(trusted)
            action(store)
        }
    }

    private fun trusted(reference: LinuxGenerationRef): LinuxInstallation = installations.single {
        it.environmentId == reference.environmentId && it.generation == reference.generation
    }


}

private class TerminalResource(
    private val readOperation: (TaskAuthorization, Int, Int) -> JSONObject,
    private val writeOperation: (TaskAuthorization, String, Long, ByteArray) -> JSONObject,
    private val closeOperation: () -> Unit,
    private val awaitOperation: (Long) -> Boolean,
    private val resizeOperation: ((TaskAuthorization, String, Long, Int, Int) -> JSONObject)? = null,
    private val availableOperation: ((TaskAuthorization) -> JSONObject)? = null,
) : TerminalOwnedResource {
    override fun read(caller: TaskAuthorization, offset: Int, limit: Int) = readOperation(caller, offset, limit)
    override fun write(caller: TaskAuthorization, requestId: String, sequence: Long, bytes: ByteArray) = writeOperation(caller, requestId, sequence, bytes)
    override fun readAvailable(caller: TaskAuthorization) = availableOperation?.invoke(caller)
    override fun resize(caller: TaskAuthorization, requestId: String, sequence: Long, rows: Int, columns: Int) =
        resizeOperation?.invoke(caller, requestId, sequence, rows, columns)
            ?: super.resize(caller, requestId, sequence, rows, columns)
    override fun close() = closeOperation()
    override fun awaitTermination(timeoutMillis: Long) = awaitOperation(timeoutMillis)
}
