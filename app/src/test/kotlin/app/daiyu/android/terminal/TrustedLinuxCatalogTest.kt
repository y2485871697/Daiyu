package app.daiyu.android.terminal

import junit.framework.TestCase
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

/** Fixture files are inert text. No shell, download, installer or native helper is invoked. */
class TrustedLinuxCatalogTest : TestCase() {
    private val linkModels = mutableMapOf<File, MutableSet<java.nio.file.Path>>()
    private fun fixture(test: suspend (File, LinuxInstallation, TrustedLinuxCatalog) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("trusted-linux-catalog-").toFile()
        val guest = File(root, "guest").apply { mkdirs() }
        File(guest, "bin").mkdirs()
        File(guest, "bin/sh").writeText("fixture shell; never executed")
        val proot = File(root, "proot").apply { writeText("inert proot"); check(setExecutable(true)) }
        val loader = File(root, "loader").apply { writeText("inert loader") }
        fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val spec = LinuxInstallation("fixture", "installed-env", "ignored-input-generation", guest,
            proot, hash(proot), loader, hash(loader))
        try {
            val links = mutableSetOf<java.nio.file.Path>()
            linkModels[root] = links
            test(root, spec, TrustedLinuxCatalog(File(root, "catalog"), ::hostDirectorySync,
                symbolicLink = { Files.isSymbolicLink(it) || it.toAbsolutePath().normalize() in links }))
        }
        finally { linkModels.remove(root); root.deleteRecursively() }
    }

    private suspend fun rejected(action: suspend () -> Unit) {
        try { action() } catch (_: Exception) { return }
        fail("must reject rather than silently grant/replace authority")
    }
    private fun manifest(root: File) = File(root, "catalog/trusted-linux-catalog.json")
    private fun reference(value: LinuxInstallation) = LinuxGenerationRef(value.environmentId, value.generation)

    /** Fail only after the requested one-record snapshot has actually replaced the manifest. */
    private class PostRenameDirectorySyncFailure(root: File, private val active: Boolean) : (File) -> Unit {
        private val catalogDirectory = File(root, "catalog")
        private val manifest = File(catalogDirectory, "trusted-linux-catalog.json")
        var attempts = 0
            private set
        var successes = 0
            private set

        override fun invoke(directory: File) {
            val snapshotVisible = directory == catalogDirectory && manifest.isFile &&
                JSONObject(manifest.readText()).getJSONArray("records").let {
                    it.length() == 1 && it.getJSONObject(0).getBoolean("active") == active
                }
            if (snapshotVisible) {
                check(!File(catalogDirectory, "trusted-linux-catalog.json.tmp").exists())
                attempts++
                if (attempts <= 2) throw IOException("injected_catalog_directory_fsync_failure")
            }
            hostDirectorySync(directory)
            if (snapshotVisible) successes++
        }
    }

    private suspend fun directorySyncRejected(action: suspend () -> Unit) {
        try { action() } catch (failure: IOException) {
            assertEquals("injected_catalog_directory_fsync_failure", failure.message)
            return
        }
        fail("must not acknowledge the visible snapshot before directory fsync succeeds")
    }

    fun testInstalledRequestCanRetireAgainstRecordedTombstoneButNeverReactivateIt() = fixture { root, spec, catalog ->
        val approval = catalog.freezeForApproval(spec)
        catalog.approve(approval)
        catalog.remove(reference(approval.installation))
        val tombstone = manifest(root).readBytes()
        var admissions = 0
        assertTrue(catalog.approveInstallation(approval) { admissions++; true }.isEmpty())
        assertEquals(1, admissions)
        assertTrue(tombstone.contentEquals(manifest(root).readBytes()))
        assertTrue(catalog.registrationRecorded(approval))
        rejected { catalog.approve(approval) }
        rejected { catalog.approveInstallation(FrozenLinuxApproval(
            approval.installation.copy(guestWorkingDirectory = "/changed"))) { true } }
        assertTrue(tombstone.contentEquals(manifest(root).readBytes()))
    }

