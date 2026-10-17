package app.daiyu.android

import app.daiyu.android.domain.*
import app.daiyu.android.domain.tools.*
import app.daiyu.android.integration.tools.*
import app.daiyu.android.platform.actions.*
import app.daiyu.android.skills.SkillRunSession
import app.daiyu.android.tasksurface.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real retained location policy and central catalog; only clock transport is synthetic. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ControllerPlatformActionLocationTest {
    private class Persistence : TaskSurfacePersistence {
        var raw: String? = null
        var reads = 0
        override fun read(): String? { reads++; return raw }
        override fun write(record: String) { raw = record }
    }
    private class Backend : PlatformActionBackend {
        var reply: () -> ExecutionReceipt = { ExecutionReceipt.Confirmed("synthetic clock receipt") }
        var calls = 0
        override fun unavailable(kind: PlatformActionKind): String? = null
        override fun execute(action: PlatformAction, cancelled: () -> Boolean): ExecutionReceipt {
            calls++
            return reply()
        }
    }
    private val persistence = Persistence()
    private val store = TaskSurfaceStore(persistence)
    private val backend = Backend()
    private fun preview(target: String? = "example.clock") = ControllerPlatformActionPreparation(null, "clock draft",
        PlatformAction.parse(PlatformActionKind.TIMER, """{"seconds":15,"label":"fixture"}"""), backend,
        { store }, "app.daiyu.android", { target })
    private fun freeze(preview: ControllerPlatformActionPreparation): TaskSurfaceDecision {
        val worker = Executors.newSingleThreadExecutor()
        return try { worker.submit(Callable { preview.freezeLocationOnWorker() }).get(5, TimeUnit.SECONDS) }
        finally { worker.shutdownNow() }
    }
    @Test fun backgroundClockRejectsBeforeApprovalAndNeverFallsBackAfterSettingChanges() {
        assertTrue(store.save(TaskSurfacePreference.BACKGROUND))
        val preview = preview()
        assertEquals("TASK_SURFACE_BACKGROUND_UNSUPPORTED", (freeze(preview) as TaskSurfaceDecision.NotExecuted).code)
        assertThrows(IllegalStateException::class.java) { preview.approve() }
        assertTrue(store.save(TaskSurfacePreference.FOREGROUND))
        assertEquals("TASK_SURFACE_BACKGROUND_UNSUPPORTED", (freeze(preview) as TaskSurfaceDecision.NotExecuted).code)
        assertFalse(preview.submitted); assertNull(preview.scope); assertEquals(0, backend.calls)
        preview.cancel()
    }
    @Test fun askCancelIsStickyAndChangingSettingsCannotAskAgainOrIssueAnActionGrant() {
        val preview = preview()
        assertSame(TaskSurfaceDecision.AskRequired, freeze(preview))
        val location = checkNotNull(preview.location)
        val adapter = checkNotNull(location.adapter)
        val question = checkNotNull(adapter.askFromUser(location.request))
        assertTrue(adapter.answerFromUser(location.request, question, null))
        assertTrue(store.save(TaskSurfacePreference.FOREGROUND))
        assertEquals("TASK_SURFACE_ASK_CANCELLED", (freeze(preview) as TaskSurfaceDecision.NotExecuted).code)
        assertNull(adapter.askFromUser(location.request))
        assertThrows(IllegalStateException::class.java) { preview.approve() }
        assertEquals(0, backend.calls)
        preview.cancel()
    }
    @Test fun foregroundAnswerOnlySelectsLocationThenExactApprovalUsesOriginalCatalogAndEndsOnDrain() {
        val preview = preview()
        assertSame(TaskSurfaceDecision.AskRequired, freeze(preview))
        val location = checkNotNull(preview.location)
        val adapter = checkNotNull(location.adapter)
        val question = checkNotNull(adapter.askFromUser(location.request))
        assertTrue(adapter.answerFromUser(location.request, question, TaskSurface.FOREGROUND))
        assertFalse(preview.submitted); assertNull(preview.scope); assertEquals(0, backend.calls)
        val scope = preview.approve()
        val message = Message(MessageId("assistant"), preview.conversationId, preview.runId,
            Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)
        val tools = RunCatalogTools(ToolRegistry(), SessionCapabilities(preview.runId.value,
            setOf(preview.action.kind.capability)), ToolLedger(InMemoryToolLedgerStore()),
            preview.conversationId, preview.runId, authorization = scope.authorization, platformAction = scope) { caps, task ->
            SkillRunSession.create(task, caps.enabled, emptyList())
        }
        tools.bindOwner(runnerOwner(message))
        assertEquals(listOf(preview.action.kind.toolName), tools.definitions.map { it.name })
        val result = tools.execute(message, listOf(ToolInvocation(ToolCallId("clock"),
            preview.action.kind.toolName, preview.action.json))) { false }.single().outcome
        assertTrue(result is DispatchOutcome.Confirmed); assertEquals(1, backend.calls)
        tools.closeWithResult { assertEquals(RunCatalogCloseResult.Drained, it) }
        assertNull(location.ready(TaskSurface.FOREGROUND))
    }
    @Test fun missingOrSelfClockHandlerCannotMintASelfExceptionOrAnActionGrant() {
        for (target in listOf(null, "app.daiyu.android")) {
            val preview = preview(target)
            assertEquals("CLOCK_TARGET_UNAVAILABLE", (freeze(preview) as TaskSurfaceDecision.NotExecuted).code)
            assertNull(preview.location)
            assertThrows(IllegalStateException::class.java) { preview.approve() }
            preview.cancel()
        }
        assertEquals(0, backend.calls)
    }
    @Test fun cancelledBeforeFreezeDoesNotReadSettingsOrResolveAReplacement() {
        var resolves = 0
        val preview = ControllerPlatformActionPreparation(null, "clock draft",
            PlatformAction.parse(PlatformActionKind.TIMER, """{"seconds":15,"label":"fixture"}"""), backend,
            { store }, "app.daiyu.android", { resolves++; "example.clock" })
        preview.cancel()
        assertEquals("PLATFORM_PREPARATION_REVOKED", (freeze(preview) as TaskSurfaceDecision.NotExecuted).code)
        assertEquals(0, persistence.reads); assertEquals(0, resolves); assertEquals(0, backend.calls)
    }

    private fun locationEnded(location: ControllerTaskSurface): Boolean =
        ControllerTaskSurface::class.java.getDeclaredField("ended").apply { isAccessible = true }.getBoolean(location)

    private fun catalog(preview: ControllerPlatformActionPreparation): Pair<RunCatalogTools, Message> {
        val scope = preview.approve()
        val message = Message(MessageId("assistant"), preview.conversationId, preview.runId,
            Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)
        val tools = RunCatalogTools(ToolRegistry(), SessionCapabilities(preview.runId.value,
            setOf(preview.action.kind.capability)), ToolLedger(InMemoryToolLedgerStore()),
            preview.conversationId, preview.runId, authorization = scope.authorization, platformAction = scope) { caps, task ->
            SkillRunSession.create(task, caps.enabled, emptyList())
        }
        tools.bindOwner(runnerOwner(message))
        assertEquals(listOf(preview.action.kind.toolName), tools.definitions.map { it.name })
        return tools to message
    }
    @Test fun repeatedFreezeReadsSettingsAndResolvesOriginalTargetOnlyOnce() {
        assertTrue(store.save(TaskSurfacePreference.FOREGROUND))
        var resolves = 0
        val preview = ControllerPlatformActionPreparation(null, "clock draft",
            PlatformAction.parse(PlatformActionKind.ALARM, """{"hour":7,"minute":30,"label":"fixture"}"""), backend,
            { store }, "app.daiyu.android", { resolves++; "example.clock" })
        val before = persistence.reads
        assertTrue(freeze(preview) is TaskSurfaceDecision.Ready)
        assertTrue(freeze(preview) is TaskSurfaceDecision.Ready)
        assertEquals(1, persistence.reads - before)
        assertEquals(1, resolves)
        preview.cancel()
        assertTrue(locationEnded(checkNotNull(preview.location)))
    }
    @Test fun cancelDuringBackendRetainsOriginalLocationUntilConfirmedCatalogDrain() {
        assertTrue(store.save(TaskSurfacePreference.FOREGROUND))
        val preview = preview()
        assertTrue(freeze(preview) is TaskSurfaceDecision.Ready)
        val location = checkNotNull(preview.location)
        val (tools, message) = catalog(preview)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val drained = CountDownLatch(1)
        val closeResult = AtomicReference<RunCatalogCloseResult>()
        backend.reply = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            ExecutionReceipt.Confirmed("synthetic clock receipt")
        }
        val worker = Executors.newSingleThreadExecutor()
        try {
            val execution = worker.submit(Callable {
                tools.execute(message, listOf(ToolInvocation(ToolCallId("blocked-clock"),
                    preview.action.kind.toolName, preview.action.json))) { false }.single().outcome
            })
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(locationEnded(location))
            preview.cancel()
            tools.closeWithResult { closeResult.set(it); drained.countDown() }
            assertFalse(locationEnded(location))
            assertEquals(1L, drained.count)
            release.countDown()
            assertTrue(execution.get(5, TimeUnit.SECONDS) is DispatchOutcome.Confirmed)
            assertTrue(drained.await(5, TimeUnit.SECONDS))
            assertEquals(RunCatalogCloseResult.Drained, closeResult.get())
            assertTrue(locationEnded(location))
        } finally { release.countDown(); worker.shutdownNow() }
    }
    @Test fun cancelAfterUnknownDoesNotEndTheRetainedLocationOrClaimDrained() {
        assertTrue(store.save(TaskSurfacePreference.FOREGROUND))
        val preview = preview()
        assertTrue(freeze(preview) is TaskSurfaceDecision.Ready)
        val location = checkNotNull(preview.location)
        val (tools, message) = catalog(preview)
        backend.reply = { ExecutionReceipt.Unknown("synthetic clock effect unverified") }
        val result = tools.execute(message, listOf(ToolInvocation(ToolCallId("unknown-clock"),
            preview.action.kind.toolName, preview.action.json))) { false }.single().outcome
        assertTrue(result is DispatchOutcome.Unknown)
        preview.cancel()
        var closed: RunCatalogCloseResult? = null
        tools.closeWithResult { closed = it }
        assertTrue(closed is RunCatalogCloseResult.Unknown)
        assertFalse(locationEnded(location))
        assertEquals(1, backend.calls)
    }

}
