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

    fun testRemovalIsExactOriginOneShotAndCancelCannotDelete() = fixture { builtin, user, _, manager ->
        val appSkill = builtin.install(listOf(bundle("builtin"))).single()
        val userSkill = user.install(listOf(bundle())).single()
        val canceled = manager.previewRemoval("user:sample", userSkill.revision)
        manager.cancelRemoval()
        assertTrue(manager.remove(canceled) is SkillManagement.RemovalResult.Rejected)
        assertEquals(userSkill, user.list().single())
        val approved = manager.previewRemoval("user:sample", userSkill.revision)
        assertEquals("user:sample", approved.entry.id)
        assertTrue(manager.remove(approved) is SkillManagement.RemovalResult.Removed)
        assertTrue(manager.remove(approved) is SkillManagement.RemovalResult.Rejected)
        assertTrue(user.list().isEmpty())
        assertEquals(appSkill, builtin.list().single())
    }
    fun testManagementCannotOfferOrSubmitBuiltinRemoval() = fixture { builtin, _, registry, manager ->
        val old = builtin.install(listOf(bundle("builtin"))).single()
        try { manager.previewRemoval("builtin:sample", old.revision); fail("builtin preview accepted") }
        catch (_: SkillToolRegistry.ManagementBuiltinConflict) { }
        try { registry.removeForManagement("builtin:sample", old.revision); fail("builtin deletion accepted") }
        catch (_: SkillToolRegistry.ManagementBuiltinConflict) { }
        assertEquals(old, builtin.list().single())
        assertTrue(builtin.snapshot().skills.single().instructions.contains("builtin"))
    }

    fun testRemovalRejectsReplacementAfterPreviewWithoutDeletingIt() = fixture { _, user, _, manager ->
        val old = user.install(listOf(bundle())).single()
        val approved = manager.previewRemoval("user:sample", old.revision)
        val fresh = user.install(listOf(bundle("replacement")), mapOf("sample" to old.revision)).single()
        assertEquals(SkillManagement.RemovalResult.Rejected("skill_conflict"), manager.remove(approved))
        assertEquals(fresh, user.list().single())
    }
    fun testRemovalWhileGlobalAndIndividualOffPreservesCapturedBodyAndNextCapture() = fixture { _, user, registry, manager ->
        val old = user.install(listOf(bundle())).single()
        val owner = TaskAuthorization("removal-snapshot", "running")
        val capabilities = SessionCapabilities(owner.sessionId, setOf(SkillRunSession.READ))
        val run = registry.captureForRun(capabilities, owner)
        try {
            val body = run.read(owner, "user:sample", old.revision)
            manager.setEnabled("user:sample", false, old.revision)
            manager.setGlobalEnabled(false)
            val approved = manager.previewRemoval("user:sample", old.revision)
            assertTrue(manager.remove(approved) is SkillManagement.RemovalResult.Removed)
            assertFalse(registry.enabled)
            assertTrue(user.list().isEmpty())
            assertEquals(body, run.read(owner, "user:sample", old.revision))
            assertEquals("user:sample", run.index(owner).single().id)
            manager.setGlobalEnabled(true)
            val next = registry.captureForRun(capabilities, owner)
            try { assertTrue(next.index(owner).isEmpty()) } finally { next.close() }
        } finally { run.close() }
    }

    fun testRemovalCleanupPendingIsCommittedResult() = fixture { _, user, _, manager ->
        val old = user.install(listOf(bundle())).single()
        val approved = manager.previewRemoval("user:sample", old.revision)
        user.transactionCheckpoint = { if (it == "after_commit") throw java.io.IOException("injected") }
        val result = manager.remove(approved) as SkillManagement.RemovalResult.Removed
        assertTrue(result.cleanupPending)
        assertTrue(manager.snapshot().entries.isEmpty())
    }

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

    private fun localZip(body: String = "local instructions"): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(output).use { zip ->
            bundle(body).files.forEach { (path, bytes) ->
                zip.putNextEntry(java.util.zip.ZipEntry(path)); zip.write(bytes); zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    fun testLocalPreviewFreezesBytesClosesStreamAndInstallsOnceWithGlobalOff() = fixture { _, user, registry, manager ->
        manager.setGlobalEnabled(false)
        val bytes = localZip()
        var closes = 0
        val preview = manager.previewLocalZip {
            object : java.io.ByteArrayInputStream(bytes) {
                override fun close() { closes++; super.close() }
            }
        }
        assertEquals(1, closes)
        assertTrue(user.list().isEmpty())
        bytes.fill(0)
        val result = manager.installLocalZip(preview, emptyMap()) as SkillManagement.InstallResult.Installed
        assertEquals(preview.change.candidate.incomingRevision, result.skills.single().revision)
        assertNull(result.skills.single().source)
        assertNull(result.skills.single().commit)
        assertFalse(registry.enabled)
        assertTrue(user.list().single().enabled)
        assertTrue(manager.installLocalZip(preview, emptyMap()) is SkillManagement.InstallResult.Rejected)
    }

    fun testLocalReplacementRequiresExactOneShotApprovalAndRejectsChangedTarget() = fixture { _, user, _, manager ->
        val old = user.install(listOf(bundle())).single()
        val missing = manager.previewLocalZip { localZip().inputStream() }
        assertEquals(mapOf("user:sample" to old.revision), missing.replacements)
        assertEquals(SkillManagement.InstallResult.Rejected("replacement_approval_required"), manager.installLocalZip(missing, emptyMap()))
        assertEquals(SkillManagement.InstallResult.Rejected("stale_preview"), manager.installLocalZip(missing, missing.replacements))
        val stale = manager.previewLocalZip { localZip().inputStream() }
        val new = user.install(listOf(bundle("external")), mapOf("sample" to old.revision)).single()
        assertEquals(SkillManagement.InstallResult.Rejected("skill_conflict"), manager.installLocalZip(stale, stale.replacements))
        assertEquals(new, user.list().single())
        val approved = manager.previewLocalZip { localZip().inputStream() }
        assertTrue(manager.installLocalZip(approved, approved.replacements) is SkillManagement.InstallResult.Installed)
    }

    fun testLocalCancelAndSourceChangesRevokeOldPreview() = fixture { _, user, _, manager ->
        val first = manager.previewLocalZip { localZip().inputStream() }
        manager.cancelInstall()
        assertEquals(SkillManagement.InstallResult.Rejected("stale_preview"), manager.installLocalZip(first, emptyMap()))
        val next = manager.previewLocalZip { localZip().inputStream() }
        try { manager.inspect("not a URL"); fail("bad URL accepted") }
        catch (failure: IllegalStateException) { assertEquals("invalid_github_url", failure.message) }
        assertEquals(SkillManagement.InstallResult.Rejected("stale_preview"), manager.installLocalZip(next, emptyMap()))
        val installed = user.install(listOf(bundle())).single()
        val beforeDelete = manager.previewLocalZip { localZip().inputStream() }
        manager.previewRemoval("user:sample", installed.revision)
        assertEquals(SkillManagement.InstallResult.Rejected("stale_preview"), manager.installLocalZip(beforeDelete, beforeDelete.replacements))
    }

    fun testLocalBuiltinConflictAtPreviewAndPublication() = fixture { builtin, user, _, manager ->
        val preview = manager.previewLocalZip { localZip().inputStream() }
        builtin.install(listOf(bundle("builtin")))
        assertEquals(SkillManagement.InstallResult.Rejected("builtin_conflict"), manager.installLocalZip(preview, emptyMap()))
        try { manager.previewLocalZip { localZip().inputStream() }; fail("builtin preview accepted") }
        catch (failure: IllegalArgumentException) { assertEquals("builtin_conflict", failure.message) }
        assertTrue(user.list().isEmpty())
    }

    fun testLocalCommittedCleanupFailureIsNotRejectedOrRetried() = fixture { _, user, _, manager ->
        val preview = manager.previewLocalZip { localZip().inputStream() }
        user.transactionCheckpoint = { if (it == "after_commit") throw java.io.IOException("injected") }
        val result = manager.installLocalZip(preview, emptyMap()) as SkillManagement.InstallResult.Installed
        assertTrue(result.cleanupPending)
        assertEquals(preview.change.candidate.incomingRevision, user.list().single().revision)
        assertEquals(SkillManagement.InstallResult.Rejected("stale_preview"), manager.installLocalZip(preview, emptyMap()))
    }

}
