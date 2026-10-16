package app.daiyu.android.skills

import android.content.ContextWrapper
import android.content.SharedPreferences
import android.test.InstrumentationTestCase
import app.daiyu.android.domain.tools.SessionCapabilities
import app.daiyu.android.domain.tools.TaskAuthorization
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Selection is a trusted parent input, not a model tool argument. */
@Suppress("DEPRECATION")
class SkillRunSelectionTest : InstrumentationTestCase() {
    private inner class Fixture : AutoCloseable {
        private val base = instrumentation.targetContext
        private val token = "run-selection-${UUID.randomUUID()}"
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
        val builtin = SkillStore(File(root, "agent-builtin-skills-v1"))
        val user = SkillStore(File(root, "runtime-skills-v1"))
        val registry = SkillToolRegistry(context, GitHubSkills { _, _ -> error("unexpected_fetch") }, builtin, user)
        val owner = TaskAuthorization("selection-session", "parent")
        val capabilities = SessionCapabilities(owner.sessionId, setOf(SkillRunSession.READ, SkillRunSession.RESOURCE))

        init { registry.enabled = true }

        fun install(store: SkillStore, name: String, body: String = name, previous: String? = null): InstalledSkill =
            store.install(listOf(SkillBundle(mapOf(
                "SKILL.md" to "---\nname: $name\ndescription: Selection fixture\n---\n$body".toByteArray(Charsets.UTF_8),
                "references/guide.md" to "Resource: $body".toByteArray(Charsets.UTF_8)
            ))), if (previous == null) emptyMap() else mapOf(name to previous)).single()

        fun corrupt(origin: String, name: String, path: String) {
            val directory = if (origin == "builtin") "agent-builtin-skills-v1" else "runtime-skills-v1"
            SkillFiles.atomic(File(root, "$directory/installed/$name/$path"), "broken".toByteArray())
        }

        override fun close() {
            preferences.forEach { base.deleteSharedPreferences(it) }
            root.deleteRecursively()
        }
    }

    private fun rejected(code: String, operation: () -> Unit) {
        try { operation(); fail("expected $code") }
        catch (failure: Exception) { assertEquals(code, failure.message) }
    }

    fun testOmittedAndNullSelectionKeepLegacyEnabledBuiltinPrecedence() { Fixture().use { f ->
        f.install(f.builtin, "shared", "Builtin")
        f.install(f.user, "shared", "User")
        f.install(f.user, "only-user")
        val disabled = f.install(f.user, "disabled")
        f.user.setEnabled(disabled.name, false, disabled.revision)
        f.registry.captureForRun(f.capabilities, f.owner).use { run ->
            assertEquals(listOf("builtin:shared", "user:only-user"), run.index(f.owner).map { it.id })
        }
        f.registry.captureForRun(f.capabilities, f.owner, selection = null).use { run ->
            assertEquals(listOf("builtin:shared", "user:only-user"), run.index(f.owner).map { it.id })
        }
    } }

    fun testExplicitEmptySelectionHasNoSkillsEvenWhenUnselectedMetadataIsBroken() { Fixture().use { f ->
        val builtin = f.install(f.builtin, "built")
        val user = f.install(f.user, "user-demo")
        f.corrupt("builtin", builtin.name, ".skill.json")
        f.corrupt("user", user.name, ".skill.json")
        f.registry.captureForRun(f.capabilities, f.owner, emptySet()).use { run ->
            assertTrue(run.index(f.owner).isEmpty())
            assertEquals(0, JSONObject(run.indexJson(f.owner)).getJSONArray("skills").length())
            rejected("skill_not_found") { run.read(f.owner, "builtin:built", builtin.revision) }
            rejected("skill_not_found") { run.readResource(f.owner, "user:user-demo", user.revision, "references/guide.md") }
        }
    } }

