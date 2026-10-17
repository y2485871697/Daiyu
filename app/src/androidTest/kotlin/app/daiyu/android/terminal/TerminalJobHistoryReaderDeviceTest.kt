package app.daiyu.android.terminal

import android.system.Os
import android.system.OsConstants
import android.test.InstrumentationTestCase
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real Android Os/PFD tests. No Registry, Process, shell, journal or execution authorization.
 * All replacements happen at explicit reader boundaries; there is no racing retry loop.
 */
@Suppress("DEPRECATION")
class TerminalJobHistoryReaderDeviceTest : InstrumentationTestCase() {
    private lateinit var temporary: File
    private lateinit var files: File
    private lateinit var root: File
    private val owner = "history-owner"
    private val session = UUID.randomUUID().toString()

    override fun setUp() {
        super.setUp()
        temporary = File(instrumentation.targetContext.cacheDir, "history-fd-${UUID.randomUUID()}")
        files = File(temporary, "files")
        root = File(files, "terminal-jobs")
        assertTrue(files.mkdirs())
    }

    override fun tearDown() {
        try { removeTree(temporary) } finally { super.tearDown() }
    }

    private fun removeTree(file: File) {
        if (Files.isSymbolicLink(file.toPath())) { Files.delete(file.toPath()); return }
        file.listFiles()?.forEach(::removeTree)
        Files.deleteIfExists(file.toPath())
    }

    private fun record(directory: File = root, id: String = UUID.randomUUID().toString(),
        backend: String = "android"): String {
        directory.mkdirs()
        File(directory, "$id.json").writeText(JSONObject().put("job_id", id).put("owner", owner)
            .put("scope", "original-scope").put("session_id", session).put("mode", "async")
            .put("backend", backend).put("state", "exited").put("exit_code", 0).put("pid", 1234).toString())
        return id
    }

    fun testRealDescriptorsReadBoundedUnknownEvidenceWithoutCreatingMissingHistory() {
        val reader = TerminalJobHistoryReader(files)
        assertTrue(reader.list(owner).records.isEmpty())
        assertFalse(root.exists())
        val id = record()
        val log = File(root, "$id.log").apply { writeText("original output") }
        val original = reader.list(owner).records.single()
        val page = reader.page(owner, original, 0, 4)
        assertTrue(page.getBoolean("ok"))
        assertEquals("orig", page.getString("stdout"))
        assertEquals(4L, page.getLong("next_offset"))
        assertTrue(page.getBoolean("has_more"))
        assertEquals("unknown_after_process_restart", page.getString("state"))
        assertEquals("unknown", page.getString("execution"))
        assertTrue(page.isNull("exit_code"))
        assertFalse(page.has("pid"))
        val outside = File(temporary, "secret").apply { writeText("outside") }
        assertTrue(log.delete())
        Files.createSymbolicLink(log.toPath(), outside.toPath())
        assertFalse(reader.page(owner, original).getBoolean("ok"))
    }

    fun testRootSymlinkInsertedAfterAnchorOpenIsRejected() {
        record()
        val parked = File(files, "parked")
        val outside = File(temporary, "outside").apply { mkdirs() }
        record(outside)
        val reader = TerminalJobHistoryReader(files)
        var invoked = false
        reader.afterFilesOpened = {
            invoked = true
            assertTrue(root.renameTo(parked))
            Files.createSymbolicLink(root.toPath(), outside.toPath())
        }
        try {
            assertEquals("job_history_unavailable", reader.list(owner).error)
            assertTrue(invoked)
        } finally {
            Files.deleteIfExists(root.toPath())
            assertTrue(parked.renameTo(root))
        }
    }

    fun testFilesAnchorReplacementCannotRerouteRootOpen() {
        val id = record()
        val outside = File(temporary, "outside-files").apply { mkdirs() }
        record(File(outside, "terminal-jobs"), id, "outside")
        val parked = File(temporary, "parked-files")
        val reader = TerminalJobHistoryReader(files)
        var swapped = false
        reader.afterFilesOpened = {
            assertTrue(files.renameTo(parked))
            Files.createSymbolicLink(files.toPath(), outside.toPath())
            swapped = true
        }
        reader.afterRootOpened = {
            Files.delete(files.toPath())
            assertTrue(parked.renameTo(files))
            swapped = false
        }
        try { assertEquals("android", reader.list(owner).records.single().backend) }
        finally {
            if (swapped) { Files.delete(files.toPath()); assertTrue(parked.renameTo(files)) }
        }
    }

    /** Restore pathname *after* the leaf was opened, exactly the pre/post-check bypass window. */
    private fun temporaryRootSwap(reader: TerminalJobHistoryReader, leaf: String, outside: File,
        action: () -> Unit) {
        val parked = File(files, "parked-root")
        var swaps = 0
        var restores = 0
        var swapped = false
        reader.beforeLeafOpened = { name ->
            if (name == leaf) {
                swaps++
                assertTrue(root.renameTo(parked))
                Files.createSymbolicLink(root.toPath(), outside.toPath())
                swapped = true
            }
        }
        reader.afterLeafOpened = { name ->
            if (name == leaf) {
                Files.delete(root.toPath())
                assertTrue(parked.renameTo(root))
                swapped = false
                restores++
            }
        }
        try {
            action()
            assertEquals(1, swaps)
            assertEquals(1, restores)
        } finally {
            reader.beforeLeafOpened = null
            reader.afterLeafOpened = null
            if (swapped) { Files.delete(root.toPath()); assertTrue(parked.renameTo(root)) }
        }
    }

