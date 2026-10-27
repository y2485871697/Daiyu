package app.daiyu.android.model.reconnect

import app.daiyu.android.domain.ModelFailure
import org.junit.Assert.*
import org.junit.Test

/** Adapted retained f001fb82 ModelErrorReconnectPauseTest scenarios. Deterministic time only;
 * no sleeps, providers, original host, model requests or local tools are executed. */
class ModelErrorReconnectTest {
    private class Clock : ReconnectTiming {
        data class Task(val at: Long, val action: () -> Unit, var cancelled: Boolean = false)
        var now = 0L
        val tasks = mutableListOf<Task>()
        override fun nowMs() = now
        override fun schedule(delayMs: Long, action: () -> Unit): AutoCloseable {
            val task = Task(now + delayMs, action)
            tasks.add(task)
            return AutoCloseable { task.cancelled = true }
        }
        fun advance(ms: Long) {
            val end = now + ms
            while (true) {
                val task = tasks.filter { !it.cancelled && it.at <= end }.minByOrNull { it.at } ?: break
                tasks.remove(task); now = task.at; task.action()
            }
            now = end
        }
    }
    private class Control : ReconnectControl {
        var paused = false
        var stopped = false
        val stops = linkedSetOf<() -> Unit>()
        val pauses = linkedSetOf<(Boolean) -> Unit>()
        override fun registerStop(wakeBeforeCleanup: Boolean, action: () -> Unit): AutoCloseable {
            assertTrue(wakeBeforeCleanup)
            stops.add(action)
            if (stopped) action()
            return AutoCloseable { stops.remove(action) }
        }
        override fun observePause(observer: (Boolean) -> Unit): AutoCloseable {
            pauses.add(observer); observer(paused)
            return AutoCloseable { pauses.remove(observer) }
        }
        fun pause() { paused = true; pauses.toList().forEach { it(true) } }
        fun resume() { paused = false; pauses.toList().forEach { it(false) } }
        fun stop() { stopped = true; stops.toList().forEach { it() } }
    }
    private fun state(clock: Clock, events: MutableList<ReconnectChanged>, window: Long? = 30_000L) =
        ModelErrorReconnect(1, window, clock, events::add, ModelFailure.NETWORK)

    @Test fun stalePauseRevisionCannotRestartBudgetOrCancelNewTransport() {
        val clock = Clock(); val events = mutableListOf<ReconnectChanged>()
        val reconnect = state(clock, events, 1_000L)
        reconnect.start()
        reconnect.setPaused(false, 0)
        clock.advance(200)
        reconnect.setPaused(true, 1)
        reconnect.setPaused(false, 0)
        clock.advance(5_000)
        reconnect.check()
        assertEquals(800L, reconnect.remainingMs())
        reconnect.setPaused(false, 2)
        var cancelled = 0
        val scope = ReconnectTransportScope { cancelled++ }
        reconnect.attach(scope)
        reconnect.setPaused(true, 1)
        reconnect.setPaused(true, 2)
        assertEquals(0, cancelled)
        clock.advance(799)
        reconnect.check()
        assertEquals(1L, reconnect.remainingMs())
        clock.advance(1)
        assertTrue(reconnect.expired)
        assertEquals(1, cancelled)
    }

    @Test fun pauseFreezesTotalBudgetAndResumeUpdatesSameSegmentAndRejectsStaleTick() {
        val clock = Clock(); val control = Control(); val events = mutableListOf<ReconnectChanged>()
        val reconnect = state(clock, events)
        val binding = reconnect.bind(control)
        reconnect.start(); clock.advance(3_000)
        val stale = clock.tasks.first { !it.cancelled }.action
        control.pause()
        assertEquals("stopped", events.last().status); assertEquals(3_000L, events.last().elapsedMs)
        val oldId = events.last().reconnectId; val count = events.size
        clock.advance(120_000); reconnect.check()
        assertEquals(27_000L, reconnect.remainingMs()); assertEquals(count, events.size)
        assertFalse(control.stopped)
        control.resume()
        assertEquals("running", events.last().status); assertEquals(oldId, events.last().reconnectId)
        val resumedCount = events.size; stale(); assertEquals(resumedCount, events.size)
        clock.advance(26_000); reconnect.check(); assertEquals(1_000L, reconnect.remainingMs())
        clock.advance(1_000)
        assertThrows(ReconnectDeadlineException::class.java) { reconnect.check() }
        reconnect.finish("failed"); binding.close()
    }

