package app.daiyu.android

import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.chat.conversation.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.daiyu.android.delegation.ReasoningEffort
import app.daiyu.android.model.GptSpeedMode

class ChatGptSpeedActivityTest : WiredActivityTestCase() {
    override fun createFixture() = super.createFixture().also {
        it.settings.save(it.settings.load().copy(model = "gpt-5"))
    }

    private fun awaitSpeed(mode: GptSpeedMode) {
        val label = when (mode) {
            GptSpeedMode.NORMAL -> "标准速度"
            GptSpeedMode.FAST -> "快速速度"
            GptSpeedMode.ULTRA_FAST -> "极速速度"
        }
        ui.waitUntil(5_000) {
            ui.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, label),
                useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(mode, fixture.settings.load().requestOptions.speedMode)
    }

    fun testLongPressAndPopupClickSharePersistedCycleWithoutChangingDepthOrDraft() {
        setDraft("speed keeps draft")
        fixture.controller.reasoningPreference.set(null, ReasoningEffort.HIGH)
        ui.waitUntil(5_000) {
            ui.onAllNodesWithTag("chat-reasoning").fetchSemanticsNodes().any {
                it.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnLongClick)
            }
        }
        node("chat-reasoning").performTouchInput { longClick() }
        awaitSpeed(GptSpeedMode.FAST)
        ui.onNodeWithText("调整思考深度").assertDoesNotExist()
        node("chat-reasoning").performClick()
        node("chat-thinking-speed").performClick()
        awaitSpeed(GptSpeedMode.ULTRA_FAST)
        node("chat-thinking-value").assertTextEquals("高")
        node("chat-thinking-speed").performClick()
        awaitSpeed(GptSpeedMode.NORMAL)
        assertEquals(ReasoningEffort.HIGH, fixture.controller.reasoningPreference.snapshot(null))
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        assertDraft("speed keeps draft")
        assertEquals(0, fixture.opens.get())
    }

    fun testPopupLargerIconAndValueRetainSliderAndReopeningDoesNotWriteSpeed() {
        node("chat-reasoning").performClick()
        node("chat-thinking-speed").assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        ui.onNodeWithTag("chat-thinking-speed-icon", useUnmergedTree = true)
            .assertWidthIsEqualTo(36.dp).assertHeightIsEqualTo(36.dp)
        node("thinking-depth-slider").assertHeightIsEqualTo(28.dp)
        awaitDialogEnter(node("chat-thinking-title"))
        val title = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node("chat-thinking-title").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) {
            assertTrue(it(title))
        }
        assertEquals(20f, title.single().layoutInput.style.fontSize.value, .001f)
        assertEquals(28f, title.single().layoutInput.style.lineHeight.value, .001f)
        val layout = title.single()
        assertFalse("title size=${layout.size} constraints=${layout.layoutInput.constraints} " +
            "lines=${layout.lineCount} widthOverflow=${layout.didOverflowWidth} heightOverflow=${layout.didOverflowHeight} " +
            "bounds=${node("chat-thinking-title").fetchSemanticsNode().boundsInRoot}", layout.hasVisualOverflow)
        val values = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node("chat-thinking-value").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) {
            assertTrue(it(values))
        }
        val value = values.single()
        assertEquals(22f, value.layoutInput.style.fontSize.value, .001f)
        assertEquals(30f, value.layoutInput.style.lineHeight.value, .001f)
        assertFalse(value.hasVisualOverflow)
        val iconBounds = ui.onNodeWithTag("chat-thinking-speed-icon", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val textSize = value.layoutInput.style.fontSize.value * value.layoutInput.density.fontScale * value.layoutInput.density.density
        assertTrue("Thinking icon must be larger than the selected value text", iconBounds.height > textSize)
        val sliderBounds = node("thinking-depth-slider").fetchSemanticsNode().boundsInRoot
        val titleBounds = node("chat-thinking-title").fetchSemanticsNode().boundsInRoot
        assertEquals("Slider keeps the existing content width", titleBounds.width, sliderBounds.width, 1f)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        node("chat-reasoning").performClick()
        assertNull(fixture.settings.load().requestOptions.speedMode)
        assertEquals(0, fixture.opens.get())
    }
    fun testPendingSpeedSaveKeepsChatViewportStableForEveryMode() {
        setDraft("speed keeps viewport")
        dismissIme()
        ui.waitUntil(5_000) {
            ui.onAllNodesWithTag("chat-reasoning").fetchSemanticsNodes().any {
                it.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnLongClick)
            }
        }
        for (mode in listOf(GptSpeedMode.FAST, GptSpeedMode.ULTRA_FAST, GptSpeedMode.NORMAL)) {
            val beforeMessages = node("chat-messages").fetchSemanticsNode().boundsInRoot
            val beforeComposer = node("chat-composer-surface").fetchSemanticsNode().boundsInRoot
            val stableTags = listOf("chat-model", "chat-context", "chat-send")
            val beforeControls = stableTags.associateWith { node(it).captureToImage() }
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
            val holder = Thread {
                try { fixture.settings.modelCatalog.configurationChange {
                    entered.countDown()
                    check(release.await(60, java.util.concurrent.TimeUnit.SECONDS))
                } } catch (problem: Throwable) { failure.set(problem) }
            }.apply { start() }
            try {
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                node("chat-reasoning").performTouchInput { longClick() }
                mainThread { assertTrue(fixture.controller.dataBusy) }
                node("chat-draft").assertIsNotEnabled()
                node("chat-model").assertIsNotEnabled()
                node("chat-send").assertIsNotEnabled()
                repeat(3) {
                    ui.mainClock.advanceTimeByFrame()
                    stableTags.forEach { tag -> assertSamePixels(tag, beforeControls.getValue(tag), node(tag).captureToImage()) }
                }
                node("chat-status").assertDoesNotExist()
                val pendingMessages = node("chat-messages").fetchSemanticsNode().boundsInRoot
                val pendingComposer = node("chat-composer-surface").fetchSemanticsNode().boundsInRoot
                assertEquals(beforeMessages.top, pendingMessages.top, 1f)
                assertEquals(beforeMessages.bottom, pendingMessages.bottom, 1f)
                assertEquals(beforeComposer.top, pendingComposer.top, 1f)
            } finally {
                release.countDown()
                holder.join(10_000)
            }
            assertFalse(holder.isAlive)
            failure.get()?.let { throw it }
            awaitSpeed(mode)
            node("chat-status").assertDoesNotExist()
            val afterMessages = node("chat-messages").fetchSemanticsNode().boundsInRoot
            assertEquals(beforeMessages.top, afterMessages.top, 1f)
            assertEquals(beforeMessages.bottom, afterMessages.bottom, 1f)
            assertDraft("speed keeps viewport")
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testSpeedSavingKeepsColorsButNotActionsInBothThemesAndEmptyDraft() {
        val reference = "文件引用（未读取）：content://documents/document/image%3A100"
        for (dark in listOf(false, true)) {
            val state = mutableStateOf(ComposerUi(modelLabel = "gpt-5", gptSpeedMode = GptSpeedMode.NORMAL,
                gptSpeedEnabled = true))
            var actions = 0
            mainThread { activity.setContent {
                AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                    applyInterfaceScale = false) {
                    ChatMenuLayer(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
                        ChatComposer(state.value, ChatScreenActions(onSend = { actions++ }, onAttach = { actions++ },
                            onModelClick = { actions++ }, onOpenReasoning = { actions++ }, onCycleGptSpeed = { actions++ },
                            onTextChange = { actions++ }, onProjectedDraftEdit = { _, _, _, _ -> actions++; true }),
                            Modifier.requiredWidth(320.dp))
                    }
                }
            } }
            for (raw in listOf("", "draft\n$reference")) {
                mainThread { state.value = state.value.copy(text = raw, enabled = true, gptSpeedSaving = false) }
                ui.waitForIdle()
                val tags = listOf("chat-model", "chat-context", "chat-send") +
                    if (raw.isEmpty()) emptyList() else listOf("chat-reference-remove:0")
                val before = tags.associateWith { node(it).captureToImage() }
                val attachment = ui.onNodeWithContentDescription("附件")
                val beforeAttachment = attachment.captureToImage()
                if (raw.isEmpty()) node("chat-send").assertIsNotEnabled() else node("chat-send").assertIsEnabled()
                mainThread { state.value = state.value.copy(enabled = false, gptSpeedSaving = true, gptSpeedEnabled = false) }
                ui.waitForIdle()
                node("chat-draft").assertIsNotEnabled()
                node("chat-model").assertIsNotEnabled()
                node("chat-reasoning").assertIsNotEnabled()
                node("chat-send").assertIsNotEnabled()
                attachment.assertIsNotEnabled()
                repeat(3) {
                    ui.mainClock.advanceTimeByFrame()
                    tags.forEach { tag -> assertSamePixels(tag, before.getValue(tag), node(tag).captureToImage()) }
                    assertSamePixels("attachment", beforeAttachment, attachment.captureToImage())
                }
                node("chat-send").performTouchInput { click() }
                node("chat-model").performTouchInput { click() }
                node("chat-reasoning").performTouchInput { longClick() }
                attachment.performTouchInput { click() }
                if (raw.isNotEmpty()) {
                    node("chat-reference-remove:0").assertIsNotEnabled().performTouchInput { click() }
                }
                assertEquals(0, actions)
                assertEquals(raw, state.value.text)
                mainThread { state.value = state.value.copy(gptSpeedSaving = false) }
                ui.waitForIdle()
                val ordinaryDisabled = node("chat-model").captureToImage()
                assertTrue("ordinary disabled model must still dim", pixelsDiffer(before.getValue("chat-model"), ordinaryDisabled))
                node("chat-draft").assertIsNotEnabled()
            }
        }
    }

    private fun pixelsDiffer(before: ImageBitmap, after: ImageBitmap): Boolean {
        assertEquals(before.width, after.width)
        assertEquals(before.height, after.height)
        val first = before.toPixelMap()
        val second = after.toPixelMap()
        for (y in 0 until before.height) for (x in 0 until before.width) {
            val a = first[x, y]
            val b = second[x, y]
            if (abs(a.red - b.red) > .02f || abs(a.green - b.green) > .02f ||
                abs(a.blue - b.blue) > .02f || abs(a.alpha - b.alpha) > .02f) return true
        }
        return false
    }

    private fun assertSamePixels(label: String, before: ImageBitmap, after: ImageBitmap) {
        assertFalse("$label must not dim during a speed save", pixelsDiffer(before, after))
    }

}
