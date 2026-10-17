package app.daiyu.android.terminal

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import junit.framework.TestCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** Inert text fixtures only; real catalog/store validation, never shell execution or installation. */
class LinuxSettingsLocalCatalogTest : TestCase() {
    private fun fixture(test: suspend (File, LinuxSettingsDraft, TrustedLinuxCatalog) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("linux-settings-local-").toFile()
        val guest = File(root, "guest").apply { mkdirs() }
        File(guest, "bin").mkdirs()
        File(guest, "bin/sh").writeText("never execute this fixture")
        val proot = File(root, "proot").apply { writeText("inert proot"); check(setExecutable(true)) }
        val loader = File(root, "loader").apply { writeText("inert loader") }
        val shared = File(root, "shared").apply { mkdirs() }
        fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val draft = LinuxSettingsDraft("local", "environment", guest.path, proot.path, digest(proot),
            loader.path, digest(loader), "/work", listOf(LinuxSettingsMount(shared.path, "/shared")))
        try { test(root, draft, hostLinuxCatalog(File(root, "catalog"))) }
        finally { root.deleteRecursively() }
    }

    private suspend fun idle(page: LinuxSettingsPageOwner): LinuxSettingsState =
        withTimeout(5_000) { page.state.first { !it.busy } }

    /** Inject only after the target one-record snapshot is visible and the tmp has been renamed. */
    private class PostRenameSync(
        root: File,
        private val active: Boolean,
        private val beforeFailure: () -> Unit = {},
    ) : (File) -> Unit {
        private val directory = File(root, "catalog")
        private val manifest = File(directory, "trusted-linux-catalog.json")
        var fail = true
        var attempts = 0
            private set
        var successes = 0
            private set

        override fun invoke(value: File) {
            val visible = value == directory && manifest.isFile &&
                JSONObject(manifest.readText()).getJSONArray("records").let {
                    it.length() == 1 && it.getJSONObject(0).getBoolean("active") == active
                }
            if (visible) {
                check(!File(directory, "trusted-linux-catalog.json.tmp").exists())
                attempts++
                beforeFailure()
                if (fail) throw IOException("injected post-rename directorySync failure")
            }
            hostDirectorySync(value)
            if (visible) successes++
        }
    }

    private class RecordingCatalog(catalog: TrustedLinuxCatalog) :
        LinuxSettingsCatalogAccess by LinuxSettingsCatalogAdapter(catalog) {
        private val delegate = LinuxSettingsCatalogAdapter(catalog)
        val approvals = mutableListOf<FrozenLinuxApproval>()
        val removals = mutableListOf<LinuxGenerationRef>()
        override suspend fun approve(approval: FrozenLinuxApproval): List<LinuxInstallation> {
            approvals += approval
            return delegate.approve(approval)
        }
        override suspend fun remove(reference: LinuxGenerationRef): List<LinuxInstallation> {
            removals += reference
            return delegate.remove(reference)
        }
    }

    fun testRealPreviewApprovalAndRemovalReuseCatalogWithoutSelectingOrLaunching() = fixture { root, draft, catalog ->
        val gate = LinuxSettingsAdmission(emptySet())
        val controller = LinuxSettingsController(LinuxSettingsCatalogAdapter(catalog), gate)
        try {
            val page = controller.enterPage()
            assertTrue(idle(page).listConfirmed)
            assertTrue(page.preview(draft))
            val frozen = idle(page).preview!!
            assertEquals(draft.rootfs, frozen.installation.rootfs.path)
            assertEquals(draft.prootSha256, frozen.installation.prootSha256)
            assertEquals(draft.loaderSha256, frozen.installation.loaderSha256)
            assertEquals(mapOf(draft.sharedMounts.single().host to "/shared"), frozen.installation.sharedMounts)
            assertFalse(File(root, "catalog/trusted-linux-catalog.json").exists())
            assertTrue(page.approve(frozen))
            assertEquals(listOf(frozen.installation), idle(page).entries)
            assertEquals(listOf(frozen.installation), catalog.load())
            val reference = LinuxGenerationRef(frozen.installation.environmentId, frozen.installation.generation)
            assertFalse(gate.contains(reference)) // approval never widens the startup snapshot
            assertFalse(File(root, "user-terminal").exists())
            assertFalse(File(root, "catalog/selected-linux.json").exists())
            assertTrue(page.remove(reference))
            assertTrue(idle(page).entries.isEmpty())
            assertTrue(catalog.load().isEmpty())
            assertTrue(File(draft.rootfs).isDirectory)
            assertTrue(File(draft.proot).isFile)
            assertTrue(File(draft.loader).isFile)
            assertTrue(File(draft.sharedMounts.single().host).isDirectory)
            page.close()
        } finally { controller.close() }
    }

