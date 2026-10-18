package app.daiyu.android.skills

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Collections
import java.util.UUID

/** A snapshot is detached from disk: later enable/install changes cannot change an in-flight turn. */
data class SkillSnapshot(val skills: List<EnabledSkill>) {
    fun promptIndex(): String = skills.joinToString("\n") { "${it.name}: ${it.description}" }
}
data class EnabledSkill(val name: String, val description: String, val revision: String, val instructions: String)
data class InstalledSkill(val name: String, val description: String, val revision: String, val enabled: Boolean, val source: String?, val commit: String?)
data class SkillBundle(val files: Map<String, ByteArray>, val source: String? = null, val commit: String? = null)
internal data class SkillBundlePreview(val header: SkillHeader, val revision: String, val compatibility: SkillCompatibilityResult)
class SkillConflict(val skillName: String, val currentRevision: String?) : IllegalStateException("skill_conflict")
/** Publication succeeded; callers must not retry the write or describe it as rolled back. */
class SkillCleanupPending(val installedSkills: List<InstalledSkill>, cause: Throwable) : IllegalStateException("skill_cleanup_pending", cause)

class SkillRemovalCleanupPending(val removedSkill: InstalledSkill, cause: Throwable) : IllegalStateException("skill_removal_cleanup_pending", cause)

