package app.daiyu.android.terminal.install

import junit.framework.TestCase
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import java.util.zip.GZIPOutputStream

/** Real tiny tar/gzip/xz fixtures, no downloads or guest/host process execution. */
class TarLinuxRootfsExtractionTest : TestCase() {
    private data class Item(val name: String, val content: String = "", val type: Char = '0',
                            val target: String = "", val mode: Int = 493)

    private fun tar(vararg items: Item): ByteArray {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(bytes).use { out ->
            out.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            for (item in items) {
                val entry = TarArchiveEntry(item.name, item.type.code.toByte(), true)
                entry.mode = item.mode
                entry.linkName = item.target
                val payload = item.content.toByteArray()
                entry.size = if (item.type == '0') payload.size.toLong() else 0L
                out.putArchiveEntry(entry)
                if (item.type == '0') out.write(payload)
                out.closeArchiveEntry()
            }
            out.finish()
        }
        return bytes.toByteArray()
    }

    private fun compressed(tar: ByteArray, format: RootfsArchive): ByteArray {
        val bytes = ByteArrayOutputStream()
        when (format) {
            RootfsArchive.TAR_GZIP -> GZIPOutputStream(bytes).use { it.write(tar) }
            RootfsArchive.TAR_XZ -> XZOutputStream(bytes, LZMA2Options(0)).use { it.write(tar) }
        }
        return bytes.toByteArray()
    }

    private class Fixture(val root: File) {
        val stage = File(root, "stage").apply { mkdir() }
        val archive = File(root, "archive")
        val ops = HostTarRootfsFileOps(root.toPath())
        fun extractor(maxExpandedBytes: Long = TarLinuxRootfsExtraction.MAX_EXPANDED_BYTES,
                      maxEntries: Int = TarLinuxRootfsExtraction.MAX_ENTRIES) =
            TarLinuxRootfsExtraction(maxExpandedBytes, maxEntries, ops)
        fun extract(payload: ByteArray, format: RootfsArchive = RootfsArchive.TAR_GZIP,
                    strip: Int = 0, extractor: TarLinuxRootfsExtraction = extractor(),
                    token: LinuxInstallCancellation = LinuxInstallCancellation()) {
            archive.writeBytes(payload)
            val artifact = LinuxRootfsArtifact("test", "alpine", "test", "arm64-v8a", format,
                payload.size.toLong(), "0".repeat(64),
                listOf(RootfsMirror("test", URI("https://example.invalid/test"))), strip)
            // Hash verification belongs to the installer; this exercises extraction directly.
            extractor.extract(artifact, archive, stage, token)
        }
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("tar-rootfs-test-").toFile()
        try { block(Fixture(root)) } finally { root.deleteRecursively() }
    }

    private fun rejected(block: () -> Unit) {
        try { block(); fail("expected rejection") } catch (_: IllegalArgumentException) {
        } catch (_: IllegalStateException) { } catch (_: IOException) { }
    }

    fun testBothCompressionFormatsExtractFilesModesAndDeferredLinks() {
        for (format in RootfsArchive.entries) fixture { f ->
            f.extract(compressed(tar(
                Item("./", type = '5'),
                Item("./bin/", type = '5'),
                Item("bin/sh", type = '2', target = "/usr/bin/tool"),
                Item("chain-a", type = '1', target = "chain-b"),
                Item("chain-b", type = '1', target = "usr/bin/tool"),
                Item("usr/bin/tool", "inert executable", mode = 493),
                Item("etc/config", "plain text", mode = 420)
            ), format), format)
            assertEquals("inert executable", f.ops.readText(File(f.stage, "bin/sh").toPath()))
            assertFalse(f.ops.readSymbolicLink(File(f.stage, "bin/sh").toPath()).isAbsolute)
            assertTrue(Files.isSameFile(File(f.stage, "chain-a").toPath(), File(f.stage, "usr/bin/tool").toPath()))
            assertTrue(f.ops.permissions(File(f.stage, "usr/bin/tool").toPath())
                .contains(PosixFilePermission.OWNER_EXECUTE))
            assertFalse(f.ops.permissions(File(f.stage, "etc/config").toPath())
                .contains(PosixFilePermission.OWNER_EXECUTE))
        }
    }

