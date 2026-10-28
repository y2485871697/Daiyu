package app.daiyu.android.terminal.install

import app.daiyu.android.terminal.LinuxExecutionMode
import app.daiyu.android.terminal.LinuxInstallation
import junit.framework.TestCase
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.zip.GZIPOutputStream

/** Real tiny archives and transaction; transport and guest process execution stay inert. */
class LinuxRootfsArchiveInstallTest : TestCase() {
    fun testRealArchivesCleanUpAfterProotSuccessAndBothModeCancellation() {
        for (distribution in listOf("alpine", "debian")) for (mode in LinuxExecutionMode.entries) {
            // CHROOT success needs Android's fixed, trusted /system/bin/su transport.
            val outcomes = if (mode == LinuxExecutionMode.CHROOT) listOf(true) else listOf(false, true)
            for (cancel in outcomes) {
                val root = Files.createTempDirectory("linux-archive-install-test-").toFile()
                try {
                    val format = if (distribution == "debian") RootfsArchive.TAR_XZ else RootfsArchive.TAR_GZIP
                    val prefix = if (distribution == "debian") "debian/" else ""
                    val payload = archive(format, prefix)
                    val artifact = LinuxRootfsArtifact("fixture", distribution, "fixture", "arm64-v8a",
                        format, payload.size.toLong(), hash(payload),
                        listOf(RootfsMirror("primary", URI("https://example.invalid/rootfs"))),
                        stripComponents = if (prefix.isEmpty()) 0 else 1)
                    val privateRoot = File(root, "installations")
                    val target = File(privateRoot, "environment/generation/rootfs")
                    val proot = File(root, "proot").apply { writeText("inert proot"); setExecutable(true) }
                    val loader = File(root, "loader").apply { writeText("inert loader") }
                    val installation = LinuxInstallation(distribution, "environment", "generation", target,
                        if (mode == LinuxExecutionMode.PROOT) proot else null,
                        if (mode == LinuxExecutionMode.PROOT) hash(proot.readBytes()) else "",
                        if (mode == LinuxExecutionMode.PROOT) loader else null,
                        if (mode == LinuxExecutionMode.PROOT) hash(loader.readBytes()) else "",
                        executionMode = mode)
                    val extractor = TarLinuxRootfsExtraction(ops = HostTarRootfsFileOps(root.toPath()))
                    val token = LinuxInstallCancellation()
                    var probes = 0
                    val installer = RootlessLinuxInstaller(privateRoot,
                        LinuxRootfsExtraction { actual, downloaded, stage, cancellation ->
                            extractor.extract(actual, downloaded, stage, cancellation)
                            assertEquals("inert shell", File(stage, "bin/sh").readText())
                            assertFalse(Files.exists(File(stage, "dev/null").toPath(), NOFOLLOW_LINKS))
                            if (cancel) cancellation.cancel()
                        },
                        LinuxInstallProbeRunner { configuration, command, cancellation ->
                            cancellation.check()
                            probes++
                            assertEquals("linux", configuration.backend)
                            assertFalse(configuration.root)
                            assertEquals("id -u", command)
                            val linux = checkNotNull(configuration.linux)
                            assertEquals(0, linux.expectedUid)
                            assertEquals(mode == LinuxExecutionMode.CHROOT, linux.rootScope != null)
                            assertEquals(proot.path, linux.shellArgv.first())
                            assertEquals(listOf("-b", "/dev:/dev", "/bin/sh"), linux.shellArgv.takeLast(3))
                            assertTrue(linux.sharedMounts.isEmpty())
                            LinuxProbeResult(0, "0\n")
                        },
                        LinuxArtifactDownload { _, _, destination, cancellation ->
                            cancellation.check()
                            destination.writeBytes(payload)
                        }, directorySync = {}, fileOps = HostInstallLinkFileOps(),
                        executionMode = mode, requireChrootTransport = {})
                    val prepared = installer.prepare(artifact, "primary", installation)
                    val approval = installer.approveDownloadAndInstall(prepared)
                    if (cancel) {
                        try {
                            installer.install(approval, token)
                            fail("expected cancellation")
                        } catch (_: CancellationException) { }
                        assertFalse(target.exists())
                        assertTrue(target.parentFile.list()!!.isEmpty())
                        assertEquals(0, probes)
                    } else {
                        val receipt = installer.install(approval, token)
                        assertTrue(receipt.directorySyncConfirmed)
                        assertEquals(installation, installer.inspectPublished(prepared))
                        assertEquals("inert shell", File(target, "bin/sh").readText())
                        assertFalse(Files.exists(File(target, "dev/null").toPath(), NOFOLLOW_LINKS))
                        assertEquals(listOf("rootfs"), target.parentFile.list()!!.toList())
                        assertEquals(1, probes)
                    }
                } finally {
                    root.deleteRecursively()
                }
            }
        }
    }

    private fun archive(format: RootfsArchive, prefix: String): ByteArray {
        val tar = ByteArrayOutputStream()
        TarArchiveOutputStream(tar).use { out ->
            val device = TarArchiveEntry("${prefix}dev/null", '3'.code.toByte(), true)
            out.putArchiveEntry(device)
            out.closeArchiveEntry()
            val bytes = "inert shell".toByteArray()
            val shell = TarArchiveEntry("${prefix}bin/sh").apply { size = bytes.size.toLong(); mode = 493 }
            out.putArchiveEntry(shell)
            out.write(bytes)
            out.closeArchiveEntry()
            out.finish()
        }
        val compressed = ByteArrayOutputStream()
        when (format) {
            RootfsArchive.TAR_GZIP -> GZIPOutputStream(compressed).use { it.write(tar.toByteArray()) }
            RootfsArchive.TAR_XZ -> XZOutputStream(compressed, LZMA2Options(0)).use { it.write(tar.toByteArray()) }
        }
        return compressed.toByteArray()
    }

    private fun hash(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