    @Test fun actualErrorChangesPublishImmediatelyWithoutResettingTheBudgetOrChangingTheId() {
        val clock = Clock(); val events = mutableListOf<ReconnectChanged>()
        val first = app.daiyu.android.domain.ModelRequestDiagnostic(502)
        val reconnect = ModelErrorReconnect(0, 30_000L, clock, events::add, ModelFailure.SERVER, first)
        reconnect.start()
        assertEquals(first, events.single().diagnostic)
        val id = events.single().reconnectId
        clock.advance(250)
        reconnect.updateReason(ModelFailure.SERVER, app.daiyu.android.domain.ModelRequestDiagnostic(503))
        assertEquals(503, events.last().diagnostic!!.httpStatus)
        assertEquals(250L, events.last().elapsedMs)
        reconnect.setPaused(true)
        val count = events.size
        clock.advance(120_000)
        reconnect.updateReason(ModelFailure.RATE_LIMIT, app.daiyu.android.domain.ModelRequestDiagnostic(429))
        assertEquals(count + 1, events.size)
        assertEquals("stopped", events.last().status)
        assertEquals(250L, events.last().elapsedMs)
        reconnect.setPaused(false)
        assertEquals(429, events.last().diagnostic!!.httpStatus)
        assertEquals("running", events.last().status)
        assertEquals(29_750L, reconnect.remainingMs())
        assertTrue(events.all { it.reconnectId == id && it.round == 0 })
        reconnect.finish("failed")
        val terminal = events.last(); reconnect.updateReason(ModelFailure.NETWORK)
        assertEquals(terminal, events.last())
    }

    @Test fun retainedFiveTotalWindowsIncludeInflightTimeAndNeverResetOnNewFailureReason() {
        for (policy in ReconnectDurationPreset.entries) {
            val clock = Clock(); val events = mutableListOf<ReconnectChanged>()
            // Original request time is outside the reconnect window; creation is first error.
            clock.advance(7_000)
            val reconnect = state(clock, events, policy.windowMillis)
            reconnect.start()
            if (policy == ReconnectDurationPreset.NONE) {
                assertEquals(listOf("failed"), events.map { it.status }); assertTrue(clock.tasks.isEmpty())
            } else if (policy == ReconnectDurationPreset.CONTINUOUS) {
                clock.advance(400_000); reconnect.check(); assertNull(reconnect.remainingMs())
                reconnect.finish("succeeded"); assertEquals("succeeded", events.last().status)
            } else {
                val window = policy.windowMillis!!
                clock.advance(window - 1_000)
                reconnect.updateReason(ModelFailure.TIMEOUT)
                assertEquals(1_000L, reconnect.remainingMs())
                clock.advance(1_000)
                assertThrows(ReconnectDeadlineException::class.java) { reconnect.finish("succeeded") }
                assertEquals(ModelFailure.TIMEOUT,
                    assertThrows(ReconnectDeadlineException::class.java) { reconnect.check() }.lastReason)
                reconnect.finish("failed")
                assertTrue(events.none { it.status == "succeeded" })
                assertEquals((0 until window step 1_000).toList() + (window - 1_000),
                    events.filter { it.status == "running" }.map { it.elapsedMs })
            }
        }
    }

    @Test fun deadlineCancelsOnlyOriginalTransportAndLateSuccessCannotWin() {
        val clock = Clock(); val control = Control(); val events = mutableListOf<ReconnectChanged>()
        val reconnect = state(clock, events); val binding = reconnect.bind(control)
        var transportStops = 0
        reconnect.start(); reconnect.attach { transportStops++ }
        clock.advance(30_000)
        assertTrue(reconnect.expired); assertEquals(1, transportStops); assertFalse(control.stopped)
        assertThrows(ReconnectDeadlineException::class.java) { reconnect.check() }
        assertThrows(ReconnectDeadlineException::class.java) { reconnect.finish("succeeded") }
        reconnect.finish("failed"); binding.close()
        assertEquals("failed", events.last().status)
    }

