package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
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
    private var background = Color.Unspecified
    private var density = 1f

    private fun show() {
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                background = conversationColors().surface.copy(alpha = 1f)
                density = LocalDensity.current.density
                Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                Box(Modifier.width(320.dp).height(180.dp).background(background).testTag("entrance-raster")) {
                    if (mounted) {
                        val current = rememberUserMessageEntrance(request, active)
                        SideEffect { motion = current }
                        Column(Modifier.testTag("entrance-slot")) {
                            key(turn.listKey) { UserTurn(turn, ChatScreenActions(), entrance = current) }
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
}
