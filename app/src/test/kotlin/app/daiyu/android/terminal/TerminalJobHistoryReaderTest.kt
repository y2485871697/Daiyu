package app.daiyu.android.terminal

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.UUID

/** Format/paging fixtures only: explicitly inject JVM file I/O, never fake an Android descriptor.
 * Actual NOFOLLOW/NONBLOCK/fstat and path replacement coverage lives in the device test.
 * No Registry, Android runtime, Process, shell, journal or network.
 */
class TerminalJobHistoryReaderTest {
    private val owner = "conversation-1"
    private fun reader(files: File) = TerminalJobHistoryReader(files) { anchor ->
        val root = File(anchor, "terminal-jobs").toPath()
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) null else {
            check(Files.isDirectory(root, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            object : TerminalJobHistoryReader.Directory {
                override fun names() = Files.newDirectoryStream(root)
                override fun read(name: String, offset: Long, limit: Int, maxSize: Int): TerminalJobHistoryReader.Slice? {
                    val path = root.resolve(name)
                    if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return null
                    check(Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    return java.io.RandomAccessFile(path.toFile(), "r").use { input ->
                        val size = input.length()
                        require(size in 0..maxSize.toLong() && offset in 0..size)
                        val bytes = ByteArray(minOf(limit.toLong(), size - offset).toInt())
                        input.seek(offset)
                        input.readFully(bytes)
                        TerminalJobHistoryReader.Slice(bytes, size)
                    }
                }
                override fun close() = Unit
            }
        }
    }
    private fun fixture(test: (File, File) -> Unit) {
        val files = Files.createTempDirectory("terminal-history").toFile()
        try { test(files, File(files, "terminal-jobs")) } finally { files.deleteRecursively() }
    }
    private fun record(directory: File, id: String = UUID.randomUUID().toString(),
        owner: String = this.owner, mutate: (JSONObject) -> Unit = {}): String {
        directory.mkdirs()
        val value = JSONObject().put("job_id", id).put("owner", owner).put("scope", "original-run")
            .put("session_id", UUID.randomUUID().toString()).put("backend", "android").put("mode", "async")
            .put("state", "exited").put("exit_code", 0).put("pid", 4242).put("process_start", "old")
        mutate(value)
        File(directory, "$id.json").writeText(value.toString())
        return id
    }
    private fun snapshot(files: File): Map<String, Pair<Long, List<Byte>>> = files.walkTopDown()
        .filter { it.isFile }.associate { it.relativeTo(files).path to (it.lastModified() to it.readBytes().toList()) }

    @Test fun rebuildingReaderReadsOriginalRecordButNeverClaimsLiveStateOrWrites() = fixture { files, directory ->
        val id = record(directory)
        File(directory, "$id.log").writeText("original output")
        val before = snapshot(files)
        val original = reader(files).list(owner).records.single()
        val reopened = reader(files)
        assertEquals(original, reopened.list(owner).records.single())
        val page = reopened.page(owner, original)
        assertTrue(page.getBoolean("ok"))
        assertEquals("original output", page.getString("stdout"))
        assertEquals("unknown_after_process_restart", page.getString("state"))
        assertEquals("unknown", page.getString("execution"))
        assertTrue(page.isNull("exit_code"))
        assertFalse(page.has("pid")); assertFalse(page.has("process_start"))
        assertEquals(before, snapshot(files))
        assertEquals(setOf("terminal-jobs"), files.list()!!.toSet())
    }

    @Test fun absentHistoryDoesNotCreateDirectoryAndOwnerOrFilenameMismatchIsIsolated() = fixture { files, directory ->
        assertTrue(reader(files).list(owner).records.isEmpty())
        assertFalse(directory.exists())
        val good = record(directory)
        record(directory, owner = "different-conversation")
        record(directory) { it.put("owner", "../outside") }
        record(directory) { it.put("job_id", UUID.randomUUID().toString()) }
        record(directory) { it.put("scope", "../../outside") }
        record(directory) { it.put("session_id", "not-uuid") }
        File(directory, "not-a-uuid.json").writeText("{}")
        val scan = reader(files).list(owner)
        assertEquals(listOf(good), scan.records.map { it.jobId })
        assertEquals(5, scan.skipped)
        val original = scan.records.single()
        try { reader(files).page("different-conversation", original); fail() }
        catch (_: IllegalArgumentException) { }
        File(directory, "$good.json").writeText(JSONObject(File(directory, "$good.json").readText())
            .put("owner", "different-conversation").toString())
        assertFalse(reader(files).page(owner, original).getBoolean("ok"))
    }

    @Test fun changingOriginalScopeOrSessionBetweenPagesIsRejected() = fixture { files, directory ->
        val id = record(directory)
        val metadata = File(directory, "$id.json")
        val reader = reader(files)
        val original = reader.list(owner).records.single()
        metadata.writeText(JSONObject(metadata.readText()).put("session_id", UUID.randomUUID().toString()).toString())
        assertFalse(reader.page(owner, original).getBoolean("ok"))
        val changedSession = reader.list(owner).records.single()
        metadata.writeText(JSONObject(metadata.readText()).put("scope", "another-run").toString())
        assertFalse(reader.page(owner, changedSession).getBoolean("ok"))
    }

    @Test fun badMetadataIsBoundedAndDoesNotHideGoodEvidence() = fixture { files, directory ->
        val good = record(directory)
        File(directory, "${UUID.randomUUID()}.json").writeText("{")
        File(directory, "${UUID.randomUUID()}.json").writeText("x".repeat(TerminalJobHistoryReader.MAX_METADATA_BYTES + 1))
        val scan = reader(files).list(owner)
        assertNull(scan.error); assertEquals(2, scan.skipped)
        assertEquals(good, scan.records.single().jobId)
    }

    @Test fun pagesAreByteBoundedWithoutOverlapAndRejectInvalidBoundsOrOversizedLogs() = fixture { files, directory ->
        val id = record(directory)
        val bytes = ByteArray(70003) { (it % 251).toByte() }
        val log = File(directory, "$id.log").apply { writeBytes(bytes) }
        val reader = reader(files)
        val original = reader.list(owner).records.single()
        val collected = java.io.ByteArrayOutputStream()
        var offset = 0L
        do {
            val page = reader.page(owner, original, offset, 16384)
            assertTrue(page.getBoolean("ok")); assertEquals(offset, page.getLong("offset"))
            val chunk = Base64.getDecoder().decode(page.getString("stdout_base64"))
            assertTrue(chunk.size <= 16384)
            collected.write(chunk)
            assertEquals(offset + chunk.size, page.getLong("next_offset"))
            offset = page.getLong("next_offset")
        } while (page.getBoolean("has_more"))
        assertArrayEquals(bytes, collected.toByteArray())
        assertEquals(bytes.size.toLong(), offset)
        assertFalse(reader.page(owner, original, offset + 1).getBoolean("ok"))
        try { reader.page(owner, original, 0, 65537); fail() } catch (_: IllegalArgumentException) { }
        try { reader.page(owner, original, -1, 1); fail() } catch (_: IllegalArgumentException) { }
        log.writeBytes(ByteArray(TerminalJobHistoryReader.MAX_LOG_BYTES + 1))
        assertFalse(reader.page(owner, original).getBoolean("ok"))
    }

    @Test fun injectedFormatFixtureRejectsStaticSymlinks_notDescriptorSecurityCoverage() = fixture { files, directory ->
        val outside = Files.createTempDirectory("terminal-history-outside").toFile()
        try {
            val good = record(directory)
            val escaped = record(outside)
            Files.createSymbolicLink(File(directory, "$escaped.json").toPath(), File(outside, "$escaped.json").toPath())
            File(outside, "secret").writeText("must not read")
            Files.createSymbolicLink(File(directory, "$good.log").toPath(), File(outside, "secret").toPath())
            val reader = reader(files)
            val scan = reader.list(owner)
            assertEquals(1, scan.skipped); assertEquals(good, scan.records.single().jobId)
            assertFalse(reader.page(owner, scan.records.single()).getBoolean("ok"))
            // Remove the fixture's links before directory cleanup, never traverse the target.
            Files.delete(File(directory, "$escaped.json").toPath())
            Files.delete(File(directory, "$good.log").toPath())
            directory.deleteRecursively()
            Files.createSymbolicLink(directory.toPath(), outside.toPath())
            assertEquals("job_history_unavailable", reader.list(owner).error)
            Files.delete(directory.toPath())
            assertTrue(File(outside, "secret").exists())
        } finally { outside.deleteRecursively() }
    }

    @Test fun missingAndEmptyLogsRemainReadOnlyAndMissingLogCannotAcceptNonzeroOffset() = fixture { files, directory ->
        val id = record(directory)
        val reader = reader(files)
        val original = reader.list(owner).records.single()
        val before = snapshot(files)
        val missing = reader.page(owner, original)
        assertTrue(missing.getBoolean("ok"))
        assertFalse(missing.getBoolean("log_available"))
        assertEquals(0L, missing.getLong("total_bytes"))
        assertEquals("", missing.getString("stdout_base64"))
        assertFalse(reader.page(owner, original, 1).getBoolean("ok"))
        assertEquals(before, snapshot(files))
        File(directory, "$id.log").writeBytes(ByteArray(0))
        val empty = reader.page(owner, original)
        assertTrue(empty.getBoolean("ok"))
        assertTrue(empty.getBoolean("log_available"))
        assertFalse(empty.getBoolean("has_more"))
    }

    @Test fun fileAndRecordQuotasFailClosedWithNoUnboundedResult() = fixture { files, directory ->
        record(directory)
        repeat(TerminalJobHistoryReader.MAX_FILES) { File(directory, "ignored-$it.tmp").writeText("") }
        val reader = reader(files)
        assertEquals("job_directory_scan_limit", reader.list(owner).error)
        assertTrue(reader.list(owner).records.isEmpty())
        directory.deleteRecursively()
        repeat(TerminalJobHistoryReader.MAX_JOBS + 1) { record(directory) }
        assertEquals("job_history_limit", reader.list(owner).error)
        assertTrue(reader.list(owner).records.isEmpty())
    }
}
