package app.daiyu.android

import android.content.Context
import app.daiyu.android.domain.ToolInvocation
import app.daiyu.android.domain.runtime.RunExecutionOwner
import app.daiyu.android.domain.tools.SessionCapabilities
import app.daiyu.android.domain.tools.TaskAuthorization
import app.daiyu.android.domain.tools.WriteEffect
import app.daiyu.android.integration.tools.*
import app.daiyu.android.vd.*
import app.daiyu.android.vd.runtime.OwnerV2Contract
import app.daiyu.android.vdapproval.VirtualDisplayApprovalRequest
import org.json.JSONObject
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipal
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Semaphore

/** Production backend for ONE retained, already accepted parent run. Not a catalog or dispatcher.
 * Construction/retention does no storage or launcher I/O. The existing session reserves on its
 * worker before approval/exposure; only its explicitly executed Start leaf reaches the launcher.
 */
internal object ControllerVirtualDisplayRuntime {
    /** Implement in the ONE real controller, using its actual authorization and accepted-run stop.
     * No reserve callback: this adapter, not an in-memory parent substitute, owns that operation.
     */
    interface Parent {
        val requiresExplicitApproval: Boolean get() = false
        fun showApproval(request: VirtualDisplayApprovalRequest)
        fun combinedAuthorization(owner: RunExecutionOwner, capabilities: SessionCapabilities,
                                  approvedEffects: Set<WriteEffect>): TaskAuthorization
        fun stopAcceptedRun(owner: RunExecutionOwner)
        fun beginBatch(leaf: R08VirtualDisplayCatalogLeaf, originalWholeBatch: List<ToolInvocation>):
            ControllerVirtualDisplaySession.BatchCompletion
    }

    /** Add this exact session to the parent's EXISTING accepted-run retention record, synchronously,
     * before returning. Keep it on every exception/UNKNOWN; remove only after observed Drained.
     * Must not execute tools, authorize, or start anything. A failed retain must not be retried.
     */
    fun interface Retention { fun retain(session: ControllerVirtualDisplaySession) }

    /** Call with the real Runner-issued owner, immutable host-planned steps, and the actual parent.
     * No model-selected paths, shell fragments, transport credentials, or replacement ledgers.
     */
    fun create(context: Context, owner: RunExecutionOwner, capabilities: SessionCapabilities,
               writes: List<R08VirtualDisplayWrite>, reads: List<R08VirtualDisplayRead> = emptyList(),
               parent: Parent, retention: Retention): ControllerVirtualDisplaySession {
        val application = context.applicationContext
        return assemble(owner, capabilities, writes, reads, parent, retention,
            LeaseStore({ application.noBackupFilesDir.canonicalFile.toPath().resolve("r08-controller-leases-v1") })) {
            RealHost(AndroidVirtualDisplayOwnerHost(application))
        }
    }

    /** Package-only assembly seam: tests substitute a host, never a production transport/launcher. */
    internal fun assemble(owner: RunExecutionOwner, capabilities: SessionCapabilities,
                          writes: List<R08VirtualDisplayWrite>, reads: List<R08VirtualDisplayRead>,
                          parent: Parent, retention: Retention, store: LeaseStore,
                          hostFactory: () -> Host): ControllerVirtualDisplaySession {
        val frozenWrites = writes.toList()
        val frozenReads = reads.toList()
        var reserved: DurableLease? = null
        var reserveAttempted = false
        val bridge = object : ControllerVirtualDisplaySession.Parent {
            override val requiresExplicitApproval get() = parent.requiresExplicitApproval
            @Synchronized override fun reserve(actual: RunExecutionOwner, caps: SessionCapabilities):
                ControllerVirtualDisplaySession.Lease {
                check(actual === owner && caps === capabilities && !reserveAttempted) { FAILURE }
                reserveAttempted = true // consumed even if mkdir, lock, journal, or publication fails
                return store.reserve { generation ->
                    R08VirtualDisplayPlan.prepare(owner, capabilities, generation, frozenWrites, frozenReads)
                }.also { reserved = it }
            }
            override fun showApproval(request: VirtualDisplayApprovalRequest) = parent.showApproval(request)
            override fun combinedAuthorization(owner: RunExecutionOwner, capabilities: SessionCapabilities,
                                               approvedEffects: Set<WriteEffect>) =
                parent.combinedAuthorization(owner, capabilities, approvedEffects)
            override fun stopAcceptedRun(owner: RunExecutionOwner) = parent.stopAcceptedRun(owner)
            override fun beginBatch(leaf: R08VirtualDisplayCatalogLeaf, originalWholeBatch: List<ToolInvocation>) =
                parent.beginBatch(leaf, originalWholeBatch)
        }
        val session = ControllerVirtualDisplaySession(capabilities, frozenWrites, frozenReads, bridge) {
            val lease = checkNotNull(reserved) { FAILURE }
            val host = hostFactory() // real host constructor is non-launching
            val adapter = OwnerAdapter(host, lease.plan.generation, lease.plan.bindingKey)
            ControllerVirtualDisplaySession.OwnerHandle(adapter, adapter::physicalObservation,
                adapter, host.retainedHost)
        }
        retention.retain(session)
        return session
    }

