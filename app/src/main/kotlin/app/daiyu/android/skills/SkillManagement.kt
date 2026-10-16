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

    data class Candidate(val directory: String, val name: String, val description: String, val incomingRevision: String)

    /** Opaque page-visit objects: callers can display them, but cannot mint approval inputs. */
    class Inspection internal constructor(
        val owner: String, val repo: String, val commit: String, val candidates: List<Candidate>,
    )
    data class Change(val candidate: Candidate, val previousRevision: String?)
    class Preview internal constructor(val inspection: Inspection, val changes: List<Change>) {
        val replacements: Map<String, String> = java.util.Collections.unmodifiableMap(changes
            .filter { it.previousRevision != null }.associate { "user:${it.candidate.name}" to it.previousRevision!! })
    }
    sealed class InstallResult {
        data class Installed(val skills: List<InstalledSkill>, val cleanupPending: Boolean = false) : InstallResult()
        data class Rejected(val code: String) : InstallResult()
        /** Publication could not be established. Refresh/reconcile; never automatically resubmit. */
        object Unknown : InstallResult()
    }

    private var inspected: Inspection? = null
    private var pending: Preview? = null

    fun inspect(url: String): Inspection {
        workerOnly()
        return synchronized(this) {
            inspected = null; pending = null
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
            selected.zip(bundles).forEach { (candidate, bundle) ->
                val (header, revision) = registry.previewForManagement(bundle)
                require(header.name == candidate.name && header.description == candidate.description && revision == candidate.incomingRevision) { "incoming_revision_conflict" }
            }
            val state = registry.managementState()
            check(!state.entriesUnavailable) { "skills_unavailable" }
            require(selected.none { candidate -> state.entries.any { it.builtin && it.skill.name == candidate.name } }) { "builtin_conflict" }
            Preview(inspection, frozen(selected.map { candidate ->
                Change(candidate, state.entries.singleOrNull { it.id == "user:${candidate.name}" }?.skill?.revision)
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
            if (approvedReplacements != preview.replacements) return@synchronized InstallResult.Rejected("replacement_approval_required")
            val selection = GitHubSkills.Selection(preview.inspection.owner, preview.inspection.repo,
                preview.inspection.commit, preview.changes.map { it.candidate.directory })
            val incoming = preview.changes.associate { it.candidate.name to it.candidate.incomingRevision }
            val replacements = preview.replacements.mapKeys { it.key.removePrefix("user:") }
            val bundles = try {
                registry.downloadForManagement(selection).also { downloaded ->
                    require(downloaded.size == preview.changes.size) { "incoming_revision_conflict" }
                    downloaded.zip(preview.changes).forEach { (bundle, change) ->
                        val (header, revision) = registry.previewForManagement(bundle)
                        require(header.name == change.candidate.name && revision == incoming[header.name]) { "incoming_revision_conflict" }
                    }
                }
            } catch (failure: Exception) {
                return@synchronized InstallResult.Rejected(safeCode(failure))
            }
            try {
                InstallResult.Installed(frozen(registry.installForManagement(bundles, replacements, incoming)))
            } catch (committed: SkillCleanupPending) {
                InstallResult.Installed(frozen(committed.installedSkills), cleanupPending = true)
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
        synchronized(this) { pending = null; inspected = null }
    }

    private fun safeCode(failure: Exception) = failure.message?.takeIf { it.matches(Regex("[a-z_]+")) } ?: "skills_unavailable"
    private fun <T> frozen(values: List<T>): List<T> = java.util.Collections.unmodifiableList(ArrayList(values))

    private fun workerOnly() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "background_thread_required" }
    }
}
