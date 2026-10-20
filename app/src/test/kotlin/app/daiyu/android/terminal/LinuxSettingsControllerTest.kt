package app.daiyu.android.terminal

import java.io.File
import java.io.IOException
import junit.framework.TestCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** Coordinator tests do not access a filesystem, execute a helper, or claim file validation. */
@OptIn(ExperimentalCoroutinesApi::class)
class LinuxSettingsControllerTest : TestCase() {
    private fun draft(mounts: List<LinuxSettingsMount> = emptyList()) = LinuxSettingsDraft(
        distribution = "local", environmentId = "environment", rootfs = "/local/rootfs", proot = "/local/proot",
        prootSha256 = "a".repeat(64), loader = "/local/loader", loaderSha256 = "b".repeat(64),
        guestWorkingDirectory = "/work", sharedMounts = mounts,
    )
    private fun ref(value: LinuxInstallation) = LinuxGenerationRef(value.environmentId, value.generation)

    private class Catalog : LinuxSettingsCatalogAccess {
        override suspend fun registrationRecorded(approval: FrozenLinuxApproval) = false
        override suspend fun approveInstallation(approval: FrozenLinuxApproval, admit: () -> Boolean): List<LinuxInstallation> {
            check(admit()) { "linux_catalog_admission_revoked" }
            return approve(approval)
        }
        var entries = emptyList<LinuxInstallation>()
        var reads = 0
        var freezes = 0
        var approvals = 0
        var removals = 0
        var failLoad = false
        var failApprove = false
        var failRemove = false
        var readWait: CompletableDeferred<Unit>? = null
        var approveWait: CompletableDeferred<Unit>? = null
        override fun freeze(configuration: LinuxInstallation): FrozenLinuxApproval {
            freezes++
            return FrozenLinuxApproval(configuration.copy(generation = "generation-$freezes"))
        }
        override suspend fun load(): List<LinuxInstallation> {
            reads++
            val before = entries
            readWait?.await()
            if (failLoad) throw IOException("injected read failure")
            return before
        }
        override suspend fun approve(approval: FrozenLinuxApproval): List<LinuxInstallation> {
            approvals++
            approveWait?.await()
            if (failApprove) throw IOException("injected approval failure")
            entries = (entries + approval.installation).distinctBy { it.generation }
            return entries
        }
        override suspend fun remove(reference: LinuxGenerationRef): List<LinuxInstallation> {
            removals++
            if (failRemove) throw IOException("injected removal failure")
            entries = entries.filterNot { LinuxGenerationRef(it.environmentId, it.generation) == reference }
            return entries
        }
    }

    fun testChrootApprovalRequiresRootModeAndOldPreviewCannotBypassIt() = runTest {
        val catalog = Catalog()
        var rootMode = false
        val controller = LinuxSettingsController(catalog, LinuxSettingsAdmission(emptySet()), this,
            allowChroot = { rootMode })
        val page = controller.enterPage()
        runCurrent()
        val configuration = LinuxSettingsDraft(distribution = "alpine", environmentId = "root-env",
            rootfs = "/local/chroot", executionMode = LinuxExecutionMode.CHROOT)
        assertTrue(page.preview(configuration))
        runCurrent()
        val preview = checkNotNull(page.state.value.preview)
        assertFalse(page.approve(preview))
        assertEquals(0, catalog.approvals)
        rootMode = true
        assertTrue(page.approve(preview))
        runCurrent()
        assertEquals(1, catalog.approvals)
        assertTrue(page.preview(configuration))
        runCurrent()
        val oldPreview = checkNotNull(page.state.value.preview)
        rootMode = false
        assertFalse(page.approve(oldPreview))
        assertFalse(page.prepareInstall("alpine", "primary", LinuxExecutionMode.CHROOT))
        assertEquals(1, catalog.approvals)
        controller.close()
    }

    fun testNoReadBeforePageAndFrozenFullPreviewIsNotApproval() = runTest {
        val catalog = Catalog()
        val gate = LinuxSettingsAdmission(emptySet())
        val controller = LinuxSettingsController(catalog, gate, this)
        assertEquals(0, catalog.reads)
        val page = controller.enterPage()
        assertTrue(page.state.value.busy)
        runCurrent()
        assertEquals(1, catalog.reads)
        val mounts = mutableListOf(LinuxSettingsMount("/shared/one", "/one"))
        assertTrue(page.preview(draft(mounts)))
        mounts[0] = LinuxSettingsMount("/unapproved", "/other")
        assertNull(page.state.value.preview)
        runCurrent()
        val frozen = page.state.value.preview!!.installation
        assertEquals(File("/local/rootfs"), frozen.rootfs)
        assertEquals(File("/local/proot"), frozen.proot)
        assertEquals(File("/local/loader"), frozen.loader)
        assertEquals("a".repeat(64), frozen.prootSha256)
        assertEquals("b".repeat(64), frozen.loaderSha256)
        assertEquals("/work", frozen.guestWorkingDirectory)
        assertEquals(mapOf("/shared/one" to "/one"), frozen.sharedMounts)
        assertEquals(0, catalog.approvals)
        assertFalse(gate.contains(ref(frozen)))
        page.close()
    }