    fun testPostRenameApprovalFailureSurvivesRefreshAndReentryUntilExactObjectRetry() = fixture { root, draft, _ ->
        val sync = PostRenameSync(root, active = true)
        val catalog = hostLinuxCatalog(File(root, "catalog"), sync)
        val calls = RecordingCatalog(catalog)
        val gate = LinuxSettingsAdmission(emptySet())
        val controller = LinuxSettingsController(calls, gate)
        try {
            val first = controller.enterPage()
            idle(first)
            assertTrue(first.preview(draft))
            val frozen = idle(first).preview!!
            assertTrue(first.approve(frozen))
            val failed = idle(first)
            assertTrue(failed.error)
            assertFalse(failed.listConfirmed)
            assertSame(frozen, failed.pendingApproval)
            assertEquals(1, sync.attempts)
            assertEquals(0, sync.successes)
            assertEquals(listOf(frozen.installation), catalog.load()) // Visible is not durable.
            val manifest = File(root, "catalog/trusted-linux-catalog.json")
            val visibleBytes = manifest.readBytes()
            assertTrue(first.refresh())
            val refreshed = idle(first)
            assertEquals(listOf(frozen.installation), refreshed.entries)
            assertTrue(refreshed.error)
            assertFalse(refreshed.listConfirmed)
            assertSame(frozen, refreshed.preview)
            assertSame(frozen, refreshed.pendingApproval)
            assertFalse(first.discardPreview())
            assertFalse(first.preview(draft.copy(guestWorkingDirectory = "/different")))
            assertEquals(1, sync.attempts) // load cannot substitute for directorySync.

            first.close()
            val second = controller.enterPage()
            val reentered = idle(second)
            assertTrue(reentered.error)
            assertFalse(reentered.listConfirmed)
            assertSame(frozen, reentered.preview) // Screen renders all original fields again.
            assertSame(frozen, reentered.pendingApproval)
            assertEquals(1, calls.approvals.size) // No automatic replay on entry.
            assertFalse(first.approve(frozen))
            assertFalse(second.approve(FrozenLinuxApproval(frozen.installation)))
            sync.fail = false
            assertTrue(second.approve(reentered.preview!!)) // New page's explicit confirmation.
            val confirmed = idle(second)
            assertFalse(confirmed.error)
            assertTrue(confirmed.listConfirmed)
            assertNull(confirmed.preview)
            assertNull(confirmed.pendingApproval)
            assertEquals(listOf(frozen.installation), confirmed.entries)
            assertEquals(2, sync.attempts)
            assertEquals(1, sync.successes)
            assertEquals(2, calls.approvals.size)
            assertSame(frozen, calls.approvals[0])
            assertSame(frozen, calls.approvals[1])
            assertTrue(visibleBytes.contentEquals(manifest.readBytes())) // Same generation, no rewrite.
            assertFalse(gate.contains(LinuxGenerationRef(frozen.installation.environmentId, frozen.installation.generation)))
            second.close()
        } finally { controller.close() }
    }

