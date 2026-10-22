package app.daiyu.android

import androidx.compose.ui.test.*
import app.daiyu.android.domain.Conversation
import app.daiyu.android.terminal.TerminalJobHistoryReader
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Mounted production task dialog with zero live sessions. No shell, real provider or restored control. */
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
            .put("state", "exited").put("exit_code", 0).put("command", "printf saved-command").toString())
        File(directory, "$id.log").also { created.add(it) }.writeText(text)
        return conversation to id
    }
    private fun open(conversation: Conversation): Any {
        mainThread {
            activity.select(conversation)
            retainedControls(activity).onMenuAction(app.daiyu.android.ui.screens.chat.conversation.ChatMenuAction.OPEN_TERMINAL)
        }
        awaitUniqueTag("terminal-screen")
        node("terminal-tasks").performClick()
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

    fun testNoLiveShowsUnknownHistoryAndReadsBoundedTailThroughLogButton() {
        val (conversation, _) = seedHistory("HEAD-MUST-NOT-APPEAR" + "H".repeat(100_000) + "tail-last")
        assertTrue(fixture.controller.terminalJobRuns(conversation.id).isEmpty())
        open(conversation)
        node("terminal-tasks-dialog").assertExists()
        node("terminal-task-stop:0").assertDoesNotExist()
        ui.onNodeWithText("printf saved-command").assertExists()
        ui.onNodeWithText("状态未知").assertExists()
        node("terminal-task-read:0").assertIsEnabled().performClick()
        awaitControl("bounded history tail") {
            val visit = checkNotNull(invoke("getTerminalJobsVisit"))
            !(property(visit, "getBusy") as Boolean) && property(visit, "getHistoryPage") != null
        }
        node("terminal-task-output").assertTextContains("tail-last", substring = true)
        node("terminal-task-output").assertTextContains("H".repeat(32), substring = true)
        val text = node("terminal-task-output").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString("") { it.text }
        assertEquals(65_536, text.length)
        assertFalse(text.contains("HEAD-MUST-NOT-APPEAR"))
        assertFalse(text.contains("历史任务状态未知"))
        node("terminal-tasks-next").assertDoesNotExist()
        node("terminal-tasks-refresh").assertDoesNotExist()
        node("terminal-task-read:0").performClick()
        node("terminal-task-output").assertDoesNotExist()
        node("terminal-task-read:0").performClick()
        node("terminal-task-output").assertTextContains("tail-last", substring = true)
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
        node("terminal-tasks-dialog").assertDoesNotExist()
        assertEquals(0, fixture.opens.get())
    }

    @Suppress("UNCHECKED_CAST")
    fun testReturningToChatRevokesQueuedHistoryVisit() {
        val (conversation, _) = seedHistory("leaving terminal must revoke history")
        val old = open(conversation)
        val record = (property(old, "getHistory") as List<TerminalJobHistoryReader.Record>).single()
        mainThread {
            invoke("terminalHistoryPage", old, record, 0L)
            invoke("setRetainedPage", "chat")
        }
        awaitControl("chat return worker drained") { property(old, "getBusy") == false }
        assertEquals(true, (property(old, "getRevoked") as java.util.concurrent.atomic.AtomicBoolean).get())
        assertEquals("", property(old, "getOutput"))
        assertNull(invoke("getTerminalJobsVisit"))
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
        node("terminal-tasks-dialog").assertDoesNotExist()
        assertEquals(0, fixture.opens.get())
    }

    fun testTerminalPageTasksReuseBoundedHistoryAndCannotControlRestartedJobs() {
        val (conversation, _) = seedHistory("terminal page history")
        mainThread {
            activity.select(conversation)
            retainedControls(activity).onMenuAction(app.daiyu.android.ui.screens.chat.conversation.ChatMenuAction.OPEN_TERMINAL)
        }
        awaitUniqueTag("terminal-screen")
        val owner = (instrumentation.targetContext.applicationContext as ChatApplication).terminalOwner
        val caller = owner.caller.value
        node("terminal-tasks").performClick()
        awaitControl("terminal task history published") {
            val visit = invoke("getTerminalJobsVisit")
            visit != null && property(visit, "getBusy") == false
        }
        node("terminal-task-stop:0").assertDoesNotExist()
        node("terminal-task-read:0").performClick()
        awaitControl("terminal history page") {
            val visit = checkNotNull(invoke("getTerminalJobsVisit"))
            property(visit, "getBusy") == false && property(visit, "getHistoryPage") != null
        }
        node("terminal-task-output").assertTextContains("terminal page history", substring = true)
        awaitNodeWindowFocus(node("terminal-tasks-dialog"))
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        ui.waitForIdle()
        node("terminal-tasks-dialog").assertDoesNotExist()
        node("terminal-screen").assertIsDisplayed()
        assertSame(caller, owner.caller.value)
        assertFalse(owner.host.state.value.ownsResources)
        assertEquals(0, fixture.opens.get())
    }

}
