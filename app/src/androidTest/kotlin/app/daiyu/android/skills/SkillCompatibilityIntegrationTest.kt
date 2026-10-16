package app.daiyu.android.skills

import android.test.InstrumentationTestCase
import app.daiyu.android.domain.tools.SessionCapabilities
import app.daiyu.android.domain.tools.TaskAuthorization
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Real store/management/capture paths; temporary roots and injected GitHub only. */
@Suppress("DEPRECATION")
class SkillCompatibilityIntegrationTest : InstrumentationTestCase() {
    private fun markdown(extra: String = "", body: String = "Portable instructions", name: String = "demo") =
        "---\nname: $name\ndescription: Install test\n${extra}---\n$body\n".toByteArray(Charsets.UTF_8)

    private fun incompatible(action: () -> Unit) {
        try { action(); fail("expected static compatibility denial") }
        catch (failure: SkillIncompatible) {
            assertEquals("skill_incompatible", failure.message)
            assertFalse(failure.compatibility.available)
            assertNotNull(failure.compatibility.reason)
        }
    }

    fun testPreviewDisplaysRealDenialsAndCannotPublishEvenViaManagementApi() {
        for (input in listOf(
            markdown("compatibility: HomeKit\n"),
            markdown("metadata:\n  runtime: android-a11y-cli\n"),
            markdown(body = "Requires codex-on-ish"),
            markdown("compatibility: iOS-only\n"),
        )) {
            SkillInstallFixture(instrumentation.targetContext).use { f ->
                f.content = input
                val old = f.user.install(listOf(f.oldBundle())).single()
                val preview = f.preview()
                assertFalse(preview.compatible)
                assertFalse(preview.changes.single().compatibility.available)
                assertNotNull(preview.changes.single().compatibility.reason)
                val calls = f.calls.size
                val rejected = f.manager.install(preview, preview.replacements) as SkillManagement.InstallResult.Rejected
                assertEquals("skill_incompatible", rejected.code)
                assertEquals(calls, f.calls.size)
                assertEquals(old, f.user.list().single())
                incompatible { f.user.preview(SkillBundle(mapOf("SKILL.md" to input))) }
                var staged = false
                f.user.transactionCheckpoint = { staged = true }
                incompatible { f.user.install(listOf(SkillBundle(mapOf("SKILL.md" to input))), mapOf("demo" to old.revision)) }
                assertFalse(staged)
                assertEquals(old, f.user.list().single())
            }
        }
    }

    fun testCompatibleExactSourceRevisionIsDownloadedRecheckedAndPreservesBothInputs() {
        SkillInstallFixture(instrumentation.targetContext).use { f ->
            f.content = markdown(
                "compatibility: >\n  Python 3; Alpine (iSH) / Termux (Android) / Debian\nmetadata:\n  runtime: portable\n",
                "Use browser_use get_cookies, curl, ffmpeg, adb and android-open.",
            )
            val preview = f.preview()
            assertTrue(preview.compatible)
            assertNull(preview.changes.single().compatibility.reason)
            val calls = f.calls.size
            val result = f.manager.install(preview, emptyMap()) as SkillManagement.InstallResult.Installed
            assertTrue(f.calls.size > calls)
            assertEquals(3, f.calls.count { it == "https://codeload.github.com/owner/repo/zip/${f.commit}" })
            assertEquals(1, f.calls.count { it.endsWith("/commits/main") })
            val installed = result.skills.single()
            assertEquals(preview.changes.single().candidate.incomingRevision, installed.revision)
            assertEquals(f.commit, installed.commit)
            assertEquals("https://github.com/owner/repo/tree/${f.commit}/demo", installed.source)
            val saved = f.user.readResource("demo", "SKILL.md", installed.revision)
            assertEquals(String(f.content, Charsets.UTF_8), saved)
            val header = SkillFrontmatter.parse(saved)
            assertEquals("portable", header.metadata["runtime"])
            assertEquals("Python 3; Alpine (iSH) / Termux (Android) / Debian", header.compatibility)
            val owner = TaskAuthorization("compatibility", "parent")
            f.registry.captureForRun(SessionCapabilities(owner.sessionId, setOf(SkillRunSession.READ)), owner, setOf("user:demo")).use { run ->
                assertEquals(saved, run.read(owner, "user:demo", installed.revision))
            }
        }
    }

    fun testChangedDependencyBetweenPreviewAndInstallCannotBypassPinnedRevision() {
        SkillInstallFixture(instrumentation.targetContext).use { f ->
            val preview = f.preview()
            f.content = markdown("metadata:\n  runtime: HealthKit\n")
            val rejected = f.manager.install(preview, emptyMap()) as SkillManagement.InstallResult.Rejected
            assertEquals("incoming_revision_conflict", rejected.code)
            assertTrue(f.user.list().isEmpty())
        }
    }

