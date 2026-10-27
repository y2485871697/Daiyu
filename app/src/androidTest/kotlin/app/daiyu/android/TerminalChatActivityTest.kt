package app.daiyu.android

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import app.daiyu.android.domain.Conversation
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Mounted one-click submissions plus explicit, non-submitting controller approval fixtures. */
class TerminalChatActivityTest : WiredActivityTestCase() {
    override val engineeringUi = true
    private fun invoke(target: MainActivity, name: String, vararg args: Any?): Any? =
        MainActivity::class.java.declaredMethods.single { it.name == name && it.parameterCount == args.size }
            .apply { isAccessible = true }.invoke(target, *args)
    private fun editorValue(editor: Any, name: String, vararg args: Any?): Any? =
        editor.javaClass.declaredMethods.single { it.name == name && it.parameterCount == args.size }
            .apply { isAccessible = true }.invoke(editor, *args)
    private fun executor(): ExecutorService = ChatController::class.java.getDeclaredField("executor")
        .apply { isAccessible = true }.get(fixture.controller) as ExecutorService
    private fun openEditor(async: Boolean = false): Any {
        setDraft(if (async) "解释 job 日志；自然语言不是命令" else "解释终端输出；这不是 shell 命令")
        node("terminal-chat-entry").performClick()
        if (async) {
            node("terminal-mode-async").performClick()
            node("terminal-mode-async").assertTextContains("已选", substring = true)
        }
        node("terminal-exact-command").performTextReplacement("printf 'explicit bytes'\n")
        var editor: Any? = null
        mainThread { editor = invoke(activity, "getTerminalEditor") }
        return checkNotNull(editor)
    }
    private fun assertNotSent(draft: TerminalDraftSnapshot) {
        assertNull(fixture.controller.textSendState())
        assertNull(fixture.controller.active)
        assertEquals(0, fixture.opens.get())
        assertTrue(fixture.database.conversations().isEmpty())
        assertEquals(draft.text, fixture.database.draft())
    }
    // Explicit controller fixture: product terminal-prepare immediately submits, not a preview UI.
    private fun reserveApproved(): Pair<Any, ControllerTerminalPreparation> {
        val editor = openEditor()
        val draft = editorValue(editor, "getDraft") as TerminalDraftSnapshot
        val pending = checkNotNull(fixture.controller.prepareTerminalSend(
            editorValue(editor, "getConversation") as Conversation?, draft,
            editorValue(editor, "getCommand") as String, null))
        mainThread {
            assertSame(editor, invoke(activity, "getTerminalEditor"))
            editorValue(editor, "setPreparation", pending)
            assertTrue(fixture.controller.confirmTerminal(pending, pending.preview, draft, pending.assistant, pending.provider))
        }
        assertEquals(ControllerTerminalPreparation.State.CONFIRMED, pending.state)
        assertNotSent(draft)
        return editor to pending
    }
    private fun clickRun() {
        node("terminal-prepare").scrollToVisible().assertIsDisplayed().assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
    }
    private fun <T> workerBlocked(action: (CountDownLatch) -> T): T {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        executor().execute {
            entered.countDown()
            try { check(release.await(30, TimeUnit.SECONDS)) } finally { done.countDown() }
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        return try { action(release) } finally {
            release.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
        }
    }
    fun testMountedAsyncJobApprovalUsesOriginalSendReceiptAndJobsResultEntry() {
        val editor = openEditor(async = true)
        val draft = editorValue(editor, "getDraft") as TerminalDraftSnapshot
        val pending = checkNotNull(fixture.controller.prepareTerminalJobSend(
            editorValue(editor, "getConversation") as Conversation?, draft,
            editorValue(editor, "getCommand") as String, "async", null))
        mainThread {
            editorValue(editor, "setJobPreparation", pending)
            assertTrue(fixture.controller.confirmTerminalJob(pending, draft))
        }
        assertTrue(pending.isConfirmed)
        assertNotSent(draft)
        val session = fixture.session(holdSeconds = 120)
        mainThread { invoke(activity, "sendTerminalJob", editor, pending) }
        assertTrue(session.entered.await(10, TimeUnit.SECONDS))
        awaitTextSendResult(activity)
        assertDraft("")
        mainThread { retainedControls(activity).onMenuAction(app.daiyu.android.ui.screens.chat.conversation.ChatMenuAction.OPEN_TERMINAL) }
        awaitUniqueTag("terminal-screen")
        node("terminal-tasks").performClick()
        node("terminal-tasks-dialog").assertExists()
        val conversation = fixture.database.conversations().single()
        assertEquals(pending.plan.conversationId, conversation.id)
        val assistant = fixture.database.messages(conversation.id).single { it.second.role == "assistant" }.second
        assertEquals(pending.identity.assistantMessageId, assistant.id)
        assertEquals(pending.identity.runId, assistant.runId)
        assertSame(pending.plan, fixture.controller.terminalJobRuns(conversation.id).single().plan)
        assertNull(fixture.controller.terminalJobRuns(conversation.id).single().jobId)
        session.release.countDown()
    }
    fun testMountedConfirmationDoesNotSendAndExplicitSendClearsOnlyOnReceipt() {
        val (editor, pending) = reserveApproved()
        node("shizuku-text-entry").assertIsNotEnabled()
        node("browser-approval-entry").assertIsNotEnabled()
        val session = fixture.session(holdSeconds = 120)
        mainThread { invoke(activity, "sendTerminal", editor, pending) }
        assertTrue(session.entered.await(10, TimeUnit.SECONDS))
        awaitTextSendResult(activity)
        assertDraft("")
        assertEquals(1, fixture.opens.get())
        val conversation = fixture.database.conversations().single()
        assertEquals(pending.identity.conversationId, conversation.id)
        val assistant = fixture.database.messages(conversation.id).single { it.second.role == "assistant" }.second
        assertEquals(pending.identity.assistantMessageId, assistant.id)
        assertEquals(pending.identity.runId, assistant.runId)
        session.release.countDown()
    }
    fun testRecreationDisposesApprovalAndOldHostSendCallbackCannotSubmit() {
        val (editor, pending) = reserveApproved()
        val old = activity
        activity = recreateActivity(old)
        awaitControl("disposed approval drained") { fixture.controller.terminalPreparation() == null }
        mainThread { invoke(old, "sendTerminal", editor, pending) }
        assertEquals(0, fixture.opens.get())
        assertNull(fixture.controller.textSendState())
        assertEquals(pending.draft.text, fixture.database.draft())
        assertDraft(pending.draft.text)
        assertTrue(fixture.database.conversations().isEmpty())
    }
    fun testMountedRunButtonSubmitsPtyOnlyAfterOriginalReceipt() = mountedRun(async = false)
    fun testMountedRunButtonSubmitsAsyncOnlyAfterOriginalReceipt() = mountedRun(async = true)
    private fun mountedRun(async: Boolean) {
        openEditor(async)
        val before = fixture.database.draft()
        val session = fixture.session(holdSeconds = 120)
        workerBlocked { release ->
            clickRun()
            awaitControl("original terminal ticket") { fixture.controller.textSendState() != null }
            val identity = if (async) checkNotNull(fixture.controller.terminalJobPreparation()).identity
                else checkNotNull(fixture.controller.terminalPreparation()).identity
            assertEquals(before, fixture.database.draft())
            assertDraft(before)
            assertTrue(fixture.database.conversations().isEmpty())
            assertEquals(0, fixture.opens.get())
            assertFalse(session.entered.await(50, TimeUnit.MILLISECONDS))
            release.countDown()
            assertTrue(session.entered.await(10, TimeUnit.SECONDS))
            awaitTextSendResult(activity)
            assertDraft("")
            val conversation = fixture.database.conversations().single()
            val assistant = fixture.database.messages(conversation.id).single { it.second.role == "assistant" }.second
            assertEquals(identity.conversationId, conversation.id)
            assertEquals(identity.assistantMessageId, assistant.id)
            assertEquals(identity.runId, assistant.runId)
            assertEquals(1, fixture.opens.get())
        }
        session.release.countDown()
    }
}
