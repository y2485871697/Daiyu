package app.daiyu.android.skills

import android.os.Looper

/** Trusted settings surface only. This API is deliberately absent from the model tool catalog. */
class SkillManagement private constructor(private val registry: SkillToolRegistry) {
    companion object {
        fun create(registryFactory: () -> SkillToolRegistry): SkillManagement {
            check(Looper.myLooper() != Looper.getMainLooper()) { "background_thread_required" }
            return SkillManagement(registryFactory())
        }
    }
    data class Entry(val id: String, val skill: InstalledSkill, val builtin: Boolean)
    data class State(val enabled: Boolean, val entries: List<Entry>, val entriesUnavailable: Boolean = false)

    fun snapshot(): State {
        workerOnly()
        return synchronized(this) { registry.managementState() }
    }

    fun setGlobalEnabled(enabled: Boolean): State {
        workerOnly()
        return synchronized(this) {
            registry.enabled = enabled
            registry.managementState()
        }
    }

    fun setEnabled(id: String, enabled: Boolean, revision: String): State {
        workerOnly()
        return synchronized(this) {
            registry.setEnabled(id, enabled, revision)
            registry.managementState()
        }
    }

    class Removal internal constructor(val entry: Entry)
    sealed class RemovalResult {
        data class Removed(val entry: Entry, val cleanupPending: Boolean = false) : RemovalResult()
        data class Rejected(val code: String) : RemovalResult()
        object Unknown : RemovalResult()
    }
    private var pendingRemoval: Removal? = null

    fun previewRemoval(id: String, revision: String): Removal {
        workerOnly()
        return synchronized(this) {
            pendingRemoval = null; inspected = null; pending = null; pendingLocal = null
            val state = registry.managementState()
            check(!state.entriesUnavailable) { "skills_unavailable" }
            val entry = state.entries.singleOrNull { it.id == id } ?: throw SkillConflict(id, null)
            if (entry.skill.revision != revision) throw SkillConflict(id, entry.skill.revision)
            if (entry.builtin) throw SkillToolRegistry.ManagementBuiltinConflict()
            Removal(entry).also { pendingRemoval = it }
        }
    }

    fun remove(removal: Removal): RemovalResult {
        workerOnly()
        return synchronized(this) {
            if (pendingRemoval !== removal) return@synchronized RemovalResult.Rejected("stale_removal")
            pendingRemoval = null
            try {
                registry.removeForManagement(removal.entry.id, removal.entry.skill.revision)
                RemovalResult.Removed(removal.entry)
            } catch (_: SkillRemovalCleanupPending) {
                RemovalResult.Removed(removal.entry, cleanupPending = true)
            } catch (_: SkillToolRegistry.ManagementBuiltinConflict) {
                RemovalResult.Rejected("builtin_conflict")
            } catch (_: SkillConflict) {
                RemovalResult.Rejected("skill_conflict")
            } catch (_: Exception) {
                RemovalResult.Unknown
            }
        }
    }

    fun cancelRemoval() {
        workerOnly()
        synchronized(this) { pendingRemoval = null }
    }

    data class Candidate(val directory: String, val name: String, val description: String, val incomingRevision: String)

    /** Opaque page-visit objects: callers can display them, but cannot mint approval inputs. */
    class Inspection internal constructor(
        val owner: String, val repo: String, val commit: String, val candidates: List<Candidate>,
    )
    data class Change(val candidate: Candidate, val previousRevision: String?, val compatibility: SkillCompatibilityResult)
    class Preview internal constructor(val inspection: Inspection, val changes: List<Change>) {
        val compatible: Boolean get() = changes.all { it.compatibility.available }
        val replacements: Map<String, String> = java.util.Collections.unmodifiableMap(changes
            .filter { it.previousRevision != null }.associate { "user:${it.candidate.name}" to it.previousRevision!! })
    }
    sealed class InstallResult {
        data class Installed(val skills: List<InstalledSkill>, val cleanupPending: Boolean = false) : InstallResult()
        data class Rejected(val code: String) : InstallResult()
        /** Publication could not be established. Refresh/reconcile; never automatically resubmit. */
        object Unknown : InstallResult()
    }

