package app.daiyu.android

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import java.util.concurrent.TimeUnit

/** Exercises the mounted production composer, not a replacement approval-only composition. */
class TerminalChatActivityTest : WiredActivityTestCase() {
    override val engineeringUi = true
    private fun invoke(target: MainActivity, name: String, vararg args: Any?): Any? =
        MainActivity::class.java.declaredMethods.single { it.name == name && it.parameterCount == args.size }
            .apply { isAccessible = true }.invoke(target, *args)

    private fun openAndConfirm(): ControllerTerminalPreparation {
        setDraft("解释终端输出；这不是 shell 命令")
        node("terminal-chat-entry").performClick()
        node("terminal-exact-command").performTextReplacement("printf 'explicit bytes'\n")
        node("terminal-prepare").scrollToVisible().assertIsDisplayed()
        node("terminal-prepare").assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        awaitControl("production preview") { fixture.controller.terminalPreparation() != null }
        ui.waitForIdle()
        val pending = checkNotNull(fixture.controller.terminalPreparation())
        assertEquals(0, fixture.opens.get())
        assertTrue(fixture.database.conversations().isEmpty())
        node("terminal-confirm").performClick()
        ui.waitForIdle()
        assertEquals(ControllerTerminalPreparation.State.CONFIRMED, pending.state)
        assertNull(fixture.controller.textSendState())
        assertEquals(0, fixture.opens.get())
        return pending
    }

    fun testMountedAsyncJobApprovalUsesOriginalSendReceiptAndJobsResultEntry() {
        setDraft("解释 job 日志；自然语言不是命令")
        node("terminal-chat-entry").performClick()
        node("terminal-mode-async").performClick()
        node("terminal-mode-async").assertTextContains("已选", substring = true)
        node("terminal-exact-command").performTextReplacement("printf 'separate exact job'")
        node("terminal-prepare").scrollToVisible().assertIsDisplayed()
        node("terminal-prepare").assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        awaitControl("job preview") { fixture.controller.terminalJobPreparation() != null }
        ui.waitForIdle()
        val pending = checkNotNull(fixture.controller.terminalJobPreparation())
        node("terminal-job-confirm").scrollToVisible().assertIsDisplayed()
        node("terminal-job-confirm").performClick()
        assertTrue(pending.isConfirmed)
        assertEquals(0, fixture.opens.get())
        assertTrue(fixture.database.conversations().isEmpty())
        val session = fixture.session(holdSeconds = 120)
        node("terminal-job-send").scrollToVisible().assertIsDisplayed()
        node("terminal-job-send").performClick()
        assertTrue(session.entered.await(10, TimeUnit.SECONDS))
        awaitTextSendResult(activity)
        assertDraft("")
        mainThread { retainedControls(activity).onMenuAction(app.daiyu.android.ui.screens.chat.conversation.ChatMenuAction.OPEN_TERMINAL) }
        awaitUniqueTag("terminal-screen")
        node("terminal-tasks").performClick()
        node("terminal-tasks-dialog").assertExists()
        assertEquals(pending.plan.conversationId, fixture.database.conversations().single().id)
        assertSame(pending.plan, fixture.controller.terminalJobRuns(pending.plan.conversationId).single().plan)
        assertNull(fixture.controller.terminalJobRuns(pending.plan.conversationId).single().jobId)
        session.release.countDown()
    }

    fun testMountedConfirmationDoesNotSendAndExplicitSendClearsOnlyOnReceipt() {
        val pending = openAndConfirm()
        assertEquals(pending.draft.text, fixture.database.draft())
        node("shizuku-text-entry").assertIsNotEnabled()
        node("browser-approval-entry").assertIsNotEnabled()
        val session = fixture.session(holdSeconds = 120)
        node("terminal-send-approved").scrollToVisible().assertIsDisplayed()
        node("terminal-send-approved").performClick()
        assertTrue(session.entered.await(10, TimeUnit.SECONDS))
        awaitTextSendResult(activity)
        assertDraft("")
        assertEquals(1, fixture.opens.get())
        val conversation = fixture.database.conversations().single()
        assertEquals(pending.identity.conversationId, conversation.id)
        assertEquals(pending.identity.assistantMessageId,
            fixture.database.messages(conversation.id).single { it.second.role == "assistant" }.second.id)
        session.release.countDown()
    }

    fun testRecreationDisposesApprovalAndOldHostSendCallbackCannotSubmit() {
        val pending = openAndConfirm()
        val old = activity
        var editor: Any? = null
        mainThread { editor = invoke(old, "getTerminalEditor") }
        activity = recreateActivity(old)
        awaitControl("disposed approval drained") { fixture.controller.terminalPreparation() == null }
        mainThread { invoke(old, "sendTerminal", checkNotNull(editor), pending) }
        assertEquals(0, fixture.opens.get())
        assertNull(fixture.controller.textSendState())
        assertEquals(pending.draft.text, fixture.database.draft())
        assertDraft(pending.draft.text)
        node("terminal-send-approved").assertDoesNotExist()
        assertTrue(fixture.database.conversations().isEmpty())
    }
}
