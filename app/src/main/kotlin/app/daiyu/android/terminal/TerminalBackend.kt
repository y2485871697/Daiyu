package app.daiyu.android.terminal

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Immutable selection. A Linux launcher must be explicitly configured by the owner; no fallback. */
data class TerminalConfiguration(
    val backend: String = "android",
    val root: Boolean = false,
    val workingDirectory: File? = null,
    val environment: Map<String, String> = emptyMap(),
    val linux: LinuxBackend? = null,
    val rootExecutable: String = "/system/xbin/su"
)
data class LinuxBackend(
    val label: String,
    val shellArgv: List<String>,
    val identityArgv: List<String>,
    val environment: Map<String, String>,
    val expectedUid: Int,
    val daemonSupported: Boolean = false,
    val environmentId: String = label,
    val guestWorkingDirectory: String = "/",
    val sharedMounts: Map<String, String> = emptyMap(),
    val installationEntry: String = "settings://terminal/linux",
    val rootScope: RootScopeLaunch? = null,
)

/** Fixed host transport for an explicitly selected installation, never model argv. */
@ConsistentCopyVisibility
data class RootScopeLaunch internal constructor(val rootfs: File, val su: File, internal val identityProbe: Boolean = false,
    val guestWorkingDirectory: String = "/",
    val workspaceSource: File? = null, val command: String? = null,
    val workspaceIdentity: RootWorkspaceIdentity? = workspaceSource?.let(RootWorkspaceIdentity::capture)) {
    init {
        require(rootfs.isAbsolute && rootfs == rootfs.canonicalFile && rootfs.isDirectory)
        require(su.isAbsolute && su.toPath().normalize() == su.toPath() && su.isFile)
        LinuxPathSyntax.guest(guestWorkingDirectory)
        require(workspaceSource == null || (workspaceSource.isAbsolute && workspaceSource == workspaceSource.canonicalFile &&
            workspaceSource.isDirectory && guestWorkingDirectory == "/workspace")) { "linux_chroot_workspace_invalid" }
        require((workspaceSource == null) == (workspaceIdentity == null)) { "linux_chroot_workspace_identity_required" }
        require(command == null || (command.isNotBlank() && command.length <= 32768 && '\u0000' !in command))
        require(!identityProbe || (workspaceSource == null && command == null))
        require(!identityProbe || guestWorkingDirectory == "/") { "linux_chroot_probe_cwd_not_supported" }
    }
    companion object {
        private val transportPaths = listOf("/system/bin/su", "/system/bin/chroot", "/system/bin/env", "/system/bin/sh")
        internal fun isFixedTransport(file: File): Boolean = file.path in transportPaths
        internal fun trustedTransportIdentity(file: File): String {
            require(isFixedTransport(file))
            return try { ChrootTransportTrust.identity(file) }
            catch (_: Exception) { throw IllegalStateException("linux_chroot_transport_unavailable") }
        }
        internal fun requireTrustedTransport(): List<File> = transportPaths.map(::File).also { files ->
            files.forEach { trustedTransportIdentity(it) }
        }
    }
    fun binding(): JSONObject = JSONObject().put("protocol", "DPTY2").put("rootfs", rootfs.path)
        .put("su", su.path).put("guest_cwd", guestWorkingDirectory).put("identity_probe", identityProbe)
        .put("workspace_source", workspaceSource?.path ?: JSONObject.NULL)
        .put("workspace_device", workspaceIdentity?.device).put("workspace_inode", workspaceIdentity?.inode)
        .put("command", command ?: JSONObject.NULL)
    internal fun guestArgv(): List<String> = listOf("/system/bin/chroot", rootfs.path, "/bin/sh") + when {
        identityProbe -> listOf("-c", "id -u")
        command != null -> listOf("-c", "cd -- \"\$1\" && exec /bin/sh -c \"\$2\"", "daiyu-command", guestWorkingDirectory, command)
        guestWorkingDirectory == "/" -> emptyList()
        // Fixed bridge: cwd is a positional argument, never shell source. RootScope still owns
        // the cgroup, private procfs mount and D/launcher-exit termination protocol.
        else -> listOf("-c", "cd -- \"\$1\" && exec /bin/sh", "daiyu-workspace", guestWorkingDirectory)
    }
}
internal class BackendUnavailable(val code: String, val cleanup: BackendProbeCleanup? = null) : Exception(code)

