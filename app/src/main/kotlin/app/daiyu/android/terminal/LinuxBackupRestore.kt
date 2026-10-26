package app.daiyu.android.terminal

import android.content.Context
import app.daiyu.android.terminal.install.PackagedProotHelperProvider
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Offline restoration is separate from approval. No imported program, download or selection is executed. */
internal class LinuxBackupRestore(private val context: Context) {
    internal data class Candidate(val packageId: String, val installation: LinuxInstallation,
        val approval: FrozenLinuxApproval, val alreadyRestored: Boolean)

    internal inner class Prepared internal constructor(private val stage: File?, private val destination: File,
        private val candidate: Candidate, private val mode: LinuxExecutionMode) : Closeable {
        private var published = stage == null
        fun publish(): Candidate {
            if (!published) {
                check(!Files.exists(destination.toPath(), NOFOLLOW_LINKS)) { "linux_restore_target_exists" }
                Files.move(checkNotNull(stage).toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
                published = true // A visible directory must never be deleted after a durability error.
                TerminalDurability.syncDirectory(checkNotNull(destination.parentFile))
            }
            validate(candidate.installation)
            return candidate
        }
        override fun close() {
            if (!published && stage != null && stage.exists()) {
                val root = File(stage, "rootfs")
                if (root.exists()) LinuxBackupProcess(context).remove(root, mode)
                check(stage.deleteRecursively()) { "linux_restore_cleanup_failed" }
            }
        }
    }

    fun prepare(packageId: String, metadata: JSONObject, archive: File, progress: (Long) -> Unit): Prepared {
        require(packageId.matches(Regex("[a-f0-9]{64}")))
        val mode = LinuxExecutionMode.fromWire(metadata.getString("mode"))
        check(metadata.getString("abi") == LinuxRuntimePlatform.requireSupported(mode)) { "linux_restore_abi_mismatch" }
        val base = secureDirectory(File(context.filesDir.canonicalFile,
            if (mode == LinuxExecutionMode.CHROOT) "linux-chroot-installations" else "linux-installations"))
        val environmentId = "restore-" + packageId.take(32)
        val parent = secureDirectory(File(base, environmentId))
        val destination = File(parent, packageId)
        fun installation(rootfs: File, generation: String): LinuxInstallation {
            val helpers = if (mode == LinuxExecutionMode.PROOT) PackagedProotHelperProvider(context).resolve() else null
            return LinuxInstallation(metadata.getString("distribution"), environmentId, generation, rootfs,
                helpers?.proot, helpers?.prootSha256.orEmpty(), helpers?.loader, helpers?.loaderSha256.orEmpty(),
                executionMode = mode)
        }
        if (Files.exists(destination.toPath(), NOFOLLOW_LINKS)) {
            require(!Files.isSymbolicLink(destination.toPath()) && destination.isDirectory)
            val record = File(destination, "restore.json")
            check(Files.isRegularFile(record.toPath(), NOFOLLOW_LINKS) && record.length() in 1..16384)
            val saved = JSONObject(record.readText(Charsets.UTF_8))
            check(saved.getString("package_id") == packageId && metadata.keys().asSequence().all { saved.getJSONObject("environment").get(it).toString() == metadata.get(it).toString() }) {
                "linux_restore_target_conflict"
            }
            val value = installation(File(destination, "rootfs"), saved.getString("approval_generation"))
            validate(value)
            return Prepared(null, destination, Candidate(packageId, value, FrozenLinuxApproval(value), true), mode)
        }
        val stage = File(parent, ".restore-${UUID.randomUUID()}.stage")
        Files.createDirectory(stage.toPath()); android.system.Os.chmod(stage.path, 448)
        try {
            val rootfs = File(stage, "rootfs")
            Files.createDirectory(rootfs.toPath()); android.system.Os.chmod(rootfs.path, 448)
            LinuxBackupProcess(context).unpack(archive, rootfs, mode, metadata.getInt("host_uid"), metadata.getInt("host_gid"), progress)
            val shell = File(rootfs, "bin/sh").canonicalFile
            check(shell.toPath().startsWith(rootfs.canonicalFile.toPath()) && shell.isFile && shell.canRead()) { "linux_restore_shell_unavailable" }
            // Validate using the final private location after publication; no guest shell is invoked here.
            val generation = UUID.randomUUID().toString()
            val value = installation(File(destination, "rootfs"), generation)
            val record = JSONObject().put("package_id", packageId).put("environment", metadata)
                .put("approval_generation", generation)
            TerminalDurability.replace(File(stage, "restore.json"), record.toString().toByteArray(Charsets.UTF_8))
            TerminalDurability.syncDirectory(stage)
            return Prepared(stage, destination, Candidate(packageId, value, FrozenLinuxApproval(value), false), mode)
        } catch (failure: Throwable) {
            try {
                val root = File(stage, "rootfs")
                if (root.exists()) LinuxBackupProcess(context).remove(root, mode)
                check(stage.deleteRecursively()) { "linux_restore_cleanup_failed" }
            } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    suspend fun approve(candidate: Candidate): List<LinuxInstallation> {
        validate(candidate.installation)
        return catalog().approve(candidate.approval)
    }
    private fun catalog() = TrustedLinuxCatalog(File(context.filesDir.canonicalFile, "trusted-linux-catalog"),
        File(context.filesDir.canonicalFile, "linux-chroot-installations"))
    private fun validate(installation: LinuxInstallation) {
        LinuxEnvironmentStore(File(context.filesDir, "linux-restore-validation"),
            File(context.filesDir.canonicalFile, "linux-chroot-installations")).installationIdentity(installation)
    }
    private fun secureDirectory(directory: File): File {
        var current: File? = directory
        while (current != null) {
            require(!Files.isSymbolicLink(current.toPath())) { "linux_restore_symlink" }
            current = current.parentFile
        }
        if (!Files.exists(directory.toPath(), NOFOLLOW_LINKS)) {
            Files.createDirectory(directory.toPath()); android.system.Os.chmod(directory.path, 448)
            TerminalDurability.syncDirectory(checkNotNull(directory.parentFile))
        }
        require(directory.isDirectory && directory.canonicalFile == directory) { "linux_restore_unsafe_directory" }
        return directory
    }
}
