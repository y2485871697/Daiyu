package app.daiyu.android.tasksurface

import org.junit.Assert.*
import org.junit.Test

class TaskSurfaceRouteAdapterTest {
    private class Backends {
        var foregroundLease = 0
        var backgroundSession = 0
        var globalAction = 0
        var screenshot = 0
        var launch = 0
        fun foreground(target: TaskSurfaceSelfTarget?): String {
            foregroundLease++; globalAction++; screenshot++; launch++
            return if (target == null) "external-foreground" else "self-foreground"
        }
        fun background(): String { backgroundSession++; return "background" }
        fun assertZero() {
            assertEquals(0, foregroundLease); assertEquals(0, backgroundSession)
            assertEquals(0, globalAction); assertEquals(0, screenshot); assertEquals(0, launch)
        }
    }
    private val own = "app.daiyu.android"
    private val external = "example.target"
    private fun run(preference: TaskSurfacePreference) =
        TaskSurfaceRunDecision(Any(), TaskSurfaceSettings.Available(preference), own)
    private fun request(run: TaskSurfaceRunDecision, supported: Boolean = false, target: String = external) =
        TaskSurfaceRouteAdapter.Request(run.owner, target, supported)
    private fun prepare(adapter: TaskSurfaceRouteAdapter, request: TaskSurfaceRouteAdapter.Request, backends: Backends) =
        adapter.prepare(request, backends::foreground, backends::background)

    @Test fun unsupportedBackgroundHasZeroCallsToEitherBackendAndEveryForegroundFallback() {
        val run = run(TaskSurfacePreference.BACKGROUND)
        val adapter = TaskSurfaceRouteAdapter(run) { true }
        val backends = Backends()
        repeat(5) {
            val result = prepare(adapter, request(run), backends) as TaskSurfaceRouteAdapter.Preparation.NotPrepared
            assertEquals("TASK_SURFACE_BACKGROUND_UNSUPPORTED", (result.decision as TaskSurfaceDecision.NotExecuted).code)
        }
        backends.assertZero()
    }

    @Test fun selfRoutesAllPreferencesWithoutQuestionOrBackgroundAndTokenTracksAdmission() {
        TaskSurfacePreference.entries.forEach { preference ->
            val run = run(preference)
            var current = true
            val adapter = TaskSurfaceRouteAdapter(run) { current }
            val selfRequest = request(run, true, own)
            val backends = Backends()
            assertNull(adapter.askFromUser(selfRequest))
            val ready = adapter.decision(selfRequest) as TaskSurfaceDecision.Ready
            val token = checkNotNull(ready.selfTarget)
            assertTrue(token.isCurrent(run.owner, own))
            prepare(adapter, selfRequest, backends)
            assertEquals(1, backends.foregroundLease)
            assertEquals(0, backends.backgroundSession)
            current = false
            assertFalse(token.isCurrent(run.owner, own))
            prepare(adapter, selfRequest, backends)
            assertEquals(1, backends.foregroundLease)
            if (preference == TaskSurfacePreference.ASK) {
                current = true
                assertSame(TaskSurfaceDecision.AskRequired, adapter.decision(request(run, true)))
            }
        }
    }

    @Test fun askSelectedUnsupportedBackgroundAlsoHasZeroBackendCalls() {
        val run = run(TaskSurfacePreference.ASK)
        val adapter = TaskSurfaceRouteAdapter(run) { true }
        val request = request(run)
        val question = checkNotNull(adapter.askFromUser(request))
        assertTrue(adapter.answerFromUser(request, question, TaskSurface.BACKGROUND))
        val backends = Backends()
        repeat(3) {
            assertTrue(prepare(adapter, request, backends) is TaskSurfaceRouteAdapter.Preparation.NotPrepared)
            assertNull(adapter.askFromUser(request))
        }
        backends.assertZero()
    }

