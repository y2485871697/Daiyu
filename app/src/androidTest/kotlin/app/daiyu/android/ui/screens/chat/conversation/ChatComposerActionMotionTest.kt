package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.icons.ChatPreviewIcons
import kotlin.math.abs

private class ComposerActionTestScale : MotionDurationScale {
    var factor = 1f
    override val scaleFactor get() = factor
}

class ChatComposerActionMotionTest private constructor(private val scale: ComposerActionTestScale) : WiredActivityTestCase(scale) {
    override val requiresWindowDraw: Boolean = true
    constructor() : this(ComposerActionTestScale())

    private var icon by mutableStateOf(ChatPreviewIcons.Send)
    private var label by mutableStateOf("发送")
    private var clicks = 0

    override fun tearDown() {
        ui.mainClock.autoAdvance = true
        super.tearDown()
    }

    private fun showButton(animated: Boolean = true) {
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    Box(Modifier.size(80.dp).background(Color.Black), contentAlignment = Alignment.Center) {
                        ChatIconButton(icon, label, { clicks++ }, Modifier.size(32.dp).testTag("motion-button"),
                            containerColor = Color.Magenta, containerSize = 28.dp, iconSize = 16.dp,
                            tint = Color.White, indication = null, animateChanges = animated)
                    }
                }
            }
        }
        ui.waitForIdle()
        ui.mainClock.autoAdvance = false
    }

    private fun frame() = captureScreenNode(node("motion-button"))
    private fun frameWithoutClockAdvance(tag: String = "motion-button"): ImageBitmap {
        val before = ui.mainClock.currentTime
        val image = captureScreenNode(node(tag), advanceAnimationClock = false)
        assertEquals("capture must not advance the animation clock", before, ui.mainClock.currentTime)
        return image
    }
    private fun magentaPixels(image: ImageBitmap): Int {
        val pixels = image.toPixelMap()
        var count = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val color = pixels[x, y]
            if (color.red > .8f && color.blue > .8f && color.green < .2f) count++
        }
        return count
    }
    private fun differentPixels(a: ImageBitmap, b: ImageBitmap): Int {
        assertEquals(a.width, b.width)
        assertEquals(a.height, b.height)
        val first = a.toPixelMap(); val second = b.toPixelMap()
        var count = 0
        for (y in 0 until first.height) for (x in 0 until first.width) {
            val left = first[x, y]; val right = second[x, y]
            if (abs(left.red - right.red) + abs(left.green - right.green) + abs(left.blue - right.blue) > .15f) count++
        }
        return count
    }

    fun testPressScalesOnlyDrawingAndReleaseOrCancelRestoresIt() {
        showButton()
        val bounds = node("motion-button").fetchSemanticsNode().boundsInRoot
        val rest = frame()
        node("motion-button").performTouchInput { down(center) }
        ui.mainClock.advanceTimeBy(160)
        val pressed = frame()
        assertEquals(bounds, node("motion-button").fetchSemanticsNode().boundsInRoot)
        val ratio = magentaPixels(pressed).toFloat() / magentaPixels(rest)
        assertTrue("pressed area ratio=$ratio", ratio in .66f.. .80f)
        assertEquals(0, clicks)
        node("motion-button").performTouchInput { cancel() }
        ui.mainClock.advanceTimeBy(800)
        assertEquals(0, differentPixels(rest, frame()))
        assertEquals(0, clicks)
        node("motion-button").performTouchInput { down(center) }
        ui.mainClock.advanceTimeBy(160)
        node("motion-button").performTouchInput { up() }
        assertEquals(1, clicks)
        ui.mainClock.advanceTimeBy(800)
        assertEquals(0, differentPixels(rest, frame()))
        assertEquals(bounds, node("motion-button").fetchSemanticsNode().boundsInRoot)
    }

    fun testSameFrameTapStillHasVisibleFeedbackAfterRelease() {
        showButton()
        val bounds = node("motion-button").fetchSemanticsNode().boundsInRoot
        val rest = frameWithoutClockAdvance()
        val restArea = magentaPixels(rest)
        assertTrue(restArea > 0)
        val before = ui.mainClock.currentTime
        node("motion-button").performTouchInput { down(center); up() }
        assertEquals(1, clicks)
        assertEquals(before, ui.mainClock.currentTime)
        ui.mainClock.advanceTimeBy(60, ignoreFrameDuration = true)
        val ratio = magentaPixels(frameWithoutClockAdvance()).toFloat() / restArea
        assertEquals(before + 60L, ui.mainClock.currentTime)
        assertTrue("short-tap area ratio at release+60ms=$ratio", ratio in .66f.. .88f)
        assertEquals(bounds, node("motion-button").fetchSemanticsNode().boundsInRoot)
        ui.mainClock.advanceTimeBy(800)
        assertEquals(0, differentPixels(rest, frameWithoutClockAdvance()))
        assertEquals(1, clicks)
    }

    fun testComposerFeedbackSurvivesImmediateControlOwnerReplacement() {
        var state by mutableStateOf(ComposerUi(text = "", phase = ComposerPhase.RUNNING,
            controlOwnerKey = "first-owner"))
        val actions = ChatScreenActions(onPrimaryTap = {
            clicks++
            state = state.copy(controlOwnerKey = "next-owner")
        })
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    Box(Modifier.width(320.dp)) { ChatComposer(state, actions) }
                }
            }
        }
        ui.waitForIdle()
        ui.mainClock.autoAdvance = false
        val bounds = node("chat-control").fetchSemanticsNode().boundsInRoot
        val rest = frameWithoutClockAdvance("chat-control")
        val container = rest.toPixelMap()[rest.width / 2, rest.height / 6]
        fun containerPixels(image: ImageBitmap): Int {
            val pixels = image.toPixelMap()
            var count = 0
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                val color = pixels[x, y]
                if (abs(color.red - container.red) + abs(color.green - container.green) +
                    abs(color.blue - container.blue) < .05f) count++
            }
            return count
        }
        val restArea = containerPixels(rest)
        assertTrue(restArea > 0)
        node("chat-control").performTouchInput { down(center); up() }
        assertEquals(1, clicks)
        assertEquals("next-owner", state.controlOwnerKey)
        ui.mainClock.advanceTimeBy(60, ignoreFrameDuration = true)
        val ratio = containerPixels(frameWithoutClockAdvance("chat-control")).toFloat() / restArea
        assertTrue("owner-replaced feedback area ratio=$ratio", ratio in .66f.. .88f)
        assertEquals(bounds, node("chat-control").fetchSemanticsNode().boundsInRoot)
        node("chat-control").assertContentDescriptionEquals("暂停")
        ui.mainClock.advanceTimeBy(800)
        assertEquals(0, differentPixels(rest, frameWithoutClockAdvance("chat-control")))
        assertEquals(1, clicks)
    }

    fun testIconCrossfadeHasIntermediatePixelsButOnlyOneCurrentAction() {
        showButton()
        val send = frame()
        val bounds = node("motion-button").fetchSemanticsNode().boundsInRoot
        ui.runOnIdle { icon = ChatPreviewIcons.Pause; label = "暂停" }
        ui.mainClock.advanceTimeByFrame()
        ui.mainClock.advanceTimeBy(32)
        ui.onAllNodesWithContentDescription("发送", useUnmergedTree = true).assertCountEquals(0)
        ui.onAllNodesWithContentDescription("暂停", useUnmergedTree = true).assertCountEquals(1)
        node("motion-button").assertContentDescriptionEquals("暂停").performClick()
        assertEquals(1, clicks)
        val during = frame()
        ui.mainClock.advanceTimeBy(320)
        val pause = frame()
        assertTrue("transition differs from send", differentPixels(send, during) > 3)
        assertTrue("transition differs from pause", differentPixels(pause, during) > 3)
        assertTrue("icons differ", differentPixels(send, pause) > 3)
        assertEquals(bounds, node("motion-button").fetchSemanticsNode().boundsInRoot)
    }

    fun testRapidActionReversalsSettleWithoutStaleLabelsOrDuplicateCallbacks() {
        showButton()
        val send = frame()
        ui.runOnIdle { icon = ChatPreviewIcons.Pause; label = "暂停" }
        ui.mainClock.advanceTimeByFrame()
        ui.mainClock.advanceTimeBy(32)
        ui.runOnIdle { icon = ChatPreviewIcons.Append; label = "追加消息" }
        ui.mainClock.advanceTimeByFrame()
        ui.mainClock.advanceTimeBy(32)
        ui.runOnIdle { icon = ChatPreviewIcons.Send; label = "发送" }
        ui.mainClock.advanceTimeByFrame()
        node("motion-button").assertContentDescriptionEquals("发送").performClick()
        ui.onAllNodesWithContentDescription("暂停", useUnmergedTree = true).assertCountEquals(0)
        ui.onAllNodesWithContentDescription("追加消息", useUnmergedTree = true).assertCountEquals(0)
        ui.mainClock.advanceTimeBy(320)
        assertEquals(0, differentPixels(send, frame()))
        assertEquals(1, clicks)
    }

    fun testOtherSharedButtonsKeepTheirExistingStaticDrawing() {
        showButton(animated = false)
        val rest = frame()
        node("motion-button").performTouchInput { down(center) }
        ui.mainClock.advanceTimeBy(160)
        assertEquals(0, differentPixels(rest, frame()))
        node("motion-button").performTouchInput { cancel() }
        assertEquals(0, clicks)
    }

    fun testZeroDurationScaleSettlesIconAndPressWithoutAnimationDelay() {
        scale.factor = 0f
        showButton()
        ui.runOnIdle { icon = ChatPreviewIcons.Pause; label = "暂停" }
        repeat(2) { ui.mainClock.advanceTimeByFrame() }
        val pause = frame()
        ui.mainClock.advanceTimeBy(320)
        assertEquals(0, differentPixels(pause, frame()))
        node("motion-button").assertContentDescriptionEquals("暂停")
        node("motion-button").performTouchInput { down(center) }
        repeat(2) { ui.mainClock.advanceTimeByFrame() }
        assertTrue(magentaPixels(frame()) < magentaPixels(pause))
        node("motion-button").performTouchInput { up() }
        repeat(2) { ui.mainClock.advanceTimeByFrame() }
        assertEquals(1, clicks)
        assertEquals(0, differentPixels(pause, frame()))
    }

    fun testComposerKeepsSendAppendPauseResumeAndLongStopCallbacks() {
        val events = mutableListOf<String>()
        var presses = 0; var releases = 0
        var state by mutableStateOf(ComposerUi(text = "消息", phase = ComposerPhase.IDLE,
            controlOwnerKey = "motion-owner", supplementAvailable = true))
        val actions = ChatScreenActions(onSend = { events.add("send:$it") },
            onSupplement = { events.add("append:$it") }, onPrimaryTap = { events.add("primary") },
            onPrimaryLongPress = { events.add("stop") }, onCancelSend = { events.add("cancel") },
            onControlPress = { presses++ }, onControlRelease = { releases++ })
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    Box(Modifier.width(320.dp)) { ChatComposer(state, actions) }
                }
            }
        }
        node("chat-send").assertContentDescriptionEquals("发送").performClick()
        ui.runOnIdle { state = state.copy(text = "", phase = ComposerPhase.RUNNING) }
        node("chat-control").assertContentDescriptionEquals("暂停").performTouchInput { click() }
        ui.runOnIdle { state = state.copy(text = "补充") }
        node("chat-send").assertContentDescriptionEquals("追加消息").performClick()
        ui.runOnIdle { state = state.copy(supplementPending = true) }
        node("chat-send").assertContentDescriptionEquals("正在追加").assertIsNotEnabled()
        ui.runOnIdle { state = state.copy(text = "", phase = ComposerPhase.PAUSED, supplementPending = false) }
        node("chat-control").assertContentDescriptionEquals("继续").performTouchInput { click() }
        node("chat-control").performTouchInput { longClick() }
        ui.runOnIdle { state = state.copy(preparingSendKey = "preparing") }
        node("text-send-cancel").assertContentDescriptionEquals("取消发送").performClick()
        ui.runOnIdle {
            assertEquals(listOf("send:消息", "primary", "append:补充", "primary", "stop", "cancel"), events)
            assertEquals(5, presses)
            assertEquals(5, releases)
        }
    }
    fun testMediaSubmissionStaysVisuallyActiveWithoutCancelOrRunAuthority() {
        var state by mutableStateOf(ComposerUi(text = "图片消息", enabled = false,
            phase = ComposerPhase.IDLE, mediaSubmissionKey = "exact-image-attempt"))
        var sends = 0; var cancellations = 0; var controls = 0
        val actions = ChatScreenActions(onSend = { sends++ }, onCancelSend = { cancellations++ },
            onPrimaryTap = { controls++ })
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                Box(Modifier.width(320.dp)) { ChatComposer(state, actions) }
            }
        } }
        node("media-send-pending").assertContentDescriptionEquals("正在发送").assertIsNotEnabled()
            .performTouchInput { click() }
        ui.runOnIdle { state = state.copy(text = "", editorGeneration = 1) }
        node("media-send-pending").assertIsNotEnabled()
        ui.runOnIdle { state = state.copy(mediaSubmissionKey = null, enabled = true,
            phase = ComposerPhase.RUNNING, controlOwnerKey = "accepted-owner") }
        node("chat-control").assertContentDescriptionEquals("暂停").assertIsEnabled().performClick()
        assertEquals(0, sends)
        assertEquals(0, cancellations)
        assertEquals(1, controls)
    }

    private fun showAttachmentExit(content: @androidx.compose.runtime.Composable () -> Unit) {
        mainThread {
            activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    Box(Modifier.size(240.dp, 180.dp).background(Color.Black)
                        .testTag("attachment-motion-scene"), contentAlignment = Alignment.BottomCenter) {
                        Column(Modifier.fillMaxWidth()) {
                            ComposerAttachmentExit(content)
                            Box(Modifier.fillMaxWidth().height(40.dp).background(Color.Cyan))
                        }
                    }
                }
            }
        }
        awaitVisualIdle()
        ui.mainClock.autoAdvance = false
    }

    private fun retainedAttachmentPixels(): Int {
        val pixels = frameWithoutClockAdvance("attachment-motion-scene").toPixelMap()
        var count = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val c = pixels[x, y]
            if (c.red > .005f && c.blue > .005f && c.green < .005f) count++
        }
        return count
    }

    private fun retainedAttachmentStrength(): Float {
        val pixels = frameWithoutClockAdvance("attachment-motion-scene").toPixelMap()
        var maximum = 0f
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val c = pixels[x, y]
            if (c.red > c.green + .005f && c.blue > c.green + .005f) maximum = maxOf(maximum, c.red)
        }
        return maximum
    }

    fun testAttachmentPixelsRetireWithTheMeasuredHeightAcrossEditorReplacement() {
        var visible by mutableStateOf(true)
        var editor by mutableStateOf(0)
        showAttachmentExit {
            key(editor) {
                if (visible) Column(Modifier.fillMaxWidth().height(56.dp).testTag("live-attachment")) {
                    Box(Modifier.fillMaxWidth().weight(1f).background(Color.Yellow))
                    Box(Modifier.fillMaxWidth().weight(1f).background(Color.Magenta))
                }
            }
        }
        val before = node("composer-attachment-exit").fetchSemanticsNode().boundsInRoot
        assertTrue(retainedAttachmentPixels() > 0)
        mainThread { visible = false; editor++ }
        ui.mainClock.advanceTimeByFrame()
        node("live-attachment").assertDoesNotExist()
        var previousHeight = before.height
        repeat(3) {
            ui.mainClock.advanceTimeBy(32)
            val now = node("composer-attachment-exit").fetchSemanticsNode().boundsInRoot
            assertTrue("attachment height must remain positive during pixel sampling: $now", now.height > 0f)
            assertTrue("attachment height must shrink", now.height < previousHeight)
            val strength = retainedAttachmentStrength()
            assertTrue("old pixels must survive: height=${now.height}/${before.height}, strength=$strength, time=${ui.mainClock.currentTime}", strength > 0f)
            assertEquals("opacity must use the same progress as height", now.height / before.height, strength, .12f)
            assertTrue(abs(now.bottom - before.bottom) <= 1f)
            previousHeight = now.height
        }
        ui.mainClock.advanceTimeBy(240)
        assertEquals(0f, node("composer-attachment-exit").fetchSemanticsNode().boundsInRoot.height, 1f)
        assertEquals(0, retainedAttachmentPixels())
    }

    fun testReplacingAnExitingAttachmentCannotRemoveTheNewAttachment() {
        var visible by mutableStateOf(true)
        var replacement by mutableStateOf(false)
        showAttachmentExit {
            if (visible) Box(Modifier.fillMaxWidth().height(56.dp)
                .background(if (replacement) Color.Green else Color.Magenta).testTag("live-attachment"))
        }
        assertTrue("the outgoing attachment must first be painted", retainedAttachmentPixels() > 0)
        mainThread { visible = false }
        ui.mainClock.advanceTimeByFrame()
        ui.mainClock.advanceTimeBy(48)
        val remaining = node("composer-attachment-exit").fetchSemanticsNode().boundsInRoot.height
        assertTrue("replacement must interrupt a still-positive exit height: $remaining", remaining > 0f)
        assertTrue("the outgoing pixels must survive before replacement", retainedAttachmentPixels() > 0)
        mainThread { replacement = true; visible = true }
        ui.mainClock.advanceTimeByFrame()
        node("live-attachment").assertExists()
        assertEquals("replacement must not replay the old pixels", 0, retainedAttachmentPixels())
        ui.mainClock.advanceTimeBy(300)
        node("live-attachment").assertExists()
        assertEquals(0, retainedAttachmentPixels())
        val pixels = frameWithoutClockAdvance("attachment-motion-scene").toPixelMap()
        var green = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val c = pixels[x, y]
            if (c.green > .8f && c.red < .1f && c.blue < .1f) green++
        }
        assertTrue("the new attachment must remain painted", green > 0)
    }

    fun testZeroDurationAttachmentExitLeavesNoEmptyHeightOrRetainedPixels() {
        scale.factor = 0f
        var visible by mutableStateOf(true)
        showAttachmentExit {
            if (visible) Box(Modifier.fillMaxWidth().height(56.dp).background(Color.Magenta))
        }
        mainThread { visible = false }
        ui.mainClock.advanceTimeByFrame()
        ui.mainClock.advanceTimeByFrame()
        assertEquals(0f, node("composer-attachment-exit").fetchSemanticsNode().boundsInRoot.height, 1f)
        assertEquals(0, retainedAttachmentPixels())
    }

    fun testInterruptedAttachmentEntranceBeginsItsExitAtTheCurrentOpacity() {
        var visible by mutableStateOf(false)
        showAttachmentExit {
            if (visible) Box(Modifier.fillMaxWidth().height(56.dp).background(Color.Magenta))
        }
        mainThread { visible = true }
        ui.mainClock.advanceTimeByFrame()
        frameWithoutClockAdvance("attachment-motion-scene")
        ui.mainClock.advanceTimeBy(48)
        val entering = node("composer-attachment-exit").fetchSemanticsNode().boundsInRoot
        assertTrue(entering.height > 0f)
        assertEquals(1f, retainedAttachmentStrength(), .02f)
        mainThread { visible = false }
        ui.mainClock.advanceTimeByFrame()
        assertEquals("interruption must not jump opacity to current/target height", 1f,
            retainedAttachmentStrength(), .12f)
        ui.mainClock.advanceTimeBy(240)
        assertEquals(0, retainedAttachmentPixels())
    }

    fun testAsynchronousChildPixelsRefreshTheRasterWithoutChangingItsHeight() {
        var visible by mutableStateOf(true)
        var loaded by mutableStateOf(false)
        showAttachmentExit {
            if (visible) Box(Modifier.fillMaxWidth().height(56.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))) {
                ObserveComposerAttachmentVisual(loaded)
                Box(Modifier.fillMaxSize().background(if (loaded) Color.Magenta else Color.Yellow))
            }
        }
        mainThread { loaded = true }
        ui.mainClock.advanceTimeByFrame()
        assertTrue("the asynchronous child must first be visible", retainedAttachmentPixels() > 0)
        mainThread { visible = false }
        repeat(10) {
            ui.mainClock.advanceTimeByFrame()
            val height = node("composer-attachment-exit").fetchSemanticsNode().boundsInRoot.height
            if (height > 12f) assertTrue("no blank exit frame at $height", retainedAttachmentPixels() > 0)
        }
        ui.mainClock.advanceTimeBy(240)
        assertEquals(0, retainedAttachmentPixels())
    }

}
