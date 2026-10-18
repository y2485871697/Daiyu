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
        return ui.onNode(target).performScrollTo().assertIsDisplayed().assertIsEnabled()
    }

    private fun enableTextApproval() {
        panelNode(hasTestTag("shizuku-text-enable")).assertIsOff().performClick()
        ui.onNodeWithTag("shizuku-text-enable").assertIsOn()
    }

    private fun openEditor(seedDraft: Boolean = true) {
        if (seedDraft) setDraft(text) else assertDraft(text)
        node("shizuku-text-entry").performClick()
        enableTextApproval()
        panelNode(hasSetTextAction() and hasText("精确 UID：0 或 2000（必选，无默认值）")).performTextReplacement("2000")
        panelNode(hasSetTextAction() and hasText("本次精确 shell 命令")).performTextReplacement(command)
        panelNode(hasSetTextAction() and hasText("超时毫秒（1–120000）")).performTextReplacement("1234")
    }

    private fun preview(seedDraft: Boolean = true): ControllerShizukuPreparation {
        openEditor(seedDraft)
        panelNode(hasText("预览命令及本次运行") and hasClickAction()).performClick()
        ui.waitUntil(5_000) { fixture.controller.shizukuPreparation() != null }
        val preparation = checkNotNull(fixture.controller.shizukuPreparation())
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

    fun testIndependentEntryDefaultsOffAndUidHasNoFallback() {
        setDraft(text)
        node("shizuku-text-entry").assertIsEnabled().performClick()
        assertNull(fixture.controller.shizukuPreparation())
        ui.onNodeWithText("本次精确 shell 命令").assertDoesNotExist()
        enableTextApproval()
        panelNode(hasSetTextAction() and hasText("本次精确 shell 命令")).performTextReplacement(command)
        // Even with valid text/command/timeout, no UID was selected.
        panelNode(hasText("预览命令及本次运行") and hasClickAction()).performClick()
        assertNull(fixture.controller.shizukuPreparation())
        assertEquals(0, fixture.opens.get())
        assertEquals(text, fixture.database.draft())
    }

    fun testFullPreviewRefusalAndExplicitPanelCancelDoNotSend() {
        val p = preview()
        assertEquals(text, p.text); assertEquals(command, p.command)
        assertEquals(2000, p.uid); assertEquals(1234L, p.timeoutMillis)
        val inPreview = hasAnyAncestor(isDialog())
        ui.onNode(hasText("会话：${p.conversationId.value}\n运行：${p.runId.value}") and inPreview).assertExists()
        ui.onNode(hasText(command) and inPreview).assertExists()
        ui.onNode(hasText(p.messageText) and inPreview).assertExists()
        ui.onNode(hasText("拒绝 / 取消") and inPreview).performClick()
        ui.waitUntil(5_000) { fixture.controller.shizukuPreparation() == null }
        assertTrue(p.isCancelled())
        panelNode(hasTestTag("shizuku-text-cancel")).performClick()
        node("shizuku-text-panel").assertDoesNotExist()
        assertDraft(text)
        assertTrue(fixture.database.conversations().isEmpty())
        assertEquals(0, fixture.opens.get())
    }

    fun testApproveReachesRealControllerAndFailureReceiptPreservesDraftWithoutPermissionIO() {
        val p = preview()
        ui.onNodeWithText("同意本次写操作并发送").performClick()
        // RunControlFixture intentionally is not a ChatApplication: preparation fails closed at
        // shizuku_application_host_required before any SDK connection. This is not a fake success.
        ui.waitUntil(5_000) { p.isSubmitted() && fixture.controller.shizukuPreparation() == null }
        assertTrue(fixture.controller.problem != null)
        assertDraft(text)
        assertEquals(text, fixture.database.draft())
        assertEquals(0, fixture.opens.get())
        assertTrue(fixture.database.conversations().isEmpty())
    }

    fun testRecreationCancelsAlreadyApprovedQueuedPreparationWithoutSending() {
        val p = preview()
        val executor = ChatController::class.java.getDeclaredField("executor")
            .apply { isAccessible = true }.get(fixture.controller) as java.util.concurrent.ExecutorService
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        executor.execute {
            entered.countDown()
            check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
        }
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        try {
            ui.onNodeWithText("同意本次写操作并发送").performClick()
            assertTrue(p.isSubmitted())
            activity = recreateActivity(activity)
            assertTrue(p.isCancelled())
            release.countDown()
            ui.waitUntil(5_000) { fixture.controller.shizukuPreparation() == null }
            assertDraft(text)
            assertEquals(0, fixture.opens.get())
            assertTrue(fixture.database.conversations().isEmpty())
        } finally { release.countDown() }
    }

    fun testEditorAbaAndOtherConversationRejectOldAcceptedCallbacks() {
        val conversation = fixture.database.create()
        mainThread { activity.select(conversation) }
        val p = preview()
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
        val p = preview()
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
        val p = preview()
        val oldActivity = activity
        val oldEditor = checkNotNull(editor())
        activity = recreateActivity(activity)
        assertTrue(p.isCancelled())
        assertNull(fixture.controller.shizukuPreparation())
        node("shizuku-text-panel").assertDoesNotExist()
        assertDraft(text)
        mainThread { invokeOn(oldActivity, "acceptShizuku", oldEditor, p, fixture.database.create()) }
        assertDraft(text)
        val next = preview(seedDraft = false)
        // Invoke the real host route while the modal preview covers the chat toolbar.
        ui.onNode(hasText(command) and hasAnyAncestor(isDialog())).assertExists()
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
