package app.daiyu.android.terminal

import android.system.Os
import android.system.OsConstants
import android.test.InstrumentationTestCase
import java.io.File
import java.util.UUID
import java.util.zip.GZIPOutputStream

/** Exercises the package-installed host helper, including on API26; no guest is executed. */
@Suppress("DEPRECATION")
class LinuxBackupProcessTest : InstrumentationTestCase() {
    private lateinit var base: File
    private lateinit var process: LinuxBackupProcess
    override fun setUp() {
        super.setUp()
        base = File(instrumentation.targetContext.cacheDir.canonicalFile, "rootfs-test-${UUID.randomUUID()}")
        check(base.mkdir())
        process = LinuxBackupProcess(instrumentation.targetContext)
    }
    override fun tearDown() {
        try {
            base.listFiles()?.filter { it.isDirectory }?.forEach { process.remove(it, LinuxExecutionMode.PROOT) }
            check(base.deleteRecursively())
        } finally { super.tearDown() }
    }
    private fun directory(name: String) = File(base, name).apply { check(mkdir()); Os.chmod(path, 448) }

    fun testHostArchiveRoundTripPreservesLinksPermissionsAndDoesNotExecuteGuest() {
        val source = directory("source")
        val executable = File(source, "file").apply { writeText("not a guest program\n") }
        Os.chmod(executable.path, 489) // 0751
        val sticky = File(source, "tmp").apply { check(mkdir()); Os.chmod(path, 1023) }
        Os.link(executable.path, File(source, "hard").path)
        Os.symlink("file", File(source, "relative").path)
        Os.symlink("/usr/bin/sh", File(source, "absolute").path)
        File(source, ".daiyu-rootfs-install").writeText("old transaction")
        val archive = File(base, "rootfs.gz")
        var packed = 0L
        process.pack(source, LinuxExecutionMode.PROOT, archive) { packed = it }
        assertTrue(packed > 0)
        val target = directory("target")
        process.unpack(archive, target, LinuxExecutionMode.PROOT, android.os.Process.myUid(), Os.getgid()) {}
        assertEquals(executable.readText(), File(target, "file").readText())
        assertEquals(489, Os.stat(File(target, "file").path).st_mode and 4095)
        assertEquals(Os.stat(sticky.path).st_mode and 4095, Os.stat(File(target, "tmp").path).st_mode and 4095)
        assertEquals(Os.stat(File(target, "file").path).st_ino, Os.stat(File(target, "hard").path).st_ino)
        assertEquals("file", Os.readlink(File(target, "relative").path))
        assertEquals("/usr/bin/sh", Os.readlink(File(target, "absolute").path))
        assertFalse(File(target, ".daiyu-rootfs-install").exists())
    }

    fun testMalformedArchiveFailsAndPrivatePartialDestinationCanBeCleaned() {
        val archive = File(base, "broken.gz")
        GZIPOutputStream(archive.outputStream()).use { it.write("not DRFS1".toByteArray()) }
        val target = directory("partial")
        var rejected = false
        try { process.unpack(archive, target, LinuxExecutionMode.PROOT, android.os.Process.myUid(), Os.getgid()) {} }
        catch (_: Exception) { rejected = true }
        assertTrue(rejected)
        val zero = File(target, "zero").apply { check(mkdir()) }
        File(zero, "file").writeText("partial")
        Os.chmod(zero.path, 0); Os.chmod(target.path, 0)
        process.remove(target, LinuxExecutionMode.PROOT)
        assertTrue(target.listFiles()!!.isEmpty())
    }

    fun testSpecialFileRefusesCompleteBackupWithoutBlockingOrFollowingIt() {
        val source = directory("special")
        Os.mkfifo(File(source, "pipe").path, OsConstants.S_IRUSR or OsConstants.S_IWUSR)
        var rejected = false
        try { process.pack(source, LinuxExecutionMode.PROOT, File(base, "incomplete.gz")) {} }
        catch (_: Exception) { rejected = true }
        assertTrue(rejected)
        assertTrue(OsConstants.S_ISFIFO(Os.lstat(File(source, "pipe").path).st_mode))
    }
}