/** Dedicated app-private store; never points at the repository's own skills directory. */
class SkillStore(private val root: File, builtinNames: Set<String> = emptySet()) {
    private val builtinNames = builtinNames.toSet()
    constructor(context: Context) : this(File(context.filesDir, "runtime-skills-v1"))
    companion object {
        const val MAX_FILE_BYTES = 256 * 1024
        const val MAX_SKILL_BYTES = 8 * 1024 * 1024
        const val MAX_FILES = 256
        const val MAX_SKILLS = 100
    }
    // Test-only deterministic crash/failure boundaries; never configured by model arguments.
    internal var transactionCheckpoint: (String) -> Unit = {}
    private val installed get() = File(root, "installed")
    private fun <T> access(block: () -> T): T = SkillFiles.locked(root) {
        require(!java.nio.file.Files.isSymbolicLink(installed.toPath())) { "unsafe_root" }
        require(installed.isDirectory || installed.mkdirs()) { "storage_unavailable" }
        recoverLocked()
        block()
    }
    fun recover() { access { Unit } }
    fun list(): List<InstalledSkill> = access { listLocked() }
    fun snapshot(): SkillSnapshot = access {
        SkillSnapshot(Collections.unmodifiableList(listLocked().filter { it.enabled }.map {
            val directory = File(installed, it.name)
            val metadata = JSONObject(SkillFiles.text(SkillFiles.readAtomic(SkillFiles.path(directory, ".skill.json"), MAX_FILE_BYTES)))
            val bytes = SkillFiles.read(SkillFiles.path(directory, "SKILL.md"), MAX_FILE_BYTES)
            require(SkillFiles.hash(bytes) == metadata.getJSONObject("files").getString("SKILL.md")) { "resource_changed" }
            val markdown = SkillFiles.text(bytes)
            SkillCompatibilityPolicy.requireAvailable(markdown)
            EnabledSkill(it.name, it.description, it.revision, markdown)
        }))
    }
    /** Names only: resolving run selection must not parse an unselected skill's metadata/body. */
    internal fun installedNamesForRun(): Set<String> = access {
        installed.listFiles().orEmpty().filter { it.isDirectory }.map { it.name }.toSet()
    }
    internal fun captureForRun(maxBytes: Int, selectedNames: Set<String>? = null): List<CapturedSkill> {
        // A previous store may exhaust the budget; only an empty/disabled selection can fit zero.
        require(maxBytes in 0..SkillRunSession.MAX_CAPTURE_BYTES) { "invalid_limit" }
        val selected = selectedNames?.toSet()
        if (selected != null && selected.isEmpty()) return emptyList()
        return access {
            var total = 0L
            val captured = listLocked(selected).filter { it.enabled }.map { info ->
                val directory = File(installed, info.name)
                val metadata = JSONObject(SkillFiles.text(SkillFiles.readAtomic(SkillFiles.path(directory, ".skill.json"), MAX_FILE_BYTES)))
                val files = metadata.getJSONObject("files")
                require(files.length() in 1..MAX_FILES) { "invalid_skill_store" }
                val hashes = files.keys().asSequence().associateWith { path ->
                    require(path.split('/').none { it.startsWith('.') }) { "unsafe_path" }
                    SkillFiles.path(directory, path)
                    files.getString(path).also { require(it.matches(Regex("[a-f0-9]{64}"))) { "invalid_skill_store" } }
                }
                val bytes = SkillFiles.read(SkillFiles.path(directory, "SKILL.md"), MAX_FILE_BYTES)
                require(SkillFiles.hash(bytes) == hashes["SKILL.md"]) { "resource_changed" }
                val markdown = SkillFiles.text(bytes)
                SkillCompatibilityPolicy.requireAvailable(markdown)
                total += bytes.size + info.description.toByteArray(Charsets.UTF_8).size +
                    hashes.entries.sumOf { it.key.toByteArray(Charsets.UTF_8).size.toLong() + it.value.length } + info.name.length + info.revision.length
                require(total <= maxBytes) { "snapshot_too_large" }
                CapturedSkill(info.name, info.description, info.revision, markdown,
                    Collections.unmodifiableMap(hashes), total.toInt())
            }
            Collections.unmodifiableList(captured)
        }
    }
    internal fun readCapturedResource(name: String, path: String, revision: String, hash: String, maxBytes: Int): String {
        val text = readResource(name, path, revision, maxBytes)
        require(SkillFiles.hash(text.toByteArray(Charsets.UTF_8)) == hash) { "resource_changed" }
        return text
    }
    fun readResource(name: String, relativePath: String, expectedRevision: String, maxBytes: Int = 64 * 1024): String = access {
        require(maxBytes in 1..MAX_FILE_BYTES) { "invalid_limit" }
        val info = infoLocked(name) ?: error("skill_not_found")
        require(info.enabled) { "skill_disabled" }
        if (info.revision != expectedRevision) throw SkillConflict(name, info.revision)
        require(!relativePath.split('/').any { it.startsWith('.') }) { "unsafe_path" }
        val directory = File(installed, name)
        val metadata = JSONObject(SkillFiles.text(SkillFiles.readAtomic(SkillFiles.path(directory, ".skill.json"), MAX_FILE_BYTES)))
        require(metadata.getJSONObject("files").has(relativePath)) { "resource_not_found" }
        val skillBytes = SkillFiles.read(SkillFiles.path(directory, "SKILL.md"), MAX_FILE_BYTES)
        require(SkillFiles.hash(skillBytes) == metadata.getJSONObject("files").getString("SKILL.md")) { "resource_changed" }
        SkillCompatibilityPolicy.requireAvailable(SkillFiles.text(skillBytes))
        val bytes = SkillFiles.read(SkillFiles.path(directory, relativePath), maxBytes)
        require(SkillFiles.hash(bytes) == metadata.getJSONObject("files").getString(relativePath)) { "resource_changed" }
        SkillFiles.text(bytes)
    }
    fun setEnabled(name: String, enabled: Boolean, expectedRevision: String): InstalledSkill = access {
        val info = infoLocked(name) ?: error("skill_not_found")
        if (info.revision != expectedRevision) throw SkillConflict(name, info.revision)
        val file = File(File(installed, name), ".skill.json")
        val json = JSONObject(SkillFiles.text(SkillFiles.readAtomic(file, MAX_FILE_BYTES)))
        json.put("enabled", enabled)
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_FILE_BYTES) { "metadata_too_large" }
        SkillFiles.atomic(file, bytes)
        info.copy(enabled = enabled)
    }
    /** Exact revision removal shares the installation journal and crash-recovery rules. */
    fun remove(name: String, expectedRevision: String): InstalledSkill = access {
        val previous = infoLocked(name)
        if (previous == null || previous.revision != expectedRevision) throw SkillConflict(name, previous?.revision)
        val txn = File(root, ".txn-${UUID.randomUUID()}")
        val journalFile = File(root, ".transaction.json")
        var committed = false
        try {
            check(txn.mkdir())
            check(File(txn, "stage").mkdir())
            val backup = File(txn, "backup"); check(backup.mkdir())
            transactionCheckpoint("before_journal")
            val entries = JSONArray().put(JSONObject().put("name", name).put("had_previous", true))
            val journal = JSONObject().put("transaction", txn.name).put("entries", entries).put("committed", false)
            SkillFiles.atomic(journalFile, journal.toString().toByteArray(Charsets.UTF_8))
            SkillFiles.move(File(installed, name), File(backup, name))
            transactionCheckpoint("after_backup")
            transactionCheckpoint("before_commit")
            journal.put("committed", true)
            SkillFiles.atomic(journalFile, journal.toString().toByteArray(Charsets.UTF_8))
            committed = true
            transactionCheckpoint("after_commit")
        } catch (failure: Throwable) {
            if (!committed && (journalFile.exists() || File(root, ".transaction.json.bak").exists())) {
                try { committed = JSONObject(SkillFiles.text(SkillFiles.readAtomic(journalFile, MAX_FILE_BYTES))).getBoolean("committed") }
                catch (checkFailure: Throwable) { failure.addSuppressed(checkFailure); throw failure }
            }
            if (committed) throw SkillRemovalCleanupPending(previous, failure)
            try { if (journalFile.exists() || File(root, ".transaction.json.bak").exists()) recoverLocked() else SkillFiles.remove(txn) }
            catch (rollbackFailure: Throwable) { failure.addSuppressed(rollbackFailure) }
            throw failure
        }
        try { transactionCheckpoint("before_cleanup"); recoverLocked() }
        catch (failure: Throwable) { throw SkillRemovalCleanupPending(previous, failure) }
        previous
    }
    /** Missing expected revision means create-only. Replacement requires the exact current revision. */
    fun install(inputBundles: List<SkillBundle>, expectedRevisions: Map<String, String> = emptyMap(), expectedIncomingRevisions: Map<String, String>? = null): List<InstalledSkill> = access {
        require(inputBundles.size in 1..MAX_SKILLS) { "invalid_selection" }
        // Bound every selected bundle before allocating detached byte arrays; recheck the copies below.
        inputBundles.forEach(::validateBundleLimits)
        val bundles = inputBundles.map { it.copy(files = it.files.mapValues { (_, bytes) -> bytes.copyOf() }) }
        require(bundles.size in 1..MAX_SKILLS) { "invalid_selection" }
        val prepared = bundles.map(::validateBundle)
        // Re-evaluate detached incoming bytes on every install, before any journal/staging write.
        prepared.forEach { SkillCompatibilityPolicy.requireAvailable(it.compatibility) }
        val names = prepared.map { it.header.name }
        require(names.none { it in builtinNames }) { "builtin_conflict" }
        if (expectedIncomingRevisions != null) {
            require(expectedIncomingRevisions.keys == names.toSet() && prepared.all { expectedIncomingRevisions[it.header.name] == it.revision }) { "incoming_revision_conflict" }
        }
        require(names.distinct().size == names.size && expectedRevisions.keys.all { it in names }) { "duplicate_skill" }
        val old = names.associateWith(::infoLocked)
        names.forEach { name ->
            val current = old[name]?.revision
            if (current != expectedRevisions[name]) throw SkillConflict(name, current)
        }
        require(listLocked().map { it.name }.union(names).size <= MAX_SKILLS) { "too_many_skills" }
        // Final serialized UTF-8 metadata is checked before any journal or installed-tree mutation.
        val metadata = prepared.mapIndexed { index, (header, revision) -> metadataBytes(bundles[index], header, revision) }
        val result = prepared.mapIndexed { index, (header, revision) ->
            InstalledSkill(header.name, header.description, revision, true, bundles[index].source, bundles[index].commit)
        }
        val txn = File(root, ".txn-${UUID.randomUUID()}")
        val journalFile = File(root, ".transaction.json")
        var committed = false
        try {
            check(txn.mkdir())
            val stage = File(txn, "stage"); check(stage.mkdir())
            val backup = File(txn, "backup"); check(backup.mkdir())
            val entries = JSONArray()
            prepared.forEachIndexed { index, (header, _) ->
                val target = File(stage, header.name); check(target.mkdir())
                bundles[index].files.toSortedMap().forEach { (path, bytes) ->
                    val file = SkillFiles.path(target, path)
                    check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
                    SkillFiles.atomic(file, bytes)
                }
                SkillFiles.atomic(File(target, ".skill.json"), metadata[index])
                entries.put(JSONObject().put("name", header.name).put("had_previous", old[header.name] != null))
            }
            transactionCheckpoint("before_journal")
            val journal = JSONObject().put("transaction", txn.name).put("entries", entries).put("committed", false)
            SkillFiles.atomic(journalFile, journal.toString().toByteArray(Charsets.UTF_8))
            names.forEach { name ->
                val target = File(installed, name)
                if (target.exists()) SkillFiles.move(target, File(backup, name))
                transactionCheckpoint("after_backup")
                SkillFiles.move(File(stage, name), target)
                transactionCheckpoint("after_publish")
            }
            transactionCheckpoint("before_commit")
            journal.put("committed", true)
            SkillFiles.atomic(journalFile, journal.toString().toByteArray(Charsets.UTF_8))
            committed = true
            transactionCheckpoint("after_commit")
        } catch (failure: Throwable) {
            // Resolve the durable marker even if the marker write itself reported a failure.
            if (!committed && (journalFile.exists() || File(root, ".transaction.json.bak").exists())) {
                try { committed = JSONObject(SkillFiles.text(SkillFiles.readAtomic(journalFile, MAX_FILE_BYTES))).getBoolean("committed") }
                catch (checkFailure: Throwable) { failure.addSuppressed(checkFailure); throw failure }
            }
            if (committed) throw SkillCleanupPending(result, failure)
            try { if (journalFile.exists() || File(root, ".transaction.json.bak").exists()) recoverLocked() else SkillFiles.remove(txn) }
            catch (rollbackFailure: Throwable) { failure.addSuppressed(rollbackFailure) }
            throw failure
        }
        // Cleanup is explicitly outside the rollback region. A failure here is a committed result.
        try { transactionCheckpoint("before_cleanup"); recoverLocked() }
        catch (failure: Throwable) { throw SkillCleanupPending(result, failure) }
        result
    }
    fun preview(bundle: SkillBundle): Pair<SkillHeader, String> = previewForManagement(bundle).let {
        SkillCompatibilityPolicy.requireAvailable(it.compatibility)
        it.header to it.revision
    }
    /** Structured denial is displayed by settings, never treated as install approval. */
    internal fun previewForManagement(bundle: SkillBundle): SkillBundlePreview = access {
        validateBundleLimits(bundle)
        validateBundle(bundle.copy(files = bundle.files.mapValues { (_, bytes) -> bytes.copyOf() }))
    }
    private fun validateBundleLimits(bundle: SkillBundle) {
        require(bundle.files.size in 1..MAX_FILES) { "skill_too_large" }
        var total = 0L
        bundle.files.values.forEach { bytes ->
            require(bytes.size <= MAX_FILE_BYTES) { "unsafe_resource" }
            total += bytes.size
            require(total <= MAX_SKILL_BYTES) { "skill_too_large" }
        }
    }
    private fun validateBundle(bundle: SkillBundle): SkillBundlePreview {
        validateBundleLimits(bundle)
        bundle.files.forEach { (path, bytes) ->
            require(path.length <= 512 && path.split('/').none { it.startsWith('.') } && bytes.size <= MAX_FILE_BYTES) { "unsafe_resource" }
            SkillFiles.path(installed, path)
        }
        // Also disallow a file being the parent of another file.
        SkillFiles.validateResourceTree(bundle.files.keys)
        val markdown = SkillFiles.text(bundle.files["SKILL.md"] ?: error("missing_skill"))
        val header = SkillFrontmatter.parse(markdown)
        val revision = SkillFiles.hash(bundle.files.toSortedMap().entries.joinToString("\n") { "${it.key}\u0000${SkillFiles.hash(it.value)}" }.toByteArray(Charsets.UTF_8))
        metadataBytes(bundle, header, revision)
        return SkillBundlePreview(header, revision, SkillCompatibilityPolicy.evaluate(header, markdown))
    }
    private fun metadataBytes(bundle: SkillBundle, header: SkillHeader, revision: String): ByteArray {
        require(bundle.source == null || (bundle.source.length <= 4096 && bundle.source.toByteArray(Charsets.UTF_8).size <= 4096)) { "source_too_large" }
        require(bundle.commit == null || bundle.commit.matches(Regex("[a-f0-9]{40}"))) { "invalid_commit" }
        val hashes = JSONObject()
        bundle.files.toSortedMap().forEach { (path, bytes) -> hashes.put(path, SkillFiles.hash(bytes)) }
        val meta = JSONObject().put("name", header.name).put("description", header.description)
            .put("revision", revision).put("enabled", true)
            .put("source", bundle.source ?: JSONObject.NULL).put("commit", bundle.commit ?: JSONObject.NULL).put("files", hashes)
        val bytes = meta.toString().toByteArray(Charsets.UTF_8)
        // false is one byte longer than true; ensure settings can disable even a boundary-size skill.
        require(bytes.size.toLong() + 1 <= MAX_FILE_BYTES) { "metadata_too_large" }
        return bytes
    }
    private fun listLocked(selectedNames: Set<String>? = null): List<InstalledSkill> = installed.listFiles().orEmpty()
        .filter { it.isDirectory && (selectedNames == null || it.name in selectedNames) }.map {
            infoLocked(it.name) ?: error("invalid_skill_store")
        }.sortedBy { it.name }.also { require(it.size <= MAX_SKILLS) { "too_many_skills" } }
    private fun infoLocked(name: String): InstalledSkill? {
        require(name.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*")) && name.length <= 64) { "invalid_name" }
        val directory = SkillFiles.path(installed, name)
        if (!directory.exists()) return null
        val json = JSONObject(SkillFiles.text(SkillFiles.readAtomic(SkillFiles.path(directory, ".skill.json"), MAX_FILE_BYTES)))
        require(json.getString("name") == name) { "invalid_skill_store" }
        return InstalledSkill(name, json.getString("description"), json.getString("revision"), json.getBoolean("enabled"),
            if (json.isNull("source")) null else json.getString("source"), if (json.isNull("commit")) null else json.getString("commit"))
    }
    private fun recoverLocked() {
        val journalFile = File(root, ".transaction.json")
        // AtomicFile may have only a backup after interrupted writing.
        val atomic = android.util.AtomicFile(journalFile)
        require(!java.nio.file.Files.isSymbolicLink(journalFile.toPath()) && !java.nio.file.Files.isSymbolicLink(File(root, ".transaction.json.bak").toPath()) && !java.nio.file.Files.isSymbolicLink(File(root, ".transaction.json.new").toPath())) { "unsafe_root" }
        if (journalFile.exists() || File(root, ".transaction.json.bak").exists()) {
            val journal = JSONObject(SkillFiles.text(SkillFiles.readAtomic(journalFile, MAX_FILE_BYTES)))
            val transaction = journal.getString("transaction")
            require(transaction.matches(Regex("\\.txn-[a-f0-9-]{36}"))) { "invalid_journal" }
            val txn = SkillFiles.path(root, transaction)
            val entries = journal.getJSONArray("entries")
            require(entries.length() in 1..MAX_SKILLS) { "invalid_journal" }
            if (!journal.getBoolean("committed")) {
                for (i in 0 until entries.length()) {
                    val entry = entries.getJSONObject(i)
                    val name = entry.getString("name")
                    require(name.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*")) && name.length <= 64) { "invalid_journal" }
                    val target = SkillFiles.path(installed, name)
                    val saved = SkillFiles.path(txn, "backup/$name")
                    val staged = SkillFiles.path(txn, "stage/$name")
                    if (saved.exists()) { SkillFiles.remove(target); SkillFiles.move(saved, target) }
                    else if (!entry.getBoolean("had_previous") && !staged.exists()) SkillFiles.remove(target)
                }
            }
            // Delete the journal before backup cleanup: rollback remains idempotent across crashes.
            atomic.delete()
            check(!journalFile.exists() && !File(root, ".transaction.json.bak").exists() && !File(root, ".transaction.json.new").exists()) { "storage_unavailable" }
            SkillFiles.remove(txn)
        }
        root.listFiles().orEmpty().filter { it.name.startsWith(".txn-") }.forEach(SkillFiles::remove)
    }
}
