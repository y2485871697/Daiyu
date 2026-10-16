package app.daiyu.android.media

import app.daiyu.android.media.video.AndroidVideoInspector
import app.daiyu.android.media.video.VideoAttachmentLimits
import app.daiyu.android.media.video.VideoInspector
import app.daiyu.android.media.video.validateVideo
import app.daiyu.android.model.generation.GenerationAssetReceipt
import app.daiyu.android.model.generation.GenerationAssets
import app.daiyu.android.model.generation.GenerationIdentity
import app.daiyu.android.model.generation.GenerationKind
import app.daiyu.android.model.generation.GenerationMediaInfo
import app.daiyu.android.model.generation.GenerationRequestPlan
import app.daiyu.android.storage.GenerationAttemptBinding
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** The account scope is a stable local account/DB identifier, NEVER a URL or a credential.
 * binding must describe the real persisted assistant invocation, just as for the attempt ledger.
 */
class GenerationAssetOwner(val accountScope: String, val binding: GenerationAttemptBinding) {
    init { require(accountScope.isNotBlank() && accountScope.length <= 200) }
    internal fun fields() = listOf(accountScope, binding.conversationId, binding.assistantMessageId,
        binding.runId, binding.toolCallId, binding.toolName)
    override fun toString() = "GenerationAssetOwner(<redacted>)"
}

/** Parent-owned persisted-owner publication barrier, shared by writers AND history readers.
 * Never keep one in a global registry. The actual account owner retains it by stable run scope.
 * Seal is pure in-memory admission closure; an already admitted operation may still finish.
 * BEFORE deleting/changing the real owner, the parent must also await revokeOnWorker(), outside
 * UI/controller locks and BEFORE opening any DB transaction (ordering is gate -> DB, never DB -> gate).
 * Revocation is terminal. Recovery of the same live owner uses this same gate, not a fresh one.
 */
class GenerationAssetPublicationGate {
    private val monitor = Any()
    private val admissionSealed = AtomicBoolean(false)

    fun sealAdmission() { admissionSealed.set(true) }

    /** Physical publication barrier: on return no admitted rename/SQL can remain in this gate. */
    fun revokeOnWorker() {
        sealAdmission()
        synchronized(monitor) { Unit }
    }

    internal fun revokeLocal(action: () -> Unit) { synchronized(monitor) { action() } }

    internal fun checkAdmission() {
        if (admissionSealed.get() || Thread.currentThread().isInterrupted) reject(GenerationAssetFailure.CANCELLED)
    }

    internal fun <T> admit(action: () -> T): T = synchronized(monitor) {
        checkAdmission()
        action()
    }
}

/** Per-execution/reader cancellation layered OVER the persisted owner's shared publication gate.
 * Closing this fence never revokes another fence or accepted history. validateOwner must validate
 * the real account/run/tool owner; it and final publication run under publicationGate's monitor.
 * The two-argument constructor intentionally preserves the legacy, independent-gate behavior.
 */
class GenerationAssetFence(
    val owner: GenerationAssetOwner,
    val publicationGate: GenerationAssetPublicationGate,
    private val validateOwner: (GenerationAssetOwner) -> Boolean,
) {
    constructor(owner: GenerationAssetOwner, validateOwner: (GenerationAssetOwner) -> Boolean) :
        this(owner, GenerationAssetPublicationGate(), validateOwner)

    private val revoked = AtomicBoolean(false)

    /** Nonblocking local cancellation. Does not close the persisted owner's publication gate. */
    fun revokeAdmission() { revoked.set(true) }

    /** Worker-only local barrier. An operation already admitted wins unless the caller first
     * used revokeAdmission(). Never seals the shared gate or changes other fences' admission.
     */
    fun revoke() {
        publicationGate.revokeLocal { revoked.set(true) }
    }

    internal fun <T> admit(action: () -> T): T = publicationGate.admit {
        checkLocalAdmission()
        val valid = try { validateOwner(owner) } catch (_: Exception) { false }
        if (!valid) reject(GenerationAssetFailure.OWNER_REJECTED)
        publicationGate.checkAdmission()
        checkLocalAdmission()
        action()
    }

    private fun checkLocalAdmission() {
        if (revoked.get() || Thread.currentThread().isInterrupted) reject(GenerationAssetFailure.CANCELLED)
    }
}