    fun testBareNamesResolveUniqueOriginsAndMissingOrMalformedTokensNeverExpand() { Fixture().use { f ->
        f.install(f.builtin, "self-improving-agent")
        f.install(f.user, "only-user")
        f.install(f.user, "unselected")
        f.registry.captureForRun(f.capabilities, f.owner, setOf("self-improving-agent", "only-user", "missing")).use { run ->
            assertEquals(listOf("builtin:self-improving-agent", "user:only-user"), run.index(f.owner).map { it.id })
        }
        for (token in listOf("missing", "builtin:missing", "user:missing", "builtin:only-user", "user:self-improving-agent", "other:only-user", "USER:only-user",
            "user:only-user:extra", "Only-user", " only-user", "only-user ", "user:", "../only-user", "*")) {
            f.registry.captureForRun(f.capabilities, f.owner, setOf(token)).use { run ->
                assertTrue("Token must not broaden selection: $token", run.index(f.owner).isEmpty())
            }
        }
    } }

    fun testExplicitPrefixesSelectOnlyExactOriginIncludingSameName() { Fixture().use { f ->
        val builtin = f.install(f.builtin, "shared", "Builtin")
        val user = f.install(f.user, "shared", "User")
        f.registry.captureForRun(f.capabilities, f.owner, setOf("user:shared")).use { run ->
            assertEquals(listOf("user:shared"), run.index(f.owner).map { it.id })
            assertTrue(run.read(f.owner, "user:shared", user.revision).endsWith("User"))
            rejected("skill_not_found") { run.read(f.owner, "builtin:shared", builtin.revision) }
        }
        f.registry.captureForRun(f.capabilities, f.owner, setOf("builtin:shared")).use { run ->
            assertEquals(listOf("builtin:shared"), run.index(f.owner).map { it.id })
        }
        f.registry.captureForRun(f.capabilities, f.owner, setOf("builtin:shared", "user:shared")).use { run ->
            assertEquals(listOf("builtin:shared", "user:shared"), run.index(f.owner).map { it.id })
        }
        f.user.setEnabled(user.name, false, user.revision)
        f.registry.captureForRun(f.capabilities, f.owner, setOf("user:shared")).use { run ->
            assertTrue("Disabled exact origin must not fall back to builtin", run.index(f.owner).isEmpty())
        }
    } }

    fun testBareNameCollisionFailsClosedEvenWithDisabledOrCorruptNamesake() { Fixture().use { f ->
        val builtin = f.install(f.builtin, "shared")
        f.install(f.user, "shared")
        f.install(f.user, "other")
        fun assertNarrow() {
            f.registry.captureForRun(f.capabilities, f.owner, setOf("shared", "other")).use { run ->
                assertEquals(listOf("user:other"), run.index(f.owner).map { it.id })
            }
        }
        assertNarrow()
        f.builtin.setEnabled(builtin.name, false, builtin.revision)
        assertNarrow()
        f.corrupt("builtin", builtin.name, ".skill.json")
        assertNarrow()
        f.registry.captureForRun(f.capabilities, f.owner, setOf("shared", "user:shared")).use { run ->
            assertEquals(listOf("user:shared"), run.index(f.owner).map { it.id })
        }
    } }

    fun testUnselectedBrokenBodiesAndMetadataDoNotBlockSelectedCaptureIncludingDefaultStore() { Fixture().use { f ->
        val selected = f.install(f.user, "selected")
        f.install(f.user, "bad-body")
        f.install(f.user, "bad-metadata")
        f.install(f.builtin, "bad-builtin")
        f.corrupt("user", "bad-body", "SKILL.md")
        f.corrupt("user", "bad-metadata", ".skill.json")
        f.corrupt("builtin", "bad-builtin", ".skill.json")
        // Do not inject user: its lazy construction must also avoid reading builtin metadata.
        val registry = SkillToolRegistry(f.context, GitHubSkills { _, _ -> error("unexpected_fetch") }, f.builtin)
        for (selection in listOf(setOf("selected"), setOf("user:selected"))) {
            registry.captureForRun(f.capabilities, f.owner, selection).use { run ->
                assertEquals(listOf("user:selected"), run.index(f.owner).map { it.id })
                assertTrue(run.read(f.owner, "user:selected", selected.revision).endsWith("selected"))
                assertEquals("Resource: selected", run.readResource(f.owner, "user:selected", selected.revision, "references/guide.md"))
            }
        }
        rejected("resource_changed") {
            registry.captureForRun(f.capabilities, f.owner, setOf("user:bad-body")).close()
        }
    } }

