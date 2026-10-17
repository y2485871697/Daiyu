package app.daiyu.android.media

import app.daiyu.android.backup.StaticGenerationCodec
import app.daiyu.android.media.video.AndroidVideoInspector
import app.daiyu.android.media.video.VideoAttachmentLimits
import app.daiyu.android.media.video.VideoInspector
import app.daiyu.android.media.video.validateVideo
import app.daiyu.android.model.generation.GenerationKind
import app.daiyu.android.model.generation.GenerationRequestPlan
import app.daiyu.android.storage.StaticGenerationOutput
import app.daiyu.android.storage.StaticGenerationProvenance
import app.daiyu.android.storage.staticDigest
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/** Host-internal read capability: resolve ONLY via the existing owner-fenced generation reader,
 * backup historical member reader, or static registry+openVerified. No path/URL/receipt resolver
 * for UI or tools. The asset store also checks bounded EOF, digest and real media independently.
 * Returned streams belong to this operation and are closed here, including on failure.
 */
internal fun interface StaticGenerationOriginalReader {
    fun open(source: StaticGenerationProvenance): InputStream
}

internal enum class StaticGenerationSettlement {
    COMMITTED,
    /** Host has confirmed rollback AND exact destination has no reference, under its acceptance
     * lock, AFTER SQL is fully released. A thrown endTransaction is NOT this result. */
    ROLLED_BACK_UNREFERENCED,
    UNKNOWN,
}

/** Includes a refresh locator, not an invitation to repeat a branch/tool request. */
internal class StaticGenerationPublicationUnknown(val identity: String) :
    RuntimeException("Static publication outcome UNKNOWN; retain destination and refresh")

/** Destination-only private copies. NOT GenerationAssets: no commit/register/reserve/live receipt.
 * All methods run on a worker, OUTSIDE SQLite/controller monitors. Host keeps its existing account
 * acceptance/deletion lock across capture -> prepare/publish -> T2 -> settle; T2 itself does no
 * file IO. There is no filesystem/SQL cross-media atomicity or crash orphan-GC claim.
 *
 * A fixed <slot>.lease reservation, created with CREATE_NEW under the namespace's process/file
 * lock, excludes another preparer until settlement. It is never stolen after a crash/UNKNOWN.
 * Only this lease's fixed staging/copy may be removed on confirmed unreferenced rollback. A
 * previously published copy is NEVER owned for deletion. Shared/source blobs are never touched.
 * Same-UID hostile path replacement is outside the media store threat model (as in PrivateBlobs
 * and MessageGenerationAssets); NOFOLLOW and ancestor checks are repeated on every access.
 */
