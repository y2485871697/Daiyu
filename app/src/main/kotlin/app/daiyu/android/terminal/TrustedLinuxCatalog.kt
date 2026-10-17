package app.daiyu.android.terminal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.Collections
import java.util.UUID

/** An explicit approval target, not an installation source or a grant inferred from selection. */
class FrozenLinuxApproval internal constructor(configuration: LinuxInstallation) {
    val installation: LinuxInstallation = configuration.copy(
        sharedMounts = Collections.unmodifiableMap(LinkedHashMap(configuration.sharedMounts.toSortedMap())))
}

/**
 * App-private, installed-files-only authority. Construction/freezing does no filesystem I/O.
 * All disk work runs on IO; the returned list is for the NEXT application-scoped UI owner.
 * Keep one catalog root per app. Never point it at a user-selected directory or selection root.
 */
class TrustedLinuxCatalog internal constructor(
    private val directory: File,
    private val directorySync: (File) -> Unit,
    private val beforeApprovalGate: () -> Unit = {},
    private val symbolicLink: (java.nio.file.Path) -> Boolean = Files::isSymbolicLink,
    private val fileKey: (java.nio.file.Path) -> Any? = ::strictLinuxFileKey,
    private val chrootRoot: File? = null,
) {
    constructor(directory: File) : this(directory, TerminalDurability::syncDirectory)
    constructor(directory: File, chrootRoot: File) : this(directory, TerminalDurability::syncDirectory, chrootRoot = chrootRoot)

    private data class Record(val installation: LinuxInstallation, val active: Boolean)
    private val gate = synchronized(gates) {
        val key = directory.absoluteFile.toPath().normalize().toString()
        gates[key] ?: run {
            check(gates.size < 128) { "linux_catalog_store_limit" }
            Any().also { gates[key] = it }
        }
    }
    private val file get() = File(directory, "trusted-linux-catalog.json")
    // LinuxEnvironmentStore owns the actual validator. Its selection here is disposable scratch,
    // NEVER the user's selection, NEVER read to establish catalog authority.
    private val validationDirectory get() = File(directory, "validation-only")
    private var validator: LinuxEnvironmentStore? = null

    /** Call only to prepare the exact confirmation UI. Known hashes must come from the caller. */
    fun freezeForApproval(configuration: LinuxInstallation): FrozenLinuxApproval {
        // Always issue a fresh generation, even for a changed cwd, label, digest or mount list.
        val frozen = freeze(configuration.copy(generation = UUID.randomUUID().toString()))
        checkMetadata(frozen)
        return FrozenLinuxApproval(frozen)
    }

    /** Missing catalog means no authority. Malformed or unavailable active entries throw, not []. */
    suspend fun load(): List<LinuxInstallation> = withContext(Dispatchers.IO) {
        synchronized(gate) {
            val records = read()
            records.filter { it.active }.forEach { validate(it.installation) }
            activeList(records)
        }
    }

    /** Invoke only after the user/parent explicitly confirms this frozen object's FULL contents. */
    suspend fun approve(
        approval: FrozenLinuxApproval,
        admit: () -> Boolean = { true },
    ): List<LinuxInstallation> = approveRecord(approval, admit, acceptRecorded = false)

    /** Retry an installed request without resurrecting a concurrently revoked generation. */
    internal suspend fun approveInstallation(approval: FrozenLinuxApproval, admit: () -> Boolean): List<LinuxInstallation> =
        approveRecord(approval, admit, acceptRecorded = true)

    private suspend fun approveRecord(approval: FrozenLinuxApproval, admit: () -> Boolean,
                                      acceptRecorded: Boolean): List<LinuxInstallation> = withContext(Dispatchers.IO) {
        beforeApprovalGate() // Test seam; no catalog IO has occurred yet.
        synchronized(gate) {
            // Exactly once, after dispatcher/gate waits and before the first catalog IO.
            // The caller atomically decides against cancellation; never holds its lock for IO.
            check(admit()) { "linux_catalog_admission_revoked" }
            val records = read()
            val value = approval.installation
            checkMetadata(value)
            val previous = records.singleOrNull { ref(it.installation) == ref(value) }
            if (previous != null) {
                check((previous.active || acceptRecorded) && previous.installation == value) { "linux_catalog_generation_conflict" }
            }
            val next = if (previous == null) records + Record(value, true) else records
            // Do not publish a partially usable list, nor replace a failed old approval.
            next.filter { it.active }.forEach { validate(it.installation) }
            // A visible record can be from a rename whose directory fsync failed. Even an
            // unchanged retry must confirm durability before acknowledging the approval.
            if (previous == null) publish(next) else directorySync(directory)
            activeList(next)
        }
    }

    /** Revokes authority only; never deletes installed files or clears/changes the user's selection. */
    suspend fun remove(reference: LinuxGenerationRef): List<LinuxInstallation> = withContext(Dispatchers.IO) {
        synchronized(gate) {
            val records = read()
            check(records.any { ref(it.installation) == reference }) { "linux_catalog_unknown_reference" }
            val next = records.map { if (ref(it.installation) == reference) it.copy(active = false) else it }
            // Permit revoking a broken entry, but do not return other broken active entries.
            next.filter { it.active }.forEach { validate(it.installation) }
            // This also covers the final active entry: there may be no validation work
            // left, but a visible tombstone alone does not confirm the prior rename.
            if (next != records) publish(next) else directorySync(directory)
            activeList(next)
        }
    }

    /** A matching tombstone is also a completed registration, never permission to resurrect it. */
    internal suspend fun registrationRecorded(approval: FrozenLinuxApproval): Boolean = withContext(Dispatchers.IO) {
        synchronized(gate) {
            val previous = read().singleOrNull { ref(it.installation) == ref(approval.installation) }
            if (previous == null) false else {
                check(previous.installation == approval.installation) { "linux_catalog_generation_conflict" }
                // Confirm even a visible record left by a failed directory sync before retiring.
                directorySync(directory)
                true
            }
        }
    }

    private fun activeList(records: List<Record>): List<LinuxInstallation> =
        Collections.unmodifiableList(records.filter { it.active }.map { freeze(it.installation) })

    private fun validate(value: LinuxInstallation) {
        checkMetadata(value)
        securePath(directory)
        securePath(validationDirectory)
        securePath(File(validationDirectory, "selected-linux.json"))
        securePath(File(validationDirectory, "selected-linux.json.tmp"))
        val store = validator ?: LinuxEnvironmentStore(validationDirectory, directorySync, fileKey, chrootRoot).also { validator = it }
        // select validates paths, readable shell containment, bind targets, known helper digests,
        // file identities and metadata limits. It does not execute any of these files.
        store.select(value)
    }

    private fun read(): List<Record> {
        securePath(directory)
        securePath(file)
        if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) return emptyList()
        check(file.isFile && file.length() in 1..MAX_BYTES.toLong()) { "linux_catalog_corrupt" }
        val bytes = file.inputStream().use { it.readBytesBounded() }
        val text = bytes.toString(Charsets.UTF_8)
        val root = JSONObject(text)
        val version = root.getInt("version")
        check(version == 1 || version == 2) { "linux_catalog_version" }
        val entries = root.getJSONArray("records")
        check(entries.length() <= MAX_RECORDS) { "linux_catalog_limit" }
        val records = (0 until entries.length()).map { index ->
            val record = entries.getJSONObject(index)
            val installation = freeze(LinuxInstallationJson.decode(record.getJSONObject("installation"), version))
            checkMetadata(installation)
            Record(installation, record.getBoolean("active"))
        }
        check(records.map { ref(it.installation) }.toSet().size == records.size) { "linux_catalog_duplicate_reference" }
        // Only our canonical, complete schema is accepted. Also rejects unknown fields, duplicate
        // object keys (including Android JSONObject's last-key-wins behavior), and coercions.
        check(encode(records, version).contentEquals(bytes)) { "linux_catalog_noncanonical" }
        return records
    }

    private fun publish(records: List<Record>) {
        check(records.size <= MAX_RECORDS) { "linux_catalog_limit" }
        val bytes = encode(records)
        check(bytes.size <= MAX_BYTES) { "linux_catalog_limit" }
        securePath(directory)
        securePath(file)
        securePath(File(directory, file.name + ".tmp"))
        TerminalDurability.ensureDirectory(directory, directorySync)
        // Immutable old records (including revocations) are retained in every later snapshot.
        TerminalDurability.replace(file, bytes, directorySync = directorySync)
    }

    private fun encode(records: List<Record>, version: Int = 2): ByteArray {
        val array = JSONArray()
        records.forEach { record ->
            array.put(JSONObject().put("active", record.active)
                .put("installation", LinuxInstallationJson.encode(record.installation, version)))
        }
        return JSONObject().put("version", version).put("records", array).toString().toByteArray(Charsets.UTF_8)
    }

    private fun java.io.InputStream.readBytesBounded(): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            check(bytes.size() + count <= MAX_BYTES) { "linux_catalog_limit" }
            bytes.write(buffer, 0, count)
        }
        return bytes.toByteArray()
    }

    /** Reject links in the catalog's ancestry; assume app-private, cooperating writers. */
    private fun securePath(file: File) {
        require(file.isAbsolute) { "linux_catalog_absolute_directory_required" }
        require(file.toPath().normalize() == file.toPath()) { "linux_catalog_unsafe_path" }
        var cursor: File? = file
        while (cursor != null) {
            check(!symbolicLink(cursor.toPath())) { "linux_catalog_symlink" }
            cursor = cursor.parentFile
        }
    }

    companion object {
        private const val MAX_BYTES = 1024 * 1024
        private const val MAX_RECORDS = 256 // Includes tombstones; never recycle an old generation.
        private val gates = mutableMapOf<String, Any>()
        private val id = Regex("[A-Za-z0-9_-]{1,128}")
        private val hash = Regex("[a-f0-9]{64}")
        private fun ref(v: LinuxInstallation) = LinuxGenerationRef(v.environmentId, v.generation)
        private fun freeze(v: LinuxInstallation) = v.copy(
            sharedMounts = Collections.unmodifiableMap(LinkedHashMap(v.sharedMounts.toSortedMap())))

        private fun checkMetadata(v: LinuxInstallation) {
            require(v.distribution.matches(Regex("[A-Za-z0-9_-]{1,64}")))
            require(id.matches(v.environmentId) && id.matches(v.generation))
            require(v.environmentId.length + 1 + v.generation.length <= 128)
            hostPath(v.rootfs.path)
            when (v.executionMode) {
                LinuxExecutionMode.PROOT -> {
                    val helpers = v.requireProotFiles()
                    require(hash.matches(v.prootSha256) && hash.matches(v.loaderSha256))
                    listOf(helpers.executable.path, helpers.loader.path).forEach(::hostPath)
                }
                LinuxExecutionMode.CHROOT -> {
                    require(v.proot == null && v.loader == null && v.prootSha256.isEmpty() && v.loaderSha256.isEmpty())
                    require(v.guestWorkingDirectory == "/" && v.sharedMounts.isEmpty()) { "linux_chroot_mapping_not_supported" }
                }
            }
            guestPath(v.guestWorkingDirectory)
            require(v.sharedMounts.size <= 32)
            v.sharedMounts.forEach { (host, guest) -> hostPath(host); guestPath(guest) }
        }

        private fun hostPath(path: String) = LinuxPathSyntax.host(path)
        private fun guestPath(path: String) = LinuxPathSyntax.guest(path)

    }
}
