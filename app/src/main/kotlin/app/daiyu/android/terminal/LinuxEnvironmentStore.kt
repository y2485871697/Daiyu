package app.daiyu.android.terminal

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

enum class LinuxExecutionMode(val wire: String) {
    PROOT("proot"), CHROOT("chroot");
    companion object {
        internal fun fromWire(value: String): LinuxExecutionMode =
            entries.singleOrNull { it.wire == value } ?: error("linux_execution_mode")
    }
}

/** Trusted installation metadata only: inspection never starts a shell or installs software. */
data class LinuxInstallation(
    val distribution: String,
    val environmentId: String,
    val generation: String,
    val rootfs: File,
    val proot: File? = null,
    val prootSha256: String = "",
    val loader: File? = null,
    val loaderSha256: String = "",
    val guestWorkingDirectory: String = "/",
    val sharedMounts: Map<String, String> = emptyMap(),
    val executionMode: LinuxExecutionMode = LinuxExecutionMode.PROOT,
)

internal data class LinuxProotFiles(val executable: File, val loader: File)
internal fun LinuxInstallation.requireProotFiles(): LinuxProotFiles {
    check(executionMode == LinuxExecutionMode.PROOT) { "linux_not_proot" }
    return LinuxProotFiles(requireNotNull(proot) { "linux_proot_missing" }, requireNotNull(loader) { "linux_loader_missing" })
}

data class LinuxGenerationRef(val environmentId: String, val generation: String)

