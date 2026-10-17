package app.daiyu.android.browser.download

import app.daiyu.android.browser.session.BrowserSessionRegistry
import app.daiyu.android.browser.session.BrowserTabLease
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID

class BrowserDownloadTicket internal constructor(val id: String, val lease: BrowserTabLease)
data class BrowserDownloadReceipt(val path: Path, val size: Long, val sha256: String)

/** privateRoot must be app-private and not writable/renameable by pages or shared-storage users. */
class BrowserDownloadCoordinator internal constructor(
    private val sessions: BrowserSessionRegistry,
    private val paths: BrowserPathMapper,
    private val maxBytes: Long,
    private val maxPending: Int,
    private val createStaging: (Path) -> Unit,
) {
    constructor(
        sessions: BrowserSessionRegistry,
        paths: BrowserPathMapper,
        maxBytes: Long = 16L * 1024 * 1024,
        maxPending: Int = 12,
    ) : this(sessions, paths, maxBytes, maxPending, { Files.createFile(it); Unit })
    private enum class State { RESERVED, WRITING, FAILED, CANCELLED }
    private data class Entry(
        val ticket: BrowserDownloadTicket, val staging: Path, val target: Path,
        var state: State = State.RESERVED, var input: InputStream? = null, var activeWriter: Boolean = false,
        var targetOwned: Boolean = false, var invalidation: AutoCloseable? = null,
        var activeReservation: Boolean = true,
    )
    private val lock = Any()
    private val entries = mutableMapOf<String, Entry>()
    private data class Completed(val reference: BrowserCompletedDownload, val receipt: BrowserDownloadReceipt)
    private val completed = linkedMapOf<String, Completed>()
    private var completionObserver: (BrowserCompletedDownload) -> Unit = {}
    private var observerInstalled = false
    init { require(maxBytes > 0 && maxPending > 0) }

    /** Trusted host wiring only, before the first reservation; never a tool argument. */
    fun observeCompleted(observer: (BrowserCompletedDownload) -> Unit) = synchronized(lock) {
        check(!observerInstalled && entries.isEmpty() && completed.isEmpty())
        observerInstalled = true
        completionObserver = observer
    }

    fun reserve(lease: BrowserTabLease): BrowserDownloadTicket {
        sessions.assertAllowed(lease, "fetch")
        val id = UUID.randomUUID().toString()
        val ticket = BrowserDownloadTicket(id, lease)
        val entry = Entry(ticket, paths.privateFile("$id.part"), paths.privateFile("$id.download"))
        sessions.publish(lease) { synchronized(lock) {
            check(entries.size < maxPending) { "BROWSER_DOWNLOAD_LIMIT" }
            entries[id] = entry
        } }
        try {
            createStaging(entry.staging)
            val registration = sessions.onInvalidated(lease) { cancel(ticket) }
            synchronized(lock) { entry.invalidation = registration }
            return sessions.publish(lease) { synchronized(lock) {
                check(entry.state == State.RESERVED) { "BROWSER_DOWNLOAD_CANCELLED" }
                entry.activeReservation = false
                ticket
            } }
        } catch (e: Exception) {
            synchronized(lock) {
                entry.activeReservation = false
                if (entry.state != State.CANCELLED) entry.state = State.FAILED
            }
            cleanup(entry, e)
            throw e
        }
    }

    /** The stream must support prompt close/abort and the network adapter must set I/O timeouts. */
    fun write(ticket: BrowserDownloadTicket, input: InputStream): BrowserDownloadReceipt {
        val entry = try {
            synchronized(lock) {
                val entry = entry(ticket)
                check(entry.state == State.RESERVED) { "BROWSER_DOWNLOAD_NOT_RESERVED" }
                entry.input = input
                entry.state = State.WRITING
                entry.activeWriter = true
                entry
            }
        } catch (e: Exception) {
            try { input.close() } catch (close: Exception) { e.addSuppressed(close) }
            throw e
        }
        try {
            sessions.assertAllowed(ticket.lease, "fetch")
            paths.recheckPrivate(entry.staging)
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            Files.newOutputStream(entry.staging, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    checkWriting(entry)
                    val size = input.read(buffer)
                    checkWriting(entry)
                    if (size == -1) break
                    check(size > 0) { "BROWSER_DOWNLOAD_NO_PROGRESS" }
                    check(count <= maxBytes - size) { "BROWSER_DOWNLOAD_TOO_LARGE" }
                    output.write(buffer, 0, size)
                    digest.update(buffer, 0, size)
                    count += size
                }
            }
            input.close()
            synchronized(lock) { entry.input = null }
            check(count > 0 && Files.isRegularFile(entry.staging, LinkOption.NOFOLLOW_LINKS)) { "BROWSER_DOWNLOAD_EMPTY" }
            check(Files.size(entry.staging) == count) { "BROWSER_DOWNLOAD_SIZE" }
            paths.recheckPrivate(entry.staging)
            paths.recheckPrivate(entry.target)
            checkWriting(entry)
            Files.move(entry.staging, entry.target)
            entry.targetOwned = true
            val receipt = BrowserDownloadReceipt(entry.target, count, digest.digest().joinToString("") { "%02x".format(it) })
            sessions.publish(ticket.lease) { synchronized(lock) {
                check(entry.state == State.WRITING) { "BROWSER_DOWNLOAD_CANCELLED" }
                val reference = BrowserCompletedDownload(ticket.id, ticket.lease, receipt.size, receipt.sha256, this)
                completed[ticket.id] = Completed(reference, receipt)
                completionObserver(reference)
                while (completed.size > 128) completed.remove(completed.keys.first())
                entry.activeWriter = false
                entries.remove(ticket.id)
            } }
            entry.invalidation?.close()
            return receipt
        } catch (e: Exception) {
            synchronized(lock) { completed.remove(ticket.id); if (entry.state != State.CANCELLED) entry.state = State.FAILED; entry.input = null; entry.activeWriter = false }
            try { input.close() } catch (close: Exception) { e.addSuppressed(close) }
            cleanup(entry, e)
            throw e
        }
    }

    /** Two bounded passes: validate BEFORE opening/truncating a destination, then copy exact
     * original bytes with another digest. Destination open/write/flush/close or owner failure
     * always propagates; a provider may retain a partial document, never a success receipt.
     * Completion identity pins the original lease; no caller-supplied path is accepted.
     */
    internal fun saveCompleted(reference: BrowserCompletedDownload,
        openDestination: () -> OutputStream, assertOwner: () -> Unit) {
        val record = synchronized(lock) {
            val record = completed[reference.id] ?: error("BROWSER_SAVE_REFERENCE_EXPIRED")
            check(record.reference === reference) { "BROWSER_SAVE_REFERENCE" }
            record
        }
        fun checkOwner() {
            assertOwner()
            synchronized(lock) { check(completed[reference.id] === record) { "BROWSER_SAVE_REFERENCE_EXPIRED" } }
        }
        fun transfer(output: OutputStream?) {
            checkOwner()
            val path = paths.recheckPrivate(record.receipt.path)
            check(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                Files.size(path) == reference.size && reference.size in 1..maxBytes) { "BROWSER_SAVE_SIZE" }
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    checkOwner()
                    val size = input.read(buffer)
                    checkOwner()
                    if (size == -1) break
                    check(size > 0 && count <= reference.size - size) { "BROWSER_SAVE_SIZE" }
                    output?.write(buffer, 0, size)
                    digest.update(buffer, 0, size)
                    count += size
                }
            }
            check(count == reference.size && digest.digest().joinToString("") { "%02x".format(it) } == reference.sha256) {
                "BROWSER_SAVE_DIGEST"
            }
            checkOwner()
        }
        transfer(null)
        checkOwner()
        openDestination().use { output ->
            checkOwner()
            transfer(output)
            output.flush()
            checkOwner()
        }
        checkOwner()
    }

    fun cancel(ticket: BrowserDownloadTicket) {
        val (entry, input, writing) = synchronized(lock) {
            val entry = entries[ticket.id] ?: return
            require(entry.ticket === ticket) { "BROWSER_DOWNLOAD_TICKET" }
            val writing = entry.activeWriter || entry.activeReservation
            entry.state = State.CANCELLED
            Triple(entry, entry.input, writing)
        }
        var failure: Exception? = null
        try { input?.close() } catch (e: Exception) { failure = e }
        // An initializer or writer retains its record until its own cleanup has finished.
        if (!writing) cleanup(entry, failure)
        failure?.let { throw it }
    }

    fun cancelLease(lease: BrowserTabLease) {
        val tickets = synchronized(lock) { entries.values.filter { it.ticket.lease == lease }.map { it.ticket } }
        var failure: Exception? = null
        tickets.forEach { ticket ->
            try { cancel(ticket) } catch (e: Exception) {
                if (failure == null) failure = e else failure.addSuppressed(e)
            }
        }
        failure?.let { throw it }
    }
    fun retryCleanup(ticket: BrowserDownloadTicket) {
        val entry = synchronized(lock) { entry(ticket).also { check(it.state in setOf(State.FAILED, State.CANCELLED) && it.input == null && !it.activeWriter && !it.activeReservation) } }
        cleanup(entry, null)
    }
    private fun checkWriting(entry: Entry) {
        sessions.assertCurrent(entry.ticket.lease)
        synchronized(lock) { check(entry.state == State.WRITING) { "BROWSER_DOWNLOAD_CANCELLED" } }
    }
    private fun entry(ticket: BrowserDownloadTicket): Entry =
        (entries[ticket.id] ?: error("BROWSER_DOWNLOAD_TICKET")).also { require(it.ticket === ticket) { "BROWSER_DOWNLOAD_TICKET" } }
    private fun cleanup(entry: Entry, primary: Exception?) {
        var failure = primary
        var cleaned = true
        for (path in listOfNotNull(entry.staging, entry.target.takeIf { entry.targetOwned })) {
            try { Files.deleteIfExists(paths.recheckPrivate(path)) } catch (e: Exception) {
                cleaned = false
                if (failure == null) failure = e else failure.addSuppressed(e)
            }
        }
        if (cleaned) {
            synchronized(lock) { entries.remove(entry.ticket.id) }
            entry.invalidation?.close()
        }
        if (primary == null) failure?.let { throw it }
    }
}