    /** Seed the old on-disk format directly: no disabling the new production checker. */
    private fun legacy(root: File, name: String, bytes: ByteArray, enabled: Boolean = true): String {
        val directory = File(root, "installed/$name").apply { check(mkdirs()) }
        val hash = SkillFiles.hash(bytes)
        val revision = SkillFiles.hash("SKILL.md\u0000$hash".toByteArray(Charsets.UTF_8))
        SkillFiles.atomic(File(directory, "SKILL.md"), bytes)
        SkillFiles.atomic(File(directory, ".skill.json"), JSONObject()
            .put("name", name).put("description", "Install test").put("revision", revision)
            .put("enabled", enabled).put("source", JSONObject.NULL).put("commit", JSONObject.NULL)
            .put("files", JSONObject().put("SKILL.md", hash)).toString().toByteArray(Charsets.UTF_8))
        return revision
    }

    fun testLegacyInstalledSkillsAreRecheckedAtCaptureWithSelectionAndDisableIsolation() {
        val base = instrumentation.targetContext
        val root = File(base.cacheDir, "compatibility-${UUID.randomUUID()}").apply { check(mkdir()) }
        try {
            val builtinRoot = File(root, "builtin")
            val userRoot = File(root, "user")
            val portable = legacy(userRoot, "portable", markdown(name = "portable"))
            val blocked = legacy(userRoot, "blocked", markdown("metadata:\n  runtime: android-a11y-cli\n", name = "blocked"))
            legacy(userRoot, "legacy-health", markdown("compatibility: HomeKit\n", name = "legacy-health"))
            legacy(userRoot, "legacy-mobile", markdown("compatibility: iOS-only\n", name = "legacy-mobile"))
            legacy(userRoot, "disabled", markdown("compatibility: HomeKit\n", name = "disabled"), enabled = false)
            legacy(builtinRoot, "built", markdown(body = "Requires codex-on-ish", name = "built"))
            run {
                val context = object : android.content.ContextWrapper(base) {
                    override fun getSharedPreferences(name: String, mode: Int) =
                        base.getSharedPreferences("$name-${root.name}", mode)
                }
                val user = SkillStore(userRoot)
                val registry = SkillToolRegistry(context, GitHubSkills { _, _ -> error("unexpected_fetch") }, SkillStore(builtinRoot), user)
                val owner = TaskAuthorization("legacy-compatibility", "parent")
                val caps = SessionCapabilities(owner.sessionId, setOf(SkillRunSession.READ))
                registry.enabled = true
                registry.captureForRun(caps, owner, setOf("user:portable", "user:disabled")).use { run ->
                    assertEquals(listOf("user:portable"), run.index(owner).map { it.id })
                    assertEquals(String(markdown(name = "portable"), Charsets.UTF_8), run.read(owner, "user:portable", portable))
                }
                incompatible { registry.captureForRun(caps, owner, setOf("user:blocked")) }
                incompatible { registry.captureForRun(caps, owner, setOf("user:legacy-health")) }
                incompatible { registry.captureForRun(caps, owner, setOf("user:legacy-mobile")) }
                incompatible { registry.captureForRun(caps, owner, setOf("builtin:built")) }
                incompatible { registry.captureForRun(caps, owner) }
                incompatible { user.snapshot() }
                incompatible { user.readResource("blocked", "SKILL.md", blocked) }
                // Same already-installed revision still cannot bypass install validation.
                incompatible {
                    user.install(listOf(SkillBundle(mapOf("SKILL.md" to markdown("metadata:\n  runtime: android-a11y-cli\n", name = "blocked")))),
                        mapOf("blocked" to blocked), mapOf("blocked" to blocked))
                }
                registry.captureForRun(caps, owner, emptySet()).use { assertTrue(it.index(owner).isEmpty()) }
                registry.enabled = false
                registry.captureForRun(caps, owner, setOf("user:blocked")).use { assertTrue(it.availableTools.isEmpty()) }
                registry.enabled = true
                registry.captureForRun(SessionCapabilities(owner.sessionId, emptySet()), owner, setOf("user:blocked")).use { assertTrue(it.availableTools.isEmpty()) }
                // Integrity is checked before policy, not accepted as a fresh compatibility assessment.
                SkillFiles.atomic(File(userRoot, "installed/blocked/SKILL.md"), markdown(name = "blocked"))
                try { registry.captureForRun(caps, owner, setOf("user:blocked")); fail("expected hash denial") }
                catch (failure: IllegalArgumentException) { assertEquals("resource_changed", failure.message) }
            }
        } finally {
            base.deleteSharedPreferences("runtime-skills-v1-settings-${root.name}")
            root.deleteRecursively()
        }
    }
}
