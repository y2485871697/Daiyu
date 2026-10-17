package app.daiyu.android.terminal.install

import android.content.Context
import app.daiyu.android.terminal.LinuxExecutionMode
import app.daiyu.android.terminal.requireProotFiles
import app.daiyu.android.terminal.RootScopeLaunch
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
 * Files-only install transaction. Caller explicitly confirms a PreparedLinuxInstall and wires
 * operation cancellation. The app factory supplies the real bounded Android PTY probe runner
 * and bounded tar extraction; injected runners remain a host-test/audited integration seam.
 * No catalog writes, selection, profiles, shell fallback or implicit network requests.
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
    },
    private val fileForce: (FileChannel) -> Unit = { it.force(true) },
    private val fileOps: FileOps = PosixFileOps,
    private val executionMode: LinuxExecutionMode = LinuxExecutionMode.PROOT,
    private val requireChrootTransport: () -> Unit = { RootScopeLaunch.requireTrustedTransport(); Unit },
) {
    private val owner = Any()
    // Strong references to unknown PTY resources; their writer locks/staging remain on disk.
    // Neither Activity/page disposal nor a new install request is evidence of termination.
    private val unknownProbes = java.util.concurrent.CopyOnWriteArrayList<LinuxInstallProbeUnknown>()

    private class PendingCleanup(val prepared: PreparedLinuxInstall, val staging: File?,
        val archive: File?, val lock: File, val receipt: LinuxInstallReceipt?, var failure: Throwable?) {
        var lockReleased = false
    }
    private val pendingCleanups = java.util.concurrent.ConcurrentHashMap<File, PendingCleanup>()

    private fun cleanupOwned(record: PendingCleanup) {
        if (!record.lockReleased) {
            record.staging?.let { deleteTreeWithoutFollowingLinks(it.toPath()) }
            record.archive?.let { fileOps.deleteIfExists(it.toPath()) }
            // Persist child cleanup before releasing the writer lease.
            directorySync(record.lock.parentFile!!)
            fileOps.deleteIfExists(record.lock.toPath())
            record.lockReleased = true
        }
        // If this sync fails, a retry must not delete a new owner's replacement lock.
        directorySync(record.lock.parentFile!!)
    }

    internal fun retryCleanup(prepared: PreparedLinuxInstall): LinuxInstallReceipt? {
        check(prepared.owner === owner) { "linux_install_wrong_owner" }
        val record = checkNotNull(pendingCleanups[prepared.targetRootfs]) { "linux_install_cleanup_not_retained" }
        check(record.prepared === prepared) { "linux_install_cleanup_request_changed" }
        securePath(record.lock.parentFile!!)
        try { cleanupOwned(record) }
        catch (failure: Exception) {
            if (record.failure == null) record.failure = failure
            throw LinuxInstallCleanupPending(record.receipt, record.failure, failure)
        }
        check(pendingCleanups.remove(prepared.targetRootfs, record))
        return record.receipt
    }

    private fun finishCleanup(record: PendingCleanup) {
        try { cleanupOwned(record) }
        catch (failure: Exception) {
            if (record.failure == null) record.failure = failure
            pendingCleanups[record.prepared.targetRootfs] = record
            throw LinuxInstallCleanupPending(record.receipt, record.failure, failure)
        }
    }

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
        require(installation.executionMode == executionMode) { "linux_install_mode_mismatch" }
        cleanAbsolutePath(installation.rootfs)
        if (executionMode == LinuxExecutionMode.PROOT) {
            val helpers = installation.requireProotFiles()
            listOf(helpers.executable, helpers.loader).forEach(::cleanAbsolutePath)
            require(installation.prootSha256.matches(Regex("[a-f0-9]{64}")))
            require(installation.loaderSha256.matches(Regex("[a-f0-9]{64}")))
        } else {
            require(installation.proot == null && installation.loader == null &&
                installation.prootSha256.isEmpty() && installation.loaderSha256.isEmpty())
        }
        val expected = File(File(File(privateRoot, installation.environmentId), installation.generation), "rootfs")
        require(installation.rootfs == expected) { "linux_install_target_outside_private_root" }
        return PreparedLinuxInstall(owner, artifact, mirror, installation)
    }

    /**
     * Persist only the request description. No consent, process status or durability receipt is
     * serialized. The production coordinator calls this before it can offer installation.
     */
    internal fun rememberRequest(prepared: PreparedLinuxInstall) {
        check(prepared.owner === owner) { "linux_install_wrong_owner" }
        secureDirectory(prepared.targetRootfs.parentFile!!)
        val bytes = requestBytes(prepared)
        val journal = File(privateRoot, REQUEST)
        securePath(journal)
        if (exists(journal) && readBounded(journal).contentEquals(bytes)) {
            directorySync(privateRoot)
            return
        }
        publishRequest(bytes)
    }

    /** Preserve the exact catalog generation BEFORE its first possible catalog write. */
    internal fun rememberRegistration(prepared: PreparedLinuxInstall,
                                      approval: app.daiyu.android.terminal.FrozenLinuxApproval) {
        check(prepared.owner === owner) { "linux_install_wrong_owner" }
        check(approval.installation == prepared.installation.copy(generation = approval.installation.generation))
        checkGeneration(approval.installation.generation)
        val journal = readBounded(File(privateRoot, REQUEST))
        val linked = linkedRequestBytes(prepared, approval.installation.generation)
        check(journal.contentEquals(requestBytes(prepared)) || journal.contentEquals(linked))
        val file = File(prepared.targetRootfs.parentFile!!, REGISTRATION)
        securePath(file)
        val bytes = registrationBytes(prepared, approval.installation.generation)
        if (exists(file)) {
            check(readBounded(file).contentEquals(bytes)) { "linux_install_registration_mismatch" }
            // Visible identical bytes may be from a failed force, not a durability receipt.
            FileChannel.open(file.toPath(), WRITE).use(fileForce)
        } else writeForced(file, bytes)
        directorySync(file.parentFile!!)
        // Link the registration identity into the durable request before catalog IO. Recovery
        // must reject a missing registration description rather than generate a replacement UUID.
        if (!journal.contentEquals(linked)) publishRequest(linked) else directorySync(privateRoot)
    }

    /** Retire only a catalog-recorded request. Never delete files, locks or UNKNOWN residue. */
    internal fun completeRegistration(prepared: PreparedLinuxInstall,
                                      approval: app.daiyu.android.terminal.FrozenLinuxApproval) {
        check(prepared.owner === owner) { "linux_install_wrong_owner" }
        check(approval.installation == prepared.installation.copy(generation = approval.installation.generation))
        val journal = readBounded(File(privateRoot, REQUEST))
        if (journal.contentEquals(EMPTY_REQUEST)) {
            directorySync(privateRoot)
            return
        }
        check(journal.contentEquals(linkedRequestBytes(prepared, approval.installation.generation)))
        val parent = prepared.targetRootfs.parentFile!!
        securePath(parent)
        check(parent.list()?.all { it == "rootfs" || it == REGISTRATION } == true) {
            "linux_install_completion_unknown"
        }
        check(inspectPublished(prepared) != null) { "linux_install_completion_missing" }
        check(readBounded(File(parent, REGISTRATION)).contentEquals(
            registrationBytes(prepared, approval.installation.generation)))
        publishRequest(EMPTY_REQUEST)
    }

    /** Explicit discard is permitted only for an untouched, non-published request. */
    internal fun discardRequest(prepared: PreparedLinuxInstall) {
        check(prepared.owner === owner) { "linux_install_wrong_owner" }
        val parent = prepared.targetRootfs.parentFile!!
        securePath(parent)
        check(readBounded(File(privateRoot, REQUEST)).contentEquals(requestBytes(prepared)))
        check(!exists(prepared.targetRootfs) && parent.list()?.isEmpty() == true) {
            "linux_install_discard_not_safe"
        }
        // A durable empty description distinguishes intentional discard from a missing journal.
        // Do not delete rootfs, staging, writer locks or even old generation directories.
        publishRequest(EMPTY_REQUEST)
    }

    /**
     * Read-only restart recovery. The pinned catalog and original packaged helper pair must still
     * match byte-for-byte. A visible marker is NOT a publication receipt; a lock/residue is UNKNOWN,
     * never evidence of a dead process. No mkdir/fsync/probe/download/catalog mutation here.
     */
    internal fun recoverRequest(packaged: PackagedProotHelpers,
                                artifacts: List<LinuxRootfsArtifact> = LinuxRootfsCatalog.artifacts): RecoveredLinuxInstall? =
        recoverRequest(packaged, packaged.abi, artifacts)

    internal fun recoverChrootRequest(abi: String,
        artifacts: List<LinuxRootfsArtifact> = LinuxRootfsCatalog.artifacts): RecoveredLinuxInstall? {
        check(executionMode == LinuxExecutionMode.CHROOT)
        return recoverRequest(null, abi, artifacts)
    }

    private fun recoverRequest(packaged: PackagedProotHelpers?, abi: String,
                               artifacts: List<LinuxRootfsArtifact>): RecoveredLinuxInstall? {
        securePath(privateRoot)
        val journal = File(privateRoot, REQUEST)
        securePath(journal)
        check(!exists(File(privateRoot, "$REQUEST.tmp"))) { "linux_install_request_incomplete" }
        if (!exists(journal)) {
            check(!exists(privateRoot) || (privateRoot.isDirectory && privateRoot.list()?.isEmpty() == true)) {
                "linux_install_request_missing"
            }
            return null
        }
        val bytes = readBounded(journal)
        if (bytes.contentEquals(EMPTY_REQUEST)) return null
        val text = bytes.toString(Charsets.UTF_8)
        check(text.startsWith("daiyu-install-request-v1\ndaiyu-rootfs-marker-v1\n")) { "linux_install_request_format" }
        val fields = text.lines().drop(2).dropLast(1).associate { line ->
            val separator = line.indexOf('=')
            check(separator > 0) { "linux_install_request_format" }
            line.substring(0, separator) to line.substring(separator + 1)
        }
        fun field(name: String) = checkNotNull(fields[name]) { "linux_install_request_field" }
        val artifact = artifacts.singleOrNull { it.id == field("entry") && it.abi == abi }
            ?: error("linux_install_request_catalog_changed")
        val installation = if (executionMode == LinuxExecutionMode.PROOT) {
            val original = checkNotNull(packaged)
            LinuxInstallation(artifact.distribution, field("environment"), field("generation"),
                File(field("rootfs")), File(field("proot")), field("proot_sha256"),
                File(field("loader")), field("loader_sha256"), "/", emptyMap()).also {
                check(it.proot == original.proot && it.prootSha256 == original.prootSha256 &&
                    it.loader == original.loader && it.loaderSha256 == original.loaderSha256) {
                    "linux_install_original_helpers_unavailable"
                }
            }
        } else {
            check(field("execution_mode") == "chroot") { "linux_install_mode_mismatch" }
            LinuxInstallation(artifact.distribution, field("environment"), field("generation"),
                File(field("rootfs")), executionMode = LinuxExecutionMode.CHROOT)
        }
        val prepared = prepare(artifact, field("mirror"), installation)
        // Exact canonical comparison rejects changed hashes/URLs, duplicate/unknown fields,
        // truncation, non-UTF8, alternative paths and noncanonical values without trusting them.
        val linkedGeneration = fields["registration_generation"]
        linkedGeneration?.let(::checkGeneration)
        val expected = if (linkedGeneration == null) requestBytes(prepared)
            else linkedRequestBytes(prepared, linkedGeneration)
        check(expected.contentEquals(bytes)) { "linux_install_request_mismatch" }
        securePath(prepared.targetRootfs.parentFile!!)
        validateHelpers(installation, LinuxInstallCancellation())
        val registrationFile = File(prepared.targetRootfs.parentFile!!, REGISTRATION)
        securePath(registrationFile)
        check(linkedGeneration == null || exists(registrationFile)) { "linux_install_registration_missing" }
        val registration = if (!exists(registrationFile)) null else {
            val saved = readBounded(registrationFile)
            val generation = saved.toString(Charsets.UTF_8).lines().getOrNull(2)
                ?.removePrefix("generation=") ?: error("linux_install_registration_format")
            checkGeneration(generation)
            check(linkedGeneration == null || linkedGeneration == generation) { "linux_install_registration_mismatch" }
            check(saved.contentEquals(registrationBytes(prepared, generation))) { "linux_install_registration_mismatch" }
            app.daiyu.android.terminal.FrozenLinuxApproval(installation.copy(generation = generation))
        }
        val published = inspectPublished(prepared) != null
        check(registration == null || published) { "linux_install_registered_target_missing" }
        val names = checkNotNull(prepared.targetRootfs.parentFile!!.list()) { "linux_install_parent_unreadable" }
        val unknown = names.any { it != "rootfs" && it != REGISTRATION }
        return RecoveredLinuxInstall(prepared, published, unknown, registration)
    }

    private fun checkGeneration(generation: String) {
        check(Regex("[A-Za-z0-9_-]{1,128}").matches(generation)) { "linux_install_registration_generation" }
    }

    private fun requestBytes(prepared: PreparedLinuxInstall) =
        "daiyu-install-request-v1\n".toByteArray(Charsets.UTF_8) + markerBytes(prepared)

    private fun linkedRequestBytes(prepared: PreparedLinuxInstall, generation: String) =
        requestBytes(prepared) + "registration_generation=$generation\n".toByteArray(Charsets.UTF_8)

    private fun registrationBytes(prepared: PreparedLinuxInstall, generation: String) =
        ("daiyu-install-registration-description-v1\nrequest_sha256=" +
            MessageDigest.getInstance("SHA-256").digest(requestBytes(prepared)).joinToString("") { "%02x".format(it) } +
            "\ngeneration=$generation\n").toByteArray(Charsets.UTF_8)

    private fun readBounded(file: File): ByteArray {
        securePath(file)
        check(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && file.length() in 1L..20000L) {
            "linux_install_journal_unavailable"
        }
        return file.inputStream().use { input ->
            val result = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                check(result.size() + n <= 20000) { "linux_install_journal_limit" }
                result.write(buffer, 0, n)
            }
            result.toByteArray()
        }
    }

    private fun writeForced(file: File, bytes: ByteArray) {
        securePath(file)
        FileChannel.open(file.toPath(), CREATE_NEW, WRITE).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            fileForce(channel)
        }
    }

    private fun publishRequest(bytes: ByteArray) {
        val journal = File(privateRoot, REQUEST)
        val temporary = File(privateRoot, "$REQUEST.tmp")
        securePath(journal)
        writeForced(temporary, bytes)
        Files.move(temporary.toPath(), journal.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        directorySync(privateRoot)
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
        var cleanupSafe = true
        var receipt: LinuxInstallReceipt? = null
        var originalFailure: Throwable? = null
        var staging: File? = null
        var archive: File? = null
        val lock = File(parent, ".install-lock")
        try {
            check(!pendingCleanups.containsKey(prepared.targetRootfs)) { "linux_install_cleanup_pending" }
            secureDirectory(parent)
            // Cross-instance/process writer exclusion, fail-closed after an interrupted owner.
            // No automatic stale-lock removal: parent can recover after inspecting disk state.
            fileOps.createPrivateDirectory(lock)
            lockOwned = true
            check(!exists(prepared.targetRootfs)) { "linux_install_target_exists" }
            cancellation.check()
            validateHelpers(prepared.installation, cancellation)
            val stage = fileOps.createPrivateStaging(parent)
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
            return LinuxInstallReceipt(prepared.installation, directorySyncConfirmed = true).also { receipt = it }
        } catch (failure: Exception) {
            originalFailure = failure
            if (failure is LinuxInstallProbeUnknown) {
                cleanupSafe = false
                unknownProbes.add(failure)
            }
            if (publicationAttempted && runCatching { inspectPublished(prepared) }.getOrNull() != null) {
                return LinuxInstallReceipt(prepared.installation, directorySyncConfirmed = false).also { receipt = it }
            }
            throw failure
        } finally {
            // Never delete a published target, pre-existing rootfs, or another owner's staging.
            if (cleanupSafe && lockOwned) {
                finishCleanup(PendingCleanup(prepared, staging, archive, lock, receipt, originalFailure))
            }
        }
    }

    /**
     * Explicit retry after a visible rename without its durability receipt. Same prepared request,
     * exact marker, helper validation and exclusive writer lock are required; no probe/network or
     * catalog approval is implied. Inspection alone must never be treated as this receipt.
     */
    fun confirmPublishedDurability(prepared: PreparedLinuxInstall): LinuxInstallReceipt {
        check(prepared.owner === owner) { "linux_install_wrong_owner" }
        val parent = prepared.targetRootfs.parentFile!!
        secureDirectory(parent)
        val lock = File(parent, ".install-lock")
        check(!pendingCleanups.containsKey(prepared.targetRootfs)) { "linux_install_cleanup_pending" }
        fileOps.createPrivateDirectory(lock)
        var receipt: LinuxInstallReceipt? = null
        var originalFailure: Throwable? = null
        try {
            check(inspectPublished(prepared) != null) { "linux_install_not_published" }
            validateHelpers(prepared.installation, LinuxInstallCancellation())
            syncTree(prepared.targetRootfs, LinuxInstallCancellation())
            directorySync(parent)
            check(inspectPublished(prepared) != null) { "linux_install_published_marker_invalid" }
            return LinuxInstallReceipt(prepared.installation, directorySyncConfirmed = true).also { receipt = it }
        } catch (failure: Exception) {
            originalFailure = failure
            throw failure
        } finally {
            finishCleanup(PendingCleanup(prepared, null, null, lock, receipt, originalFailure))
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
        val helpers = if (installation.executionMode == LinuxExecutionMode.PROOT) installation.requireProotFiles() else null
        val rootScope = if (installation.executionMode == LinuxExecutionMode.CHROOT)
            RootScopeLaunch(installation.rootfs, File("/system/bin/su"), identityProbe = true) else null
        val linux = LinuxBackend(
            label = installation.distribution,
            shellArgv = rootScope?.guestArgv() ?: listOf(checkNotNull(helpers).executable.path, "-0", "-r", installation.rootfs.path,
                "-w", "/", "/bin/sh"),
            identityArgv = emptyList(),
            environment = mapOf("HOME" to "/root", "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin") +
                (helpers?.let { mapOf("PROOT_LOADER" to it.loader.path, "PROOT_TMP_DIR" to hostDirectory.path) } ?: emptyMap()),
            expectedUid = 0,
            environmentId = installation.environmentId + ":" + installation.generation,
            guestWorkingDirectory = "/", sharedMounts = emptyMap(), rootScope = rootScope
        )
        val result = runner.run(TerminalConfiguration(backend = "linux", root = false,
            workingDirectory = hostDirectory, linux = linux), "id -u", cancellation)
        cancellation.check()
        check(result.exitCode == 0 && result.stdout.trim() == "0") { "linux_install_probe_failed" }
    }

    private fun validateHelpers(value: LinuxInstallation, cancellation: LinuxInstallCancellation) {
        if (value.executionMode == LinuxExecutionMode.CHROOT) {
            cancellation.check()
            check(value.proot == null && value.loader == null && value.prootSha256.isEmpty() && value.loaderSha256.isEmpty())
            requireChrootTransport()
            return
        }
        val helpers = value.requireProotFiles()
        listOf(helpers.executable to value.prootSha256, helpers.loader to value.loaderSha256).forEach { (file, expected) ->
            securePath(file)
            check(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && file.canRead())
            check(file.length() in 1..(64L * 1024 * 1024))
            check(hash(file, 64L * 1024 * 1024, cancellation).second == expected) { "linux_install_helper_hash" }
        }
        check(helpers.executable.canExecute()) { "linux_install_proot_not_executable" }
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
        val shell = fileOps.canonicalFile(File(root, "bin/sh"))
        check(shell.toPath().startsWith(fileOps.canonicalFile(root).toPath()) && shell.isFile && shell.canRead()) {
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
        if (p.installation.executionMode == LinuxExecutionMode.PROOT) {
            val helpers = p.installation.requireProotFiles()
            append("proot=${helpers.executable.path}\nproot_sha256=${p.installation.prootSha256}\n")
            append("loader=${helpers.loader.path}\nloader_sha256=${p.installation.loaderSha256}\n")
        } else append("execution_mode=chroot\n")
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
        if (!exists(directory)) fileOps.createPrivateDirectory(directory)
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
            check(!fileOps.isSymbolicLink(current)) { "linux_install_symlink_path" }
            current = current.parent
        }
    }

    private fun cleanAbsolutePath(file: File) = app.daiyu.android.terminal.LinuxPathSyntax.host(file.path)

    internal interface FileOps {
        fun deleteIfExists(path: Path): Boolean = Files.deleteIfExists(path)
        fun createPrivateDirectory(directory: File)
        fun createPrivateStaging(parent: File): File
        fun isSymbolicLink(path: Path): Boolean = Files.isSymbolicLink(path)
        fun canonicalFile(file: File): File = file.canonicalFile
    }
    private object PosixFileOps : FileOps {
        override fun createPrivateDirectory(directory: File) { Files.createDirectory(directory.toPath(), PRIVATE_DIRECTORY) }
        override fun createPrivateStaging(parent: File): File =
            Files.createTempDirectory(parent.toPath(), ".staging-", PRIVATE_DIRECTORY).toFile()
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
                fileOps.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                if (exc != null) throw exc
                fileOps.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    companion object {
        private const val MARKER = ".daiyu-rootfs-install"
        private const val REQUEST = ".install-request"
        private const val REGISTRATION = ".registration-request"
        private val EMPTY_REQUEST = "daiyu-install-empty-v1\n".toByteArray(Charsets.UTF_8)
        private val PRIVATE_DIRECTORY = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))

        /** Production extraction is bounded and always staged under Context.filesDir. */
        fun forApp(context: Context, runner: LinuxInstallProbeRunner): RootlessLinuxInstaller =
            forApp(context, TarLinuxRootfsExtraction(), runner)

        /** Explicit extraction seam for audited integrations; never a SAF/shared-storage path. */
        fun forApp(context: Context, extraction: LinuxRootfsExtraction,
                   runner: LinuxInstallProbeRunner): RootlessLinuxInstaller = RootlessLinuxInstaller(
            File(context.filesDir.canonicalFile, "linux-installations"), extraction, runner)

        internal fun forChroot(context: Context, runner: LinuxInstallProbeRunner) = RootlessLinuxInstaller(
            File(context.filesDir.canonicalFile, "linux-chroot-installations"), TarLinuxRootfsExtraction(), runner,
            executionMode = LinuxExecutionMode.CHROOT)
    }
}