/** Failed identity probes also own real processes; a destroy request is not exit evidence. */
internal class BackendProbeCleanup(private val process: Process, private val reader: Thread) {
    fun requestClose() {
        runCatching { process.destroy() }
        runCatching { process.inputStream.close() }
        runCatching { process.outputStream.close() }
        runCatching { process.errorStream.close() }
    }
    fun awaitTermination(timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        if (process.isAlive && !process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) return false
        val left = deadline - System.nanoTime()
        if (reader.isAlive && left > 0) TimeUnit.NANOSECONDS.timedJoin(reader, left)
        return !process.isAlive && !reader.isAlive
    }
}
internal data class SelectedBackend(val name: String, val uid: Int, val root: Boolean,
    val shell: List<String>, val environment: Map<String, String>, val cwd: File, val daemonSupported: Boolean,
    val environmentId: String = name, val guestCwd: String? = null, val sharedMounts: Map<String,String> = emptyMap(),
    val rootScope: RootScopeLaunch? = null) {
    fun builder(argv: List<String> = shell): ProcessBuilder = ProcessBuilder(argv).directory(cwd).apply {
        environment().clear(); environment().putAll(this@SelectedBackend.environment)
    }
}
internal sealed interface BackendPathIdentity {
    data class FileSystem(val path: String, val realPath: String, val key: String?,
        val created: java.nio.file.attribute.FileTime?) : BackendPathIdentity
    data class ChrootTransport(val path: String, val identity: String) : BackendPathIdentity
}

internal object BackendSelection {
    fun paths(context: Context, config: TerminalConfiguration): List<BackendPathIdentity> {
        val paths = mutableListOf<File>()
        val roles = mutableMapOf<String, String>()
        fun add(file: File, role: String) { paths += file; roles[file.path] = role }
        add(config.workingDirectory ?: context.filesDir, "cwd")
        when (config.backend) {
            "android" -> {
                add(File("/system/bin/sh"), "shell")
                if (config.root) add(File(config.rootExecutable), "su")
            }
            "linux" -> config.linux?.let { linux ->
                linux.shellArgv.firstOrNull()?.let { add(File(it), "launcher") }
                linux.sharedMounts.keys.forEach { add(File(it), "mount") }
                linux.rootScope?.let { root ->
                    chrootTransportFiles().forEach { add(it, "transport") }
                    add(root.rootfs, "rootfs")
                }
            }
        }
        val rootTransports = if (config.backend == "android" && config.root)
            setOf("/system/bin/sh", config.rootExecutable) else emptySet()
        return pathIdentities(paths, config.backend == "linux" && config.linux?.rootScope != null,
            rootTransports, roles)
    }

    internal fun pathIdentities(paths: List<File>, chroot: Boolean,
        rootTransports: Set<String> = emptySet(), roles: Map<String, String> = emptyMap(),
        transportIdentity: (File) -> String = ChrootTransportTrust::identity): List<BackendPathIdentity> = paths.map { file ->
        var stage = "transport_identity"
        try {
            if ((chroot && RootScopeLaunch.isFixedTransport(file)) || file.path in rootTransports) {
                BackendPathIdentity.ChrootTransport(file.path, transportIdentity(file))
            } else {
                stage = "realpath"
                val real = file.toPath().toRealPath()
                stage = "attributes"
                val attr = java.nio.file.Files.readAttributes(real, java.nio.file.attribute.BasicFileAttributes::class.java)
                fileSystemIdentity(file, real, attr)
            }
        } catch (failure: Exception) { throw pathFailure(roles[file.path] ?: "path", stage, failure) }
    }

    internal fun fileSystemIdentity(file: File, real: java.nio.file.Path,
        attributes: java.nio.file.attribute.BasicFileAttributes): BackendPathIdentity.FileSystem {
        val key = attributes.fileKey()?.toString()
        // Android may report lastModifiedTime as creationTime. With an inode key, writes are not replacement.
        return BackendPathIdentity.FileSystem(file.absolutePath, real.toString(), key,
            if (key == null) attributes.creationTime() else null)
    }

