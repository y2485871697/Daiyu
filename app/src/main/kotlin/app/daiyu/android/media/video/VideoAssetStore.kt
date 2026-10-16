package app.daiyu.android.media.video

import app.daiyu.android.media.ImageAttachmentException
import app.daiyu.android.media.ImageAttachmentFailure
import app.daiyu.android.media.checkMediaPath
import app.daiyu.android.media.initializeMediaRoot
import app.daiyu.android.media.mediaDirectory
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest
import java.util.UUID

/**
 * One app-scoped instance in a private filesDir root, separate from all image assets. Every
 * original + metadata pair is published by an atomic directory rename, never recompressed.
 * The host must serialize prepare / DB acceptance / recovery / deletion with its acceptance lock.
 * prepare is NOT DB acceptance and does not consume drafts. Persist returned references with the
 * message, then remove exactly the accepted drafts. Retain both on an unknown DB outcome.
 *
 * No age GC, cross-process lock, directory fsync or filesystem+DB transaction is claimed. Recovery
 * uses known owner identities. This is not a sandbox against other code writing under the same UID.
 * All cancel callbacks must be nonthrowing. Cancellation observed through the final publication
 * check rolls back only this invocation's new asset; cancellation after return cannot revoke it.
 */
class VideoAssetStore internal constructor(
    root: File,
    private val limits: VideoAttachmentLimits,
    private val inspector: VideoInspector,
) {
    constructor(root: File, limits: VideoAttachmentLimits = VideoAttachmentLimits()) :
        this(root, limits, AndroidVideoInspector())

    private val root = videoIo { initializeMediaRoot(root) }
    private val gate = Any()

    /** Takes ownership of input even on rejection; bounded streaming avoids a video-sized heap copy. */
    fun import(
        owner: VideoAttachmentOwner,
        input: InputStream,
        isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
    ): VideoAttachment = synchronized(gate) { videoIo {
        val id = UUID.randomUUID().toString()
        var owned: File? = null
        try {
            // Close the source BEFORE publishing: even a provider close failure cannot produce
            // a published asset together with a failed import result.
            val staged = input.use { source ->
                checkVideoCancellation(isCancelled)
                val pending = assetDirectory(owner, id, ".pending")
                val destination = assetDirectory(owner, id)
                if (exists(pending) || exists(destination)) videoFail(VideoAttachmentFailure.STORAGE)
                if (!pending.mkdir()) videoFail(VideoAttachmentFailure.STORAGE)
                owned = pending
                Triple(pending, destination, copy(source, checked(pending, ORIGINAL), limits.maxVideoBytes, isCancelled))
            }
            val (pending, destination, copied) = staged
            checkVideoCancellation(isCancelled)
            val actual = inspector.inspect(checked(pending, ORIGINAL), limits, isCancelled)
            validateVideo(actual, limits)
            checkVideoCancellation(isCancelled)
            checkCapacity(owner, copied.first)
            val value = VideoAttachment(owner, id, actual.mime, copied.first, copied.second,
                actual.durationMs, actual.width, actual.height)
            writeRecord(pending, value, owner.draftId)
            checkVideoCancellation(isCancelled)
            Files.move(pending.toPath(), destination.toPath(), ATOMIC_MOVE)
            owned = destination
            checkVideoCancellation(isCancelled)
            value
        } catch (failure: Exception) {
            owned?.let { cleanupAfterFailure(it, failure) }
            if (failure is IOException && isCancelled()) {
                throw VideoAttachmentException(VideoAttachmentFailure.CANCELLED, failure)
            }
            throw failure
        }
    } }

    /** Idempotent for an exact attachment/message pair, also after draft deletion and restart. */
    fun prepare(
        messageOwner: MessageVideoOwner,
        attachment: VideoAttachment,
        isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
    ): MessageVideoReference = synchronized(gate) { videoIo {
        checkVideoCancellation(isCancelled)
        if (messageOwner.conversationId != attachment.owner.conversationId) videoFail(VideoAttachmentFailure.OWNER_MISMATCH)
        validateReference(attachment)
        val value = MessageVideoReference(messageOwner, attachment.id, attachment.mime, attachment.bytes,
            attachment.sha256, attachment.durationMs, attachment.width, attachment.height)
        val destination = assetDirectory(messageOwner, value.id)
        if (exists(assetDirectory(messageOwner, value.id, ".deleting"))) videoFail(VideoAttachmentFailure.STORAGE)
        if (exists(destination)) {
            val record = readRecord(messageOwner, destination)
            if (record.reference != value || record.sourceDraft != attachment.owner.draftId) videoFail(VideoAttachmentFailure.CORRUPT)
            openOriginal(messageOwner, value, isCancelled).use { }
            checkVideoCancellation(isCancelled)
            return@videoIo value
        }
        checkCapacity(messageOwner, value.bytes)
        val pending = assetDirectory(messageOwner, value.id, ".pending")
        // Never erase pre-existing work implicitly; the host has an explicit recovery operation.
        if (exists(pending)) videoFail(VideoAttachmentFailure.STORAGE)
        if (!pending.mkdir()) videoFail(VideoAttachmentFailure.STORAGE)
        var published = false
        try {
            openOriginal(attachment.owner, attachment, isCancelled).use { original ->
                val copied = copy(original, checked(pending, ORIGINAL), limits.maxVideoBytes, isCancelled)
                if (copied.first != value.bytes || copied.second != value.sha256) videoFail(VideoAttachmentFailure.CORRUPT)
            }
            val actual = inspector.inspect(checked(pending, ORIGINAL), limits, isCancelled)
            validateVideo(actual, limits)
            if (actual != value.metadata()) videoFail(VideoAttachmentFailure.CORRUPT)
            writeRecord(pending, value, attachment.owner.draftId)
            checkVideoCancellation(isCancelled)
            Files.move(pending.toPath(), destination.toPath(), ATOMIC_MOVE)
            published = true
            checkVideoCancellation(isCancelled)
            value
        } catch (failure: Exception) {
            cleanupAfterFailure(if (published) destination else pending, failure)
            throw failure
        }
    } }

    /** Restore immutable message originals without minting IDs or granting draft/upload authority.
     * Exact existing pairs are verified, never overwritten. On failure only directories created by
     * this call are removed; the host still owns DB atomicity and must hold its acceptance lock.
     * A successful call followed by a DB rollback may leave unreferenced originals, not data loss.
     */
    fun restore(
        owner: MessageVideoOwner,
        references: List<MessageVideoReference>,
        contents: List<ByteArray>,
        isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
    ) = synchronized(gate) { videoIo {
        checkVideoCancellation(isCancelled)
        require(references.size == contents.size)
        require(references.map { it.id }.distinct().size == references.size)
        references.forEachIndexed { index, reference ->
            checkVideoCancellation(isCancelled)
            authorize(owner, reference)
            if (contents[index].size.toLong() != reference.bytes || videoDigest(contents[index]) != reference.sha256) {
                videoFail(VideoAttachmentFailure.CORRUPT)
            }
        }
        val existing = listLocked(owner)
        val additions = references.filter { wanted -> existing.none { it.id == wanted.id } }
        if (existing.size + additions.size > limits.maxVideos) videoFail(VideoAttachmentFailure.TOO_MANY)
        if (existing.sumOf { it.bytes } + additions.sumOf { it.bytes } > limits.maxOwnerBytes) {
            videoFail(VideoAttachmentFailure.TOO_LARGE)
        }
        val staged = mutableListOf<Pair<File, File>>()
        val published = mutableListOf<File>()
        try {
            references.forEachIndexed { index, reference ->
                checkVideoCancellation(isCancelled)
                val destination = assetDirectory(owner, reference.id)
                if (exists(assetDirectory(owner, reference.id, ".deleting"))) videoFail(VideoAttachmentFailure.STORAGE)
                if (exists(destination)) {
                    // A marker/metadata file is not proof that the original still exists.
                    openOriginal(owner, reference, isCancelled).use { }
                    val actual = inspector.inspect(checked(destination, ORIGINAL), limits, isCancelled)
                    validateVideo(actual, limits)
                    if (actual != reference.metadata()) videoFail(VideoAttachmentFailure.CORRUPT)
                } else {
                    val pending = assetDirectory(owner, reference.id, ".pending")
                    if (exists(pending) || !pending.mkdir()) videoFail(VideoAttachmentFailure.STORAGE)
                    staged.add(pending to destination)
                    val copied = contents[index].inputStream().use {
                        copy(it, checked(pending, ORIGINAL), limits.maxVideoBytes, isCancelled)
                    }
                    if (copied.first != reference.bytes || copied.second != reference.sha256) videoFail(VideoAttachmentFailure.CORRUPT)
                    val actual = inspector.inspect(checked(pending, ORIGINAL), limits, isCancelled)
                    validateVideo(actual, limits)
                    if (actual != reference.metadata()) videoFail(VideoAttachmentFailure.CORRUPT)
                    // Local recovery provenance only: this must never authorize a source draft.
                    writeRecord(pending, reference, "restored-backup")
                }
            }
            staged.forEach { (pending, destination) ->
                checkVideoCancellation(isCancelled)
                Files.move(pending.toPath(), destination.toPath(), ATOMIC_MOVE)
                published.add(destination)
            }
            checkVideoCancellation(isCancelled)
        } catch (failure: Exception) {
            published.asReversed().forEach { cleanupAfterFailure(it, failure) }
            staged.forEach { (pending, _) -> cleanupAfterFailure(pending, failure) }
            throw failure
        }
    } }

    /** Exact owner AND complete immutable reference must match stored metadata. Caller closes it.
     * The same read-only descriptor is hashed, rewound and returned; no path/URI is exported.
     */
    fun openOriginal(
        owner: VideoOwner,
        reference: VideoReference,
        isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
    ): FileInputStream = synchronized(gate) { videoIo {
        checkVideoCancellation(isCancelled)
        authorize(owner, reference)
        val directory = assetDirectory(owner, reference.id)
        if (!exists(directory)) videoFail(VideoAttachmentFailure.MISSING)
        if (readRecord(owner, directory).reference != reference) videoFail(VideoAttachmentFailure.CORRUPT)
        val file = checked(directory, ORIGINAL)
        if (!Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) videoFail(VideoAttachmentFailure.MISSING)
        val stream = FileInputStream(file)
        try {
            if (stream.channel.size() != reference.bytes) videoFail(VideoAttachmentFailure.CORRUPT)
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                checkVideoCancellation(isCancelled)
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                if (total > reference.bytes) videoFail(VideoAttachmentFailure.CORRUPT)
                digest.update(buffer, 0, count)
            }
            if (total != reference.bytes || hex(digest.digest()) != reference.sha256) videoFail(VideoAttachmentFailure.CORRUPT)
            checkVideoCancellation(isCancelled)
            stream.channel.position(0)
            stream
        } catch (failure: Exception) {
            try { stream.close() } catch (close: IOException) { failure.addSuppressed(close) }
            throw failure
        }
    } }

    /** Run-local original bytes for protocol encoding, not a preview. The caller must preflight
     * the WHOLE request's metadata budget first. Rehash the actual bounded copy as well as the
     * descriptor verified by openOriginal, so in-place changes cannot become a wire payload.
     */
    internal fun readForModel(
        owner: MessageVideoOwner,
        reference: MessageVideoReference,
        maxBytes: Long,
        isCancelled: () -> Boolean,
    ): ByteArray = synchronized(gate) { videoIo {
        checkVideoCancellation(isCancelled)
        if (reference.bytes <= 0) videoFail(VideoAttachmentFailure.CORRUPT)
        if (reference.bytes > maxBytes || reference.bytes > Int.MAX_VALUE) videoFail(VideoAttachmentFailure.TOO_LARGE)
        openOriginal(owner, reference, isCancelled).use { source ->
            val bytes = ByteArray(reference.bytes.toInt())
            var offset = 0
            while (offset < bytes.size) {
                checkVideoCancellation(isCancelled)
                val count = source.read(bytes, offset, minOf(16 * 1024, bytes.size - offset))
                if (count < 0) videoFail(VideoAttachmentFailure.CORRUPT)
                if (count == 0) continue
                offset += count
            }
            checkVideoCancellation(isCancelled)
            if (source.read() != -1 || videoDigest(bytes) != reference.sha256) videoFail(VideoAttachmentFailure.CORRUPT)
            checkVideoCancellation(isCancelled)
            bytes
        }
    } }

    fun list(owner: VideoAttachmentOwner): List<VideoAttachment> = synchronized(gate) { videoIo {
        listLocked(owner).map { it as VideoAttachment }
    } }

    /** Recovery only: existence is not evidence of DB acceptance. */
    fun preparedReferences(owner: MessageVideoOwner): List<MessageVideoReference> = synchronized(gate) { videoIo {
        listLocked(owner).map { it as MessageVideoReference }
    } }

    fun remove(owner: VideoAttachmentOwner, attachment: VideoAttachment): Boolean =
        synchronized(gate) { videoIo { removeLocked(owner, attachment) } }

    /** Call ONLY after the host has authoritatively established this exact asset is unreferenced. */
    fun discardUnreferenced(owner: MessageVideoOwner, reference: MessageVideoReference): Boolean =
        synchronized(gate) { videoIo { removeLocked(owner, reference) } }

    /** Only recognized staging directories under this exact owner; never touches published assets. */
    fun cleanUnpublished(owner: VideoOwner) = synchronized(gate) { videoIo {
        val directory = ownerDirectory(owner, false)
        if (exists(directory)) {
            for (child in directory.listFiles() ?: videoFail(VideoAttachmentFailure.STORAGE)) {
                if (child.name.endsWith(".pending") && VIDEO_ID.matches(child.name.removeSuffix(".pending"))) {
                    cleanupKnown(checked(directory, child.name))
                }
            }
        }
    } }

    private fun removeLocked(owner: VideoOwner, reference: VideoReference): Boolean {
        authorize(owner, reference)
        val directory = assetDirectory(owner, reference.id)
        val deleting = assetDirectory(owner, reference.id, ".deleting")
        if (exists(directory) && exists(deleting)) videoFail(VideoAttachmentFailure.CORRUPT)
        if (exists(directory)) {
            if (readRecord(owner, directory).reference != reference) videoFail(VideoAttachmentFailure.CORRUPT)
            openOriginal(owner, reference).use { }
            Files.move(directory.toPath(), deleting.toPath(), ATOMIC_MOVE)
        } else if (!exists(deleting)) return false
        // A tombstone can be partially deleted after interruption, but never follow unknown entries.
        val metadata = checked(deleting, RECORD)
        if (exists(metadata) && readRecord(owner, deleting, allowMissingOriginal = true).reference != reference) {
            videoFail(VideoAttachmentFailure.CORRUPT)
        }
        cleanupKnown(deleting)
        return true
    }

    private fun checkCapacity(owner: VideoOwner, added: Long) {
        val references = listLocked(owner)
        if (references.size >= limits.maxVideos) videoFail(VideoAttachmentFailure.TOO_MANY)
        if (references.sumOf { it.bytes } + added > limits.maxOwnerBytes) videoFail(VideoAttachmentFailure.TOO_LARGE)
    }

    private fun listLocked(owner: VideoOwner): List<VideoReference> {
        val directory = ownerDirectory(owner, false)
        if (!exists(directory)) return emptyList()
        return (directory.listFiles() ?: videoFail(VideoAttachmentFailure.STORAGE)).mapNotNull { child ->
            when {
                VIDEO_ID.matches(child.name) -> readRecord(owner, checked(directory, child.name)).reference
                child.name.endsWith(".pending") && VIDEO_ID.matches(child.name.removeSuffix(".pending")) -> null
                child.name.endsWith(".deleting") && VIDEO_ID.matches(child.name.removeSuffix(".deleting")) -> null
                else -> videoFail(VideoAttachmentFailure.CORRUPT)
            }
        }.sortedBy { it.id }
    }

    private data class Record(val reference: VideoReference, val sourceDraft: String)

    private fun writeRecord(directory: File, value: VideoReference, sourceDraft: String) {
        val local = when (val owner = value.owner) {
            is VideoAttachmentOwner -> owner.draftId
            is MessageVideoOwner -> owner.messageId
        }
        val json = JSONObject().put("version", 1)
            .put("kind", if (value is VideoAttachment) "draft" else "message")
            .put("conversation", value.owner.conversationId).put("local", local)
            .put("sourceDraft", sourceDraft).put("id", value.id).put("mime", value.mime)
            .put("bytes", value.bytes).put("sha256", value.sha256).put("durationMs", value.durationMs)
            .put("width", value.width).put("height", value.height)
        val file = checked(directory, RECORD)
        if (!file.createNewFile()) videoFail(VideoAttachmentFailure.STORAGE)
        FileOutputStream(file).use { it.write(json.toString().toByteArray(Charsets.UTF_8)); it.fd.sync() }
    }

    private fun readRecord(owner: VideoOwner, directory: File, allowMissingOriginal: Boolean = false): Record {
        try {
            checkMediaPath(directory)
            if (!Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS)) videoFail(VideoAttachmentFailure.CORRUPT)
            val names = (directory.listFiles() ?: videoFail(VideoAttachmentFailure.STORAGE)).map { it.name }.toSet()
            if (names != setOf(ORIGINAL, RECORD) && !(allowMissingOriginal && names == setOf(RECORD))) {
                videoFail(VideoAttachmentFailure.CORRUPT)
            }
            val file = checked(directory, RECORD)
            if (!Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) || file.length() !in 1..4096) videoFail(VideoAttachmentFailure.CORRUPT)
            val json = JSONObject(file.readText(Charsets.UTF_8))
            if (json.getInt("version") != 1) videoFail(VideoAttachmentFailure.CORRUPT)
            val conversation = json.getString("conversation")
            val local = json.getString("local")
            val storedOwner: VideoOwner = when (json.getString("kind")) {
                "draft" -> VideoAttachmentOwner(conversation, local)
                "message" -> MessageVideoOwner(conversation, local)
                else -> videoFail(VideoAttachmentFailure.CORRUPT)
            }
            if (storedOwner != owner) videoFail(VideoAttachmentFailure.OWNER_MISMATCH)
            val id = json.getString("id")
            val mime = json.getString("mime")
            val bytes = json.getLong("bytes")
            val hash = json.getString("sha256")
            val duration = json.getLong("durationMs")
            val width = json.getInt("width")
            val height = json.getInt("height")
            val reference: VideoReference = when (storedOwner) {
                is VideoAttachmentOwner -> VideoAttachment(storedOwner, id, mime, bytes, hash, duration, width, height)
                is MessageVideoOwner -> MessageVideoReference(storedOwner, id, mime, bytes, hash, duration, width, height)
            }
            authorize(owner, reference)
            if (directory.name.removeSuffix(".deleting") != id) videoFail(VideoAttachmentFailure.CORRUPT)
            val source = json.getString("sourceDraft")
            if (source.isBlank() || source.length > 200 || (owner is VideoAttachmentOwner && source != owner.draftId)) {
                videoFail(VideoAttachmentFailure.CORRUPT)
            }
            return Record(reference, source)
        } catch (failure: Exception) {
            if (failure is VideoAttachmentException || failure is ImageAttachmentException) throw failure
            throw VideoAttachmentException(VideoAttachmentFailure.CORRUPT, failure)
        }
    }

    private fun authorize(owner: VideoOwner, reference: VideoReference) {
        if (reference.owner != owner) videoFail(VideoAttachmentFailure.OWNER_MISMATCH)
        validateReference(reference)
    }

    private fun validateReference(value: VideoReference) {
        if (!VIDEO_ID.matches(value.id) || !VIDEO_HASH.matches(value.sha256) || value.bytes <= 0) videoFail(VideoAttachmentFailure.CORRUPT)
        if (value.bytes > limits.maxVideoBytes) videoFail(VideoAttachmentFailure.TOO_LARGE)
        validateVideo(value.metadata(), limits)
    }

    private fun ownerDirectory(owner: VideoOwner, create: Boolean) = mediaDirectory(root, owner.directoryName(), create)
    private fun assetDirectory(owner: VideoOwner, id: String, suffix: String = ""): File {
        if (!VIDEO_ID.matches(id) || suffix !in setOf("", ".pending", ".deleting")) videoFail(VideoAttachmentFailure.CORRUPT)
        val directory = checked(ownerDirectory(owner, true), id + suffix)
        if (exists(directory) && !Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS)) videoFail(VideoAttachmentFailure.CORRUPT)
        return directory
    }
    private fun checked(parent: File, name: String): File = File(parent, name).also { checkMediaPath(it) }
    private fun exists(file: File): Boolean = Files.exists(file.toPath(), NOFOLLOW_LINKS)

    private fun cleanupAfterFailure(directory: File, failure: Exception) {
        try { cleanupKnown(directory) } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
    }
    private fun cleanupKnown(directory: File) {
        checkMediaPath(directory)
        if (!exists(directory)) return
        if (!Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS)) videoFail(VideoAttachmentFailure.CORRUPT)
        val files = directory.listFiles() ?: videoFail(VideoAttachmentFailure.STORAGE)
        // Validate every leaf BEFORE deleting any. Never delete recursively or follow a symlink.
        files.forEach {
            if (it.name !in setOf(ORIGINAL, RECORD)) videoFail(VideoAttachmentFailure.CORRUPT)
            checkMediaPath(it)
            if (!Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS)) videoFail(VideoAttachmentFailure.CORRUPT)
        }
        files.sortedBy { if (it.name == ORIGINAL) 0 else 1 }.forEach {
            if (!it.delete()) videoFail(VideoAttachmentFailure.STORAGE)
        }
        if (!directory.delete()) videoFail(VideoAttachmentFailure.STORAGE)
    }

    private fun copy(input: InputStream, file: File, maxBytes: Long, cancel: () -> Boolean): Pair<Long, String> {
        if (!file.createNewFile()) videoFail(VideoAttachmentFailure.STORAGE)
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        FileOutputStream(file).use { output ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                checkVideoCancellation(cancel)
                val read = try {
                    val bulk = input.read(buffer)
                    if (bulk != 0) bulk else {
                        val one = input.read()
                        if (one < 0) -1 else { buffer[0] = one.toByte(); 1 }
                    }
                } catch (failure: IOException) {
                    throw VideoAttachmentException(if (cancel()) VideoAttachmentFailure.CANCELLED else VideoAttachmentFailure.SOURCE_UNAVAILABLE, failure)
                }
                if (read < 0) break
                count += read
                if (count > maxBytes) videoFail(VideoAttachmentFailure.TOO_LARGE)
                output.write(buffer, 0, read)
                digest.update(buffer, 0, read)
            }
            checkVideoCancellation(cancel)
            if (count == 0L) videoFail(VideoAttachmentFailure.INVALID_VIDEO)
            output.fd.sync()
        }
        return count to hex(digest.digest())
    }

    companion object {
        private const val ORIGINAL = "original.video"
        private const val RECORD = "metadata.json"
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

private inline fun <T> videoIo(block: () -> T): T = try { block() }
catch (failure: ImageAttachmentException) {
    val reason = when (failure.failure) {
        ImageAttachmentFailure.CORRUPT -> VideoAttachmentFailure.CORRUPT
        else -> VideoAttachmentFailure.STORAGE
    }
    throw VideoAttachmentException(reason, failure)
} catch (failure: IOException) {
    throw VideoAttachmentException(VideoAttachmentFailure.STORAGE, failure)
} catch (failure: SecurityException) {
    throw VideoAttachmentException(VideoAttachmentFailure.STORAGE, failure)
}
