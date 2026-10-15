package app.daiyu.android.skills

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Pure invocation validation: never reads a store, resolves a remote ref, or performs I/O. */
internal object SkillInvocationValidation {
    data class Install(
        val selection: GitHubSkills.Selection,
        val replacements: Map<String, String>,
        val incoming: Map<String, String>
    )

    fun validate(tool: String, args: JSONObject): Install? {
        val required: Set<String>
        val optional: Set<String>
        when (tool) {
            "skills_list" -> { required = emptySet(); optional = emptySet() }
            "skills_read" -> { required = setOf("id", "revision"); optional = emptySet() }
            "skills_read_resource" -> { required = setOf("id", "revision", "path"); optional = setOf("max_bytes") }
            "skills_inspect_github" -> { required = setOf("url"); optional = emptySet() }
            "skills_install_from_github" -> {
                required = setOf("owner", "repo", "commit", "directories", "incoming_revisions")
                optional = setOf("replacements")
            }
            else -> error("unknown_tool")
        }
        val keys = args.keys().asSequence().toSet()
        require(keys.containsAll(required) && keys.all { it in required || it in optional }) { "invalid_arguments" }
        when (tool) {
            "skills_read", "skills_read_resource" -> {
                id(text(args, "id"))
                revision(text(args, "revision"))
                if (tool == "skills_read_resource") {
                    resourcePath(text(args, "path"))
                    if (args.has("max_bytes")) limit(args)
                }
            }
            "skills_inspect_github" -> githubUrl(text(args, "url"))
            "skills_install_from_github" -> return install(args)
        }
        return null
    }

    fun limit(args: JSONObject): Int {
        val value = args.opt("max_bytes")
        // JSON adapters may retain small integral values as Long. Never truncate/coerce Double.
        require(value is Int || value is Long) { "invalid_arguments" }
        val number = (value as Number).toLong()
        require(number in 1..SkillStore.MAX_FILE_BYTES.toLong()) { "invalid_limit" }
        return number.toInt()
    }

    private fun install(args: JSONObject): Install {
        val owner = text(args, "owner"); val repo = text(args, "repo")
        repository(owner, repo)
        val commit = text(args, "commit")
        require(commit.matches(Regex("[a-f0-9]{40}"))) { "invalid_commit" }
        val directories = args.opt("directories") as? JSONArray ?: error("invalid_arguments")
        require(directories.length() in 1..SkillStore.MAX_SKILLS) { "invalid_selection" }
        val selected = (0 until directories.length()).map { index ->
            val path = directories.opt(index) as? String ?: error("invalid_arguments")
            // The empty string is the existing, valid selection for a repository-root SKILL.md.
            if (path.isNotEmpty()) githubPath(path)
            path
        }
        require(selected.distinct().size == selected.size) { "invalid_selection" }
        val incomingJson = args.opt("incoming_revisions") as? JSONObject ?: error("invalid_arguments")
        require(incomingJson.length() == selected.size) { "invalid_arguments" }
        val incoming = linkedMapOf<String, String>()
        incomingJson.keys().forEach { key ->
            skillName(key, "invalid_arguments")
            val value = text(incomingJson, key)
            revision(value)
            incoming[key] = value
        }
        val replacements = if (args.has("replacements")) {
            args.opt("replacements") as? JSONArray ?: error("invalid_arguments")
        } else JSONArray()
        require(replacements.length() <= selected.size && replacements.length() <= SkillStore.MAX_SKILLS) { "invalid_arguments" }
        val expected = linkedMapOf<String, String>()
        for (index in 0 until replacements.length()) {
            val item = replacements.opt(index) as? JSONObject ?: error("invalid_arguments")
            require(item.keys().asSequence().toSet() == setOf("id", "revision")) { "invalid_arguments" }
            val replacementId = text(item, "id")
            require(replacementId.startsWith("user:")) { "invalid_replacement" }
            val name = replacementId.removePrefix("user:")
            skillName(name, "invalid_replacement")
            require(name !in expected && name in incoming) { "invalid_replacement" }
            val value = text(item, "revision")
            revision(value)
            expected[name] = value
        }
        return Install(GitHubSkills.Selection(owner, repo, commit, selected), expected, incoming)
    }

    private fun text(json: JSONObject, key: String): String = json.opt(key) as? String ?: error("invalid_arguments")
    private fun revision(value: String) {
        require(value.matches(Regex("[a-f0-9]{64}"))) { "invalid_revision" }
    }
    private fun skillName(value: String, code: String) {
        require(value.length in 1..64 && value.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*"))) { code }
    }
    private fun id(value: String) {
        require(value.substringBefore(':') in setOf("builtin", "user") && value.count { it == ':' } == 1) { "invalid_id" }
        skillName(value.substringAfter(':'), "invalid_id")
    }
    private fun repository(owner: String, repo: String) {
        require(owner.matches(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}")) && repo.matches(Regex("[A-Za-z0-9_.-]{1,100}")) && repo !in setOf(".", "..")) { "invalid_repository" }
    }
    private fun githubPath(path: String) {
        require(path.length in 1..512 && !path.startsWith('/') && '\\' !in path && path.none { it.code < 32 } && path.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "unsafe_github_path" }
    }
    private fun resourcePath(path: String) {
        require(path.isNotBlank() && path.length <= 512 && !path.startsWith('/') && '\\' !in path && '\u0000' !in path && path.split('/').none { it.isEmpty() || it.startsWith('.') }) { "unsafe_path" }
    }
    private fun githubUrl(value: String) {
        require(value.length in 1..4096) { "invalid_github_url" }
        val uri = try { URI(value) } catch (_: Exception) { error("invalid_github_url") }
        require(uri.scheme == "https" && uri.host == "github.com" && uri.userInfo == null && uri.port == -1 && uri.query == null && uri.fragment == null) { "invalid_github_url" }
        val parts = (uri.path ?: "").trim('/').split('/')
        require(parts.size == 2 || (parts.size >= 4 && parts[2] == "tree")) { "invalid_github_url" }
        repository(parts[0], parts[1].removeSuffix(".git"))
        if (parts.size >= 4) require(parts[3].matches(Regex("[A-Za-z0-9._-]{1,200}"))) { "invalid_ref" }
        val prefix = if (parts.size > 4) parts.drop(4).joinToString("/") else ""
        if (prefix.isNotEmpty()) githubPath(prefix)
    }
}
