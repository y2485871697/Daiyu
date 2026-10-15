package app.daiyu.android.skills

import app.daiyu.android.domain.tools.SessionCapabilities
import app.daiyu.android.domain.tools.TaskAuthorization
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal data class CapturedSkill(
    val name: String,
    val description: String,
    val revision: String,
    val instructions: String,
    val resources: Map<String, String>,
    val capturedBytes: Int
)

data class SkillRunIndex(val id: String, val description: String, val revision: String)

/** Host-owned, read-only run snapshot. It cannot install, inspect GitHub or execute scripts. */
class SkillRunSession private constructor(
    private val authorization: TaskAuthorization,
    permissions: Set<String>,
    entries: List<Entry>,
    private val parent: SkillRunSession? = null,
    private val returnedBytes: AtomicLong = AtomicLong(0)
) : AutoCloseable {
    companion object {
        const val READ = "skills.read"
        const val RESOURCE = "skills.resource"
        const val MAX_CAPTURE_BYTES = 2 * 1024 * 1024
        const val MAX_INDEX_BYTES = 64 * 1024
        internal val READ_CAPABILITIES = setOf(READ, RESOURCE)
        internal fun create(authorization: TaskAuthorization, permissions: Set<String>, entries: List<Entry>) =
            SkillRunSession(authorization, permissions, entries)
    }
    internal class Entry(val id: String, val skill: CapturedSkill, val store: SkillStore)
    private val closed = AtomicBoolean(false)
    private val permissions = Collections.unmodifiableSet(permissions.toSet())
    private val entries = entries.associateBy { it.id }
    private val tools: Set<String> = Collections.unmodifiableSet(buildSet {
        if (READ in permissions) { add("skills_list"); add("skills_read") }
        if (READ in permissions && RESOURCE in permissions) add("skills_read_resource")
    })

    val availableTools: Set<String> get() = if (isClosed()) emptySet() else tools

    fun index(caller: TaskAuthorization): List<SkillRunIndex> = captureIndex(caller).first

    /** Canonical model payload; its complete encoded size is charged to the shared budget. */
    fun indexJson(caller: TaskAuthorization): String = captureIndex(caller).second

    private fun captureIndex(caller: TaskAuthorization): Pair<List<SkillRunIndex>, String> {
        requireAccess(caller, READ)
        val index = entries.values.map { SkillRunIndex(it.id, it.skill.description, it.skill.revision) }
        val encoded = JSONObject().put("skills", JSONArray(index.map {
            JSONObject().put("id", it.id).put("description", it.description).put("revision", it.revision)
        })).toString()
        val bytes = encoded.toByteArray(Charsets.UTF_8).size.toLong()
        require(bytes <= MAX_INDEX_BYTES) { "index_too_large" }
        delivered(caller, READ, bytes)
        return Collections.unmodifiableList(index) to encoded
    }

    fun read(caller: TaskAuthorization, id: String, revision: String): String {
        val entry = entry(caller, id, revision, READ)
        return entry.skill.instructions.also { delivered(caller, READ, it.toByteArray(Charsets.UTF_8).size.toLong()) }
    }

    fun readResource(caller: TaskAuthorization, id: String, revision: String, path: String, maxBytes: Int = 64 * 1024): String {
        val entry = entry(caller, id, revision, RESOURCE)
        require(maxBytes in 1..SkillStore.MAX_FILE_BYTES) { "invalid_limit" }
        require(path.isNotBlank() && path.length <= 512 && !path.startsWith('/') && '\\' !in path && '\u0000' !in path &&
            path.split('/').all { it.isNotEmpty() && it != "." && it != ".." && !it.startsWith('.') }) { "unsafe_path" }
        val hash = entry.skill.resources[path] ?: error("resource_not_found")
        // No cache: bounded reads retain neither arbitrary resources nor mutable JSON objects.
        val text = if (path == "SKILL.md") {
            entry.skill.instructions.also { require(it.toByteArray(Charsets.UTF_8).size <= maxBytes) { "resource_too_large" } }
        } else entry.store.readCapturedResource(entry.skill.name, path, revision, hash, maxBytes)
        delivered(caller, RESOURCE, text.toByteArray(Charsets.UTF_8).size.toLong())
        return text
    }

    /** Child grants may only narrow this captured run; do not recapture settings or stores. */
    fun child(caller: TaskAuthorization, child: TaskAuthorization, capabilities: SessionCapabilities): SkillRunSession {
        requireAccess(caller, READ)
        require(child.sessionId == authorization.sessionId && capabilities.sessionId == authorization.sessionId &&
            child.taskId != authorization.taskId && child.allowedWrites.isEmpty()) { "session_mismatch" }
        val narrowed = permissions.intersect(capabilities.enabled)
        val result = SkillRunSession(child, narrowed, if (READ in narrowed) entries.values.toList() else emptyList(), this, returnedBytes)
        requireAccess(caller, READ)
        return result
    }

    private fun entry(caller: TaskAuthorization, id: String, revision: String, permission: String): Entry {
        requireAccess(caller, permission)
        val found = entries[id] ?: error("skill_not_found")
        require(found.skill.revision == revision) { "revision_mismatch" }
        return found
    }
    private fun delivered(caller: TaskAuthorization, permission: String, bytes: Long) {
        requireAccess(caller, permission)
        while (true) {
            val used = returnedBytes.get()
            require(bytes <= MAX_CAPTURE_BYTES - used) { "run_read_budget_exhausted" }
            if (returnedBytes.compareAndSet(used, used + bytes)) break
        }
        requireAccess(caller, permission)
    }
    private fun isClosed(): Boolean = closed.get() || parent?.isClosed() == true
    private fun requireAccess(caller: TaskAuthorization, permission: String) {
        // Identity ties this handle to the exact host-issued run grant, not a model-supplied string.
        require(caller === authorization) { "run_mismatch" }
        check(!isClosed()) { "run_closed" }
        require(READ in permissions && permission in permissions) { "skills_not_authorized" }
    }
    /** New calls are revoked immediately; overlapping calls may complete or be rejected. */
    override fun close() { closed.set(true) }
}
