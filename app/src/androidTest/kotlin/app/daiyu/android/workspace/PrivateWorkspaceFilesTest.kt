package app.daiyu.android.workspace

import android.test.InstrumentationTestCase
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

@Suppress("DEPRECATION")
class PrivateWorkspaceFilesTest : InstrumentationTestCase() {
    private fun fixture(test: (File, PrivateWorkspaceFiles) -> Unit) {
        val files = File(instrumentation.targetContext.cacheDir, "private-files-${UUID.randomUUID()}").apply { mkdir() }
        try { test(files, PrivateWorkspaceFiles(PrivateWorkspacePaths(files))) }
        finally { files.deleteRecursively() }
    }
    fun testImportExportAndDeleteUsePrivateRootAndKeepSource() = fixture { files, access ->
        val root = access.list().directory
        val bytes = "真实私有文件".toByteArray()
        val name = access.importFile(root, "original.txt", { ByteArrayInputStream(bytes) })
        val entry = access.list().entries.single()
        assertEquals("original.txt", name)
        assertEquals(name, entry.name)
        assertTrue(File(files, "workspace/$name").isFile)
        val output = ByteArrayOutputStream()
        access.exportFile(entry, { output })
        assertTrue(bytes.contentEquals(output.toByteArray()))
        access.delete(entry)
        assertTrue(access.list().entries.isEmpty())
    }
    fun testImportKeepsPartialBytesHiddenUntilCompleted() = fixture { files, access ->
        val root = access.list().directory
        val workspace = File(files, "workspace")
        var observed = false
        val input = object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (pos > 0) {
                    observed = true
                    val staging = workspace.listFiles()!!.single()
                    assertTrue(staging.name.startsWith(".workspace-import-"))
                    assertEquals(3L, staging.length())
                    assertTrue(access.list().entries.isEmpty())
                }
                return super.read(buffer, offset, length)
            }
        }
        val name = access.importFile(root, "complete.bin", { input })
        assertTrue(observed)
        assertEquals(setOf(name), workspace.list()!!.toSet())
        assertTrue(byteArrayOf(1, 2, 3).contentEquals(File(workspace, name).readBytes()))
    }
    fun testImportCollisionPreservesExistingTargetAndCleansStaging() = fixture { files, access ->
        val root = access.list().directory
        val workspace = File(files, "workspace")
        val existing = File(workspace, "collision.bin")
        val name = access.importFile(root, "collision.bin", {
            existing.writeText("keep")
            ByteArrayInputStream(byteArrayOf(1, 2, 3))
        })
        assertEquals("collision (1).bin", name)
        assertEquals("keep", existing.readText())
        assertTrue(byteArrayOf(1, 2, 3).contentEquals(File(workspace, name).readBytes()))
        assertEquals(setOf("collision.bin", name), workspace.list()!!.toSet())
    }
    fun testChineseZipImportPreservesOriginalNameAndNumbersCopiesBeforeExtension() = fixture { files, access ->
        val root = access.list().directory
        val original = "代鱼-干净源码-main-a1fdf36b-20261115.zip"
        val names = (0..2).map { index ->
            access.importFile(root, original, { ByteArrayInputStream(byteArrayOf(index.toByte())) })
        }
        assertEquals(listOf(original, original.removeSuffix(".zip") + " (1).zip",
            original.removeSuffix(".zip") + " (2).zip"), names)
        names.forEachIndexed { index, name ->
            assertTrue(byteArrayOf(index.toByte()).contentEquals(File(files, "workspace/$name").readBytes()))
        }
        assertEquals(names.toSet(), File(files, "workspace").list()!!.toSet())
    }
    fun testImportSkipsExistingDirectoryAndSymlinkWithoutFollowingOrReplacing() = fixture { files, access ->
        val root = access.list().directory
        val workspace = File(files, "workspace")
        val directory = File(workspace, "copy.txt").apply { mkdir() }
        File(directory, "keep").writeText("directory")
        val outside = File(files, "outside.txt").apply { writeText("outside") }
        val link = File(workspace, "copy (1).txt")
        java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
        val name = access.importFile(root, "copy.txt", { ByteArrayInputStream(byteArrayOf(7)) })
        assertEquals("copy (2).txt", name)
        assertEquals("directory", File(directory, "keep").readText())
        assertTrue(java.nio.file.Files.isSymbolicLink(link.toPath()))
        assertEquals("outside", outside.readText())
        assertTrue(byteArrayOf(7).contentEquals(File(workspace, name).readBytes()))
        assertEquals(setOf("copy.txt", "copy (1).txt", name), workspace.list()!!.toSet())
    }
    fun testImportPreservesLegitimateUuidPrefixAndExtensionlessNames() = fixture { _, access ->
        val root = access.list().directory
        val original = "f62a6f00-bbee-411f-8a2f-5c420b4a6d06-原名.zip"
        assertEquals(original, access.importFile(root, original, { ByteArrayInputStream(byteArrayOf(1)) }))
        for (name in listOf("README", ".hidden")) {
            assertEquals(name, access.importFile(root, name, { ByteArrayInputStream(byteArrayOf(1)) }))
            assertEquals("$name (1)", access.importFile(root, name, { ByteArrayInputStream(byteArrayOf(2)) }))
        }
    }
    fun testConcurrentImportsKeepEveryCopyUnderDistinctReadableNames() = fixture { files, access ->
        val root = access.list().directory
        val ready = java.util.concurrent.CountDownLatch(4)
        val start = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val copies = (0..3).map { value ->
                pool.submit<Pair<String, Int>> {
                    ready.countDown()
                    check(start.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    access.importFile(root, "并发.中文", { ByteArrayInputStream(byteArrayOf(value.toByte())) }) to value
                }
            }
            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS))
            start.countDown()
            val results = copies.map { it.get(10, java.util.concurrent.TimeUnit.SECONDS) }
            assertEquals(setOf("并发.中文", "并发 (1).中文", "并发 (2).中文", "并发 (3).中文"),
                results.map { it.first }.toSet())
            results.forEach { (name, value) ->
                assertTrue(byteArrayOf(value.toByte()).contentEquals(File(files, "workspace/$name").readBytes()))
            }
            assertEquals(results.map { it.first }.toSet(), File(files, "workspace").list()!!.toSet())
        } finally { start.countDown(); pool.shutdownNow() }
    }
    fun testImportRejectsDotSegmentsAndHiddenStagingNamesBeforeOpeningSource() = fixture { files, access ->
        val root = access.list().directory
        for (name in listOf(".", "..", ".workspace-import-hidden.zip")) {
            var opened = false
            try {
                access.importFile(root, name, { opened = true; ByteArrayInputStream(byteArrayOf(1)) })
                fail("reserved name accepted: $name")
            } catch (failure: IllegalArgumentException) { assertEquals("workspace_invalid_name", failure.message) }
            assertFalse(opened)
        }
        assertTrue(File(files, "workspace").list()!!.isEmpty())
    }
    fun testInputCloseCancellationDoesNotPublishOrLeaveStaging() = fixture { files, access ->
        val root = access.list().directory
        val cancelled = AtomicBoolean()
        val input = object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
            override fun close() { cancelled.set(true); super.close() }
        }
        try { access.importFile(root, "cancel-on-close.bin", { input }, cancelled::get); fail("close cancellation ignored") }
        catch (_: InterruptedException) { }
        assertTrue(File(files, "workspace").list()!!.isEmpty())
    }
    fun testLongUnicodeSourceNameRespectsFilesystemByteLimit() = fixture { _, access ->
        val root = access.list().directory
        val original = "汉".repeat(73) + ".txt"
        assertEquals(original, access.importFile(root, original, { ByteArrayInputStream(byteArrayOf(1)) }))
        val tooLong = "汉😀".repeat(80) + ".zip"
        val first = access.importFile(root, tooLong, { ByteArrayInputStream(byteArrayOf(2)) })
        val second = access.importFile(root, tooLong, { ByteArrayInputStream(byteArrayOf(3)) })
        for (name in listOf(first, second)) {
            assertTrue(name.toByteArray(Charsets.UTF_8).size <= 255)
            assertTrue(name.endsWith(".zip"))
            assertFalse(name.contains('\uFFFD'))
        }
        assertTrue(second.endsWith(" (1).zip"))
        assertEquals(setOf(original, first, second), access.list().entries.map { it.name }.toSet())
    }
    fun testSymlinksCannotBeListedOrRedirectStaleDirectories() = fixture { files, access ->
        access.list()
        val workspace = File(files, "workspace")
        val outside = File(files, "outside").apply { mkdir() }
        File(outside, "keep.txt").writeText("keep")
        val child = File(workspace, "child").apply { mkdir() }
        val selected = access.list().entries.single()
        assertTrue(child.delete())
        java.nio.file.Files.createSymbolicLink(child.toPath(), outside.toPath())
        try {
            assertTrue(access.list().entries.isEmpty())
            try { access.importFile(selected, "escape.txt", { ByteArrayInputStream(byteArrayOf(1)) }); fail("stale directory accepted") }
            catch (_: Exception) { }
            assertEquals(listOf("keep.txt"), outside.list()!!.toList())
        } finally { java.nio.file.Files.delete(child.toPath()) }
    }
    fun testCancelledImportRemovesStagingAndDoesNotPublish() = fixture { files, access ->
        val cancel = AtomicBoolean()
        val root = access.list().directory
        val input = object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val count = super.read(buffer, offset, length); cancel.set(true); return count
            }
        }
        try { access.importFile(root, "cancel.txt", { input }, cancel::get); fail("cancel ignored") }
        catch (_: InterruptedException) { }
        assertTrue(access.list().entries.isEmpty())
        assertTrue(File(files, "workspace").list()!!.isEmpty())
    }
    fun testStaleFileExportDoesNotOpenDestinationAndRootCannotBeDeleted() = fixture { files, access ->
        val root = access.list().directory
        val name = access.importFile(root, "first.txt", { ByteArrayInputStream(byteArrayOf(1)) })
        val entry = access.list().entries.single()
        val source = File(files, "workspace/$name")
        source.writeText("different size")
        var opened = false
        try { access.exportFile(entry, { opened = true; ByteArrayOutputStream() }); fail("stale file exported") }
        catch (_: IllegalStateException) { }
        assertFalse(opened)
        try { access.delete(root); fail("root deleted") } catch (_: IllegalStateException) { }
        assertTrue(source.exists())
    }
    fun testParentNavigationAndNonEmptyDirectoryProtection() = fixture { files, access ->
        access.list()
        val child = File(files, "workspace/sub").apply { mkdir() }
        File(child, "keep.txt").writeText("keep")
        val selected = access.list().entries.single()
        assertEquals("sub", access.list(selected).directory.relative)
        assertEquals("", access.parent(selected).directory.relative)
        try { access.delete(selected); fail("nonempty directory deleted") } catch (_: Exception) { }
        assertEquals("keep", File(child, "keep.txt").readText())
    }
    fun testListingCapsCandidatesIncludingHiddenStaging() = fixture { files, access ->
        access.list()
        val workspace = File(files, "workspace")
        val limit = 100
        assertEquals(limit, PrivateWorkspaceFiles.MAX_ENTRIES)
        val expected = (0 until limit).map { "entry-$it.txt" }.toSet()
        expected.forEach { assertTrue(File(workspace, it).createNewFile()) }
        val exact = access.list()
        assertEquals(limit, exact.entries.size)
        assertEquals(expected, exact.entries.map { it.name }.toSet())
        assertFalse(exact.truncated)
        assertTrue(File(workspace, "one-more.txt").createNewFile())
        val over = access.list()
        assertEquals(limit, over.entries.size); assertTrue(over.truncated)
        workspace.listFiles()!!.forEach { assertTrue(it.delete()) }
        repeat(limit + 1) { assertTrue(File(workspace, ".workspace-import-test-$it").createNewFile()) }
        val hidden = access.list()
        assertTrue(hidden.entries.isEmpty()); assertTrue(hidden.truncated)
    }
    fun testImportBeyondOld256MiBLimitStreamsAndPublishes() = fixture { files, access ->
        val root = access.list().directory
        val cap = 256L * 1024 * 1024
        var supplied = 0L; var closed = false
        val input = object : java.io.InputStream() {
            override fun read(): Int {
                if (supplied == cap + 1) return -1
                supplied++; return 0
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                val remaining = cap + 1 - supplied
                if (remaining == 0L) return -1
                val count = minOf(length.toLong(), remaining).toInt()
                java.util.Arrays.fill(buffer, offset, offset + count, 0.toByte())
                supplied += count; return count
            }
            override fun close() { closed = true }
        }
        var progress = 0L
        val name = access.importFile(root, "large.bin", { input }, { false }) { value ->
            assertTrue(value >= progress); progress = value
        }
        assertEquals(cap + 1, supplied); assertTrue(closed)
        assertEquals(cap + 1, progress)
        assertEquals(cap + 1, File(files, "workspace/$name").length())
        assertEquals(listOf(name), File(files, "workspace").list()!!.toList())
    }
    fun testStreamFailuresCleanImportStagingAndPreserveExportSource() = fixture { files, access ->
        val root = access.list().directory
        val workspace = File(files, "workspace")
        val inputFailure = java.io.IOException("injected input failure")
        var firstByteRead = false; var inputClosed = false
        val input = object : java.io.InputStream() {
            override fun read(): Int {
                if (firstByteRead) throw inputFailure
                firstByteRead = true; return 7
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                buffer[offset] = read().toByte(); return 1
            }
            override fun close() { inputClosed = true }
        }
        try { access.importFile(root, "broken.bin", { input }); fail("input failure ignored") }
        catch (error: java.io.IOException) { assertSame(inputFailure, error) }
        assertTrue(firstByteRead); assertTrue(inputClosed); assertTrue(workspace.list()!!.isEmpty())
        val bytes = byteArrayOf(1, 2, 3)
        val name = access.importFile(root, "keep.bin", { ByteArrayInputStream(bytes) })
        val entry = access.list().entries.single()
        val outputFailure = java.io.IOException("injected output failure")
        var outputAttempted = false; var outputClosed = false
        val output = object : java.io.OutputStream() {
            override fun write(value: Int) { outputAttempted = true; throw outputFailure }
            override fun close() { outputClosed = true }
        }
        try { access.exportFile(entry, { output }); fail("output failure ignored") }
        catch (error: java.io.IOException) { assertSame(outputFailure, error) }
        assertTrue(outputAttempted); assertTrue(outputClosed)
        assertTrue(bytes.contentEquals(File(workspace, name).readBytes()))
        assertEquals(setOf(name), workspace.list()!!.toSet())
    }
    fun testReadOnlyRootfsWalkKeepsOpenedDirectoryAfterRenameAndRejectsStaleRealReplacement() = fixture { files, _ ->
        val root = File(files.canonicalFile, "linux-installations/env/rootfs").apply { mkdirs() }
        File(root, "original").writeText("keep")
        val access = PrivateWorkspaceFiles(PrivateWorkspacePaths.existingRoot(files, root))
        val moved = File(root.parentFile, "opened-rootfs")
        var checks = 0
        val listing = access.list(cancelled = {
            // Two cancellation checks precede the walk; the third observes the pinned FD.
            if (++checks == 3) {
                assertTrue(root.renameTo(moved)); assertTrue(root.mkdir())
                File(root, "replacement").writeText("not-the-open-directory")
            }
            false
        })
        assertTrue(checks >= 3)
        assertEquals(listOf("original"), listing.entries.map { it.name })
        assertEquals("keep", File(moved, "original").readText())
        try { access.list(listing.directory); fail("stale root accepted") }
        catch (failure: IllegalStateException) { assertEquals("workspace_entry_changed", failure.message) }
        assertEquals(listOf("replacement"), access.list().entries.map { it.name })
    }

    fun testReadOnlyRootfsAndIntermediateSymlinksAreRejectedBeforeListing() = fixture { files, _ ->
        val root = File(files.canonicalFile, "linux-installations/env/rootfs").apply { mkdirs() }
        val outside = File(files.canonicalFile, "outside").apply { mkdir() }
        File(outside, "secret").writeText("untouched")
        val access = PrivateWorkspaceFiles(PrivateWorkspacePaths.existingRoot(files, root))
        val movedRoot = File(root.parentFile, "saved-rootfs")
        assertTrue(root.renameTo(movedRoot))
        java.nio.file.Files.createSymbolicLink(root.toPath(), outside.toPath())
        try {
            try { access.list(); fail("linked root accepted") }
            catch (failure: IllegalStateException) { assertEquals("workspace_root_changed", failure.message) }
        } finally { java.nio.file.Files.delete(root.toPath()); assertTrue(movedRoot.renameTo(root)) }
        val parent = root.parentFile
        val movedParent = File(parent.parentFile, "saved-env")
        assertTrue(parent.renameTo(movedParent))
        java.nio.file.Files.createSymbolicLink(parent.toPath(), outside.toPath())
        try {
            try { access.list(); fail("linked ancestor accepted") }
            catch (failure: IllegalStateException) { assertEquals("workspace_root_changed", failure.message) }
        } finally { java.nio.file.Files.delete(parent.toPath()); assertTrue(movedParent.renameTo(parent)) }
        assertEquals("untouched", File(outside, "secret").readText())
    }

    fun testRootfsReadOnlyBrowserPinsNestedRootAndNeverFollowsLinksOrAllowsWrites() = fixture { files, _ ->
        val root = File(files.canonicalFile, "linux-installations/env/generation/rootfs").apply { mkdirs() }
        val directory = File(root, "etc").apply { mkdir() }
        File(directory, "config").writeText("keep")
        val outside = File(files, "outside").apply { mkdir() }
        File(outside, "secret").writeText("outside")
        val link = File(root, "linked")
        java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
        try {
            val access = PrivateWorkspaceFiles(PrivateWorkspacePaths.existingRoot(files, root))
            val listing = access.list()
            val selected = listing.entries.single { it.name == "etc" }
            val nested = access.list(selected)
            assertEquals("etc", nested.directory.relative)
            val file = nested.entries.single()
            var exported = false
            try { access.exportFile(file, { exported = true; ByteArrayOutputStream() }); fail("Rootfs export allowed") }
            catch (failure: IllegalStateException) { assertEquals("workspace_read_only", failure.message) }
            assertFalse(exported)
            assertEquals("", access.parent(selected).directory.relative)
            val symbolic = listing.entries.single { it.name == "linked" }
            assertTrue(symbolic.symbolicLink); assertFalse(symbolic.directory)
            try { access.list(symbolic); fail("followed link") } catch (_: IllegalStateException) { }
            var opened = false
            try { access.importFile(listing.directory, "new", { opened = true; ByteArrayInputStream(byteArrayOf(1)) }); fail("Rootfs import allowed") }
            catch (failure: IllegalStateException) { assertEquals("workspace_read_only", failure.message) }
            assertFalse(opened)
            try { access.delete(selected); fail("Rootfs delete allowed") }
            catch (failure: IllegalStateException) { assertEquals("workspace_read_only", failure.message) }
            val renamed = File(root, "old-etc")
            assertTrue(directory.renameTo(renamed))
            java.nio.file.Files.createSymbolicLink(directory.toPath(), outside.toPath())
            try { access.list(selected); fail("stale folder followed") } catch (_: Exception) { }
            finally { java.nio.file.Files.delete(directory.toPath()) }
            assertEquals("outside", File(outside, "secret").readText())
            assertEquals("keep", File(renamed, "config").readText())
        } finally { java.nio.file.Files.delete(link.toPath()) }
    }

    fun testUnknownSpaceAndProgressCancellationRetainAtomicPublication() = fixture { files, _ ->
        val access = PrivateWorkspaceFiles(PrivateWorkspacePaths(files), availableBytes = { 0L })
        val root = access.list().directory
        val cancel = AtomicBoolean(false)
        var copied = 0L
        try {
            access.importFile(root, "cancel.bin", { ByteArray(128 * 1024).inputStream() }, cancel::get) {
                copied = it; if (it >= 64 * 1024) cancel.set(true)
            }
            fail("cancel ignored")
        } catch (_: InterruptedException) { }
        assertEquals(64 * 1024L, copied)
        assertTrue(File(files, "workspace").list()!!.isEmpty())
        val name = access.importFile(access.list().directory, "ok.txt", { "ok".byteInputStream() })
        assertEquals("ok", File(files, "workspace/$name").readText())
    }
    fun testPositiveInsufficientSpaceRejectsAndRemovesStaging() = fixture { files, _ ->
        val access = PrivateWorkspaceFiles(PrivateWorkspacePaths(files), availableBytes = { 1L })
        try {
            access.importFile(access.list().directory, "full.bin", { ByteArray(32).inputStream() })
            fail("space rejection ignored")
        } catch (failure: java.io.IOException) { assertEquals("workspace_storage_full", failure.message) }
        assertTrue(File(files, "workspace").list()!!.isEmpty())
    }
    fun testRenameFileDirectoryCollisionAndInvalidName() = fixture { files, access ->
        access.list()
        val workspace = File(files, "workspace")
        File(workspace, "one.txt").writeText("one")
        File(workspace, "two.txt").writeText("two")
        val entry = access.list().entries.single { it.name == "one.txt" }
        try { access.rename(entry, "two.txt"); fail("existing target replaced") } catch (_: Exception) { }
        assertEquals("one", File(workspace, "one.txt").readText())
        assertEquals("two", File(workspace, "two.txt").readText())
        try { access.rename(entry, "../escape"); fail("invalid name accepted") } catch (_: IllegalArgumentException) { }
        access.rename(entry, "文件 一.txt")
        assertEquals("one", File(workspace, "文件 一.txt").readText())
        val child = File(workspace, "folder").apply { mkdir() }
        File(child, "keep").writeText("keep")
        access.rename(access.list().entries.single { it.name == "folder" }, "renamed")
        assertEquals("keep", File(workspace, "renamed/keep").readText())
        try { access.rename(access.list().directory, "changed"); fail("root renamed") } catch (_: IllegalStateException) { }
    }
    fun testTreeDeletionNeverFollowsLinksAndRootRemainsProtected() = fixture { files, access ->
        access.list()
        val workspace = File(files, "workspace")
        val outside = File(files, "outside").apply { mkdir() }
        File(outside, "keep").writeText("keep")
        val child = File(workspace, "folder/nested").apply { mkdirs() }
        File(child, "file").writeText("remove")
        java.nio.file.Files.createSymbolicLink(File(child, "outside").toPath(), outside.toPath())
        access.deleteTree(access.list().entries.single())
        assertTrue(workspace.list()!!.isEmpty())
        assertEquals("keep", File(outside, "keep").readText())
        try { access.deleteTree(access.list().directory); fail("root deleted") } catch (_: IllegalStateException) { }
    }
    fun testRootfsSystemProtectionIsEnforcedByBothOperations() = fixture { files, _ ->
        val root = File(files, "rootfs").apply { mkdir() }
        File(root, "etc").mkdir()
        File(root, "etc/keep").writeText("keep")
        File(root, "project").mkdir()
        val access = PrivateWorkspaceFiles(PrivateWorkspacePaths.editableRootfs(files, root))
        val system = access.list().entries.single { it.name == "etc" }
        assertFalse(access.canModify(system))
        try { access.rename(system, "renamed"); fail("system renamed") } catch (_: IllegalStateException) { }
        try { access.deleteTree(system); fail("system deleted") } catch (_: IllegalStateException) { }
        val project = access.list().entries.single { it.name == "project" }
        try { access.rename(project, "usr"); fail("system destination accepted") } catch (_: IllegalStateException) { }
        access.rename(project, "project2")
        access.deleteTree(access.list().entries.single { it.name == "project2" })
        assertEquals("keep", File(root, "etc/keep").readText())
        val readonly = PrivateWorkspaceFiles(PrivateWorkspacePaths.existingRoot(files, root))
        try { readonly.rename(readonly.list().entries.single(), "no"); fail("readonly renamed") } catch (_: IllegalStateException) { }
    }
    fun testDirectoryAtAndCancelledMutationKeepRootBoundary() = fixture { files, access ->
        access.list()
        val child = File(files, "workspace/child").apply { mkdir() }
        File(child, "keep").writeText("keep")
        val directory = access.directoryAt("child")
        val entry = access.list(directory).entries.single()
        try { access.rename(entry, "other") { true }; fail("cancelled rename executed") } catch (_: InterruptedException) { }
        try { access.deleteTree(entry) { true }; fail("cancelled delete executed") } catch (_: InterruptedException) { }
        assertEquals("keep", File(child, "keep").readText())
        try { access.directoryAt("../escape"); fail("escaped") } catch (_: IllegalStateException) { }
        val stale = access.list().entries.single()
        child.renameTo(File(files, "moved"))
        child.mkdir()
        File(child, "keep").writeText("replacement")
        try { access.deleteTree(stale); fail("stale tree deleted") } catch (_: IllegalStateException) { }
        assertEquals("replacement", File(child, "keep").readText())
    }


    fun testCreateDirectoryUsesExactNameAndRejectsConflictsCancellationAndStaleParents() = fixture { files, access ->
        val root = access.list().directory
        val name = " 项目 '🐟' "
        access.createDirectory(root, name)
        val created = access.list().entries.single()
        assertEquals(name, created.name); assertTrue(created.directory)
        val directory = access.list(created).directory
        access.createDirectory(directory, "nested")
        assertTrue(File(files, "workspace/$name/nested").isDirectory)
        try { access.createDirectory(root, name); fail("directory replaced") }
        catch (failure: IllegalStateException) { assertEquals("workspace_directory_exists", failure.message) }
        File(files, "workspace/file").writeText("keep")
        try { access.createDirectory(root, "file"); fail("file replaced") }
        catch (failure: IllegalStateException) { assertEquals("workspace_directory_exists", failure.message) }
        assertEquals("keep", File(files, "workspace/file").readText())
        try { access.createDirectory(root, "cancelled") { true }; fail("cancel ignored") } catch (_: InterruptedException) { }
        assertFalse(File(files, "workspace/cancelled").exists())
        val actual = File(files, "workspace/$name")
        assertTrue(actual.renameTo(File(files, "workspace/moved")))
        assertTrue(actual.mkdir())
        try { access.createDirectory(directory, "stale"); fail("stale parent accepted") } catch (_: IllegalStateException) { }
        assertFalse(File(actual, "stale").exists())
    }

    fun testExplicitRootfsMkdirCapabilityKeepsEveryOtherWriteReadOnly() = fixture { files, _ ->
        val root = File(files, "rootfs").apply { mkdir() }
        val paths = PrivateWorkspacePaths.existingRoot(files, root)
        val readonly = PrivateWorkspaceFiles(paths)
        val entry = readonly.list().directory
        try { readonly.createDirectory(entry, "denied"); fail("default read-only mkdir allowed") }
        catch (failure: IllegalStateException) { assertEquals("workspace_read_only", failure.message) }
        val creator = PrivateWorkspaceFiles(paths, allowDirectoryCreation = true)
        creator.createDirectory(entry, "new")
        assertTrue(File(root, "new").isDirectory)
        try { creator.importFile(entry, "file", { ByteArrayInputStream(byteArrayOf(1)) }); fail("import allowed") }
        catch (failure: IllegalStateException) { assertEquals("workspace_read_only", failure.message) }
        val child = creator.list().entries.single()
        try { creator.delete(child); fail("delete allowed") }
        catch (failure: IllegalStateException) { assertEquals("workspace_read_only", failure.message) }
        assertTrue(File(root, "new").isDirectory)
    }
    fun testRecoveryRunsBeforeDeleteOnceAndNeverReplaysFailedDeletion() = fixture { files, _ ->
        val workspace = File(files, "workspace").apply { mkdir() }
        val folder = File(workspace, "backup").apply { mkdir() }
        File(folder, "file").writeText("keep")
        val calls = mutableListOf<Pair<String, Boolean>>()
        val recovery = PrivateWorkspaceRecovery { relative, recursive, _, cancelled ->
            assertFalse(cancelled())
            calls += relative to recursive
        }
        val access = PrivateWorkspaceFiles(PrivateWorkspacePaths(files), recovery = recovery)
        val entry = access.list().entries.single()
        calls.clear()
        access.deleteTree(entry)
        assertEquals(1, calls.count { it == ("backup" to true) })
        assertFalse(folder.exists())
        assertTrue(workspace.isDirectory)
    }

    fun testRecoveryFailureAndCancellationNeverStartMutation() = fixture { files, _ ->
        val workspace = File(files, "workspace").apply { mkdir() }
        val target = File(workspace, "keep").apply { writeText("keep") }
        val entry = PrivateWorkspaceFiles(PrivateWorkspacePaths(files)).list().entries.single()
        var calls = 0
        val rejected = PrivateWorkspaceFiles(PrivateWorkspacePaths(files), recovery = PrivateWorkspaceRecovery { _, _, _, _ ->
            calls++
            error("workspace_recovery_failed")
        })
        try { rejected.deleteTree(entry); fail("failed recovery allowed deletion") }
        catch (failure: IllegalStateException) { assertEquals("workspace_recovery_failed", failure.message) }
        assertEquals(1, calls)
        assertEquals("keep", target.readText())
        try { rejected.deleteTree(entry) { true }; fail("cancelled operation allowed") }
        catch (_: InterruptedException) { }
        assertEquals(1, calls)
        assertEquals("keep", target.readText())
    }

    fun testRootfsAndStorageChooserNeverReceiveRecovery() = fixture { files, _ ->
        val rootfs = File(files, "rootfs").apply { mkdir() }
        File(rootfs, "keep").writeText("keep")
        var calls = 0
        val recovery = PrivateWorkspaceRecovery { _, _, _, _ -> calls++ }
        for (paths in listOf(PrivateWorkspacePaths.existingRoot(files, rootfs),
            PrivateWorkspacePaths.editableRootfs(files, rootfs), PrivateWorkspacePaths.storageRoot(files))) {
            PrivateWorkspaceFiles(paths, recovery = recovery).list()
        }
        assertEquals(0, calls)
        assertEquals("keep", File(rootfs, "keep").readText())
    }

    fun testRenameAndDeleteRecoverExactTargetBeforeParent() = fixture { files, _ ->
        val workspace = File(files, "workspace").apply { mkdir() }
        File(workspace, "old").writeText("bytes")
        val calls = mutableListOf<String>()
        val access = PrivateWorkspaceFiles(PrivateWorkspacePaths(files), recovery = PrivateWorkspaceRecovery { relative, _, expected, _ ->
            if (relative.isNotEmpty()) assertNotNull(expected)
            calls += relative
        })
        val first = access.list().entries.single()
        calls.clear()
        access.rename(first, "new")
        assertEquals("old", calls.first())
        val renamed = access.list().entries.single()
        calls.clear()
        access.delete(renamed)
        assertEquals("new", calls.first())
        assertFalse(File(workspace, "new").exists())
    }

    fun testProductionContextClientChecksMetadataBeforeOrdinaryFileOperations() {
        val context = instrumentation.targetContext
        val access = PrivateWorkspaceFiles(context)
        val name = "ownership-client-${UUID.randomUUID()}"
        val root = access.list().directory
        try {
            access.createDirectory(root, name)
            val folder = access.list().entries.single { it.name == name }
            val imported = access.importFile(folder, "bytes.txt", { ByteArrayInputStream("keep".toByteArray()) })
            val file = access.list(folder).entries.single { it.name == imported }
            val output = ByteArrayOutputStream()
            access.exportFile(file, { output })
            assertEquals("keep", output.toString("UTF-8"))
            access.rename(file, "renamed.txt")
            access.deleteTree(access.list().entries.single { it.name == name })
            assertFalse(File(context.filesDir, "workspace/$name").exists())
        } finally {
            File(context.filesDir, "workspace/$name").deleteRecursively()
        }
    }

}