    internal interface Host {
        val client: VirtualDisplayOwnerClient?
        val retainedHost: AndroidVirtualDisplayOwnerHost? get() = null
        fun start(generation: String, binding: OwnerRunBinding): AndroidVirtualDisplayOwnerHost.Start
        fun observe(): AndroidVirtualDisplayOwnerHost.Observation
    }
    private class RealHost(override val retainedHost: AndroidVirtualDisplayOwnerHost) : Host {
        override val client get() = retainedHost.client
        override fun start(generation: String, binding: OwnerRunBinding) = retainedHost.start(generation, binding)
        override fun observe() = retainedHost.observe()
    }

    /** Retains the SAME host/client and identity after any ambiguous launch or send. No reconnect,
     * cancellation-as-exit, implicit initialize/release, automatic shutdown, or launch retry API.
     */
    internal class OwnerAdapter(private val host: Host, private val generation: String,
                                private val bindingKey: String) : R08VirtualDisplayEndpoint,
        ControllerVirtualDisplaySession.UninitializedShutdown {
        private val state = Any()
        private var attempted = false
        private var ready = false
        private var initializationAttempted = false
        private var maintenance = false
        private var lookupAttempted = false
        private var proven = false
        private var shutdownAttempted = false

        override fun start(generation: String, binding: OwnerRunBinding): Boolean {
            VirtualDisplayOwnerClient.checkWorker()
            synchronized(state) {
                check(matches(generation, binding) && !attempted && !maintenance) { FAILURE }
                attempted = true
            }
            val result = host.start(generation, binding) == AndroidVirtualDisplayOwnerHost.Start.READY
            synchronized(state) { ready = result }
            return result
        }
        override fun initialize(): OwnerReply {
            VirtualDisplayOwnerClient.checkWorker()
            val client = synchronized(state) {
                check(ready && !maintenance && !initializationAttempted) { FAILURE }
                initializationAttempted = true // BEFORE client lookup/send, including a lost reply
                checkNotNull(host.client) { FAILURE }
            }
            return client.initialize()
        }
        private fun client(): VirtualDisplayOwnerClient = synchronized(state) {
            check(ready && !maintenance) { FAILURE }
            checkNotNull(host.client) { FAILURE }
        }
        override fun execute(id: String, operation: String, parameters: JSONObject) =
            client().execute(id, operation, parameters)
        override fun lookup(id: String, operation: String, parameters: JSONObject) =
            client().lookup(id, operation, parameters)
        override fun lookupGeneration() = client().lookupGeneration()
        override fun observe() = physicalObservation().lifecycle
        fun physicalObservation(): AndroidVirtualDisplayOwnerHost.Observation {
            val observation = host.observe()
            // A failed attempt is not a never-attempted host, even if failure preceded Process.start.
            return if (synchronized(state) { attempted } &&
                observation.lifecycle == AndroidVirtualDisplayOwnerHost.Lifecycle.NOT_STARTED)
                observation.copy(lifecycle = AndroidVirtualDisplayOwnerHost.Lifecycle.UNKNOWN)
            else observation
        }

        override fun lookupOnly(generation: String, binding: OwnerRunBinding):
            ControllerVirtualDisplaySession.UninitializedEvidence {
            VirtualDisplayOwnerClient.checkWorker()
            val client = synchronized(state) {
                if (lookupAttempted) return ControllerVirtualDisplaySession.UninitializedEvidence.UNKNOWN
                lookupAttempted = true
                maintenance = true // fence initialize/execute BEFORE authenticated receipt lookup
                if (!matches(generation, binding) || !ready || initializationAttempted)
                    return ControllerVirtualDisplaySession.UninitializedEvidence.UNKNOWN
                host.client ?: return ControllerVirtualDisplaySession.UninitializedEvidence.UNKNOWN
            }
            val reply = client.lookupGeneration() // existing validator authenticates digest/binding
            val body = reply.payloadCopy()
            // v2 receipt has no process-initialized field. A found record (even old) is NOT proof.
            // Only a strict miss + this private READY host + never-crossed initialize admission is
            // sufficient here. The server/client still independently reject initialized shutdown.
            val proof = reply.outcome == OwnerReply.Outcome.REPLIED && body != null &&
                body.opt("op") == "receipt" && body.opt("ok") == true &&
                body.opt("receiptOnly") == true && body.opt("executed") == false &&
                body.opt("found") == false && !body.has("receipt")
            synchronized(state) { proven = proof }
            return if (proof) ControllerVirtualDisplaySession.UninitializedEvidence.PROVEN_UNINITIALIZED
            else ControllerVirtualDisplaySession.UninitializedEvidence.UNKNOWN
        }
        override fun shutdownOnce(generation: String, binding: OwnerRunBinding) {
            VirtualDisplayOwnerClient.checkWorker()
            val client = synchronized(state) {
                check(!shutdownAttempted) { FAILURE }
                shutdownAttempted = true
                check(matches(generation, binding) && maintenance && proven && !initializationAttempted) { FAILURE }
                checkNotNull(host.client) { FAILURE }
            }
            val reply = client.shutdownUninitialized()
            val body = reply.payloadCopy()
            check(reply.outcome == OwnerReply.Outcome.REPLIED && body != null && body.opt("ok") == true &&
                body.opt("initialized") == false && body.opt("receiptsClosed") == true) { FAILURE }
            // This response is NOT exit evidence. The session must separately observe physical drain.
        }
        private fun matches(generation: String, binding: OwnerRunBinding) =
            generation == this.generation && OwnerV2Contract.bindingDigest(binding.trusted) == bindingKey
        override fun toString() = "R08OwnerAdapter(private)"
    }