    fun testContainedDanglingAndCurrentDirectorySymlinksStayRelative() = fixture { f ->
        f.extract(compressed(tar(
            Item("root-link", type = '2', target = "/"),
            Item("dangling", type = '2', target = "/proc/not-mounted"),
            Item("dir/relative", type = '2', target = "../missing")
        ), RootfsArchive.TAR_GZIP))
        try {
            assertEquals(f.stage.toPath().fileSystem.getPath("."), f.ops.readSymbolicLink(File(f.stage, "root-link").toPath()))
            assertEquals(f.stage.toPath().fileSystem.getPath("proc/not-mounted"), f.ops.readSymbolicLink(File(f.stage, "dangling").toPath()))
            assertEquals(f.stage.toPath().fileSystem.getPath("../missing"), f.ops.readSymbolicLink(File(f.stage, "dir/relative").toPath()))
        } finally {
            // Do not let recursive fixture cleanup traverse this intentional directory cycle.
            f.ops.delete(File(f.stage, "root-link").toPath())
        }
    }

    fun testStripOneAlsoStripsHardlinkTargetsButNotGuestAbsoluteSymlinkTargets() = fixture { f ->
        f.extract(compressed(tar(
            Item("root/", type = '5'), Item("root/usr/bin/tool", "fixture"),
            Item("root/hard", type = '1', target = "root/usr/bin/tool"),
            Item("root/bin", type = '2', target = "/usr/bin")
        ), RootfsArchive.TAR_XZ), RootfsArchive.TAR_XZ, strip = 1)
        assertFalse(File(f.stage, "root").exists())
        assertEquals("fixture", f.ops.readText(File(f.stage, "bin/tool").toPath()))
        assertTrue(Files.isSameFile(File(f.stage, "hard").toPath(), File(f.stage, "usr/bin/tool").toPath()))
    }

    fun testRejectsUnsafeNamesAndNormalizedDuplicates() {
        val unsafe = listOf("../outside", "a/../outside", "/absolute", "C:/absolute", "a\\b",
            ".daiyu-rootfs-install", ".daiyu-rootfs-install/child", "x/".repeat(129) + "file")
        for (name in unsafe) fixture { f ->
            val rawName = name.toByteArray(Charsets.UTF_8)
            val archive = if (rawName.size <= 100) tar(Item("placeholder", "bad")).also {
                it.fill(0.toByte(), 0, 100)
                rawName.copyInto(it)
                fixHeaderChecksum(it)
                assertTrue(rawName.contentEquals(it.copyOfRange(0, rawName.size)))
            } else tar(Item(name, "bad"))
            val payload = compressed(archive, RootfsArchive.TAR_GZIP)
            rejected { f.extract(payload) }
            assertFalse(File(f.root, "outside").exists())
            assertFalse(File(f.stage, "a/b").exists())
        }
        fixture { f ->
            rejected { f.extract(compressed(tar(Item("a//b", "one"), Item("./a/b", "two")), RootfsArchive.TAR_GZIP)) }
        }
        fixture { f ->
            rejected { f.extract(compressed(tar(Item("one/a", "one"), Item("two/a", "two")), RootfsArchive.TAR_GZIP), strip = 1) }
        }
    }

    fun testRejectsEmbeddedNulInTarNameRatherThanSilentlyTruncatingIt() = fixture { f ->
        val bytes = tar(Item("ok", "data"))
        bytes[2] = 0
        bytes[3] = 'x'.code.toByte()
        fixHeaderChecksum(bytes)
        rejected { f.extract(compressed(bytes, RootfsArchive.TAR_GZIP)) }
        assertFalse(File(f.stage, "ok").exists())
    }

    fun testRejectsEmptyOrNonemptyStagingSymlinkAndPopulatedStaging() {
        fixture { f ->
            val keep = File(f.stage, "keep").apply { writeText("untouched") }
            rejected { f.extract(compressed(tar(Item("file", "data")), RootfsArchive.TAR_GZIP)) }
            assertEquals("untouched", keep.readText())
        }
        fixture { f ->
            f.stage.delete()
            f.ops.createSymbolicLink(f.stage.toPath(), f.root.toPath())
            try {
                rejected { f.extract(compressed(tar(Item("file", "data")), RootfsArchive.TAR_GZIP)) }
                assertFalse(File(f.root, "file").exists())
            } finally { f.ops.delete(f.stage.toPath()) }
        }
        fixture { f ->
            f.stage.delete()
            rejected { f.extract(compressed(tar(Item("file", "data")), RootfsArchive.TAR_GZIP)) }
        }
    }