    @Test fun repeatedPauseFreezesWithoutTouchingOtherOwnersAndUnbindRemovesObservers() {
        val clock = Clock(); val control = Control(); val events = mutableListOf<ReconnectChanged>()
        val reconnect = state(clock, events, null); val binding = reconnect.bind(control)
        reconnect.start()
        repeat(2) {
            clock.advance(1_000); control.pause(); val count = events.size
            control.pause(); clock.advance(60_000); assertEquals(count, events.size)
            assertFalse(control.stopped); control.resume()
        }
        assertEquals(1, events.map { it.reconnectId }.distinct().size)
        assertEquals(2_000L, events.last().elapsedMs)
        reconnect.finish("succeeded"); binding.close()
        assertTrue(control.stops.isEmpty()); assertTrue(control.pauses.isEmpty())
        val count = events.size; control.pause(); control.resume(); clock.advance(60_000)
        assertEquals(count, events.size)
    }

    @Test fun alreadyPausedCannotStartTimerAcceptSuccessOrUseAttachedTransport() {
        val clock = Clock(); val control = Control().also { it.pause() }; val events = mutableListOf<ReconnectChanged>()
        val reconnect = state(clock, events); val binding = reconnect.bind(control)
        reconnect.start(); var stops = 0; reconnect.attach { stops++ }; assertEquals(1, stops)
        val count = events.size; clock.advance(60_000); assertEquals(count, events.size)
        reconnect.finish("succeeded"); control.resume()
        assertEquals("stopped", events.last().status)
        assertTrue(events.none { it.status in setOf("running", "succeeded") }); binding.close()
    }

    @Test fun stopWhilePausedIsTerminalAndLateAttachmentCannotResurrectRecovery() {
        val clock = Clock(); val control = Control(); val events = mutableListOf<ReconnectChanged>()
        val reconnect = state(clock, events); val binding = reconnect.bind(control)
        reconnect.start(); control.pause(); control.stop(); val count = events.size
        control.resume(); clock.advance(60_000); reconnect.start(); reconnect.finish("succeeded")
        assertEquals(count, events.size); assertEquals("stopped", events.last().status)
        var stops = 0; reconnect.attach { stops++ }; assertEquals(1, stops); binding.close()
    }

    @Test fun fastResumeCancelsOldAttemptScopeAndOldDetachCannotDetachNewScope() {
        val clock = Clock(); val control = Control(); val events = mutableListOf<ReconnectChanged>()
        val reconnect = state(clock, events); val binding = reconnect.bind(control)
        var oldStops = 0; var newStops = 0
        val old = ReconnectTransportScope { oldStops++ }; val fresh = ReconnectTransportScope { newStops++ }
        reconnect.start(); reconnect.attach(old); control.pause(); control.resume()
        assertEquals(1, oldStops)
        reconnect.attach(fresh); reconnect.detach(old); clock.advance(30_000)
        assertEquals(1, oldStops); assertEquals(1, newStops); assertFalse(control.stopped)
        reconnect.finish("failed"); binding.close()
    }

    @Test fun tickObserverFailureCancelsTransportAndRethrowsOnRequestThreadNotAsNetworkRetry() {
        val clock = Clock(); var fail = false; var stops = 0
        val expected = AssertionError("fixture observer")
        val reconnect = ModelErrorReconnect(1, 30_000L, clock,
            { if (fail) throw expected }, ModelFailure.NETWORK)
        reconnect.start(); reconnect.attach { stops++ }; fail = true; clock.advance(1_000)
        assertEquals(1, stops)
        assertSame(expected, assertThrows(AssertionError::class.java) { reconnect.check() })
        fail = false; reconnect.finish("failed")
    }
}