    fun testExactPreviewRequiredAndFailureRetainsSameGenerationForRetry() = runTest {
        val catalog = Catalog()
        val gate = LinuxSettingsAdmission(emptySet())
        val controller = LinuxSettingsController(catalog, gate, this)
        val page = controller.enterPage()
        runCurrent()
        page.preview(draft())
        runCurrent()
        val first = page.state.value.preview!!
        assertTrue(page.discardPreview())
        assertFalse(page.approve(first))
        page.preview(draft().copy(guestWorkingDirectory = "/other"))
        runCurrent()
        val current = page.state.value.preview!!
        assertFalse(page.approve(first))
        catalog.failApprove = true
        assertTrue(page.approve(current))
        assertFalse(page.approve(current)) // no duplicate concurrent write
        runCurrent()
        assertTrue(page.state.value.error)
        assertSame(current, page.state.value.preview)
        assertSame(current, page.state.value.pendingApproval)
        catalog.failApprove = false
        assertTrue(page.approve(current))
        assertSame(current, page.state.value.pendingApproval) // Enqueue is not a receipt either.
        runCurrent()
        assertEquals(listOf(current.installation), page.state.value.entries)
        assertEquals(2, catalog.approvals)
        assertNull(page.state.value.preview)
        assertNull(page.state.value.pendingApproval)
        assertFalse(gate.contains(ref(current.installation))) // fixed startup snapshot, not hot grant
        page.close()
    }

    fun testDuplicateHostsCannotDisappearInPreview() = runTest {
        val catalog = Catalog()
        val page = LinuxSettingsController(catalog, LinuxSettingsAdmission(emptySet()), this).enterPage()
        runCurrent()
        page.preview(draft(listOf(LinuxSettingsMount("/same", "/one"), LinuxSettingsMount("/same", "/two"))))
        runCurrent()
        assertTrue(page.state.value.error)
        assertNull(page.state.value.preview)
        assertEquals(0, catalog.freezes)
        assertEquals(0, catalog.approvals)
        page.close()
    }

    fun testRevocationDeniesSynchronouslyEvenWhenPersistenceFails() = runTest {
        val catalog = Catalog()
        val value = draft().installation().copy(generation = "startup")
        catalog.entries = listOf(value)
        val gate = LinuxSettingsAdmission(setOf(ref(value)))
        val controller = LinuxSettingsController(catalog, gate, this)
        val page = controller.enterPage()
        runCurrent()
        catalog.failRemove = true
        assertTrue(page.remove(ref(value)))
        assertFalse(gate.contains(ref(value))) // before worker or persistence
        assertEquals(0, catalog.removals)
        runCurrent()
        assertTrue(page.state.value.error)
        assertEquals(listOf(value), page.state.value.entries)
        assertFalse(gate.admit(ref(value)) { fail("must not admit"); true })
        catalog.failRemove = false
        assertTrue(page.remove(ref(value)))
        runCurrent()
        assertTrue(page.state.value.entries.isEmpty())
        assertEquals(2, catalog.removals)
        assertFalse(gate.contains(ref(value)))
        page.close()
    }

    fun testRevokedCallbacksCannotActOnNewPageAndOldReadCannotFillIt() = runTest {
        val catalog = Catalog()
        val wait = CompletableDeferred<Unit>()
        catalog.readWait = wait
        val controller = LinuxSettingsController(catalog, LinuxSettingsAdmission(emptySet()), this)
        val first = controller.enterPage()
        runCurrent() // first read is in flight
        val value = draft().installation().copy(generation = "current")
        catalog.entries = listOf(value)
        val second = controller.enterPage() // implicitly revokes first even without disposal
        assertTrue(first.state.value.revoked)
        assertFalse(first.preview(draft()))
        assertFalse(first.refresh())
        assertFalse(first.remove(ref(value)))
        assertFalse(first.revoke()) // stale back callback must not navigate again
        first.close() // old onDispose cannot revoke the second page
        wait.complete(Unit)
        runCurrent()
        assertTrue(first.state.value.entries.isEmpty())
        assertEquals(listOf(value), second.state.value.entries)
        assertFalse(second.state.value.revoked)
        assertNull(second.state.value.preview)
        assertEquals(2, catalog.reads)
        second.close()
        assertFalse(second.refresh())
    }

