package app.daiyu.android.skills

import android.content.Context
import android.content.ContextWrapper
import android.test.InstrumentationTestCase
import app.daiyu.android.assistants.AssistantDefaults
import app.daiyu.android.domain.tools.SessionCapabilities
import app.daiyu.android.domain.tools.TaskAuthorization
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

@Suppress("DEPRECATION")
class DefaultSkillsRemovalTest : InstrumentationTestCase() {
    private fun fixture(work: (Context, File) -> Unit) {
        val base = instrumentation.targetContext
        val token = "retired-skills-${UUID.randomUUID()}"
        val root = File(base.cacheDir, token).apply { check(mkdir()) }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = root
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(token, mode)
        }
        try { work(context, root) }
        finally { root.deleteRecursively(); base.deleteSharedPreferences(token) }
    }

    private fun bundle(name: String, body: String = "previous") = SkillBundle(mapOf(
        "SKILL.md" to "---\nname: $name\ndescription: Removal fixture\n---\n$body".toByteArray()))

    fun testFreshRegistryNeverSeedsDefaultsEvenWithGlobalOff() = fixture { context, root ->
        val registry = SkillToolRegistry(context)
        assertTrue(AssistantDefaults.ENABLED_SKILL_IDS.isEmpty())
        assertTrue(BuiltinSkillCatalog(context).names.isEmpty())
        registry.enabled = false
        repeat(2) {
            assertTrue(registry.managementState().entries.isEmpty())
            assertEquals(0, registry.execute("skills_list", JSONObject()).getJSONArray("skills").length())
            assertFalse(registry.enabled)
        }
        registry.enabled = true
        val owner = TaskAuthorization("default-skills", "empty")
        registry.captureForRun(SessionCapabilities(owner.sessionId, setOf(SkillRunSession.READ)), owner,
            AssistantDefaults.ENABLED_SKILL_IDS.toSet()).use { assertTrue(it.index(owner).isEmpty()) }
        for (name in BuiltinSkillCatalog.RETIRED_NAMES) {
            assertFalse(File(root, "agent-builtin-skills-v1/installed/$name").exists())
        }
    }

    fun testUpgradeRemovesOnlyBuiltinCopiesAndPreservesUserNamesakes() = fixture { context, root ->
        val legacy = SkillStore(File(root, "agent-builtin-skills-v1"))
        val user = SkillStore(context)
        val names = BuiltinSkillCatalog.RETIRED_NAMES
        val installed = legacy.install((names + "kept-builtin").map { bundle(it) })
        legacy.setEnabled(installed.first().name, false, installed.first().revision)
        val users = user.install((names + "user-demo").map { bundle(it, "user body") })
        user.setEnabled(users.first().name, false, users.first().revision)
        val before = user.list()
        val bytes = before.associate { it.name to File(root, "runtime-skills-v1/installed/${it.name}/SKILL.md").readBytes() }
        val registry = SkillToolRegistry(context)
        registry.enabled = false
        val state = registry.managementState()
        assertFalse(state.entriesUnavailable)
        assertEquals(setOf("kept-builtin"), state.entries.filter { it.builtin }.map { it.skill.name }.toSet())
        assertEquals(before, user.list())
        for ((name, body) in bytes) assertTrue(body.contentEquals(File(root, "runtime-skills-v1/installed/$name/SKILL.md").readBytes()))
        assertEquals(before.map { "user:${it.name}" }.toSet(), state.entries.filterNot { it.builtin }.map { it.id }.toSet())
        repeat(2) {
            assertEquals(state.entries, SkillToolRegistry(context).managementState().entries)
            assertTrue(names.none { File(root, "agent-builtin-skills-v1/installed/$it").exists() })
        }
        registry.enabled = true
        val owner = TaskAuthorization("default-skills", "origins")
        val capabilities = SessionCapabilities(owner.sessionId, setOf(SkillRunSession.READ))
        registry.captureForRun(capabilities, owner, names.map { "builtin:$it" }.toSet()).use {
            assertTrue(it.index(owner).isEmpty())
        }
        registry.captureForRun(capabilities, owner, before.filter { it.enabled }.map { "user:${it.name}" }.toSet()).use {
            assertEquals(before.filter { it.enabled }.map { "user:${it.name}" }.toSet(), it.index(owner).map { entry -> entry.id }.toSet())
        }
    }

    fun testCorruptRetiredMetadataDoesNotBlockCleanupOrUserSkills() = fixture { context, root ->
        val legacy = SkillStore(File(root, "agent-builtin-skills-v1"))
        legacy.install(BuiltinSkillCatalog.RETIRED_NAMES.map { bundle(it) })
        for (name in BuiltinSkillCatalog.RETIRED_NAMES) {
            File(root, "agent-builtin-skills-v1/installed/$name/.skill.json").writeText("broken metadata")
        }
        val user = SkillStore(context).install(listOf(bundle("user-demo"))).single()
        val state = SkillToolRegistry(context).managementState()
        assertFalse(state.entriesUnavailable)
        assertEquals(listOf("user:user-demo"), state.entries.map { it.id })
        assertEquals(user, state.entries.single().skill)
    }

    private fun assertRecoveredRetirement(committed: Boolean) = fixture { context, root ->
        val directory = File(root, "agent-builtin-skills-v1")
        val legacy = SkillStore(directory)
        legacy.install(listOf(bundle("skill-creator"), bundle("kept-builtin")))
        val kept = legacy.list().single { it.name == "kept-builtin" }
        val transaction = ".txn-${UUID.randomUUID()}"
        val txn = File(directory, transaction)
        check(File(txn, "backup").mkdirs()); check(File(txn, "stage").mkdir())
        val target = File(directory, "installed/skill-creator")
        check(target.renameTo(File(txn, "backup/skill-creator")))
        check(target.mkdir()); File(target, "SKILL.md").writeText("partially published")
        File(directory, ".transaction.json").writeText(JSONObject().put("transaction", transaction)
            .put("committed", committed).put("entries", JSONArray().put(JSONObject()
                .put("name", "skill-creator").put("had_previous", true))).toString())
        val state = SkillToolRegistry(context).managementState()
        assertFalse(state.entriesUnavailable)
        assertEquals(listOf(kept), state.entries.map { it.skill })
        assertFalse(target.exists()); assertFalse(txn.exists())
        assertFalse(File(directory, ".transaction.json").exists())
    }

    fun testRollbackRecoveryCannotResurrectRetiredPackage() = assertRecoveredRetirement(false)
    fun testCommittedCleanupCannotResurrectRetiredPackage() = assertRecoveredRetirement(true)

    fun testRepeatedAccessRetiresReappearedCopiesAndRejectsOldRestore() = fixture { context, root ->
        val legacy = SkillStore(File(root, "agent-builtin-skills-v1"))
        val registry = SkillToolRegistry(context)
        repeat(2) {
            legacy.install(listOf(bundle("skill-creator")))
            assertTrue(registry.managementState().entries.isEmpty())
        }
        val manager = SkillManagement.create { registry }
        assertEquals(SkillManagement.InstallResult.Rejected("builtin_not_available"),
            manager.reinstallBuiltin("builtin:skill-creator", "0".repeat(64)))
        val retired = SkillStore(File(root, "agent-builtin-skills-v1"), retiredNames = BuiltinSkillCatalog.RETIRED_NAMES)
        try { retired.install(listOf(bundle("skill-creator"))); fail("retired builtin installed") }
        catch (failure: IllegalArgumentException) { assertEquals("builtin_retired", failure.message) }
    }

    fun testFormerDefaultNamesRemainInstallableAsUserSkills() = fixture { context, _ ->
        val registry = SkillToolRegistry(context)
        val bundles = BuiltinSkillCatalog.RETIRED_NAMES.map { bundle(it, "explicit user install") }
        val incoming = bundles.associate { bundle ->
            val preview = registry.previewForManagement(bundle)
            preview.header.name to preview.revision
        }
        val installed = registry.installForManagement(bundles, emptyMap(), incoming)
        assertEquals(BuiltinSkillCatalog.RETIRED_NAMES, installed.map { it.name }.toSet())
        assertTrue(registry.managementState().entries.all { !it.builtin })
        assertEquals(installed.associateBy { it.name }, SkillStore(context).list().associateBy { it.name })
    }
}
