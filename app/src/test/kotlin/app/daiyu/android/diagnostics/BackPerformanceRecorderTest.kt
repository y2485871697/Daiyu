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
    @Test fun previewFailuresAndStalePixelsAreSavedEvenWithoutSlowFrames() {
        for (event in listOf(BackPerformanceRecorder.Event.PREVIEW_TARGET_MISSING,
            BackPerformanceRecorder.Event.PREVIEW_SOURCE_MISSING, BackPerformanceRecorder.Event.PREVIEW_TARGET_STALE,
            BackPerformanceRecorder.Event.PREVIEW_SOURCE_STALE)) {
            val f = Fixture(); val token = f.start()
            f.recorder.mark(BackPerformanceRecorder.Event.PREVIEW_AFTER_RESUME, token)
            f.recorder.mark(event, token, 3)
            f.recorder.commit(f.owner); f.recorder.route(f.owner); f.end()
            val row = f.rows.single()
            assertEquals(0, row.frames); assertEquals(0, row.slow)
            assertTrue(row.commitAttempted); assertTrue(row.routeChanged)
            assertEquals(event, row.timeline[1].event)
            assertEquals(3, row.timeline[1].count)
        }
        val healthy = Fixture(); val token = healthy.start()
        healthy.recorder.mark(BackPerformanceRecorder.Event.PREVIEW_AFTER_RESUME, token)
        healthy.recorder.mark(BackPerformanceRecorder.Event.PREVIEW_READY, token)
        healthy.recorder.commit(healthy.owner); healthy.recorder.route(healthy.owner)
        healthy.end(); assertTrue(healthy.rows.isEmpty())
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
        assertEquals(23, BackPerformanceRecorder.Phase.entries.size)
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
        val match = f.recorder.begin("daiyu.chat.action-match")
        assertNotNull(match)
        f.now += 2_000_000L; f.recorder.complete(match)
        f.recorder.complete(refresh); f.end()
        val phases = f.rows.single().phases
        assertEquals(4, phases.size)
        assertEquals(4_000_000L, phases.single { it.phase == BackPerformanceRecorder.Phase.MESSAGE_ACTION_REVISION }.total)
        assertEquals(2_000_000L, phases.single { it.phase == BackPerformanceRecorder.Phase.MESSAGE_ACTION_MATCH }.total)
        assertEquals(7_000_000L, phases.single { it.phase == BackPerformanceRecorder.Phase.CHAT_REFRESH }.total)
        assertTrue(phases.all { it.count == 1 && it.inFlight == 0 })
    }
    @Test fun helperPreservesReturnExceptionAndNonlocalReturnDespiteDiagnosticFailure() {
        for (name in listOf("daiyu.back.prepare", "daiyu.back.layers.transition", "daiyu.back.layers.frame",
            "daiyu.back.layers.find", "daiyu.chat.refresh", "daiyu.chat.publish", "daiyu.chat.action-revision", "daiyu.chat.action-match")) {
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
    @Test fun timelineAndWorstFrameOffsetsShareOneMonotonicOrigin() {
        val f = Fixture(); val token = f.start()
        f.now += 2_000_000L
        f.recorder.mark(BackPerformanceRecorder.Event.RELEASE, token)
        f.now += 3_000_000L; f.recorder.commit(f.owner)
        f.now += 1_000_000L; f.recorder.route(f.owner)
        f.recorder.mark(BackPerformanceRecorder.Event.MOTION_ADOPTED, token)
        f.recorder.reset(f.owner)
        f.frame(vsync = 1_004_000_000L)
        f.frame(40_000_000L, 999_000_000L)
        f.end()
        val row = f.rows.single()
        assertEquals(listOf(BackPerformanceRecorder.Event.RELEASE, BackPerformanceRecorder.Event.COMMIT,
            BackPerformanceRecorder.Event.ROUTE, BackPerformanceRecorder.Event.MOTION_ADOPTED,
            BackPerformanceRecorder.Event.RESET), row.timeline.map { it.event })
        assertEquals(listOf(2_000_000L, 5_000_000L, 6_000_000L, 6_000_000L, 6_000_000L), row.timeline.map { it.offset })
        assertEquals(listOf(-1_000_000L, 4_000_000L), row.worst.map { it.vsyncOffset })
        assertTrue(row.worst.first().overlapsStart)
        assertFalse(row.worst.last().overlapsStart)
    }

    @Test fun captureLaunchPrefixAndSuspendedWallKeepIndependentElapsedSlices() {
        val f = Fixture(); val token = f.start()
        val prefix = f.recorder.begin("daiyu.back.capture-launch", token)
        f.now += 1_000_000L
        val wall = f.recorder.begin("daiyu.back.capture-wall", token)
        f.now += 2_000_000L; f.recorder.complete(prefix)
        f.now += 40_000_000L
        f.recorder.completeCapture(wall, BackPerformanceRecorder.CaptureResult.STORED)
        f.end()
        val row = f.rows.single()
        assertEquals(listOf(0L, 1_000_000L), row.captures.map { it.offset })
        assertEquals(listOf(3_000_000L, 42_000_000L), row.captures.map { it.duration })
        assertEquals(listOf(BackPerformanceRecorder.CaptureResult.COMPLETE, BackPerformanceRecorder.CaptureResult.STORED), row.captures.map { it.result })
        assertEquals(2, row.phases.size)
    }

    @Test fun synchronousCaptureCanFinishBeforeLaunchReturnsAndOutcomesRemainTyped() {
        val f = Fixture(); val token = f.start(); f.frame()
        for (outcome in listOf(BackPerformanceRecorder.CaptureResult.STORED, BackPerformanceRecorder.CaptureResult.DISCARDED,
            BackPerformanceRecorder.CaptureResult.CANCELLED, BackPerformanceRecorder.CaptureResult.FAILED)) {
            val prefix = f.recorder.begin("daiyu.back.capture-launch", token)
            val wall = f.recorder.begin("daiyu.back.capture-wall", token)
            f.now += 1000L; f.recorder.completeCapture(wall, outcome)
            f.recorder.completeCapture(wall, outcome)
            f.now += 1000L; f.recorder.complete(prefix)
        }
        f.end()
        val captures = f.rows.single().captures
        assertEquals(8, captures.size)
        assertTrue(captures.filter { it.phase == BackPerformanceRecorder.Phase.CAPTURE_LAUNCH }.all { it.duration == 2000L })
        assertEquals(4, captures.count { it.phase == BackPerformanceRecorder.Phase.CAPTURE_WALL && it.duration == 1000L })
    }

    @Test fun timelineAndCaptureBoundsAreExplicitAndInFlightWallIsNotCompleted() {
        val f = Fixture(); val token = f.start(); f.frame()
        repeat(80) { f.recorder.mark(BackPerformanceRecorder.Event.OBSERVED_CAPTURE_IN_FLIGHT, token, Int.MAX_VALUE) }
        repeat(30) {
            val sample = f.recorder.begin("daiyu.back.capture-launch", token)
            f.now += 1000L; f.recorder.complete(sample)
        }
        f.recorder.begin("daiyu.back.capture-wall", token)
        f.now += 20_000_000L; f.end()
        val row = f.rows.single()
        assertEquals(BackPerformanceRecorder.MAX_TIMELINE, row.timeline.size)
        assertEquals(48, row.timelineDropped)
        assertTrue(row.timeline.all { it.count == BackPerformanceRecorder.MAX_COUNT })
        assertEquals(BackPerformanceRecorder.MAX_CAPTURE_TIMINGS, row.captures.size)
        assertEquals(15, row.capturesDropped)
        assertEquals(1, row.phases.single { it.phase == BackPerformanceRecorder.Phase.CAPTURE_WALL }.inFlight)

        val next = f.start()
        f.recorder.begin("daiyu.back.capture-wall", next)
        f.recorder.commit(f.owner)
        f.now += BackPerformanceRecorder.TAIL_NS; f.recorder.checkpoint()
        val pending = f.rows.last().captures.single()
        assertEquals(BackPerformanceRecorder.CaptureResult.IN_FLIGHT, pending.result)
        assertEquals(BackPerformanceRecorder.TAIL_NS, pending.duration)

        val saturated = f.start(); f.frame()
        repeat(BackPerformanceRecorder.MAX_CAPTURE_TIMINGS + BackPerformanceRecorder.MAX_COUNT) {
            f.recorder.complete(f.recorder.begin("daiyu.back.capture-launch", saturated))
        }
        repeat(8) {
            f.recorder.begin("daiyu.back.capture-launch", saturated)
            f.recorder.begin("daiyu.back.capture-wall", saturated)
        }
        f.end()
        assertEquals(BackPerformanceRecorder.MAX_COUNT, f.rows.last().capturesDropped)
    }

    @Test fun oldAndNullCaptureSourcesAndTimelineCannotRebindAfterNewGestureOrClear() {
        val f = Fixture(); val old = f.start(); f.frame()
        val oldWall = f.recorder.begin("daiyu.back.capture-wall", old)
        f.now += 1_000_000L; val next = f.start(); f.frame()
        f.recorder.completeCapture(oldWall, BackPerformanceRecorder.CaptureResult.STORED)
        f.recorder.mark(BackPerformanceRecorder.Event.MOTION_FINISHED, old)
        f.recorder.mark(BackPerformanceRecorder.Event.MOTION_ADOPTED, null)
        assertNull(f.recorder.begin("daiyu.back.capture-launch", old))
        assertNull(f.recorder.begin("daiyu.back.capture-wall", null))
        f.recorder.mark(BackPerformanceRecorder.Event.OBSERVED_CAPTURE_IN_FLIGHT, next, 1)
        f.end()
        val row = f.rows.last()
        assertTrue(row.captures.isEmpty()); assertTrue(row.phases.isEmpty())
        assertEquals(listOf(BackPerformanceRecorder.Event.OBSERVED_CAPTURE_IN_FLIGHT), row.timeline.map { it.event })
        f.start(); f.frame(); f.epoch++
        f.recorder.mark(BackPerformanceRecorder.Event.MOTION_ADOPTED, next)
        f.end(); assertEquals(2, f.rows.size)
    }


    @Test fun nativeTraceEventsPairTheirStartStateAcrossSwitchesAndThreads() {
        val main = CompositionTraceEvents(); val worker = CompositionTraceEvents()
        assertFalse(main.end()); assertFalse(main.pending)
        assertFalse(main.start(false)); assertTrue(main.pending)
        assertTrue(main.start(true)); assertTrue(worker.start(true))
        assertTrue(main.end()); assertTrue(main.pending)
        assertFalse(main.end()); assertFalse(main.pending)
        assertTrue(worker.pending); assertTrue(worker.end()); assertFalse(worker.pending)
    }

    @Test fun nativeTraceDepthOverflowDrainsWithoutClosingTrackedParents() {
        val events = CompositionTraceEvents()
        repeat(BackCompositionSamples.MAX_DEPTH) { assertTrue(events.start(true)) }
        repeat(4) { assertFalse(events.start(true)) }
        repeat(4) { assertFalse(events.end()); assertTrue(events.pending) }
        repeat(BackCompositionSamples.MAX_DEPTH) { assertTrue(events.end()) }
        assertFalse(events.pending); assertFalse(events.end())
    }

    @Test fun expiredCompositionFlagsCannotMutateHardOrTailWindowsOrTheNextWindow() {
        for (tail in listOf(false, true)) {
            val f = Fixture(); val old = f.start(); f.frame()
            if (tail) f.recorder.commit(f.owner)
            f.now += if (tail) BackPerformanceRecorder.TAIL_NS else BackPerformanceRecorder.HARD_NS
            assertNull(f.recorder.compositionToken())
            f.recorder.compositionObserved(old); f.recorder.compositionLimited(old)
            f.recorder.checkpoint()
            assertFalse(f.rows.single().composeObserved); assertFalse(f.rows.single().composeLimited)
            f.start(); f.frame()
            f.recorder.compositionObserved(old); f.recorder.compositionLimited(old)
            f.end()
            assertFalse(f.rows.last().composeObserved); assertFalse(f.rows.last().composeLimited)
        }
    }

    private fun Fixture.compositions() = BackCompositionSamples(
        clock = { now }, observed = recorder::compositionObserved,
        limited = recorder::compositionLimited, completed = recorder::composition,
    )

    @Test fun compositionCallbacksOutsideWindowsDoNotReadClockOrSave() {
        var reads = 0
        val samples = BackCompositionSamples({ reads++; 1L }, { fail() }, { fail() }, { _, _, _, _, _ -> fail() })
        assertFalse(samples.enabled(null))
        samples.start(null, "not-saved"); samples.end(null)
        assertEquals(0, reads)
        val f = Fixture()
        assertNull(f.recorder.compositionToken())
    }

    @Test fun aSlowCompositionAloneSavesTheWindowWithoutSyntheticFramesOrPhases() {
        val f = Fixture(); val token = f.start(); val samples = f.compositions()
        samples.start(token, "app.daiyu.android.SlowComposition (Fixture.kt:1)")
        f.now += 20_000_000L; samples.end(token); f.end()
        val row = f.rows.single()
        assertEquals(0, row.frames); assertTrue(row.phases.isEmpty())
        assertTrue(row.composeObserved); assertFalse(row.composeLimited)
        assertEquals(20_000_000L, row.compositions.single().self)
    }

    @Test fun nestedCompositionSelfTimeExcludesChildrenAndRetainsStaticNames() {
        val f = Fixture(); val token = f.start(); val samples = f.compositions()
        samples.end(token) // End of a parent entered before the window is deliberately unattributed.
        samples.start(token, "app.daiyu.android.Parent (Fixture.kt:1)")
        f.now += 1_000_000L
        samples.start(token, "app.daiyu.android.Child (Fixture.kt:2)")
        f.now += 20_000_000L; samples.end(token)
        f.now += 3_000_000L; samples.end(token); f.end()
        val row = f.rows.single()
        assertTrue(row.composeObserved); assertFalse(row.composeLimited)
        assertEquals(listOf(20_000_000L, 4_000_000L), row.compositions.map { it.self })
        assertEquals(listOf(20_000_000L, 24_000_000L), row.compositions.map { it.inclusive })
        assertEquals(listOf(1_000_000L, 0L), row.compositions.map { it.offset })
        assertTrue(row.compositions.first().function.contains("Child"))
    }

    @Test fun compositionWindowsChangingInsideParentNeverRebindTheOldSpan() {
        val f = Fixture(); val first = f.start(); val samples = f.compositions()
        samples.start(first, "old-parent"); f.now += 2_000_000L
        f.frame(); val second = f.start()
        samples.end(second)
        samples.start(second, "new-child"); f.now += 20_000_000L; samples.end(second)
        samples.end(second); f.end()
        assertTrue(f.rows.first().compositions.isEmpty())
        assertEquals("new-child", f.rows.last().compositions.single().function)
        assertEquals(20_000_000L, f.rows.last().compositions.single().inclusive)
    }

    @Test fun compositionDeadlineAndClearDiscardUnfinishedSpansWithoutCountingCompletion() {
        val f = Fixture(); val token = f.start(); val samples = f.compositions(); f.frame()
        samples.start(token, "unfinished"); f.recorder.commit(f.owner)
        f.now += BackPerformanceRecorder.TAIL_NS
        assertNull(f.recorder.compositionToken())
        samples.end(f.recorder.compositionToken()); f.recorder.checkpoint()
        assertTrue(f.rows.single().compositions.isEmpty())
        val next = f.start(); samples.start(next, "cleared"); f.epoch++
        assertNull(f.recorder.compositionToken()); samples.end(f.recorder.compositionToken())
        f.end(); assertEquals(1, f.rows.size)
    }

    @Test fun compositionDepthAndCallbackBudgetsStopUntilAnotherWindow() {
        val f = Fixture(); val samples = f.compositions(); var token = f.start(); f.frame()
        repeat(BackCompositionSamples.MAX_DEPTH + 1) { samples.start(token, "nested") }
        assertFalse(samples.enabled(token)); samples.end(token); f.end()
        assertTrue(f.rows.single().composeLimited); assertTrue(f.rows.single().compositions.isEmpty())
        token = f.start(); f.frame()
        repeat(BackCompositionSamples.MAX_CALLS) { samples.start(token, "small"); samples.end(token) }
        samples.start(token, "beyond-budget")
        assertFalse(samples.enabled(token)); f.end()
        assertTrue(f.rows.last().composeLimited)
        assertTrue(samples.enabled(f.start()))
    }

    @Test fun onlyFourCompletedSelfSamplesAreSavedAndNamesAreBounded() {
        val f = Fixture(); val token = f.start(); val samples = f.compositions(); f.frame()
        samples.start(token, "below-threshold"); f.now += 999_999L; samples.end(token)
        repeat(6) { index ->
            samples.start(token, "function-$index-" + "x".repeat(500))
            f.now += (index + 1) * 1_000_000L; samples.end(token)
        }
        f.end()
        val rows = f.rows.single().compositions
        assertEquals(4, rows.size)
        assertEquals(listOf(6_000_000L, 5_000_000L, 4_000_000L, 3_000_000L), rows.map { it.self })
        assertTrue(rows.all { it.function.length == BackPerformanceRecorder.MAX_COMPOSE_NAME })
    }

    @Test fun staleOrOutOfWindowCompositionSamplesCannotEnterNextWindow() {
        val f = Fixture(); val old = f.start(); val began = f.now
        f.now += 1_000_000L; val token = f.start(); f.frame()
        f.recorder.composition(old, "stale", began, 20_000_000L, 20_000_000L)
        f.recorder.composition(token, "before-start", began, 20_000_000L, 20_000_000L)
        f.recorder.composition(token, "invalid-self", f.now, 1L, 20_000_000L)
        f.recorder.composition(token, "beyond-deadline", f.now, BackPerformanceRecorder.HARD_NS + 1, 20_000_000L)
        f.end(); assertTrue(f.rows.single().compositions.isEmpty())
    }
    @Test fun longAnonymousCompilerNamesKeepTheDistinctSourceLocationsWithinTheSameBudget() {
        val f = Fixture(); val token = f.start(); f.frame()
        val prefix = "app.daiyu.android.MainActivity.onCreate." + "<anonymous>.".repeat(12)
        val infos = listOf(2291, 2304, 2313, 2314).map { "$prefix (MainActivity.kt:$it)" }
        infos.forEachIndexed { index, info ->
            f.recorder.composition(token, info, f.now, (index + 1) * 2_000_000L, (index + 1) * 2_000_000L)
        }
        f.end()
        val names = f.rows.single().compositions.map { it.function }
        assertEquals(infos.reversed().map { it.takeLast(BackPerformanceRecorder.MAX_COMPOSE_NAME) }, names)
        assertEquals(4, names.distinct().size)
        assertTrue(names.all { it.length == BackPerformanceRecorder.MAX_COMPOSE_NAME })
        assertTrue(names.first().endsWith("(MainActivity.kt:2314)"))
    }

}