    @Test fun waitingPendingAndCancelledAskNeverCallBackendOrReissueQuestion() {
        val run = run(TaskSurfacePreference.ASK)
        val adapter = TaskSurfaceRouteAdapter(run) { true }
        val request = request(run, true)
        val backends = Backends()
        assertTrue(prepare(adapter, request, backends) is TaskSurfaceRouteAdapter.Preparation.NotPrepared)
        val question = checkNotNull(adapter.askFromUser(request))
        assertNull(adapter.askFromUser(request))
        assertTrue(prepare(adapter, request, backends) is TaskSurfaceRouteAdapter.Preparation.NotPrepared)
        assertTrue(adapter.answerFromUser(request, question, null))
        repeat(3) {
            assertTrue(prepare(adapter, request, backends) is TaskSurfaceRouteAdapter.Preparation.NotPrepared)
            assertNull(adapter.askFromUser(request))
        }
        backends.assertZero()
        assertTrue(prepare(adapter, this.request(run, true, own), backends) is TaskSurfaceRouteAdapter.Preparation.Prepared<*>)
        assertEquals(1, backends.foregroundLease)
        assertEquals(0, backends.backgroundSession)
        // Self's exception did not clear the cancelled external decision.
        assertTrue(prepare(adapter, request, backends) is TaskSurfaceRouteAdapter.Preparation.NotPrepared)
        assertEquals(1, backends.foregroundLease)
    }

    @Test fun wrongOwnerUnavailableSettingsEndedUnknownAndDrainFailBeforeAnyBackend() {
        val backends = Backends()
        val run = run(TaskSurfacePreference.FOREGROUND)
        var admission = true
        val adapter = TaskSurfaceRouteAdapter(run) { admission }
        assertTrue(prepare(adapter, TaskSurfaceRouteAdapter.Request(Any(), external, true), backends)
            is TaskSurfaceRouteAdapter.Preparation.NotPrepared)
        // The parent supplies the SAME existing Unknown/drain gate, never creates a new session.
        admission = false
        assertTrue(prepare(adapter, request(run, true), backends) is TaskSurfaceRouteAdapter.Preparation.NotPrepared)
        assertTrue(prepare(adapter, this.request(run, true, own), backends) is TaskSurfaceRouteAdapter.Preparation.NotPrepared)
        admission = true; run.end()
        assertTrue(prepare(adapter, request(run, true), backends) is TaskSurfaceRouteAdapter.Preparation.NotPrepared)
        val unavailable = TaskSurfaceRunDecision(Any(), TaskSurfaceSettings.Unavailable, own)
        assertTrue(prepare(TaskSurfaceRouteAdapter(unavailable) { true }, request(unavailable, true), backends)
            is TaskSurfaceRouteAdapter.Preparation.NotPrepared)
        backends.assertZero()
    }

    @Test fun ownerLossCancelsOriginalQuestionRatherThanRetryingIt() {
        val run = run(TaskSurfacePreference.ASK)
        var current = true
        val adapter = TaskSurfaceRouteAdapter(run) { current }
        val request = request(run, true)
        val question = checkNotNull(adapter.askFromUser(request))
        current = false
        assertFalse(adapter.answerFromUser(request, question, TaskSurface.FOREGROUND))
        current = true
        assertNull(adapter.askFromUser(request))
        val denied = adapter.decision(request) as TaskSurfaceDecision.NotExecuted
        assertEquals("TASK_SURFACE_ASK_CANCELLED", denied.code)
    }

    @Test fun acceptedRouteCallsOnlyItsBackendAndNeverFallsBackFromBackendRejectionOrException() {
        TaskSurfacePreference.entries.filter { it != TaskSurfacePreference.ASK }.forEach { preference ->
            val run = run(preference)
            val adapter = TaskSurfaceRouteAdapter(run) { true }
            var fg = 0; var bg = 0
            val result = adapter.prepare(request(run, true), { fg++; "NotExecuted" }, { bg++; "NotExecuted" })
            assertEquals("NotExecuted", (result as TaskSurfaceRouteAdapter.Preparation.Prepared<*>).value)
            assertEquals(if (preference == TaskSurfacePreference.FOREGROUND) 1 else 0, fg)
            assertEquals(if (preference == TaskSurfacePreference.BACKGROUND) 1 else 0, bg)
            try {
                adapter.prepare<Unit>(request(run, true), { fg++; throw IllegalStateException("backend_rejected") },
                    { bg++; throw IllegalStateException("backend_rejected") })
                fail("must propagate without fallback")
            } catch (_: IllegalStateException) { }
            assertEquals(if (preference == TaskSurfacePreference.FOREGROUND) 2 else 0, fg)
            assertEquals(if (preference == TaskSurfacePreference.BACKGROUND) 2 else 0, bg)
        }
    }
}
