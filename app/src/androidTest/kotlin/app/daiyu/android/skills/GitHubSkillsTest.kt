package app.daiyu.android.skills

import android.test.InstrumentationTestCase
import org.json.JSONArray
import org.json.JSONObject

@Suppress("DEPRECATION")
class GitHubSkillsTest : InstrumentationTestCase() {
    private val commit = "a".repeat(40)
    private val content = "---\nname: demo\ndescription: Pinned test\n---\nDo not execute scripts.".toByteArray()
    private fun tree(mode: String = "100644"): ByteArray {
        val hash = java.security.MessageDigest.getInstance("SHA-1").apply { update("blob ${content.size}\u0000".toByteArray()); update(content) }.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        return JSONObject().put("truncated", false).put("tree", JSONArray().put(JSONObject().put("path", "demo/SKILL.md").put("type", "blob").put("mode", mode).put("size", content.size).put("sha", hash))).toString().toByteArray()
    }
    private fun zip(): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bytes).use { output -> output.putNextEntry(java.util.zip.ZipEntry("repo-$commit/demo/SKILL.md")); output.write(content); output.closeEntry() }
        return bytes.toByteArray()
    }
    fun testInspectResolveThenPinnedZipDownload() {
        val calls = mutableListOf<String>()
        val github = GitHubSkills { url, _ ->
            calls.add(url)
            when {
                url.endsWith("/commits/main") -> JSONObject().put("sha", commit).toString().toByteArray()
                url.contains("/git/trees/$commit?") -> tree()
                url == "https://codeload.github.com/owner/repo/zip/$commit" -> zip()
                else -> throw IllegalStateException("unexpected_url")
            }
        }
        val result = github.inspect("https://github.com/owner/repo/tree/main/demo")
        assertEquals(commit, result.getString("commit"))
        assertEquals("demo", result.getJSONArray("candidates").getJSONObject(0).getString("name"))
        assertEquals(1, calls.count { it.contains("/git/trees/") })
        assertEquals(1, calls.count { it.contains("codeload.github.com") })
        val bundle = github.download(GitHubSkills.Selection("owner", "repo", commit, listOf("demo"))).single()
        assertEquals(commit, bundle.commit); assertEquals(String(content), String(bundle.files.getValue("SKILL.md")))
        assertTrue(calls.any { it == "https://codeload.github.com/owner/repo/zip/$commit" })
        assertFalse(calls.any { it.contains("/zip/main") })
    }
    fun testRootDefaultBranchWithSlashResolvesEncodedRefThenUsesOnlySha() {
        val api = "https://api.github.com/repos/owner/repo"
        val calls = mutableListOf<String>()
        val github = GitHubSkills { url, _ ->
            calls.add(url)
            when (url) {
                api -> JSONObject().put("default_branch", "release/current").toString().toByteArray()
                "$api/commits/release%2Fcurrent" -> JSONObject().put("sha", commit).toString().toByteArray()
                "$api/git/trees/$commit?recursive=1" -> tree()
                "https://codeload.github.com/owner/repo/zip/$commit" -> zip()
                else -> error("unexpected_url: $url")
            }
        }
        val result = github.inspect("https://github.com/owner/repo")
        assertEquals(commit, result.getString("commit"))
        val candidate = result.getJSONArray("candidates").getJSONObject(0)
        assertEquals("demo", candidate.getString("directory"))
        val revision = SkillFiles.hash("SKILL.md\u0000${SkillFiles.hash(content)}".toByteArray(Charsets.UTF_8))
        assertEquals(revision, candidate.getString("incoming_revision"))
        assertEquals(listOf(api, "$api/commits/release%2Fcurrent", "$api/git/trees/$commit?recursive=1",
            "https://codeload.github.com/owner/repo/zip/$commit"), calls)
        val bundle = github.download(GitHubSkills.Selection("owner", "repo", result.getString("commit"), listOf(candidate.getString("directory")))).single()
        assertEquals(commit, bundle.commit)
        assertEquals("https://github.com/owner/repo/tree/$commit/demo", bundle.source)
        assertEquals(String(content), String(bundle.files.getValue("SKILL.md")))
        assertEquals(listOf("$api/git/trees/$commit?recursive=1", "https://codeload.github.com/owner/repo/zip/$commit"), calls.drop(4))
    }
    fun testDefaultRefUsesUtf8PathSegmentEncodingNotUrlSyntax() {
        val api = "https://api.github.com/repos/owner/repo"
        val calls = mutableListOf<String>()
        val github = GitHubSkills { url, _ ->
            calls.add(url)
            when (url) {
                api -> JSONObject().put("default_branch", "release/当前#%2F+").toString().toByteArray()
                "$api/commits/release%2F%E5%BD%93%E5%89%8D%23%252F%2B" -> JSONObject().put("sha", commit).toString().toByteArray()
                "$api/git/trees/$commit?recursive=1" -> JSONObject().put("tree", JSONArray()).toString().toByteArray()
                else -> error("unexpected_url: $url")
            }
        }
        assertEquals(commit, github.inspect("https://github.com/owner/repo").getString("commit"))
        assertEquals(3, calls.size)
        val resolveUri = java.net.URI(calls[1])
        assertNull(resolveUri.rawQuery)
        assertNull(resolveUri.rawFragment)
        assertEquals("/repos/owner/repo/commits/release%2F%E5%BD%93%E5%89%8D%23%252F%2B", resolveUri.rawPath)
    }
    fun testInvalidDefaultRefsRejectedBeforeCommitFetch() {
        val api = "https://api.github.com/repos/owner/repo"
        for (ref in listOf("", "a".repeat(201), "release/\u0000current", "release/\ncurrent", "release/\u007fcurrent",
            "release/\u0085current", "release/current?query", "release//current", "../current", "release/current.lock")) {
            val calls = mutableListOf<String>()
            val github = GitHubSkills { url, _ ->
                calls.add(url)
                assertEquals(api, url)
                JSONObject().put("default_branch", ref).toString()
                    .replace("\u007f", "\\u007f").replace("\u0085", "\\u0085").toByteArray(Charsets.UTF_8)
            }
            try { github.inspect("https://github.com/owner/repo"); fail("invalid default ref must fail") }
            catch (problem: IllegalArgumentException) { assertEquals("invalid_ref", problem.message) }
            assertEquals(listOf(api), calls)
        }
    }
    fun testDefaultRefAcceptsLengthBoundary() {
        val api = "https://api.github.com/repos/owner/repo"
        val ref = "release/" + "a".repeat(192)
        val calls = mutableListOf<String>()
        val github = GitHubSkills { url, _ ->
            calls.add(url)
            when (url) {
                api -> JSONObject().put("default_branch", ref).toString().toByteArray()
                "$api/commits/release%2F${"a".repeat(192)}" -> JSONObject().put("sha", commit).toString().toByteArray()
                "$api/git/trees/$commit?recursive=1" -> JSONObject().put("tree", JSONArray()).toString().toByteArray()
                else -> error("unexpected_url: $url")
            }
        }
        assertEquals(commit, github.inspect("https://github.com/owner/repo").getString("commit"))
        assertEquals(3, calls.size)
    }
    fun testDefaultRefStillRequiresFixedCommitSha() {
        val api = "https://api.github.com/repos/owner/repo"
        val calls = mutableListOf<String>()
        val github = GitHubSkills { url, _ ->
            calls.add(url)
            when (url) {
                api -> JSONObject().put("default_branch", "release/current").toString().toByteArray()
                "$api/commits/release%2Fcurrent" -> JSONObject().put("sha", "release/current").toString().toByteArray()
                else -> error("unexpected_url: $url")
            }
        }
        try { github.inspect("https://github.com/owner/repo"); fail("fixed SHA required") }
        catch (problem: IllegalArgumentException) { assertEquals("invalid_commit", problem.message) }
        assertEquals(2, calls.size)
    }
    fun testExplicitTreeStillTreatsSlashAsDirectoryBoundary() {
        val api = "https://api.github.com/repos/owner/repo"
        val calls = mutableListOf<String>()
        val github = GitHubSkills { url, _ ->
            calls.add(url)
            when (url) {
                "$api/commits/release" -> JSONObject().put("sha", commit).toString().toByteArray()
                "$api/git/trees/$commit?recursive=1" -> tree()
                else -> error("unexpected_url: $url")
            }
        }
        val result = github.inspect("https://github.com/owner/repo/tree/release/current")
        assertEquals(0, result.getJSONArray("candidates").length())
        assertEquals(listOf("$api/commits/release", "$api/git/trees/$commit?recursive=1"), calls)
        calls.clear()
        try { github.inspect("https://github.com/owner/repo/tree/release+current"); fail("explicit ref policy unchanged") }
        catch (problem: IllegalArgumentException) { assertEquals("invalid_ref", problem.message) }
        assertTrue(calls.isEmpty())
    }
    fun testRegistryInspectMultipleCandidatesDownloadsAndVerifiesOnce() {
        val contents = linkedMapOf(
            "first/SKILL.md" to String(content).replace("name: demo", "name: first").toByteArray(),
            "second/SKILL.md" to String(content).replace("name: demo", "name: second").toByteArray(),
            "second/references/note.txt" to "bounded reference".toByteArray()
        )
        val nodes = JSONArray()
        contents.forEach { (path, bytes) ->
            val hash = java.security.MessageDigest.getInstance("SHA-1").apply { update("blob ${bytes.size}\u0000".toByteArray()); update(bytes) }
                .digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            nodes.put(JSONObject().put("path", path).put("type", "blob").put("mode", "100644").put("size", bytes.size).put("sha", hash))
        }
        val zipBytes = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(zipBytes).use { out ->
            contents.forEach { (path, bytes) -> out.putNextEntry(java.util.zip.ZipEntry("repo-$commit/$path")); out.write(bytes); out.closeEntry() }
        }
        val calls = mutableListOf<String>()
        val github = GitHubSkills { url, _ ->
            calls.add(url)
            when {
                url.endsWith("/commits/main") -> JSONObject().put("sha", commit).toString().toByteArray()
                url.contains("/git/trees/$commit?") -> JSONObject().put("tree", nodes).put("truncated", false).toString().toByteArray()
                url == "https://codeload.github.com/owner/repo/zip/$commit" -> zipBytes.toByteArray()
                else -> error("unexpected_url")
            }
        }
        val directory = java.io.File(instrumentation.targetContext.cacheDir, "github-test-${java.util.UUID.randomUUID()}")
        try {
            val builtin = SkillStore(java.io.File(directory, "builtin"))
            val user = SkillStore(java.io.File(directory, "user"))
            val registry = SkillToolRegistry(instrumentation.targetContext, github, builtin, user)
            val result = registry.execute("skills_inspect_github", JSONObject().put("url", "https://github.com/owner/repo/tree/main"))
            assertTrue(result.toString(), result.getBoolean("ok"))
            assertEquals(2, result.getJSONArray("candidates").length())
            assertEquals(1, calls.count { it.contains("/git/trees/") })
            assertEquals(1, calls.count { it.contains("codeload.github.com") })
            assertEquals(0, calls.count { it.contains("raw.githubusercontent.com") })
            for (i in 0 until 2) {
                val candidate = result.getJSONArray("candidates").getJSONObject(i)
                val prefix = candidate.getString("directory") + "/"
                val files = contents.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }
                assertEquals(user.preview(SkillBundle(files)).second, candidate.getString("incoming_revision"))
            }
            assertTrue(user.list().isEmpty())
        } finally { directory.deleteRecursively() }
    }
    fun testWholeOperationDeadlineAndCancellationBoundaries() {
        val calls = mutableListOf<String>()
        var now = 0L
        val github = GitHubSkills { url, _ ->
            calls.add(url); now += 40_000_000L
            when {
                url.endsWith("/commits/main") -> JSONObject().put("sha", commit).toString().toByteArray()
                url.contains("/git/trees/") -> tree()
                else -> zip()
            }
        }
        github.operationTimeoutMillis = 100
        github.nanoTime = { now }
        try { github.inspect("https://github.com/owner/repo/tree/main"); fail("aggregate deadline must fail") }
        catch (problem: IllegalArgumentException) { assertEquals("github_operation_timeout", problem.message) }
        assertEquals(3, calls.size)
        calls.clear()
        github.cancellationCheck = { throw java.util.concurrent.CancellationException("skills_cancelled") }
        try { github.download(GitHubSkills.Selection("owner", "repo", commit, listOf("demo"))); fail("cancel before fetch") }
        catch (_: java.util.concurrent.CancellationException) { }
        assertTrue(calls.isEmpty())
    }
    fun testCancellationAfterFetchAndDuringArchiveValidation() {
        var cancelled = false; var calls = 0
        val github = GitHubSkills { _, _ -> calls++; cancelled = true; tree() }
        github.cancellationCheck = { if (cancelled) throw java.util.concurrent.CancellationException("skills_cancelled") }
        try { github.download(GitHubSkills.Selection("owner", "repo", commit, listOf("demo"))); fail("cancel after fetch") }
        catch (_: java.util.concurrent.CancellationException) { }
        assertEquals(1, calls)
        var checks = 0
        try { SkillZipArchive.read(zip(), commit) { if (++checks == 3) throw java.util.concurrent.CancellationException("skills_cancelled") }; fail("cancel in validation") }
        catch (_: java.util.concurrent.CancellationException) { }
        assertEquals(3, checks)
    }
    fun testRegistryReportsCommittedCleanupFaultAsSuccessNotRetryableFailure() {
        val directory = java.io.File(instrumentation.targetContext.cacheDir, "github-cleanup-${java.util.UUID.randomUUID()}")
        try {
            val user = SkillStore(java.io.File(directory, "user"))
            val old = user.install(listOf(SkillBundle(mapOf("SKILL.md" to String(content).replace("Do not execute scripts.", "Old instructions.").toByteArray())))).single()
            user.setEnabled("demo", false, old.revision)
            val incoming = user.preview(SkillBundle(mapOf("SKILL.md" to content))).second
            user.transactionCheckpoint = { if (it == "before_cleanup") throw java.io.IOException("injected_failure") }
            val github = GitHubSkills { url, _ -> if (url.contains("/git/trees/")) tree() else zip() }
            val registry = SkillToolRegistry(instrumentation.targetContext, github, SkillStore(java.io.File(directory, "builtin")), user)
            val result = registry.execute("skills_install_from_github", JSONObject().put("owner", "owner").put("repo", "repo").put("commit", commit)
                .put("directories", JSONArray().put("demo")).put("incoming_revisions", JSONObject().put("demo", incoming))
                .put("replacements", JSONArray().put(JSONObject().put("id", "user:demo").put("revision", old.revision))))
            assertTrue(result.toString(), result.getBoolean("ok"))
            assertTrue(result.getBoolean("committed"))
            assertTrue(result.getBoolean("cleanup_pending"))
            assertEquals(incoming, result.getJSONArray("skills").getJSONObject(0).getString("revision"))
            user.transactionCheckpoint = {}
            assertEquals(incoming, user.list().single().revision)
            assertTrue(user.list().single().enabled)
        } finally { directory.deleteRecursively() }
    }
    fun testArchiveBlobMismatchRejected() {
        val bytes = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bytes).use { out ->
            out.putNextEntry(java.util.zip.ZipEntry("repo-$commit/demo/SKILL.md")); out.write(content.copyOf().also { it[it.lastIndex] = '!'.code.toByte() }); out.closeEntry()
        }
        val github = GitHubSkills { url, _ -> if (url.contains("/git/trees/")) tree() else bytes.toByteArray() }
        try { github.download(GitHubSkills.Selection("owner", "repo", commit, listOf("demo"))); fail("blob mismatch") }
        catch (problem: IllegalArgumentException) { assertEquals("download_changed", problem.message) }
    }
    fun testSymlinkAndNonCommitAreRejected() {
        val github = GitHubSkills { url, _ -> if (url.contains("/git/trees/")) tree("120000") else zip() }
        try { github.download(GitHubSkills.Selection("owner", "repo", "main", listOf("demo"))); fail("fixed SHA required") } catch (_: IllegalArgumentException) { }
        try { github.download(GitHubSkills.Selection("owner", "repo", commit, listOf("demo"))); fail("symlink must fail") } catch (_: IllegalArgumentException) { }
    }
}
