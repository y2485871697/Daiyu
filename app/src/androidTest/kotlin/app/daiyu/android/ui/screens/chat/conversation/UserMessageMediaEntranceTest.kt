package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.domain.*
import app.daiyu.android.media.video.MessageVideoOwner
import app.daiyu.android.media.video.MessageVideoReference
import app.daiyu.android.ui.app.AgentAppTheme

class UserMessageMediaEntranceTest : WiredActivityTestCase() {
    private var request by mutableStateOf<UserMessageEntrance?>(null)
    private var motion: UserMessageEntranceMotion? = null
    private var rows by mutableStateOf<List<ChatTurnUi>>(List(24) { ChatTurnUi.User("history-$it", "历史消息 $it") })
    private var preview by mutableStateOf<ChatSubmissionPreview?>(null)
    private var navigation by mutableStateOf(ChatScrollRequest(1))
    private var thumbnail: ImageBitmap = android.graphics.Bitmap.createBitmap(120, 240,
        android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.MAGENTA) }.asImageBitmap()
    private val loader = object : MessageImageLoader {
        override suspend fun load(expectedOwner: MessageImageOwner, reference: MessageImageReference, maxEdge: Int) = thumbnail
        override fun cached(expectedOwner: MessageImageOwner, reference: MessageImageReference, maxEdge: Int) = thumbnail
        override suspend fun loadVideo(expectedOwner: MessageVideoOwner, reference: MessageVideoReference, maxEdge: Int) = thumbnail
        override fun cachedVideo(expectedOwner: MessageVideoOwner, reference: MessageVideoReference, maxEdge: Int) = thumbnail
    }

    private fun mapped(video: Boolean, portrait: Boolean): ChatTurnUi.User {
        val owner = MessageImageOwner("media-chat", "saved-media-user")
        val width = if (portrait) 1080 else 1920
        val height = if (portrait) 2344 else 1080
        thumbnail = android.graphics.Bitmap.createBitmap(width / 12, height / 12,
            android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.MAGENTA) }.asImageBitmap()
        val image = MessageImageReference(owner, "00000000-0000-0000-0000-000000000001",
            "image/jpeg", 24, "a".repeat(64), width, height)
        val message = Message(MessageId(owner.messageId), ConversationId(owner.conversationId), RunId("media-run"),
            Message.USER_ROLE, if (video) emptyList() else listOf(MessageBlock.Image(image)), RunStatus.COMPLETED)
        val videoOwner = MessageVideoOwner(owner.conversationId, owner.messageId)
        val reference = MessageVideoReference(videoOwner, "00000000-0000-0000-0000-000000000002",
            "video/mp4", 24L, "b".repeat(64), 74_464L, width, height)
        return (ChatTurnMapper.map(listOf(message), messageVideos = {
            if (video) listOf(MessageVideoUi(videoOwner, reference)) else emptyList()
        }).single() as ChatTurnUi.User).also {
            assertEquals("", it.text)
            assertTrue("use the real encoded mapper key, not a hand-built id", it.listKey != it.id)
            assertTrue(it.matchesKey(owner.messageId))
            assertTrue(it.content.any { content -> if (video) content is MessageContentUi.Video else content is MessageContentUi.Image })
        }
    }

    private fun show() {
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                val color = conversationColors().surface.copy(alpha = 1f)
                val entrance = rememberUserMessageEntrance(request, active = true, awaitPlacement = true)
                SideEffect { motion = entrance }
                Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                    Box(Modifier.width(320.dp).height(420.dp).background(color).testTag("media-entrance-host")) {
                        ChatMessageList(submissionTurns(rows, preview), ChatScreenActions(), Modifier.fillMaxSize(),
                            scrollRequest = navigation, imageLoader = loader, composerBottomInset = 96.dp,
                            userMessageEntrance = entrance, hasTurnExtras = { false })
                        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(96.dp).background(color))
                    }
                }
            }
        } }
        ui.waitForIdle()
        ui.mainClock.autoAdvance = false
    }

    private fun change(block: () -> Unit) {
        instrumentation.runOnMainSync(block)
        ui.mainClock.advanceTimeByFrame()
        ui.waitForIdle()
    }

    private fun finishEntrance() {
        var frames = 0
        while (checkNotNull(motion).progress.value < 1f && frames++ < 180) {
            ui.mainClock.advanceTimeByFrame()
            ui.waitForIdle()
        }
        assertEquals("mapped media must not remain transparent waiting for a raw message id", 1f,
            checkNotNull(motion).progress.value, .001f)
    }

    private fun assertMediaPaint(video: Boolean, playVisible: Boolean) {
        val media = if (video) "message-video:1" else "message-image:1"
        ui.onNodeWithTag(media, useUnmergedTree = true).assertIsDisplayed()
        if (video) {
            ui.onNodeWithTag("message-video-thumbnail:1", useUnmergedTree = true).assertExists()
            ui.onNodeWithTag("message-video-play:1", useUnmergedTree = true).assertExists()
            if (playVisible) ui.onNodeWithTag("message-video-play:1", useUnmergedTree = true).assertIsDisplayed()
        }
        val before = ui.mainClock.currentTime
        val pixels = captureScreenNode(ui.onNodeWithTag("media-entrance-host"), advanceAnimationClock = false).toPixelMap()
        assertEquals("sampling must not advance the animation", before, ui.mainClock.currentTime)
        var painted = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val color = pixels[x, y]
            if (color.red > .8f && color.blue > .8f && color.green < .2f) painted++
        }
        assertTrue("real thumbnail pixels must paint through the entrance layer, not just reserve geometry", painted > 100)
    }

    private fun verify(video: Boolean, portrait: Boolean, pending: Boolean) {
        val durable = mapped(video, portrait)
        show()
        val history = rows
        val ticket = UserMessageEntrance("media-ticket")
        change {
            request = ticket
            navigation = ChatScrollRequest(2)
            if (pending) preview = ChatSubmissionPreview(ticket.key)
            else {
                ticket.messageId = durable.id
                rows = history + durable
            }
        }
        if (pending) {
            ui.onNodeWithTag("chat-send-preview").assertExists()
            val sameMotion = checkNotNull(motion)
            change {
                ticket.messageId = durable.id
                preview = ChatSubmissionPreview(ticket.key, settledUserId = durable.id, settledAssistantId = "media-answer")
                rows = history + ChatTurnUi.Assistant("media-answer")
            }
            ui.onNodeWithTag("chat-send-preview").assertExists()
            change {
                rows = history + durable + ChatTurnUi.Assistant("media-answer")
                preview = null
                navigation = ChatScrollRequest(3)
            }
            assertSame("settlement must retain the entrance clock", sameMotion, motion)
        }
        finishEntrance()
        assertMediaPaint(video, playVisible = video && !portrait)
        ui.onNodeWithTag("chat-send-preview").assertDoesNotExist()
    }

    fun testMappedPortraitImageStartsEntranceWithoutATextPreview() = verify(video = false, portrait = true, pending = false)
    fun testMappedVideoStartsEntranceAndPaintsItsPlayOverlay() = verify(video = true, portrait = false, pending = false)
    fun testImageOnlyPendingSettlesToTheRealMappedMediaRow() = verify(video = false, portrait = false, pending = true)
    fun testPortraitVideoOnlyPendingSettlesWithoutAFullVisibilityDeadlock() = verify(video = true, portrait = true, pending = true)
    fun testMediaPresentationCannotBeginBeforeItsFirstFrameIsAdmitted() {
        val durable = mapped(video = true, portrait = false)
        show()
        val ticket = UserMessageEntrance(durable.id, waitForMedia = true)
        change {
            request = ticket
            rows = rows + durable
            navigation = ChatScrollRequest(2)
        }
        repeat(6) { ui.mainClock.advanceTimeByFrame(); ui.waitForIdle() }
        val sameMotion = checkNotNull(motion)
        assertEquals("placement alone cannot start a cold media entrance", 0f, sameMotion.progress.value, .001f)
        change { ticket.mediaReady = true }
        repeat(22) {
            ui.mainClock.advanceTimeByFrame()
            ui.waitForIdle()
            if (sameMotion.progress.value >= .5f) assertMediaPaint(video = true, playVisible = true)
        }
        assertSame(sameMotion, motion)
        assertEquals(1f, sameMotion.progress.value, .001f)
    }

    fun testFailedMediaProjectionShowsAnExplicitUnavailableMessage() {
        val durable = mapped(video = true, portrait = false)
        show()
        val ticket = UserMessageEntrance(durable.id, waitForMedia = true)
        change {
            request = ticket
            rows = rows + durable.copy(content = emptyList())
            navigation = ChatScrollRequest(2)
        }
        change { ticket.mediaUnavailable = true; ticket.mediaReady = true }
        finishEntrance()
        ui.onNodeWithText("附件暂不可用").assertIsDisplayed()
        ui.onNodeWithTag("message-video-play:1").assertDoesNotExist()
    }

}