    fun testTemporaryRootSymlinkNeverSuppliesMetadataOrLogEvenWhenRestoredBeforeRead() {
        val id = record()
        File(root, "$id.log").writeText("authorized bytes")
        val outside = File(temporary, "outside-root")
        record(outside, id, "outside")
        File(outside, "$id.log").writeText("outside secret")
        val reader = TerminalJobHistoryReader(files)
        temporaryRootSwap(reader, "$id.json", outside) {
            assertEquals("android", reader.list(owner).records.single().backend)
        }
        val original = reader.list(owner).records.single()
        temporaryRootSwap(reader, "$id.log", outside) {
            val page = reader.page(owner, original)
            assertTrue(page.getBoolean("ok"))
            assertEquals("authorized bytes", page.getString("stdout"))
        }
    }

    fun testReplacedLeafPathIsNotReopenedAfterDescriptorAdmission() {
        val id = record()
        File(root, "$id.log").writeText("authorized bytes")
        val reader = TerminalJobHistoryReader(files)
        var replaced = 0
        reader.afterLeafOpened = { name ->
            if (name == "$id.json") {
                assertTrue(File(root, name).renameTo(File(root, "old-json")))
                record(root, id, "outside")
                replaced++
            }
        }
        assertEquals("android", reader.list(owner).records.single().backend)
        assertEquals(1, replaced)
        reader.afterLeafOpened = null
        val original = reader.list(owner).records.single()
        reader.afterLeafOpened = { name ->
            if (name == "$id.log") {
                assertTrue(File(root, name).renameTo(File(root, "old-log")))
                File(root, name).writeText("replacement bytes")
                replaced++
            }
        }
        val page = reader.page(owner, original)
        assertTrue(page.getBoolean("ok"))
        assertEquals("authorized bytes", page.getString("stdout"))
        assertEquals(2, replaced)
    }

    /** Bound the regression even if a future implementation accidentally removes NONBLOCK.
     * A read/write nonblocking FIFO fd unblocks a regressed read-only open during cleanup.
     */
    private fun <T> promptly(fifo: File, action: () -> T): T {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "history-fifo-test").apply { isDaemon = true }
        }
        val result = executor.submit<T> { action() }
        try { return result.get(3, TimeUnit.SECONDS) }
        finally {
            try {
                if (!result.isDone) {
                    val unblock = Os.open(fifo.path, OsConstants.O_RDWR or OsConstants.O_NONBLOCK, 0)
                    try { try { result.get(2, TimeUnit.SECONDS) } catch (_: Exception) { } }
                    finally { Os.close(unblock) }
                }
            } finally { executor.shutdownNow() }
        }
    }

    fun testMetadataReplacedWithFifoBeforeOpenIsPromptlyIsolated() {
        val good = record()
        val bad = record()
        val fifo = File(root, "$bad.json")
        val reader = TerminalJobHistoryReader(files)
        var swaps = 0
        var admitted = 0
        reader.beforeLeafOpened = { name ->
            if (name == fifo.name) {
                assertTrue(fifo.delete())
                Os.mkfifo(fifo.path, 384) // 0600; no writer exists during the actual read.
                swaps++
            }
        }
        reader.afterLeafOpened = { name -> if (name == fifo.name) admitted++ }
        val scan = promptly(fifo) { reader.list(owner) }
        assertNull(scan.error)
        assertEquals(listOf(good), scan.records.map { it.jobId })
        assertEquals(1, scan.skipped)
        assertEquals(1, swaps)
        assertEquals(0, admitted)
    }

    fun testLogReplacedWithFifoBeforeOpenIsPromptlyRejected() {
        val id = record()
        val fifo = File(root, "$id.log").apply { writeText("old") }
        val reader = TerminalJobHistoryReader(files)
        val original = reader.list(owner).records.single()
        var swaps = 0
        reader.beforeLeafOpened = { name ->
            if (name == fifo.name) {
                assertTrue(fifo.delete())
                Os.mkfifo(fifo.path, 384)
                swaps++
            }
        }
        val page = promptly(fifo) { reader.page(owner, original) }
        assertFalse(page.getBoolean("ok"))
        assertEquals("job_history_unavailable", page.getString("error"))
        assertEquals(1, swaps)
    }

    private fun heldDescriptors(): Int = File("/proc/self/fd").listFiles()!!.count {
        try { Os.readlink(it.path).startsWith(temporary.canonicalPath + "/") }
        catch (_: Exception) { false }
    }

    fun testDescriptorOwnershipClosesOnSuccessAndEveryInjectedFailureBoundary() {
        record()
        val reader = TerminalJobHistoryReader(files)
        val initial = heldDescriptors()
        val original = reader.list(owner).records.single()
        assertEquals(initial, heldDescriptors())
        assertTrue(reader.page(owner, original).getBoolean("ok"))
        assertEquals(initial, heldDescriptors())
        reader.afterFilesOpened = { throw IOException("anchor boundary") }
        assertNotNull(reader.list(owner).error)
        assertEquals(initial, heldDescriptors())
        reader.afterFilesOpened = null
        reader.afterRootOpened = { throw IOException("root boundary") }
        assertNotNull(reader.list(owner).error)
        assertEquals(initial, heldDescriptors())
        reader.afterRootOpened = null
        reader.beforeLeafOpened = { throw IOException("before leaf") }
        assertEquals(1, reader.list(owner).skipped)
        assertEquals(initial, heldDescriptors())
        reader.beforeLeafOpened = null
        reader.afterLeafOpened = { throw IOException("after leaf") }
        assertEquals(1, reader.list(owner).skipped)
        assertEquals(initial, heldDescriptors())
        assertFalse(reader.page(owner, original).getBoolean("ok"))
        assertEquals(initial, heldDescriptors())
    }
}
