package app.daiyu.android.skills

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.test.InstrumentationTestCase
import app.daiyu.android.domain.tools.SessionCapabilities
import app.daiyu.android.domain.tools.TaskAuthorization
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** No network or app registry: both stores, preferences and every response belong to the fixture. */
internal class SkillInstallFixture(private val base: Context, rootSelection: Boolean = false, fixtureBuiltins: Boolean = false) : AutoCloseable {
    val commit = "a".repeat(40)
    private val token = "skill-install-${UUID.randomUUID()}"
    private val root = File(base.cacheDir, token).apply { check(mkdir()) }
    private val preferences = mutableSetOf<String>()
    private val context = object : ContextWrapper(base) {
        override fun getFilesDir() = root
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolated = "$token-$name"
            preferences.add(isolated)
            return base.getSharedPreferences(isolated, mode)
        }
    }
    val directory = if (rootSelection) "" else "demo"
    var content = markdown("new instructions")
    var additionalFiles: Map<String, ByteArray> = emptyMap()
    val calls = mutableListOf<String>()
    var fetchHook: (() -> Unit)? = null
    val user = SkillStore(File(root, "user"))
    val builtin = SkillStore(File(root, "builtin"))
    val registry = SkillToolRegistry(context, GitHubSkills { url, _ ->
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
        calls.add(url)
        fetchHook?.invoke()
        when {
            url == "https://api.github.com/repos/owner/repo" -> "{\"default_branch\":\"main\"}".toByteArray()
            url.endsWith("/commits/main") -> JSONObject().put("sha", commit).toString().toByteArray()
            url.contains("/git/trees/$commit?") -> tree()
            url == "https://codeload.github.com/owner/repo/zip/$commit" -> zip()
            else -> error("unexpected_url")
        }
    }, builtin, user, builtinCatalogFactory = { if (fixtureBuiltins) BuiltinSkillCatalog(mapOf("builtin-demo" to
        "---\nname: builtin-demo\ndescription: Trusted test package\n---\nfixture body".toByteArray())) else BuiltinSkillCatalog.empty() })
    val manager by lazy { SkillManagement.create { registry } }
    private val path get() = if (directory.isEmpty()) "SKILL.md" else "$directory/SKILL.md"
    fun markdown(body: String) = "---\nname: demo\ndescription: Install test\n---\n$body\n".toByteArray()
    fun oldBundle() = SkillBundle(mapOf("SKILL.md" to markdown("old instructions"), "resource.txt" to "old resource".toByteArray()))
    fun inspect() = manager.inspect("https://github.com/owner/repo")
    fun preview() = manager.preview(inspect(), setOf(directory))
    private fun files() = mapOf(path to content) + additionalFiles
    private fun tree(): ByteArray {
        val nodes = JSONArray()
        files().forEach { (path, content) ->
            val sha = MessageDigest.getInstance("SHA-1").apply {
                update("blob ${content.size}\u0000".toByteArray()); update(content)
            }.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            nodes.put(JSONObject().put("path", path).put("type", "blob").put("mode", "100644")
                .put("size", content.size).put("sha", sha))
        }
        return JSONObject().put("truncated", false).put("tree", nodes).toString().toByteArray()
    }
    private fun zip() = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { out ->
            files().forEach { (path, content) ->
                out.putNextEntry(ZipEntry("repo-$commit/$path")); out.write(content); out.closeEntry()
            }
        }
    }.toByteArray()
    override fun close() {
        preferences.forEach { base.deleteSharedPreferences(it) }
        root.deleteRecursively()
    }
}

@Suppress("DEPRECATION")
class SkillInstallManagementTest : InstrumentationTestCase() {
    private fun rejected(code: String, action: () -> Unit) {
        try { action(); fail("expected $code") } catch (failure: IllegalArgumentException) { assertEquals(code, failure.message) }
    }
    private fun assertRejected(code: String, result: SkillManagement.InstallResult) {
        assertTrue(result.toString(), result is SkillManagement.InstallResult.Rejected)
        assertEquals(code, (result as SkillManagement.InstallResult.Rejected).code)
    }

    fun testRootSelectionPinsCommitAndIncomingRevisionAndConsumesApprovalOnce() {
        SkillInstallFixture(instrumentation.targetContext, rootSelection = true).use { f ->
            val preview = f.preview()
            assertEquals("", preview.changes.single().candidate.directory)
            assertTrue(f.user.list().isEmpty())
            val result = f.manager.install(preview, emptyMap())
            assertTrue(result is SkillManagement.InstallResult.Installed)
            val installed = f.user.list().single()
            assertEquals(f.commit, installed.commit)
            assertEquals(preview.changes.single().candidate.incomingRevision, installed.revision)
            assertEquals("https://github.com/owner/repo/tree/${f.commit}", installed.source)
            val calls = f.calls.size
            assertRejected("stale_preview", f.manager.install(preview, emptyMap()))
            assertEquals(calls, f.calls.size)
            assertEquals(1, f.calls.count { it.endsWith("/commits/main") })
            assertEquals(3, f.calls.count { it.contains("codeload") })
        }
    }