    internal fun requireRootExecutable(path: String) {
        try { ChrootTransportTrust.identity(File(path)) }
        catch (failure: Exception) { throw pathFailure("su", "transport_identity", failure) }
    }

    private fun pathFailure(role: String, stage: String, failure: Exception): BackendUnavailable {
        val reason = when (failure) {
            is java.nio.file.NoSuchFileException -> "missing"
            is java.nio.file.AccessDeniedException, is SecurityException -> "denied"
            is android.system.ErrnoException -> when (failure.errno) {
                android.system.OsConstants.ENOENT -> "missing"
                android.system.OsConstants.EACCES, android.system.OsConstants.EPERM -> "denied"
                else -> "unavailable"
            }
            is IllegalArgumentException -> "untrusted"
            else -> "unavailable"
        }
        return BackendUnavailable("backend_path_${role}_${stage}_$reason")
    }

    internal fun chrootTransportFiles(read: () -> List<File> = RootScopeLaunch::requireTrustedTransport): List<File> = try {
        read()
    } catch (_: Exception) { throw BackendUnavailable("linux_chroot_transport_unavailable") }

    fun select(context: Context, config: TerminalConfiguration): SelectedBackend {
        if (config.backend == "linux") LinuxRuntimePlatform.unavailableReason(
            if (config.linux?.rootScope != null) LinuxExecutionMode.CHROOT else LinuxExecutionMode.PROOT
        )?.let { throw BackendUnavailable(it) }
        // Owner configuration is still bounded; launcher selection remains explicit, never replaced.
        fun validEnvironment(values:Map<String,String>)=values.size<=128 && values.all { (k,v) ->
            k.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,127}")) && v.length<=8192 && !v.contains('\u0000')
        }
        fun validArgv(values:List<String>)=values.size in 1..128 && values.all { it.isNotEmpty() && it.length<=8192 && !it.contains('\u0000') }
        if(!validEnvironment(config.environment)) throw BackendUnavailable("invalid_environment")
        val configuredCwd = config.workingDirectory
        if (configuredCwd != null && !configuredCwd.isAbsolute) throw BackendUnavailable("working_directory_unavailable")
        val cwd = (configuredCwd ?: context.filesDir).canonicalFile
        if (!cwd.isDirectory || !cwd.canRead() || !cwd.canExecute()) throw BackendUnavailable("working_directory_unavailable")
        val env = mapOf("PATH" to "/system/bin:/system/xbin", "HOME" to context.filesDir.absolutePath,
            "TMPDIR" to context.cacheDir.absolutePath) + config.environment.toMap()
        if (env.any { (k,v) -> !k.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) || v.contains('\u0000') })
            throw BackendUnavailable("invalid_environment")
        return when(config.backend) {
            "android" -> {
                if (!File("/system/bin/sh").canExecute()) throw BackendUnavailable("android_shell_unavailable")
                val shell = if (config.root) listOf(config.rootExecutable, "-c", "/system/bin/sh") else listOf("/system/bin/sh")
                if (config.root) requireRootExecutable(config.rootExecutable)
                val probe = if (config.root) listOf(config.rootExecutable, "-c", "/system/bin/id -u") else listOf("/system/bin/id", "-u")
                val uid = probeUid(probe, env, cwd, if (config.root) "root_unavailable" else "identity_unavailable")
                if (config.root && uid != 0) throw BackendUnavailable("root_unavailable")
                if (!config.root && uid != android.os.Process.myUid()) throw BackendUnavailable("identity_mismatch")
                SelectedBackend("android", uid, config.root, shell, env, cwd, true)
            }
            "linux" -> {
                val linux = config.linux ?: throw BackendUnavailable("linux_backend_not_configured")
                if (config.root) throw BackendUnavailable("linux_host_root_not_supported")
                if (config.environment.keys.any { it.startsWith("PROOT_") || it.startsWith("LD_") })
                    throw BackendUnavailable("linux_reserved_environment_override")
                if (linux.label.isBlank() || linux.label.length>128 || !validArgv(linux.shellArgv) || !validEnvironment(linux.environment) || linux.sharedMounts.size>32 ||
                    !linux.shellArgv.first().startsWith("/") || !File(linux.shellArgv.first()).canExecute())
                    throw BackendUnavailable("linux_backend_unavailable")
                val selectedEnv = linux.environment.toMap() + config.environment.toMap()
                if (selectedEnv.any { (k,v) -> !k.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) || v.contains('\u0000') })
                    throw BackendUnavailable("invalid_environment")
                if (linux.environmentId.isBlank() || linux.environmentId.length>128 || linux.guestWorkingDirectory.length>4096 ||
                    !validGuestPath(linux.guestWorkingDirectory) ||
                    linux.sharedMounts.any { (host,guest) ->
                        host.length>4096 || guest.length>4096 || host.contains('\u0000') || guest.contains('\u0000') ||
                            !host.startsWith("/") || !validGuestPath(guest) || !validHostDirectory(host)
                    })
                    throw BackendUnavailable("linux_configuration_invalid")
                linux.rootScope?.let { root ->
                    if (linux.shellArgv != root.guestArgv() || linux.expectedUid != 0 ||
                        linux.guestWorkingDirectory != root.guestWorkingDirectory ||
                        linux.sharedMounts != root.workspaceSource?.let { mapOf(it.path to "/workspace") }.orEmpty())
                        throw BackendUnavailable("linux_chroot_launch_mismatch")
                    chrootTransportFiles()
                    // Native root scope checks actual uid/euid before any guest is forked.
                    return SelectedBackend("linux:${linux.label}", 0, true, linux.shellArgv.toList(), selectedEnv, cwd, true,
                        linux.environmentId, linux.guestWorkingDirectory, linux.sharedMounts.toMap(), root)
                }
                // identityArgv is advisory only; probe through the SAME selected launcher/shell as commands.
                val quotedCwd = "'" + linux.guestWorkingDirectory.replace("'", "'\\''") + "'"
                val uid = probeUid(linux.shellArgv.toList() + listOf("-c", "cd -- $quotedCwd && id -u"), selectedEnv, cwd, "linux_backend_unavailable")
                if (uid != linux.expectedUid) throw BackendUnavailable("linux_identity_mismatch")
                // Guest UID 0 (e.g. PRoot --root-id) does not grant Android host root.
                SelectedBackend("linux:${linux.label}", uid, false, linux.shellArgv.toList(), selectedEnv, cwd, linux.daemonSupported,
                    linux.environmentId, linux.guestWorkingDirectory, linux.sharedMounts.toMap())
            }
            else -> throw BackendUnavailable("unknown_backend")
        }
    }
    private fun validHostDirectory(path: String): Boolean = try {
        val file = File(path)
        file.isAbsolute && file.canonicalFile.isDirectory && file.canonicalFile.canRead()
    } catch (_: Exception) { false }

    private fun validGuestPath(path: String): Boolean {
        if (!path.startsWith('/') || path.contains('\u0000')) return false
        return path.split('/').none { it == ".." }
    }

    private fun probeUid(argv: List<String>, env: Map<String,String>, cwd: File, error: String): Int {
        val process = try { ProcessBuilder(argv).directory(cwd).redirectErrorStream(true).apply {
            environment().clear(); environment().putAll(env)
        }.start() } catch (_: Exception) { throw BackendUnavailable(error) }
        // Drain concurrently, but retain at most 256 bytes. A denial may print indefinitely.
        val output = java.io.ByteArrayOutputStream()
        val reader = Thread {
            try { process.inputStream.use { input -> val bytes=ByteArray(256); var n=input.read(bytes)
                while(n>=0) { synchronized(output) { if(output.size()<256) output.write(bytes,0,minOf(n,256-output.size())) }; n=input.read(bytes) }
            } } catch (_: Exception) { }
        }.apply { isDaemon=true; start() }
        try {
            if (!process.waitFor(3,TimeUnit.SECONDS)) throw BackendUnavailable(if(error=="root_unavailable") "root_probe_timeout_not_executed" else error)
            reader.join(200)
            val uid = synchronized(output) { output.toString("UTF-8").trim().toIntOrNull() }
            if (process.exitValue()!=0 || uid==null || uid<0) throw BackendUnavailable(error)
            return uid
        } finally {
            val cleanup = BackendProbeCleanup(process, reader)
            cleanup.requestClose()
            val exited = try { cleanup.awaitTermination(1500) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
            catch (_: Exception) { false }
            if (!exited) throw BackendUnavailable("backend_probe_cleanup_unknown", cleanup)
        }
    }
}
