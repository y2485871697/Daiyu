package app.daiyu.android

import android.test.InstrumentationTestCase
import app.daiyu.android.domain.ConversationId
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Real Controller restart, durable conversation, disk evidence; no model or terminal execution. */
@Suppress("DEPRECATION")
class ControllerTerminalHistoryTest : InstrumentationTestCase() {
    @Suppress("UNCHECKED_CAST")
    fun testHistoryDeduplicatesByOriginalJobIdAndBadHistoryCannotRemoveLiveSession() {
        val fixture = RunControlFixture(instrumentation.targetContext)
        val id = UUID.randomUUID().toString()
        val badId = UUID.randomUUID().toString()
        val directory = File(fixture.application.filesDir, "terminal-jobs").apply { mkdirs() }
        val meta = File(directory, "$id.json")
        val bad = File(directory, "$badId.json")
        var original: app.daiyu.android.integration.tools.TerminalJobSession? = null
        try {
            awaitControl("controller ready") { fixture.controller.ready }
            val conversation = fixture.database.create("default")
            val plan = app.daiyu.android.integration.tools.TerminalJobPlan(conversation.id,
                app.daiyu.android.domain.RunId(UUID.randomUUID().toString()),
                app.daiyu.android.integration.tools.TerminalPtyLaunch.Android(listOf("/system/bin/sh"), emptyMap(), fixture.application.filesDir),
                "fixture bytes never executed", "async")
            val token = app.daiyu.android.domain.tools.TaskAuthorization(plan.sessionId, plan.runId.value, setOf(plan.effect))
            val session = app.daiyu.android.integration.tools.TerminalJobSession.approved(plan, token) {
                error("read-only merge must never create a backend")
            }
            original = session
            // Seed the post-dispatch identity without any transport, command or process.
            session.javaClass.getDeclaredField("job").apply { isAccessible = true }.set(session, id)
            val retained = ChatController::class.java.getDeclaredField("retainedTerminalJobs")
                .apply { isAccessible = true }.get(fixture.controller) as MutableList<app.daiyu.android.integration.tools.TerminalJobSession>
            synchronized(fixture.controller) { retained.add(session) }
            meta.writeText(JSONObject().put("job_id", id).put("owner", conversation.id.value)
                .put("scope", plan.runId.value).put("session_id", UUID.randomUUID().toString())
                .put("mode", "async").put("backend", "android").toString())
            bad.writeText("{")
            val snapshot = fixture.controller.terminalJobsOnWorker(conversation.id) { true }
            assertSame(session, snapshot.sessions.single())
            assertTrue(snapshot.history.none { it.jobId == id })
            assertTrue(snapshot.skipped >= 1)
            // An unavailable/bad original record does not remove the retained live object either.
            meta.writeText("{")
            val corrupt = fixture.controller.terminalJobsOnWorker(conversation.id) { true }
            assertSame(session, corrupt.sessions.single())
            assertTrue(corrupt.history.none { it.jobId == id })
        } finally { original?.close(); meta.delete(); bad.delete(); fixture.close() }
    }

    fun testNewControllerReadsHistoryWithoutRestoringSessionAuthorizationOrStopCapability() {
        val fixture = RunControlFixture(instrumentation.targetContext)
        val id = UUID.randomUUID().toString()
        val directory = File(fixture.application.filesDir, "terminal-jobs").apply { mkdirs() }
        val meta = File(directory, "$id.json")
        val log = File(directory, "$id.log")
        var reopened: ChatController? = null
        try {
            awaitControl("original controller ready") { fixture.controller.ready }
            val conversation = fixture.database.create("default")
            meta.writeText(JSONObject().put("job_id", id).put("owner", conversation.id.value)
                .put("scope", UUID.randomUUID().toString()).put("session_id", UUID.randomUUID().toString())
                .put("mode", "async").put("backend", "android").put("state", "exited").put("exit_code", 0).toString())
            log.writeText("restart evidence")
            val originalBytes = meta.readBytes().toList() to log.readBytes().toList()
            fixture.controller.close()
            val controller = ChatController(fixture.application, fixture.database, fixture.settings,
                fixture.application.getSharedPreferences("conversation-ui", 0),
                model = { error("history must not open a model") })
            reopened = controller
            awaitControl("new controller ready") { controller.ready }
            val snapshot = controller.terminalJobsOnWorker(conversation.id) { true }
            assertTrue(snapshot.sessions.isEmpty())
            assertTrue(controller.terminalJobRuns(conversation.id).isEmpty())
            assertNull(controller.terminalJobPreparation())
            assertNull(controller.active)
            val original = snapshot.history.single()
            val page = controller.terminalJobHistoryPageOnWorker(conversation.id, original, 0, 16) { true }
            assertEquals("restart evidence", page.getString("stdout"))
            assertEquals("unknown_after_process_restart", page.getString("state"))
            assertTrue(page.isNull("exit_code")); assertEquals("unknown", page.getString("execution"))
            // History is a data-only Record, not a TerminalJobSession accepted by the stop API.
            assertFalse(original.javaClass.methods.any { it.name.contains("stop", ignoreCase = true) })
            try {
                controller.terminalJobHistoryPageOnWorker(ConversationId("other-owner"), original, 0, 16) { true }
                fail("untrusted/missing conversation must not read history")
            } catch (_: IllegalStateException) { }
            try {
                controller.terminalJobsOnWorker(conversation.id) { false }
                fail("revoked visit must not scan")
            } catch (_: IllegalStateException) { }
            assertEquals(originalBytes, meta.readBytes().toList() to log.readBytes().toList())
            assertEquals(0, fixture.opens.get())
        } finally { reopened?.close(); meta.delete(); log.delete(); fixture.close() }
    }
}
