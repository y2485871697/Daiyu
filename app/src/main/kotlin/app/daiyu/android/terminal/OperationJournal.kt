package app.daiyu.android.terminal

import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.security.MessageDigest
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** Durable at-most-once ledger. Pending entries are never evicted or executed again.
 * Reservation and commit locks are short: command execution is outside the ledger lock. */
class OperationJournal(directory: File, scope: String, private val limit: Int = 256, binding: JSONObject? = null) {
    private val bindingDigest = binding?.let { digest(canonical(it)) }
    private val file: File
    private val lockFile: File
    private val lock: ReentrantLock
    internal var beforeSave: (() -> Unit)? = null
    internal var beforeDirectorySync: (() -> Unit)? = null
    internal var lockTimeoutMillis = 3000L

    init {
        require(scope.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        require(limit in 1..256)
        TerminalDurability.ensureDirectory(directory)
        file = File(directory, "$scope.json")
        lockFile = File(directory, "$scope.lock")
        lock = synchronized(locks) {
            locks[file.canonicalPath] ?: run {
                check(locks.size < 1024) { "Journal registry full; unresolved entries retained" }
                ReentrantLock().also { locks[file.canonicalPath] = it }
            }
        }
        synchronized(directoryLock) {
            var count = 0
            Files.newDirectoryStream(directory.toPath()).use { stream ->
                for (entry in stream) {
                    count++
                    check(count <= 2048) { "Journal directory full; unresolved entries retained" }
                }
            }
            check(file.exists() || count < 2046) { "Journal directory full" }
        }
    }

    fun once(id: String, name: String, args: JSONObject, operation: () -> JSONObject): JSONObject =
        once(id, name, args, null, operation)

    fun once(id: String, name: String, args: JSONObject, eventIdentity: String?, operation: () -> JSONObject): JSONObject {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        require(eventIdentity == null || eventIdentity.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        val fingerprintArgs = JSONObject(args.toString()).apply { remove("request_id") }
        val fingerprint = digest(name + "\n" + canonical(fingerprintArgs))

        try {
            withFileLock {
                val entries = load()
                if (entries.has(id)) {
                    val old = entries.getJSONObject(id)
                    if (old.getString("fingerprint") != fingerprint) return@withFileLock failure(
                        if(name == "terminal_exec" && old.optInt("fingerprint_version") < 3) "legacy_request_identity_unavailable_not_replayed" else "request_id_conflict"
                    )
                    return@withFileLock if (old.has("result")) {
                        JSONObject(old.getJSONObject("result").toString()).put("replayed_execution", true)
                    } else {
                        failure("outcome_unknown_not_replayed").put("execution", "unknown")
                    }
                }
                entries.keys().forEach { key ->
                    val prior = entries.getJSONObject(key)
                    if (eventIdentity != null && prior.optString("event_identity") == eventIdentity &&
                        prior.getString("fingerprint") != fingerprint) {
                        return@withFileLock failure("operation_event_identity_conflict")
                    }
                    val unresolved = !prior.has("result") || prior.getJSONObject("result").optString("execution") == "unknown"
                    if (name == "terminal_exec" && unresolved && prior.optInt("fingerprint_version") < 3) {
                        return@withFileLock failure("legacy_equivalence_unknown_not_replayed").put("execution", "unknown")
                    }
                    if (prior.getString("fingerprint") == fingerprint) {
                        if (unresolved) return@withFileLock failure("equivalent_outcome_unknown_not_replayed").put("execution", "unknown")
                        if (name in setOf("terminal_exec", "pty_write", "pty_resize", "pty_interrupt") && prior.getJSONObject("result").optString("execution") == "executed") {
                            return@withFileLock JSONObject(prior.getJSONObject("result").toString())
                                .put("replayed_execution", true).put("equivalent_request", true)
                        }
                    }
                }
                if (entries.length() >= limit) return@withFileLock failure("operation_ledger_full")
                entries.put(id, JSONObject().put("fingerprint", fingerprint).put("state", "pending").put("fingerprint_version", 3).put("event_identity", eventIdentity ?: JSONObject.NULL))
                save(entries) // No side effect until this durable reservation succeeds.
                null
            }?.let { return it }
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            return failure(when (error) {
                is InterruptedException -> "operation_reservation_interrupted"
                is JournalRejected -> error.code
                else -> "operation_reservation_unavailable"
            })
        }

        val result = try {
            operation()
        } catch (_: Exception) {
            failure("outcome_unknown_not_replayed").put("execution", "unknown")
        }
        try {
            withFileLock {
                val entries = load()
                val entry = entries.optJSONObject(id) ?: throw IOException("Reserved journal entry missing")
                check(entry.getString("fingerprint") == fingerprint) { "Reserved journal entry changed" }
                entry.put("result", result).put("state", "committed")
                save(entries)
            }
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            // Preserve dispatch identifiers even if final ledger persistence fails.
            return JSONObject(result.toString()).put("ok", false)
                .put("error", "journal_commit_unknown_not_replayed").put("execution", "unknown")
        }
        return result
    }

    /** Validate before any cached result, log read or process lookup can expose this scope. */
    fun verifyBinding(): JSONObject = try {
        withFileLock {
            val state = readState()
            if (state.retired) return@withFileLock failure("operation_scope_retired")
            if (!state.enveloped) save(state.entries)
            else {
                beforeDirectorySync?.invoke()
                TerminalDurability.syncDirectory(file.parentFile!!)
            }
            JSONObject().put("ok", true)
        }
    } catch (error: Exception) {
        if (error is InterruptedException) Thread.currentThread().interrupt()
        failure(if (error is JournalRejected) error.code else "operation_binding_unavailable")
    }

    /** Trusted host lifecycle only. Never forget unresolved outcomes or reactivate this scope. */
    fun retireScope(): JSONObject = try {
        withFileLock {
            val state = readState()
            if (state.retired) {
                save(JSONObject(), retired = true)
                return@withFileLock JSONObject().put("ok", true).put("state", "retired")
            }
            val entries = state.entries
            for (id in entries.keys()) {
                val entry = entries.optJSONObject(id)
                val result = entry?.optJSONObject("result")
                if (entry?.optString("state") != "committed" ||
                    entry.optString("fingerprint").length != 64 ||
                    result?.optString("execution") !in setOf("executed", "not_executed")) {
                    return@withFileLock failure("operation_scope_unresolved_not_retired")
                }
            }
            save(JSONObject(), retired = true)
            JSONObject().put("ok", true).put("state", "retired")
                .put("compacted_entries", entries.length())
        }
    } catch (error: Exception) {
        if (error is InterruptedException) Thread.currentThread().interrupt()
        failure(if (error is JournalRejected) error.code else "operation_retirement_unconfirmed")
    }

    private data class State(val entries: JSONObject, val retired: Boolean, val enveloped: Boolean = false)
    private class JournalRejected(val code: String) : IOException(code)

    private fun readState(): State {
        check(!file.exists() || file.length() <= MAX_BYTES) { "Journal size limit" }
        if (!file.exists()) return State(JSONObject(), false)
        val value = JSONObject(file.readText())
        // Legacy entries are objects, so a numeric format cannot collide with a valid request ID.
        if (value.opt("format") is Number) {
            check(value.getInt("format") == 4) { "Unknown journal format" }
            val actualBinding = if (value.isNull("binding")) null else value.getString("binding")
            if (actualBinding != bindingDigest) throw JournalRejected("operation_scope_binding_mismatch")
            val lifecycle = value.getString("lifecycle")
            check(lifecycle in setOf("active", "retired"))
            val entries = value.getJSONObject("entries")
            check(lifecycle != "retired" || entries.length() == 0)
            return State(entries, lifecycle == "retired", enveloped = true)
        }
        // A nonempty pre-binding journal cannot be attributed to the current trusted caller.
        if (bindingDigest != null && value.length() != 0) throw JournalRejected("legacy_scope_binding_unavailable")
        return State(value, false)
    }

    private fun load(): JSONObject {
        val state = readState()
        if (state.retired) throw JournalRejected("operation_scope_retired")
        return state.entries
    }

    private fun save(value: JSONObject, retired: Boolean = false) {
        beforeSave?.invoke()
        val envelope = JSONObject().put("format", 4).put("binding", bindingDigest ?: JSONObject.NULL)
            .put("lifecycle", if (retired) "retired" else "active").put("entries", value)
        val bytes = envelope.toString().toByteArray(Charsets.UTF_8)
        check(bytes.size <= MAX_BYTES) { "Journal size limit" }
        TerminalDurability.replace(file, bytes, beforeDirectorySync)
    }

    private fun <T> withFileLock(block: () -> T): T {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(lockTimeoutMillis)
        if (!lock.tryLock(lockTimeoutMillis, TimeUnit.MILLISECONDS)) throw IOException("Journal lock deadline")
        try {
            return RandomAccessFile(lockFile, "rw").use { raf ->
                withChannelLock(raf, deadline, block)
            }
        } finally { lock.unlock() }
    }

    private fun <T> withChannelLock(raf: RandomAccessFile, deadline: Long, block: () -> T): T {
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            try {
                val held = raf.channel.tryLock()
                if (held != null) return held.use {
                    if (System.nanoTime() >= deadline) throw IOException("Journal file-lock deadline")
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    block()
                }
            } catch (_: OverlappingFileLockException) {
                // Another journal instance holds the same process/file reservation.
            }
            if (System.nanoTime() >= deadline) throw IOException("Journal file-lock deadline")
            Thread.sleep(10)
        }
    }

    companion object {
        private const val MAX_BYTES = 4 * 1024 * 1024
        private val locks = mutableMapOf<String, ReentrantLock>()
        private val directoryLock = Any()

        fun failure(code: String) = JSONObject().put("ok", false).put("error", code).put("execution", "not_executed")

        private fun digest(text: String) = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        private fun canonical(value: Any?): String = when (value) {
            is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") {
                JSONObject.quote(it) + ":" + canonical(value.get(it))
            }
            is org.json.JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
            null, JSONObject.NULL -> "null"
            is String -> JSONObject.quote(value)
            is Number, is Boolean -> value.toString()
            else -> JSONObject.quote(value.toString())
        }
    }
}
