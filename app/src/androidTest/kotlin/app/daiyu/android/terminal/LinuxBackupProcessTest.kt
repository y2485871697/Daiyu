package app.daiyu.android.terminal

import android.system.Os
import android.system.OsConstants
import android.system.ErrnoException
import java.io.DataOutputStream
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

    private fun hardlinksAvailable(executable: File): Boolean {
        val stat = Os.stat(executable.path)
        assertTrue(OsConstants.S_ISREG(stat.st_mode))
        assertEquals(android.os.Process.myUid(), stat.st_uid)
        assertEquals(489, stat.st_mode and 4095)
        assertEquals(448, Os.stat(executable.parentFile!!.path).st_mode and 4095)
        return try {
            Os.link(executable.path, File(executable.parentFile, "hard").path)
            true
        } catch (failure: ErrnoException) {
            if (failure.errno != OsConstants.EACCES && failure.errno != OsConstants.EPERM) throw failure
            android.util.Log.i("DaiyuRootfsTest", "hardlink policy denied errno=${failure.errno} api=${android.os.Build.VERSION.SDK_INT} uid=${stat.st_uid}")
            false
        }
    }

    fun testHostArchiveRoundTripPreservesLinksPermissionsAndDoesNotExecuteGuest() {
        val source = directory("source")
        val executable = File(source, "file").apply { writeText("not a guest program\n") }
        Os.chmod(executable.path, 489) // 0751
        val sticky = File(source, "tmp").apply { check(mkdir()); Os.chmod(path, 1023) }
        val hardlinks = hardlinksAvailable(executable)
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
        if (hardlinks) {
            val regular = Os.stat(File(target, "file").path)
            val hard = Os.stat(File(target, "hard").path)
            assertEquals(regular.st_dev, hard.st_dev)
            assertEquals(regular.st_ino, hard.st_ino)
            assertTrue(regular.st_nlink >= 2)
        } else assertFalse(File(target, "hard").exists())
        assertEquals("file", Os.readlink(File(target, "relative").path))
        assertEquals("/usr/bin/sh", Os.readlink(File(target, "absolute").path))
        assertFalse(File(target, ".daiyu-rootfs-install").exists())
    }

    fun testHardlinkRestorePreservesIdentityOrRejectsPlatformPolicyWithoutFallback() {
        val probe = directory("probe")
        val file = File(probe, "file").apply { writeText("hardlink content\n"); Os.chmod(path, 489) }
        val allowed = hardlinksAvailable(file)
        val archive = File(base, "hardlink.gz")
        DataOutputStream(GZIPOutputStream(archive.outputStream())).use { output ->
            fun text(value: String) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                output.writeInt(bytes.size); output.write(bytes)
            }
            fun metadata(mode: Int) {
                output.writeInt(mode); output.writeInt(android.os.Process.myUid()); output.writeInt(Os.getgid())
                output.writeLong(0); output.writeInt(0); output.writeInt(0)
            }
            output.writeBytes("DRFS1"); metadata(448)
            output.writeByte('F'.code); text("file"); metadata(489)
            val bytes = file.readBytes(); output.writeLong(bytes.size.toLong()); output.write(bytes)
            output.writeByte('H'.code); text("hard"); text("file")
            output.writeByte('E'.code); output.writeByte('Z'.code)
        }
        val target = directory("hardlink-target")
        if (allowed) {
            process.unpack(archive, target, LinuxExecutionMode.PROOT, android.os.Process.myUid(), Os.getgid()) {}
            val regular = Os.stat(File(target, "file").path)
            val hard = Os.stat(File(target, "hard").path)
            assertEquals(regular.st_dev, hard.st_dev); assertEquals(regular.st_ino, hard.st_ino)
            assertTrue(regular.st_nlink >= 2)
        } else {
            try {
                process.unpack(archive, target, LinuxExecutionMode.PROOT, android.os.Process.myUid(), Os.getgid()) {}
                fail("Unavailable hardlinks must not be copied or silently accepted")
            } catch (failure: IllegalStateException) {
                assertEquals("linux_backup_hardlink_unavailable", failure.message)
            }
            assertEquals(file.readText(), File(target, "file").readText())
            assertFalse(File(target, "hard").exists())
            process.remove(target, LinuxExecutionMode.PROOT)
            assertTrue(target.listFiles()!!.isEmpty())
        }
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