    fun testPostRenameRemovalFailureSurvivesEmptyRefreshAndReentryUntilOriginalRefRetry() = fixture { root, draft, seed ->
        val frozen = seed.freezeForApproval(draft.installation())
        seed.approve(frozen)
        val reference = LinuxGenerationRef(frozen.installation.environmentId, frozen.installation.generation)
        val sync = PostRenameSync(root, active = false)
        val catalog = hostLinuxCatalog(File(root, "catalog"), sync)
        val calls = RecordingCatalog(catalog)
        val gate = LinuxSettingsAdmission(setOf(reference))
        val controller = LinuxSettingsController(calls, gate)
        try {
            val first = controller.enterPage()
            assertEquals(listOf(frozen.installation), idle(first).entries)
            assertTrue(first.remove(reference))
            assertFalse(gate.contains(reference)) // Synchronous, before persistence result.
            val failed = idle(first)
            assertTrue(failed.error)
            assertSame(reference, failed.pendingRemoval)
            assertEquals(1, sync.attempts)
            assertEquals(0, sync.successes)
            assertTrue(catalog.load().isEmpty()) // Tombstone is already visible.
            val manifest = File(root, "catalog/trusted-linux-catalog.json")
            val visibleBytes = manifest.readBytes()
            assertTrue(first.refresh())
            val refreshed = idle(first)
            assertTrue(refreshed.entries.isEmpty())
            assertTrue(refreshed.error)
            assertFalse(refreshed.listConfirmed)
            assertSame(reference, refreshed.pendingRemoval)
            assertNotNull(refreshed.notice)
            assertEquals(1, sync.attempts)
            assertFalse(gate.admit(reference) { fail("revocation must not roll back"); true })

            first.close()
            val second = controller.enterPage()
            val reentered = idle(second)
            assertTrue(reentered.entries.isEmpty())
            assertTrue(reentered.error)
            assertSame(reference, reentered.pendingRemoval)
            assertEquals(1, calls.removals.size)
            assertFalse(first.remove(reference))
            assertFalse(second.remove(reference.copy(generation = "other")))
            sync.fail = false
            // Even an equal caller ref must reach the catalog as the original retained ref.
            assertTrue(second.remove(reference.copy()))
            val confirmed = idle(second)
            assertTrue(confirmed.entries.isEmpty())
            assertTrue(confirmed.listConfirmed)
            assertFalse(confirmed.error)
            assertNull(confirmed.pendingRemoval)
            assertEquals(2, sync.attempts)
            assertEquals(1, sync.successes)
            assertEquals(2, calls.removals.size)
            assertSame(reference, calls.removals[0])
            assertSame(reference, calls.removals[1])
            assertTrue(visibleBytes.contentEquals(manifest.readBytes()))
            assertFalse(gate.contains(reference))
            second.close()
        } finally { controller.close() }
    }

    fun testPageRevocationDoesNotWaitForRealCatalogIoAndLateFailureKeepsPending() = fixture { root, draft, _ ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val sync = PostRenameSync(root, active = true) {
            entered.countDown()
            try { check(release.await(5, TimeUnit.SECONDS)) }
            finally { completed.countDown() }
        }
        val catalog = hostLinuxCatalog(File(root, "catalog"), sync)
        val calls = RecordingCatalog(catalog)
        val controller = LinuxSettingsController(calls, LinuxSettingsAdmission(emptySet()))
        try {
            val first = controller.enterPage()
            idle(first)
            first.preview(draft)
            val frozen = idle(first).preview!!
            assertTrue(first.approve(frozen))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(first.revoke())
            val second = controller.enterPage()
            assertEquals(1L, completed.count) // Neither page operation waited for directorySync.
            assertTrue(second.state.value.busy)
            assertFalse(first.approve(frozen))
            release.countDown()
            val state = idle(second)
            assertTrue(state.error)
            assertSame(frozen, state.preview)
            assertSame(frozen, state.pendingApproval)
            assertEquals(1, calls.approvals.size)
            sync.fail = false
            assertTrue(second.approve(frozen))
            assertFalse(idle(second).error)
            assertSame(frozen, calls.approvals[1])
            assertNull(second.state.value.pendingApproval)
            second.close()
        } finally {
            release.countDown()
            controller.close()
        }
    }

    fun testExpectedDigestIsNeverReplacedByComputedFileDigest() = fixture { root, draft, catalog ->
        val controller = LinuxSettingsController(LinuxSettingsCatalogAdapter(catalog), LinuxSettingsAdmission(emptySet()))
        try {
            val page = controller.enterPage()
            idle(page)
            page.preview(draft.copy(prootSha256 = "0".repeat(64)))
            val frozen = idle(page).preview!!
            assertEquals("0".repeat(64), frozen.installation.prootSha256)
            page.approve(frozen)
            val failed = idle(page)
            assertTrue(failed.error)
            assertFalse(failed.listConfirmed)
            assertSame(frozen, failed.preview) // retry the exact object, not a new generation
            assertFalse(File(root, "catalog/trusted-linux-catalog.json").exists())
            assertTrue(catalog.load().isEmpty())
            page.close()
        } finally { controller.close() }
    }
}
