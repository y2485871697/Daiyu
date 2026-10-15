package app.daiyu.android.skills

import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Public GitHub only. One pinned tree/ZIP per operation, never extracted or executed. */
class GitHubSkills(private val fetch: ((String, Int) -> ByteArray)? = null) {
    data class Selection(val owner: String, val repo: String, val commit: String, val directories: List<String>)
    // Deterministic test seams, not model arguments. Cancellation is worker-thread interruption.
    internal var operationTimeoutMillis = 60000L
    internal var nanoTime: () -> Long = System::nanoTime
    internal var cancellationCheck: () -> Unit = {
        if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("skills_cancelled")
    }
    private fun operation() = GitHubOperation(operationTimeoutMillis, nanoTime, cancellationCheck)
    fun inspect(url: String): JSONObject {
        val operation = operation(); operation.check()
        val uri = java.net.URI(url)
        require(uri.scheme == "https" && uri.host == "github.com" && uri.userInfo == null && uri.port == -1 && uri.query == null && uri.fragment == null) { "invalid_github_url" }
        val parts = uri.path.trim('/').split('/')
        require(parts.size == 2 || (parts.size >= 4 && parts[2] == "tree")) { "invalid_github_url" }
        val owner = parts[0]; val repo = parts[1].removeSuffix(".git")
        validateRepository(owner, repo)
        val refSegment = if (parts.size >= 4) {
            // Explicit /tree URLs retain the single-segment ref / remaining-path interpretation.
            parts[3].also { require(it.matches(Regex("[A-Za-z0-9._-]{1,200}"))) { "invalid_ref" } }
        } else {
            encodeDefaultRef(JSONObject(json(api(owner, repo), operation)).getString("default_branch"))
        }
        val commit = JSONObject(json(api(owner, repo) + "/commits/$refSegment", operation)).getString("sha")
        validateCommit(commit)
        val prefix = if (parts.size > 4) parts.drop(4).joinToString("/") else ""
        if (prefix.isNotEmpty()) validatePath(prefix)
        val nodes = tree(owner, repo, commit, operation)
        val directories = nodes.keys.filter { it == "SKILL.md" || it.endsWith("/SKILL.md") }
            .map { it.removeSuffix("SKILL.md").trimEnd('/') }
            .filter { prefix.isEmpty() || it == prefix || it.startsWith("$prefix/") }.sorted()
        require(directories.size <= SkillStore.MAX_SKILLS) { "too_many_skills" }
        val candidates = JSONArray()
        if (directories.isNotEmpty()) {
            val archive = verifiedArchive(owner, repo, commit, nodes, operation)
            val bundles = select(Selection(owner, repo, commit, directories), archive, operation)
            bundles.forEachIndexed { index, bundle ->
                operation.check()
                val header = SkillFrontmatter.parse(SkillFiles.text(bundle.files.getValue("SKILL.md")))
                val revision = SkillFiles.hash(bundle.files.toSortedMap().entries.joinToString("\n") {
                    operation.check(); "${it.key}\u0000${SkillFiles.hash(it.value)}"
                }.toByteArray(Charsets.UTF_8))
                candidates.put(JSONObject().put("directory", directories[index]).put("name", header.name)
                    .put("description", header.description).put("incoming_revision", revision))
            }
        }
        operation.check()
        return JSONObject().put("owner", owner).put("repo", repo).put("commit", commit).put("candidates", candidates)
    }
    fun download(selection: Selection): List<SkillBundle> {
        val operation = operation(); operation.check()
        validateRepository(selection.owner, selection.repo); validateCommit(selection.commit)
        require(selection.directories.isNotEmpty() && selection.directories.size <= SkillStore.MAX_SKILLS && selection.directories.distinct().size == selection.directories.size) { "invalid_selection" }
        selection.directories.filter { it.isNotEmpty() }.forEach(::validatePath)
        val nodes = tree(selection.owner, selection.repo, selection.commit, operation)
        val archive = verifiedArchive(selection.owner, selection.repo, selection.commit, nodes, operation)
        return select(selection, archive, operation).also { operation.check() }
    }
    private fun verifiedArchive(owner: String, repo: String, commit: String, nodes: Map<String, JSONObject>, operation: GitHubOperation): Map<String, ByteArray> {
        val archive = SkillZipArchive.read(get("https://codeload.github.com/$owner/$repo/zip/$commit", SkillZipArchive.MAX_ZIP_BYTES, operation), commit, operation::check)
        require(archive.keys == nodes.keys) { "archive_tree_mismatch" }
        archive.forEach { (path, bytes) ->
            operation.check()
            val node = nodes.getValue(path)
            require(bytes.size.toLong() == node.getLong("size")) { "download_changed" }
            val digest = java.security.MessageDigest.getInstance("SHA-1")
            digest.update("blob ${bytes.size}\u0000".toByteArray(Charsets.UTF_8)); digest.update(bytes)
            val sha = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            require(sha == node.getString("sha")) { "download_changed" }
        }
        return archive
    }
    private fun select(selection: Selection, archive: Map<String, ByteArray>, operation: GitHubOperation): List<SkillBundle> {
        var total = 0L
        return selection.directories.map { directory ->
            operation.check()
            val prefix = if (directory.isEmpty()) "" else "$directory/"
            val files = linkedMapOf<String, ByteArray>()
            archive.forEach { (path, bytes) ->
                operation.check()
                if (path.startsWith(prefix)) {
                    val relative = path.removePrefix(prefix); validatePath(relative)
                    require(!relative.split('/').any { it.startsWith('.') }) { "unsafe_github_entry" }
                    require(files.size < SkillStore.MAX_FILES) { "too_many_files" }
                    total += bytes.size
                    require(total <= SkillStore.MAX_SKILL_BYTES.toLong()) { "download_too_large" }
                    files[relative] = bytes.copyOf()
                }
            }
            require(files.containsKey("SKILL.md")) { "missing_skill" }
            SkillBundle(files, "https://github.com/${selection.owner}/${selection.repo}/tree/${selection.commit}" + if (directory.isEmpty()) "" else "/$directory", selection.commit)
        }
    }
    private fun tree(owner: String, repo: String, commit: String, operation: GitHubOperation): Map<String, JSONObject> {
        val result = JSONObject(json(api(owner, repo) + "/git/trees/$commit?recursive=1", operation))
        operation.check()
        require(!result.optBoolean("truncated", false)) { "github_tree_truncated" }
        val tree = result.getJSONArray("tree")
        require(tree.length() <= 20000) { "github_tree_too_large" }
        val nodes = linkedMapOf<String, JSONObject>(); val paths = hashSetOf<String>()
        for (i in 0 until tree.length()) {
            operation.check()
            val node = tree.getJSONObject(i); val path = node.getString("path"); validatePath(path)
            require(paths.add(path)) { "duplicate_github_entry" }
            if (node.getString("type") == "tree") continue
            require(node.getString("type") == "blob" && node.getString("mode") in setOf("100644", "100755")) { "unsafe_github_entry" }
            require(node.getLong("size") in 0..SkillStore.MAX_FILE_BYTES.toLong()) { "resource_too_large" }
            require(node.getString("sha").matches(Regex("[a-f0-9]{40}"))) { "invalid_blob" }
            nodes[path] = node
        }
        return nodes
    }
    private fun get(url: String, limit: Int, operation: GitHubOperation): ByteArray {
        operation.check()
        val bytes = fetch?.invoke(url, limit) ?: publicGet(url, limit, operation)
        operation.check()
        require(bytes.size <= limit) { "download_too_large" }
        operation.transferred += bytes.size
        require(operation.transferred <= 32L * 1024 * 1024) { "download_too_large" }
        return bytes
    }
    private fun json(url: String, operation: GitHubOperation) = SkillFiles.text(get(url, 4 * 1024 * 1024, operation))
    private fun api(owner: String, repo: String) = "https://api.github.com/repos/$owner/$repo"
    private fun validateRepository(owner: String, repo: String) {
        require(owner.matches(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}")) && repo.matches(Regex("[A-Za-z0-9_.-]{1,100}")) && repo !in setOf(".", "..")) { "invalid_repository" }
    }
    private fun encodeDefaultRef(ref: String): String {
        // API metadata is not URL syntax: accept slash-containing Git refs, but bound and
        // validate them before encoding the entire UTF-8 value as ONE commits path segment.
        require(ref.length in 1..200 && ref != "@" && !ref.endsWith('.') &&
            !ref.contains("..") && !ref.contains("@{") &&
            ref.none { it.isISOControl() || it == ' ' || it in "~^:?*[\\" } &&
            ref.split('/').none { it.isEmpty() || it.startsWith('.') || it.endsWith(".lock") } &&
            Charsets.UTF_8.newEncoder().canEncode(ref)) { "invalid_ref" }
        val hex = "0123456789ABCDEF"
        return buildString {
            for (byte in ref.toByteArray(Charsets.UTF_8)) {
                val value = byte.toInt() and 255
                if (value in 65..90 || value in 97..122 || value in 48..57 || value.toChar() in "-._~") {
                    append(value.toChar())
                } else {
                    append('%'); append(hex[value ushr 4]); append(hex[value and 15])
                }
            }
        }
    }
    private fun validateCommit(commit: String) { require(commit.matches(Regex("[a-f0-9]{40}"))) { "invalid_commit" } }
    private fun validatePath(path: String) {
        require(path.length in 1..512 && !path.startsWith('/') && '\\' !in path && path.none { it.code < 32 } && path.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "unsafe_github_path" }
    }
}

private class GitHubOperation(timeoutMillis: Long, private val nanoTime: () -> Long, private val cancellationCheck: () -> Unit) {
    private val started = nanoTime()
    private val timeoutNanos = timeoutMillis.coerceIn(1, 60000) * 1000000L
    var transferred = 0L
    fun check() {
        cancellationCheck()
        require(nanoTime() - started < timeoutNanos) { "github_operation_timeout" }
    }
    fun timeout(maxMillis: Int): Int {
        check()
        return ((timeoutNanos - (nanoTime() - started)) / 1000000L).coerceIn(1, maxMillis.toLong()).toInt()
    }
}

private fun publicGet(url: String, limit: Int, operation: GitHubOperation): ByteArray {
    operation.check()
    val target = URL(url)
    require(target.protocol == "https" && target.host in setOf("api.github.com", "codeload.github.com") && target.userInfo == null && target.port == -1) { "invalid_download_url" }
    val connection = target.openConnection() as HttpsURLConnection
    try {
        connection.instanceFollowRedirects = false
        connection.connectTimeout = operation.timeout(15000); connection.readTimeout = operation.timeout(30000)
        connection.setRequestProperty("Accept", "application/vnd.github+json"); connection.setRequestProperty("User-Agent", "Daiyu-Skills")
        // Explicitly suppress ambient authentication/cookies; no secrets enter this transport.
        connection.setRequestProperty("Cookie", ""); connection.setRequestProperty("Authorization", "")
        connection.connect()
        operation.check()
        connection.readTimeout = operation.timeout(30000)
        require(connection.responseCode == 200) { "github_http_error" }
        operation.check()
        require(connection.contentLengthLong <= limit) { "download_too_large" }
        return connection.inputStream.use { input ->
            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) {
                connection.readTimeout = operation.timeout(30000)
                val count = input.read(buffer); operation.check(); if (count < 0) break
                require(output.size() + count <= limit) { "download_too_large" }; output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    } finally { connection.disconnect() }
}
