package app.daiyu.android.model.catalog

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A codec for the host's COMPLETE local model record, not just its remote name. */
internal interface CatalogRecordCodec<T> {
    fun encode(record: T): String
    fun decode(encoded: String): T
}

/**
 * Durable catalog portion of a host provider store. The file belongs to a host-owned provider
 * SLOT (for example the existing settings store's primary slot), never an endpoint-derived path.
 * The host must own this instance and route ALL provider edits through [configurationChange].
 * This class does not read/save active ModelSettings, store credentials, or select a model.
 *
 * UUID allocation happens only at explicit host registration/migration [register]. Editing an
 * endpoint does not replace that identity. Deletion is durable; re-registration gets a new UUID.
 * Files for other host slots are not touched. A process monitor AND an OS lock serialize different
 * instances; atomic replacement is mandatory, with no non-atomic fallback. Old encoded records
 * are retained verbatim, so importing never re-encodes/customizes an existing model.
 *
 * Durability boundary: this backend guarantees atomic replacement across PROCESS crashes, not
 * persistence across OS crash/power loss. The temporary file is fsynced, but the containing
 * directory is NOT fsynced. COMMITTED means rename succeeded and is visible to subsequent reads;
 * it does not certify power-loss durability (including initial registration/directory creation).
 * After rename we must report success, never FAILED/CANCELLED inviting an automatic retry.
 * A host requiring power-loss durability needs a stronger backend/result contract.
 */
