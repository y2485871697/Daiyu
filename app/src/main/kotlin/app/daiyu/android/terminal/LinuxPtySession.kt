package app.daiyu.android.terminal

import app.daiyu.android.domain.tools.TaskAuthorization
import org.json.JSONObject
import java.io.File

/**
 * A run-bound PTY and its selected Linux generation. All inputs are trusted host values,
 * not model arguments. close() only fences operations and requests shutdown: the host must
 * keep this handle until awaitTermination() confirms the selected protocol's termination proof (root scope D plus launcher exit).
 * Do not use AutoCloseable.use as a substitute for that join.
 */
class LinuxPtySession private constructor(
    private val session: PtyRunSession,
    lease: AutoCloseable,
) : AutoCloseable {
    private val termination = LinuxLeaseUntilExit(lease, session::awaitTermination)

    fun read(caller: TaskAuthorization, offset: Int = 0, limit: Int = 16384): JSONObject =
        session.read(caller, offset, limit)

    fun write(caller: TaskAuthorization, requestId: String, inputSequence: Long, bytes: ByteArray): JSONObject =
        session.write(caller, requestId, inputSequence, bytes)

    fun resize(caller: TaskAuthorization, requestId: String, controlSequence: Long, rows: Int, columns: Int): JSONObject =
        session.resize(caller, requestId, controlSequence, rows, columns)

    fun interrupt(caller: TaskAuthorization, requestId: String, controlSequence: Long): JSONObject =
        session.interrupt(caller, requestId, controlSequence)

    /** Retryable, including after a close failure. Never releases the generation. */
    override fun close() = session.close()

    /** Worker-only; overlapping/reentrant joins return false. False or failure retains the lease for retry. */
    fun awaitTermination(timeoutMillis: Long): Boolean = termination.awaitTermination(timeoutMillis)

    companion object {
        /**
         * Acquires the expected generation before constructing launch arguments. A post-launch
         * failure throws LinuxPtyStartupFailure: its cleanup handle must be retained and joined
         * just like a successful session. Other failures have no owned Process and release here.
         */
        fun open(
            owner: PtyRunOwner,
            store: LinuxEnvironmentStore,
            expected: LinuxGenerationRef,
            helper: File,
            tempDirectory: File,
            hostWorkingDirectory: File,
            journalDirectory: File,
            sessionScope: String,
            rows: Int = 24,
            columns: Int = 80,
        ): LinuxPtySession = openConfigured(owner, store, expected, tempDirectory, hostWorkingDirectory) { configuration ->
            val backend = checkNotNull(configuration.linux)
            val root = backend.rootScope
            if (root == null) {
                check(backend.shellArgv.firstOrNull() != "/system/bin/chroot") { "linux_chroot_requires_root_scope" }
                PtyRunSession.open(owner, helper, backend.shellArgv, backend.environment + configuration.environment,
                    checkNotNull(configuration.workingDirectory), journalDirectory, sessionScope, rows, columns)
            } else {
                check(!configuration.root && backend.shellArgv == root.guestArgv()) { "linux_chroot_launch_mismatch" }
                check(backend.guestWorkingDirectory == "/" && backend.sharedMounts.isEmpty()) { "linux_chroot_mapping_not_supported" }
                PtyRunSession.openRootScope(owner, helper, root, backend.environment + configuration.environment,
                    checkNotNull(configuration.workingDirectory), journalDirectory, sessionScope, rows, columns)
            }
        }

        // Per-call test seam; production always invokes the real PtyRunSession.open above.
        internal fun openConfigured(
            owner: PtyRunOwner,
            store: LinuxEnvironmentStore,
            expected: LinuxGenerationRef,
            tempDirectory: File,
            hostWorkingDirectory: File,
            openSession: (TerminalConfiguration) -> PtyRunSession,
        ): LinuxPtySession {
            val lease = store.acquire(expected)
            var execution: AutoCloseable? = null
            try {
                check(owner.environmentId == expected.environmentId && owner.generation == expected.generation) {
                    "linux_pty_owner_generation_mismatch"
                }
                val configuration = lease.configuration(tempDirectory, hostWorkingDirectory)
                check(configuration.linux?.environmentId == "${owner.environmentId}:${owner.generation}") {
                    "linux_pty_owner_generation_mismatch"
                }
                execution = lease.claimExecution()
                return LinuxPtySession(openSession(configuration), execution)
            } catch (failure: PtyStartupFailure) {
                // Do not close the lease, even if shutdown was requested during startup: neither
                // that request nor an X frame/EOF proves that the helper Process has terminated.
                throw LinuxPtyStartupFailure(failure, LinuxPtyStartupCleanup(failure.cleanup, checkNotNull(execution)))
            } catch (failure: Throwable) {
                // PtyRunSession's launch contract transfers every owned Process via PtyStartupFailure.
                try { (execution ?: lease).close() }
                catch (settlement: Throwable) {
                    if (settlement !== failure) failure.addSuppressed(settlement)
                    throw LinuxPtyStartupFailure(failure, LinuxPtyStartupCleanup.unstarted(execution ?: lease))
                }
                throw failure
            }
        }
    }
}

/** Ownership transfer on failed launch; catch this before discarding an open() exception. */
class LinuxPtyStartupFailure internal constructor(
    cause: Throwable,
    val cleanup: LinuxPtyStartupCleanup,
) : IllegalStateException(cause.message, cause)

/** Keeps the original startup cleanup and generation together, without exposing a raw lease. */
class LinuxPtyStartupCleanup private constructor(
    private val requestShutdown: () -> Unit,
    waitForExit: (Long) -> Boolean,
    lease: AutoCloseable,
) : AutoCloseable {
    internal constructor(cleanup: PtyStartupCleanup, lease: AutoCloseable) :
        this(cleanup::requestClose, cleanup::awaitTermination, lease)

    private val termination = LinuxLeaseUntilExit(lease, waitForExit)

    fun requestClose() = requestShutdown()
    override fun close() = requestClose()
    fun awaitTermination(timeoutMillis: Long): Boolean = termination.awaitTermination(timeoutMillis)

    companion object {
        // Only the no-owned-Process exception path may transfer a pin-only cleanup.
        internal fun unstarted(lease: AutoCloseable) = LinuxPtyStartupCleanup({}, { true }, lease)
    }
}

/**
 * No polling thread, GC release, timeout release, or implicit replacement process.
 * One caller owns the entire wait/release attempt; overlaps (including reentry) return false
 * without invoking external code. Neither waiting nor lease release holds the state lock.
 * A false wait or exception leaves the lease owned and the attempt retryable. In particular,
 * a throwing close propagates its failure, never records success, and may be retried (Lease
 * close is idempotent). Internal visibility/AutoCloseable allow testing that failure boundary.
 */
internal class LinuxLeaseUntilExit(
    private val lease: AutoCloseable,
    private val waitForExit: (Long) -> Boolean,
) {
    private val lock = Any()
    private var inFlight = false
    private var released = false

    fun awaitTermination(timeoutMillis: Long): Boolean {
        require(timeoutMillis in 1..30_000)
        synchronized(lock) {
            if (released) return true
            if (inFlight) return false
            inFlight = true
        }
        var releaseSucceeded = false
        try {
            if (!waitForExit(timeoutMillis)) return false
            lease.close()
            releaseSucceeded = true
            return true
        } finally {
            synchronized(lock) {
                released = releaseSucceeded
                inFlight = false
            }
        }
    }
}
