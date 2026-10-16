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
    private fun openVideo() {
        ui.onNodeWithContentDescription("附件").performClick()
        ui.onNodeWithText("视频").performClick()
        ui.waitForIdle()
    }
    fun testProductionConfigurationSaveAdvancesEpochEvenWhenValuesAreRestored() {
        val epoch = ChatController::class.java.getDeclaredField("videoConfigurationEpoch")
            .apply { isAccessible = true }.get(fixture.controller) as java.util.concurrent.atomic.AtomicLong
        val before = epoch.get()
        val original = fixture.settings.load()
        mainThread { fixture.controller.saveModelSettings(original, false, false) {} }
        awaitControl("same-value configuration save") { !fixture.controller.dataBusy }
        mainThread { fixture.controller.saveModelSettings(original, false, false) {} }
        awaitControl("same-value configuration restore") { !fixture.controller.dataBusy }
        assertEquals(before + 2, epoch.get())
        assertEquals(0, fixture.opens.get())
    }

    fun testOrdinaryTextWithVideoHistoryNeverFallsBackToGenericTextModel() {
        val db = fixture.database
        val conversation = db.create(db.selectedAssistantId())
        val run = RunId("historical-video")
        val user = Message(MessageId("historical-user"), conversation.id, run, Message.USER_ROLE,
            listOf(MessageBlock.Text("body", "old question")), RunStatus.COMPLETED)
        val assistant = user.copy(id = MessageId("historical-assistant"), role = Message.ASSISTANT_ROLE)
        val owner = MessageVideoOwner(conversation.id.value, user.id.value)
        db.send(user, assistant, videoInputs = listOf(MessageVideoInput(owner, listOf(MessageVideoReference(
            owner, "original", "video/mp4", 3, "a".repeat(64), 1000, 32, 24)))))
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
        val video = VideoAttachment(owner, "original", "video/mp4", 3, "a".repeat(64), 1000, 32, 24)
        val request = VideoDraftRequest(conversation.id.value, owner, 2, editor.text, listOf(video))
        val run = RunId("accepted-run")
        val user = Message(MessageId("accepted-user"), conversation.id, run, Message.USER_ROLE,
            listOf(MessageBlock.Text("text", editor.text)), RunStatus.COMPLETED)
        val messageOwner = MessageVideoOwner(conversation.id.value, user.id.value)
        val saved = db.send(user, user.copy(id = MessageId("accepted-assistant"), role = Message.ASSISTANT_ROLE),
            videoInputs = listOf(MessageVideoInput(messageOwner, listOf(MessageVideoReference(messageOwner,
                video.id, video.mime, video.bytes, video.sha256, video.durationMs, video.width, video.height)))))
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
        node("video_attachments").assertExists()
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
        node("video_attachments").assertExists()
        mainThread { assertTrue(fixture.controller.videoHost.state.visible) }
    }

    fun testSidebarAndRecreationDetachTheProductionVideoMount() {
        openVideo()
        node("video_attachments").assertExists()
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
            val video = VideoAttachment(owner, "original", "video/mp4", 3, "a".repeat(64), 1000, 32, 24)
            val request = VideoDraftRequest(conversation.id.value, owner, 1, editor.text, listOf(video))
            val run = RunId("visible-receipt")
            val user = Message(MessageId("visible-user"), conversation.id, run, Message.USER_ROLE,
                listOf(MessageBlock.Text("text", editor.text)), RunStatus.COMPLETED)
            val messageOwner = MessageVideoOwner(conversation.id.value, user.id.value)
            val saved = db.send(user, user.copy(id = MessageId("visible-assistant"), role = Message.ASSISTANT_ROLE),
                videoInputs = listOf(MessageVideoInput(messageOwner, listOf(MessageVideoReference(messageOwner,
                    video.id, video.mime, video.bytes, video.sha256, video.durationMs, video.width, video.height)))))
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
            MainActivity::class.java.getDeclaredField("videoSending").apply { isAccessible = true }
                .setBoolean(activity, true)
        }
        ui.waitForIdle()
        node("terminal-chat-entry").assertIsNotEnabled()
        mainThread {
            MainActivity::class.java.getDeclaredMethod("openVirtualDisplayPreparation").apply { isAccessible = true }
                .invoke(activity)
            assertNull(MainActivity::class.java.getDeclaredField("virtualDisplayEditor")
                .apply { isAccessible = true }.get(activity))
        }
        assertDraft("keep video caption")
        assertEquals(0, fixture.opens.get())
    }

}