    class LocalPreview internal constructor(val archiveSha256: String, val change: Change) {
        val compatible: Boolean get() = change.compatibility.available
        val replacements: Map<String, String> = java.util.Collections.unmodifiableMap(
            change.previousRevision?.let { mapOf("user:${change.candidate.name}" to it) } ?: emptyMap())
    }
    private data class PendingLocal(val preview: LocalPreview, val bundle: SkillBundle)
    private var pendingLocal: PendingLocal? = null

    fun previewLocalZip(openStream: () -> java.io.InputStream): LocalPreview {
        workerOnly()
        return synchronized(this) {
            inspected = null; pending = null; pendingLocal = null; pendingRemoval = null
            val archive = LocalSkillZipImport.read(openStream)
            val checked = registry.previewForManagement(archive.bundle)
            val state = registry.managementState()
            check(!state.entriesUnavailable) { "skills_unavailable" }
            require(state.entries.none { it.builtin && it.skill.name == checked.header.name }) { "builtin_conflict" }
            val candidate = Candidate("", checked.header.name, checked.header.description, checked.revision)
            LocalPreview(archive.sha256, Change(candidate,
                state.entries.singleOrNull { it.id == "user:${candidate.name}" }?.skill?.revision,
                checked.compatibility)).also { pendingLocal = PendingLocal(it, archive.bundle) }
        }
    }

    /** Installs the privately frozen bytes, not a reopened provider URI or a caller-owned bundle. */
    fun installLocalZip(preview: LocalPreview, approvedReplacements: Map<String, String>): InstallResult {
        workerOnly()
        return synchronized(this) {
            val prepared = pendingLocal
            if (prepared?.preview !== preview) return@synchronized InstallResult.Rejected("stale_preview")
            pendingLocal = null
            if (!preview.compatible) return@synchronized InstallResult.Rejected("skill_incompatible")
            if (approvedReplacements != preview.replacements) return@synchronized InstallResult.Rejected("replacement_approval_required")
            try {
                InstallResult.Installed(frozen(registry.installForManagement(listOf(prepared.bundle),
                    preview.replacements.mapKeys { it.key.removePrefix("user:") },
                    mapOf(preview.change.candidate.name to preview.change.candidate.incomingRevision))))
            } catch (committed: SkillCleanupPending) {
                InstallResult.Installed(frozen(committed.installedSkills), cleanupPending = true)
            } catch (_: SkillIncompatible) {
                InstallResult.Rejected("skill_incompatible")
            } catch (_: SkillConflict) {
                InstallResult.Rejected("skill_conflict")
            } catch (_: SkillToolRegistry.ManagementBuiltinConflict) {
                InstallResult.Rejected("builtin_conflict")
            } catch (_: Exception) {
                InstallResult.Unknown
            }
        }
    }

    private var inspected: Inspection? = null
    private var pending: Preview? = null

    fun inspect(url: String): Inspection {
        workerOnly()
        return synchronized(this) {
            inspected = null; pending = null; pendingLocal = null; pendingRemoval = null
            SkillInvocationValidation.validate("skills_inspect_github", org.json.JSONObject().put("url", url))
            val json = registry.inspectForManagement(url)
            val candidates = json.getJSONArray("candidates")
            Inspection(json.getString("owner"), json.getString("repo"), json.getString("commit"),
                frozen((0 until candidates.length()).map { index ->
                    val item = candidates.getJSONObject(index)
                    Candidate(item.getString("directory"), item.getString("name"), item.getString("description"), item.getString("incoming_revision"))
                })).also { inspected = it }
        }
    }

