package app.daiyu.android.skills

import android.content.ContextWrapper
import android.content.SharedPreferences
import android.test.InstrumentationTestCase
import java.io.File
import java.util.UUID

@Suppress("DEPRECATION")
class SkillRegistrySnapshotTest : InstrumentationTestCase() {
    /** Own both stores and preferences; never change the app's real registry. */
    private inner class Fixture : AutoCloseable {
        private val base = instrumentation.targetContext
        private val token = "registry-snapshot-${UUID.randomUUID()}"
        val root = File(base.cacheDir, token).apply { check(mkdir()) }
        private val preferences = mutableSetOf<String>()
        private val context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = root
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val isolated = "$token-$name"
                preferences.add(isolated)
                return base.getSharedPreferences(isolated, mode)
            }
        }
        val builtin = SkillStore(File(root, "builtin"))
        val user = SkillStore(File(root, "user"))
        val registry = SkillToolRegistry(context, GitHubSkills { _, _ -> error("unexpected_fetch") }, builtin, user)

        init { registry.enabled = true }

        fun install(store: SkillStore, name: String, body: String = "Original instructions.", revision: String? = null): InstalledSkill {
            val content = "---\nname: $name\ndescription: Snapshot fixture\n---\n$body"
            return store.install(
                listOf(SkillBundle(mapOf("SKILL.md" to content.toByteArray(Charsets.UTF_8)))),
                if (revision == null) emptyMap() else mapOf(name to revision)
            ).single()
        }

        override fun close() {
            preferences.forEach { base.deleteSharedPreferences(it) }
            root.deleteRecursively()
        }
    }

    fun testRunFactoryUsesFrozenIdsBodiesAndCapabilities() {
        Fixture().use { f ->
            val builtin = f.install(f.builtin, "shared", "Builtin")
            f.install(f.user, "shared", "Shadow")
            val user = f.install(f.user, "only-user", "User")
            val auth = app.daiyu.android.domain.tools.TaskAuthorization("run-session", "task")
            val caps = app.daiyu.android.domain.tools.SessionCapabilities("run-session", setOf(SkillRunSession.READ))
            val run = f.registry.captureForRun(caps, auth)
            assertEquals(setOf("skills_list", "skills_read"), run.availableTools)
            assertEquals(listOf("builtin:shared", "user:only-user"), run.index(auth).map { it.id })
            f.registry.enabled = false
            f.install(f.user, "only-user", "Changed", user.revision)
            assertTrue(run.read(auth, "user:only-user", user.revision).endsWith("User"))
            assertTrue(run.read(auth, "builtin:shared", builtin.revision).endsWith("Builtin"))
            assertTrue(f.registry.captureForRun(caps, auth).availableTools.isEmpty())
        }
    }

    fun testRunFactoryDeniesMissingCapabilityAndSessionMismatch() {
        Fixture().use { f ->
            f.install(f.user, "demo")
            val auth = app.daiyu.android.domain.tools.TaskAuthorization("run-session", "task")
            val empty = f.registry.captureForRun(app.daiyu.android.domain.tools.SessionCapabilities("run-session", emptySet()), auth)
            assertTrue(empty.availableTools.isEmpty())
            try {
                f.registry.captureForRun(app.daiyu.android.domain.tools.SessionCapabilities("other", setOf(SkillRunSession.READ)), auth)
                fail("session mismatch must reject")
            } catch (expected: IllegalArgumentException) { assertEquals("session_mismatch", expected.message) }
        }
    }

    fun testRunFactoryCountsBuiltinCaptureOnceNotCumulativePrefixes() {
        Fixture().use { f ->
            repeat(5) { f.install(f.builtin, "built-$it", "x".repeat(150000)) }
            f.install(f.user, "user-demo", "y".repeat(150000))
            val auth = app.daiyu.android.domain.tools.TaskAuthorization("run-session", "task")
            val run = f.registry.captureForRun(app.daiyu.android.domain.tools.SessionCapabilities("run-session", setOf(SkillRunSession.READ)), auth)
            assertEquals(6, run.index(auth).size)
        }
    }

    private fun assertUnmodifiable(snapshot: SkillSnapshot) {
        val before = snapshot.skills.toList()
        val injected = EnabledSkill("injected", "Injected", "0".repeat(64), "Injected instructions.")
        val skills = snapshot.skills as MutableList<EnabledSkill>
        fun rejected(label: String, operation: () -> Unit) {
            try {
                operation()
                fail("$label must throw UnsupportedOperationException")
            } catch (_: UnsupportedOperationException) {
                // A Kotlin read-only type alone does not enforce this at runtime.
            }
            assertEquals("$label must not change the snapshot", before, snapshot.skills)
        }
        rejected("clear") { skills.clear() }
        rejected("add") { skills.add(injected) }
        rejected("indexed add") { skills.add(0, injected) }
        rejected("set") { skills[0] = injected }
        rejected("indexed remove") { skills.removeAt(0) }
        rejected("remove") { skills.remove(before.firstOrNull() ?: injected) }
        rejected("addAll") { skills.addAll(listOf(injected)) }
        rejected("removeAll") { skills.removeAll(before.toSet()) }
        rejected("retainAll") { skills.retainAll(emptySet()) }
        if (skills.isNotEmpty()) {
            val iterator = skills.iterator().apply { next() }
            rejected("iterator remove") { iterator.remove() }
            val listIterator = skills.listIterator().apply { next() }
            rejected("list iterator set") { listIterator.set(injected) }
            rejected("list iterator add") { listIterator.add(injected) }
            rejected("list iterator remove") { listIterator.remove() }
        }
    }

    fun testMergedBuiltinAndUserSnapshotRejectsMutationAndKeepsBuiltinPrecedence() {
        Fixture().use { f ->
            f.install(f.builtin, "builtin-demo")
            f.install(f.builtin, "shared", "Builtin instructions.")
            f.install(f.user, "user-demo")
            f.install(f.user, "shared", "Shadowed user instructions.")

            val snapshot = f.registry.snapshot()
            assertEquals(listOf("builtin-demo", "shared", "user-demo"), snapshot.skills.map { it.name })
            assertTrue(snapshot.skills.single { it.name == "shared" }.instructions.contains("Builtin instructions."))
            assertFalse(snapshot.skills.any { it.instructions.contains("Shadowed user instructions.") })
            assertUnmodifiable(snapshot)
            assertEquals(snapshot, f.registry.snapshot())
        }
    }

    fun testEmptyAndAllSkillsDisabledSnapshotsRejectMutationAndStayEmptyAfterInstallOrEnable() {
        Fixture().use { f ->
            val empty = f.registry.snapshot()
            assertTrue(empty.skills.isEmpty())
            assertUnmodifiable(empty)

            val builtin = f.install(f.builtin, "builtin-demo")
            val user = f.install(f.user, "user-demo")
            f.registry.setEnabled("builtin:${builtin.name}", false, builtin.revision)
            f.registry.setEnabled("user:${user.name}", false, user.revision)
            val allDisabled = f.registry.snapshot()
            assertTrue(allDisabled.skills.isEmpty())
            assertUnmodifiable(allDisabled)

            f.registry.setEnabled("builtin:${builtin.name}", true, builtin.revision)
            f.registry.setEnabled("user:${user.name}", true, user.revision)
            assertEquals(2, f.registry.snapshot().skills.size)
            assertTrue(empty.skills.isEmpty())
            assertTrue(allDisabled.skills.isEmpty())
        }
    }

    fun testGloballyDisabledSnapshotRejectsMutationAndStaysEmptyAfterReenable() {
        Fixture().use { f ->
            f.install(f.builtin, "builtin-demo")
            f.install(f.user, "user-demo")
            f.registry.enabled = false
            val disabled = f.registry.snapshot()
            assertTrue(disabled.skills.isEmpty())
            assertEquals("", disabled.promptIndex())
            assertUnmodifiable(disabled)

            f.registry.enabled = true
            f.install(f.user, "later-demo")
            assertEquals(3, f.registry.snapshot().skills.size)
            assertTrue(disabled.skills.isEmpty())
        }
    }

    fun testCapturedMergedSnapshotIsDetachedFromLaterEnableAndInstallChanges() {
        Fixture().use { f ->
            val builtin = f.install(f.builtin, "builtin-demo", "Original builtin instructions.")
            val user = f.install(f.user, "user-demo", "Original user instructions.")
            val snapshot = f.registry.snapshot()
            val before = snapshot.skills.toList()
            val prompt = snapshot.promptIndex()

            f.registry.setEnabled("builtin:${builtin.name}", false, builtin.revision)
            f.registry.setEnabled("user:${user.name}", false, user.revision)
            assertTrue(f.registry.snapshot().skills.isEmpty())
            f.registry.enabled = false
            assertTrue(f.registry.snapshot().skills.isEmpty())
            assertEquals(before, snapshot.skills)

            val replacement = f.install(f.user, user.name, "Replacement user instructions.", user.revision)
            f.install(f.user, "later-demo", "Later instructions.")
            f.registry.setEnabled("builtin:${builtin.name}", true, builtin.revision)
            f.registry.enabled = true
            val fresh = f.registry.snapshot()
            assertEquals(listOf("builtin-demo", "later-demo", "user-demo"), fresh.skills.map { it.name })
            assertEquals(replacement.revision, fresh.skills.single { it.name == user.name }.revision)
            assertFalse(user.revision == replacement.revision)
            assertEquals(before, snapshot.skills)
            assertEquals(prompt, snapshot.promptIndex())
            assertTrue(snapshot.skills.single { it.name == user.name }.instructions.contains("Original user instructions."))
            assertUnmodifiable(snapshot)
        }
    }
}
