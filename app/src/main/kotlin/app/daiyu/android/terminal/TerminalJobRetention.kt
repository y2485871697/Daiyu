package app.daiyu.android.terminal

import org.json.JSONObject
import java.io.File
import java.nio.file.Files

/** Reclaims completed history under capacity pressure, never the at-most-once journal. */
internal class TerminalJobRetention(private val directory: File, private val journals: File) {
    fun reclaim(liveJobs: Map<String, Boolean>, hasCapacity: () -> Boolean, release: (String) -> Unit) {
        val records = mutableMapOf<String, File>()
        Files.newDirectoryStream(directory.toPath()).use { stream ->
            var scanned = 0
            for (entry in stream) {
                if (++scanned > 256) return
                val file = entry.toFile()
                if (file.extension == "json" && file.nameWithoutExtension.matches(JOB_ID))
                    records[file.nameWithoutExtension] = file
            }
        }
        liveJobs.filterValues { it }.keys.forEach { id -> records.putIfAbsent(id, File(directory, "$id.json")) }
        val receipts = mutableMapOf<String, Set<String>?>()
        for ((id, metadata) in records.entries.sortedBy { it.value.lastModified() }) {
            if (hasCapacity()) return
            val complete = liveJobs[id] ?: completedRecord(id, metadata, receipts)
            if (!complete) continue
            // Release the reservation only after removal is durable; the caller holds the jobs lock.
            val removed = runCatching {
                for (suffix in listOf("log", "script", "json.tmp", "json")) {
                    val file = File(directory, "$id.$suffix")
                    check(!file.exists() || file.delete())
                }
                TerminalDurability.syncDirectory(directory)
            }.isSuccess
            if (removed) release(id)
        }
    }

    private fun completedRecord(id: String, metadata: File, receipts: MutableMap<String, Set<String>?>): Boolean {
        return runCatching {
            val record = readObject(metadata, 16384) ?: return false
            if (record.optString("job_id") != id || record.optString("mode") !in setOf("sync", "async")) return false
            val scope = record.optString("scope")
            if (!scope.matches(Regex("[A-Za-z0-9_-]{1,128}"))) return false
            if (record.optString("state") in setOf("start_failed", "cancelled_before_dispatch")) return true
            val proof = receipts.getOrPut(scope) { completedScope(scope) } ?: return false
            id in proof
        }.getOrDefault(false)
    }

    private fun completedScope(scope: String): Set<String>? {
        val journal = readObject(File(journals, "$scope.json"), 4 * 1024 * 1024) ?: return null
        if (journal.optInt("format") != 4 || !journal.optString("binding").matches(Regex("[a-f0-9]{64}"))) return null
        val entries = journal.optJSONObject("entries") ?: return null
        if (journal.optString("lifecycle") != "active") return null
        val complete = mutableSetOf<String>()
        entries.keys().forEach { key ->
            val entry = entries.optJSONObject(key) ?: return@forEach
            val result = entry.optJSONObject("result") ?: return@forEach
            if (entry.optString("state") == "committed" && entry.optString("fingerprint").matches(Regex("[a-f0-9]{64}")) &&
                result.optString("execution") == "executed" && result.optString("state") == "exited" &&
                result.opt("output_complete") == true && result.opt("exit_code") is Number &&
                result.opt("has_more") == false && result.optLong("offset", -1) == 0L &&
                result.optLong("total_bytes", -1) >= 0 &&
                result.optLong("next_offset", -1) == result.optLong("total_bytes", -2))
                complete.add(result.optString("job_id"))
        }
        return complete
    }

    private fun readObject(file: File, maxBytes: Int): JSONObject? {
        return runCatching {
            if (!file.isFile || file.length() > maxBytes) return null
            JSONObject(file.readText())
        }.getOrNull()
    }

    companion object {
        private val JOB_ID = Regex("[a-f0-9-]{36}")
    }
}