    internal enum class Mutation { RESERVE, RELEASE }
    /** Package-only failure boundaries; BEFORE_* throws before the named I/O, without a fallback. */
    internal enum class WritePoint { BEFORE_RESERVE_IO, BEFORE_LOCK_OPEN, BEFORE_LOCK,
        BEFORE_FILE_FORCE, BEFORE_DIRECTORY_FORCE, BEFORE_ATOMIC_MOVE,
        DIRECTORY_CREATED, JOURNAL_CREATED, PENDING_CREATED,
        FILE_SYNCED, PUBLISHED, DIRECTORY_SYNCED, BEFORE_UNLOCK, BEFORE_CLOSE }

    /** One lifetime OS lock + one process-wide semaphore, NOT a run registry. The semaphore avoids
     * opening/closing a second descriptor for the lock inode (which could drop a POSIX process lock).
     * UNKNOWN retains the acquired semaphore and existing references, not necessarily an OS lock:
     * early reserve failure may precede lock acquisition; successful unlock followed by failed close
     * has already released it. Neither case permits an in-process retry or reports Drained.
     * There is intentionally no reset, delete, repair, reclaim-on-timeout, or recovery-as-new-generation method.
     */
    internal class LeaseStore(private val rootProvider: () -> Path,
                              private val processGate: Semaphore = productionGate,
                              private val fs: R08PrivateFileSystem = StrictR08PrivateFileSystem,
                              private val fault: (Mutation, WritePoint) -> Unit = { _, _ -> }) {
        private var attempted = false
        private var root: Path? = null
        private var owner: UserPrincipal? = null
        private var channel: FileChannel? = null
        private var lock: FileLock? = null
        private var held = false
        private var current: DurableLease? = null

        @Synchronized fun reserve(prepare: (String) -> R08VirtualDisplayPlan): DurableLease = guarded {
            check(!attempted) { FAILURE }
            attempted = true
            check(processGate.tryAcquire()) { FAILURE }
            held = true
            fault(Mutation.RESERVE, WritePoint.BEFORE_RESERVE_IO)
            val base = rootProvider()
            check(base.isAbsolute && base.normalize() == base) { FAILURE }
            checkAncestors(base.parent, fs)
            owner = Files.getOwner(base.parent, NOFOLLOW_LINKS)
            if (!Files.exists(base, NOFOLLOW_LINKS)) {
                fs.createDirectory(base, DIR_MODE)
                sync(base.parent, Mutation.RESERVE)
            }
            root = base
            checkPrivate(base, true)
            val lockPath = base.resolve("lease.lock")
            fault(Mutation.RESERVE, WritePoint.BEFORE_LOCK_OPEN)
            channel = if (!Files.exists(lockPath, NOFOLLOW_LINKS))
                fs.openNew(lockPath, FILE_MODE)
            else {
                checkPrivate(lockPath, false)
                fs.openExisting(lockPath, WRITE, NOFOLLOW_LINKS)
            }
            checkPrivate(lockPath, false)
            fault(Mutation.RESERVE, WritePoint.BEFORE_LOCK)
            lock = checkNotNull(channel!!.tryLock()) { FAILURE }
            force(channel!!, Mutation.RESERVE)
            sync(base, Mutation.RESERVE)
            sync(base.parent, Mutation.RESERVE)
            inspectHistory(base)
            val generation = UUID.randomUUID().toString().replace("-", "")
            val plan = prepare(generation)
            check(plan.generation == generation) { FAILURE }
            val directory = base.resolve("generation-$generation")
            fs.createDirectory(directory, DIR_MODE)
            sync(base, Mutation.RESERVE)
            fault(Mutation.RESERVE, WritePoint.DIRECTORY_CREATED)
            val journalRoot = directory.resolve("journal")
            fs.createDirectory(journalRoot, DIR_MODE)
            sync(journalRoot, Mutation.RESERVE)
            sync(directory, Mutation.RESERVE)
            fault(Mutation.RESERVE, WritePoint.JOURNAL_CREATED)
            val journal = R08PersistentJournal(journalRoot, fs)
            val record = record(plan)
            publish(directory, "claim", record, Mutation.RESERVE)
            DurableLease(this, directory, record, plan, journal).also { current = it }
        }

        @Synchronized internal fun owns(lease: DurableLease): Boolean =
            current === lease && held && lock?.isValid == true && channel?.isOpen == true

        @Synchronized internal fun release(lease: DurableLease) = guarded {
            check(current === lease && held && lock?.isValid == true) { FAILURE }
            val base = checkNotNull(root)
            checkPrivate(base, true)
            checkPrivate(lease.directory, true)
            check(read(lease.directory.resolve("claim.event")).contentEquals(lease.record)) { FAILURE }
            publish(lease.directory, "released", lease.record, Mutation.RELEASE)
            fault(Mutation.RELEASE, WritePoint.BEFORE_UNLOCK)
            lock!!.release()
            fault(Mutation.RELEASE, WritePoint.BEFORE_CLOSE)
            channel!!.close()
            held = false
            processGate.release()
        }

        private fun inspectHistory(base: Path) {
            Files.newDirectoryStream(base).use { entries ->
                for (path in entries) {
                    if (path.fileName.toString() == "lease.lock") continue
                    check(GENERATION_DIR.matches(path.fileName.toString())) { FAILURE }
                    checkPrivate(path, true)
                    val names = Files.newDirectoryStream(path).use { children ->
                        children.map { it.fileName.toString() }.toSet()
                    }
                    // Pending, partial creation, active claim, corrupt or unknown entries block all
                    // generations after a restart. Never remove evidence to make progress.
                    check(names == setOf("journal", "claim.event", "released.event")) { FAILURE }
                    checkPrivate(path.resolve("journal"), true)
                    val claim = read(path.resolve("claim.event"))
                    check(validRecord(claim, path.fileName.toString().removePrefix("generation-")) &&
                        claim.contentEquals(read(path.resolve("released.event")))) { FAILURE }
                }
            }
        }

        private fun publish(directory: Path, name: String, bytes: ByteArray, mutation: Mutation) {
            val pending = directory.resolve("$name.pending")
            val target = directory.resolve("$name.event")
            check(!Files.exists(target, NOFOLLOW_LINKS) && !Files.exists(pending, NOFOLLOW_LINKS)) { FAILURE }
            fs.openNew(pending, FILE_MODE).use {
                sync(directory, mutation) // durable evidence exists even if the subsequent write is interrupted
                fault(mutation, WritePoint.PENDING_CREATED)
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) it.write(buffer)
                force(it, mutation)
                fault(mutation, WritePoint.FILE_SYNCED)
            }
            fault(mutation, WritePoint.BEFORE_ATOMIC_MOVE)
            fs.move(pending, target) // same directory; NEVER replace/nonatomic fallback
            fault(mutation, WritePoint.PUBLISHED)
            sync(directory, mutation)
            fault(mutation, WritePoint.DIRECTORY_SYNCED)
        }
        private fun force(channel: FileChannel, mutation: Mutation) {
            fault(mutation, WritePoint.BEFORE_FILE_FORCE)
            channel.force(true)
        }
        private fun sync(path: Path, mutation: Mutation) {
            fault(mutation, WritePoint.BEFORE_DIRECTORY_FORCE)
            fs.syncDirectory(path)
        }
        private fun read(path: Path): ByteArray {
            checkPrivate(path, false)
            return fs.openExisting(path, READ, NOFOLLOW_LINKS).use {
                check(it.size() in 1L..512L) { FAILURE }
                val bytes = ByteBuffer.allocate(it.size().toInt())
                while (bytes.hasRemaining()) check(it.read(bytes) > 0) { FAILURE }
                check(it.read(ByteBuffer.allocate(1)) == -1) { FAILURE }
                bytes.array()
            }
        }
        private fun checkPrivate(path: Path, directory: Boolean) {
            checkAncestors(path.parent, fs)
            check(!fs.isSymbolicLink(path) &&
                (if (directory) Files.isDirectory(path, NOFOLLOW_LINKS) else Files.isRegularFile(path, NOFOLLOW_LINKS)) &&
                Files.getOwner(path, NOFOLLOW_LINKS) == owner &&
                fs.permissions(path) == (if (directory) DIR_MODE else FILE_MODE)) { FAILURE }
        }
        override fun toString() = "R08LeaseStore(private)"
    }

    internal class DurableLease internal constructor(private val store: LeaseStore, internal val directory: Path,
        internal val record: ByteArray, internal val plan: R08VirtualDisplayPlan,
        override val journal: R08PersistentJournal) : ControllerVirtualDisplaySession.Lease {
        override val generation get() = plan.generation
        // The store admits this identity only AFTER journal construction, file fsync, atomic
        // publish and directory fsync. Also require the live exclusive lock; never a supplied flag.
        override val durable get() = store.owns(this)
        private var releaseAttempted = false
        @Synchronized override fun releaseAfterDrain() {
            check(!releaseAttempted) { FAILURE }
            releaseAttempted = true
            store.release(this)
        }
        override fun toString() = "R08DurableLease(private)"
    }

    private val productionGate = Semaphore(1)
    private val DIR_MODE = PosixFilePermissions.fromString("rwx------")
    private val FILE_MODE = PosixFilePermissions.fromString("rw-------")
    private val GENERATION_DIR = Regex("generation-[a-f0-9]{32}")
    private const val FAILURE = "virtual_display_runtime_unknown"
    private fun <T> guarded(block: () -> T): T = try { block() } catch (e: Exception) {
        if (e is InterruptedException) Thread.currentThread().interrupt()
        throw IOException(FAILURE) // no path, owner identity, response, credential, or chained cause
    }
    private fun checkAncestors(path: Path, fs: R08PrivateFileSystem) {
        var next: Path? = path
        while (next != null) {
            check(!fs.isSymbolicLink(next) && Files.isDirectory(next, NOFOLLOW_LINKS)) { FAILURE }
            next = next.parent
        }
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun record(plan: R08VirtualDisplayPlan): ByteArray {
        val steps = (plan.writes + plan.reads).joinToString("\n") {
            "${it.id.length}:${it.id}${it.operation.length}:${it.operation}${it.canonicalParameters.length}:${it.canonicalParameters}"
        }
        val prefix = "r08-controller-lease-v1\n${plan.generation}\n${plan.bindingKey}\n${hash(steps.toByteArray(Charsets.UTF_8))}\n"
        return (prefix + hash(prefix.toByteArray(Charsets.UTF_8)) + "\n").toByteArray(Charsets.UTF_8)
    }
    private fun validRecord(bytes: ByteArray, generation: String): Boolean {
        val fields = bytes.toString(Charsets.UTF_8).split('\n')
        return fields.size == 6 && fields[0] == "r08-controller-lease-v1" && fields[1] == generation &&
            fields.slice(2..4).all { Regex("[a-f0-9]{64}").matches(it) } && fields[5].isEmpty() &&
            fields[4] == hash((fields.take(4).joinToString("\n") + "\n").toByteArray(Charsets.UTF_8))
    }
}