    fun testUnselectedBodiesDoNotConsumeCaptureBudget() { Fixture().use { f ->
        repeat(10) { f.install(f.builtin, "large-$it", "x".repeat(220000)) }
        f.install(f.user, "small")
        rejected("snapshot_too_large") { f.registry.captureForRun(f.capabilities, f.owner).close() }
        f.registry.captureForRun(f.capabilities, f.owner, setOf("small")).use { run ->
            assertEquals(listOf("user:small"), run.index(f.owner).map { it.id })
        }
    } }

    fun testSelectionChangesAffectOnlyNextCaptureAndAllReadsUseSameFrozenScope() { Fixture().use { f ->
        val first = f.install(f.user, "first", "First body")
        val second = f.install(f.user, "second", "Second body")
        val selected = mutableSetOf("first")
        f.registry.captureForRun(f.capabilities, f.owner, selected).use { run ->
            selected.clear(); selected.add("second")
            assertEquals(listOf("user:first"), run.index(f.owner).map { it.id })
            assertEquals("user:first", JSONObject(run.indexJson(f.owner)).getJSONArray("skills").getJSONObject(0).getString("id"))
            assertTrue(run.read(f.owner, "user:first", first.revision).endsWith("First body"))
            assertEquals("Resource: First body", run.readResource(f.owner, "user:first", first.revision, "references/guide.md"))
            rejected("skill_not_found") { run.read(f.owner, "user:second", second.revision) }
            rejected("skill_not_found") { run.readResource(f.owner, "user:second", second.revision, "references/guide.md") }
            rejected("run_mismatch") { run.index(TaskAuthorization(f.owner.sessionId, f.owner.taskId)) }
            f.install(f.user, "first", "Replacement body", first.revision)
            assertTrue(run.read(f.owner, "user:first", first.revision).endsWith("First body"))
            try {
                run.readResource(f.owner, "user:first", first.revision, "references/guide.md")
                fail("Live resources must retain revision checking")
            } catch (_: SkillConflict) { }
            f.registry.captureForRun(f.capabilities, f.owner, selected).use { next ->
                assertEquals(listOf("user:second"), next.index(f.owner).map { it.id })
            }
        }
    } }

    fun testSelectionCannotOverrideGlobalIndividualReadOrResourcePermissions() { Fixture().use { f ->
        val skill = f.install(f.user, "selected")
        val selection = setOf("selected")
        f.registry.enabled = false
        f.registry.captureForRun(f.capabilities, f.owner, selection).use { assertTrue(it.availableTools.isEmpty()) }
        f.registry.enabled = true
        f.registry.captureForRun(SessionCapabilities(f.owner.sessionId, setOf(SkillRunSession.RESOURCE)), f.owner, selection).use {
            assertTrue(it.availableTools.isEmpty())
        }
        f.registry.captureForRun(SessionCapabilities(f.owner.sessionId, setOf(SkillRunSession.READ)), f.owner, selection).use { run ->
            assertEquals(setOf("skills_list", "skills_read"), run.availableTools)
            rejected("skills_not_authorized") { run.readResource(f.owner, "user:selected", skill.revision, "references/guide.md") }
        }
        f.user.setEnabled(skill.name, false, skill.revision)
        f.registry.captureForRun(f.capabilities, f.owner, selection).use { assertTrue(it.index(f.owner).isEmpty()) }
        rejected("session_mismatch") {
            f.registry.captureForRun(SessionCapabilities("other", setOf(SkillRunSession.READ)), f.owner, emptySet()).close()
        }
    } }
}