internal class StaticGenerationAssets internal constructor(
    root: File,
    private val imageInspector: ImageInspector,
    private val videoInspector: VideoInspector,
    private val imageLimits: ImageAttachmentLimits,
    private val videoLimits: VideoAttachmentLimits,
    private val afterAtomicMove: () -> Unit = {},
    private val directoryForce: ((File) -> Unit)? = null, // fault injection; never replaces actual rename
) {
    constructor(root: File) : this(root, AndroidImageInspector(), AndroidVideoInspector(),
        ImageAttachmentLimits(maxImageBytes = GenerationRequestPlan.MAX_IMAGE_BYTES),
        VideoAttachmentLimits(maxVideoBytes = GenerationRequestPlan.MAX_VIDEO_BYTES.toLong()))

    private val root = initializeMediaRoot(File(initializeMediaRoot(root), NAMESPACE))
    private val ownerToken = Any()
    private val active = HashSet<Lease>()

    internal class Lease internal constructor(
        internal val ownerToken: Any,
        val output: StaticGenerationOutput,
        internal val reservation: ByteArray,
        internal val reused: Boolean,
        internal val isCancelled: () -> Boolean,
    ) {
        internal var state = State.PREPARED
        override fun toString() = "StaticGenerationLease(state=$state, payload=<redacted>)"
    }
    internal enum class State { PREPARED, PUBLISHED, UNKNOWN, SETTLED }

    /** Parent must validateBatch/reserve its COMPLETE prefix budget before the first call. Per
     * item limits are checked before opening the reader or allocating an original-sized array.
     */
    fun prepare(output: StaticGenerationOutput, reader: StaticGenerationOriginalReader,
        isCancelled: () -> Boolean = { false }): Lease {
        checkCancelled(isCancelled)
        checkSize(output.source)
        // Safe source IO happens before destination locks; never call a source reader under them.
        val bytes = reader.open(output.source).use { readBounded(it, output.source.bytes.toInt(), isCancelled) }
        require(staticDigest(bytes) == output.source.sha256) { "Static original digest mismatch" }
        return locked {
            checkCancelled(isCancelled)
            val slot = slot(output)
            val reservation = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val marker = marker(slot)
            // An abandoned/unknown reservation is evidence, not a stale lock to erase or reuse.
            writeNew(marker, reservation)
            var ownsStage = false
            try {
                val published = directory(slot)
                val reused = exists(published)
                if (reused) {
                    require(readRecord(published) == output) { "Static destination slot conflict" }
                    verifyFile(published, output, isCancelled).close()
                } else {
                    val stage = directory(slot, ".pending")
                    check(!exists(stage)) { "Unknown static staging retained" }
                    check(stage.mkdir())
                    ownsStage = true
                    writeNew(child(stage, ORIGINAL), bytes)
                    writeNew(child(stage, RECORD), StaticGenerationCodec.encode(output).toByteArray(Charsets.UTF_8))
                    inspect(stage, output.source, bytes, isCancelled)
                    syncDirectory(stage)
                }
                syncDirectory(root)
                checkCancelled(isCancelled)
                Lease(ownerToken, output, reservation, reused, isCancelled).also { active.add(it) }
            } catch (failure: Exception) {
                val cleaned = !ownsStage || cleanupKnown(directory(slot, ".pending"))
                if (cleaned) removeReservation(slot, reservation)
                throw failure
            }
        }
    }

    /** Publish before T2. No cancellation/cleanup after admitted rename. A rename/fsync failure
     * is UNKNOWN even if the original exception looked like a normal IO failure. Never retry it.
     */
    fun publish(lease: Lease): StaticGenerationOutput = locked {
        requireActive(lease)
        check(lease.state == State.PREPARED)
        checkReservation(lease)
        checkCancelled(lease.isCancelled)
        val slot = slot(lease.output)
        if (lease.reused) {
            require(readRecord(directory(slot)) == lease.output)
            verifyFile(directory(slot), lease.output, lease.isCancelled).close()
            lease.state = State.PUBLISHED
        } else {
            val stage = directory(slot, ".pending")
            check(!exists(directory(slot))) { "Static publication conflict" }
            require(readRecord(stage) == lease.output)
            verifyFile(stage, lease.output, lease.isCancelled).close()
            checkCancelled(lease.isCancelled)
            lease.state = State.UNKNOWN
            try {
                Files.move(stage.toPath(), directory(slot).toPath(), ATOMIC_MOVE)
                afterAtomicMove()
                syncDirectory(root)
                lease.state = State.PUBLISHED
            } catch (_: Exception) {
                throw StaticGenerationPublicationUnknown(lease.output.identity)
            }
        }
        lease.output
    }

    /** SQL must already be released. UNKNOWN is terminal for this lease: retain reservation,
     * staging and any published copy even if a caller subsequently claims a rollback. Committed
     * success also wins over cancellation; settlement never consults cancellation callbacks.
     * Best-effort cleanup failures retain evidence, never broaden deletion to unknown files.
     */
    fun settle(lease: Lease, result: StaticGenerationSettlement): Unit = locked {
        requireActive(lease)
        if (lease.state == State.UNKNOWN || result == StaticGenerationSettlement.UNKNOWN) {
            lease.state = State.UNKNOWN
            active.remove(lease)
            return@locked
        }
        if (result == StaticGenerationSettlement.COMMITTED) check(lease.state == State.PUBLISHED)
        val slot = slot(lease.output)
        try {
            checkReservation(lease)
            if (result == StaticGenerationSettlement.ROLLED_BACK_UNREFERENCED && !lease.reused) {
                val owned = if (lease.state == State.PREPARED) directory(slot, ".pending") else directory(slot)
                // Exact record/known filenames; never delete a mutated or foreign directory.
                require(readRecord(owned) == lease.output)
                if (!cleanupKnown(owned)) return@locked retainUnknown(lease)
                syncDirectory(root)
            }
            if (!removeReservation(slot, lease.reservation)) return@locked retainUnknown(lease)
            syncDirectory(root)
            lease.state = State.SETTLED
            active.remove(lease)
        } catch (_: Exception) { retainUnknown(lease) }
        Unit
    }

    /** Lookup must use the real account's readonly registry and compare EVERY scalar. It runs
     * before file locks, never from an outer SQL transaction. Parent acceptance lock stabilizes
     * owner membership until this operation ends. Stream is read-only, verified and rewound;
     * no path/preview leaks out and later source deletion cannot affect these private bytes.
     */
    fun openVerified(expected: StaticGenerationOutput,
        lookup: (String) -> StaticGenerationOutput?,
        isCancelled: () -> Boolean = { false }): InputStream {
        checkCancelled(isCancelled)
        require(lookup(expected.identity) == expected) { "Static registry membership mismatch" }
        return locked {
            val directory = directory(slot(expected))
            require(readRecord(directory) == expected) { "Static mapping mismatch" }
            verifyFile(directory, expected, isCancelled)
        }
    }

    private fun retainUnknown(lease: Lease) {
        lease.state = State.UNKNOWN
        active.remove(lease)
    }
    private fun requireActive(lease: Lease) {
        require(lease.ownerToken === ownerToken && active.contains(lease)) { "Foreign or settled static lease" }
    }
    private fun slot(output: StaticGenerationOutput) = output.identity.removePrefix("genstatic-v1-")
    private fun directory(slot: String, suffix: String = "") = mediaDirectory(root, slot + suffix)
    private fun child(parent: File, name: String) = File(parent, name).also { checkMediaPath(it) }
    private fun marker(slot: String): File {
        require(slot.matches(Regex("[0-9a-f]{64}")))
        return child(root, "$slot.lease")
    }
    private fun exists(file: File) = Files.exists(file.toPath(), NOFOLLOW_LINKS)

    private fun checkReservation(lease: Lease) {
        val bytes = readSmall(marker(slot(lease.output)), 32)
        require(bytes.contentEquals(lease.reservation)) { "Static lease reservation changed" }
    }
    private fun removeReservation(slot: String, reservation: ByteArray): Boolean = try {
        val file = marker(slot)
        if (!readSmall(file, 32).contentEquals(reservation)) false else Files.deleteIfExists(file.toPath())
    } catch (_: Exception) { false }

    private fun readRecord(directory: File): StaticGenerationOutput {
        checkMediaPath(directory)
        require(Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS))
        require(directory.list()?.toSet() == setOf(ORIGINAL, RECORD)) { "Unknown static directory contents" }
        val raw = readSmall(child(directory, RECORD), StaticGenerationCodec.MAX_RECORD_BYTES)
        val text = raw.toString(Charsets.UTF_8)
        require(text.toByteArray(Charsets.UTF_8).contentEquals(raw)) { "Invalid static UTF-8" }
        return StaticGenerationCodec.decode(text)
    }

    private fun checkSize(s: StaticGenerationProvenance) {
        val max = if (s.kind == GenerationKind.IMAGE) imageLimits.maxImageBytes.toLong() else videoLimits.maxVideoBytes
        require(s.bytes in 1..minOf(max, Int.MAX_VALUE.toLong()))
    }

    private fun verifyFile(directory: File, output: StaticGenerationOutput, cancel: () -> Boolean): InputStream {
        val s = output.source
        checkSize(s)
        val file = child(directory, ORIGINAL)
        require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS))
        val channel = FileChannel.open(file.toPath(), READ, NOFOLLOW_LINKS)
        val stream = Channels.newInputStream(channel)
        try {
            require(channel.size() == s.bytes)
            val bytes = readBounded(stream, s.bytes.toInt(), cancel)
            require(staticDigest(bytes) == s.sha256) { "Static original damaged" }
            inspect(directory, s, bytes, cancel)
            channel.position(0)
            return stream
        } catch (failure: Exception) { stream.close(); throw failure }
    }

    private fun inspect(directory: File, s: StaticGenerationProvenance, bytes: ByteArray, cancel: () -> Boolean) {
        checkCancelled(cancel)
        when (s.kind) {
            GenerationKind.IMAGE -> {
                val info = imageInspector.inspect(bytes)
                validateImageDimensions(info, imageLimits)
                require(info.mime == s.mimeType && info.width == s.width && info.height == s.height && s.durationMs == 0L)
            }
            GenerationKind.VIDEO -> {
                // Original already bounded/hash-verified; real track/sample inspector, not a preview.
                val info = videoInspector.inspect(child(directory, ORIGINAL), videoLimits) {
                    cancel() || Thread.currentThread().isInterrupted
                }
                validateVideo(info, videoLimits)
                require(info.mime == s.mimeType && info.width == s.width && info.height == s.height && info.durationMs == s.durationMs)
            }
        }
        checkCancelled(cancel)
    }

    private fun readBounded(stream: InputStream, count: Int, cancel: () -> Boolean): ByteArray {
        val bytes = ByteArray(count)
        var offset = 0
        while (offset < count) {
            checkCancelled(cancel)
            val n = stream.read(bytes, offset, minOf(16 * 1024, count - offset))
            require(n > 0) { "Truncated static original" }
            offset += n
        }
        checkCancelled(cancel)
        require(stream.read() == -1) { "Trailing static original bytes" }
        return bytes
    }
    private fun readSmall(file: File, max: Int): ByteArray {
        checkMediaPath(file)
        require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS))
        return FileChannel.open(file.toPath(), READ, NOFOLLOW_LINKS).use { channel ->
            require(channel.size() in 1..max.toLong())
            readBounded(Channels.newInputStream(channel), channel.size().toInt()) { false }
        }
    }
    private fun writeNew(file: File, bytes: ByteArray) {
        checkMediaPath(file)
        FileChannel.open(file.toPath(), CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }
    private fun syncDirectory(directory: File) {
        checkMediaPath(directory)
        require(Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS))
        val force = directoryForce
        if (force != null) force(directory)
        else FileChannel.open(directory.toPath(), READ, NOFOLLOW_LINKS).use { it.force(true) }
    }
    private fun cleanupKnown(directory: File): Boolean = try {
        checkMediaPath(directory)
        if (!Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS) ||
            directory.list()?.any { it !in setOf(ORIGINAL, RECORD) } != false) false
        else {
            listOf(ORIGINAL, RECORD).forEach { name ->
                val file = child(directory, name)
                if (exists(file)) {
                    require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS))
                    Files.delete(file.toPath())
                }
            }
            Files.delete(directory.toPath())
            true
        }
    } catch (_: Exception) { false }

    private fun checkCancelled(cancel: () -> Boolean) {
        check(!cancel() && !Thread.currentThread().isInterrupted) { "Static copy cancelled" }
    }
    private fun <T> locked(action: () -> T): T = synchronized(ROOT_LOCKS.computeIfAbsent(root.absolutePath) { Any() }) {
        checkMediaPath(root)
        require(Files.isDirectory(root.toPath(), NOFOLLOW_LINKS))
        val file = child(root, ".lock")
        if (exists(file)) require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS))
        FileChannel.open(file.toPath(), CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
            channel.lock().use { action() }
        }
    }
    private companion object {
        const val NAMESPACE = "generation-static-v1"
        const val ORIGINAL = "original"
        const val RECORD = "record-v1"
        val ROOT_LOCKS = ConcurrentHashMap<String, Any>()
    }
}
