package app.daiyu.android.backup.legacy

import org.json.JSONArray
import org.json.JSONObject

/** This gate never grants execution, model/provider bindings, payment or credential capabilities. */
internal object LegacySubAgentGate {
    fun inspect(document: JSONObject, issues: MutableList<LegacyIssue>): JSONObject {
        val schema = LegacyJson.integer(document, "schemaVersion")
        require(schema in 1..3)
        val generation = if (!document.has("subAgentConfigGeneration") || document.isNull("subAgentConfigGeneration")) null
            else LegacyJson.string(document, "subAgentConfigGeneration")
        require(generation == null || generation == "1") { "Unknown sub-agent configuration generation" }
        if (generation == null) {
            // Deliberately do not parse or even type-check pre-reset config payloads.
            issues += LegacyIssue("SUB_AGENT_PRE_RESET_DISABLED", 1, "Missing generation: obsolete payload was not decoded or retained; compatibility config is disabled and empty.")
            return empty()
        }
        val hasArchive = document.has("subAgentConfigJson") && !document.isNull("subAgentConfigJson")
        require(schema >= 3 || !hasArchive) { "Pre-v3 archive cannot contain generation-1 configuration" }
        if (!hasArchive) return empty()
        val archive = LegacyJson.parse(LegacyJson.string(document, "subAgentConfigJson")) as? JSONObject ?: error("Sub-agent archive must be an object")
        require(LegacyJson.integer(archive, "version") == 1L) { "Unknown sub-agent archive schema" }
        LegacyParser.keys(archive, setOf("version", "enabled", "agents", "parallel_limits", "legacy_parallel_limits"))
        LegacyParser.bool(archive, "enabled", false)
        listOf("agents", "parallel_limits", "legacy_parallel_limits").forEach { key ->
            if (archive.has(key)) require(archive.get(key) is JSONArray) { "Invalid sub-agent collection" }
        }
        // The frozen importer delegates the nested schema to a repository not in the snapshot.
        // Do not claim to validate or activate that missing schema. Nonempty payloads are rejected
        // rather than copying potentially privileged unknown fields into compatibility storage.
        require(listOf("agents", "parallel_limits", "legacy_parallel_limits").all { !archive.has(it) || archive.getJSONArray(it).length() == 0 }) {
            "Nonempty sub-agent profile schema is not available in frozen evidence; refusing unsafe import"
        }
        issues += LegacyIssue("SUB_AGENT_NOT_ACTIVATED", 1, "Known generation/schema validated; empty source config retained read-only, not installed into live preferences.")
        return JSONObject().put("generation", "1").put("source", archive).put("active", false)
    }
    private fun empty() = JSONObject().put("version", 1).put("enabled", false).put("agents", JSONArray())
        .put("parallel_limits", JSONArray()).put("legacy_parallel_limits", JSONArray())
}
