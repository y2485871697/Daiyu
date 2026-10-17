package app.daiyu.android.terminal.install

import app.daiyu.android.terminal.LinuxInstallation
import app.daiyu.android.terminal.TerminalConfiguration
import java.io.File
import java.net.URI
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** Transport metadata, NOT an installed-environment registry or a grant to download. */
class LinuxRootfsArtifact internal constructor(
    val id: String,
    val distribution: String,
    val version: String,
    val abi: String,
    val archive: RootfsArchive,
    val compressedBytes: Long,
    val sha256: String,
    mirrors: List<RootfsMirror>,
    val stripComponents: Int = 0
) {
    val mirrors: List<RootfsMirror> = Collections.unmodifiableList(ArrayList(mirrors))
    init {
        require(id.matches(Regex("[A-Za-z0-9._-]{1,128}")))
        require(distribution.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        require(version.matches(Regex("[A-Za-z0-9._-]{1,64}")))
        require(abi.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        require(compressedBytes > 0 && sha256.matches(Regex("[a-f0-9]{64}")))
        require(stripComponents in 0..1)
        require(this.mirrors.isNotEmpty() && this.mirrors.map { it.id }.distinct().size == this.mirrors.size)
    }
}

enum class RootfsArchive { TAR_GZIP, TAR_XZ }

data class RootfsMirror(val id: String, val uri: URI) {
    init {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        require(uri.scheme == "https" && !uri.host.isNullOrBlank())
        require(uri.userInfo == null && uri.fragment == null && uri.port in setOf(-1, 443))
        require(uri.path.startsWith('/') && uri.path.isNotEmpty())
    }
}

/** Frozen exact contents to show in confirmation UI. Constructing this performs no I/O. */
class PreparedLinuxInstall internal constructor(
    internal val owner: Any,
    val artifact: LinuxRootfsArtifact,
    val mirror: RootfsMirror,
    installation: LinuxInstallation
) {
    val installation: LinuxInstallation = installation.copy(
        sharedMounts = Collections.unmodifiableMap(LinkedHashMap(installation.sharedMounts)))
    val targetRootfs: File get() = installation.rootfs
}

/** A one-shot in-memory capability; it is never inferred from files, selection or catalog state. */
class DownloadInstallApproval internal constructor(internal val prepared: PreparedLinuxInstall) {
    private val consumed = AtomicBoolean(false)
    internal fun consume(owner: Any): PreparedLinuxInstall {
        check(prepared.owner === owner) { "linux_install_wrong_owner" }
        check(consumed.compareAndSet(false, true)) { "linux_install_approval_consumed" }
        return prepared
    }
}

/**
 * Explicit parent-owned cancellation. cancel() interrupts a registered live connection, not just
 * a progress flag. The short publish section and cancellation are linearized on the same lock.
 * Calling code must cancel this object when its operation/lifecycle is cancelled.
 */
class LinuxInstallCancellation {
    private val lock = Any()
    private var cancelled = false
    private val listeners = LinkedHashSet<() -> Unit>()

    fun cancel() {
        val callbacks = synchronized(lock) {
            if (cancelled) return
            cancelled = true
            listeners.toList().also { listeners.clear() }
        }
        callbacks.forEach { runCatching(it) }
    }

    fun check() = synchronized(lock) {
        if (cancelled) throw CancellationException("linux_install_cancelled")
    }

    internal fun register(callback: () -> Unit): AutoCloseable {
        val callNow = synchronized(lock) {
            if (cancelled) true else { listeners.add(callback); false }
        }
        if (callNow) runCatching(callback)
        return AutoCloseable { synchronized(lock) { listeners.remove(callback) } }
    }

    internal fun <T> publish(block: () -> T): T = synchronized(lock) {
        check()
        block()
    }
}

fun interface LinuxArtifactDownload {
    fun download(artifact: LinuxRootfsArtifact, mirror: RootfsMirror, destination: File,
                 cancellation: LinuxInstallCancellation)
}

/** Implementations must extract into the empty private staging directory, never the final root. */
fun interface LinuxRootfsExtraction {
    fun extract(artifact: LinuxRootfsArtifact, verifiedArchive: File, stagingRoot: File,
                cancellation: LinuxInstallCancellation)
}

data class LinuxProbeResult(val exitCode: Int, val stdout: String)

/** No ProcessBuilder/default shell fallback here: the application owner supplies its runner. */
fun interface LinuxInstallProbeRunner {
    fun run(configuration: TerminalConfiguration, command: String,
            cancellation: LinuxInstallCancellation): LinuxProbeResult
}

/** Read-only reconstruction. Neither a receipt nor a persisted consent/capability. */
internal data class RecoveredLinuxInstall(
    val prepared: PreparedLinuxInstall,
    val published: Boolean,
    val unknown: Boolean,
    val registration: app.daiyu.android.terminal.FrozenLinuxApproval?,
)

/** Files only. This receipt is NOT catalog approval, selection, or an execution lease. */
data class LinuxInstallReceipt(val installation: LinuxInstallation, val directorySyncConfirmed: Boolean)

/** Publication and cleanup are independent: preserve the receipt and the first failure. */
internal class LinuxInstallCleanupPending(
    val receipt: LinuxInstallReceipt?,
    val originalFailure: Throwable?,
    cleanupFailure: Throwable,
) : IllegalStateException("linux_install_cleanup_pending", originalFailure ?: cleanupFailure) {
    init { if (originalFailure != null && originalFailure !== cleanupFailure) addSuppressed(cleanupFailure) }
}
