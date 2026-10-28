package app.daiyu.android.terminal

import junit.framework.TestCase
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class LinuxEnvironmentStoreTest : TestCase() {
    private fun fixture(test: (File, LinuxInstallation) -> Unit) {
        val root = Files.createTempDirectory("daiyu-linux-state").toFile()
        val rootfs = File(root, "guest").apply { mkdirs() }
        File(rootfs, "bin").mkdirs()
        File(rootfs, "bin/sh").writeText("test-only shell; never executed")
        val proot = File(root, "trusted-proot").apply { writeText("test-only executable; never executed"); setExecutable(true) }
        val loader = File(root, "trusted-loader").apply { writeText("test-only loader; never executed") }
        val mount = File(root, "shared").apply { mkdirs() }
        fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        val spec = LinuxInstallation("debian", "env-1", "generation-1", rootfs, proot, hash(proot), loader, hash(loader),
            "/workspace", mapOf(mount.path to "/workspace/mounts/shared"))
        try { test(root, spec) } finally { root.deleteRecursively() }
    }
    fun testSelectionPersistsAndNeverClaimsToolsOrRootVerified() = fixture { root, spec ->
        val store = LinuxEnvironmentStore(File(root, "selection"))
        assertEquals("NOT_SELECTED", store.status().getString("state"))
        store.select(spec)
        val reopened = LinuxEnvironmentStore(File(root, "selection"))
        val status = reopened.status()
        assertEquals("FILES_READY", status.getString("state"))
        assertEquals("app", status.getString("host_identity"))
        assertEquals(0, status.getInt("guest_uid"))
        assertFalse(status.getBoolean("tools_verified"))
        assertEquals("not_executed", status.getString("execution"))
    }
    fun testLeaseBlocksSelectionAcrossStoreInstancesAndBuildsExactBindings() = fixture { root, spec ->
        val directory = File(root, "selection")
        val store = LinuxEnvironmentStore(directory).apply { select(spec) }
        val other = LinuxEnvironmentStore(directory)
        store.acquire().use { lease ->
            try { other.clearSelection(); fail("active generation must not be replaced") } catch (expected: IllegalStateException) { }
            try { other.select(spec.copy(generation="new")); fail("active generation must not change") } catch (expected: IllegalStateException) { }
            val backend = lease.backend(root)
            assertEquals(0, backend.expectedUid)
            assertEquals("env-1:generation-1", backend.environmentId)
            assertTrue(backend.shellArgv.contains("-0"))
            assertEquals(1, backend.shellArgv.count { it == "/dev:/dev" })
            assertEquals("-b", backend.shellArgv[backend.shellArgv.indexOf("/dev:/dev") - 1])
            assertEquals("/bin/sh", backend.shellArgv.last())
            assertTrue(backend.shellArgv.contains("${spec.sharedMounts.keys.single()}:${spec.sharedMounts.values.single()}"))
            assertEquals(spec.sharedMounts, backend.sharedMounts)
            assertEquals(requireNotNull(spec.loader).path, backend.environment["PROOT_LOADER"])
        }
        other.clearSelection()
        assertEquals("NOT_SELECTED", store.status().getString("state"))
    }
    fun testRuntimeDevicesCannotBeOverriddenBySharedMounts() = fixture { root, spec ->
        for (target in listOf("/dev", "/dev/pts", "/dev/", "//dev//pts")) {
            val store = LinuxEnvironmentStore(File(root, "selection-${target.hashCode()}"))
            store.select(spec.copy(sharedMounts = mapOf(spec.sharedMounts.keys.single() to target)))
            store.acquire().use { lease ->
                try { lease.backend(root); fail("device bind conflict") }
                catch (expected: IllegalArgumentException) {
                    assertEquals("linux_proot_device_mount_conflict", expected.message)
                }
            }
        }
        val store = LinuxEnvironmentStore(File(root, "selection-device-sibling"))
        store.select(spec.copy(sharedMounts = mapOf(spec.sharedMounts.keys.single() to "/device")))
        store.acquire().use { lease -> assertTrue(lease.backend(root).shellArgv.contains("/dev:/dev")) }
        val dev = File(spec.rootfs, "dev").apply { mkdir() }
        val workspaceStore = LinuxEnvironmentStore(File(root, "selection-device-workspace"))
        workspaceStore.select(spec.copy(sharedMounts = emptyMap()))
        workspaceStore.acquire().use { lease ->
            try { lease.backend(root, LinuxWorkspaceDirectory(dev, "/dev")); fail("device workspace conflict") }
            catch (expected: IllegalArgumentException) {
                assertEquals("linux_workspace_device_mount_conflict", expected.message)
            }
        }
    }

    fun testCorruptHelperOrMetadataNeverFallsBack() = fixture { root, spec ->
        val store = LinuxEnvironmentStore(File(root, "selection")).apply { select(spec) }
        requireNotNull(spec.proot).writeText("replaced helper")
        assertEquals("BROKEN", store.status().getString("state"))
        try { store.acquire(); fail("must refuse changed executable") } catch (expected: IllegalArgumentException) { }
        File(root, "selection/selected-linux.json").writeText("not json")
        assertEquals("BROKEN", store.status().getString("state"))
    }
    fun testFrozenLeaseRejectsRootOrSharedDirectoryReplacement() = fixture { root, spec ->
        val store = LinuxEnvironmentStore(File(root, "selection")).apply { select(spec) }
        store.acquire().use { lease ->
            val mount = File(spec.sharedMounts.keys.single())
            assertTrue(mount.renameTo(File(root, "old-shared")))
            assertTrue(mount.mkdir())
            try { lease.backend(root); fail("snapshot must reject shared directory replacement") } catch (expected: IllegalStateException) { }
        }
    }
    fun testOversizedSelectionAndShellBindOverridesPreservePreviousChoice() = fixture { root, spec ->
        val store = LinuxEnvironmentStore(File(root, "selection")).apply { select(spec) }
        val mounts = (0 until 16).associate { index ->
            val host = File(root, "shared-$index").apply { mkdirs() }.path
            host to ("/" + index + "x".repeat(2200))
        }
        try { store.select(spec.copy(generation="oversized", sharedMounts=mounts)); fail("oversized metadata") } catch (expected: IllegalArgumentException) { }
        assertEquals("generation-1", store.status().getString("generation"))
        for (target in listOf("/", "/bin")) {
            try { store.select(spec.copy(sharedMounts=mapOf(root.path to target))); fail("shell bind override: $target") } catch (expected: IllegalArgumentException) { }
        }
        assertEquals("generation-1", store.status().getString("generation"))
    }
    fun testColonBindInjectionGuestTraversalAndMissingShellRejected() = fixture { root, spec ->
        val store = LinuxEnvironmentStore(File(root, "selection"))
        try { store.select(spec.copy(guestWorkingDirectory="/workspace/../other")); fail("guest traversal") } catch (expected: IllegalArgumentException) { }
        try { store.select(spec.copy(sharedMounts=mapOf(root.path to "/guest:other"))); fail("bind delimiter injection") } catch (expected: IllegalArgumentException) { }
        File(spec.rootfs, "bin/sh").delete()
        try { store.select(spec); fail("missing guest shell") } catch (expected: IllegalArgumentException) { }
        assertEquals("NOT_SELECTED", store.status().getString("state"))
    }
    fun testExpectedGenerationAcquiredAtomicallyAndIdentityFitsBackend() = fixture { root, spec ->
        val store = LinuxEnvironmentStore(File(root,"selection")).apply { select(spec) }
        try { store.acquire(LinuxGenerationRef(spec.environmentId,"stale")); fail("must reject stale selection") }
        catch (expected:IllegalStateException) { assertEquals("linux_generation_mismatch",expected.message) }
        store.select(spec.copy(generation="next")) // Failed acquisition did not leak a lease.
        store.acquire(LinuxGenerationRef(spec.environmentId,"next")).use { lease ->
            assertEquals("env-1:next",lease.backend(root).environmentId)
        }
        try { store.select(spec.copy(environmentId="x".repeat(128))); fail("combined backend identity too long") }
        catch (expected:IllegalArgumentException) { assertEquals("linux_environment_identity_limit",expected.message) }
        assertEquals("next",store.status().getString("generation"))
    }

    fun testColdStartUnknownExecutionBlocksNewScopeAndSelectionReplacement() = fixture { root, spec ->
        val directory = File(root, "selection")
        val store = LinuxEnvironmentStore(directory).apply { select(spec) }
        assertEquals("CLAIMED", runLinuxPinChild(directory, "claim"))
        val previous = File(directory, "selected-linux.json").readBytes()
        assertEquals("RUNNER_UNKNOWN", store.status().getString("state"))
        assertEquals("linux_runner_generation_unknown", store.status().getString("error"))
        for (attempt in listOf<() -> Unit>({ store.acquire().close() },
            { store.select(spec.copy(generation = "new-generation")) }, { store.clearSelection() })) {
            try { attempt(); fail("old unknown runner must block new scopes and selection changes") }
            catch (expected: IllegalStateException) { assertEquals("linux_runner_generation_unknown", expected.message) }
        }
        assertTrue(previous.contentEquals(File(directory, "selected-linux.json").readBytes()))
    }

}
