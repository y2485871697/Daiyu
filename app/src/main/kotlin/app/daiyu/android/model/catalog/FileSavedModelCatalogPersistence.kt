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
 * UUID allocation happens at explicit registration/migration [register] or account replacement
 * [replaceRegisteredProvider]. A plain [configurationChange] only invalidates the revision.
 * Deletion is durable; re-registration gets a new UUID.
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
) : SavedModelCatalogPersistence<T>, SavedModelCatalogSelectionPersistence<T> {
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

    /** Read a settings snapshot under the same lease used by every configuration mutation.
     * Includes revisions of unregistered catalogs; reading never registers or advances it.
     */
    internal fun <R> readConfiguration(block: (Long) -> R): R = exclusive {
        block(readDocument()?.revision ?: -1)
    }

    /** Runtime lookup does not register a directory or decode unrelated records. */
    internal fun readRegisteredModel(id: String): SavedCatalogModel<T>? = exclusive {
        val document = readDocument()?.takeIf { it.identity != null } ?: return@exclusive null
        val encoded = checkNotNull(document.models.singleOrNull { it.id == id }) {
            "当前模型不在本地目录中，请重新选择模型"
        }
        SavedCatalogModel(encoded.id, encoded.name, codec.decode(encoded.record))
    }

    override fun current(): SavedModelCatalogSnapshot<T>? = exclusive {
        readDocument()?.takeIf { it.identity != null }?.let(::snapshot)
    }

    override fun read(providerIdentity: String): SavedModelCatalogSnapshot<T>? = exclusive {
        readDocument()?.takeIf { it.identity == providerIdentity }?.let(::snapshot)
    }

    override fun <R> consumeSelection(
        selection: CatalogModelSelection<T>,
        change: (SavedCatalogModel<T>) -> R,
    ): R = exclusive {
        val old = readDocument()
        check(old != null && old.identity == selection.providerIdentity && old.revision == selection.expectedRevision) {
            "模型目录已变化，请重新打开选择器"
        }
        val encoded = old.models.singleOrNull { it.id == selection.record.id }
        check(encoded != null) { "本地模型已删除" }
        val record = SavedCatalogModel(encoded.id, encoded.name, codec.decode(encoded.record))
        check(record == selection.record) { "本地模型记录已变化" }
        // Invalidate BEFORE host writes, including no-op selection and failed host saves.
        writeDocument(old.copy(revision = nextRevision(old.revision))) { false }
        change(record)
    }

    /** Ticket validation and the local edit share one catalog lease. */
    fun editSelection(
        selection: CatalogModelSelection<T>,
        displayName: String?,
        isCancelled: () -> Boolean,
    ): CatalogLocalEditResult = try {
        exclusive {
            if (cancelled(isCancelled)) return@exclusive CatalogLocalEditResult.CANCELLED
            if (displayName != null) require(displayName.isNotBlank() && displayName == displayName.trim() &&
                displayName.length <= 200 && displayName.none { it.code < 0x20 || it.code == 0x7f })
            val old = readDocument()
            if (old == null || old.identity != selection.providerIdentity || old.revision != selection.expectedRevision)
                return@exclusive CatalogLocalEditResult.STALE
            val encoded = old.models.singleOrNull { it.id == selection.record.id }
                ?: return@exclusive CatalogLocalEditResult.STALE
            if (SavedCatalogModel(encoded.id, encoded.name, codec.decode(encoded.record)) != selection.record)
                return@exclusive CatalogLocalEditResult.STALE
            val models = if (displayName == null) old.models.filterNot { it.id == encoded.id }
                else old.models.map { if (it.id == encoded.id) it.copy(name = displayName) else it }
            // Preserve encoded model parameters and every unselected record verbatim.
            if (writeDocument(old.copy(revision = nextRevision(old.revision), models = models)) { cancelled(isCancelled) })
                CatalogLocalEditResult.APPLIED else CatalogLocalEditResult.CANCELLED
        }
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        CatalogLocalEditResult.CANCELLED
    } catch (_: java.util.concurrent.CancellationException) {
        CatalogLocalEditResult.CANCELLED
    } catch (_: Exception) {
        if (Thread.currentThread().isInterrupted) CatalogLocalEditResult.CANCELLED else CatalogLocalEditResult.FAILED
    }

    fun deleteSelections(
        selections: List<CatalogModelSelection<T>>,
        isCancelled: () -> Boolean,
    ): CatalogLocalEditResult = try {
        exclusive {
            if (cancelled(isCancelled)) return@exclusive CatalogLocalEditResult.CANCELLED
            require(selections.isNotEmpty())
            val first = selections.first()
            val ids = selections.map { it.record.id }.toSet()
            require(ids.size == selections.size)
            val old = readDocument()
            if (old == null || old.identity != first.providerIdentity || old.revision != first.expectedRevision ||
                selections.any { it.providerIdentity != old.identity || it.expectedRevision != old.revision })
                return@exclusive CatalogLocalEditResult.STALE
            val records = old.models.associateBy { it.id }
            if (selections.any { selection ->
                    val encoded = records[selection.record.id]
                    encoded == null || SavedCatalogModel(encoded.id, encoded.name, codec.decode(encoded.record)) != selection.record
                }) return@exclusive CatalogLocalEditResult.STALE
            val next = old.copy(revision = nextRevision(old.revision), models = old.models.filterNot { it.id in ids })
            if (writeDocument(next) { cancelled(isCancelled) }) CatalogLocalEditResult.APPLIED
            else CatalogLocalEditResult.CANCELLED
        }
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        CatalogLocalEditResult.CANCELLED
    } catch (_: java.util.concurrent.CancellationException) {
        CatalogLocalEditResult.CANCELLED
    } catch (_: Exception) {
        if (Thread.currentThread().isInterrupted) CatalogLocalEditResult.CANCELLED else CatalogLocalEditResult.FAILED
    }

    fun replaceSelection(selection: CatalogModelSelection<T>, replacement: SavedCatalogModel<T>,
        isCancelled: () -> Boolean,
    ): CatalogLocalEditResult = try {
        exclusive {
            if (cancelled(isCancelled)) return@exclusive CatalogLocalEditResult.CANCELLED
            require(replacement.id == selection.record.id)
            val old = readDocument()
            if (old == null || old.identity != selection.providerIdentity || old.revision != selection.expectedRevision)
                return@exclusive CatalogLocalEditResult.STALE
            val previous = old.models.singleOrNull { it.id == selection.record.id }
                ?: return@exclusive CatalogLocalEditResult.STALE
            if (SavedCatalogModel(previous.id, previous.name, codec.decode(previous.record)) != selection.record)
                return@exclusive CatalogLocalEditResult.STALE
            val encoded = encodeModels(listOf(replacement)).single()
            val models = old.models.map { if (it.id == encoded.id) encoded else it }
            if (writeDocument(old.copy(revision = nextRevision(old.revision), models = models)) { cancelled(isCancelled) })
                CatalogLocalEditResult.APPLIED else CatalogLocalEditResult.CANCELLED
        }
    } catch (_: Exception) {
        if (cancelled(isCancelled)) CatalogLocalEditResult.CANCELLED else CatalogLocalEditResult.FAILED
    }

    /**
     * Host save synchronization, NOT import. Only one record is re-encoded; every other record
     * remains byte-for-byte intact. update receives the existing local UUID/name when present.
     * Called inside configurationChange, after the host commit, with host compensation on failure.
     */
    fun updateRegisteredModel(id: String, update: (SavedCatalogModel<T>?) -> SavedCatalogModel<T>): Unit = exclusive {
        val old = readDocument() ?: return@exclusive
        if (old.identity == null) return@exclusive
        val previous = old.models.singleOrNull { it.id == id }
        val decoded = previous?.let { SavedCatalogModel(it.id, it.name, codec.decode(it.record)) }
        val replacement = update(decoded).also { require(it.id == id) }
        val encoded = encodeModels(listOf(replacement)).single()
        if (previous == encoded) return@exclusive
        val models = if (previous == null) old.models + encoded
            else old.models.map { if (it.id == id) encoded else it }
        writeDocument(old.copy(revision = nextRevision(old.revision), models = models)) { false }
        Unit
    }

    fun updateExistingRegisteredModel(id: String, update: (SavedCatalogModel<T>) -> SavedCatalogModel<T>): Unit = exclusive {
        val old = readDocument() ?: return@exclusive
        if (old.identity == null) return@exclusive
        val previous = old.models.singleOrNull { it.id == id } ?: return@exclusive
        val record = SavedCatalogModel(previous.id, previous.name, codec.decode(previous.record))
        updateRegisteredModel(id) { update(record) }
    }

    /** A new connection/account in this host slot must never inherit the old account's records. */
    fun replaceRegisteredProvider(initialModels: List<SavedCatalogModel<T>>): Unit = exclusive {
        val old = readDocument() ?: return@exclusive
        if (old.identity == null) return@exclusive
        val next = Document(UUID.randomUUID().toString(), nextRevision(old.revision), encodeModels(initialModels))
        writeDocument(next) { false }
        Unit
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
     * This boundary only advances revision; any explicit host record synchronization is separate.
     * A failed host save can conservatively advance revision without making old tickets valid.
     * No lock inversion: acquire this boundary BEFORE the host's existing configuration lock.
     */
    fun <R> configurationChange(change: () -> R): R = exclusive {
        val old = readDocument()
        val next = if (old == null) Document(null, 0, emptyList())
            else old.copy(revision = nextRevision(old.revision))
        writeDocument(next) { false }
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
