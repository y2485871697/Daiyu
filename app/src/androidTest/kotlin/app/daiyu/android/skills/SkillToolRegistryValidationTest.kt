package app.daiyu.android.skills

import android.content.ContextWrapper
import android.content.SharedPreferences
import android.test.InstrumentationTestCase
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Suppress("DEPRECATION")
class SkillToolRegistryValidationTest : InstrumentationTestCase() {
    private val commit = "a".repeat(40)
    private val revision = "b".repeat(64)
    private val content = "---\nname: demo\ndescription: Validation fixture\n---\nPinned instructions.".toByteArray()

    /** Each fixture owns both filesDir and preferences; never toggles the app's real registry. */
    private inner class Fixture(val rootSelection: Boolean = false) : AutoCloseable {
        private val base = instrumentation.targetContext
        private val token = "registry-validation-${UUID.randomUUID()}"
        val root = File(base.cacheDir, token).apply { check(mkdir()) }
        private val preferences = mutableSetOf<String>()
        val context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = root
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val isolated = "$token-$name"
                preferences.add(isolated)
                return base.getSharedPreferences(isolated, mode)
            }
        }
        val user = SkillStore(File(root, "runtime-skills-v1"))
        val builtin = SkillStore(File(root, "agent-builtin-skills-v1"))
        val calls = mutableListOf<String>()
        val incoming = SkillFiles.hash("SKILL.md\u0000${SkillFiles.hash(content)}".toByteArray())
        val registry: SkillToolRegistry
        init {
            user.recover(); builtin.recover()
            val path = if (rootSelection) "SKILL.md" else "demo/SKILL.md"
            val blob = MessageDigest.getInstance("SHA-1").apply {
                update("blob ${content.size}\u0000".toByteArray()); update(content)
            }.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            val tree = JSONObject().put("truncated", false).put("tree", JSONArray().put(JSONObject()
                .put("path", path).put("type", "blob").put("mode", "100644").put("size", content.size).put("sha", blob))).toString().toByteArray()
            val zip = ByteArrayOutputStream().also { bytes ->
                ZipOutputStream(bytes).use { out ->
                    out.putNextEntry(ZipEntry("repo-$commit/$path")); out.write(content); out.closeEntry()
                }
            }.toByteArray()
            val github = GitHubSkills { url, _ ->
                calls.add(url)
                when {
                    url.contains("/git/trees/$commit?") -> tree
                    url == "https://codeload.github.com/owner/repo/zip/$commit" -> zip
                    url.endsWith("/commits/main") -> JSONObject().put("sha", commit).toString().toByteArray()
                    else -> error("unexpected_url")
                }
            }
            registry = SkillToolRegistry(context, github, builtin, user)
            registry.enabled = true
        }
        fun arguments(): JSONObject = JSONObject().put("owner", "owner").put("repo", "repo").put("commit", commit)
            .put("directories", JSONArray().put(if (rootSelection) "" else "demo"))
            .put("incoming_revisions", JSONObject().put("demo", incoming))
        private fun disk(): Map<String, String> = root.walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(root).path to SkillFiles.hash(it.readBytes()) }
        fun rejected(args: JSONObject, code: String = "invalid_arguments", tool: String = "skills_install_from_github") {
            val before = disk()
            val fetches = calls.size
            val result = registry.execute(tool, args)
            assertFalse(result.toString(), result.getBoolean("ok"))
            assertEquals(result.toString(), code, result.getString("error"))
            assertEquals("Invalid invocation must not fetch", fetches, calls.size)
            assertEquals("Invalid invocation must not mutate stores", before, disk())
            assertTrue("Invalid invocation must not install", user.list().isEmpty())
        }
        override fun close() {
            preferences.forEach { base.deleteSharedPreferences(it) }
            root.deleteRecursively()
        }
    }

    fun testEveryMissingRequiredInstallFieldFailsBeforeFetch() {
        Fixture().use { f ->
            for (key in listOf("owner", "repo", "commit", "directories", "incoming_revisions")) {
                f.rejected(f.arguments().also { it.remove(key) })
            }
        }
    }

    fun testNullWrongTypesAndUnknownInstallFieldsFailBeforeFetch() {
        Fixture().use { f ->
            for (key in listOf("owner", "repo", "commit")) {
                for (value in listOf(JSONObject.NULL, 1, true, JSONArray(), JSONObject())) {
                    f.rejected(f.arguments().put(key, value))
                }
            }
            for (key in listOf("directories", "incoming_revisions", "replacements")) {
                for (value in listOf(JSONObject.NULL, 1, true, "wrong")) {
                    f.rejected(f.arguments().put(key, value))
                }
            }
            f.rejected(f.arguments().put("directories", JSONObject()))
            f.rejected(f.arguments().put("incoming_revisions", JSONArray()))
            f.rejected(f.arguments().put("replacements", JSONObject()))
            for (key in listOf("unknown", "approved", "model_approved")) f.rejected(f.arguments().put(key, true))
        }
    }

    fun testRepositoryCommitAndDirectoryFormatsAndBoundsFailBeforeFetch() {
        Fixture().use { f ->
            for (owner in listOf("", "-owner", "a".repeat(40), "owner/repo"))
                f.rejected(f.arguments().put("owner", owner), "invalid_repository")
            for (repo in listOf("", ".", "..", "a".repeat(101), "repo?query"))
                f.rejected(f.arguments().put("repo", repo), "invalid_repository")
            for (sha in listOf("main", "A".repeat(40), "a".repeat(39), "a".repeat(41)))
                f.rejected(f.arguments().put("commit", sha), "invalid_commit")
            f.rejected(f.arguments().put("directories", JSONArray()), "invalid_selection")
            f.rejected(f.arguments().put("directories", JSONArray(List(101) { "dir-$it" })), "invalid_selection")
            f.rejected(f.arguments().put("directories", JSONArray(listOf("demo", "demo"))), "invalid_selection")
            for (value in listOf(JSONObject.NULL, 1, JSONObject(), JSONArray()))
                f.rejected(f.arguments().put("directories", JSONArray().put(value)))
            for (path in listOf("/demo", "../demo", "demo/..", "demo//child", "demo\\child", "demo\u0000", "a".repeat(513)))
                f.rejected(f.arguments().put("directories", JSONArray().put(path)), "unsafe_github_path")
        }
    }

    fun testIncomingMapKeysValuesAndCardinalityFailBeforeFetch() {
        Fixture().use { f ->
            f.rejected(f.arguments().put("incoming_revisions", JSONObject()))
            f.rejected(f.arguments().put("incoming_revisions", JSONObject().put("demo", revision).put("other", revision)))
            for (key in listOf("", "user:demo", "../demo", "Demo", "a".repeat(65), "two--hyphens"))
                f.rejected(f.arguments().put("incoming_revisions", JSONObject().put(key, revision)))
            for (value in listOf(JSONObject.NULL, 1, true, JSONObject(), JSONArray()))
                f.rejected(f.arguments().put("incoming_revisions", JSONObject().put("demo", value)))
            for (value in listOf("", "a".repeat(63), "a".repeat(65), "A".repeat(64), "z".repeat(64)))
                f.rejected(f.arguments().put("incoming_revisions", JSONObject().put("demo", value)), "invalid_revision")
        }
    }

    fun testReplacementsAreExactBoundedUserIdsAndRevisionsBeforeFetch() {
        Fixture().use { f ->
            fun item(id: String = "user:demo", rev: String = revision) = JSONObject().put("id", id).put("revision", rev)
            fun args(value: Any) = f.arguments().put("replacements", JSONArray().put(value))
            for (value in listOf(JSONObject.NULL, 1, "user:demo", JSONArray())) f.rejected(args(value))
            for (value in listOf(JSONObject(), JSONObject().put("id", "user:demo"), JSONObject().put("revision", revision), item().put("extra", true)))
                f.rejected(args(value))
            for (value in listOf(JSONObject.NULL, 1, JSONObject())) {
                f.rejected(args(item().put("id", value)))
                f.rejected(args(item().put("revision", value)))
            }
            for (id in listOf("builtin:demo", "demo", "user:", "user:../demo", "user:Demo", "user:demo:extra", "user:${"a".repeat(65)}", "user:other"))
                f.rejected(args(item(id)), "invalid_replacement")
            for (rev in listOf("", "a".repeat(63), "a".repeat(65), "A".repeat(64)))
                f.rejected(args(item(rev = rev)), "invalid_revision")
            f.rejected(f.arguments().put("replacements", JSONArray().put(item()).put(item())))
            f.rejected(f.arguments().put("replacements", JSONArray(List(101) { item("user:demo") })))
            // Duplicate IDs also fail when count alone is within the selected-directory limit.
            f.rejected(f.arguments().put("directories", JSONArray(listOf("demo", "other")))
                .put("incoming_revisions", JSONObject().put("demo", revision).put("other", revision))
                .put("replacements", JSONArray().put(item()).put(item())), "invalid_replacement")
        }
    }

    fun testOtherToolsUseTheSameRequiredUnknownTypeAndFormatBoundary() {
        Fixture().use { f ->
            f.rejected(JSONObject().put("extra", true), tool = "skills_list")
            for (tool in listOf("skills_read", "skills_read_resource")) {
                val args = JSONObject().put("id", "user:demo").put("revision", revision)
                if (tool == "skills_read_resource") args.put("path", "references/a.txt")
                for (key in args.keys().asSequence().toList()) {
                    f.rejected(JSONObject(args.toString()).also { it.remove(key) }, tool = tool)
                    f.rejected(JSONObject(args.toString()).put(key, JSONObject.NULL), tool = tool)
                    f.rejected(JSONObject(args.toString()).put(key, 1), tool = tool)
                }
                f.rejected(JSONObject(args.toString()).put("extra", 1), tool = tool)
                f.rejected(JSONObject(args.toString()).put("id", "user:../demo"), "invalid_id", tool)
                f.rejected(JSONObject(args.toString()).put("revision", "main"), "invalid_revision", tool)
            }
            f.rejected(JSONObject(), tool = "skills_inspect_github")
            f.rejected(JSONObject().put("url", JSONObject.NULL), tool = "skills_inspect_github")
            f.rejected(JSONObject().put("url", 1), tool = "skills_inspect_github")
            for (url in listOf("http://github.com/owner/repo", "https://evil.example/owner/repo", "https://github.com/owner/repo?token=x", "https://github.com/owner/repo#x", "x".repeat(4097))) {
                f.rejected(JSONObject().put("url", url), "invalid_github_url", "skills_inspect_github")
            }
            f.rejected(JSONObject().put("url", "https://github.com/owner/repo/tree/main/../demo"), "unsafe_github_path", "skills_inspect_github")
            for (path in listOf("", "../x", ".skill.json", "references/.hidden", "/x", "x\\y", "x".repeat(513)))
                f.rejected(JSONObject().put("id", "user:demo").put("revision", revision).put("path", path), "unsafe_path", "skills_read_resource")
        }
    }

    fun testIntegerLimitsAcceptLongButRejectFloatingPointAndOverflow() {
        Fixture().use { f ->
            fun args(value: Any) = JSONObject().put("id", "user:demo").put("revision", revision).put("path", "SKILL.md").put("max_bytes", value)
            for (value in listOf(1.0, 1.5, 262144.0, "1", true, JSONObject.NULL))
                f.rejected(args(value), tool = "skills_read_resource")
            for (value in listOf(0, -1, SkillStore.MAX_FILE_BYTES + 1, 0L, -1L, Long.MAX_VALUE, Long.MIN_VALUE))
                f.rejected(args(value), "invalid_limit", "skills_read_resource")
            val installed = f.user.install(listOf(SkillBundle(mapOf("SKILL.md" to content)))).single()
            for (value in listOf(content.size, content.size.toLong(), SkillStore.MAX_FILE_BYTES, SkillStore.MAX_FILE_BYTES.toLong())) {
                val result = f.registry.execute("skills_read_resource", args(value).put("revision", installed.revision))
                assertTrue(result.toString(), result.getBoolean("ok"))
                assertEquals(String(content), result.getString("content"))
            }
            val one = f.registry.execute("skills_read_resource", args(1L).put("revision", installed.revision))
            assertEquals("resource_too_large", one.getString("error"))
            assertTrue(f.calls.isEmpty())
        }
    }

    fun testMaximumSizeStructurallyValidSelectionAndEmptyReplacementsAreAccepted() {
        val directories = JSONArray()
        val incoming = JSONObject()
        val replacements = JSONArray()
        repeat(SkillStore.MAX_SKILLS) { index ->
            directories.put("directory-$index")
            incoming.put("skill-$index", revision)
            replacements.put(JSONObject().put("id", "user:skill-$index").put("revision", revision))
        }
        val args = JSONObject().put("owner", "owner").put("repo", "repo").put("commit", commit)
            .put("directories", directories).put("incoming_revisions", incoming).put("replacements", replacements)
        val validated = SkillInvocationValidation.validate("skills_install_from_github", args)!!
        assertEquals(100, validated.selection.directories.size)
        assertEquals(100, validated.incoming.size)
        assertEquals(100, validated.replacements.size)
        args.put("replacements", JSONArray())
        assertTrue(SkillInvocationValidation.validate("skills_install_from_github", args)!!.replacements.isEmpty())
    }

    fun testLegalInstallRootSelectionAndExactReplacementAreNotBlocked() {
        for (root in listOf(false, true)) Fixture(root).use { f ->
            val result = f.registry.execute("skills_install_from_github", f.arguments())
            assertTrue(result.toString(), result.getBoolean("ok"))
            assertEquals(2, f.calls.size)
            val installed = f.user.list().single()
            assertEquals(f.incoming, installed.revision)
            assertTrue(installed.enabled)
            f.user.setEnabled("demo", false, installed.revision)
            val replacement = f.arguments().put("replacements", JSONArray().put(JSONObject().put("id", "user:demo").put("revision", installed.revision)))
            val replaced = f.registry.execute("skills_install_from_github", replacement)
            assertTrue(replaced.toString(), replaced.getBoolean("ok"))
            assertEquals(4, f.calls.size)
            assertTrue(f.user.list().single().enabled)
            val read = f.registry.execute("skills_read", JSONObject().put("id", "user:demo").put("revision", installed.revision))
            assertTrue(read.toString(), read.getBoolean("ok"))
            assertEquals(String(content), read.getString("content"))
            val inspect = f.registry.execute("skills_inspect_github", JSONObject().put("url", "https://github.com/owner/repo/tree/main"))
            assertTrue(inspect.toString(), inspect.getBoolean("ok"))
            assertEquals(f.incoming, inspect.getJSONArray("candidates").getJSONObject(0).getString("incoming_revision"))
        }
    }
}
