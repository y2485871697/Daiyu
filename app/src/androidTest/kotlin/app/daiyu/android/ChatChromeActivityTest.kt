package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daiyu.android.balance.BalanceMoney
import app.daiyu.android.balance.BalanceResult
import app.daiyu.android.balance.BalanceState
import app.daiyu.android.balance.formatBalanceDisplay
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.chat.conversation.ChatMenuLayer
import app.daiyu.android.ui.screens.chat.conversation.ChatComposer
import app.daiyu.android.ui.screens.chat.conversation.ChatActionColors
import app.daiyu.android.ui.screens.chat.conversation.chatActionColors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import app.daiyu.android.ui.screens.chat.conversation.ChatScreenActions
import app.daiyu.android.ui.screens.chat.conversation.ChatTopBar
import app.daiyu.android.ui.screens.chat.conversation.ComposerPhase
import app.daiyu.android.ui.screens.chat.conversation.ComposerUi
import kotlin.math.abs
import kotlin.math.roundToInt

/** UI geometry and action dispatch; real run ownership stays covered by RunControlActivityTest. */
class ChatChromeActivityTest : WiredActivityTestCase() {
    private var actionColors: ChatActionColors? = null
    private var disabledContextColor: Color = Color.Unspecified
    private var layoutScenario = ""

    private fun composer() = ComposerUi(
        text = "  下一条消息  ", phase = ComposerPhase.RUNNING,
        modelLabel = "超长模型名称-provider/model-".repeat(20),
        reasoningLabel = "高", assistantLabel = "代码助手",
        contextFraction = 0.37f, controlOwnerKey = "chrome-owner",
    )