    fun testRejectsLinkAncestorsInEitherOrderAndEscapingTargets() {
        val cases = listOf(
            arrayOf(Item("a", type = '2', target = "inside"), Item("a/file", "bad")),
            arrayOf(Item("a/file", "bad"), Item("a", type = '2', target = "inside")),
            arrayOf(Item("escape", type = '2', target = "../outside")),
            arrayOf(Item("escape", type = '2', target = "/../../outside")),
            arrayOf(Item("hard", type = '1', target = "../outside")),
            arrayOf(Item("hard", type = '1', target = "/outside")),
            arrayOf(Item("dir/", type = '5'), Item("hard", type = '1', target = "dir")),
            arrayOf(Item("real", "data"), Item("sym", type = '2', target = "real"), Item("hard", type = '1', target = "sym")),
            arrayOf(Item("a", type = '1', target = "b"), Item("b", type = '1', target = "a")),
            arrayOf(Item("a", type = '1', target = "missing"))
        )
        for (items in cases) fixture { f ->
            rejected { f.extract(compressed(tar(*items), RootfsArchive.TAR_GZIP)) }
            assertFalse(File(f.root, "outside").exists())
        }
    }

    fun testSkipsDeviceAndFifoWithoutCreatingPlaceholdersInEitherFormat() {
        for (format in RootfsArchive.entries) for (strip in listOf(0, 1)) fixture { f ->
            val prefix = if (strip == 0) "" else "debian/"
            val items = listOfNotNull(
                if (strip == 1) Item(prefix, type = '5') else null,
                Item("${prefix}dev/null", type = '3'),
                Item("${prefix}dev/block", type = '4'),
                Item("${prefix}run/pipe", type = '6'),
                Item("${prefix}usr/bin/tool", "inert executable"),
                Item("${prefix}bin/sh", type = '2', target = "/usr/bin/tool")
            )
            f.extract(compressed(tar(*items.toTypedArray()), format), format, strip)
            assertEquals("inert executable", File(f.stage, "bin/sh").readText())
            for (name in listOf("dev/null", "dev/block", "run/pipe", "dev", "run")) {
                assertFalse(Files.exists(File(f.stage, name).toPath(), NOFOLLOW_LINKS))
            }
        }
    }

    fun testRejectsUnknownAndContiguousTypesRatherThanTreatingThemAsFiles() {
        for (type in listOf('7', 'A')) fixture { f ->
            rejected { f.extract(compressed(tar(Item("special", type = type)), RootfsArchive.TAR_GZIP)) }
            assertFalse(Files.exists(File(f.stage, "special").toPath(), NOFOLLOW_LINKS))
        }
    }

    fun testSkippedSpecialEntriesStillValidateNamesAndReservedPaths() {
        for (name in listOf("../outside", "/outside", "C:/outside", "dev\\node",
            ".daiyu-rootfs-install", ".daiyu-rootfs-install/node")) fixture { f ->
            val bytes = tar(Item("placeholder", type = '3'))
            bytes.fill(0.toByte(), 0, 100)
            name.toByteArray().copyInto(bytes)
            fixHeaderChecksum(bytes)
            rejected { f.extract(compressed(bytes, RootfsArchive.TAR_GZIP)) }
            assertFalse(File(f.root, "outside").exists())
        }
        fixture { f ->
            rejected { f.extract(compressed(tar(Item("root", type = '3')), RootfsArchive.TAR_XZ),
                RootfsArchive.TAR_XZ, strip = 1) }
        }
    }

    fun testSkippedSpecialEntriesCannotCollideOrBecomeAncestorsOrHardlinkSources() {
        val cases = listOf(
            arrayOf(Item("node", type = '3'), Item("./node", "file")),
            arrayOf(Item("node", "file"), Item("node", type = '4')),
            arrayOf(Item("node", type = '6'), Item("node/child", "file")),
            arrayOf(Item("node/child", "file"), Item("node", type = '3')),
            arrayOf(Item("node/child", type = '3'), Item("node", type = '2', target = "inside")),
            arrayOf(Item("node/child", type = '4'), Item("node", "file")),
            arrayOf(Item("node/child", type = '6'), Item("node", type = '3')),
            arrayOf(Item("node", "file"), Item("node/child", type = '3')),
            arrayOf(Item("node", type = '2', target = "inside"), Item("node/child", type = '3')),
            arrayOf(Item("node", type = '3'), Item("hard", type = '1', target = "node"))
        )
        for (items in cases) fixture { f ->
            rejected { f.extract(compressed(tar(*items), RootfsArchive.TAR_GZIP)) }
            assertFalse(Files.exists(File(f.stage, "hard").toPath(), NOFOLLOW_LINKS))
        }
    }