enum class GenerationAssetFailure { CANCELLED, OWNER_REJECTED, IDENTITY_CONFLICT, INVALID_MEDIA, CORRUPT, STORAGE }
class GenerationAssetException(val failure: GenerationAssetFailure) :
    RuntimeException("Generation asset: ${failure.name}")

/** FILE publication only, always awaiting the parent's same-DB reference/receipt transaction.
 * Not a MessageBlock, not a claim that a message/asset database row exists. No provider URLs.
 */
data class PendingGenerationAsset internal constructor(
    val receipt: GenerationAssetReceipt,
    val owner: GenerationAssetOwner,
    val identity: GenerationIdentity,
    val outputIndex: Int,
    val kind: GenerationKind,
    val mimeType: String,
    val bytes: Long,
    val sha256: String,
    val width: Int,
    val height: Int,
    val durationMs: Long,
) {
    override fun toString() = "PendingGenerationAsset(kind=$kind, registration=PENDING, payload=<redacted>)"
}

/** Incremental generation namespace; NEVER calls/changes the complete-batch image prepare API.
 *
 * One immutable directory per owner/operation/item/output index, containing original + mapping.
 * The receipt also binds the original digest. Same slot with different content is a conflict,
 * not an overwrite or a second asset. Independent indices can arrive in any order. A file lock
 * serializes helpers/processes; the parent's fence supplies live owner/cancellation admission.
 * Files are fsynced and the directory is atomically renamed. No filesystem+DB atomicity or
 * power-loss directory-fsync guarantee is claimed. No delete, age GC, retry POST or network API.
 */
