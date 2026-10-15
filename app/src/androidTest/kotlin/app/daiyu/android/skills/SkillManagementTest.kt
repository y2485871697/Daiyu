package app.daiyu.android.skills

import android.test.InstrumentationTestCase
import app.daiyu.android.domain.tools.SessionCapabilities
import app.daiyu.android.domain.tools.TaskAuthorization
import java.io.File
import java.util.UUID

class SkillManagementTest : InstrumentationTestCase() {
    private fun fixture(work: (SkillStore, SkillStore, SkillToolRegistry, SkillManagement) -> Unit) {
        val context = instrumentation.targetContext
        val root = File(context.cacheDir, "management-${UUID.randomUUID()}")
        val builtin = SkillStore(File(root, "builtin"))
        val user = SkillStore(File(root, "user"))
        val registry = SkillToolRegistry(context, builtin = builtin, user = user)
        val previous = registry.enabled
        try { registry.enabled = true; work(builtin, user, registry, SkillManagement.create { registry }) }
        finally { registry.enabled = previous; root.deleteRecursively() }
    }
    private fun bundle(text: String = "instructions") = SkillBundle(mapOf("SKILL.md" to
        "---\nname: sample\ndescription: Management sample\n---\n$text\n".toByteArray()))

    fun testListIncludesDisabledAndPreservesIndividualSettingAcrossGlobalToggle() = fixture { builtin, user, _, manager ->
        builtin.install(listOf(bundle("builtin")))
        val installed = user.install(listOf(bundle())).single()
        manager.setEnabled("user:sample", false, installed.revision)
        val off = manager.setGlobalEnabled(false)
        assertFalse(off.enabled)
        assertEquals(setOf("builtin:sample", "user:sample"), off.entries.map { it.id }.toSet())
        assertTrue(off.entries.single { it.builtin }.skill.enabled)
        assertFalse(manager.setGlobalEnabled(true).entries.single { !it.builtin }.skill.enabled)
    }

    fun testStaleRevisionCannotDisableReplacement() = fixture { _, user, _, manager ->
        val old = user.install(listOf(bundle())).single()
        val replacement = user.install(listOf(bundle("new")), mapOf("sample" to old.revision)).single()
        try { manager.setEnabled("user:sample", false, old.revision); fail("stale revision accepted") }
        catch (_: SkillConflict) { }
        assertEquals(replacement.revision, manager.snapshot().entries.single().skill.revision)
        assertTrue(manager.snapshot().entries.single().skill.enabled)
    }

    fun testDisabledGlobalOmitsNextCaptureWithoutChangingCapturedBody() = fixture { _, user, registry, manager ->
        user.install(listOf(bundle()))
        val capabilities = SessionCapabilities("management-test", setOf(SkillRunSession.READ))
        val owner = TaskAuthorization("management-test", "run")
        val captured = registry.captureForRun(capabilities, owner)
        val entry = captured.index(owner).single()
        manager.setGlobalEnabled(false)
        val next = registry.captureForRun(capabilities, TaskAuthorization("management-test", "next"))
        assertTrue(next.availableTools.isEmpty())
        assertTrue(captured.read(owner, entry.id, entry.revision).contains("instructions"))
        next.close(); captured.close()
    }

    fun testManagementDoesNotAdvertiseWriteTools() = fixture { _, user, registry, manager ->
        user.install(listOf(bundle()))
        manager.snapshot()
        val owner = TaskAuthorization("management-test", "run")
        val scope = registry.captureForRun(SessionCapabilities(owner.sessionId, setOf(SkillRunSession.READ)), owner)
        assertEquals(setOf("skills_list", "skills_read"), scope.availableTools)
        scope.close()
    }

    fun testMainThreadCreationRejectsBeforeRegistryFactory() {
        var calls = 0
        var rejected = false
        instrumentation.runOnMainSync {
            try { SkillManagement.create { calls++; SkillToolRegistry(instrumentation.targetContext) } }
            catch (failure: IllegalStateException) { rejected = failure.message == "background_thread_required" }
        }
        assertTrue(rejected)
        assertEquals(0, calls)
    }

    fun testGlobalEnableSurvivesRegistryReopen() = fixture { builtin, user, _, manager ->
        manager.setGlobalEnabled(false)
        val reopened = SkillManagement.create { SkillToolRegistry(instrumentation.targetContext, builtin = builtin, user = user) }
        assertFalse(reopened.snapshot().enabled)
    }

    fun testManagementRejectsMainThreadBeforeStorageAccess() = fixture { _, _, _, manager ->
        var rejected = false
        instrumentation.runOnMainSync {
            try { manager.snapshot() } catch (failure: IllegalStateException) { rejected = failure.message == "background_thread_required" }
        }
        assertTrue(rejected)
    }
    fun testMainThreadRejectsEvenWhileWorkerOwnsManagementLock() = fixture { _, _, _, manager ->
        val held = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val worker = Thread {
            synchronized(manager) { held.countDown(); release.await(5, java.util.concurrent.TimeUnit.SECONDS) }
        }
        worker.start()
        assertTrue(held.await(5, java.util.concurrent.TimeUnit.SECONDS))
        try {
            var rejected = false
            val began = System.nanoTime()
            instrumentation.runOnMainSync {
                try { manager.snapshot() }
                catch (failure: IllegalStateException) { rejected = failure.message == "background_thread_required" }
            }
            assertTrue(rejected)
            assertTrue("main waited for worker lock", System.nanoTime() - began < 1_000_000_000L)
            assertEquals(1L, release.count)
        } finally { release.countDown(); worker.join(5000) }
    }

}
