package app.daiyu.android

import androidx.compose.runtime.State
import androidx.compose.ui.test.*
import app.daiyu.android.domain.*
import app.daiyu.android.ui.screens.chat.conversation.ChatMenuAction

/** Real Activity + real approval host/controller. No device permission request or shell execution.
 * Accepted UI receipts below are explicit DB fixtures, not a claim of real privileged transport.
 */
class ShizukuMainActivityTest : WiredActivityTestCase() {
    override val engineeringUi = true
    private val text = "完整正文\n保留末尾空格  "
    private val command = "printf 'exact command\\n'; id"
    private fun invoke(name: String, vararg args: Any?): Any? = invokeOn(activity, name, *args)
    private fun invokeOn(target: MainActivity, name: String, vararg args: Any?): Any? =
        MainActivity::class.java.declaredMethods.single { it.name == name && it.parameterCount == args.size }
            .apply { isAccessible = true }.invoke(target, *args)

    private fun editor(target: MainActivity = activity): Any? =
        (MainActivity::class.java.getDeclaredField("shizukuEditor\$delegate")
            .apply { isAccessible = true }.get(target) as State<*>).value

    private fun panelNode(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        val target = matcher and hasAnyAncestor(hasTestTag("shizuku-text-panel"))
        return ui.onNode(target).scrollToVisible().assertIsDisplayed().assertIsEnabled()
    }

