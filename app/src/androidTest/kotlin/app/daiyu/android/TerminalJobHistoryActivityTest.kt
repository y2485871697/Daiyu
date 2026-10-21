package app.daiyu.android

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import app.daiyu.android.domain.Conversation
import app.daiyu.android.terminal.TerminalJobHistoryReader
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Mounted production panel with zero live sessions. No shell, real provider or restored control. */
class TerminalJobHistoryActivityTest : WiredActivityTestCase() {
    override val engineeringUi: Boolean = true
    private val created = mutableListOf<File>()
    private fun invoke(name: String, vararg args: Any?): Any? = MainActivity::class.java.declaredMethods
        .single { it.name == name && it.parameterCount == args.size }.apply { isAccessible = true }.invoke(activity, *args)
    private fun property(target: Any, getter: String): Any? = target.javaClass.declaredMethods
        .single { it.name == getter && it.parameterCount == 0 }.apply { isAccessible = true }.invoke(target)
    private fun seedHistory(text: String): Pair<Conversation, String> {
        val conversation = fixture.database.create("default")
        val id = UUID.randomUUID().toString()
        val directory = File(fixture.application.filesDir, "terminal-jobs").apply { mkdirs() }
        File(directory, "$id.json").also { created.add(it) }.writeText(JSONObject()
            .put("job_id", id).put("owner", conversation.id.value).put("scope", "original-run")
            .put("session_id", UUID.randomUUID().toString()).put("backend", "android").put("mode", "async")
            .put("state", "exited").put("exit_code", 0).toString())
        File(directory, "$id.log").also { created.add(it) }.writeText(text)
        return conversation to id
    }
    private fun open(conversation: Conversation): Any {
        mainThread { activity.select(conversation) }
        node("terminal-jobs-entry").performClick()
        awaitControl("history published") {
            val visit = invoke("getTerminalJobsVisit")
            visit != null && !(property(visit, "getBusy") as Boolean)
        }
        ui.waitForIdle()
        return checkNotNull(invoke("getTerminalJobsVisit"))
    }
    override fun tearDown() {
        try { super.tearDown() } finally { created.forEach { it.delete() } }
    }

    fun testNoLiveStillShowsUnknownReadOnlyHistoryAndExplicitRefreshAndPagination() {
        val (conversation, id) = seedHistory("H".repeat(16384) + "tail-only-second-page")
        assertTrue(fixture.controller.terminalJobRuns(conversation.id).isEmpty())
        open(conversation)
        node("terminal-job-history").assertExists()
        ui.onAllNodesWithText("停止此 job…").assertCountEquals(0)
        node("terminal-job-stop-confirm").assertDoesNotExist()
        node("terminal-jobs-panel").scrollToDescendant(hasTestTag("terminal-history-read-$id"))
        node("terminal-history-read-$id").assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        awaitControl("first history page") {
            val visit = checkNotNull(invoke("getTerminalJobsVisit"))
            !(property(visit, "getBusy") as Boolean) && property(visit, "getHasMore") == true
        }
        node("terminal-job-result").assertTextContains("unknown_after_process_restart", substring = true)
        node("terminal-job-result").assertTextContains("\"exit_code\": null", substring = true)
        node("terminal-jobs-panel").scrollToDescendant(hasTestTag("terminal-history-next"))
        node("terminal-history-next").performClick()
        awaitControl("second history page") {
            val visit = checkNotNull(invoke("getTerminalJobsVisit"))
            !(property(visit, "getBusy") as Boolean) && property(visit, "getNextOffset") == 16405L
        }
        node("terminal-job-result").assertTextContains("tail-only-second-page", substring = true)
        node("terminal-history-next").assertDoesNotExist()
        node("terminal-jobs-panel").scrollToDescendant(hasTestTag("terminal-jobs-refresh"))
        node("terminal-jobs-refresh").performClick()
        awaitControl("explicit refresh") {
            val visit = checkNotNull(invoke("getTerminalJobsVisit"))
            !(property(visit, "getBusy") as Boolean) && property(visit, "getHistoryPage") == null
        }
        node("terminal-job-stop-confirm").assertDoesNotExist()
        assertEquals(0, fixture.opens.get())
    }

    @Suppress("UNCHECKED_CAST")
    fun testQueuedHistoryResultCannotRefillAfterConversationSwitchOrPageLeave() {
        val (first, _) = seedHistory("old conversation output")
        val (second, _) = seedHistory("new conversation output")
        val old = open(first)
        val record = (property(old, "getHistory") as List<TerminalJobHistoryReader.Record>).single()
        // The accepted read is queued. This same Main turn revokes its visit before
        // admission; neither the worker refusal nor a result may refill the old UI.
        mainThread {
            invoke("terminalHistoryPage", old, record, 0L)
            activity.select(second)
        }
        awaitControl("old worker returned") { property(old, "getBusy") == false }
        assertEquals("", property(old, "getOutput"))
        assertNull(invoke("getTerminalJobsVisit"))
        val current = open(second)
        val currentRecord = (property(current, "getHistory") as List<TerminalJobHistoryReader.Record>).single()
        mainThread {
            invoke("terminalHistoryPage", current, currentRecord, 0L)
            invoke("setRetainedPage", "设置")
        }
        awaitControl("off-page worker returned") { property(current, "getBusy") == false }
        assertEquals("", property(current, "getOutput"))
        assertNull(invoke("getTerminalJobsVisit"))
        node("terminal-job-history").assertDoesNotExist()
        assertEquals(0, fixture.opens.get())
    }

    @Suppress("UNCHECKED_CAST")
    fun testPauseWithoutStopPermanentlyRevokesQueuedHistoryVisit() {
        val (conversation, _) = seedHistory("paused history must not publish")
        val old = open(conversation)
        val record = (property(old, "getHistory") as List<TerminalJobHistoryReader.Record>).single()
        realPauseResume(activity, beforePause = {
            invoke("terminalHistoryPage", old, record, 0L)
            assertEquals(true, property(old, "getBusy"))
        }) {
            assertNull(invoke("getTerminalJobsVisit"))
        }
        awaitControl("paused history worker returned") { property(old, "getBusy") == false }
        assertEquals("", property(old, "getOutput"))
        assertNull(invoke("getTerminalJobsVisit"))
        node("terminal-job-history").assertDoesNotExist()
        assertEquals(0, fixture.opens.get())
    }

}
