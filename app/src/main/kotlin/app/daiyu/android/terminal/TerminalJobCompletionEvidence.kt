package app.daiyu.android.terminal

import org.json.JSONObject

/** Historical exit facts only. Never restores a Process, a session or permission to control it. */
internal object TerminalJobCompletionEvidence {
    fun persisted(value: JSONObject?, record: TerminalJobHistoryReader.Record): Int? = runCatching {
        requireNotNull(value)
        require(value.opt("format") == 1)
        require(value.opt("owner") == record.owner && value.opt("scope") == record.scope &&
            value.opt("session_id") == record.sessionId)
        exit(value, record)
    }.getOrNull()

    fun committed(journal: JSONObject?, record: TerminalJobHistoryReader.Record): Int? = runCatching {
        requireNotNull(journal)
        require(journal.opt("format") == 4 && journal.opt("lifecycle") == "active")
        require((journal.opt("binding") as? String)?.matches(Regex("[a-f0-9]{64}")) == true)
        val entries = journal.getJSONObject("entries")
        require(entries.length() <= 256)
        val matches = entries.keys().asSequence().mapNotNull { key ->
            entries.optJSONObject(key)?.takeIf { it.opt("state") == "committed" }
                ?.optJSONObject("result")?.takeIf { it.opt("job_id") == record.jobId }
        }.toList()
        // A reservation, an accepted dispatch and conflicting/duplicate receipts are not a final exit.
        require(matches.size == 1)
        exit(matches.single(), record)
    }.getOrNull()

    private fun exit(value: JSONObject, record: TerminalJobHistoryReader.Record): Int {
        require(value.opt("job_id") == record.jobId && value.opt("backend") == record.backend &&
            value.opt("mode") == record.mode)
        require(value.opt("ok") == true && value.opt("state") == "exited" &&
            value.opt("execution") == "executed" && value.opt("output_complete") == true)
        for ((key, expected) in listOf("owner" to record.owner, "scope" to record.scope, "session_id" to record.sessionId)) {
            require(!value.has(key) || value.opt(key) == expected)
        }
        require(!record.scopeDrainRequired || value.opt("scope_drain_confirmed") == true)
        val code = value.get("exit_code")
        require(code is Int || code is Long)
        val number = (code as Number).toLong()
        require(number in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
        return number.toInt()
    }
}
