package app.daiyu.android.terminal.install

import android.content.Context
import app.daiyu.android.terminal.LinuxBackend
import app.daiyu.android.terminal.LinuxInstallation
import app.daiyu.android.terminal.TerminalConfiguration
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

/**
 * Files-only install transaction. Parent must explicitly confirm a PreparedLinuxInstall, provide
 * its own runner, and wire operation cancellation. Production uses bounded tar extraction.
 * No catalog writes, selection, chroot, profiles, shell fallback or implicit network requests.
 */
class RootlessLinuxInstaller internal constructor(
    private val privateRoot: File,
    private val extraction: LinuxRootfsExtraction,
    private val runner: LinuxInstallProbeRunner,
    private val downloader: LinuxArtifactDownload = VerifiedArtifactDownloader(),
    private val directorySync: (File) -> Unit = { directory ->
        FileChannel.open(directory.toPath(), READ).use { it.force(true) }
    },
    private val rename: (File, File) -> Unit = { from, to ->
        // Deliberately no REPLACE_EXISTING and no ATOMIC_MOVE (which may replace its target).
        // Both directories have the same parent/filesystem; never fall back to copying.
        Files.move(from.toPath(), to.toPath())
        Unit
    }
) {
    private val owner = Any()

    /** Pure preparation; caller chooses the generation BEFORE obtaining consent. */
    fun prepare(artifact: LinuxRootfsArtifact, mirrorId: String,
                installation: LinuxInstallation): PreparedLinuxInstall {
        val mirror = artifact.mirrors.singleOrNull { it.id == mirrorId }
            ?: throw IllegalArgumentException("linux_install_unknown_mirror")
        val id = Regex("[A-Za-z0-9_-]{1,128}")
        require(id.matches(installation.environmentId) && id.matches(installation.generation))
        require(installation.environmentId.length + installation.generation.length + 1 <= 128)
        require(installation.distribution == artifact.distribution)
        require(installation.sharedMounts.isEmpty()) { "linux_install_mounts_not_supported" }
        require(installation.guestWorkingDirectory == "/")
        cleanAbsolutePath(privateRoot)
        listOf(installation.rootfs, installation.proot, installation.loader).forEach(::cleanAbsolutePath)
        require(installation.prootSha256.matches(Regex("[a-f0-9]{64}")))
        require(installation.loaderSha256.matches(Regex("[a-f0-9]{64}")))
        val expected = File(File(File(privateRoot, installation.environmentId), installation.generation), "rootfs")
        require(installation.rootfs == expected) { "linux_install_target_outside_private_root" }
        return PreparedLinuxInstall(owner, artifact, mirror, installation)
    }

    /** Call ONLY after the user explicitly accepts this exact entry/mirror/generation/target. */
    fun approveDownloadAndInstall(prepared: PreparedLinuxInstall): DownloadInstallApproval {
        check(prepared.owner === owner) { "linux_install_wrong_owner" }
        return DownloadInstallApproval(prepared)
    }

    /**
     * Blocking I/O. Must run on the parent's IO worker. A cancelled caller must invoke cancel()
     * on the supplied token; merely cancelling an unrelated coroutine is NOT sufficient.
     * The capability is consumed once even on failure. A retry requires new explicit approval.
     */
    fun install(approval: DownloadInstallApproval,
                cancellation: LinuxInstallCancellation): LinuxInstallReceipt {
        val prepared = approval.consume(owner)
        cancellation.check()
        val parent = prepared.targetRootfs.parentFile!!
        var lockOwned = false
        var publicationAttempted = false
        var staging: File? = null
        var archive: File? = null
        val lock = File(parent, ".install-lock")
        try {
            secureDirectory(parent)
            // Cross-instance/process writer exclusion, fail-closed after an interrupted owner.
            // No automatic stale-lock removal: parent can recover after inspecting disk state.
            Files.createDirectory(lock.toPath(), PRIVATE_DIRECTORY)
            lockOwned = true
            check(!exists(prepared.targetRootfs)) { "linux_install_target_exists" }
            cancellation.check()
            validateHelpers(prepared.installation, cancellation)
            val stage = Files.createTempDirectory(parent.toPath(), ".staging-", PRIVATE_DIRECTORY).toFile()
            staging = stage
            val download = File(parent, stage.name + ".archive")
            archive = download
            downloader.download(prepared.artifact, prepared.mirror, download, cancellation)
            // Verify again at the transaction boundary, including injected transports.
            verifyArchive(prepared.artifact, download, cancellation)
            extraction.extract(prepared.artifact, download, stage, cancellation)
            cancellation.check()
            validateShell(stage)
            probe(prepared.installation.copy(rootfs = stage), parent, cancellation)
            cancellation.check()
            val marker = File(stage, MARKER)
            FileChannel.open(marker.toPath(), CREATE_NEW, WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(markerBytes(prepared))
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            syncTree(stage, cancellation)
            cancellation.publish {
                // Last cancellation check and rename share the cancellation lock.
                securePath(parent)
                check(!exists(prepared.targetRootfs)) { "linux_install_target_exists" }
                publicationAttempted = true
                rename(stage, prepared.targetRootfs)
            }
            // A cancellation or durability error AFTER rename cannot turn visible files into
            // "not installed". Reconcile the marker and shell from disk, never delete rootfs.
            directorySync(parent)
            check(inspectPublished(prepared) != null) { "linux_install_published_marker_invalid" }
            return LinuxInstallReceipt(prepared.installation, directorySyncConfirmed = true)
        } catch (failure: Exception) {
            if (publicationAttempted && runCatching { inspectPublished(prepared) }.getOrNull() != null) {
                return LinuxInstallReceipt(prepared.installation, directorySyncConfirmed = false)
            }
            throw failure
        } finally {
            // Never delete a published target, pre-existing rootfs, or another owner's staging.
            staging?.let { runCatching { deleteTreeWithoutFollowingLinks(it.toPath()) } }
            archive?.let { runCatching { Files.deleteIfExists(it.toPath()) } }
            if (lockOwned) runCatching { Files.deleteIfExists(lock.toPath()); directorySync(parent) }
        }
    }

    /** Inspection grants no authority. It neither executes code nor contacts the network. */
    fun inspectPublished(prepared: PreparedLinuxInstall): LinuxInstallation? {
        check(prepared.owner === owner) { "linux_install_wrong_owner" }
        securePath(prepared.targetRootfs)
        if (!exists(prepared.targetRootfs)) return null
        check(Files.isDirectory(prepared.targetRootfs.toPath(), NOFOLLOW_LINKS))
        val marker = File(prepared.targetRootfs, MARKER)
        securePath(marker)
        check(Files.isRegularFile(marker.toPath(), NOFOLLOW_LINKS) && marker.canRead())
        val expected = markerBytes(prepared)
        check(marker.length() == expected.size.toLong()) { "linux_install_marker_mismatch" }
        val observed = ByteArray(expected.size + 1)
        var count = 0
        marker.inputStream().use { input ->
            while (count < observed.size) {
                val n = input.read(observed, count, observed.size - count)
                if (n < 0) break
                count += n
            }
        }
        check(count == expected.size && observed.copyOf(count).contentEquals(expected)) {
            "linux_install_marker_mismatch"
        }
        validateShell(prepared.targetRootfs)
        return prepared.installation
    }

    private fun probe(installation: LinuxInstallation, hostDirectory: File,
                      cancellation: LinuxInstallCancellation) {
        validateHelpers(installation, cancellation)
        val linux = LinuxBackend(
            label = installation.distribution,
            shellArgv = listOf(installation.proot.path, "-0", "-r", installation.rootfs.path,
                "-w", "/", "/bin/sh"),
            identityArgv = emptyList(),
            environment = mapOf("PROOT_LOADER" to installation.loader.path,
                "PROOT_TMP_DIR" to hostDirectory.path, "HOME" to "/root",
                "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"),
            expectedUid = 0,
            environmentId = installation.environmentId + ":" + installation.generation,
            guestWorkingDirectory = "/", sharedMounts = emptyMap()
        )
        val result = runner.run(TerminalConfiguration(backend = "linux", root = false,
            workingDirectory = hostDirectory, linux = linux), "id -u", cancellation)
        cancellation.check()
        check(result.exitCode == 0 && result.stdout.trim() == "0") { "linux_install_probe_failed" }
    }

    private fun validateHelpers(value: LinuxInstallation, cancellation: LinuxInstallCancellation) {
        listOf(value.proot to value.prootSha256, value.loader to value.loaderSha256).forEach { (file, expected) ->
            securePath(file)
            check(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && file.canRead())
            check(file.length() in 1..(64L * 1024 * 1024))
            check(hash(file, 64L * 1024 * 1024, cancellation).second == expected) { "linux_install_helper_hash" }
        }
        check(value.proot.canExecute()) { "linux_install_proot_not_executable" }
    }

    private fun verifyArchive(artifact: LinuxRootfsArtifact, archive: File,
                              cancellation: LinuxInstallCancellation) {
        securePath(archive)
        check(Files.isRegularFile(archive.toPath(), NOFOLLOW_LINKS))
        check(archive.length() == artifact.compressedBytes) { "linux_download_size" }
        val (bytes, digest) = hash(archive, artifact.compressedBytes, cancellation)
        check(bytes == artifact.compressedBytes && digest == artifact.sha256) { "linux_download_hash" }
    }

    private fun hash(file: File, max: Long, cancellation: LinuxInstallCancellation): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                cancellation.check()
                val n = input.read(buffer)
                if (n < 0) break
                check(n.toLong() <= max - total) { "linux_install_size_limit" }
                total += n
                digest.update(buffer, 0, n)
            }
        }
        return total to digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun validateShell(root: File) {
        check(root.canRead() && root.isDirectory)
        val shell = File(root, "bin/sh").canonicalFile
        check(shell.toPath().startsWith(root.canonicalFile.toPath()) && shell.isFile && shell.canRead()) {
            "linux_install_shell_unavailable"
        }
    }

    private fun markerBytes(p: PreparedLinuxInstall): ByteArray = buildString {
        append("daiyu-rootfs-marker-v1\n")
        append("entry=${p.artifact.id}\nsha256=${p.artifact.sha256}\nbytes=${p.artifact.compressedBytes}\n")
        append("distribution=${p.artifact.distribution}\nversion=${p.artifact.version}\nabi=${p.artifact.abi}\n")
        append("archive=${p.artifact.archive}\nstrip_components=${p.artifact.stripComponents}\n")
        append("mirror=${p.mirror.id}\nurl=${p.mirror.uri.toASCIIString()}\n")
        append("environment=${p.installation.environmentId}\ngeneration=${p.installation.generation}\n")
        append("rootfs=${p.targetRootfs.path}\n")
        append("proot=${p.installation.proot.path}\nproot_sha256=${p.installation.prootSha256}\n")
        append("loader=${p.installation.loader.path}\nloader_sha256=${p.installation.loaderSha256}\n")
        append("cwd=/\nmounts=none\n")
    }.toByteArray(Charsets.UTF_8).also { require(it.size <= 16384) }

    private fun secureDirectory(directory: File) {
        securePath(directory)
        check(directory.toPath().startsWith(privateRoot.toPath()))
        val parent = checkNotNull(directory.parentFile)
        if (directory == privateRoot) {
            // The caller-owned root must already exist. Do not create external ancestors
            // or change their permissions; only sync this parent to persist privateRoot.
            check(Files.isDirectory(parent.toPath(), NOFOLLOW_LINKS))
        } else {
            secureDirectory(parent)
        }
        if (!exists(directory)) Files.createDirectory(directory.toPath(), PRIVATE_DIRECTORY)
        check(Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS))
        // Existence does not prove durability: a previous attempt may have created this
        // name but failed its parent sync. Reconfirm the entire managed chain on every
        // attempt, including retries, other generations and new installer instances.
        directorySync(parent)
    }

    private fun securePath(file: File) {
        cleanAbsolutePath(file)
        var current: Path? = file.toPath()
        while (current != null) {
            check(!Files.isSymbolicLink(current)) { "linux_install_symlink_path" }
            current = current.parent
        }
    }

    private fun cleanAbsolutePath(file: File) {
        require(file.isAbsolute && file.path.length <= 4096)
        require(file.path.none { it.code < 32 || it.code == 127 })
        require(file.path.split('/').none { it == "." || it == ".." })
        require(':' !in file.path)
    }

    private fun exists(file: File) = Files.exists(file.toPath(), NOFOLLOW_LINKS)

    private fun syncTree(root: File, cancellation: LinuxInstallCancellation) {
        Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                cancellation.check()
                if (attrs.isRegularFile) FileChannel.open(file, READ).use { it.force(true) }
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                if (exc != null) throw exc
                cancellation.check()
                directorySync(dir.toFile())
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun deleteTreeWithoutFollowingLinks(path: Path) {
        if (!Files.exists(path, NOFOLLOW_LINKS)) return
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    companion object {
        private const val MARKER = ".daiyu-rootfs-install"
        private val PRIVATE_DIRECTORY = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))

        /** Production extraction is bounded and always staged under Context.filesDir. */
        fun forApp(context: Context, runner: LinuxInstallProbeRunner): RootlessLinuxInstaller =
            forApp(context, TarLinuxRootfsExtraction(), runner)

        /** Explicit extraction seam for audited integrations; never a SAF/shared-storage path. */
        fun forApp(context: Context, extraction: LinuxRootfsExtraction,
                   runner: LinuxInstallProbeRunner): RootlessLinuxInstaller = RootlessLinuxInstaller(
            File(context.filesDir, "linux-installations"), extraction, runner)
    }
}