    /** Downloads only the selected directories at the inspected commit and statically validates them. */
    fun preview(inspection: Inspection, directories: Set<String>): Preview {
        workerOnly()
        return synchronized(this) {
            pending = null
            require(inspected === inspection) { "stale_inspection" }
            require(directories.isNotEmpty() && directories.all { path -> inspection.candidates.any { it.directory == path } }) { "invalid_selection" }
            val selected = inspection.candidates.filter { it.directory in directories }
            require(selected.map { it.name }.distinct().size == selected.size) { "duplicate_skill" }
            val bundles = registry.downloadForManagement(GitHubSkills.Selection(inspection.owner, inspection.repo, inspection.commit, selected.map { it.directory }))
            require(bundles.size == selected.size) { "incoming_revision_conflict" }
            val checked = selected.zip(bundles).map { (candidate, bundle) ->
                val result = registry.previewForManagement(bundle)
                require(result.header.name == candidate.name && result.header.description == candidate.description && result.revision == candidate.incomingRevision) { "incoming_revision_conflict" }
                result
            }
            val state = registry.managementState()
            check(!state.entriesUnavailable) { "skills_unavailable" }
            require(selected.none { candidate -> state.entries.any { it.builtin && it.skill.name == candidate.name } }) { "builtin_conflict" }
            Preview(inspection, frozen(selected.zip(checked).map { (candidate, result) ->
                Change(candidate, state.entries.singleOrNull { it.id == "user:${candidate.name}" }?.skill?.revision,
                    result.compatibility)
            })).also { pending = it }
        }
    }

    /** Exact preview identity + complete old-revision approval, consumed BEFORE any network/write. */
    fun install(preview: Preview, approvedReplacements: Map<String, String>): InstallResult {
        workerOnly()
        return synchronized(this) {
            if (pending !== preview) return@synchronized InstallResult.Rejected("stale_preview")
            pending = null
            inspected = null
            if (!preview.compatible) return@synchronized InstallResult.Rejected("skill_incompatible")
            if (approvedReplacements != preview.replacements) return@synchronized InstallResult.Rejected("replacement_approval_required")
            val selection = GitHubSkills.Selection(preview.inspection.owner, preview.inspection.repo,
                preview.inspection.commit, preview.changes.map { it.candidate.directory })
            val incoming = preview.changes.associate { it.candidate.name to it.candidate.incomingRevision }
            val replacements = preview.replacements.mapKeys { it.key.removePrefix("user:") }
            val bundles = try {
                registry.downloadForManagement(selection).also { downloaded ->
                    require(downloaded.size == preview.changes.size) { "incoming_revision_conflict" }
                    downloaded.zip(preview.changes).forEach { (bundle, change) ->
                        val checked = registry.previewForManagement(bundle)
                        require(checked.header.name == change.candidate.name && checked.revision == incoming[checked.header.name]) { "incoming_revision_conflict" }
                        SkillCompatibilityPolicy.requireAvailable(checked.compatibility)
                    }
                }
            } catch (failure: Exception) {
                return@synchronized InstallResult.Rejected(safeCode(failure))
            }
            try {
                InstallResult.Installed(frozen(registry.installForManagement(bundles, replacements, incoming)))
            } catch (committed: SkillCleanupPending) {
                InstallResult.Installed(frozen(committed.installedSkills), cleanupPending = true)
            } catch (_: SkillIncompatible) {
                InstallResult.Rejected("skill_incompatible")
            } catch (_: SkillConflict) {
                InstallResult.Rejected("skill_conflict")
            } catch (_: SkillToolRegistry.ManagementBuiltinConflict) {
                InstallResult.Rejected("builtin_conflict")
            } catch (_: Exception) {
                // Even an argument-shaped failure can occur while resolving a durable
                // journal marker. Only explicitly typed pre-publication failures reject.
                InstallResult.Unknown
            }
        }
    }

    fun cancelInstall() {
        workerOnly()
        synchronized(this) { pending = null; inspected = null; pendingLocal = null }
    }

    private fun safeCode(failure: Exception) = failure.message?.takeIf { it.matches(Regex("[a-z_]+")) } ?: "skills_unavailable"
    private fun <T> frozen(values: List<T>): List<T> = java.util.Collections.unmodifiableList(ArrayList(values))

    private fun workerOnly() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "background_thread_required" }
    }
}
