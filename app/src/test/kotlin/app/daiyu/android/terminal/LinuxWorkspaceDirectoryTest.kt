package app.daiyu.android.terminal

import app.daiyu.android.integration.tools.TerminalPtyLaunch
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class LinuxWorkspaceDirectoryTest {
    private fun fixture(action: (File, LinuxInstallation, LinuxEnvironmentStore) -> Unit) {
        val root = Files.createTempDirectory("linux-workspace-").toFile().canonicalFile
        try {
            val rootfs = File(root, "rootfs").apply { File(this, "bin").mkdirs(); File(this, "bin/sh").writeText("fixture") }
            val proot = File(root, "proot").apply { writeText("fixture"); setExecutable(true) }
            val loader = File(root, "loader").apply { writeText("fixture") }
            fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
            val spec = LinuxInstallation("fixture", "env", "generation", rootfs, proot, hash(proot), loader, hash(loader))
            val store = LinuxEnvironmentStore(File(root, "selection")).apply { select(spec) }
            action(root, spec, store)
        } finally { root.deleteRecursively() }
    }

    @Test fun twoFrozenLaunchesRetainIndependentCwdThroughRevalidationAndCommandConfiguration() = fixture { root, spec, store ->
        val a = LinuxWorkspaceDirectory(File(root, "a").apply { mkdirs() }, "/workspace", true)
        val b = LinuxWorkspaceDirectory(File(spec.rootfs, "work/b").apply { mkdirs() }, "/work/b")
        val ref = LinuxGenerationRef(spec.environmentId, spec.generation)
        val selection = File(root, "selection/selected-linux.json").readBytes()
        TerminalPtyLaunch.Linux.freeze(store, ref, root, root, a).use { first ->
            TerminalPtyLaunch.Linux.freeze(store, ref, root, root, b).use { second ->
                repeat(2) { first.revalidate(); second.revalidate() }
                assertEquals(a.hostDirectory, first.commandConfiguration().workingDirectory)
                assertEquals(b.hostDirectory, second.commandConfiguration().workingDirectory)
                assertEquals("/workspace", first.commandConfiguration().linux!!.guestWorkingDirectory)
                assertEquals("/work/b", second.commandConfiguration().linux!!.guestWorkingDirectory)
                assertTrue(first.argv.contains("${a.hostDirectory.path}:/workspace"))
                assertFalse(second.argv.contains("${a.hostDirectory.path}:/workspace"))
                assertArrayEquals(selection, File(root, "selection/selected-linux.json").readBytes())
                assertEquals("/", store.acquire(ref).use { it.backend(root).guestWorkingDirectory })
            }
        }
    }

    @Test fun approvedGenerationStorePinsAndFreezesWithoutSelectingGlobalInstallation() = fixture { root, spec, global ->
        val other = spec.copy(environmentId = "other", generation = "other-generation")
        val selection = TerminalTrustedLinuxSelection(File(root, "selection"), listOf(spec, other))
        val ref = LinuxGenerationRef(other.environmentId, other.generation)
        val workspace = LinuxWorkspaceDirectory(File(other.rootfs, "project").apply { mkdirs() }, "/project")
        val pin = selection.withInstallation(ref) { it.acquire(ref) }
        try {
            assertEquals(spec.environmentId, global.status().getString("environment_id"))
            selection.withInstallation(ref) { store ->
                TerminalPtyLaunch.Linux.freeze(store, ref, root, root, workspace).use { launch ->
                    global.clearSelection()
                    launch.revalidate()
                    assertEquals(workspace.hostDirectory, launch.commandConfiguration().workingDirectory)
                    global.select(spec)
                    launch.revalidate()
                }
            }
            assertEquals(spec.environmentId, global.status().getString("environment_id"))
            val generations = File(root, "workspace-generations").listFiles()!!
            assertTrue(generations.isNotEmpty())
            assertTrue(generations.none { File(it, "selected-linux.json").exists() })
        } finally { pin.close() }
    }

    @Test fun actualLinuxOpenConfigurationKeepsOverrideAndReleasesFailedPrestartPin() = fixture { root, spec, store ->
        val workspace = LinuxWorkspaceDirectory(File(spec.rootfs, "open-cwd").apply { mkdirs() }, "/open-cwd")
        val ref = LinuxGenerationRef(spec.environmentId, spec.generation)
        val owner = PtyRunOwner(app.daiyu.android.domain.ConversationId("open"),
            app.daiyu.android.domain.tools.TaskAuthorization("open", "run"), ref.environmentId, ref.generation)
        var observed = false
        try {
            LinuxPtySession.openConfigured(owner, store, ref, root, root, workspace) { configuration ->
                observed = true
                assertEquals(workspace.hostDirectory, configuration.workingDirectory)
                assertEquals(workspace.guestDirectory, configuration.linux!!.guestWorkingDirectory)
                assertTrue(configuration.linux!!.shellArgv.contains("/open-cwd"))
                error("test_prestart")
            }
            fail("test seam should not launch")
        } catch (failure: IllegalStateException) { assertEquals("test_prestart", failure.message) }
        assertTrue(observed)
        store.clearSelection()
    }

    @Test fun rootfsMappingAndSymlinkAndDifferentGenerationFailClosed() = fixture { root, spec, store ->
        val host = File(spec.rootfs, "work").apply { mkdirs() }
        val valid = LinuxWorkspaceDirectory(host, "/work")
        store.acquire().use { lease ->
            assertEquals(host, lease.configuration(root, root, valid).workingDirectory)
            for (invalid in listOf(valid.copy(guestDirectory = "/other"), valid.copy(hostDirectory = root))) {
                try { lease.backend(root, invalid); fail("mapping accepted") } catch (_: IllegalArgumentException) { }
            }
            val link = File(spec.rootfs, "linked")
            Files.createSymbolicLink(link.toPath(), host.toPath())
            try { lease.backend(root, LinuxWorkspaceDirectory(link, "/linked")); fail("symlink accepted") }
            catch (_: IllegalArgumentException) { }
            assertTrue(host.renameTo(File(spec.rootfs, "old-work")))
            assertTrue(host.mkdir())
            try { lease.backend(root, valid); fail("directory replacement accepted") }
            catch (failure: IllegalStateException) { assertEquals("linux_workspace_changed", failure.message) }
        }
        try {
            TerminalPtyLaunch.Linux.freeze(store, LinuxGenerationRef("env", "stale"), root, root, valid)
            fail("stale generation accepted")
        } catch (failure: IllegalStateException) { assertEquals("linux_generation_mismatch", failure.message) }
    }

    @Test fun chrootRootfsCwdUsesOnlyFixedSourceAndPositionalArgument() = fixture { _, spec, _ ->
        val cwd = "/project with 'quotes' and \$(literal)"
        val launch = RootScopeLaunch(spec.rootfs, requireNotNull(spec.proot), guestWorkingDirectory = cwd)
        assertEquals(listOf("/system/bin/chroot", spec.rootfs.path, "/bin/sh", "-c",
            "cd -- \"\$1\" && exec /bin/sh", "daiyu-workspace", cwd), launch.guestArgv())
    }

    @Test fun chrootPrivateBindUsesFrozenSourceAndFixedGuestTarget() = fixture { root, spec, _ ->
        val workspace = LinuxWorkspaceDirectory(root, "/workspace", true)
        assertEquals(listOf(root), workspace.validateFor(spec.copy(executionMode = LinuxExecutionMode.CHROOT)))
        val launch = RootScopeLaunch(spec.rootfs, requireNotNull(spec.proot), guestWorkingDirectory = "/workspace",
            workspaceSource = root, workspaceIdentity = RootWorkspaceIdentity(7, 11))
        assertEquals(root, launch.workspaceSource)
        assertEquals("/workspace", launch.guestWorkingDirectory)
        assertEquals(11, launch.binding().getLong("workspace_inode"))
        assertFalse(launch.guestArgv().contains(root.path))
        try { workspace.copy(guestDirectory = "/other").validateFor(spec); fail("arbitrary bind target accepted") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun chrootCommandAndCwdRemainSeparatePositionalArguments() = fixture { _, spec, _ ->
        val cwd = "/a 'quoted' $(literal)"
        val command = "printf '%s' \"a; $(literal)\"\nexit 7"
        val launch = RootScopeLaunch(spec.rootfs, requireNotNull(spec.proot), guestWorkingDirectory = cwd, command = command)
        val args = launch.guestArgv()
        assertEquals(listOf("daiyu-command", cwd, command), args.takeLast(3))
        assertEquals("cd -- \"\$1\" && exec /bin/sh -c \"\$2\"", args[4])
    }
}
