package app.daiyu.android.terminal

import android.content.ContextWrapper
import android.system.Os
import android.test.InstrumentationTestCase
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.util.UUID

@Suppress("DEPRECATION")
class LinuxBackupRestoreTest : InstrumentationTestCase() {
    fun testOfflineRestorePublishesNewRootAndRequiresSeparateApprovalWithoutSelectingIt() {
        val base = File(instrumentation.targetContext.cacheDir.canonicalFile, "restore-${UUID.randomUUID()}").apply { check(mkdir()) }
        val context = object : ContextWrapper(instrumentation.targetContext) { override fun getFilesDir() = base }
        val process = LinuxBackupProcess(context)
        try {
            val source = File(base, "original").apply { check(mkdir()); Os.chmod(path, 448) }
            File(source, "bin").mkdir()
            File(source, "bin/sh").apply { writeText("not an executable guest; never run"); Os.chmod(path, 493) }
            File(source, "sentinel").writeText("original environment")
            val selection = File(base, "user-terminal/linux-selection/selected-linux.json").apply { parentFile!!.mkdirs(); writeText("old selection unchanged") }
            val archive = File(base, "rootfs.gz")
            process.pack(source, LinuxExecutionMode.PROOT, archive) {}
            val unsupported = LinuxRuntimePlatform.unavailableReason(LinuxExecutionMode.PROOT)
            if (unsupported != null) {
                var refused = false
                try { LinuxRuntimePlatform.requireSupported(LinuxExecutionMode.PROOT) } catch (_: Exception) { refused = true }
                assertTrue(refused)
                assertFalse(File(base, "linux-installations").exists())
                return
            }
            val metadata = JSONObject().put("distribution", "debian").put("mode", "proot")
                .put("abi", LinuxRuntimePlatform.requireSupported(LinuxExecutionMode.PROOT))
                .put("host_uid", android.os.Process.myUid()).put("host_gid", Os.getgid())
            val restorer = LinuxBackupRestore(context)
            val id = "a".repeat(64)
            val candidate = restorer.prepare(id, metadata, archive) {}.use { it.publish() }
            assertFalse(candidate.alreadyRestored)
            assertFalse(candidate.installation.rootfs.canonicalFile == source.canonicalFile)
            assertEquals("original environment", File(candidate.installation.rootfs, "sentinel").readText())
            val catalog = TrustedLinuxCatalog(File(base, "trusted-linux-catalog"), File(base, "linux-chroot-installations"))
            assertTrue(runBlocking { catalog.load() }.isEmpty())
            val repeated = restorer.prepare(id, metadata, archive) {}.use { it.publish() }
            assertTrue(repeated.alreadyRestored)
            assertEquals(candidate.installation, repeated.installation)
            runBlocking { restorer.approve(candidate) }
            assertEquals(listOf(candidate.installation), runBlocking { catalog.load() })
            assertEquals("old selection unchanged", selection.readText())
            assertEquals("original environment", File(source, "sentinel").readText())
        } finally {
            // Clear only roots owned by this fixture, through the same no-follow host helper.
            File(base, "linux-installations").listFiles()?.forEach { environment ->
                environment.listFiles()?.forEach { generation ->
                    val root = File(generation, "rootfs")
                    if (root.isDirectory) process.remove(root, LinuxExecutionMode.PROOT)
                }
            }
            check(base.deleteRecursively())
        }
    }
}
