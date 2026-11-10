package app.daiyu.android.diagnostics

import org.junit.Assert.*
import org.junit.Test

class BackPerformanceRecorderTest {
    private class Fixture {
        var now = 1_000_000_000L
        var epoch = 0L
        val rows = mutableListOf<BackPerformanceRecorder.Incident>()
        val owner = Any()
        val recorder = BackPerformanceRecorder({ now }, { epoch }, { rows.add(it) })
        fun start() = recorder.start(owner, BackPerformanceRecorder.Kind.CUSTOM_SWIPE)
        fun frame(duration: Long = 30_000_000L, vsync: Long = now) =
            recorder.frame(vsync, duration, 16_666_667L, 60f, LongArray(20) { it.toLong() })
        fun end() = recorder.end(BackPerformanceRecorder.End.PAUSED)
    }
    @Test fun fastWindowsAreNotSavedAndSlowFramesAreBounded() {
        val f = Fixture(); f.start(); repeat(100) { f.frame(1) }; f.end()
        assertTrue(f.rows.isEmpty())
        f.start(); repeat(500) { f.frame(30_000_000L + it) }; f.end()
        val row = f.rows.single()
        assertEquals(500, row.frames); assertEquals(500, row.slow)
        assertEquals(4, row.worst.size); assertTrue(row.worst.all { it.stages.size == 9 })
        assertEquals(30_000_499L, row.max)
        assertEquals(16_666_667L, row.minBudget); assertEquals(60f, row.maxHz)
    }
    @Test fun commitAndFinallyResetPreserveTailWithoutClaimingCompletion() {
        val f = Fixture(); f.start(); f.frame(); f.recorder.commit(f.owner)
        f.recorder.reset(f.owner); f.recorder.route(f.owner)
        f.now += BackPerformanceRecorder.TAIL_NS - 1; f.recorder.checkpoint()
        assertTrue(f.rows.isEmpty())
        f.now++; f.recorder.checkpoint()
        val row = f.rows.single()
        assertEquals(BackPerformanceRecorder.End.TAIL, row.reason)
        assertTrue(row.commitAttempted); assertTrue(row.routeChanged)
    }
    @Test fun hardLimitAndResetNeedNoBusinessWait() {
        val f = Fixture(); f.start(); f.frame()
        f.now += BackPerformanceRecorder.HARD_NS; f.recorder.checkpoint()
        assertEquals(BackPerformanceRecorder.End.HARD_LIMIT, f.rows.single().reason)
        f.start(); f.frame(); f.recorder.reset(f.owner)
        f.now += BackPerformanceRecorder.TAIL_NS; f.recorder.checkpoint()
        assertEquals(BackPerformanceRecorder.End.RESET, f.rows.last().reason)
        assertFalse(f.rows.last().commitAttempted)
    }
    @Test fun lateFramesAndOldIoTokenDoNotPolluteConsecutiveGestures() {
        val f = Fixture(); val first = f.start()
        val sample = f.recorder.begin("daiyu.chat.model-refresh-commit", first)
        f.frame(); val oldVsync = f.now
        f.now += 50_000_000L; val second = f.start()
        assertNotEquals(first, second)
        assertEquals(BackPerformanceRecorder.End.SUPERSEDED, f.rows.single().reason)
        f.recorder.complete(sample)
        assertNull(f.recorder.begin("daiyu.chat.model-refresh-publish", first))
        f.frame(vsync = oldVsync); f.end()
        assertEquals(1, f.rows.size)
    }
    @Test fun ownerChangesAndClearInvalidateOldWindowWithoutRebinding() {
        val f = Fixture(); val old = f.start(); f.frame()
        val stranger = Any()
        f.recorder.commit(stranger); f.recorder.route(stranger); f.recorder.reset(stranger)
        f.recorder.end(stranger, BackPerformanceRecorder.End.OWNER_REMOVED)
        assertEquals(old, f.recorder.token())
        f.epoch++; f.end(); assertTrue(f.rows.isEmpty()); assertNull(f.recorder.token())
        f.start(); f.frame(); f.end()
        assertFalse(f.rows.single().commitAttempted); assertFalse(f.rows.single().routeChanged)
    }
    @Test fun aDifferentActivityCannotCaptureFramesOrEndTheOwnersWindow() {
        val f = Fixture()
        val host = Any(); val otherHost = Any()
        val owner = BackPerformanceRecorder.Owner(host) { _, _ -> }
        f.recorder.start(owner, BackPerformanceRecorder.Kind.PREDICTIVE)
        assertTrue(f.recorder.observing(host)); assertFalse(f.recorder.observing(otherHost))
        f.recorder.frame(f.now, 40_000_000L, 16_666_667L, 60f, LongArray(9), otherHost)
        f.recorder.endHost(otherHost, BackPerformanceRecorder.End.PAUSED)
        assertNotNull(f.recorder.token())
        f.recorder.frame(f.now, 40_000_000L, 16_666_667L, 60f, LongArray(9), host)
        f.recorder.endHost(host, BackPerformanceRecorder.End.DESTROYED)
        assertEquals(1, f.rows.single().frames)
        assertEquals(BackPerformanceRecorder.End.DESTROYED, f.rows.single().reason)
    }
    @Test fun phaseWhitelistMarkerAndInclusiveNestedDurations() {
        val f = Fixture(); f.start()
        assertNull(f.recorder.begin("private-unapproved-phase"))
        val outer = f.recorder.begin("daiyu.back.callback-commit")
        val inner = f.recorder.begin("daiyu.back.business-handler")
        f.now += 20_000_000L; f.recorder.complete(inner)
        f.now += 10_000_000L; f.recorder.complete(outer)
        val marker = f.recorder.begin("daiyu.chat.retained-compose-entry")
        f.now += 100_000_000L; f.recorder.complete(marker); f.end()
        val phases = f.rows.single().phases
        assertEquals(30_000_000L, phases.first { it.phase == BackPerformanceRecorder.Phase.CALLBACK }.total)
        assertEquals(20_000_000L, phases.first { it.phase == BackPerformanceRecorder.Phase.BUSINESS }.total)
        val entry = phases.first { it.phase == BackPerformanceRecorder.Phase.COMPOSE_ENTRY }
        assertEquals(1, entry.count); assertEquals(0L, entry.max)
        assertTrue(phases.size <= BackPerformanceRecorder.Phase.entries.size)
    }
    @Test fun timeoutKeepsAnInFlightSlowPhaseWithoutWaitingForCompletion() {
        val f = Fixture(); f.start(); f.recorder.commit(f.owner)
        val sample = f.recorder.begin("daiyu.back.business-handler")
        f.now += BackPerformanceRecorder.TAIL_NS; f.recorder.checkpoint()
        val phase = f.rows.single().phases.single()
        assertEquals(0, phase.count); assertEquals(0L, phase.total)
        assertEquals(1, phase.inFlight)
        assertEquals(BackPerformanceRecorder.TAIL_NS, phase.inFlightMaxObserved)
        assertNull(f.recorder.token())
        f.now += 250_000_000L; f.recorder.complete(sample)
        assertEquals(1, f.rows.size)
        f.start()
        val hardSample = f.recorder.begin("daiyu.back.prepare")
        f.now += BackPerformanceRecorder.HARD_NS + 1
        f.recorder.complete(hardSample)
        val hard = f.rows.last()
        assertEquals(BackPerformanceRecorder.End.HARD_LIMIT, hard.reason)
        assertEquals(0, hard.phases.single().count)
        assertEquals(BackPerformanceRecorder.HARD_NS, hard.phases.single().inFlightMaxObserved)
    }
    @Test fun triggeringOverlapIsSavedButNotAttributedAndCompletedOldFramesAreIgnored() {
        val f = Fixture(); f.start()
        f.frame(40_000_000L, f.now - 10_000_000L)
        f.frame(10_000_000L, f.now - 20_000_000L)
        f.end()
        val row = f.rows.single()
        assertEquals(0, row.frames); assertEquals(0, row.slow); assertEquals(0L, row.max)
        assertEquals(1, row.overlapFrames); assertEquals(1, row.overlapSlow)
        assertTrue(row.worst.single().overlapsStart)
    }
    @Test fun pendingPhaseSlotsAreBoundedAndCompletedSamplesAreNotCountedTwice() {
        val f = Fixture(); f.start()
        val samples = List(8) { f.recorder.begin("daiyu.back.prepare") }
        assertTrue(samples.all { it != null })
        assertNull(f.recorder.begin("daiyu.back.prepare"))
        f.now += 20_000_000L
        f.recorder.complete(samples[3]); f.recorder.complete(samples[3]); f.end()
        val phase = f.rows.single().phases.single()
        assertEquals(1, phase.count); assertEquals(7, phase.inFlight)
    }
    @Test fun chatRefreshAndPublishHaveSeparateInclusiveCountsWithoutNewFields() {
        assertEquals(17, BackPerformanceRecorder.Phase.entries.size)
        val f = Fixture(); f.start(); f.frame()
        val refresh = f.recorder.begin("daiyu.chat.refresh")
        f.now += 2_000_000L
        val publish = f.recorder.begin("daiyu.chat.publish")
        f.now += 3_000_000L
        f.recorder.complete(publish)
        f.now += 1_000_000L
        f.recorder.complete(refresh)
        f.end()
        val phases = f.rows.single().phases
        assertEquals(2, phases.size)
        val refreshed = phases.single { it.phase == BackPerformanceRecorder.Phase.CHAT_REFRESH }
        val published = phases.single { it.phase == BackPerformanceRecorder.Phase.CHAT_PUBLISH }
        assertEquals(1, refreshed.count); assertEquals(6_000_000L, refreshed.total)
        assertEquals(1, published.count); assertEquals(3_000_000L, published.total)
        assertEquals(0, refreshed.inFlight); assertEquals(0, published.inFlight)
    }
    @Test fun coldRevisionPhaseIsWhitelistedAndNestedWithoutChangingRefreshPublishCounts() {
        val f = Fixture(); f.start(); f.frame()
        val refresh = f.recorder.begin("daiyu.chat.refresh")
        val revision = f.recorder.begin("daiyu.chat.action-revision")
        assertNotNull(revision)
        f.now += 4_000_000L; f.recorder.complete(revision)
        val publish = f.recorder.begin("daiyu.chat.publish")
        f.now += 1_000_000L; f.recorder.complete(publish)
        f.recorder.complete(refresh); f.end()
        val phases = f.rows.single().phases
        assertEquals(3, phases.size)
        assertEquals(4_000_000L, phases.single { it.phase == BackPerformanceRecorder.Phase.MESSAGE_ACTION_REVISION }.total)
        assertEquals(5_000_000L, phases.single { it.phase == BackPerformanceRecorder.Phase.CHAT_REFRESH }.total)
        assertTrue(phases.all { it.count == 1 && it.inFlight == 0 })
    }
    @Test fun helperPreservesReturnExceptionAndNonlocalReturnDespiteDiagnosticFailure() {
        for (name in listOf("daiyu.back.prepare", "daiyu.chat.refresh", "daiyu.chat.publish", "daiyu.chat.action-revision")) {
            var calls = 0
            assertEquals(42, synchronousTrace(name) { calls++; 42 })
            fun nonlocal(): Int { synchronousTrace(name) { calls++; return 7 } }
            assertEquals(7, nonlocal())
            val failure = IllegalStateException("not-exported")
            try { synchronousTrace(name) { calls++; throw failure }; fail() }
            catch (caught: IllegalStateException) { assertSame(failure, caught) }
            assertEquals(3, calls)
        }
        BackPerformance.safely { error("diagnostic-only") }
    }
}