    fun testInFlightWriteMayCommitButItsReceiptDoesNotPopulateNewPage() = runTest {
        val catalog = Catalog()
        val controller = LinuxSettingsController(catalog, LinuxSettingsAdmission(emptySet()), this)
        val first = controller.enterPage()
        runCurrent()
        first.preview(draft())
        runCurrent()
        val frozen = first.state.value.preview!!
        val wait = CompletableDeferred<Unit>()
        catalog.approveWait = wait
        first.approve(frozen)
        runCurrent()
        val second = controller.enterPage()
        wait.complete(Unit)
        runCurrent()
        assertTrue(first.state.value.revoked)
        assertNull(first.state.value.preview)
        assertTrue(first.state.value.entries.isEmpty())
        assertEquals(listOf(frozen.installation), second.state.value.entries) // its own fresh catalog read
        assertNull(second.state.value.preview)
        assertEquals("已读取并验证批准目录。", second.state.value.notice)
        assertFalse(first.approve(frozen))
        second.close()
    }

    fun testKnownStartupEntryCanBeRevokedWhenLaterValidationFails() = runTest {
        val catalog = Catalog()
        val value = draft().installation().copy(generation = "startup")
        catalog.entries = listOf(value)
        catalog.failLoad = true
        val gate = LinuxSettingsAdmission(setOf(ref(value)))
        val controller = LinuxSettingsController(catalog, gate, this, startupEntries = listOf(value))
        val page = controller.enterPage()
        runCurrent()
        assertFalse(page.state.value.listConfirmed)
        assertTrue(page.state.value.error)
        assertEquals(listOf(value), page.state.value.entries)
        assertTrue(page.remove(ref(value)))
        assertFalse(gate.contains(ref(value)))
        runCurrent()
        assertTrue(page.state.value.listConfirmed)
        assertTrue(page.state.value.entries.isEmpty())
        page.close()
    }

    fun testQueuedRemovalKeepsPendingIntentWhenOldPageIsRevoked() = runTest {
        val catalog = Catalog()
        val value = draft().installation().copy(generation = "startup")
        catalog.entries = listOf(value)
        val reference = ref(value)
        val gate = LinuxSettingsAdmission(setOf(reference))
        val controller = LinuxSettingsController(catalog, gate, this)
        val first = controller.enterPage()
        runCurrent()
        assertTrue(first.remove(reference))
        assertFalse(gate.contains(reference))
        first.close() // Before the queued write has started.
        val second = controller.enterPage()
        runCurrent()
        assertEquals(0, catalog.removals)
        assertSame(reference, second.state.value.pendingRemoval)
        assertTrue(second.state.value.error)
        assertFalse(second.preview(draft()))
        assertFalse(first.remove(reference))
        assertTrue(second.remove(reference))
        runCurrent()
        assertEquals(1, catalog.removals)
        assertNull(second.state.value.pendingRemoval)
        assertFalse(second.state.value.error)
        assertFalse(gate.contains(reference))
        second.close()
    }

    fun testInFlightFailureAfterLeaveStillRequiresOriginalApprovalOnNewPage() = runTest {
        val catalog = Catalog()
        val controller = LinuxSettingsController(catalog, LinuxSettingsAdmission(emptySet()), this)
        val first = controller.enterPage()
        runCurrent()
        first.preview(draft())
        runCurrent()
        val frozen = first.state.value.preview!!
        val wait = CompletableDeferred<Unit>()
        catalog.approveWait = wait
        catalog.failApprove = true
        first.approve(frozen)
        runCurrent()
        first.close()
        val second = controller.enterPage() // Page lock is not held by suspended IO.
        assertFalse(first.approve(frozen))
        wait.complete(Unit)
        runCurrent()
        assertEquals(1, catalog.approvals)
        assertTrue(second.state.value.error)
        assertSame(frozen, second.state.value.preview)
        assertSame(frozen, second.state.value.pendingApproval)
        assertFalse(second.discardPreview())
        assertFalse(second.preview(draft()))
        assertFalse(second.approve(FrozenLinuxApproval(frozen.installation)))
        catalog.failApprove = false
        assertTrue(second.approve(frozen))
        runCurrent()
        assertEquals(2, catalog.approvals)
        assertNull(second.state.value.pendingApproval)
        assertNull(second.state.value.preview)
        assertFalse(second.state.value.error)
        second.close()
    }

    fun testQueuedPreviewDoesNotExecuteAfterLeaveAndLoadFailureIsNotEmptySuccess() = runTest {
        val catalog = Catalog()
        catalog.failLoad = true
        val controller = LinuxSettingsController(catalog, LinuxSettingsAdmission(emptySet()), this)
        val page = controller.enterPage()
        runCurrent()
        assertFalse(page.state.value.listConfirmed)
        assertTrue(page.state.value.error)
        catalog.failLoad = false
        page.preview(draft())
        page.close()
        runCurrent()
        assertEquals(0, catalog.freezes)
        assertTrue(page.state.value.revoked)
    }
}
