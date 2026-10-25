package app.daiyu.android.skills

import android.content.Context
import android.content.ContextWrapper
import android.test.InstrumentationTestCase
import app.daiyu.android.domain.tools.SessionCapabilities
import app.daiyu.android.domain.tools.TaskAuthorization
import java.io.File
import java.util.UUID

class BuiltinSkillManagementTest : InstrumentationTestCase() {
    private fun fixture(work: (SkillStore, SkillStore, SkillToolRegistry, SkillManagement) -> Unit) {
        val base = instrumentation.targetContext
        val token = "builtin-${UUID.randomUUID()}"
        val root = File(base.cacheDir, token).apply { check(mkdir()) }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = root
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(token, Context.MODE_PRIVATE)
        }
        val builtin = SkillStore(File(root, "builtin"))
        val user = SkillStore(File(root, "user"))
        val registry = SkillToolRegistry(context, builtin = builtin, user = user, builtinCatalogFactory = { BuiltinSkillCatalog(mapOf("builtin-demo" to bundle("current").files.getValue("SKILL.md"))) })
        try { work(builtin, user, registry, SkillManagement.create { registry }) }
        finally { root.deleteRecursively(); base.deleteSharedPreferences(token) }
    }
    private fun bundle(body: String) = SkillBundle(mapOf("SKILL.md" to
        "---\nname: builtin-demo\ndescription: Previous package\n---\n$body\n".toByteArray()))

    fun testDirectMetadataListSeedsEvenWhenGloballyDisabledAndCleanupCommitted() = fixture { builtin, _, registry, manager ->
        registry.enabled = false
        builtin.transactionCheckpoint = { if (it == "before_cleanup") throw IllegalStateException("cleanup") }
        val listed = registry.execute("skills_list", org.json.JSONObject()).getJSONArray("skills")
        assertEquals(1, listed.length())
        assertFalse(registry.enabled)
        builtin.transactionCheckpoint = {}
        assertEquals(1, manager.snapshot().entries.size)
    }

    fun testInjectedSeedNeverOverwritesExistingDisabledPackage() = fixture { builtin, _, registry, manager ->
        val old = builtin.install(listOf(bundle("previous"))).single()
        builtin.setEnabled(old.name, false, old.revision)
        val state = manager.snapshot()
        assertFalse(state.entriesUnavailable)
        assertEquals(1, state.entries.size)
        val preserved = builtin.list().single { it.name == old.name }
        assertEquals(old.revision, preserved.revision)
        assertFalse(preserved.enabled)
        assertFalse(registry.snapshot().skills.any { it.name == old.name })
        assertEquals(1, manager.snapshot().entries.size)
    }

    fun testReinstallChecksRevisionAndKeepsFrozenRunWhileGlobalOff() = fixture { builtin, _, registry, manager ->
        val old = builtin.install(listOf(bundle("previous"))).single()
        manager.snapshot()
        val owner = TaskAuthorization("builtin-test", "running")
        val capabilities = SessionCapabilities(owner.sessionId, setOf(SkillRunSession.READ))
        val run = registry.captureForRun(capabilities, owner, setOf("builtin-demo"))
        try {
            assertEquals(setOf("builtin:builtin-demo"), run.index(owner).map { it.id }.toSet())
            val body = run.read(owner, "builtin:builtin-demo", old.revision)
            manager.setGlobalEnabled(false)
            assertEquals(SkillManagement.InstallResult.Rejected("skill_conflict"), manager.reinstallBuiltin("builtin:builtin-demo", "0".repeat(64)))
            assertEquals(old.revision, builtin.list().single { it.name == old.name }.revision)
            assertTrue(manager.reinstallBuiltin("builtin:builtin-demo", old.revision) is SkillManagement.InstallResult.Installed)
            assertFalse(registry.enabled)
            assertFalse(old.revision == builtin.list().single { it.name == old.name }.revision)
            assertEquals(body, run.read(owner, "builtin:builtin-demo", old.revision))
            manager.setGlobalEnabled(true)
            val next = registry.captureForRun(capabilities, owner, setOf("builtin-demo"))
            try { assertFalse(next.read(owner, "builtin:builtin-demo", next.index(owner).single().revision).contains("previous")) }
            finally { next.close() }
        } finally { run.close() }
    }

    fun testReservedNamesCannotBeInstalledThroughUserManagement() = fixture { _, user, registry, manager ->
        val incoming = user.preview(bundle("user" )).second
        try { registry.installForManagement(listOf(bundle("user")), emptyMap(), mapOf("builtin-demo" to incoming)); fail("reserved name installed") }
        catch (_: SkillToolRegistry.ManagementBuiltinConflict) { }
        assertTrue(user.list().isEmpty())
        assertEquals(SkillManagement.InstallResult.Rejected("builtin_not_available"), manager.reinstallBuiltin("user:builtin-demo", incoming))
    }

    fun testCommittedCleanupFailureIsSuccessfulRestoration() = fixture { builtin, _, _, manager ->
        val old = builtin.install(listOf(bundle("previous"))).single()
        manager.snapshot()
        builtin.transactionCheckpoint = { if (it == "before_cleanup") throw IllegalStateException("cleanup") }
        val result = manager.reinstallBuiltin("builtin:builtin-demo", old.revision)
        assertTrue(result is SkillManagement.InstallResult.Installed && result.cleanupPending)
        builtin.transactionCheckpoint = {}
        assertFalse(manager.snapshot().entriesUnavailable)
    }
}