    private fun withBlockedShizukuWorker(block: (() -> Unit) -> Unit) {
        val executor = app.daiyu.android.ChatController::class.java.getDeclaredField("executor")
            .apply { isAccessible = true }.get(fixture.controller) as java.util.concurrent.ExecutorService
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val blocker = executor.submit { entered.countDown(); release.await() }
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            block { release.countDown() }
        } finally {
            release.countDown()
            blocker.get(5, java.util.concurrent.TimeUnit.SECONDS)
            executor.submit { }.get(5, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    private fun openEditor(seedDraft: Boolean = true, uid: String? = "2000") {
        if (seedDraft) setDraft(text) else assertDraft(text)
        node("shizuku-text-entry").performClick()
        if (uid != null) panelNode(hasSetTextAction() and hasText("精确 UID：0 或 2000（必选，无默认值）"))
            .performTextReplacement(uid)
        panelNode(hasSetTextAction() and hasText("本次精确 shell 命令")).performTextReplacement(command)
        panelNode(hasSetTextAction() and hasText("超时毫秒（1–120000）")).performTextReplacement("1234")
    }

    private fun clickSend() {
        awaitNodeWindowFocus(node("shizuku-text-panel"))
        dismissIme()
        val send = panelNode(hasTestTag("shizuku-text-send"))
        awaitImeFrame(send, visible = false)
        send.performClick()
    }

    private fun submitWhileWorkerBlocked(): ControllerShizukuPreparation {
        clickSend()
        return checkNotNull(fixture.controller.shizukuPreparation()).also { assertTrue(it.isSubmitted()) }
    }

    // Explicit unsubmitted receipt/cancellation fixture; not a product preview or UI submission.
    private fun reserveReceiptFixture(seedDraft: Boolean = true, conversation: Conversation? = null): ControllerShizukuPreparation {
        openEditor(seedDraft)
        lateinit var preparation: ControllerShizukuPreparation
        mainThread {
            preparation = checkNotNull(fixture.controller.prepareShizukuSend(conversation, text, command, "2000", "1234"))
        }
        ui.waitUntil(5_000) {
            var published = false
            instrumentation.runOnMainSync {
                val current = editor()
                published = current?.javaClass?.getDeclaredField("preview")
                    ?.apply { isAccessible = true }?.get(current) === preparation
            }
            published
        }
        return preparation
    }

    fun testIndependentEntryIsAvailableButUidHasNoFallback() {
        openEditor(uid = null)
        ui.onNodeWithTag("shizuku-text-enable").assertDoesNotExist()
        assertNull(fixture.controller.shizukuPreparation())
        assertEquals(0, fixture.opens.get())
        clickSend()
        assertNull(fixture.controller.shizukuPreparation())
        assertEquals(0, fixture.opens.get())
        assertDraft(text)
        assertEquals(text, fixture.database.draft())
        assertTrue(fixture.database.conversations().isEmpty())
    }

    fun testExplicitPanelCancelDoesNotSend() {
        val p = reserveReceiptFixture()
        assertEquals(text, p.text); assertEquals(command, p.command)
        assertEquals(2000, p.uid); assertEquals(1234L, p.timeoutMillis)
        panelNode(hasTestTag("shizuku-text-cancel")).performClick()
        ui.waitUntil(5_000) { fixture.controller.shizukuPreparation() == null }
        assertTrue(p.isCancelled())
        node("shizuku-text-panel").assertDoesNotExist()
        assertDraft(text)
        assertTrue(fixture.database.conversations().isEmpty())
        assertEquals(0, fixture.opens.get())
    }

    fun testDirectSendFailurePreservesDraftWithoutPermissionIO() {
        openEditor()
        withBlockedShizukuWorker { release ->
            val p = submitWhileWorkerBlocked()
            assertEquals(text, p.text); assertEquals(command, p.command)
            assertEquals(2000, p.uid); assertEquals(1234L, p.timeoutMillis)
            release()
            // Fixture is not ChatApplication: refusal occurs before SDK connection.
            ui.waitUntil(5_000) { p.isSubmitted() && fixture.controller.shizukuPreparation() == null }
        }
        assertNotNull(fixture.controller.problem)
        assertDraft(text)
        assertEquals(text, fixture.database.draft())
        assertEquals(0, fixture.opens.get())
        assertTrue(fixture.database.conversations().isEmpty())
    }

    fun testRecreationCancelsSubmittedQueuedPreparationWithoutSending() {
        verifyRecreationCancelsQueuedPreparation()
    }

    private fun verifyRecreationCancelsQueuedPreparation() {
        openEditor()
        withBlockedShizukuWorker { release ->
            val p = submitWhileWorkerBlocked()
            var cancelledBeforeRelease = false
            activity = recreateActivity(activity) { cancelledBeforeRelease = p.isCancelled(); release() }
            assertTrue(cancelledBeforeRelease)
            ui.waitUntil(5_000) { fixture.controller.shizukuPreparation() == null }
            assertDraft(text)
            assertEquals(0, fixture.opens.get())
            assertTrue(fixture.database.conversations().isEmpty())
        }
    }

    fun testEditorAbaAndOtherConversationRejectOldAcceptedCallbacks() {
        val conversation = fixture.database.create()
        mainThread { activity.select(conversation) }
        val p = reserveReceiptFixture(conversation = conversation)
        val oldEditor = checkNotNull(editor())
        mainThread {
            val actions = retainedControls(activity)
            actions.onTextChange("newer text")
            actions.onTextChange(text) // Same bytes do not restore the former revision capability.
            invoke("acceptShizuku", oldEditor, p, conversation)
        }
        assertDraft(text)
        assertTrue(p.isCancelled())
        val other = fixture.database.create()
        mainThread {
            activity.select(other)
            retainedControls(activity).onTextChange("other draft")
            invoke("acceptShizuku", oldEditor, p, conversation)
        }
        assertDraft("other draft")
        assertEquals(other.id.value, fixture.controller.preferences.getString("selected", null))
        assertEquals(0, fixture.opens.get())
    }

    fun testAcceptedDbFixtureClearsOnlyMatchingEditorAndLoadsThatConversation() {
        val conversation = fixture.database.create("default")
        mainThread { activity.select(conversation) }
        val p = reserveReceiptFixture(conversation = conversation)
        val owner = checkNotNull(editor())
        val user = Message(MessageId("approved-user"), conversation.id, p.runId, Message.USER_ROLE,
            listOf(MessageBlock.Text("body", p.messageText)), RunStatus.COMPLETED)
        val assistant = Message(MessageId("approved-assistant"), conversation.id, p.runId, Message.ASSISTANT_ROLE,
            emptyList(), RunStatus.RUNNING)
        // This explicit durable receipt fixture exercises only MainActivity's onAccepted consumer.
        p.approve() // Consume approval without connecting to a device backend.
        fixture.database.send(user, assistant)
        mainThread {
            invoke("acceptShizuku", owner, p, conversation)
            // No worker was scheduled by this receipt-only fixture; release its reservation.
            ChatController::class.java.getDeclaredMethod("finishShizukuPreparation",
                ControllerShizukuPreparation::class.java, Boolean::class.javaPrimitiveType!!)
                .apply { isAccessible = true }.invoke(fixture.controller, p, false)
        }
        assertDraft("")
        assertEquals("", fixture.database.conversations().single().draft)
        assertEquals(p.messageText, fixture.database.messages(conversation.id).first().second.blocks
            .filterIsInstance<MessageBlock.Text>().single().text)
        setDraft("new after receipt")
        mainThread { invoke("acceptShizuku", owner, p, conversation) }
        assertDraft("new after receipt")
    }

    fun testNavigationAndRecreationCancelPreviewAndCannotReplayOldReceipt() {
        val p = reserveReceiptFixture()
        val oldActivity = activity
        val oldEditor = checkNotNull(editor())
        activity = recreateActivity(activity)
        assertTrue(p.isCancelled())
        assertNull(fixture.controller.shizukuPreparation())
        node("shizuku-text-panel").assertDoesNotExist()
        assertDraft(text)
        mainThread { invokeOn(oldActivity, "acceptShizuku", oldEditor, p, fixture.database.create()) }
        assertDraft(text)
        val next = reserveReceiptFixture(seedDraft = false)
        ui.onNode(hasSetTextAction() and hasText(command) and
            hasAnyAncestor(hasTestTag("shizuku-text-panel"))).assertExists()
        mainThread {
            assertSame(next, fixture.controller.shizukuPreparation())
            assertFalse(next.isCancelled())
            assertNotNull(editor())
            retainedControls(activity).onMenuAction(ChatMenuAction.SETTINGS)
            assertTrue(next.isCancelled())
            assertNull(editor())
        }
        awaitUniqueTag("page:设置")
        node("page:设置").assertIsDisplayed()
        node("shizuku-text-panel").assertDoesNotExist()
        assertTrue(next.isCancelled())
        assertNull(fixture.controller.shizukuPreparation())
        assertEquals(0, fixture.opens.get())
    }

    fun testAnyAttachmentDisablesTextApprovalWithoutChangingOrdinarySendRoute() {
        setDraft(text)
        val host = invoke("getImageHost") as ImageChatHost
        mainThread {
            val attachments = host.session.attachments
            val selection = checkNotNull(attachments.requestSelection())
            attachments.beginImport(selection, 1) // Importing also counts; no URI or device picker.
            invoke("publishChat")
        }
        node("shizuku-text-entry").assertIsNotEnabled()
        assertNull(fixture.controller.shizukuPreparation())
        assertEquals(0, fixture.opens.get())
        assertDraft(text)
    }
}