    fun testRevokedAdmissionRunsOnceBeforeEvenReadingMalformedCatalog() = fixture { root, spec, catalog ->
        File(root, "catalog").mkdirs()
        manifest(root).writeText("malformed catalog")
        var admissions = 0
        val failure = runCatching {
            catalog.approve(catalog.freezeForApproval(spec)) { admissions++; false }
        }.exceptionOrNull()
        assertEquals("linux_catalog_admission_revoked", failure?.message)
        assertEquals(1, admissions)
        assertEquals("malformed catalog", manifest(root).readText())
        assertFalse(File(root, "catalog/validation-only").exists())
    }

    fun testOnlyExplicitFrozenApprovalCreatesAuthorityAndDoesNotSelect() = fixture { root, spec, catalog ->
        assertEquals(emptyList<LinuxInstallation>(), catalog.load())
        val approval = catalog.freezeForApproval(spec)
        assertFalse(manifest(root).exists())
        assertFalse(File(root, "catalog").exists())
        assertEquals(emptyList<LinuxInstallation>(), catalog.load())
        assertFalse(approval.installation.generation == spec.generation)
        val approved = catalog.approve(approval)
        assertEquals(listOf(approval.installation), approved)
        assertTrue(approved.single().sharedMounts.isEmpty())
        assertEquals(approved, hostLinuxCatalog(File(root, "catalog")).load())
        assertFalse(File(root, "catalog/selected-linux.json").exists())
        assertFalse(File(root, "user-terminal/linux-selection").exists())
        assertEquals(approved, catalog.approve(approval)) // exact repeat is idempotent
    }

    fun testResidualSelectionAndValidationScratchCannotAuthorize() = fixture { root, spec, catalog ->
        hostLinuxStore(File(root, "user-terminal/linux-selection")).select(spec)
        hostLinuxStore(File(root, "catalog/validation-only")).select(spec)
        // Even a selection placed beside the catalog is not an import source.
        hostLinuxStore(File(root, "catalog")).select(spec)
        assertTrue(catalog.load().isEmpty())
        assertFalse(manifest(root).exists())
    }

    fun testApprovalFreezesMountsBeforeWorkerAndReturnedCollectionsCannotMutate() = fixture { root, spec, catalog ->
        val share = File(root, "explicit-share").apply { mkdirs() }
        val mounts = linkedMapOf(share.path to "/work/share")
        val approval = catalog.freezeForApproval(spec.copy(sharedMounts = mounts, guestWorkingDirectory = "/work"))
        mounts.clear()
        val loaded = catalog.approve(approval)
        assertEquals(mapOf(share.path to "/work/share"), loaded.single().sharedMounts)
        rejected { (loaded as MutableList<LinuxInstallation>).clear() }
        rejected { (loaded.single().sharedMounts as MutableMap<String, String>).clear() }
        assertEquals(loaded, catalog.load())
    }

    fun testMalformedUnknownAndDuplicateReferencesAreNotRecoveredOrOverwritten() = fixture { root, spec, catalog ->
        val approval = catalog.freezeForApproval(spec)
        catalog.approve(approval)
        val good = manifest(root).readText()
        val duplicate = JSONObject(good)
        duplicate.getJSONArray("records").put(duplicate.getJSONArray("records").getJSONObject(0))
        val unknown = JSONObject(good).put("unexpected", true).toString()
        for (bad in listOf("not json", duplicate.toString(), unknown,
            good.replace("\"version\":1", "\"version\":9"))) {
            manifest(root).writeText(bad)
            rejected { catalog.load() }
            rejected { catalog.approve(catalog.freezeForApproval(spec)) }
            assertEquals(bad, manifest(root).readText())
        }
    }