class MessageGenerationAssets internal constructor(
    root: File,
    private val fence: GenerationAssetFence,
    private val imageInspector: ImageInspector,
    private val videoInspector: VideoInspector,
    private val imageLimits: ImageAttachmentLimits,
    private val videoLimits: VideoAttachmentLimits,
) : GenerationAssets {
    constructor(root: File, fence: GenerationAssetFence) : this(root, fence,
        AndroidImageInspector(), AndroidVideoInspector(),
        ImageAttachmentLimits(maxImageBytes = GenerationRequestPlan.MAX_IMAGE_BYTES),
        VideoAttachmentLimits(maxVideoBytes = GenerationRequestPlan.MAX_VIDEO_BYTES.toLong()))

    private val root = initializeMediaRoot(File(initializeMediaRoot(root), "generation-v1"))
    private val owner get() = fence.owner

    override fun inspect(kind: GenerationKind, bytes: ByteArray, declaredMime: String): GenerationMediaInfo = guarded {
        val snapshot = snapshot(kind, bytes)
        locked { inspectBytes(kind, snapshot, declaredMime).info }
    }

    override fun commit(identity: GenerationIdentity, outputIndex: Int, kind: GenerationKind,
        bytes: ByteArray, info: GenerationMediaInfo): GenerationAssetReceipt = guarded {
        validateKey(identity, outputIndex)
        fence.admit { Unit }
        val snapshot = snapshot(kind, bytes)
        locked {
            // Never trust a previous inspect result, caller-owned array, declared MIME or metadata.
            val actual = inspectBytes(kind, snapshot, info.mimeType)
            if (actual.info != info) reject(GenerationAssetFailure.INVALID_MEDIA)
            val expected = descriptor(identity, outputIndex, kind, snapshot.size.toLong(),
                imageDigest(snapshot), actual)
            val slot = slot(identity, outputIndex)
            val published = directory(slot)
            if (exists(published)) {
                return@locked fence.admit {
                    val existing = readRecord(slot)
                    if (!sameRecord(existing, expected)) reject(GenerationAssetFailure.IDENTITY_CONFLICT)
                    openVerified(existing).use { }
                    existing.receipt
                }
            }
            val pending = directory(slot, ".pending")
            // A crash may have left incomplete/unknown work. Never erase it on a failed retry.
            if (exists(pending)) reject(GenerationAssetFailure.STORAGE)
            if (!pending.mkdir()) reject(GenerationAssetFailure.STORAGE)
            var moveAttempted = false
            try {
                writeSynced(child(pending, ORIGINAL), snapshot)
                writeSynced(child(pending, RECORD), encode(expected))
                fence.admit {
                    // Admission and rename are one linearized operation relative to revoke().
                    moveAttempted = true
                    Files.move(pending.toPath(), published.toPath(), ATOMIC_MOVE)
                    expected.receipt
                }
                // NO post-publication cancellation check/cleanup: admitted success wins.
            } catch (failure: Exception) {
                if (!moveAttempted) cleanupOwnedStage(pending)
                // Even an ambiguous rename failure must retain possibly published assets.
                throw failure
            }
        }
    }

    /** Recover a known output after restart; verifies mapping AND original, not existence alone. */
    fun pending(identity: GenerationIdentity, outputIndex: Int): PendingGenerationAsset? = guarded {
        validateKey(identity, outputIndex)
        locked { fence.admit {
            val slot = slot(identity, outputIndex)
            if (!exists(directory(slot))) null
            else readRecord(slot).also { openVerified(it).use { } }
        } }
    }

    /**
     * Final output-store admission only. The caller has already verified files, outside SQLite.
     * Holds the same gate as publication/revoke until the SQL callback has COMMITTED or failed.
     * Never call from a DB transaction, or do asset/file IO in the callback: gate -> DB only.
     * Owner equality also matters for an absent slot, which has no Pending to compare.
     */
    internal fun <T> admitRegistration(expectedOwner: GenerationAssetOwner, sql: () -> T): T = guarded {
        if (owner.fields() != expectedOwner.fields()) reject(GenerationAssetFailure.OWNER_REJECTED)
        fence.admit(sql)
    }

    /** Lookup only in this real owner's namespace. Forged/foreign receipts fail closed. */
    fun pending(receipt: GenerationAssetReceipt): PendingGenerationAsset = guarded {
        locked { fence.admit { resolve(receipt).also { openVerified(it).use { } } } }
    }

    /** Caller closes this verified, rewound read-only ORIGINAL descriptor (also for video).
     * No path/URI is exported; no preview replaces the original. Revoke cannot recall an already
     * returned descriptor. Parent must keep it private and close it with the owning operation.
     */
    fun openOriginal(receipt: GenerationAssetReceipt): FileInputStream = guarded {
        locked { fence.admit { openVerified(resolve(receipt)) } }
    }

    private data class Actual(val info: GenerationMediaInfo, val durationMs: Long = 0)

    private fun inspectBytes(kind: GenerationKind, bytes: ByteArray, declared: String): Actual {
        val actual = try {
            when (kind) {
                GenerationKind.IMAGE -> imageInspector.inspect(bytes).let {
                    validateImageDimensions(it, imageLimits)
                    Actual(GenerationMediaInfo(it.mime, it.width, it.height))
                }
                GenerationKind.VIDEO -> {
                    // Reuse the existing real track/sample/frame inspector on an unmodified file.
                    val temporary = File.createTempFile("inspect-", ".pending", root)
                    try {
                        writeExistingSynced(temporary, bytes)
                        val value = videoInspector.inspect(temporary, videoLimits) { Thread.currentThread().isInterrupted }
                        validateVideo(value, videoLimits)
                        if (value.mime !in setOf("video/mp4", "video/webm")) reject(GenerationAssetFailure.INVALID_MEDIA)
                        Actual(GenerationMediaInfo(value.mime, value.width, value.height), value.durationMs)
                    } finally { temporary.delete() }
                }
            }
        } catch (failure: GenerationAssetException) { throw failure }
        catch (_: Exception) { reject(GenerationAssetFailure.INVALID_MEDIA) }
        val normalized = declared.substringBefore(';').trim().lowercase(java.util.Locale.ROOT)
        // Missing/octet-stream declarations are common downloads. They NEVER supply MIME evidence.
        if (normalized.isNotEmpty() && normalized != "application/octet-stream" && normalized != actual.info.mimeType)
            reject(GenerationAssetFailure.INVALID_MEDIA)
        return actual
    }

    private fun snapshot(kind: GenerationKind, bytes: ByteArray): ByteArray {
        val limit = if (kind == GenerationKind.IMAGE) imageLimits.maxImageBytes.toLong() else videoLimits.maxVideoBytes
        if (bytes.isEmpty() || bytes.size.toLong() > limit) reject(GenerationAssetFailure.INVALID_MEDIA)
        return bytes.copyOf()
    }

    private fun descriptor(identity: GenerationIdentity, index: Int, kind: GenerationKind,
        size: Long, digest: String, actual: Actual) = PendingGenerationAsset(
        GenerationAssetReceipt("genasset-v1-${slot(identity, index)}-$digest"), owner, identity, index,
        kind, actual.info.mimeType, size, digest, actual.info.width, actual.info.height, actual.durationMs)

    private fun validateKey(identity: GenerationIdentity, index: Int) {
        if (identity.operationId != owner.binding.operationId || !ITEM.matches(identity.itemId) || index !in 0..31)
            reject(GenerationAssetFailure.OWNER_REJECTED)
    }

    private fun slot(identity: GenerationIdentity, index: Int): String = imageDigest(packed {
        writeUTF("generation-slot-v1")
        // Like GenerationAttemptBinding, do not let a renamed tool call open a new slot.
        // The full record still binds/validates the original tool name on every read.
        owner.fields().dropLast(1).forEach { writeUTF(it) }
        writeUTF(identity.operationId); writeUTF(identity.itemId); writeInt(index)
    })

    private fun encode(value: PendingGenerationAsset): ByteArray = packed {
        writeInt(1)
        owner.fields().forEach { writeUTF(it) }
        writeUTF(value.identity.operationId); writeUTF(value.identity.itemId); writeInt(value.outputIndex)
        writeUTF(value.kind.name); writeUTF(value.mimeType); writeLong(value.bytes); writeUTF(value.sha256)
        writeInt(value.width); writeInt(value.height); writeLong(value.durationMs)
    }

    private fun readRecord(slot: String): PendingGenerationAsset {
        val directory = directory(slot)
        val names = directory.list()?.toSet() ?: reject(GenerationAssetFailure.STORAGE)
        if (names != setOf(RECORD, ORIGINAL)) reject(GenerationAssetFailure.CORRUPT)
        val file = child(directory, RECORD)
        if (!Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) || file.length() !in 1..16384)
            reject(GenerationAssetFailure.CORRUPT)
        val value = try {
            DataInputStream(ByteArrayInputStream(file.readBytes())).use { input ->
                if (input.readInt() != 1 || owner.fields().any { input.readUTF() != it })
                    reject(GenerationAssetFailure.CORRUPT)
                val identity = GenerationIdentity(input.readUTF(), input.readUTF())
                val index = input.readInt()
                validateKey(identity, index)
                if (slot(identity, index) != slot) reject(GenerationAssetFailure.CORRUPT)
                val kind = GenerationKind.valueOf(input.readUTF())
                val mime = input.readUTF(); val size = input.readLong(); val digest = input.readUTF()
                val width = input.readInt(); val height = input.readInt(); val duration = input.readLong()
                val limit = if (kind == GenerationKind.IMAGE) imageLimits.maxImageBytes.toLong() else videoLimits.maxVideoBytes
                val mimes = if (kind == GenerationKind.IMAGE) setOf("image/png", "image/jpeg", "image/webp")
                    else setOf("video/mp4", "video/webm")
                val maxPixels = if (kind == GenerationKind.IMAGE) imageLimits.maxPixels else videoLimits.maxPixels
                if (size !in 1..limit || !HASH.matches(digest) || mime !in mimes || width <= 0 || height <= 0 ||
                    width.toLong() * height > maxPixels ||
                    (kind == GenerationKind.IMAGE && duration != 0L) ||
                    (kind == GenerationKind.VIDEO && duration !in 1..videoLimits.maxDurationMs) || input.read() != -1)
                    reject(GenerationAssetFailure.CORRUPT)
                descriptor(identity, index, kind, size, digest, Actual(GenerationMediaInfo(mime, width, height), duration))
            }
        } catch (_: Exception) { reject(GenerationAssetFailure.CORRUPT) }
        return value
    }

    private fun resolve(receipt: GenerationAssetReceipt): PendingGenerationAsset {
        val match = RECEIPT.matchEntire(receipt.assetId) ?: reject(GenerationAssetFailure.CORRUPT)
        val value = readRecord(match.groupValues[1])
        if (value.receipt != receipt) reject(GenerationAssetFailure.CORRUPT)
        return value
    }

    private fun openVerified(value: PendingGenerationAsset): FileInputStream {
        val file = child(directory(slot(value.identity, value.outputIndex)), ORIGINAL)
        if (!Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) reject(GenerationAssetFailure.CORRUPT)
        val stream = FileInputStream(file)
        try {
            if (stream.channel.size() != value.bytes) reject(GenerationAssetFailure.CORRUPT)
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(16 * 1024)
            var count = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                count += read
                if (count > value.bytes) reject(GenerationAssetFailure.CORRUPT)
                digest.update(buffer, 0, read)
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            if (count != value.bytes || hash != value.sha256) reject(GenerationAssetFailure.CORRUPT)
            stream.channel.position(0)
            val actual = if (value.kind == GenerationKind.VIDEO) {
                val metadata = videoInspector.inspect(file, videoLimits) { Thread.currentThread().isInterrupted }
                validateVideo(metadata, videoLimits)
                Actual(GenerationMediaInfo(metadata.mime, metadata.width, metadata.height), metadata.durationMs)
            } else inspectBytes(value.kind, stream.readBytes(), value.mimeType)
            if (actual.info != GenerationMediaInfo(value.mimeType, value.width, value.height) || actual.durationMs != value.durationMs)
                reject(GenerationAssetFailure.CORRUPT)
            stream.channel.position(0)
            return stream
        } catch (failure: Exception) {
            try { stream.close() } catch (_: Exception) { /* preserve primary failure */ }
            throw failure
        }
    }

    private fun sameRecord(left: PendingGenerationAsset, right: PendingGenerationAsset) =
        encode(left).contentEquals(encode(right))

    private fun directory(slot: String, suffix: String = ""): File = mediaDirectory(root, slot + suffix)
    private fun child(directory: File, name: String): File = File(directory, name).also { checkMediaPath(it) }
    private fun exists(file: File) = Files.exists(file.toPath(), NOFOLLOW_LINKS)

    private fun writeSynced(file: File, bytes: ByteArray) {
        if (!file.createNewFile()) reject(GenerationAssetFailure.STORAGE)
        writeExistingSynced(file, bytes)
    }
    private fun writeExistingSynced(file: File, bytes: ByteArray) {
        checkMediaPath(file)
        FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
    }
    private fun cleanupOwnedStage(directory: File) {
        // Only this invocation's never-published staging and known names. Best effort, no recursion.
        try {
            listOf(ORIGINAL, RECORD).forEach { child(directory, it).delete() }
            directory.delete()
        } catch (_: Exception) { /* keep unknown staging; never touch a published directory */ }
    }

    private fun <T> locked(action: () -> T): T {
        val gate = ROOT_LOCKS.computeIfAbsent(root.absolutePath) { Any() }
        return synchronized(gate) {
            checkMediaPath(root)
            if (!Files.isDirectory(root.toPath(), NOFOLLOW_LINKS)) reject(GenerationAssetFailure.STORAGE)
            val lockFile = child(root, ".lock")
            if (exists(lockFile) && !Files.isRegularFile(lockFile.toPath(), NOFOLLOW_LINKS)) reject(GenerationAssetFailure.CORRUPT)
            FileChannel.open(lockFile.toPath(), CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
                channel.lock().use { action() }
            }
        }
    }

    private fun <T> guarded(action: () -> T): T = try { action() }
        catch (failure: GenerationAssetException) { throw failure }
        catch (_: Exception) { reject(GenerationAssetFailure.STORAGE) }

    private companion object {
        const val ORIGINAL = "original"
        const val RECORD = "record-v1"
        val ITEM = Regex("[A-Za-z0-9_-]{1,128}")
        val HASH = Regex("[0-9a-f]{64}")
        val RECEIPT = Regex("genasset-v1-([0-9a-f]{64})-([0-9a-f]{64})")
        val ROOT_LOCKS = ConcurrentHashMap<String, Any>()
        fun packed(write: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also {
            DataOutputStream(it).use(write)
        }.toByteArray()
    }
}

private fun reject(failure: GenerationAssetFailure): Nothing = throw GenerationAssetException(failure)
