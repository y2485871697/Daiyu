package app.daiyu.android.terminal

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

class TerminalJobRetentionTest {
    private fun fixture(test: (File, File, TerminalJobRetention) -> Unit) {
        val root = Files.createTempDirectory("terminal-retention").toFile()
        val jobs = File(root, "terminal-jobs").apply { mkdirs() }
        val journals = File(root, "terminal-journal").apply { mkdirs() }
        try { test(jobs, journals, TerminalJobRetention(jobs, journals)) } finally { root.deleteRecursively() }
    }
    private fun record(jobs: File, mode: String = "sync", scope: String = "old-run", state: String = "dispatched"): String {
        val id = UUID.randomUUID().toString()
        File(jobs, "$id.json").writeText(JSONObject().put("job_id", id).put("scope", scope)
            .put("owner", "owner").put("mode", mode).put("state", state).toString())
        File(jobs, "$id.log").writeText("saved output")
        return id
    }
    private fun receipt(journals: File, id: String, mutate: (JSONObject) -> Unit = {}) {
        val result = JSONObject().put("job_id", id).put("execution", "executed").put("state", "exited")
            .put("output_complete", true).put("exit_code", 0).put("has_more", false)
            .put("offset", 0).put("next_offset", 12).put("total_bytes", 12)
        mutate(result)
        File(journals, "old-run.json").writeText(JSONObject().put("format", 4).put("binding", "a".repeat(64))
            .put("lifecycle", "active").put("entries", JSONObject().put("request",
                JSONObject().put("state", "committed").put("fingerprint", "b".repeat(64)).put("result", result))).toString())
    }
    @Test fun moreThan64CompletedCommandsKeepBoundedHistoryWithoutLifetimeFailure() = fixture { jobs, _, retention ->
        val live = linkedMapOf<String, Boolean>()
        val evicted = mutableListOf<String>()
        repeat(130) {
            retention.reclaim(live, { live.size < 64 }) { id -> evicted.add(id); live.remove(id) }
            assertTrue(live.size < 64)
            live[record(jobs)] = true
        }
        assertEquals(66, evicted.size)
        assertEquals(64, live.size)
        assertEquals(64, jobs.listFiles()!!.count { it.extension == "json" })
        assertTrue(evicted.all { !File(jobs, "$it.log").exists() })
    }
    @Test fun noPressureLeavesCompletedHistoryIntact() = fixture { jobs, _, retention ->
        val id = record(jobs)
        retention.reclaim(mapOf(id to true), { true }) { fail("No eviction needed") }
        assertTrue(File(jobs, "$id.log").isFile)
    }
    @Test fun oldDispatchedMetadataWithCommittedCompleteReceiptCanBeReclaimedAfterRestart() = fixture { jobs, journals, retention ->
        val id = record(jobs)
        receipt(journals, id)
        val journalBytes = File(journals, "old-run.json").readBytes()
        val released = mutableListOf<String>()
        retention.reclaim(emptyMap(), { released.isNotEmpty() }, released::add)
        assertEquals(listOf(id), released)
        assertFalse(File(jobs, "$id.json").exists())
        assertArrayEquals(journalBytes, File(journals, "old-run.json").readBytes())
    }
    @Test fun liveUnfinishedTaskOverridesEvenACompletedDiskReceipt() = fixture { jobs, journals, retention ->
        val id = record(jobs)
        receipt(journals, id)
        retention.reclaim(mapOf(id to false), { false }) { fail("Live task reclaimed") }
        assertTrue(File(jobs, "$id.json").isFile)
    }
    @Test fun unknownIncompleteAndMissingExitReceiptsAreNotCompletionProof() {
        for (change in listOf<(JSONObject) -> Unit>(
            { it.put("execution", "unknown") }, { it.put("output_complete", false) },
            { it.put("state", "running") }, { it.put("exit_code", JSONObject.NULL) },
            { it.put("job_id", UUID.randomUUID().toString()) }, { it.put("has_more", true) },
            { it.put("next_offset", 6) }, { it.put("offset", 6) })) {
            fixture { jobs, journals, retention ->
                val id = record(jobs)
                receipt(journals, id, change)
                retention.reclaim(emptyMap(), { false }) { fail("Unresolved task reclaimed") }
                assertTrue(File(jobs, "$id.log").isFile)
            }
        }
    }
    @Test fun daemonIsPreservedEvenWithAnOldCompleteReceipt() = fixture { jobs, journals, retention ->
        val id = record(jobs, mode = "daemon")
        receipt(journals, id)
        retention.reclaim(emptyMap(), { false }) { fail("Daemon reclaimed") }
        assertTrue(File(jobs, "$id.log").isFile)
    }
    @Test fun pendingUnknownCorruptAndOrphanRecordsStayUntouched() = fixture { jobs, _, retention ->
        val pending = record(jobs, state = "pending")
        val unknown = record(jobs)
        File(jobs, "${UUID.randomUUID()}.json").writeText("bad json")
        val orphan = File(jobs, "${UUID.randomUUID()}.log").apply { writeText("orphan evidence") }
        val before = jobs.listFiles()!!.associate { it.name to it.readBytes().toList() }
        retention.reclaim(emptyMap(), { false }) { fail("Unknown evidence reclaimed") }
        assertTrue(File(jobs, "$pending.json").isFile)
        assertTrue(File(jobs, "$unknown.json").isFile)
        assertTrue(orphan.isFile)
        assertEquals(before, jobs.listFiles()!!.associate { it.name to it.readBytes().toList() })
    }
    @Test fun failedDeletionDoesNotReleaseReservation() = fixture { jobs, _, retention ->
        val id = record(jobs)
        File(jobs, "$id.log").delete()
        File(jobs, "$id.log").mkdir()
        File(jobs, "$id.log/keep").writeText("cannot delete directory")
        retention.reclaim(mapOf(id to true), { false }) { fail("Reservation released despite failed deletion") }
        assertTrue(File(jobs, "$id.json").isFile)
    }
    @Test fun failedRemovalCanRetryAndSuccessfulRemovalIsNotReleasedTwice() = fixture { jobs, _, retention ->
        val id = record(jobs)
        File(jobs, "$id.log").delete()
        File(jobs, "$id.log").mkdir()
        val blocker = File(jobs, "$id.log/keep").apply { writeText("keep") }
        val live = mutableMapOf(id to true)
        val releases = mutableListOf<String>()
        fun reclaim() = retention.reclaim(live, { live.isEmpty() }) { key -> releases.add(key); live.remove(key) }
        reclaim()
        assertTrue(releases.isEmpty())
        blocker.delete()
        reclaim()
        reclaim()
        assertEquals(listOf(id), releases)
        assertFalse(File(jobs, "$id.json").exists())
    }
    @Test fun incompleteJournalAndScopePathCannotAuthorizeEviction() = fixture { jobs, journals, retention ->
        val id = record(jobs, scope = "../old-run")
        receipt(journals, id)
        retention.reclaim(emptyMap(), { false }) { fail("Unsafe scope accepted") }
        assertTrue(File(jobs, "$id.log").isFile)
        val second = record(jobs)
        receipt(journals, second)
        val file = File(journals, "old-run.json")
        val journal = JSONObject(file.readText())
        journal.getJSONObject("entries").getJSONObject("request").put("state", "pending")
        file.writeText(journal.toString())
        retention.reclaim(emptyMap(), { false }) { fail("Pending journal accepted") }
        assertTrue(File(jobs, "$second.log").isFile)
    }
    @Test fun boundedScanDoesNotDeleteOversizedDirectories() = fixture { jobs, _, retention ->
        val id = record(jobs)
        repeat(255) { File(jobs, "$it.other").writeText("") }
        retention.reclaim(mapOf(id to true), { false }) { fail("Unbounded scan") }
        assertTrue(File(jobs, "$id.log").isFile)
    }
    @Test fun retiredScopeWithoutOutputProofDoesNotDeleteHistory() = fixture { jobs, journals, retention ->
        val id = record(jobs)
        receipt(journals, id)
        val file = File(journals, "old-run.json")
        file.writeText(JSONObject(file.readText()).put("lifecycle", "retired").put("entries", JSONObject()).toString())
        retention.reclaim(emptyMap(), { false }) { fail("No remaining output receipt") }
        assertTrue(File(jobs, "$id.log").isFile)
    }
    @Test fun preparationFailureWithoutMetadataCanReleaseItsLiveReservation() = fixture { _, _, retention ->
        val id = UUID.randomUUID().toString()
        val released = mutableListOf<String>()
        retention.reclaim(mapOf(id to true), { released.isNotEmpty() }, released::add)
        assertEquals(listOf(id), released)
    }
}