    fun testReplacementRequiresExactCompleteApprovalAndFailedApprovalIsConsumed() {
        SkillInstallFixture(instrumentation.targetContext).use { f ->
            val old = f.user.install(listOf(f.oldBundle())).single()
            val preview = f.preview()
            assertEquals(mapOf("user:demo" to old.revision), preview.replacements)
            val calls = f.calls.size
            assertRejected("replacement_approval_required", f.manager.install(preview, emptyMap()))
            assertRejected("stale_preview", f.manager.install(preview, preview.replacements))
            assertEquals(calls, f.calls.size)
            assertEquals(old, f.user.list().single())
            val fresh = f.preview()
            assertTrue(f.manager.install(fresh, fresh.replacements) is SkillManagement.InstallResult.Installed)
        }
    }

    fun testWrongOldRevisionAndExtraApprovalCannotBeAccepted() {
        SkillInstallFixture(instrumentation.targetContext).use { f ->
            f.user.install(listOf(f.oldBundle()))
            var preview = f.preview()
            assertRejected("replacement_approval_required", f.manager.install(preview, mapOf("user:demo" to "b".repeat(64))))
            preview = f.preview()
            assertRejected("replacement_approval_required", f.manager.install(preview, preview.replacements + ("user:other" to "b".repeat(64))))
        }
    }

    fun testForgedPreviewOtherManagerAndSupersededInspectionAreRejectedWithoutFetch() {
        SkillInstallFixture(instrumentation.targetContext).use { f ->
            val first = f.inspect()
            val second = f.inspect()
            val calls = f.calls.size
            rejected("stale_inspection") { f.manager.preview(first, setOf(f.directory)) }
            assertEquals(calls, f.calls.size)
            val preview = f.manager.preview(second, setOf(f.directory))
            val forged = SkillManagement.Preview(preview.inspection, preview.changes)
            assertRejected("stale_preview", f.manager.install(forged, emptyMap()))
            val other = SkillManagement.create { f.registry }
            assertRejected("stale_preview", other.install(preview, emptyMap()))
            assertTrue(f.manager.install(preview, emptyMap()) is SkillManagement.InstallResult.Installed)
        }
    }

    fun testCancelInvalidatesPreviewAndNeverFetchesOrInstalls() {
        SkillInstallFixture(instrumentation.targetContext).use { f ->
            val preview = f.preview()
            val calls = f.calls.size
            f.manager.cancelInstall()
            assertRejected("stale_preview", f.manager.install(preview, emptyMap()))
            assertEquals(calls, f.calls.size)
            assertTrue(f.user.list().isEmpty())
        }
    }

    fun testSelectionMustBelongToInspectedDirectoriesBeforeAnyDownload() {
        SkillInstallFixture(instrumentation.targetContext).use { f ->
            val inspection = f.inspect()
            val calls = f.calls.size
            rejected("invalid_selection") { f.manager.preview(inspection, emptySet()) }
            rejected("invalid_selection") { f.manager.preview(inspection, setOf("elsewhere")) }
            assertEquals(calls, f.calls.size)
        }
    }

    fun testOnlyChosenDirectoryInstalledAndDuplicateNamesReject() {
        SkillInstallFixture(instrumentation.targetContext).use { f ->
            f.additionalFiles = mapOf("other/SKILL.md" to String(f.content).replace("name: demo", "name: other").toByteArray())
            val inspection = f.inspect()
            assertEquals(setOf("demo", "other"), inspection.candidates.map { it.directory }.toSet())
            val preview = f.manager.preview(inspection, setOf("other"))
            assertTrue(f.manager.install(preview, emptyMap()) is SkillManagement.InstallResult.Installed)
            assertEquals(listOf("other"), f.user.list().map { it.name })
            f.additionalFiles = mapOf("other/SKILL.md" to f.content)
            val duplicate = f.inspect()
            val calls = f.calls.size
            rejected("duplicate_skill") { f.manager.preview(duplicate, setOf("demo", "other")) }
            assertEquals(calls, f.calls.size)
        }
    }

    fun testConcurrentCallsConsumeSamePreviewOnlyOnce() {
        SkillInstallFixture(instrumentation.targetContext).use { f ->
            val preview = f.preview()
            val results = java.util.Collections.synchronizedList(mutableListOf<SkillManagement.InstallResult>())
            val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
            val gate = java.util.concurrent.CountDownLatch(1)
            val workers = (1..2).map {
                Thread {
                    try { gate.await(); results.add(f.manager.install(preview, emptyMap())) }
                    catch (failure: Throwable) { failures.add(failure) }
                }.apply { start() }
            }
            gate.countDown()
            workers.forEach { it.join(5000); assertFalse(it.isAlive) }
            assertTrue(failures.toString(), failures.isEmpty())
            assertEquals(1, results.count { it is SkillManagement.InstallResult.Installed })
            assertEquals(1, results.count { it is SkillManagement.InstallResult.Rejected && it.code == "stale_preview" })
            assertEquals(3, f.calls.count { it.contains("codeload") })
        }
    }