    fun testSkippedSpecialEntriesStillConsumeEntryQuotaAndRejectPayloads() {
        fixture { f ->
            rejected { f.extract(compressed(tar(Item("one", type = '3'), Item("two", type = '6')),
                RootfsArchive.TAR_GZIP), extractor = f.extractor(maxEntries = 1)) }
        }
        for (type in listOf('3', '4', '6')) fixture { f ->
            val bytes = tar(Item("special", type = type))
            putOctal(bytes, 124, 12, 1)
            fixHeaderChecksum(bytes)
            rejected { f.extract(compressed(bytes, RootfsArchive.TAR_GZIP)) }
            assertFalse(Files.exists(File(f.stage, "special").toPath(), NOFOLLOW_LINKS))
        }
    }

    fun testStripsPrivilegedModeBitsAndRetainsOrdinaryExecutableBits() = fixture { f ->
        f.extract(compressed(tar(Item("tool", "inert", mode = 4095)), RootfsArchive.TAR_GZIP))
        val mode = f.ops.mode(File(f.stage, "tool").toPath())
        assertEquals(511, mode)
    }

    fun testByteEntryAndMetadataQuotasAndTruncation() {
        fixture { f ->
            rejected { f.extract(compressed(tar(Item("big", "x".repeat(8192))), RootfsArchive.TAR_GZIP),
                extractor = f.extractor(maxExpandedBytes = 1024)) }
        }
        fixture { f ->
            rejected { f.extract(compressed(tar(Item("one"), Item("two")), RootfsArchive.TAR_GZIP),
                extractor = f.extractor(maxEntries = 1)) }
        }
        // A GNU metadata header is counted even though Commons hides it from nextTarEntry.
        fixture { f ->
            val bytes = tar(Item("file"))
            bytes[156] = 'L'.code.toByte()
            putOctal(bytes, 124, 12, 2L * 1024 * 1024)
            fixHeaderChecksum(bytes)
            rejected { f.extract(compressed(bytes, RootfsArchive.TAR_GZIP)) }
        }
        for (format in RootfsArchive.entries) {
            fixture { f ->
                val bytes = compressed(tar(Item("dev/null", type = '3'), Item("file", "data")), format)
                rejected { f.extract(bytes.copyOf(bytes.size - 6), format) }
            }
            fixture { f ->
                val bytes = tar(Item("file", "data"))
                rejected { f.extract(compressed(bytes.copyOf(514), format), format) }
            }
            fixture { f ->
                rejected { f.extract(compressed(tar(Item("file", "data")).copyOf(1024), format), format) }
            }
        }
    }

    fun testXzDecoderRejectsDictionaryAbove128MiBWithoutAllocatingIt() = fixture { f ->
        val bytes = compressed(tar(Item("file", "data")), RootfsArchive.TAR_XZ)
        val headerSize = (bytes[12].toInt() + 1) * 4
        assertEquals(0x21, bytes[14].toInt() and 255) // LZMA2 filter
        assertEquals(1, bytes[15].toInt())
        bytes[16] = 0x22 // LZMA2 dictionary property: 512 MiB
        val crc = CRC32().apply { update(bytes, 12, headerSize - 4) }.value
        for (i in 0..3) bytes[12 + headerSize - 4 + i] = (crc ushr (8 * i)).toByte()
        try {
            f.extract(bytes, RootfsArchive.TAR_XZ)
            fail("expected XZ memory limit")
        } catch (_: org.tukaani.xz.MemoryLimitException) { }
    }

    fun testExplicitPreCancellationMakesNoStagingChanges() = fixture { f ->
        val token = LinuxInstallCancellation().apply { cancel() }
        try {
            f.extract(compressed(tar(Item("file", "data")), RootfsArchive.TAR_GZIP), token = token)
            fail("expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(0, f.stage.list()!!.size)
    }

    private fun fixHeaderChecksum(bytes: ByteArray) {
        for (i in 148..155) bytes[i] = 32
        val sum = (0 until 512).sumOf { bytes[it].toInt() and 255 }
        val value = sum.toString(8).padStart(6, '0').toByteArray()
        value.copyInto(bytes, 148)
        bytes[154] = 0
        bytes[155] = 32
    }

    private fun putOctal(bytes: ByteArray, offset: Int, length: Int, value: Long) {
        value.toString(8).padStart(length - 1, '0').toByteArray().copyInto(bytes, offset)
        bytes[offset + length - 1] = 0
    }
}
