package app.daiyu.android.terminal

import org.json.JSONObject
import junit.framework.TestCase
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class OperationJournalTest : TestCase() {
    private fun fixture(test: (File) -> Unit) {
        val root = Files.createTempDirectory("daiyu-journal-test").toFile()
        try { test(root) } finally { root.deleteRecursively() }
    }
    private fun args(id: String) = JSONObject().put("request_id", id).put("command", "printf test").put("session_id", "session")
    private fun run(journal: OperationJournal, args: JSONObject, action: () -> JSONObject): JSONObject {
        val effective = TerminalArguments.execution(args)
        return journal.once(args.getString("request_id"), "terminal_exec", TerminalArguments.identity(effective), action)
    }
    fun testDirectoryAdapterFailuresPreserveReservationAndCommitFences() = fixture { root ->
        for (failAt in listOf(1, 2)) {
            var syncs = 0
            var enabled = false
            var starts = 0
            val journal = OperationJournal(root, "adapter-$failAt", 256, null) { directory ->
                if (enabled && ++syncs == failAt) throw java.io.IOException("injected directory adapter failure")
                hostDirectorySync(directory)
            }
            enabled = true
            val action = { starts++; JSONObject().put("execution", "executed").put("job_id", "owned-job") }
            val result = run(journal, args("first"), action)
            assertEquals(if (failAt == 1) "not_executed" else "unknown", result.getString("execution"))
            assertEquals(failAt - 1, starts)
            if (failAt == 2) assertEquals("owned-job", result.getString("job_id"))
            run(hostJournal(root, "adapter-$failAt"), args("retry"), action)
            assertEquals(failAt - 1, starts)
        }
    }
    fun testDirectoryAdapterConstructorAndExistingBindingFailuresStayClosed() = fixture { root ->
        try {
            OperationJournal(root, "constructor", 256, null) { throw java.io.IOException("directory unavailable") }
            fail("constructor must require directory sync")
        } catch (expected: java.io.IOException) { }
        var unavailable = false
        val journal = OperationJournal(root, "binding-adapter", 256, JSONObject().put("owner", "same")) {
            if (unavailable) throw java.io.IOException("directory unavailable")
            hostDirectorySync(it)
        }
        assertTrue(journal.verifyBinding().getBoolean("ok"))
        unavailable = true
        assertEquals("operation_binding_unavailable", journal.verifyBinding().getString("error"))
    }
    fun testDefaultsAndAsyncTimeoutCannotEvadeUnknownReservation() = fixture { root ->
        val journal = hostJournal(root, "run")
        var starts = 0
        val action = { starts++; JSONObject().put("execution", "unknown").put("job_id", "job") }
        assertEquals("unknown", run(journal, args("first"), action).getString("execution"))
        val explicit = args("second").put("mode", "sync").put("timeout_ms", 30000)
        assertEquals("equivalent_outcome_unknown_not_replayed", run(journal, explicit, action).getString("error"))
        assertTrue(run(journal, explicit.put("request_id", "first"), action).getBoolean("replayed_execution"))
        run(journal, args("change-wait").put("mode", "async").put("timeout_ms", 1), action)
        assertEquals(1, starts)
        run(journal, args("async1").put("command", "printf another").put("mode", "async").put("timeout_ms", 1), action)
        run(journal, args("async2").put("command", "printf another").put("mode", "async").put("timeout_ms", 120000), action)
        assertEquals(2, starts)
    }
    fun testReservationDirectorySyncFailureNeverExecutes() = fixture { root ->
        val journal = hostJournal(root, "run")
        journal.beforeDirectorySync = { throw java.io.IOException("injected reservation sync failure") }
        var starts = 0
        val result = run(journal, args("request")) { starts++; JSONObject() }
        assertEquals("not_executed", result.getString("execution"))
        assertEquals(0, starts)
        journal.beforeDirectorySync = null
        assertEquals("unknown", run(journal, args("request")) { starts++; JSONObject() }.getString("execution"))
        assertEquals(0, starts)
    }
    fun testLostCommitEntryPreservesIdentifiersAndReportsUnknown() = fixture { root ->
        val journal = hostJournal(root, "run")
        val result = run(journal, args("request")) {
            File(root, "run.json").writeText("{}")
            JSONObject().put("ok", true).put("execution", "executed").put("job_id", "owned-job")
        }
        assertEquals("journal_commit_unknown_not_replayed", result.getString("error"))
        assertEquals("unknown", result.getString("execution"))
        assertEquals("owned-job", result.getString("job_id"))
    }
    fun testFinalDirectorySyncFailureReportsUnknownAndNeverRelaunches() = fixture { root ->
        val journal = hostJournal(root, "run")
        var saves = 0; var starts = 0
        journal.beforeDirectorySync = { if (++saves == 2) throw java.io.IOException("injected commit sync failure") }
        val action = { starts++; JSONObject().put("ok", true).put("execution", "executed").put("job_id", "owned-job") }
        val result = run(journal, args("first"), action)
        assertEquals("journal_commit_unknown_not_replayed", result.getString("error"))
        assertEquals("owned-job", result.getString("job_id"))
        journal.beforeDirectorySync = null
        val cached = run(hostJournal(root, "run"), args("second"), action)
        assertEquals("executed", cached.getString("execution"))
        assertTrue(cached.getBoolean("equivalent_request"))
        assertEquals(1, starts)
    }
    fun testCompletedPtyWriteWithSameSequenceIsCachedButNewInputIsAllowed() = fixture { root ->
        val journal = hostJournal(root, "pty")
        var writes = 0
        val args = JSONObject().put("data_base64", "QQ==").put("input_sequence", 1)
        val action = { writes++; JSONObject().put("execution", "executed").put("helper_input_written", true) }
        journal.once("first", "pty_write", args, action)
        assertTrue(journal.once("retry", "pty_write", args, action).getBoolean("equivalent_request"))
        journal.once("intentional-repeat", "pty_write", JSONObject(args.toString()).put("input_sequence", 2), action)
        assertEquals(2, writes)
    }
    fun testDistinctControlSequencesDoNotSwallowRepeatedInterruptsOrResize() = fixture { root ->
        val journal = hostJournal(root, "pty")
        var interrupts = 0; var resizes = 0
        fun interrupt(id: String, seq: Long) = journal.once(id, "pty_interrupt", JSONObject().put("control_sequence", seq)) {
            interrupts++; JSONObject().put("execution", "executed")
        }
        interrupt("first", 1); interrupt("retry", 1); interrupt("next-foreground", 2)
        assertEquals(2, interrupts)
        fun resize(id: String, rows: Int, seq: Long) = journal.once(id, "pty_resize", JSONObject().put("rows", rows).put("control_sequence", seq)) {
            resizes++; JSONObject().put("execution", "executed")
        }
        resize("a", 24, 1); resize("b", 33, 2); resize("back-a", 24, 3)
        assertEquals(3, resizes)
    }
    fun testFileLockDeadlineAndInterruptNeverExecute() = fixture { root ->
        val journal = hostJournal(root, "run").apply { lockTimeoutMillis = 80 }
        RandomAccessFile(File(root, "run.lock"), "rw").use { lockFile ->
            lockFile.channel.lock().use {
                val before = System.nanoTime()
                val result = run(journal, args("deadline")) { fail("must not execute under held lock"); JSONObject() }
                assertEquals("not_executed", result.getString("execution"))
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 1500)
                journal.lockTimeoutMillis = 3000
                val started = CountDownLatch(1)
                val output = AtomicReference<JSONObject>(); val interrupted = AtomicReference<Boolean>()
                val thread = Thread {
                    started.countDown()
                    output.set(run(journal, args("interrupt")) { fail("must not execute when interrupted"); JSONObject() })
                    interrupted.set(Thread.currentThread().isInterrupted)
                }.apply { start() }
                assertTrue(started.await(1, TimeUnit.SECONDS)); thread.interrupt(); thread.join(1000)
                assertFalse(thread.isAlive)
                assertEquals("operation_reservation_interrupted", output.get().getString("error"))
                assertTrue(interrupted.get())
            }
        }
    }
    fun testOldUnknownLedgerFailsClosedWithoutDeletingEvidence() = fixture { root ->
        val file = File(root, "run.json")
        val old = """{"old":{"fingerprint":"old-raw-fingerprint","state":"pending"}}"""
        file.writeText(old)
        val result = run(hostJournal(root, "run"), args("new")) { fail("legacy unknown must not relaunch"); JSONObject() }
        assertEquals("legacy_equivalence_unknown_not_replayed", result.getString("error"))
        assertEquals(old, file.readText())
    }
    fun testCommandWaitDoesNotHoldReservationLockAndScopesStayIndependent() = fixture { root ->
        val first = hostJournal(root, "run")
        first.once("one", "tool", JSONObject().put("v", 1)) {
            assertTrue(hostJournal(root, "run").once("two", "tool", JSONObject().put("v", 2)) {
                JSONObject().put("ok", true).put("execution", "executed")
            }.getBoolean("ok"))
            assertTrue(hostJournal(root, "different_run").once("one", "tool", JSONObject().put("v", 1)) {
                JSONObject().put("ok", true).put("execution", "executed")
            }.getBoolean("ok"))
            JSONObject().put("execution", "executed")
        }
    }
    fun testBindingCheckedBeforeReplayAndLegacyCannotBeClaimed() = fixture { root ->
        fun journal(owner: String) = hostJournal(root, "bound", binding = JSONObject().put("owner", owner))
        val first = journal("a")
        assertTrue(first.verifyBinding().getBoolean("ok"))
        var starts = 0
        val action = { starts++; JSONObject().put("execution", "executed").put("stdout", "private") }
        run(first, args("one"), action)
        val other = journal("b")
        assertEquals("operation_scope_binding_mismatch", other.verifyBinding().getString("error"))
        val rejected = run(other, args("one"), action)
        assertFalse(rejected.has("stdout"))
        assertEquals("operation_scope_binding_mismatch", rejected.getString("error"))
        assertTrue(run(journal("a"), args("one"), action).getBoolean("replayed_execution"))
        assertEquals(1, starts)
        File(root, "legacy.json").writeText("{\"old\":{\"state\":\"pending\"}}")
        assertEquals("legacy_scope_binding_unavailable", hostJournal(root, "legacy", binding=JSONObject()).verifyBinding().getString("error"))
    }
    fun testRetirementCompactsWithoutReactivatingOldOrNewInstances() = fixture { root ->
        val journal = hostJournal(root, "run", limit=1)
        var starts = 0
        val action = { starts++; JSONObject().put("execution", "executed").put("stdout", "x".repeat(10000)) }
        run(journal, args("one"), action)
        assertEquals("operation_ledger_full", run(journal, args("two").put("command", "different"), action).getString("error"))
        assertTrue(run(journal, args("alias"), action).getBoolean("equivalent_request"))
        assertTrue(journal.retireScope().getBoolean("ok"))
        assertTrue(File(root, "run.json").length() < 200)
        assertTrue(journal.retireScope().getBoolean("ok"))
        for (instance in listOf(journal, hostJournal(root, "run"))) {
            for (id in listOf("one", "new")) assertEquals("operation_scope_retired", run(instance, args(id), action).getString("error"))
        }
        assertTrue(File(root, "run.lock").exists())
        assertEquals(1, starts)
    }
    fun testRetirementNeverDropsPendingUnknownOrMalformedEvidence() = fixture { root ->
        val journal = hostJournal(root, "run")
        run(journal, args("one")) {
            val bytes = File(root, "run.json").readText()
            assertEquals("operation_scope_unresolved_not_retired", journal.retireScope().getString("error"))
            assertEquals(bytes, File(root, "run.json").readText())
            JSONObject().put("execution", "unknown").put("job_id", "job")
        }
        val bytes = File(root, "run.json").readText()
        assertEquals("operation_scope_unresolved_not_retired", journal.retireScope().getString("error"))
        assertEquals(bytes, File(root, "run.json").readText())
        assertEquals("equivalent_outcome_unknown_not_replayed", run(journal, args("two")) { fail("must not execute"); JSONObject() }.getString("error"))
        File(root, "malformed.json").writeText("{\"old\":{\"state\":\"committed\"}}")
        assertEquals("operation_scope_unresolved_not_retired", hostJournal(root,"malformed").retireScope().getString("error"))
    }
    fun testRetirementFailuresPreserveDedupOrPermanentFence() = fixture { root ->
        val journal = hostJournal(root, "run")
        var starts = 0
        val action = { starts++; JSONObject().put("execution", "executed") }
        run(journal, args("one"), action)
        journal.beforeSave = { throw java.io.IOException("before retirement") }
        assertEquals("operation_retirement_unconfirmed", journal.retireScope().getString("error"))
        journal.beforeSave = null
        assertTrue(run(journal, args("two"), action).getBoolean("equivalent_request"))
        journal.beforeDirectorySync = { throw java.io.IOException("retirement sync") }
        assertEquals("operation_retirement_unconfirmed", journal.retireScope().getString("error"))
        assertEquals("operation_scope_retired", run(hostJournal(root,"run"),args("three"),action).getString("error"))
        assertEquals(1, starts)
    }

    fun testConcurrentRetirementCannotEraseReservedOperation() = fixture { root ->
        val journal = hostJournal(root,"run")
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val result = AtomicReference<JSONObject>()
        val thread = Thread {
            result.set(run(journal,args("one")) {
                entered.countDown(); release.await()
                JSONObject().put("execution","executed")
            })
        }.apply { start() }
        try {
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            assertEquals("operation_scope_unresolved_not_retired", hostJournal(root,"run").retireScope().getString("error"))
        } finally { release.countDown(); thread.join(2000) }
        assertFalse(thread.isAlive)
        assertEquals("executed",result.get().getString("execution"))
        assertTrue(journal.retireScope().getBoolean("ok"))
        assertEquals("operation_scope_retired",run(journal,args("new")) { fail("retired"); JSONObject() }.getString("error"))
    }

    fun testEmptyLegacyBindingIsDurablyClaimedAndRetirementRetrySyncsAgain() = fixture { root ->
        File(root,"run.json").writeText("{}")
        val first=hostJournal(root,"run",binding=JSONObject().put("owner","a"))
        assertTrue(first.verifyBinding().getBoolean("ok"))
        val other=hostJournal(root,"run",binding=JSONObject().put("owner","b"))
        assertEquals("operation_scope_binding_mismatch",other.verifyBinding().getString("error"))
        var syncs=0
        first.beforeDirectorySync={ syncs++; throw java.io.IOException("retry must sync") }
        assertEquals("operation_retirement_unconfirmed",first.retireScope().getString("error"))
        assertEquals("operation_retirement_unconfirmed",first.retireScope().getString("error"))
        assertEquals(2,syncs)
        first.beforeDirectorySync=null
        assertTrue(first.retireScope().getBoolean("ok"))
    }

    fun testExplicitEventIdentityCannotChangePayloadOrControlKind() = fixture { root ->
        val journal=hostJournal(root,"pty")
        var deliveries=0
        val action={ deliveries++; JSONObject().put("execution","executed") }
        val args=JSONObject().put("data","A").put("seq",1)
        journal.once("first","pty_write",args,"input-1",action)
        assertTrue(journal.once("retry","pty_write",args,"input-1",action).getBoolean("equivalent_request"))
        assertEquals("operation_event_identity_conflict", journal.once("changed","pty_write",JSONObject().put("data","B").put("seq",1),"input-1",action).getString("error"))
        journal.once("resize","pty_resize",JSONObject().put("seq",1),"control-1",action)
        assertEquals("operation_event_identity_conflict", journal.once("interrupt","pty_interrupt",JSONObject().put("seq",1),"control-1",action).getString("error"))
        journal.once("next","pty_write",JSONObject(args.toString()).put("seq",2),"input-2",action)
        assertEquals(3,deliveries)
    }

}
