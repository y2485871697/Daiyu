package app.daiyu.android.media

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE

/** App-scoped, private message assets, in a root DISTINCT from ImageAttachmentStore's root.
 *
 * prepare copies frozen original bytes and atomically publishes one complete message directory;
 * it NEVER consumes the draft. Publication is not a database commit. The host must serialize
 * prepare / message+receipt DB transaction / recovery / deletion with its common acceptance lock.
 * Persist the returned values in that DB transaction, then remove only accepted draft references.
 * On a failed/unknown DB outcome retain the draft AND prepared assets until the DB is reconciled.
 * A process crash between publication and DB commit can leave an orphan, not a dangling reference.
 * The host must durably retain/derive the pending message identity before prepare so it can target
 * recovery after restart; this store does not scan other messages or infer acceptance from files.
 *
 * One store instance per root. Files are fsynced, but directory fsync/power-loss durability and a
 * cross-process or filesystem+SQLite transaction are NOT provided. No unreferenced-asset age GC:
 * only the host with an authoritative DB absence result may call discardUnreferenced.
 */
class MessageImageAssetStore(
    root: File,
    private val limits: ImageAttachmentLimits = ImageAttachmentLimits(),
) {
    private val root = initializeMediaRoot(root)
    private val gate = Any()
    private val restoreInspector = AndroidImageInspector()

    val resolver: MessageImageResolver = object : MessageImageResolver {
        override fun read(owner: MessageImageOwner, reference: MessageImageReference): ByteArray =
            synchronized(gate) { io { readLocked(owner, reference) } }

        override fun freeze(
            owner: MessageImageOwner,
            references: List<MessageImageReference>,
            imageInputSupported: Boolean,
            isCancelled: () -> Boolean,
        ): List<FrozenImageAttachment> = synchronized(gate) {
            io {
                val snapshot = references.toList()
                checkImageCancellation(isCancelled)
                if (snapshot.isNotEmpty() && !imageInputSupported) fail(ImageAttachmentFailure.MODEL_UNSUPPORTED)
                validateReferences(owner, snapshot)
                snapshot.map {
                    checkImageCancellation(isCancelled)
                    FrozenImageAttachment(it.asAttachment(), readLocked(owner, it))
                }.also { checkImageCancellation(isCancelled) }
            }
        }
    }

    /** Same message owner + same ordered snapshot is idempotent, including after reopen or draft
     * deletion. Reusing an owner with different content/source/order fails closed. Cancellation is
     * checked before atomic publication; after that linearization point success wins the race.
     */
    fun prepare(
        owner: MessageImageOwner,
        draftOwner: ImageAttachmentOwner,
        frozen: List<FrozenImageAttachment>,
        isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
    ): List<MessageImageReference> = synchronized(gate) {
        io {
            checkImageCancellation(isCancelled)
            if (draftOwner.conversationId != owner.conversationId) fail(ImageAttachmentFailure.OWNER_MISMATCH)
            val snapshot = frozen.toList()
            if (snapshot.size > limits.maxImages) fail(ImageAttachmentFailure.TOO_MANY)
            val references = snapshot.map {
                val image = it.attachment
                if (image.owner != draftOwner) fail(ImageAttachmentFailure.OWNER_MISMATCH)
                validateImageMetadata(image, limits)
                MessageImageReference(owner, image.id, image.mime, image.bytes, image.sha256, image.width, image.height)
            }
            validateReferences(owner, references)
            val contents = snapshot.zip(references).map { (image, ref) ->
                checkImageCancellation(isCancelled)
                image.bytes().also { bytes ->
                    if (bytes.size != ref.bytes || imageDigest(bytes) != ref.sha256) fail(ImageAttachmentFailure.CORRUPT)
                }
            }
            val manifest = Manifest(owner, draftOwner, references)
            val directory = directory(owner)
            if (directory.exists()) {
                if (readManifest(owner, directory) != manifest) fail(ImageAttachmentFailure.CORRUPT)
                references.forEach {
                    checkImageCancellation(isCancelled)
                    readLocked(owner, it)
                }
                checkImageCancellation(isCancelled)
                return@io references.toList()
            }
            if (directory(owner, ".deleting").exists()) fail(ImageAttachmentFailure.STORAGE)
            cleanStagingLocked(owner)
            val staging = directory(owner, ".pending", create = true)
            try {
                contents.zip(references).forEach { (bytes, ref) ->
                    checkImageCancellation(isCancelled)
                    writeSynced(staging, ref.id + ".image", bytes)
                }
                writeSynced(staging, MANIFEST, encode(manifest))
                checkImageCancellation(isCancelled)
                // No overwrite: host lock + one app-scoped store are required for this root.
                Files.move(staging.toPath(), directory.toPath(), ATOMIC_MOVE)
                references.toList()
            } catch (failure: Exception) {
                try { cleanStagingLocked(owner) } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }
    }

    /** Restore one complete, immutable owner batch, under the SAME gate as prepare/read/delete.
     * The host must first authorize references against the package's persisted message owners.
     * Every incoming original is copied and decoded before any filesystem mutation. Existing
     * batches are compared by id + all metadata, not order or draft provenance, and fully verified;
     * a missing/corrupt/conflicting batch is never repaired or overwritten by an import.
     *
     * Only a staging directory created by this invocation can be cleaned on failure. In particular
     * pre-existing staging, tombstones, published batches and other owners (including safe orphans
     * from an earlier owner in a failed DB transaction) are never deleted. No cross-owner/DB atomicity
     * is claimed. The existing single-store, host-serialization and filesystem durability contract
     * applies. Restore always uses the same real Android image decoding as draft import.
     */
    fun restore(
        owner: MessageImageOwner,
        references: List<MessageImageReference>,
        contents: List<ByteArray>,
    ): Unit = synchronized(gate) {
        io {
            val snapshot = references.toList()
            val supplied = contents.toList()
            validateReferences(owner, snapshot)
            if (snapshot.size != supplied.size) fail(ImageAttachmentFailure.CORRUPT)
            // Check bounds before copying caller-owned buffers; never retain mutable input arrays.
            snapshot.zip(supplied).forEach { (ref, bytes) ->
                if (bytes.size != ref.bytes) fail(ImageAttachmentFailure.CORRUPT)
            }
            val originals = supplied.map { it.copyOf() }
            snapshot.zip(originals).forEach { (ref, bytes) -> verifyRestoredImage(ref, bytes) }
            val ordered = snapshot.sortedBy { it.id }
            val published = directory(owner)
            if (directory(owner, ".deleting").exists()) fail(ImageAttachmentFailure.STORAGE)
            if (published.exists()) {
                val existing = readManifest(owner, published)
                if (existing.references.sortedBy { it.id } != ordered) fail(ImageAttachmentFailure.CORRUPT)
                // Reject unrecognized entries as well; never delete or normalize an existing batch.
                val names = (published.listFiles() ?: fail(ImageAttachmentFailure.STORAGE)).map { it.name }.toSet()
                if (names != ordered.map { it.id + ".image" }.toSet() + MANIFEST) fail(ImageAttachmentFailure.CORRUPT)
                ordered.forEach { verifyRestoredImage(it, readLocked(owner, it)) }
                return@io
            }
            val staging = directory(owner, ".pending")
            // Unlike prepare recovery, import has no authority to discard earlier pending work.
            if (staging.exists()) fail(ImageAttachmentFailure.STORAGE)
            if (!staging.mkdir()) fail(ImageAttachmentFailure.STORAGE)
            try {
                snapshot.zip(originals).forEach { (ref, bytes) -> writeSynced(staging, ref.id + ".image", bytes) }
                // No draft is involved in restore. Keep the v1 manifest readable by existing resolvers.
                val source = ImageAttachmentOwner(owner.conversationId, owner.directoryName())
                writeSynced(staging, MANIFEST, encode(Manifest(owner, source, ordered)))
                // One store per root + gate/host serialization prevents concurrent destination writers.
                // ATOMIC_MOVE is deliberately required: unsupported filesystems fail, never copy over.
                if (directory(owner).exists() || directory(owner, ".deleting").exists()) fail(ImageAttachmentFailure.STORAGE)
                Files.move(staging.toPath(), published.toPath(), ATOMIC_MOVE)
                Unit
            } catch (failure: Exception) {
                try { cleanStagingLocked(owner) } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }
    }

    private fun verifyRestoredImage(reference: MessageImageReference, bytes: ByteArray) {
        if (bytes.size != reference.bytes || imageDigest(bytes) != reference.sha256) fail(ImageAttachmentFailure.CORRUPT)
        val actual = restoreInspector.inspect(bytes)
        validateImageDimensions(actual, limits)
        if (actual != ImageDimensions(reference.mime, reference.width, reference.height)) fail(ImageAttachmentFailure.CORRUPT)
    }

    /** Inspect an interrupted acceptance after restart using its persisted/known message identity.
     * Includes a deletion tombstone so an interrupted discard can be retried. Does not assert DB
     * acceptance/readability and does not recreate missing data. Empty may mean no assets.
     */
    fun preparedReferences(owner: MessageImageOwner): List<MessageImageReference> = synchronized(gate) {
        io {
            val published = directory(owner)
            val deleting = directory(owner, ".deleting")
            if (published.exists() && deleting.exists()) fail(ImageAttachmentFailure.STORAGE)
            val directory = if (published.exists()) published else deleting
            if (!directory.exists()) emptyList()
            else if (directory == deleting && !checkedFile(directory, MANIFEST).exists()) emptyList()
            else readManifest(owner, directory).references.toList()
        }
    }

    /** Only abandoned unpublished staging for this exact message. Never touches published assets. */
    fun cleanStaging(owner: MessageImageOwner) = synchronized(gate) { io { cleanStagingLocked(owner) } }

    /** Explicit DB-authorized rollback/deletion, NOT a generic failed-send finally block.
     * Under the common host lock, first prove the message has no persisted references (or commit
     * its deletion); supply the exact prepared batch. Rename to a tombstone before unlinking.
     * A failed unlink can be retried with the same values; no recursive or conversation-wide GC.
     * Never call while DB commit status is unknown. This method does not query SQLite itself.
     */
    fun discardUnreferenced(owner: MessageImageOwner, expected: List<MessageImageReference>): Boolean =
        synchronized(gate) {
            io {
                val references = expected.toList()
                validateReferences(owner, references)
                val published = directory(owner)
                val deleting = directory(owner, ".deleting")
                if (published.exists()) {
                    if (deleting.exists()) fail(ImageAttachmentFailure.STORAGE)
                    if (readManifest(owner, published).references != references) fail(ImageAttachmentFailure.CORRUPT)
                    Files.move(published.toPath(), deleting.toPath(), ATOMIC_MOVE)
                } else if (!deleting.exists()) return@io false
                // The manifest is deleted last. A crash after that leaves only an empty directory.
                val metadata = checkedFile(deleting, MANIFEST)
                if (metadata.exists() && readManifest(owner, deleting).references != references) {
                    fail(ImageAttachmentFailure.CORRUPT)
                }
                deleteDirectory(deleting, references.map { it.id + ".image" }.toSet() + MANIFEST)
                true
            }
        }

    private data class Manifest(
        val owner: MessageImageOwner,
        val draftOwner: ImageAttachmentOwner,
        val references: List<MessageImageReference>,
    )

    private fun directory(owner: MessageImageOwner, suffix: String = "", create: Boolean = false): File =
        mediaDirectory(root, owner.directoryName() + suffix, create)

    private fun validateReferences(owner: MessageImageOwner, references: List<MessageImageReference>) {
        if (references.size > limits.maxImages) fail(ImageAttachmentFailure.TOO_MANY)
        references.forEach {
            if (it.owner != owner) fail(ImageAttachmentFailure.OWNER_MISMATCH)
            validateImageMetadata(it.asAttachment(), limits)
        }
        if (references.map { it.id }.toSet().size != references.size) fail(ImageAttachmentFailure.CORRUPT)
        if (references.sumOf { it.bytes.toLong() } > limits.maxDraftBytes) fail(ImageAttachmentFailure.TOO_LARGE)
    }

    private fun readLocked(owner: MessageImageOwner, reference: MessageImageReference): ByteArray {
        validateReferences(owner, listOf(reference))
        val directory = directory(owner)
        val manifest = readManifest(owner, directory)
        if (reference !in manifest.references) fail(ImageAttachmentFailure.CORRUPT)
        val bytes = readBounded(checkedFile(directory, reference.id + ".image"), reference.bytes)
        if (bytes.size != reference.bytes || imageDigest(bytes) != reference.sha256) fail(ImageAttachmentFailure.CORRUPT)
        return bytes
    }

    private fun encode(manifest: Manifest): ByteArray = JSONObject().put("version", 1)
        .put("conversation", manifest.draftOwner.conversationId).put("draft", manifest.draftOwner.draftId)
        .put("message", manifest.owner.messageId)
        .put("images", JSONArray().also { array -> manifest.references.forEach { array.put(it.json()) } })
        .toString().toByteArray(Charsets.UTF_8)

    private fun readManifest(owner: MessageImageOwner, directory: File): Manifest {
        try {
            val json = JSONObject(readBounded(checkedFile(directory, MANIFEST), MAX_MANIFEST).toString(Charsets.UTF_8))
            if (json.getInt("version") != 1) fail(ImageAttachmentFailure.CORRUPT)
            val draft = ImageAttachmentOwner(json.getString("conversation"), json.getString("draft"))
            if (draft.conversationId != owner.conversationId) fail(ImageAttachmentFailure.OWNER_MISMATCH)
            // Store the message owner even for an empty image batch.
            if (json.getString("message") != owner.messageId) fail(ImageAttachmentFailure.OWNER_MISMATCH)
            val images = json.getJSONArray("images")
            if (images.length() > limits.maxImages) fail(ImageAttachmentFailure.TOO_MANY)
            val references = List(images.length()) { MessageImageReference.decode(images.getJSONObject(it)) }
            validateReferences(owner, references)
            return Manifest(owner, draft, references)
        } catch (failure: Exception) {
            if (failure is ImageAttachmentException) throw failure
            throw ImageAttachmentException(ImageAttachmentFailure.CORRUPT, failure)
        }
    }

    private fun checkedFile(directory: File, name: String): File {
        checkMediaPath(root)
        checkMediaPath(directory)
        val file = File(directory, name)
        checkMediaPath(file)
        return file
    }

    private fun readBounded(file: File, maxBytes: Int): ByteArray {
        if (!Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) fail(ImageAttachmentFailure.MISSING)
        if (file.length() > maxBytes) fail(ImageAttachmentFailure.CORRUPT)
        return file.inputStream().use { input ->
            val result = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (result.size().toLong() + count > maxBytes) fail(ImageAttachmentFailure.CORRUPT)
                result.write(buffer, 0, count)
            }
            result.toByteArray()
        }
    }

    private fun writeSynced(directory: File, name: String, bytes: ByteArray) {
        val file = checkedFile(directory, name)
        if (!file.createNewFile()) fail(ImageAttachmentFailure.STORAGE)
        FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
    }

    private fun cleanStagingLocked(owner: MessageImageOwner) {
        val staging = directory(owner, ".pending")
        if (!staging.exists()) return
        val names = (staging.listFiles() ?: fail(ImageAttachmentFailure.STORAGE)).map { it.name }
        if (names.any { it != MANIFEST && !(it.endsWith(".image") && IMAGE_ID.matches(it.removeSuffix(".image"))) }) {
            fail(ImageAttachmentFailure.CORRUPT)
        }
        deleteDirectory(staging, names.toSet())
    }

    private fun deleteDirectory(directory: File, allowedNames: Set<String>) {
        checkMediaPath(root)
        checkMediaPath(directory)
        val files = directory.listFiles() ?: fail(ImageAttachmentFailure.STORAGE)
        if (files.any { it.name !in allowedNames || Files.isDirectory(it.toPath(), NOFOLLOW_LINKS) }) {
            fail(ImageAttachmentFailure.CORRUPT)
        }
        files.sortedBy { it.name == MANIFEST }.forEach {
            checkMediaPath(root)
            checkMediaPath(directory)
            // Unlink a leaf symlink itself; never follow it, even during recovery.
            Files.deleteIfExists(it.toPath())
        }
        Files.delete(directory.toPath())
    }

    private inline fun <T> io(action: () -> T): T = try { action() } catch (failure: IOException) {
        throw ImageAttachmentException(ImageAttachmentFailure.STORAGE, failure)
    }

    private fun fail(failure: ImageAttachmentFailure): Nothing = throw ImageAttachmentException(failure)

    private companion object {
        const val MANIFEST = "manifest.json"
        const val MAX_MANIFEST = 64 * 1024
    }
}
