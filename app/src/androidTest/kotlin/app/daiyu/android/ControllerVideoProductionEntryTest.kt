package app.daiyu.android

import android.net.Uri
import app.daiyu.android.domain.*
import app.daiyu.android.media.video.MessageVideoOwner
import app.daiyu.android.media.video.MessageVideoReference
import app.daiyu.android.media.video.VideoAttachment
import app.daiyu.android.media.video.VideoAttachmentOwner
import app.daiyu.android.model.videos.MessageVideoInput
import androidx.compose.ui.test.*

/** Real Main menu/mount/navigation and the retained production host; never opens a provider. */
@Suppress("DEPRECATION")
class ControllerVideoProductionEntryTest : WiredActivityTestCase() {
    override val engineeringUi = true
    private fun openVideo() {
        val launches = java.util.concurrent.atomic.AtomicInteger()
        val monitor = object : android.app.Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: android.content.Intent): android.app.Instrumentation.ActivityResult? {
                val target = if (intent.action == android.content.Intent.ACTION_CHOOSER)
                    intent.getParcelableExtra<android.content.Intent>(android.content.Intent.EXTRA_INTENT) else intent
                if (target?.action !in setOf(android.content.Intent.ACTION_GET_CONTENT,
                    android.content.Intent.ACTION_OPEN_DOCUMENT, android.content.Intent.ACTION_OPEN_DOCUMENT_TREE)) return null
                assertEquals(android.content.Intent.ACTION_GET_CONTENT, target?.action)
                assertEquals("video/*", target?.type)
                assertTrue(target?.hasCategory(android.content.Intent.CATEGORY_OPENABLE) == true)
                assertTrue(target?.getBooleanExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, false) == true)
                launches.incrementAndGet()
                return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            val attachment = node("chat-attach")
            ui.waitUntil(5_000) { attachment.isDisplayed() &&
                ui.onAllNodes(hasTestTag("chat-attach") and isEnabled())
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1 }
            attachment.assertIsDisplayed().assertIsEnabled().performClick()
            awaitUniqueTag("chat-selection-sheet")
            val sheet = node("chat-selection-sheet")
            awaitNodeWindowFocus(sheet)
            awaitDialogEnter(sheet)
            ui.onNodeWithText("视频").assertIsDisplayed().assertIsEnabled().performClick()
            ui.waitForIdle()
            assertEquals(1, launches.get())
        } finally { instrumentation.removeMonitor(monitor) }
        node("video_attachments").assertDoesNotExist()
        node("video_attachments_select").assertDoesNotExist()
        node("video_attachments_send").assertDoesNotExist()
        assertEquals(0, fixture.opens.get())
    }
    fun testProductionConfigurationSaveAdvancesEpochEvenWhenValuesAreRestored() {
        val epoch = ChatController::class.java.getDeclaredField("videoConfigurationEpoch")
            .apply { isAccessible = true }.get(fixture.controller) as java.util.concurrent.atomic.AtomicLong
        val before = epoch.get()
        val original = fixture.settings.load()
        repeat(2) { index ->
            val saved = java.util.concurrent.atomic.AtomicReference<Boolean?>()
            mainThread {
                fixture.controller.saveModelSettings(original, false, false) { saved.set(it) }
            }
            awaitControl("same-value configuration save #${index + 1}") {
                saved.get() != null && !fixture.controller.dataBusy
            }
            assertEquals(java.lang.Boolean.TRUE, saved.get())
        }
        assertEquals(before + 2, epoch.get())
        assertEquals(0, fixture.opens.get())
    }

    fun testOrdinaryTextWithVideoHistoryNeverFallsBackToGenericTextModel() {
        val db = fixture.database
        val conversation = db.create(db.selectedAssistantId())
        val run = RunId("historical-video")
        val user = Message(MessageId("historical-user"), conversation.id, run, Message.USER_ROLE,
            listOf(MessageBlock.Text("body", "old question")), RunStatus.COMPLETED)
        val assistant = user.copy(id = MessageId("historical-assistant"), role = Message.ASSISTANT_ROLE, status = RunStatus.RUNNING)
        val owner = MessageVideoOwner(conversation.id.value, user.id.value)
        db.send(user, assistant, videoInputs = listOf(MessageVideoInput(owner, listOf(MessageVideoReference(
            owner, "12345678-1234-1234-1234-123456789abc", "video/mp4", 3, "a".repeat(64), 1000, 32, 24)))))
        db.save(assistant.copy(status = RunStatus.COMPLETED))
        // Enabled but unusable auxiliary vision is irrelevant to the original-video route.
        fixture.controller.independentModels.save(app.daiyu.android.storage.IndependentModelSettings.Slot.VISION,
            app.daiyu.android.storage.ModelSettings("https://vision.invalid", "vision", "test-key", false),
            null, true, null)
        mainThread { activity.select(conversation) }
        val text = "  ordinary continuation, original whitespace  "
        setDraft(text)
        node("chat-send").performClick()
        awaitTextSendResult(activity)
        // Fixture uses example.invalid: production evidence rejects it BEFORE any models GET.
        // A plain ModelPort or the last visible row may never substitute for video authorization.
        assertEquals(0, fixture.opens.get())
        assertDraft(text)
        assertEquals(2, ControllerVideoHistory(db).capture(conversation.id).rows.size)
        assertTrue(fixture.controller.problem?.contains("原视频") == true)
    }

    fun testProductionReceiptConsumerNeverOverwritesANewerEditor() {
        val db = fixture.database
        val conversation = db.create(db.selectedAssistantId())
        mainThread { activity.select(conversation) }
        setDraft("original caption")
        lateinit var editor: ControllerVideoEditor
        mainThread {
            editor = (MainActivity::class.java.getDeclaredField("videoUi").apply { isAccessible = true }
                .get(activity) as ControllerVideoUi).editor()
        }
        val owner = VideoAttachmentOwner(conversation.id.value, "receipt-draft")
        val video = VideoAttachment(owner, "12345678-1234-1234-1234-123456789abc", "video/mp4", 3, "a".repeat(64), 1000, 32, 24)
        val request = VideoDraftRequest(conversation.id.value, owner, 2, editor.text, listOf(video))
        val run = RunId("accepted-run")
        val user = Message(MessageId("accepted-user"), conversation.id, run, Message.USER_ROLE,
            listOf(MessageBlock.Text("text", editor.text)), RunStatus.COMPLETED)
        val messageOwner = MessageVideoOwner(conversation.id.value, user.id.value)
        val assistant = user.copy(id = MessageId("accepted-assistant"), role = Message.ASSISTANT_ROLE, status = RunStatus.RUNNING)
        val saved = db.send(user, assistant,
            videoInputs = listOf(MessageVideoInput(messageOwner, listOf(MessageVideoReference(messageOwner,
                video.id, video.mime, video.bytes, video.sha256, video.durationMs, video.width, video.height)))))
        db.save(assistant.copy(status = RunStatus.COMPLETED))
        val receipt = VideoDraftReceipt(request.conversation, owner, request.revision, request.videos,
            VideoDraftOutcome.ACCEPTED, conversation.id.value)
        // Freeze the admitted view before editing, then publish a real DB receipt at the receiving
        // boundary. Only delivery is injected; Main's production receipt consumer is invoked below.
        mainThread {
            MainActivity::class.java.getDeclaredField("videoReceiptRequest").apply { isAccessible = true }.set(activity, request)
            MainActivity::class.java.getDeclaredField("videoReceiptEditor").apply { isAccessible = true }.set(activity, editor)
        }
        setDraft("new text after the accepted transaction")
        mainThread {
            ChatController::class.java.getDeclaredField("pendingVideoState").apply { isAccessible = true }
                .set(fixture.controller, ChatController.VideoSendState(request, editor, receipt, saved))
            MainActivity::class.java.getDeclaredMethod("settleRetainedVideoReceipt").apply { isAccessible = true }.invoke(activity)
        }
        assertDraft("new text after the accepted transaction")
        assertEquals("new text after the accepted transaction", db.conversations().single { it.id == conversation.id }.draft)
        assertNull(fixture.controller.videoSendState())
        assertEquals(0, fixture.opens.get())
    }

    fun testOriginalVideoMenuMountsRealHostAndNavigationRetiresPickerTicket() {
        openVideo()
        node("video_attachments").assertDoesNotExist()
        lateinit var before: VideoChatHost.Selection
        var visit = 0L
        mainThread {
            val host = fixture.controller.videoHost
            assertTrue(host.state.visible)
            visit = host.state.visit
            before = checkNotNull(host.requestSelection())
            // A -> A is a real route/editor departure, not identity equality.
            activity.select(null)
        }
        node("video_attachments").assertDoesNotExist()
        mainThread {
            val host = fixture.controller.videoHost
            assertTrue(host.state.visit > visit)
            assertFalse(host.picked(before, listOf(Uri.parse("content://never-open/stale"))))
            assertTrue(host.state.items.isEmpty())
        }
        openVideo()
        node("video_attachments").assertDoesNotExist()
        mainThread { assertTrue(fixture.controller.videoHost.state.visible) }
    }

    fun testSidebarAndRecreationDetachTheProductionVideoMount() {
        openVideo()
        node("video_attachments").assertDoesNotExist()
        val host = fixture.controller.videoHost
        lateinit var stale: VideoChatHost.Selection
        mainThread { stale = checkNotNull(host.requestSelection()) }
        openSettings()
        mainThread {
            assertFalse(host.state.visible)
            assertFalse(host.picked(stale, listOf(Uri.parse("content://never-open/navigation"))))
        }
        backToChat(activity)
        mainThread { activity.select(null) }
        openVideo()
        mainThread { stale = checkNotNull(host.requestSelection()) }
        val oldUi = MainActivity::class.java.getDeclaredField("videoUi").apply { isAccessible = true }.get(activity) as ControllerVideoUi
        activity = recreateActivity(activity)
        mainThread {
            val routeEpoch = ChatController::class.java.getDeclaredField("videoRouteEpoch")
                .apply { isAccessible = true }.get(fixture.controller) as java.util.concurrent.atomic.AtomicLong
            val beforeStaleCallback = routeEpoch.get()
            fixture.controller.invalidateVideo(oldUi)
            assertEquals(beforeStaleCallback, routeEpoch.get())
            assertFalse(fixture.controller.ownsVideoUi(oldUi))
            assertSame(host, fixture.controller.videoHost)
            assertFalse(host.picked(stale, listOf(Uri.parse("content://never-open/destroyed"))))
        }
    }
    fun testUnadoptedReceiptWaitsForVisibleEditorBeforeAcknowledgement() {
        val db = fixture.database
        val conversation = db.create(db.selectedAssistantId())
        mainThread { activity.select(conversation) }
        setDraft("accepted caption")
        mainThread {
            val ui = MainActivity::class.java.getDeclaredField("videoUi").apply { isAccessible = true }
                .get(activity) as ControllerVideoUi
            val editor = ui.editor()
            assertTrue(editor.visible)
            val owner = VideoAttachmentOwner(conversation.id.value, "visible-receipt")
            val video = VideoAttachment(owner, "12345678-1234-1234-1234-123456789abc", "video/mp4", 3, "a".repeat(64), 1000, 32, 24)
            val request = VideoDraftRequest(conversation.id.value, owner, 1, editor.text, listOf(video))
            val run = RunId("visible-receipt")
            val user = Message(MessageId("visible-user"), conversation.id, run, Message.USER_ROLE,
                listOf(MessageBlock.Text("text", editor.text)), RunStatus.COMPLETED)
            val messageOwner = MessageVideoOwner(conversation.id.value, user.id.value)
            val assistant = user.copy(id = MessageId("visible-assistant"), role = Message.ASSISTANT_ROLE, status = RunStatus.RUNNING)
            val saved = db.send(user, assistant,
                videoInputs = listOf(MessageVideoInput(messageOwner, listOf(MessageVideoReference(messageOwner,
                    video.id, video.mime, video.bytes, video.sha256, video.durationMs, video.width, video.height)))))
            db.save(assistant.copy(status = RunStatus.COMPLETED))
            val receipt = VideoDraftReceipt(request.conversation, owner, request.revision, request.videos,
                VideoDraftOutcome.ACCEPTED, conversation.id.value)
            ChatController::class.java.getDeclaredField("pendingVideoState").apply { isAccessible = true }
                .set(fixture.controller, ChatController.VideoSendState(request, editor, receipt, saved))
            val foreground = MainActivity::class.java.getDeclaredField("voiceForeground").apply { isAccessible = true }
            val adopted = MainActivity::class.java.getDeclaredField("videoReceiptRequest").apply { isAccessible = true }
            val settle = MainActivity::class.java.getDeclaredMethod("settleRetainedVideoReceipt").apply { isAccessible = true }
            foreground.setBoolean(activity, false)
            try {
                settle.invoke(activity)
                assertNotNull(fixture.controller.videoSendState())
                assertNull(adopted.get(activity))
            } finally { foreground.setBoolean(activity, true) }
            settle.invoke(activity)
            assertNull(fixture.controller.videoSendState())
        }
        assertDraft("")
        assertEquals(0, fixture.opens.get())
    }

    fun testVideoSubmissionDisablesTerminalAndRejectsStaleVirtualDisplayEntry() {
        setDraft("keep video caption")
        mainThread {
            @Suppress("UNCHECKED_CAST")
            val sending = MainActivity::class.java.getDeclaredField("videoSending\$delegate")
                .apply { isAccessible = true }.get(activity) as androidx.compose.runtime.MutableState<Boolean>
            sending.value = true
        }
        ui.waitForIdle()
        node("terminal-chat-entry").assertIsNotEnabled()
        mainThread {
            MainActivity::class.java.getDeclaredMethod("openVirtualDisplayPreparation").apply { isAccessible = true }
                .invoke(activity)
            for (name in listOf("virtualDisplayEditor", "virtualDisplayPlanVisit")) {
                val state = MainActivity::class.java.getDeclaredField("${name}\$delegate")
                    .apply { isAccessible = true }.get(activity) as androidx.compose.runtime.State<*>
                assertNull(state.value)
            }
            assertNull(fixture.controller.virtualDisplayEntry())
        }
        assertDraft("keep video caption")
        assertEquals(0, fixture.opens.get())
    }

}