internal class FileSavedModelCatalogPersistence<T>(
    file: File,
    private val codec: CatalogRecordCodec<T>,
) : SavedModelCatalogPersistence<T> {
    private val file = file.canonicalFile
    private val monitor = monitors.computeIfAbsent(this.file.path) { Any() }
    private data class EncodedModel(val id: String, val name: String, val record: String)
    private data class Document(val identity: String?, val revision: Long, val models: List<EncodedModel>)

    /** Host calls only for a live, saved provider. Existing registration is never replaced. */
    fun register(initialModels: List<SavedCatalogModel<T>> = emptyList()): SavedModelCatalogSnapshot<T> =
        registerFrom { initialModels }

    /** Lazy migration runs under the same lease as host mutations and is skipped on reopen. */
    fun registerFrom(initialModels: () -> List<SavedCatalogModel<T>>): SavedModelCatalogSnapshot<T> = exclusive {
        val old = readDocument()
        if (old?.identity != null) return@exclusive snapshot(old)
        val models = encodeModels(initialModels())
        val next = Document(UUID.randomUUID().toString(), nextRevision(old?.revision ?: -1), models)
        // Decode/validate before committing; no fallible codec work after the linearization point.
        val result = snapshot(next)
        writeDocument(next) { false }
        result
    }

    fun current(): SavedModelCatalogSnapshot<T>? = exclusive {
        readDocument()?.takeIf { it.identity != null }?.let(::snapshot)
    }

    override fun read(providerIdentity: String): SavedModelCatalogSnapshot<T>? = exclusive {
        readDocument()?.takeIf { it.identity == providerIdentity }?.let(::snapshot)
    }

    override fun compareAndAppend(
        providerIdentity: String,
        expectedRevision: Long,
        additions: List<SavedCatalogModel<T>>,
        isCancelled: () -> Boolean,
    ): CatalogCommitResult = try {
        exclusive {
            val old = readDocument()
            if (old == null || old.identity != providerIdentity || old.revision != expectedRevision) {
                return@exclusive CatalogCommitResult.STALE
            }
            if (cancelled(isCancelled)) return@exclusive CatalogCommitResult.CANCELLED
            val encoded = encodeModels(additions)
            val existing = old.models.map { it.id }.toSet()
            require(encoded.none { it.id in existing }) { "Catalog append contains an existing ID" }
            if (encoded.isEmpty()) return@exclusive CatalogCommitResult.COMMITTED
            val next = old.copy(revision = nextRevision(old.revision), models = old.models + encoded)
            if (writeDocument(next) { cancelled(isCancelled) }) CatalogCommitResult.COMMITTED
            else CatalogCommitResult.CANCELLED
        }
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        CatalogCommitResult.CANCELLED
    } catch (_: java.util.concurrent.CancellationException) {
        CatalogCommitResult.CANCELLED
    } catch (_: Exception) {
        // FileLockInterruptionException/ClosedByInterruptException are IO exceptions, not
        // InterruptedException. No fallible operation is allowed after successful rename.
        if (Thread.currentThread().isInterrupted) CatalogCommitResult.CANCELLED else CatalogCommitResult.FAILED
    }

    /**
     * The host wraps save/select/restore (and local model edits) in this same boundary. Invalidate
     * BEFORE invoking the host mutation, so a crash or failed host save cannot revive an old fetch.
     * A failed host save can conservatively advance the revision, but never changes old models.
     * No lock inversion: acquire this boundary BEFORE the host's existing configuration lock.
     */
    fun <R> configurationChange(change: () -> R): R = exclusive {
        val old = readDocument()
        if (old?.identity != null) writeDocument(old.copy(revision = nextRevision(old.revision))) { false }
        change()
    }

    /** Host deletion/replacement only. Unlike an empty list, the tombstone rejects old sessions. */
    fun deleteProvider(providerIdentity: String): Boolean = exclusive {
        val old = readDocument()
        if (old == null || old.identity != providerIdentity) return@exclusive false
        writeDocument(Document(null, nextRevision(old.revision), emptyList())) { false }
        true
    }

    private fun encodeModels(models: List<SavedCatalogModel<T>>): List<EncodedModel> {
        require(models.size <= MAX_MODELS)
        require(models.all { it.id.isNotBlank() && it.id == it.id.trim() })
        require(models.map { it.id }.toSet().size == models.size)
        return models.map { model ->
            val raw = codec.encode(model.settings)
            codec.decode(raw) // Reject unreadable new records before changing durable state.
            EncodedModel(model.id, model.displayName, raw)
        }
    }

    private fun snapshot(document: Document): SavedModelCatalogSnapshot<T> = SavedModelCatalogSnapshot(
        checkNotNull(document.identity), document.revision,
        Collections.unmodifiableList(document.models.map { SavedCatalogModel(it.id, it.name, codec.decode(it.record)) }),
    )

    private fun readDocument(): Document? {
        if (!file.exists()) return null
        require(file.length() <= MAX_FILE_BYTES)
        DataInputStream(ByteArrayInputStream(file.readBytes())).use { input ->
            require(input.readInt() == MAGIC && input.readInt() == VERSION) { "Unknown catalog format" }
            val identity = input.readText().ifEmpty { null }
            if (identity != null) require(UUID.fromString(identity).toString() == identity)
            val revision = input.readLong().also { require(it >= 0) }
            val count = input.readInt().also { require(it in 0..MAX_MODELS) }
            val models = List(count) { EncodedModel(input.readText(), input.readText(), input.readText()) }
            require(input.read() == -1)
            require(identity != null || models.isEmpty())
            require(models.all { it.id.isNotBlank() && it.id == it.id.trim() })
            require(models.map { it.id }.toSet().size == models.size)
            return Document(identity, revision, models)
        }
    }

    /** Returns false only BEFORE rename. Cancellation after rename is unambiguously success. */
    private fun writeDocument(document: Document, isCancelled: () -> Boolean): Boolean {
        require(document.models.size <= MAX_MODELS)
        val bytes = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(MAGIC); data.writeInt(VERSION)
                data.writeText(document.identity.orEmpty()); data.writeLong(document.revision)
                data.writeInt(document.models.size)
                document.models.forEach { data.writeText(it.id); data.writeText(it.name); data.writeText(it.record) }
            }
        }.toByteArray()
        require(bytes.size <= MAX_FILE_BYTES)
        val pending = File(file.path + ".pending")
        try {
            FileOutputStream(pending).use { output -> output.write(bytes); output.fd.sync() }
            if (isCancelled()) return false
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            // Process-crash atomicity only: no directory fsync, so not a power-loss guarantee.
            // Do not check cancellation, decode, publish, or perform fallible work after rename.
            return true
        } finally {
            // Cleanup must not turn an already committed operation into a reported failure.
            runCatching { pending.delete() }
        }
    }

    private fun <R> exclusive(action: () -> R): R = synchronized(monitor) {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "Catalog directory unavailable" }
        // selectModel may call save; nested host operations share the already held OS lease.
        val paths = heldPaths.get()
        if (file.path in paths) return@synchronized action()
        val channel = RandomAccessFile(File(file.path + ".lock"), "rw").channel
        try {
            val lock = channel.lock()
            paths.add(file.path)
            try { action() } finally {
                paths.remove(file.path)
                runCatching { lock.release() }
            }
        } finally { runCatching { channel.close() } }
    }

    private fun DataOutputStream.writeText(text: String) {
        require(text.length <= MAX_FIELD_BYTES)
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_FIELD_BYTES && size().toLong() + 4 + bytes.size <= MAX_FILE_BYTES)
        writeInt(bytes.size); write(bytes)
    }
    private fun DataInputStream.readText(): String {
        val size = readInt().also { require(it in 0..MAX_FIELD_BYTES && it <= available()) }
        val bytes = ByteArray(size).also { readFully(it) }
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }
    private fun nextRevision(revision: Long): Long = Math.addExact(revision, 1L)
    private fun cancelled(check: () -> Boolean) = Thread.currentThread().isInterrupted || check()

    companion object {
        private const val MAGIC = 0x44415955
        private const val VERSION = 1
        private const val MAX_MODELS = 10000
        private const val MAX_FIELD_BYTES = 1024 * 1024
        private const val MAX_FILE_BYTES = 16 * 1024 * 1024
        private val monitors = ConcurrentHashMap<String, Any>()
        private val heldPaths = ThreadLocal.withInitial { mutableSetOf<String>() }
    }
}