    fun testKnownDigestMismatchPreservesOriginalCatalog() = fixture { root, spec, catalog ->
        val original = catalog.approve(catalog.freezeForApproval(spec))
        val before = manifest(root).readBytes()
        rejected { catalog.approve(catalog.freezeForApproval(spec.copy(prootSha256 = "0".repeat(64)))) }
        assertTrue(before.contentEquals(manifest(root).readBytes()))
        assertEquals(original, catalog.load())
        spec.loader.writeText("changed on disk")
        rejected { catalog.load() }
        rejected { catalog.approve(catalog.freezeForApproval(spec)) }
        assertTrue(before.contentEquals(manifest(root).readBytes()))
        // Removal of the broken generation remains possible and never deletes installed files.
        assertTrue(catalog.remove(reference(original.single())).isEmpty())
        assertTrue(spec.rootfs.isDirectory)
        assertTrue(spec.proot.isFile)
    }

    fun testNewOrChangedConfigurationGetsNewImmutableGenerationAndTombstone() = fixture { root, spec, catalog ->
        val first = catalog.freezeForApproval(spec)
        catalog.approve(first)
        val second = catalog.freezeForApproval(spec.copy(guestWorkingDirectory = "/work"))
        assertFalse(first.installation.generation == second.installation.generation)
        val both = catalog.approve(second)
        assertEquals(listOf(first.installation, second.installation), both)
        val before = manifest(root).readBytes()
        // Even a forged same-module token cannot replace the fields behind an existing reference.
        rejected { catalog.approve(FrozenLinuxApproval(first.installation.copy(guestWorkingDirectory = "/evil"))) }
        assertTrue(before.contentEquals(manifest(root).readBytes()))
        assertEquals(listOf(second.installation), catalog.remove(reference(first.installation)))
        rejected { catalog.approve(first) }
        assertEquals(listOf(second.installation), catalog.load())
        val records = JSONObject(manifest(root).readText()).getJSONArray("records")
        assertEquals(2, records.length())
        assertFalse(records.getJSONObject(0).getBoolean("active"))
        assertEquals(first.installation.generation, records.getJSONObject(0).getJSONObject("installation").getString("generation"))
    }

    fun testPathInjectionAndUnsafeBindCannotPublish() = fixture { root, spec, catalog ->
        val approved = catalog.approve(catalog.freezeForApproval(spec))
        val before = manifest(root).readBytes()
        val bad = listOf(
            spec.copy(rootfs = File("content://provider/root")),
            spec.copy(proot = File(root, "proot\n--evil")),
            spec.copy(guestWorkingDirectory = "/work/../elsewhere"),
            spec.copy(guestWorkingDirectory = "/work:evil"),
            spec.copy(sharedMounts = mapOf(root.path to "/guest:evil")),
            spec.copy(sharedMounts = mapOf(root.path to "/bin")),
            spec.copy(sharedMounts = mapOf(root.path to "/"))
        )
        for (configuration in bad) rejected { catalog.approve(catalog.freezeForApproval(configuration)) }
        assertTrue(before.contentEquals(manifest(root).readBytes()))
        assertEquals(approved, catalog.load())
    }

    fun testPreCommitWriteFailureKeepsLastApprovalAndAllowsRetry() = fixture { root, spec, catalog ->
        val first = catalog.freezeForApproval(spec)
        val original = catalog.approve(first)
        val second = catalog.freezeForApproval(spec.copy(guestWorkingDirectory = "/work"))
        val before = manifest(root).readBytes()
        val blocked = File(root, "catalog/trusted-linux-catalog.json.tmp").apply { mkdir() }
        rejected { catalog.approve(second) }
        assertTrue(before.contentEquals(manifest(root).readBytes()))
        assertEquals(original, catalog.load())
        rejected { catalog.remove(reference(first.installation)) }
        assertTrue(before.contentEquals(manifest(root).readBytes()))
        assertTrue(blocked.delete())
        assertEquals(2, catalog.approve(second).size)
    }