    fun testChangedIncomingContentAtPreviewOrInstallIsRejected() {
        SkillInstallFixture(instrumentation.targetContext).use { f ->
            val inspection = f.inspect()
            f.content = f.markdown("changed")
            rejected("incoming_revision_conflict") { f.manager.preview(inspection, setOf(f.directory)) }
            val preview = f.preview()
            f.content = f.markdown("changed again")
            assertRejected("incoming_revision_conflict", f.manager.install(preview, emptyMap()))
            assertTrue(f.user.list().isEmpty())
        }
    }

    fun testConcurrentReplacementMakesApprovedRevisionStaleWithoutOverwriting() {
        SkillInstallFixture(instrumentation.targetContext).use { f ->
            val old = f.user.install(listOf(f.oldBundle())).single()
            val preview = f.preview()
            val changed = f.user.install(listOf(SkillBundle(mapOf("SKILL.md" to f.markdown("other writer")))), mapOf("demo" to old.revision)).single()
            assertRejected("skill_conflict", f.manager.install(preview, preview.replacements))
            assertEquals(changed, f.user.list().single())
        }
    }

    fun testBuiltinCannotBeReplacedAtPreviewOrIfAddedAfterPreview() {
        SkillInstallFixture(instrumentation.targetContext).use { f ->
            val preview = f.preview()
            f.builtin.install(listOf(f.oldBundle()))
            assertRejected("builtin_conflict", f.manager.install(preview, emptyMap()))
            assertTrue(f.user.list().isEmpty())
            rejected("builtin_conflict") { f.preview() }
        }
    }

    fun testCommitCleanupFailureIsSuccessAndNeverReplayed() {
        SkillInstallFixture(instrumentation.targetContext).use { f ->
            val preview = f.preview()
            f.user.transactionCheckpoint = { if (it == "after_commit") error("injected_cleanup_failure") }
            val result = f.manager.install(preview, emptyMap()) as SkillManagement.InstallResult.Installed
            assertTrue(result.cleanupPending)
            f.user.transactionCheckpoint = {}
            assertEquals(result.skills, f.user.list())
            val calls = f.calls.size
            assertRejected("stale_preview", f.manager.install(preview, emptyMap()))
            assertEquals(calls, f.calls.size)
        }
    }

    fun testUnknownNeverAutomaticallyReinstallsAndApprovalRemainsConsumed() {
        SkillInstallFixture(instrumentation.targetContext).use { f ->
            val preview = f.preview()
            // Exception type alone cannot prove pre-publication failure.
            f.user.transactionCheckpoint = { if (it == "before_commit") throw IllegalArgumentException("injected_failure") }
            assertSame(SkillManagement.InstallResult.Unknown, f.manager.install(preview, emptyMap()))
            f.user.transactionCheckpoint = {}
            assertTrue(f.manager.snapshot().entries.isEmpty())
            val calls = f.calls.size
            assertRejected("stale_preview", f.manager.install(preview, emptyMap()))
            assertEquals(calls, f.calls.size)
        }
    }

    fun testNewRunSeesInstallOldRunKeepsBodyAndRejectsUpdatedResources() {
        SkillInstallFixture(instrumentation.targetContext).use { f ->
            f.user.install(listOf(f.oldBundle()))
            val owner = TaskAuthorization("skills-install", "old")
            val capabilities = SessionCapabilities(owner.sessionId, setOf(SkillRunSession.READ, SkillRunSession.RESOURCE))
            val oldRun = f.registry.captureForRun(capabilities, owner)
            try {
                val old = oldRun.index(owner).single()
                val preview = f.preview()
                assertTrue(f.manager.install(preview, preview.replacements) is SkillManagement.InstallResult.Installed)
                assertTrue(oldRun.read(owner, old.id, old.revision).contains("old instructions"))
                try { oldRun.readResource(owner, old.id, old.revision, "resource.txt"); fail("old resource accepted") }
                catch (_: SkillConflict) { }
                val nextOwner = TaskAuthorization(owner.sessionId, "new")
                val next = f.registry.captureForRun(capabilities, nextOwner)
                try {
                    val installed = next.index(nextOwner).single()
                    assertTrue(next.read(nextOwner, installed.id, installed.revision).contains("new instructions"))
                    assertFalse(next.availableTools.any { it.contains("install") || it.contains("inspect") || it.contains("execute") })
                } finally { next.close() }
            } finally { oldRun.close() }
        }
    }

    fun testInstallDoesNotEnableGlobalSettingAndMainThreadIsRejected() {
        SkillInstallFixture(instrumentation.targetContext).use { f ->
            f.manager.setGlobalEnabled(false)
            val preview = f.preview()
            var rejected = false
            instrumentation.runOnMainSync {
                try { f.manager.install(preview, emptyMap()) }
                catch (failure: IllegalStateException) { rejected = failure.message == "background_thread_required" }
            }
            assertTrue(rejected)
            assertTrue(f.manager.install(preview, emptyMap()) is SkillManagement.InstallResult.Installed)
            assertFalse(f.registry.enabled)
            assertTrue(f.user.list().single().enabled)
        }
    }
}
