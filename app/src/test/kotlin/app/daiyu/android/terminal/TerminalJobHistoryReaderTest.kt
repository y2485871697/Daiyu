package app.daiyu.android.terminal

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.UUID

/** 用 JVM 文件读取测试历史记录格式和分页。
 * Android 文件描述符的 NOFOLLOW、NONBLOCK、fstat 和路径替换由设备测试覆盖。
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
                override fun journal(scope: String): TerminalJobHistoryReader.Slice? {
                    val path = File(anchor, "terminal-journal/$scope.json").toPath()
                    if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return null
                    check(Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    require(Files.size(path) <= TerminalJobHistoryReader.MAX_JOURNAL_BYTES)
                    val bytes = Files.readAllBytes(path)
                    return TerminalJobHistoryReader.Slice(bytes, bytes.size.toLong())
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

    @Test fun commandPreviewIsOptionalDisplayDataAndNeverRestoresAuthority() = fixture { files, directory ->
        val old = record(directory)
        val withCommand = record(directory) { it.put("command", "printf saved-command") }
        record(directory) { it.put("command", "x".repeat(2049)) }
        val scan = reader(files).list(owner)
        assertEquals(1, scan.skipped)
        assertNull(scan.records.single { it.jobId == old }.command)
        val preview = scan.records.single { it.jobId == withCommand }
        assertEquals("printf saved-command", preview.command)
        assertEquals("unknown_after_process_restart", preview.state)
        assertEquals("unknown", reader(files).page(owner, preview).getString("execution"))
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

    @Test fun hundredsOfRecordsAndUnrelatedFilesRemainReadableWithoutDeletion() = fixture { files, directory ->
        val ids = (0 until 257).map { record(directory) }
        ids.forEach { File(directory, "$it.log").writeText("retained-$it") }
        repeat(300) { File(directory, "ignored-$it.tmp").writeText("untouched") }
        val before = snapshot(files)
        val history = reader(files)
        val scan = history.list(owner)
        assertNull(scan.error)
        assertEquals(0, scan.skipped)
        assertEquals(ids.sorted(), scan.records.map { it.jobId })
        assertEquals(scan.records, reader(files).list(owner).records)
        for (id in listOf(ids.first(), ids[128], ids.last())) {
            val original = scan.records.single { it.jobId == id }
            assertEquals("retained-$id", history.page(owner, original).getString("stdout"))
            assertEquals("retained-$id", reader(files).page(owner, original).getString("stdout"))
        }
        assertEquals(before, snapshot(files))
    }
    private fun completion(directory: File, id: String, code: Int): JSONObject {
        val source = JSONObject(File(directory, "$id.json").readText())
        return JSONObject().put("format", 1).put("ok", true).put("state", "exited")
            .put("execution", "executed").put("exit_code", code).put("output_complete", true).apply {
                for (key in listOf("job_id", "owner", "scope", "session_id", "backend", "mode")) put(key, source.get(key))
            }
    }
    private fun journal(files: File, result: JSONObject, state: String = "committed"): File {
        val directory = File(files, "terminal-journal").apply { mkdirs() }
        return File(directory, "original-run.json").apply { writeText(JSONObject().put("format", 4)
            .put("binding", "a".repeat(64)).put("lifecycle", "active").put("entries", JSONObject()
                .put("original-request", JSONObject().put("state", state).put("result", result))).toString()) }
    }
    @Test fun versionedCompletionKeepsZeroAndNonzeroExitFactsWithoutRestoringAuthority() = fixture { files, directory ->
        for (code in listOf(0, 1, 127)) {
            val id = record(directory)
            val metadata = File(directory, "$id.json")
            metadata.writeText(JSONObject(metadata.readText()).put("completion", completion(directory, id, code)).toString())
        }
        val before = snapshot(files)
        val history = reader(files)
        val records = history.list(owner).records
        assertEquals(setOf(0, 1, 127), records.map { it.exitCode }.toSet())
        records.forEach {
            assertEquals("exited", it.state)
            val page = history.page(owner, it)
            assertEquals(it.exitCode, page.getInt("exit_code"))
            assertEquals("executed", page.getString("execution"))
            assertTrue(page.getBoolean("history_read_only"))
            assertFalse(page.has("pid")); assertFalse(page.has("session_id"))
        }
        assertEquals(before, snapshot(files))
    }
    @Test fun oldDispatchedRecordCanRecoverOnlyItsExactCommittedFinalReceiptReadOnly() = fixture { files, directory ->
        val id = record(directory) { it.put("state", "dispatched").put("execution", "unknown").remove("exit_code") }
        val result = completion(directory, id, 127)
        journal(files, result)
        val before = snapshot(files)
        val original = reader(files).list(owner).records.single()
        assertEquals(127, original.exitCode)
        val page = reader(files).page(owner, original)
        assertEquals("exited", page.getString("state"))
        assertEquals(127, page.getInt("exit_code"))
        assertEquals(before, snapshot(files))
    }
    @Test fun pendingUnknownConflictingAndMismatchedReceiptsDoNotBecomeSuccess() = fixture { files, directory ->
        val id = record(directory)
        val good = completion(directory, id, 0)
        for (field in listOf("job_id", "owner", "scope", "session_id", "backend", "mode", "execution", "state")) {
            journal(files, JSONObject(good.toString()).put(field, "other"))
            assertNull(field, reader(files).list(owner).records.single().exitCode)
        }
        for (field in listOf("exit_code", "output_complete", "ok")) {
            journal(files, JSONObject(good.toString()).put(field, JSONObject.NULL))
            assertNull(field, reader(files).list(owner).records.single().exitCode)
        }
        journal(files, good, "pending")
        assertNull(reader(files).list(owner).records.single().exitCode)
        val ledger = journal(files, good)
        val value = JSONObject(ledger.readText())
        value.getJSONObject("entries").put("duplicate", JSONObject().put("state", "committed")
            .put("result", JSONObject(good.toString()).put("exit_code", 1)))
        ledger.writeText(value.toString())
        assertNull(reader(files).list(owner).records.single().exitCode)
        for (bad in listOf(JSONObject(good.toString()).put("exit_code", "0"),
            JSONObject(good.toString()).put("exit_code", 0.5), JSONObject(good.toString()).put("exit_code", Long.MAX_VALUE))) {
            journal(files, bad)
            assertNull(reader(files).list(owner).records.single().exitCode)
        }
    }
    @Test fun malformedAndSymlinkJournalsDoNotHideOriginalLogsOrEnableControls() = fixture { files, directory ->
        val id = record(directory)
        File(directory, "$id.log").writeText("original")
        val ledger = journal(files, completion(directory, id, 0))
        val validJournal = ledger.readBytes()
        ledger.writeText("{")
        val reader = reader(files)
        assertNull(reader.list(owner).records.single().exitCode)
        val outside = Files.createTempFile("outside-journal", ".json")
        try {
            Files.write(outside, validJournal)
            ledger.delete()
            Files.createSymbolicLink(ledger.toPath(), outside)
            val original = reader.list(owner).records.single()
            assertNull(original.exitCode)
            assertEquals("original", reader.page(owner, original).getString("stdout"))
            Files.delete(ledger.toPath())
        } finally { Files.deleteIfExists(outside) }
    }
    @Test fun completionUpgradeBetweenListAndPageIsAllowedButIdentityChangesStillFail() = fixture { files, directory ->
        val id = record(directory)
        val history = reader(files)
        val original = history.list(owner).records.single()
        val metadata = File(directory, "$id.json")
        metadata.writeText(JSONObject(metadata.readText()).put("completion", completion(directory, id, 1)).toString())
        assertEquals(1, history.page(owner, original).getInt("exit_code"))
        val known = history.list(owner).records.single()
        metadata.writeText(JSONObject(metadata.readText()).apply { remove("completion") }.toString())
        assertFalse(history.page(owner, known).getBoolean("ok"))
    }

    @Test fun incompleteOrMisboundVersionedCompletionAndMissingRootDrainStayUnknown() = fixture { files, directory ->
        val id = record(directory)
        val metadata = File(directory, "$id.json")
        val base = JSONObject(metadata.readText())
        val good = completion(directory, id, 0)
        for (field in listOf("format", "owner", "scope", "session_id", "job_id", "backend", "mode", "execution", "state", "exit_code", "output_complete")) {
            metadata.writeText(JSONObject(base.toString()).put("completion", JSONObject(good.toString()).put(field, JSONObject.NULL)).toString())
            assertNull(field, reader(files).list(owner).records.single().exitCode)
        }
        metadata.writeText(JSONObject(base.toString()).put("root", true).put("completion", good).toString())
        assertNull(reader(files).list(owner).records.single().exitCode)
        good.put("scope_drain_confirmed", true)
        metadata.writeText(JSONObject(base.toString()).put("root", true).put("completion", good).toString())
        assertEquals(0, reader(files).list(owner).records.single().exitCode)
    }

}