    fun testSameApprovalRetryMustSyncDirectoryAfterPostRenameFailure() = fixture { root, spec, _ ->
        val sync = PostRenameDirectorySyncFailure(root, active = true)
        val catalog = hostLinuxCatalog(File(root, "catalog"), sync)
        val approval = catalog.freezeForApproval(spec)
        directorySyncRejected { catalog.approve(approval) }
        assertEquals(1, sync.attempts)
        assertEquals(0, sync.successes)
        val visible = manifest(root).readBytes()
        val records = JSONObject(manifest(root).readText()).getJSONArray("records")
        assertEquals(1, records.length())
        assertTrue(records.getJSONObject(0).getBoolean("active"))
        assertEquals(approval.installation.generation,
            records.getJSONObject(0).getJSONObject("installation").getString("generation"))
        // Loading the renamed snapshot is reconciliation, not a durability acknowledgment.
        assertEquals(listOf(approval.installation), catalog.load())
        assertEquals(1, sync.attempts)

        directorySyncRejected { catalog.approve(approval) } // same catalog AND frozen object
        assertEquals(2, sync.attempts)
        assertEquals(0, sync.successes)
        assertTrue(visible.contentEquals(manifest(root).readBytes()))
        assertEquals(listOf(approval.installation), catalog.approve(approval))
        assertEquals(3, sync.attempts)
        assertEquals(1, sync.successes)
        assertTrue(visible.contentEquals(manifest(root).readBytes()))
        assertEquals(listOf(approval.installation), hostLinuxCatalog(File(root, "catalog")).load())
    }

    fun testLastActiveRemovalRetryMustSyncDirectoryAfterPostRenameFailure() = fixture { root, spec, original ->
        val approval = original.freezeForApproval(spec)
        original.approve(approval)
        val sync = PostRenameDirectorySyncFailure(root, active = false)
        val catalog = hostLinuxCatalog(File(root, "catalog"), sync)
        val ref = reference(approval.installation)
        directorySyncRejected { catalog.remove(ref) }
        assertEquals(1, sync.attempts)
        assertEquals(0, sync.successes)
        val visible = manifest(root).readBytes()
        val records = JSONObject(manifest(root).readText()).getJSONArray("records")
        assertEquals(1, records.length())
        assertFalse(records.getJSONObject(0).getBoolean("active"))
        assertEquals(approval.installation.generation,
            records.getJSONObject(0).getJSONObject("installation").getString("generation"))
        assertTrue(catalog.load().isEmpty())
        assertEquals(1, sync.attempts)

        directorySyncRejected { catalog.remove(ref) } // no active entries left to validate
        assertEquals(2, sync.attempts)
        assertEquals(0, sync.successes)
        assertTrue(visible.contentEquals(manifest(root).readBytes()))
        assertTrue(catalog.remove(ref).isEmpty())
        assertEquals(3, sync.attempts)
        assertEquals(1, sync.successes)
        assertTrue(visible.contentEquals(manifest(root).readBytes()))
        assertTrue(hostLinuxCatalog(File(root, "catalog")).load().isEmpty())
        rejected { catalog.approve(approval) } // a recovered tombstone still cannot resurrect
        assertTrue(visible.contentEquals(manifest(root).readBytes()))
    }

    fun testSymlinkCatalogRejectedWithoutFollowingExternalAuthority() = fixture { root, spec, catalog ->
        catalog.approve(catalog.freezeForApproval(spec))
        val outside = File(root, "outside.json")
        assertTrue(manifest(root).renameTo(outside))
        if (File.separatorChar != '\\') Files.createSymbolicLink(manifest(root).toPath(), outside.toPath())
        else {
            Files.createFile(manifest(root).toPath())
            checkNotNull(linkModels[root]).add(manifest(root).toPath().toAbsolutePath().normalize())
        }
        val before = outside.readBytes()
        try { catalog.load(); fail("must reject catalog link") }
        catch (failure: IllegalStateException) { assertEquals("linux_catalog_symlink", failure.message) }
        try { catalog.approve(catalog.freezeForApproval(spec)); fail("must reject catalog link") }
        catch (failure: IllegalStateException) { assertEquals("linux_catalog_symlink", failure.message) }
        assertTrue(before.contentEquals(outside.readBytes()))
    }
}