    private fun show(composer: ComposerUi, actions: ChatScreenActions = ChatScreenActions(),
        fontScale: Float = 1f, interfaceScale: Float = 1f, dark: Boolean = false, balance: BalanceState? = null,
        opaqueBackground: Boolean = false) {
        layoutScenario = "dark=$dark scale=$interfaceScale font=$fontScale enabled=${composer.enabled} fraction=${composer.contextFraction}"
        mainThread {
            activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    AgentAppTheme(AppearanceSettings(interfaceScale = interfaceScale,
                        themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT), applyInterfaceScale = true) {
                        val colors = chatActionColors()
                        val disabledColor = MiuixTheme.colorScheme.disabledOnSurface
                        SideEffect { actionColors = colors; disabledContextColor = disabledColor }
                        ChatMenuLayer(Modifier.fillMaxSize().then(
                            if (opaqueBackground) Modifier.background(MiuixTheme.colorScheme.background) else Modifier,
                        )) {
                            Column(Modifier.requiredWidth(320.dp).testTag("chrome-host")) {
                                ChatTopBar("测试会话", actions, assistantLabel = composer.assistantLabel, modelLabel = composer.modelLabel)
                                ChatComposer(composer, actions, balanceState = balance)
                            }
                        }
                    }
                }
            }
        }
        ui.waitForIdle()
    }

    private fun button(label: String) = ui.onNode(hasContentDescription(label) and hasClickAction())
    private fun touch(target: SemanticsNodeInteraction) {
        target.assertIsDisplayed().performTouchInput { click(center) }
    }
    private fun layout(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        ui.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
        assertEquals(1, results.size)
        return results.single()
    }

    private fun assertNoTextOverflow(text: TextLayoutResult) {
        assertFalse("$layoutScenario text=${text.layoutInput.text} " +
            "width=${text.didOverflowWidth} height=${text.didOverflowHeight} " +
            "size=${text.size} constraints=${text.layoutInput.constraints} " +
            "paragraph=${text.multiParagraph.width}x${text.multiParagraph.height} " +
            "intrinsic=${text.multiParagraph.intrinsics.maxIntrinsicWidth} " +
            "line=${text.getLineLeft(0)},${text.getLineTop(0)},${text.getLineRight(0)},${text.getLineBottom(0)} " +
            "ellipsized=${text.isLineEllipsized(0)}",
            text.didOverflowHeight || (0 until text.lineCount).any { line ->
                text.isLineEllipsized(line) ||
                    text.getLineLeft(line) < -1f ||
                    text.getLineRight(line) > text.size.width + 1f ||
                    text.getLineTop(line) < -1f ||
                    text.getLineBottom(line) > text.size.height + 1f
            })
        assertTrue("complete text has a measured line", text.lineCount > 0)
        assertEquals(0, text.getLineStart(0))
        assertEquals(text.layoutInput.text.length, text.getLineEnd(text.lineCount - 1, visibleEnd = true))
    }

    private fun assertTextOverflowRejected(text: TextLayoutResult) {
        val rejected = try { assertNoTextOverflow(text); false }
            catch (_: junit.framework.AssertionFailedError) { true }
        assertTrue("overflow oracle must reject clipped or ellipsized text", rejected)
    }

    private fun contextHeightPx(text: TextLayoutResult): Float {
        val density = text.layoutInput.density.density
        return maxOf((22f * density).roundToInt(),
            maxOf((15f * density).roundToInt(), text.size.height) +
                2 * (1f * density).roundToInt() + 2 * (2f * density).roundToInt()).toFloat()
    }

    private fun contextBottomGapPx(density: Float, contextHeight: Int): Float {
        val modelHeight = (26f * density).roundToInt()
        val spare = maxOf(modelHeight, contextHeight) - contextHeight
        return ((4f * density).roundToInt() + spare - (spare / 2f).roundToInt()).toFloat()
    }

    fun testGptLongPressIsSeparateFromTapAndUnavailableModesHaveNoSpeedAction() {
        var clicks = 0
        var cycles = 0
        val actions = ChatScreenActions(onOpenReasoning = { clicks++ }, onCycleGptSpeed = { cycles++ })
        for (dark in listOf(false, true)) {
            show(composer().copy(gptSpeedMode = app.daiyu.android.model.GptSpeedMode.NORMAL, gptSpeedEnabled = true), actions, dark = dark)
            node("chat-reasoning").performTouchInput { longClick() }
            assertEquals(0, clicks)
            node("chat-reasoning").performTouchInput { click() }
            assertEquals(1, clicks)
            assertEquals(1, cycles)
            for (state in listOf(composer(), composer().copy(gptSpeedMode = app.daiyu.android.model.GptSpeedMode.FAST))) {
                show(state, actions, dark = dark)
                node("chat-reasoning").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
            }
            clicks = 0; cycles = 0
        }
    }

    fun testRunningComposerAt320DpKeepsActionsSeparateAndContextReadable() {
        val state = composer().copy(text = "")
        show(state)
        val host = node("chrome-host").assertWidthIsEqualTo(320.dp).fetchSemanticsNode().boundsInRoot
        val controls = listOf(button("附件"), button("推理强度，当前为 高"),
            button("选择助手，当前为 代码助手"), button("语音输入"), node("chat-control"))
        node("chat-send").assertDoesNotExist()
        node("chat-control").assertIsEnabled().assertContentDescriptionEquals("暂停")
        val bounds = controls.map { target ->
            target.assertIsDisplayed().assertHasClickAction()
                .assertWidthIsEqualTo(32.dp).assertHeightIsEqualTo(32.dp)
            target.fetchSemanticsNode().boundsInRoot.also {
                assertTrue(it.width > 0 && it.height > 0)
                assertTrue(it.left >= host.left - 1 && it.right <= host.right + 1)
            }
        }
        for (i in bounds.indices) for (j in i + 1 until bounds.size) {
            val a = bounds[i]; val b = bounds[j]
            assertFalse("Controls $i/$j overlap: $a / $b",
                a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom)
        }
        assertTrue(abs(bounds[3].center.x - host.center.x) <= 1f)
        val model = node("chat-model").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val context = node("chat-context").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(model.width > 0 && model.right <= context.left + 1)
        assertTrue(context.left >= host.left && context.right <= host.right)
        assertTrue(layout(state.modelLabel).isLineEllipsized(0))
        val contextLayout = layout("37% 上下文")
        assertEquals(1, contextLayout.lineCount)
        assertFalse(contextLayout.isLineEllipsized(0))
        val lineLeft = contextLayout.getLineLeft(0)
        val lineRight = contextLayout.getLineRight(0)
        assertTrue("Context text must fit: line=[$lineLeft, $lineRight], constraints=${contextLayout.layoutInput.constraints}, " +
            "size=${contextLayout.size}, paragraphWidth=${contextLayout.multiParagraph.width}, " +
            "intrinsicWidth=${contextLayout.multiParagraph.intrinsics.maxIntrinsicWidth}, bounds=$context, host=$host",
            lineLeft >= -1f && lineRight <= contextLayout.size.width.toFloat() + 1f)
    }

    fun testTouchDispatchesPickersMenusSupplementContinueAndLongStop() {
        val events = mutableListOf<String>()
        var presses = 0; var releases = 0
        val state = composer().copy(phase = ComposerPhase.PAUSED, supplementAvailable = true)
        val actions = ChatScreenActions(
            onOpenReasoning = { events.add("reasoning") }, onOpenAssistant = { events.add("assistant") },
            onMore = { events.add("more") }, onMenuAction = { events.add("menu:${it.name}") },
            onModelClick = { events.add("model") },
            onVoice = { events.add("voice") }, onSupplement = { events.add("supplement<$it>") },
            onSend = { error("paused text must not start a new send") },
            onPrimaryTap = { events.add("continue") }, onPrimaryLongPress = { events.add("stop") },
            onControlPress = { presses++ }, onControlRelease = { releases++ },
        )
        show(state, actions)
        touch(node("chat-reasoning")); touch(node("chat-assistant"))
        touch(button("更多"))
        node("chat-menu:NEW_CHAT").assertIsDisplayed().assertTextContains("新建会话")
        node("chat-menu:SEARCH").assertIsDisplayed().assertTextContains("搜索记录")
        touch(node("chat-menu:OPEN_TERMINAL").scrollToVisible())
        ui.mainClock.advanceTimeBy(176)
        node("chat-overflow-menu").assertDoesNotExist()
        touch(button("更多"))
        val menu = node("chat-overflow-menu").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(menu.height > 336 * activity.resources.displayMetrics.density)
        node("chat-menu:AUTO_COMPRESS").scrollToVisible().assertIsDisplayed()
        touch(ui.onNodeWithText("Token 用量").scrollToVisible())
        ui.onNodeWithText("Token 用量").assertDoesNotExist()
        node("chat-context").assertHasClickAction()
        touch(node("chat-model")); touch(node("chat-context"))
        node("chat-context").performTouchInput { longClick() }
        touch(button("语音输入"))
        node("chat-send").assertContentDescriptionEquals("追加消息")
        node("chat-control").assertDoesNotExist()
        touch(node("chat-send"))
        show(state.copy(text = ""), actions)
        node("chat-send").assertDoesNotExist()
        node("chat-control").assertIsEnabled().assertContentDescriptionEquals("继续")
        touch(node("chat-control"))
        node("chat-control").performTouchInput { longClick() }
        ui.runOnIdle {
            assertEquals(listOf("reasoning", "assistant", "more", "menu:OPEN_TERMINAL", "more", "menu:CONTEXT_STATS",
                "model", "voice", "supplement<${state.text}>", "continue", "stop"), events.toList())
            assertEquals(3, presses); assertEquals(3, releases)
        }
    }

    fun testHeaderKeepsPreviewGeometryAndSubtitleAtDeviceFontScale() {
        val state = composer().copy(modelLabel = "gpt-6.1-sol")
        show(state, fontScale = .9f, interfaceScale = .8f)
        val bar = node("chat-top-bar").fetchSemanticsNode().boundsInRoot
        val density = bar.width / 320f
        val title = layout("测试会话")
        assertEquals(18.sp, title.layoutInput.style.fontSize)
        assertEquals(FontWeight(650), title.layoutInput.style.fontWeight)
        assertEquals(.9f, title.layoutInput.density.fontScale, .001f)
        assertEquals(13.sp, layout("代码助手 · gpt-6.1-sol").layoutInput.style.fontSize)
        val drawer = button("打开侧边栏").fetchSemanticsNode().boundsInRoot
        val more = button("更多").fetchSemanticsNode().boundsInRoot
        val agents = button("本会话协作").fetchSemanticsNode().boundsInRoot
        val titleBounds = node("chat-title").fetchSemanticsNode().boundsInRoot
        assertEquals(12f, (drawer.left - bar.left) / density, 1f)
        assertEquals(60f, (titleBounds.left - bar.left) / density, 1f)
        assertEquals(12f, (bar.right - more.right) / density, 1f)
        assertEquals(40f, more.width / density, .5f)
        assertEquals(8f, (more.left - agents.right) / density, .5f)
        assertEquals(64f, bar.height / density, 1.5f)
    }

    fun testEnlargedComposerFitsControlsWithoutDoubleScalingText() {
        for ((density, expected) in listOf(1f to 6f, .8f to 4f, 1.25f to 7f, 1.5f to 9f)) {
            val context = maxOf((22f * density).roundToInt(), (15f * density).roundToInt() +
                2 * density.roundToInt() + 2 * (2f * density).roundToInt())
            assertEquals(expected, contextBottomGapPx(density, context), 0f)
        }
        val state = composer().copy(text = "", modelLabel = "gpt-6.1-sol", phase = ComposerPhase.IDLE)
        for (dark in listOf(false, true)) for (scale in listOf(1f, .8f)) {
            show(state, fontScale = .9f, interfaceScale = scale, dark = dark)
            val host = node("chrome-host").fetchSemanticsNode().boundsInRoot
            val density = host.width / 320f
            val surface = node("chat-composer-surface").fetchSemanticsNode().boundsInRoot
            val context = node("chat-context").fetchSemanticsNode().boundsInRoot
            val divider = node("chat-composer-divider").fetchSemanticsNode().boundsInRoot
            val bottom = node("chat-composer-bottom").fetchSemanticsNode().boundsInRoot
            val send = node("chat-send").fetchSemanticsNode().boundsInRoot
            assertEquals(9f, (surface.left - host.left) / density, .5f)
            assertEquals(16f, (host.bottom - surface.bottom) / density, .75f)
            assertEquals(144f, surface.height / density, 1.5f)
            val contextText = layout("37% 上下文")
            val actualDensity = contextText.layoutInput.density.density
            val contextHeight = contextHeightPx(contextText).toInt()
            assertEquals(contextHeight.toFloat(), context.height, 0f)
            val modelHeight = (26f * actualDensity).roundToInt()
            val expectedTopPx = (12f * actualDensity).roundToInt() +
                ((maxOf(modelHeight, contextHeight) - contextHeight) / 2f).roundToInt()
            assertEquals(expectedTopPx.toFloat(), context.top - surface.top, 0f)
            assertEquals(14f, (surface.right - context.right) / density, .75f)
            assertEquals(13f, (divider.left - surface.left) / density, .75f)
            val expectedGap = contextBottomGapPx(actualDensity, contextHeight)
            assertEquals("Context gap density=$actualDensity height=$contextHeight expected=$expectedGap",
                expectedGap, divider.top - context.bottom, 0f)
            assertEquals((26f * actualDensity).roundToInt().toFloat(),
                node("chat-model").fetchSemanticsNode().boundsInRoot.height, 0f)
            assertEquals(36f, bottom.height / density, .75f)
            assertEquals(12f, (surface.bottom - bottom.bottom) / density, .75f)
            assertEquals(32f, send.width / density, .75f)
            assertEquals(13f, (surface.right - send.right) / density, .75f)
            assertEquals(33f, node("chat-draft").fetchSemanticsNode().boundsInRoot.height / density, .75f)
            node("chat-send").assertIsNotEnabled()
            val modelText = layout(state.modelLabel)
            assertEquals(13.sp, modelText.layoutInput.style.fontSize)
            assertEquals(18.sp, modelText.layoutInput.style.lineHeight)
            assertEquals(androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                modelText.layoutInput.style.platformStyle)
            assertEquals(androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                modelText.layoutInput.style.lineHeightStyle?.alignment)
            val chip = node("chat-model").fetchSemanticsNode().boundsInRoot
            val label = ui.onNodeWithTag("composer-model-label", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals(chip.center.y - density.roundToInt(), label.center.y, 1f)
            assertEquals(10.sp, layout("37% 上下文").layoutInput.style.fontSize)
            assertEquals(14.sp, layout("37% 上下文").layoutInput.style.lineHeight)
            val placeholder = layout("给代鱼发消息，或使用 @ 和 / 引用")
            assertEquals(16.sp, placeholder.layoutInput.style.fontSize)
            assertEquals(24.sp, placeholder.layoutInput.style.lineHeight)
            assertEquals(.9f, placeholder.layoutInput.density.fontScale, .001f)
        }
    }

    fun testCompactContextKeepsReferenceColorAndIconTextProportions() {
        for (dark in listOf(false, true)) for (scale in listOf(1f, .8f)) {
            for (font in listOf(.9f, 1.3f)) for (enabled in listOf(true, false)) {
                val state = composer().copy(contextFraction = 1f, enabled = enabled)
                show(state, dark = dark, interfaceScale = scale, fontScale = font)
                val density = node("chrome-host").fetchSemanticsNode().boundsInRoot.width / 320f
                val badge = node("chat-context").assertIsDisplayed().assertHasClickAction().fetchSemanticsNode().boundsInRoot
                val iconNode = ui.onNodeWithTag("chat-context-icon", useUnmergedTree = true).assertIsDisplayed()
                val icon = iconNode.fetchSemanticsNode().boundsInRoot
                val label = ui.onNodeWithTag("chat-context-label", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val model = node("chat-model").fetchSemanticsNode().boundsInRoot
                val text = layout("100% 上下文")
                val expected = if (!enabled) disabledContextColor else Color(if (dark) 0xFFB4A3F7 else 0xFF7663BD)
                assertEquals(expected, text.layoutInput.style.color)
                assertEquals(10.sp, text.layoutInput.style.fontSize)
                assertEquals(14.sp, text.layoutInput.style.lineHeight)
                assertEquals(FontWeight.Normal, text.layoutInput.style.fontWeight)
                assertEquals(androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false), text.layoutInput.style.platformStyle)
                assertEquals(font, text.layoutInput.density.fontScale, .001f)
                assertEquals(1, text.lineCount)
                assertNoTextOverflow(text)
                assertEquals(15f, icon.width / density, .75f)
                assertEquals(15f, icon.height / density, .75f)
                assertEquals(contextHeightPx(text), badge.height, 0f)
                assertTrue("compact context max width", badge.width / density <= 120f + 1f)
                if (font <= 1f) assertTrue("default-font badge stays compact", badge.width / density < 92f)
                assertEquals(4f, (icon.left - badge.left) / density, .75f)
                assertEquals(4f, (label.left - icon.right) / density, .75f)
                assertEquals(4f, (badge.right - label.right) / density, .75f)
                assertEquals(badge.center.y, icon.center.y, 1f)
                assertEquals(icon.center.y, label.center.y, 1f)
                assertEquals(model.center.y, badge.center.y, 1f)
                assertTrue("model remains before context", model.right <= badge.left + 1f)
                if (enabled && scale == 1f && font == .9f) {
                    val pixels = iconNode.captureToImage().toPixelMap()
                    val matches = (0 until pixels.height).any { y -> (0 until pixels.width).any { x ->
                        val color = pixels[x, y]
                        abs(color.red - expected.red) < .04f && abs(color.green - expected.green) < .04f && abs(color.blue - expected.blue) < .04f
                    } }
                    assertTrue("context stroke uses the text accent, dark=$dark", matches)
                }
            }
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testCompactContextKeepsClampingFallbackAndLongLabelInsideItsBadge() {
        for (fraction in listOf(null, Float.NaN, Float.POSITIVE_INFINITY, -.2f, 0f, .37f, 1f, 1.2f)) {
            val state = composer().copy(contextFraction = fraction)
            show(state)
            val safe = fraction?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
            val expected = if (safe == null) "上下文" else "${(safe * 100).toInt()}% 上下文"
            val text = layout(expected)
            assertEquals(1, text.lineCount)
            assertNoTextOverflow(text)
            if (fraction == null) {
                assertTextOverflowRejected(text.copy(size = androidx.compose.ui.unit.IntSize(text.size.width - 2, text.size.height)))
                assertTextOverflowRejected(text.copy(size = androidx.compose.ui.unit.IntSize(text.size.width, text.size.height - 1)))
            }
            node("chat-context").assertHasClickAction()
        }
        show(composer().copy(contextFraction = 1f, contextLabel = "上下文使用情况".repeat(20)), fontScale = 1.3f)
        val density = node("chrome-host").fetchSemanticsNode().boundsInRoot.width / 320f
        val badge = node("chat-context").fetchSemanticsNode().boundsInRoot
        val icon = ui.onNodeWithTag("chat-context-icon", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val label = ui.onNodeWithTag("chat-context-label", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(120f, badge.width / density, 1f)
        assertEquals(15f, icon.width / density, .75f)
        assertTrue(label.right <= badge.right + 1f)
        val truncated = layout("100% " + "上下文使用情况".repeat(20))
        assertTrue(truncated.isLineEllipsized(0))
        assertTextOverflowRejected(truncated)
        assertTrue(node("chat-model").fetchSemanticsNode().boundsInRoot.width > 0f)
        assertEquals(0, fixture.opens.get())
    }

    fun testContextPercentageAndActualToggleAnimateInsideTheSameCompactBadge() {
        val events = mutableListOf<String>()
        for (input in listOf(20_000L, 4_000L, 0L)) {
            val value = app.daiyu.android.ui.model.CloudContextUsageUi(input, 272_000)
            val percent = value.label(java.util.Locale.US)
            val tokens = value.tokensLabel(java.util.Locale.US)
            if (input == 20_000L) assertEquals("20K/272K", tokens)
            show(composer().copy(contextFraction = value.fraction, contextUsageText = percent, contextTokensText = tokens),
                ChatScreenActions(onModelClick = { events.add("model") }, onMenuAction = { events.add("menu") }))
            val before = node("chat-context").assertHasClickAction().fetchSemanticsNode().boundsInRoot
            ui.onNodeWithText(percent, useUnmergedTree = true).assertIsDisplayed()
            val percentLetterSpacing = layout(percent).layoutInput.style.letterSpacing
            val automatic = ui.mainClock.autoAdvance
            try {
                ui.mainClock.autoAdvance = false
                node("chat-context").performClick()
                ui.mainClock.advanceTimeByFrame()
                ui.mainClock.advanceTimeBy(80)
                ui.onNodeWithText(percent, useUnmergedTree = true).assertExists()
                ui.onNodeWithText(tokens, useUnmergedTree = true).assertExists()
                val middle = node("chat-context").fetchSemanticsNode().boundsInRoot
                ui.mainClock.advanceTimeBy(320)
                ui.onNodeWithText(tokens, useUnmergedTree = true).assertIsDisplayed()
                ui.onNodeWithText(percent, useUnmergedTree = true).assertDoesNotExist()
                val after = node("chat-context").fetchSemanticsNode().boundsInRoot
                assertEquals(before.height, middle.height, 1f)
                assertEquals(before.height, after.height, 1f)
                assertTrue("badge width interpolates rather than jumping",
                    middle.width > minOf(before.width, after.width) + .5f &&
                        middle.width < maxOf(before.width, after.width) - .5f)
                val tokenLayout = layout(tokens)
                assertEquals(0.3.sp, tokenLayout.layoutInput.style.letterSpacing)
                assertNoTextOverflow(tokenLayout)
                node("chat-context").performClick()
                ui.mainClock.advanceTimeBy(400)
                ui.onNodeWithText(percent, useUnmergedTree = true).assertIsDisplayed()
                ui.onNodeWithText(tokens, useUnmergedTree = true).assertDoesNotExist()
                assertEquals(percentLetterSpacing, layout(percent).layoutInput.style.letterSpacing)
            } finally { ui.mainClock.autoAdvance = automatic }
            assertTrue("display toggles do not navigate or launch model requests", events.isEmpty())
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testEmptySendKeepsItsCircularBackgroundWithoutBecomingClickable() {
        for (dark in listOf(false, true)) for (canSend in listOf(false, true)) {
            val sent = mutableListOf<String>()
            val draft = if (canSend) "消息" else ""
            show(composer().copy(text = draft, phase = ComposerPhase.IDLE),
                actions = ChatScreenActions(onSend = { sent.add(it) }), dark = dark, opaqueBackground = true)
            awaitVisualIdle()
            val send = node("chat-send")
            if (canSend) send.assertIsEnabled() else send.assertIsNotEnabled()
            val pixels = send.captureToImage().toPixelMap()
            val behind = pixels[0, pixels.height / 2]
            val fill = (if (canSend) requireNotNull(actionColors).appendContainer else requireNotNull(actionColors).sendContainer)
                .copy(alpha = if (canSend || !dark) 1f else .4f).compositeOver(behind)
            val actual = pixels[pixels.width / 2, pixels.height / 5]
            assertEquals("send red dark=$dark enabled=$canSend", fill.red, actual.red, .015f)
            assertEquals("send green dark=$dark enabled=$canSend", fill.green, actual.green, .015f)
            assertEquals("send blue dark=$dark enabled=$canSend", fill.blue, actual.blue, .015f)
            touch(send)
            ui.runOnIdle { assertEquals(if (canSend) listOf(draft) else emptyList<String>(), sent) }
        }
    }

    fun testComposerVisibleControlsStayBalancedWithAvatarAcrossActionStates() {
        val states = listOf(
            Triple(composer().copy(text = "消息", phase = ComposerPhase.IDLE), "发送", 16f),
            Triple(composer().copy(text = "", phase = ComposerPhase.RUNNING), "暂停", 14f),
            Triple(composer().copy(text = "", phase = ComposerPhase.PAUSED), "继续", 14f),
            Triple(composer().copy(text = "补充", phase = ComposerPhase.PAUSED,
                supplementAvailable = true), "追加消息", 16f),
            Triple(composer().copy(text = "补充", phase = ComposerPhase.RUNNING,
                supplementAvailable = true), "追加消息", 16f),
        )
        for (dark in listOf(false, true)) for (scale in listOf(1f, .8f)) {
            for ((state, label, iconDp) in states) {
                show(state, fontScale = if (scale == 1f) 1f else 1.3f,
                    interfaceScale = scale, dark = dark)
                awaitVisualIdle()
                val density = node("chrome-host").fetchSemanticsNode().boundsInRoot.width / 320f
                fun assertGraphic(description: String, sizeDp: Float) {
                    val graphicNode = if (description == "推理强度，当前为 高") {
                        node("chat-reasoning").assertContentDescriptionEquals(description).assertHasClickAction()
                        ui.onNode(hasParent(hasTestTag("chat-reasoning")) and !hasClickAction(), useUnmergedTree = true)
                    } else ui.onNode(hasContentDescription(description) and !hasClickAction(), useUnmergedTree = true)
                    val graphic = graphicNode.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                    val slot = button(description).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                    assertEquals(sizeDp, graphic.width / density, .75f)
                    assertEquals(sizeDp, graphic.height / density, .75f)
                    assertEquals(32f, slot.width / density, .75f)
                    assertEquals(32f, slot.height / density, .75f)
                    assertEquals(slot.center.x, graphic.center.x, 1f)
                    assertEquals(slot.center.y, graphic.center.y, 1f)
                }
                assertGraphic("附件", 22.5f)
                assertGraphic("推理强度，当前为 高", 24f)
                assertGraphic(label, iconDp)
                val avatar = ui.onNodeWithTag("chat-assistant-avatar", useUnmergedTree = true)
                    .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertEquals(19f, avatar.width / density, .75f)
                assertEquals(19f, avatar.height / density, .75f)
                val assistantSlot = node("chat-assistant").assertIsDisplayed()
                    .assertWidthIsEqualTo(32.dp).assertHeightIsEqualTo(32.dp)
                    .fetchSemanticsNode().boundsInRoot
                assertEquals(assistantSlot.center.x, avatar.center.x, 1f)
                assertEquals(assistantSlot.center.y, avatar.center.y, 1f)
                assertEquals(((assistantSlot.width - avatar.width) / 2f).roundToInt().toFloat(),
                    avatar.left - assistantSlot.left, 0f)
                assertEquals(((assistantSlot.height - avatar.height) / 2f).roundToInt().toFloat(),
                    avatar.top - assistantSlot.top, 0f)
                val colors = requireNotNull(actionColors)
                val expected = colors.appendContainer
                val tag = if (label == "发送" || label == "追加消息") "chat-send" else "chat-control"
                val pixels = node(tag).assertIsEnabled().captureToImage().toPixelMap()
                fun distance(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color): Float {
                    val red = a.red - b.red
                    val green = a.green - b.green
                    val blue = a.blue - b.blue
                    return red * red + green * green + blue * blue
                }
                val y = pixels.height / 2
                val exactFill = (0 until pixels.width).any { x ->
                    val color = pixels[x, y]
                    abs(color.red - expected.red) < .025f &&
                        abs(color.green - expected.green) < .025f &&
                        abs(color.blue - expected.blue) < .025f
                }
                assertTrue("$label must draw its circular surface in dark=$dark, scale=$scale", exactFill)
                val covered = (0 until pixels.width).filter { x ->
                    val background = pixels[if (x < pixels.width / 2) 0 else pixels.width - 1, y]
                    distance(pixels[x, y], expected) < distance(pixels[x, y], background)
                }
                assertTrue("$label must have a measurable circular surface", covered.isNotEmpty())
                val diameter = (covered.last() - covered.first() + 1) / density
                assertEquals("$label circle dark=$dark scale=$scale density=$density bitmap=${pixels.width}x${pixels.height}",
                    28f, diameter, 1.5f)
            }
        }
    }

    fun testEnlargedComposerGrowsForMultilineDraftAndKeepsLargeTextControlsSeparate() {
        for (dark in listOf(false, true)) {
            val state = composer().copy(text = "短消息", phase = ComposerPhase.IDLE)
            show(state, fontScale = 1.3f, interfaceScale = .8f, dark = dark)
            val shortHeight = node("chat-composer-surface").fetchSemanticsNode().boundsInRoot.height
            show(state.copy(text = List(6) { "第${it + 1}行消息" }.joinToString("\n")),
                fontScale = 1.3f, interfaceScale = .8f, dark = dark)
            val host = node("chrome-host").fetchSemanticsNode().boundsInRoot
            val surface = node("chat-composer-surface").fetchSemanticsNode().boundsInRoot
            val draft = node("chat-draft").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val bottom = node("chat-composer-bottom").fetchSemanticsNode().boundsInRoot
            assertTrue(surface.height > shortHeight)
            assertTrue(draft.bottom <= bottom.top + 1f)
            val controls = listOf(button("附件"), node("chat-reasoning"), node("chat-assistant"),
                button("语音输入"), node("chat-send"))
            val bounds = controls.map {
                it.assertIsDisplayed().assertWidthIsEqualTo(32.dp).assertHeightIsEqualTo(32.dp)
                it.fetchSemanticsNode().boundsInRoot.also { rect ->
                    assertTrue(rect.left >= surface.left && rect.right <= surface.right)
                    assertTrue(rect.top >= bottom.top - 1f && rect.bottom <= bottom.bottom + 1f)
                }
            }
            for (i in bounds.indices) for (j in i + 1 until bounds.size) {
                val a = bounds[i]; val b = bounds[j]
                assertFalse("Enlarged controls overlap: $a / $b",
                    a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom)
            }
            assertTrue(abs(bounds[3].center.x - host.center.x) <= 1f)
            assertFalse(layout("37% 上下文").isLineEllipsized(0))
            assertEquals(16f, (host.bottom - surface.bottom) / (host.width / 320f), .75f)
        }
        assertEquals(0, fixture.opens.get())
    }

    fun testMoreButtonHasNoPressedOrExpandedBackgroundInEitherTheme() {
        for (dark in listOf(false, true)) {
            show(composer(), dark = dark, opaqueBackground = true)
            val more = button("更多")
            dismissIme()
            awaitImeFrame(more, visible = false)
            val idle = captureScreenNode(more).toPixelMap()
            more.performTouchInput { down(center) }
            val pressed = try { captureScreenNode(more).toPixelMap() }
                finally { more.performTouchInput { up() } }
            more.assertIsSelected()
            val expanded = captureScreenNode(more).toPixelMap()
            for ((state, image) in listOf("pressed" to pressed, "expanded" to expanded)) {
                for ((fx, fy) in listOf(.2f to .2f, .8f to .2f, .2f to .8f, .8f to .8f)) {
                    val x = (idle.width * fx).toInt().coerceAtMost(idle.width - 1)
                    val y = (idle.height * fy).toInt().coerceAtMost(idle.height - 1)
                    val expected = idle[x, y]
                    val actual = image[x, y]
                    assertEquals("More background red, dark=$dark state=$state", expected.red, actual.red, .01f)
                    assertEquals("More background green, dark=$dark state=$state", expected.green, actual.green, .01f)
                    assertEquals("More background blue, dark=$dark state=$state", expected.blue, actual.blue, .01f)
                }
            }
            touch(node("chat-menu:NEW_CHAT"))
            ui.mainClock.advanceTimeBy(176)
            node("chat-overflow-menu").assertDoesNotExist()
        }
    }

    fun testExpandedMoreStateAndFullMenuKeepBothCompressionRowsReachable() {
        show(composer())
        button("更多").assertIsNotSelected()
        touch(button("更多"))
        button("更多").assertIsSelected()
        node("chat-menu:COMPRESS_CONTEXT").assertIsDisplayed()
        node("chat-menu:AUTO_COMPRESS").scrollToVisible().assertIsDisplayed().assertIsNotEnabled()
        ui.onNodeWithTag("chat-menu-switch:AUTO_COMPRESS", useUnmergedTree = true)
            .assertExists().assertWidthIsEqualTo(25.dp).assertHeightIsEqualTo(15.dp)
        touch(node("chat-menu:NEW_CHAT").scrollToVisible())
        button("更多").assertIsNotSelected()
        ui.mainClock.advanceTimeBy(176)
        node("chat-overflow-menu").assertDoesNotExist()
    }

    fun testComposerBalanceIsReadableAndKeepsControlsSeparateAt320Dp() {
        val state = composer().copy(text = "ready", phase = ComposerPhase.IDLE)
        val amount = formatBalanceDisplay("17.35")
        for (dark in listOf(false, true)) for (font in listOf(1f, 1.3f)) {
            val sent = mutableListOf<String>()
            val balance = BalanceState(result = BalanceResult.Available(BalanceMoney("17.35".toBigDecimal(), "USD"), 1L))
            show(state, ChatScreenActions(onSend = { sent += it }), fontScale = font, dark = dark, balance = balance)
            val amountNode = ui.onNode(hasText(amount) and hasAnyAncestor(hasTestTag("chat-composer-provider-balance")),
                useUnmergedTree = true)
            amountNode.assertIsDisplayed().assertHasNoClickAction()
            val text = layout(amount)
            assertEquals(14.sp, text.layoutInput.style.fontSize)
            assertEquals(28.sp, text.layoutInput.style.lineHeight)
            assertEquals(1, text.lineCount)
            assertNoTextOverflow(text)
            val indicator = node("chat-composer-provider-balance").assertIsDisplayed().assertHasNoClickAction()
                .fetchSemanticsNode().boundsInRoot
            val dp = node("chrome-host").fetchSemanticsNode().boundsInRoot.width / 320f
            assertTrue(indicator.width <= 72f * dp + 1f)
            val textBounds = amountNode.fetchSemanticsNode().boundsInRoot
            assertTrue("16dp icon plus 4dp gap", abs(indicator.width - textBounds.width - 20f * dp) <= 2f)
            val voice = button("语音输入").assertWidthIsEqualTo(32.dp).assertHeightIsEqualTo(32.dp).fetchSemanticsNode().boundsInRoot
            val send = node("chat-send").assertWidthIsEqualTo(32.dp).assertHeightIsEqualTo(32.dp).fetchSemanticsNode().boundsInRoot
            assertTrue("balance clears centered voice", voice.right <= indicator.left + 1f)
            assertEquals("balance-to-send gap", 15f, (send.left - indicator.right) / dp, .75f)
            assertEquals("send keeps its right anchor", 22f,
                (node("chrome-host").fetchSemanticsNode().boundsInRoot.right - send.right) / dp, .75f)
            assertTrue("send stays inside composer", send.right <= node("chrome-host").fetchSemanticsNode().boundsInRoot.right + 1f)
            node("chat-send").performClick()
            assertEquals(listOf("ready"), sent)
        }
    }

    fun testLongComposerBalanceStaysSingleLineWithoutPushingVoiceOrSend() {
        val amount = "123456789012345678901234567890.12"
        val balance = BalanceState(result = BalanceResult.Available(BalanceMoney(amount.toBigDecimal(), "USD"), 1L))
        for (dark in listOf(false, true)) {
            show(composer().copy(text = "ready", phase = ComposerPhase.IDLE), fontScale = 1.3f, dark = dark, balance = balance)
            val text = layout(formatBalanceDisplay(amount))
            assertEquals(14.sp, text.layoutInput.style.fontSize)
            assertEquals(1, text.lineCount)
            assertTrue("long amount is ellipsized", text.isLineEllipsized(0))
            val indicator = node("chat-composer-provider-balance").assertIsDisplayed().assertHasNoClickAction()
                .fetchSemanticsNode().boundsInRoot
            val dp = node("chrome-host").fetchSemanticsNode().boundsInRoot.width / 320f
            assertTrue("long amount reaches 72dp cap", abs(indicator.width - 72f * dp) <= 1f)
            val voice = button("语音输入").assertWidthIsEqualTo(32.dp).assertHeightIsEqualTo(32.dp).fetchSemanticsNode().boundsInRoot
            val send = node("chat-send").assertWidthIsEqualTo(32.dp).assertHeightIsEqualTo(32.dp).fetchSemanticsNode().boundsInRoot
            assertTrue(voice.right <= indicator.left + 1f)
            assertEquals(15f, (send.left - indicator.right) / dp, .75f)
            assertEquals(22f, (node("chrome-host").fetchSemanticsNode().boundsInRoot.right - send.right) / dp, .75f)
        }
    }

    fun testBalanceIconSizingKeepsSharedDefaultInStaticAndAnimatedBranches() {
        val amount = formatBalanceDisplay("17.35")
        val balance = BalanceState(result = BalanceResult.Available(BalanceMoney("17.35".toBigDecimal(), "USD"), 1L))
        for (animated in listOf(false, true)) for (large in listOf(false, true)) {
            mainThread {
                activity.setContent {
                    AgentAppTheme(AppearanceSettings(themeMode = AppearanceThemeMode.LIGHT), applyInterfaceScale = false) {
                        Column(Modifier.requiredWidth(320.dp).testTag("balance-size-host")) {
                            val modifier = Modifier.testTag("balance-size-fixture")
                            if (large) app.daiyu.android.balance.ui.ProviderBalanceIndicator(balance, modifier,
                                animateChanges = animated, iconSize = 16.dp)
                            else app.daiyu.android.balance.ui.ProviderBalanceIndicator(balance, modifier,
                                animateChanges = animated)
                        }
                    }
                }
            }
            val indicator = node("balance-size-fixture").assertIsDisplayed().assertHasNoClickAction()
                .fetchSemanticsNode().boundsInRoot
            val text = ui.onNodeWithText(amount, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val dp = node("balance-size-host").fetchSemanticsNode().boundsInRoot.width / 320f
            assertTrue("icon size in animated=$animated, large=$large",
                abs(indicator.width - text.width - (if (large) 20f else 16f) * dp) <= 2f)
        }
    }

    fun testIdleTextDispatchesSendAndNeverSupplement() {
        val sent = mutableListOf<String>()
        val state = composer().copy(phase = ComposerPhase.IDLE)
        show(state, ChatScreenActions(
            onSend = { sent.add(it) },
            onSupplement = { error("idle text must not append to a run") },
        ))
        node("chat-send").assertIsEnabled().assertContentDescriptionEquals("发送")
        touch(node("chat-send"))
        ui.runOnIdle { assertEquals(listOf(state.text), sent) }
    }

    fun testRunningTextAppendsOnceAndLongPressNeverStopsOrPauses() {
        val supplements = mutableListOf<String>()
        var sends = 0; var primaryTaps = 0; var stops = 0
        val state = composer().copy(supplementAvailable = true)
        show(state, ChatScreenActions(onSend = { sends++ }, onSupplement = { supplements.add(it) },
            onPrimaryTap = { primaryTaps++ }, onPrimaryLongPress = { stops++ }))
        node("chat-control").assertDoesNotExist()
        node("chat-send").assertIsEnabled().assertContentDescriptionEquals("追加消息")
        touch(node("chat-send"))
        ui.runOnIdle { assertEquals(listOf(state.text), supplements); assertEquals(0, sends); assertEquals(0, primaryTaps) }
        node("chat-send").performTouchInput { longClick() }
        ui.runOnIdle {
            assertEquals(listOf(state.text), supplements)
            assertEquals(0, sends); assertEquals(0, stops); assertEquals(0, primaryTaps)
        }
    }

    fun testOverflowHasOnlyOriginalEntriesAndNoInventedSectionHeadings() {
        show(composer())
        touch(button("更多"))
        for (text in listOf("会话", "工具与工作区", "上下文", "会话管理", "重命名", "会话 ID", "导出",
            "加载更早消息", "定位用户消息", "回到底部", "设置", "启动 Kimi Web", "停止 Kimi Web")) {
            ui.onNodeWithText(text, substring = false, useUnmergedTree = true).assertDoesNotExist()
        }
        val expected = listOf("NEW_CHAT", "SEARCH", "INTERACTIVE_MODE", "OPEN_TERMINAL", "OPEN_BROWSER",
            "OPEN_WORKSPACE", "SUB_AGENT", "CONTEXT_STATS", "COMPRESS_CONTEXT", "AUTO_COMPRESS")
        val actual = ui.onAllNodes(SemanticsMatcher("overflow menu item") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("chat-menu:") == true
        }).fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag].removePrefix("chat-menu:") }
        assertEquals(expected, actual)
        for (action in expected) node("chat-menu:$action").scrollToVisible().assertIsDisplayed().assertHeightIsEqualTo(38.dp)
    }

    fun testNewChatAndSearchMenuDispatchOnceAndClose() {
        val events = mutableListOf<String>()
        show(composer(), ChatScreenActions(onMenuAction = { events.add(it.name) }))
        for (action in listOf("NEW_CHAT", "SEARCH")) {
            touch(button("更多"))
            touch(node("chat-menu:$action"))
            ui.mainClock.advanceTimeBy(176)
            node("chat-overflow-menu").assertDoesNotExist()
        }
        ui.runOnIdle { assertEquals(listOf("NEW_CHAT", "SEARCH"), events) }
    }

    fun testMenuTogglesStayOpenAndUseCssTextSize() {
        val events = mutableListOf<String>()
        show(composer(), ChatScreenActions(onMenuAction = { events.add(it.name) }))
        touch(button("更多"))
        touch(node("chat-menu:INTERACTIVE_MODE"))
        node("chat-overflow-menu").assertIsDisplayed()
        assertEquals(12.sp, layout("交互模式").layoutInput.style.fontSize)
        assertEquals(18.sp, layout("交互模式").layoutInput.style.lineHeight)
        ui.runOnIdle { assertEquals(listOf("INTERACTIVE_MODE"), events) }
    }

    fun testDisabledComposerDoesNotAcquireControlGestureOrDispatchActions() {
        var callbacks = 0
        show(composer().copy(text = "", enabled = false), ChatScreenActions(
            onControlPress = { callbacks++ }, onControlRelease = { callbacks++ },
            onPrimaryTap = { callbacks++ }, onPrimaryLongPress = { callbacks++ },
            onSend = { callbacks++ }, onSupplement = { callbacks++ }, onOpenReasoning = { callbacks++ }, onOpenAssistant = { callbacks++ },
        ))
        node("chat-send").assertDoesNotExist()
        listOf("chat-control", "chat-reasoning", "chat-assistant").forEach {
            node(it).assertIsNotEnabled().performTouchInput { click(center) }
        }
        node("chat-control").performTouchInput { longClick() }
        ui.runOnIdle { assertEquals(0, callbacks) }
    }
}
