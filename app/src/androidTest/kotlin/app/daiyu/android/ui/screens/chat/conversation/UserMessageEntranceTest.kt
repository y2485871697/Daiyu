package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme
import kotlin.math.abs

private class UserEntranceScale : MotionDurationScale {
    var factor = 1f
    override val scaleFactor get() = factor
}

class UserMessageEntranceTest private constructor(private val scale: UserEntranceScale) : WiredActivityTestCase(scale) {
    constructor() : this(UserEntranceScale())
    private var request by mutableStateOf<UserMessageEntrance?>(null)
    private var turn by mutableStateOf(ChatTurnUi.User("history", "发送时的用户消息"))
    private var mounted by mutableStateOf(true)
    private var active by mutableStateOf(true)
    private var motion: UserMessageEntranceMotion? = null
    private var awaitPlacement by mutableStateOf(false)
    private var background = Color.Unspecified
    private var density = 1f
    private var actionCalls = 0

    private fun show() {
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                background = conversationColors().surface.copy(alpha = 1f)
                density = LocalDensity.current.density
                Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                Box(Modifier.width(320.dp).height(180.dp).background(background).testTag("entrance-raster")) {
                    if (mounted) {
                        val current = rememberUserMessageEntrance(request, active, awaitPlacement)
                        SideEffect { motion = current }
                        Column(Modifier.testTag("entrance-slot")) {
                            key(turn.listKey) { UserTurn(turn, ChatScreenActions(onCopy = { actionCalls++ }, onEditUser = { actionCalls++ },
                                onBranch = { actionCalls++ }, onDeleteTurn = { actionCalls++ }), entrance = current) }
                        }
                    }
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

    private fun progress() = checkNotNull(motion).progress.value
    private fun finish() {
        ui.mainClock.advanceTimeBy(360)
        ui.waitForIdle()
        assertEquals(1f, progress(), .001f)
    }

    private fun ink(): Float {
        val before = ui.mainClock.currentTime
        val pixels = captureScreenNode(ui.onNodeWithTag("entrance-raster"), advanceAnimationClock = false).toPixelMap()
        assertEquals("sampling must not advance the animation clock", before, ui.mainClock.currentTime)
        var ink = 0f
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val color = pixels[x, y]
            ink += abs(color.red - background.red) + abs(color.green - background.green) + abs(color.blue - background.blue)
        }
        return ink
    }

    private fun bubbleBackgroundContrast(): Float {
        val bubble = ui.onNodeWithTag("message-user-bubble:new-send").fetchSemanticsNode().boundsInRoot
        val raster = ui.onNodeWithTag("entrance-raster")
        val bounds = raster.fetchSemanticsNode().boundsInRoot
        val before = ui.mainClock.currentTime
        val image = captureScreenNode(raster, advanceAnimationClock = false)
        val pixels = image.toPixelMap()
        assertEquals(before, ui.mainClock.currentTime)
        val directory = java.io.File(activity.getExternalFilesDir(null), "visual-diagnostics/user-message-entrance").apply { mkdirs() }
        java.io.File(directory, "frame-$before.png").outputStream().use {
            check(image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
        // This top-padding patch stays inside every scale, away from text and corners.
        val x = (bubble.right - 24f * density - bounds.left).toInt()
        val y = (bubble.top + 4f * density - bounds.top).toInt()
        assertTrue("flat bubble sampling patch must stay in the raster", x > 0 && y > 0 &&
            x + 1 < pixels.width && y + 1 < pixels.height)
        val backdrop = pixels[2, pixels.height - 3]
        var contrast = 0f
        for (dy in -1..1) for (dx in -1..1) {
            val color = pixels[x + dx, y + dy]
            contrast += abs(color.red - backdrop.red) + abs(color.green - backdrop.green) + abs(color.blue - backdrop.blue)
        }
        android.util.Log.i("UserEntrancePixels", "clock=$before progress=${progress()} bubble=$bubble " +
            "rasterOnScreen=${raster.fetchSemanticsNode().positionOnScreen} " +
            "patch=$x,$y backdrop=$backdrop sample=${pixels[x, y]} contrast=${contrast / 9f}")
        return contrast / 9f
    }

    fun testNewSendScalesFromTopRightTowardBottomLeftWithoutChangingTheRowExtent() {
        show()
        change {
            request = UserMessageEntrance("new-send")
            turn = turn.copy(id = "new-send", listKey = "new-send", pending = true)
        }
        val initial = progress()
        assertTrue("first frames must be nonterminal", initial < 1f)
        val slot = ui.onNodeWithTag("entrance-slot").fetchSemanticsNode().boundsInRoot
        val startBubble = ui.onNodeWithTag("message-user-bubble:new-send").fetchSemanticsNode().boundsInRoot
        val startInk = ink()
        val startContrast = bubbleBackgroundContrast()
        ui.mainClock.advanceTimeBy(80)
        ui.waitForIdle()
        assertTrue(progress() > initial && progress() < 1f)
        val middleContrast = bubbleBackgroundContrast()
        assertTrue("background alpha visibly increases independently of scaled area: start=$startContrast middle=$middleContrast progress=${progress()}",
            middleContrast > startContrast + .001f)
        val middleBubble = ui.onNodeWithTag("message-user-bubble:new-send").fetchSemanticsNode().boundsInRoot
        assertEquals(startBubble.top, middleBubble.top, .75f)
        assertEquals(startBubble.right, middleBubble.right, .75f)
        assertEquals(slot, ui.onNodeWithTag("entrance-slot").fetchSemanticsNode().boundsInRoot)
        ui.mainClock.advanceTimeBy(100)
        ui.waitForIdle()
        val reboundBubble = ui.onNodeWithTag("message-user-bubble:new-send").fetchSemanticsNode().boundsInRoot
        assertEquals(startBubble.top, reboundBubble.top, .75f)
        assertEquals(startBubble.right, reboundBubble.right, .75f)
        assertEquals(slot, ui.onNodeWithTag("entrance-slot").fetchSemanticsNode().boundsInRoot)
        finish()
        val endBubble = ui.onNodeWithTag("message-user-bubble:new-send").fetchSemanticsNode().boundsInRoot
        assertEquals("top-right anchor keeps its right edge", endBubble.right, startBubble.right, .75f)
        assertEquals("top-right anchor keeps its top edge", endBubble.top, startBubble.top, .75f)
        assertTrue("left edge expands toward the left", startBubble.left > endBubble.left)
        assertTrue("bottom edge expands downward", startBubble.bottom < endBubble.bottom)
        assertTrue("scale overshoots slightly before settling", reboundBubble.width > endBubble.width)
        assertEquals(slot, ui.onNodeWithTag("entrance-slot").fetchSemanticsNode().boundsInRoot)
        assertTrue("actual rendered bubble gains visible ink", ink() > startInk + 1f)
        assertTrue("background reaches fully opaque color", bubbleBackgroundContrast() > middleContrast + .001f)
    }

    fun testPreviewToSavedKeepsTheSameInFlightAnimation() {
        show()
        val ticket = UserMessageEntrance("ticket")
        change { request = ticket; turn = turn.copy(id = "ticket", listKey = "ticket", pending = true) }
        ui.mainClock.advanceTimeBy(80)
        ui.waitForIdle()
        val originalMotion = checkNotNull(motion)
        val before = progress()
        change { ticket.messageId = "saved" }
        val waitingPreviewWidth = ui.onNodeWithTag("message-user-bubble:ticket").fetchSemanticsNode().boundsInRoot.width
        change { turn = turn.copy(id = "saved", listKey = "saved", pending = false) }
        assertSame(originalMotion, motion)
        assertTrue("receipt replacement must not restart at zero", progress() >= before)
        ui.onNodeWithTag("message-user-bubble:ticket").assertDoesNotExist()
        ui.onNodeWithTag("message-user-bubble:saved").assertExists()
        finish()
        assertTrue("mapping the receipt before history arrives must not snap the preview to full size",
            waitingPreviewWidth < ui.onNodeWithTag("message-user-bubble:saved").fetchSemanticsNode().boundsInRoot.width)
    }

    fun testConsumedSendDoesNotReplayAfterLazyRowOrPageRemount() {
        show()
        change { request = UserMessageEntrance("sent"); turn = turn.copy(id = "sent", listKey = "sent") }
        finish()
        change { mounted = false }
        change { mounted = true }
        assertEquals(1f, progress(), .001f)
        change { turn = turn.copy(text = "编辑后的相同用户消息") }
        assertEquals(1f, progress(), .001f)
    }

    fun testHistoryAndConversationSwitchHaveNoSendAnimation() {
        show()
        assertNull(motion)
        assertTrue(ink() > 1f)
        change { turn = ChatTurnUi.User("other-history", "另一个会话的历史") }
        assertNull(motion)
        change { mounted = false }
        change { mounted = true }
        assertNull(motion)
    }

    fun testSameTextWithANewSendTicketGetsANewEntrance() {
        show()
        change { request = UserMessageEntrance("one"); turn = turn.copy(id = "one", listKey = "one") }
        finish()
        change { request = UserMessageEntrance("two"); turn = turn.copy(id = "two", listKey = "two") }
        assertTrue(progress() < 1f)
        finish()
    }

    fun testInactivePageSettlesWithoutReplayingOnReturn() {
        show()
        change { request = UserMessageEntrance("inactive"); turn = turn.copy(id = "inactive", listKey = "inactive") }
        change { active = false }
        assertEquals(1f, progress(), .001f)
        change { active = true }
        assertEquals(1f, progress(), .001f)
    }

    fun testZeroDurationScaleMakesNewSendImmediatelyVisible() {
        scale.factor = 0f
        show()
        change { request = UserMessageEntrance("instant"); turn = turn.copy(id = "instant", listKey = "instant") }
        ui.mainClock.advanceTimeByFrame()
        ui.waitForIdle()
        assertEquals(1f, progress(), .001f)
        assertTrue(ink() > 1f)
    }
    fun testUnpresentedCandidateDoesNotConsumeTheVisiblePagesEntrance() {
        active = false
        show()
        val ticket = UserMessageEntrance("candidate")
        change { request = ticket; turn = turn.copy(id = ticket.key, listKey = ticket.key) }
        ui.mainClock.advanceTimeBy(500)
        ui.waitForIdle()
        assertFalse("an invisible candidate cannot claim the send", ticket.claimed)
        change { active = true }
        assertTrue("the first presented frames must still animate", progress() < 1f)
        finish()
    }

    fun testSendWaitsForTheRealRowInsteadOfSpendingItsAnimationOffscreen() {
        awaitPlacement = true
        show()
        val ticket = UserMessageEntrance("waiting-row")
        change { request = ticket; turn = turn.copy(id = ticket.key, listKey = ticket.key) }
        ui.mainClock.advanceTimeBy(500)
        ui.waitForIdle()
        assertFalse(ticket.claimed)
        assertEquals(0f, progress(), .001f)
        change { checkNotNull(motion).positioned = true }
        assertTrue(progress() < 1f)
        finish()
    }

    fun testActionsFadeAfterTheBubbleWithoutMovingOrChangingItsSlot() {
        show()
        change { request = UserMessageEntrance("new-send"); turn = turn.copy(id = "new-send", listKey = "new-send") }
        val actions = ui.onNodeWithTag("message-user-actions:new-send")
        val start = actions.fetchSemanticsNode().boundsInRoot
        val slot = ui.onNodeWithTag("entrance-slot").fetchSemanticsNode().boundsInRoot
        assertEquals(0f, userMessageActionEntranceAlpha(progress()), .001f)
        ui.mainClock.advanceTimeBy(144)
        ui.waitForIdle()
        assertTrue(userMessageActionEntranceAlpha(progress()) in .01f.. .99f)
        assertEquals(start, actions.fetchSemanticsNode().boundsInRoot)
        finish()
        assertEquals(1f, userMessageActionEntranceAlpha(progress()), .001f)
        assertEquals(start, actions.fetchSemanticsNode().boundsInRoot)
        assertEquals(slot, ui.onNodeWithTag("entrance-slot").fetchSemanticsNode().boundsInRoot)
    }

    fun testImeTravelAdmitsTheBubbleLateAndHandlesEqualOrOpeningInsets() {
        assertFalse(userMessageEntranceImeReady(current = 800, source = 800, target = 40, navigation = 40))
        assertFalse(userMessageEntranceImeReady(current = 500, source = 800, target = 40, navigation = 40))
        assertTrue(userMessageEntranceImeReady(current = 280, source = 800, target = 40, navigation = 40))
        assertTrue(userMessageEntranceImeReady(current = 40, source = 800, target = 40, navigation = 40))
        assertFalse(userMessageEntranceImeReady(current = 300, source = 40, target = 800, navigation = 40))
        assertFalse(userMessageEntranceImeReady(current = 800, source = 800, target = 800, navigation = 40))
    }

    fun testLeavingBeforePlacementDoesNotPlayAnOldEntranceOnReturn() {
        awaitPlacement = true
        show()
        val ticket = UserMessageEntrance("leave-before-placement")
        change { request = ticket; turn = turn.copy(id = ticket.key, listKey = ticket.key) }
        assertFalse(ticket.claimed)
        change { active = false }
        assertTrue(ticket.claimed)
        assertEquals(1f, progress(), .001f)
        change { active = true; checkNotNull(motion).positioned = true }
        assertEquals(1f, progress(), .001f)
    }

    fun testPendingActionsWaitForEntranceAndOnlyCopyIsEnabled() {
        show()
        change { request = UserMessageEntrance("pending-actions"); turn = turn.copy(id = "pending-actions", listKey = "pending-actions", pending = true) }
        ui.onNodeWithContentDescription("复制回答").assertDoesNotExist()
        finish()
        for (label in listOf("编辑", "分支", "删除")) {
            ui.onNodeWithContentDescription(label).assertIsNotEnabled().performTouchInput { click() }
        }
        assertEquals("pending mutations must not reach the host", 0, actionCalls)
        ui.onNodeWithContentDescription("复制回答").assertIsEnabled().performClick()
        assertEquals(1, actionCalls)
        change { turn = turn.copy(pending = false) }
        ui.onNodeWithContentDescription("分支").assertIsEnabled().performClick()
        assertEquals("settled real actions must remain connected", 2, actionCalls)
    }

    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    fun testRetainedPagePaintKeepsFollowingImeUntilTheCandidateIsCommitted() {
        val ime = androidx.compose.foundation.layout.MutableWindowInsets(WindowInsets(bottom = 200))
        var owner by mutableStateOf("old")
        var allowNext by mutableStateOf(false)
        var painted: Any? = null
        ChatPageHostProbe.observer = ChatPageHostProbe.Observer(
            allowReady = { it == "old" || allowNext },
            onDraw = { page, _, _ -> painted = page },
        )
        try {
            mainThread { activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    density = LocalDensity.current.density
                    val page = owner
                    Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
                        ChatPageHost(owner, Modifier.width(320.dp).height(420.dp).testTag("ime-handoff-raster"), motionInsets = ime) { _, ready ->
                            SideEffect { ready(true) }
                            Box(Modifier.fillMaxSize().background(if (page == "old") Color.White else Color.LightGray).windowInsetsPadding(ime)) {
                                Box(Modifier.align(androidx.compose.ui.Alignment.BottomCenter).fillMaxWidth().height(18.dp).background(Color.Green))
                            }
                        }
                    }
                }
            } }
            ui.waitForIdle()
            ui.mainClock.autoAdvance = false
            fun assertPaintedBottom(inset: Int, page: String) {
                val before = ui.mainClock.currentTime
                val image = captureScreenNode(ui.onNodeWithTag("ime-handoff-raster"), advanceAnimationClock = false)
                assertEquals(before, ui.mainClock.currentTime)
                val pixels = image.toPixelMap()
                val rows = (0 until pixels.height).filter { y ->
                    val c = pixels[pixels.width / 2, y]
                    c.green > .8f && c.red < .2f && c.blue < .2f
                }
                assertTrue("the presented composer marker must be painted", rows.isNotEmpty())
                assertEquals("cached paint must follow the same IME position, not just layout", (pixels.height - inset).toFloat(), (rows.last() + 1).toFloat(), 1f)
                assertEquals(page, painted)
                val directory = java.io.File(activity.getExternalFilesDir(null), "visual-diagnostics/user-message-entrance").apply { mkdirs() }
                java.io.File(directory, "ime-handoff-$page-$inset.png").outputStream().use {
                    check(image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                }
            }
            assertPaintedBottom(200, "old")
            change { owner = "next" }
            for (inset in listOf(160, 100, 40, 0)) {
                change { ime.insets = WindowInsets(bottom = inset) }
                assertPaintedBottom(inset, "old")
            }
            change { allowNext = true }
            ui.mainClock.advanceTimeBy(64)
            ui.waitForIdle()
            assertPaintedBottom(0, "next")
        } finally {
            mainThread { ChatPageHostProbe.observer = null }
        }
    }

    fun testShortSendFirstPaintAndFastAcceptedReplyKeepTheFinalTailGap() {
        assertSendTailContinuity("首帧就是安全间距", acceptAfter = 80)
    }

    fun testMultilineSendKeepsTheTailGapWhenAcceptanceArrivesAfterTheEntrance() {
        assertSendTailContinuity("多行消息也应该从最终安全间距开始，不在回执到来后整块上跳。".repeat(3), acceptAfter = 360)
    }

    fun testZeroDurationSendFromEarlierHistoryIsPlacedBeforeItsFirstPaint() {
        scale.factor = 0f
        assertSendTailContinuity("零动画时长也不能先出现在输入框旁再上跳", acceptAfter = 32, fromEarlierHistory = true)
    }

    private fun sendPhysicalBounds(tag: String): androidx.compose.ui.geometry.Rect {
        val node = ui.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        val before = ui.mainClock.currentTime
        var bounds = androidx.compose.ui.geometry.Rect.Zero
        instrumentation.runOnMainSync {
            val coordinates = node.layoutInfo.coordinates
            check(coordinates.isAttached)
            bounds = androidx.compose.ui.geometry.Rect(coordinates.positionInRoot(),
                androidx.compose.ui.geometry.Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
        }
        assertEquals("reading physical geometry must not advance the animation clock", before, ui.mainClock.currentTime)
        return bounds
    }

    private fun assertSendTailContinuity(text: String, acceptAfter: Long, fromEarlierHistory: Boolean = false, orderedHistory: Boolean = false) {
        val history: List<ChatTurnUi> = List(24) { ChatTurnUi.User("history-$it", "更早的历史消息 $it") }
        var rows by mutableStateOf(history)
        var preview by mutableStateOf<ChatSubmissionPreview?>(null)
        var navigation by mutableStateOf(ChatScrollRequest(1, messageId = if (fromEarlierHistory) "history-12" else null,
            followBottom = !fromEarlierHistory))
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                background = conversationColors().surface.copy(alpha = 1f)
                density = LocalDensity.current.density
                val current = rememberUserMessageEntrance(request, active = true, awaitPlacement = true)
                SideEffect { motion = current }
                Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                    Box(Modifier.width(320.dp).height(420.dp).background(background).testTag("entrance-raster")) {
                        ChatMessageList(submissionTurns(rows, preview), ChatScreenActions(), Modifier.fillMaxSize(),
                            scrollRequest = navigation, composerBottomInset = 96.dp, userMessageEntrance = current,
                            hasTurnExtras = { false })
                        Box(Modifier.align(androidx.compose.ui.Alignment.BottomCenter).fillMaxWidth().height(96.dp)
                            .background(background).testTag("tail-composer"))
                    }
                }
            }
        } }
        ui.waitForIdle()
        ui.mainClock.autoAdvance = false
        val ticket = UserMessageEntrance("new-send")
        change {
            request = ticket
            preview = ChatSubmissionPreview(ticket.key, text)
            navigation = ChatScrollRequest(2)
        }
        var frames = 0
        while (progress() == 0f && frames++ < 60) {
            ui.mainClock.advanceTimeByFrame()
            ui.waitForIdle()
        }
        assertTrue("a safely placed send must become visible", progress() > 0f)
        val firstContrast = bubbleBackgroundContrast()
        assertTrue("the first visible frame must contain real bubble paint", firstContrast > .001f)
        val baseline = sendPhysicalBounds("chat-turn:new-send")
        val composerTop = sendPhysicalBounds("tail-composer").top
        val expectedGap = (8f + 26f) * density
        val diagnostic = StringBuilder()
        var previousProgress = 0f
        fun sample(key: String, stage: String) {
            val row = sendPhysicalBounds("chat-turn:$key")
            val actions = sendPhysicalBounds("message-user-actions:$key")
            val composer = sendPhysicalBounds("tail-composer")
            val gap = composer.top - actions.bottom
            assertEquals("composer must not be displaced by action opacity", composerTop, composer.top, 1f)
            assertEquals("$stage: the first painted send already owns the reply gap", expectedGap, gap, 1.5f)
            assertEquals("$stage: the whole user row cannot jump after first paint", baseline.top, row.top, 1.5f)
            assertEquals("$stage: action slot cannot jump after first paint", baseline.bottom, actions.bottom, 1.5f)
            if (orderedHistory) {
                assertEquals("$stage: reference row height must not change", baseline.height, row.height, 1.5f)
                assertTrue("$stage: reference entrance progress must not go backwards", progress() + .001f >= previousProgress)
                previousProgress = progress()
                ui.onNodeWithText("scroll-flicker（5）.log").assertIsDisplayed()
                ui.onNodeWithText("工作目录").assertIsDisplayed()
                ui.onNodeWithTag("message-reference-icon:0", useUnmergedTree = true).assertIsDisplayed()
                ui.onNodeWithText("content://", substring = true, useUnmergedTree = true).assertDoesNotExist()
                ui.onNodeWithText("218953", substring = true, useUnmergedTree = true).assertDoesNotExist()
            }
            diagnostic.append("{\"stage\":\"").append(stage).append("\",\"clock\":").append(ui.mainClock.currentTime)
                .append(",\"rowTop\":").append(row.top).append(",\"actionsBottom\":").append(actions.bottom)
                .append(",\"composerTop\":").append(composer.top).append(",\"gap\":").append(gap).append("}\n")
        }
        sample(ticket.key, "first-paint")
        var elapsed = 0L
        while (elapsed < acceptAfter) {
            ui.mainClock.advanceTimeByFrame()
            ui.waitForIdle()
            sample(ticket.key, "preparing")
            elapsed += 16
        }
        if (orderedHistory) assertTrue("reference bubble alpha must increase during entrance",
            bubbleBackgroundContrast() > firstContrast + .001f)
        val sameMotion = checkNotNull(motion)
        // A durable assistant can arrive before history publishes its user. Keep the real projection order.
        val assistant = ChatTurnUi.Assistant("accepted-assistant", status = app.daiyu.android.domain.RunStatus.RUNNING, isStreaming = true)
        change {
            ticket.messageId = "saved-user"
            preview = ChatSubmissionPreview(ticket.key, text, settledUserId = "saved-user", settledAssistantId = assistant.id)
            rows = history + assistant
        }
        sample(ticket.key, "accepted-preview")
        repeat(4) {
            ui.mainClock.advanceTimeByFrame(); ui.waitForIdle()
            sample(ticket.key, "accepted-preview")
        }
        change {
            rows = history + ChatTurnUi.User("saved-user", text,
                content = if (orderedHistory) listOf(MessageContentUi.Text("saved-reference-text", text)) else emptyList()) + assistant
            preview = null
            navigation = ChatScrollRequest(3)
        }
        repeat(24) {
            ui.mainClock.advanceTimeByFrame(); ui.waitForIdle()
            sample("saved-user", "durable-history")
            assertSame("history cannot replace the entrance clock", sameMotion, motion)
        }
        assertEquals(1f, progress(), .001f)
        val directory = java.io.File(activity.getExternalFilesDir(null), "visual-diagnostics/user-message-entrance").apply { mkdirs() }
        val referenceCase = if (orderedHistory) "-references-${if (text.startsWith("检查")) "body" else "only"}" else ""
        java.io.File(directory, "tail-$acceptAfter-${scale.factor}-$fromEarlierHistory$referenceCase.jsonl").writeText(diagnostic.toString())
    }


    fun testAnOldPageCannotRevokeTheNewPagesPlacement() {
        val entrance = UserMessageEntranceMotion(UserMessageEntrance("placement-owner"), androidx.compose.animation.core.Animatable(0f))
        val old = Any()
        val next = Any()
        entrance.updatePlacement(old, true)
        entrance.updatePlacement(next, false)
        assertTrue(entrance.positioned)
        entrance.updatePlacement(next, true)
        entrance.updatePlacement(old, false)
        assertTrue(entrance.positioned)
        entrance.updatePlacement(next, false)
        assertFalse(entrance.positioned)
    }


    private val entranceReferences = "文件引用（未读取）：content://reference-never-read.invalid/document/log\n" +
        "附件信息（按需读取）：scroll-flicker（5）.log（218953 字节）；用 read_text_file 按需读取\n" +
        "目录引用（未读取）：content://reference-never-read.invalid/tree/primary%3Afiles\n" +
        "附件信息（按需读取）：工作目录；用 list_files 按需列举"

    fun testTextAndReferencesKeepOneEntranceAcrossPreviewAndSavedOrderedText() {
        assertSendTailContinuity("检查这些附件\n$entranceReferences", acceptAfter = 80, orderedHistory = true)
    }

    fun testReferencesOnlyKeepOneEntranceAcrossPreviewAndSavedOrderedText() {
        assertSendTailContinuity(entranceReferences, acceptAfter = 80, orderedHistory = true)
    }

}