class LinuxEnvironmentStore internal constructor(private val directory: File,
    private val directorySync: (File) -> Unit,
    private val fileKey: (java.nio.file.Path) -> Any? = ::strictLinuxFileKey,
    private val chrootRoot: File? = null) {
    constructor(directory: File) : this(directory, TerminalDurability::syncDirectory)
    internal constructor(directory: File, directorySync: (File) -> Unit, fileKey: (java.nio.file.Path) -> Any?) :
        this(directory, directorySync, fileKey, null)
    constructor(directory: File, chrootRoot: File) : this(directory, TerminalDurability::syncDirectory, chrootRoot = chrootRoot)
    private val selection = File(directory, "selected-linux.json")
    private val shared = synchronized(stores) {
        stores[directory.canonicalPath] ?: run {
            check(stores.size < 128) { "linux_environment_store_limit" }
            StoreControl().also { stores[directory.canonicalPath] = it }
        }
    }
    private val control get() = shared


    init { TerminalDurability.ensureDirectory(directory, directorySync) }

    /** User-selected installation, never implicit fallback to another distribution. */
    fun select(installation: LinuxInstallation) = synchronized(control) {
        check(shared.leases == 0) { "linux_environment_in_use" }
        val frozen = installation.copy(sharedMounts = installation.sharedMounts.toMap())
        validate(frozen)
        val bytes = encode(frozen).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 32768) { "linux_installation_metadata_limit" }
        TerminalDurability.replace(selection, bytes, directorySync = directorySync)
    }

    fun clearSelection() = synchronized(control) {
        check(shared.leases == 0) { "linux_environment_in_use" }
        // Persist an explicit empty choice rather than an un-fsynced unlink.
        TerminalDurability.replace(selection, JSONObject().put("version", 2).toString().toByteArray(Charsets.UTF_8), directorySync = directorySync)
    }

    fun status(): JSONObject = synchronized(control) {
        if (!selection.exists()) return@synchronized state("NOT_SELECTED")
        try {
            val installation = load() ?: return@synchronized state("NOT_SELECTED")
            validate(installation)
            state("FILES_READY").put("distribution", installation.distribution)
                .put("environment_id", installation.environmentId).put("generation", installation.generation)
                .put("execution_mode", installation.executionMode.wire)
                .put("host_identity", if (installation.executionMode == LinuxExecutionMode.PROOT) "app" else "not_verified")
                .put("host_root_required", installation.executionMode == LinuxExecutionMode.CHROOT).put("host_root_verified", false)
                .put("guest_uid", 0).put("guest_uid_verified", false).put("tools_verified", false)
        } catch (_: Exception) { state("BROKEN").put("error", "linux_installation_unavailable") }
    }

    /** A lease freezes the selected generation; release it after the entire run/session ends. */
    fun acquire(): Lease = acquireSelected(null)

    fun acquire(expected: LinuxGenerationRef): Lease = acquireSelected(expected)

    private fun acquireSelected(expected: LinuxGenerationRef?): Lease = synchronized(control) {
        val installation = load() ?: throw IllegalStateException("linux_environment_not_selected")
        check(expected == null || expected == LinuxGenerationRef(installation.environmentId, installation.generation)) {
            "linux_generation_mismatch"
        }
        val identities = validate(installation)
        shared.leases++
        Lease(installation, identities)
    }

    inner class Lease internal constructor(private val installation: LinuxInstallation, private val identities: List<String>) : AutoCloseable {
        private var closed = false
        val executionMode: LinuxExecutionMode get() = synchronized(control) {
            check(!closed) { "linux_environment_lease_closed" }
            installation.executionMode
        }
        internal fun assertMatches(expected: LinuxInstallation) = synchronized(control) {
            check(!closed) { "linux_environment_lease_closed" }
            check(installation == expected) { "linux_trusted_installation_mismatch" }
            check(validate(installation) == identities) { "linux_installation_changed" }
        }
        fun backend(tempDirectory: File): LinuxBackend = synchronized(control) {
            check(!closed) { "linux_environment_lease_closed" }
            check(validate(installation) == identities) { "linux_installation_changed" }
            require(tempDirectory.isAbsolute && tempDirectory.isDirectory && tempDirectory.canWrite())
            val environment = mapOf("HOME" to "/root", "TMPDIR" to "/tmp", "TERM" to "xterm-256color",
                "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            if (installation.executionMode == LinuxExecutionMode.CHROOT) {
                val launch = RootScopeLaunch(installation.rootfs, File("/system/bin/su"))
                LinuxBackend(installation.distribution, launch.guestArgv(), identityArgv = emptyList(), environment = environment, expectedUid = 0,
                    environmentId = "${installation.environmentId}:${installation.generation}", rootScope = launch)
            } else {
                val helpers = installation.requireProotFiles()
                val binds = installation.sharedMounts.entries.sortedBy { it.key }.flatMap { (host, guest) -> listOf("-b", "$host:$guest") }
                LinuxBackend(
                    label = installation.distribution,
                    shellArgv = listOf(helpers.executable.path, "-0", "-r", installation.rootfs.path,
                        "-w", installation.guestWorkingDirectory) + binds + listOf("/bin/sh"),
                    identityArgv = emptyList(),
                    environment = environment - setOf("TMPDIR", "TERM") + mapOf("PROOT_LOADER" to helpers.loader.path,
                        "PROOT_TMP_DIR" to tempDirectory.canonicalPath),
                    expectedUid = 0,
                    environmentId = installation.environmentId + ":" + installation.generation,
                    guestWorkingDirectory = installation.guestWorkingDirectory,
                    sharedMounts = installation.sharedMounts.toMap()
                )
            }
        }
        fun configuration(tempDirectory: File, hostWorkingDirectory: File): TerminalConfiguration {
            require(hostWorkingDirectory.isAbsolute && hostWorkingDirectory.isDirectory)
            return TerminalConfiguration(backend = "linux", root = false,
                workingDirectory = hostWorkingDirectory, linux = backend(tempDirectory))
        }
        override fun close() = synchronized(control) {
            if (!closed) { closed = true; shared.leases-- }
        }
    }

    private fun validate(value: LinuxInstallation): List<String> {
        require(value.distribution.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        require(value.environmentId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        require(value.generation.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        require(value.environmentId.length + 1 + value.generation.length <= 128) { "linux_environment_identity_limit" }
        require(value.rootfs.isAbsolute && value.rootfs.isDirectory && value.rootfs.canRead())
        val helperFiles = when (value.executionMode) {
            LinuxExecutionMode.PROOT -> {
                val helpers = value.requireProotFiles()
                require(helpers.executable.isAbsolute && helpers.executable.isFile && helpers.executable.canExecute())
                require(helpers.loader.isAbsolute && helpers.loader.isFile && helpers.loader.canRead())
                require(value.prootSha256.matches(Regex("[a-f0-9]{64}")) && digest(helpers.executable) == value.prootSha256)
                require(value.loaderSha256.matches(Regex("[a-f0-9]{64}")) && digest(helpers.loader) == value.loaderSha256)
                listOf(helpers.executable, helpers.loader)
            }
            LinuxExecutionMode.CHROOT -> {
                require(value.proot == null && value.loader == null && value.prootSha256.isEmpty() && value.loaderSha256.isEmpty()) {
                    "linux_chroot_proot_configuration_not_supported"
                }
                require(value.guestWorkingDirectory == "/" && value.sharedMounts.isEmpty()) { "linux_chroot_mapping_not_supported" }
                validateChrootRoot(value)
            }
        }
        LinuxPathSyntax.guest(value.guestWorkingDirectory)
        require(value.sharedMounts.size <= 32)
        value.sharedMounts.forEach { (host, guest) ->
            LinuxPathSyntax.host(host)
            LinuxPathSyntax.guest(guest)
            require(File(host).isDirectory && File(host).canRead())
        }
        val targets = value.sharedMounts.values.map(LinuxPathSyntax::guestParts)
        require(targets.toSet().size == targets.size)
        for (i in targets.indices) for (j in 0 until i) require(
            !LinuxPathSyntax.contains(targets[i], targets[j]) && !LinuxPathSyntax.contains(targets[j], targets[i]))
        val root = value.rootfs.canonicalFile.toPath()
        val shell = File(value.rootfs, "bin/sh").canonicalFile
        require(shell.toPath().startsWith(root) && shell.isFile && shell.canRead())
        val protectedShellPaths = listOf(listOf("bin", "sh"), root.relativize(shell.toPath()).map { it.toString() })
        for (target in targets) require(protectedShellPaths.none { LinuxPathSyntax.contains(target, it) }) { "linux_bind_overrides_shell" }
        return (listOf(value.rootfs) + helperFiles + listOf(shell) + value.sharedMounts.keys.map(::File)).map { file ->
            val real = file.toPath().toRealPath()
            val key = fileKey(real)
            check(key != null) { "linux_path_identity_unavailable" }
            file.absolutePath + "\n" + real + "\n" + key
        }
    }

    private fun validateChrootRoot(value: LinuxInstallation): List<File> {
        val policy = checkNotNull(chrootRoot) { "linux_chroot_policy_not_configured" }
        require(policy.isAbsolute && policy == policy.canonicalFile && !Files.isSymbolicLink(policy.toPath())) {
            "linux_chroot_private_root_required"
        }
        val allowed = policy.toPath()
        val root = value.rootfs.toPath()
        require(value.rootfs == value.rootfs.canonicalFile && root.startsWith(allowed)) { "linux_chroot_private_root_required" }
        val relative = allowed.relativize(root)
        require(relative.nameCount == 3 && relative.getName(2).toString() == "rootfs" &&
            (0..1).all { relative.getName(it).toString().matches(Regex("[A-Za-z0-9_-]{1,128}")) }) { "linux_chroot_private_root_required" }
        val directories = mutableListOf<File>()
        var path = root
        while (true) {
            require(!Files.isSymbolicLink(path) && Files.isDirectory(path)) { "linux_chroot_symlink" }
            if (path != root) directories += path.toFile()
            if (path == allowed) break
            path = checkNotNull(path.parent)
        }
        val transport = RootScopeLaunch.requireTrustedTransport()
        return directories + transport
    }

    private fun load(): LinuxInstallation? {
        if (!selection.exists()) return null
        check(selection.length() <= 32768) { "linux_installation_metadata_limit" }
        val value = JSONObject(selection.readText(Charsets.UTF_8))
        val version = value.getInt("version")
        check(version == 1 || version == 2) { "linux_installation_version" }
        if (!value.has("distribution")) return null
        return LinuxInstallationJson.decode(value, version)
    }

    private fun encode(value: LinuxInstallation) = LinuxInstallationJson.encode(value).put("version", 2)

    private fun state(value: String) = JSONObject().put("ok", true).put("state", value)
        .put("installation_entry", "settings://terminal/linux").put("execution", "not_executed")

    companion object {
        private class StoreControl { var leases = 0 }
        private val stores = mutableMapOf<String, StoreControl>()
    }

    private fun digest(file: File): String {
        check(file.length() in 1..(64L * 1024 * 1024))
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(8192); var total = 0L
            while (true) {
                val n = input.read(bytes); if (n < 0) break
                total += n; check(total <= 64L * 1024 * 1024)
                digest.update(bytes, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

internal fun strictLinuxFileKey(path: java.nio.file.Path): Any? =
    Files.readAttributes(path, BasicFileAttributes::class.java).fileKey()
