package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import app.daiyu.android.ui.screens.assistants.AssistantPreviewField
import app.daiyu.android.ui.screens.chat.conversation.ToolMiniAction
import app.daiyu.android.ui.screens.chat.conversation.UserMessageText
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.ui.screens.assistants.AssistantPreviewPalette
import top.yukonga.miuix.kmp.basic.Button as MiuixButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.components.UsageAction
import app.daiyu.android.ui.components.subagent.ConversationAgentButton
import app.daiyu.android.ui.screens.assistants.AssistantPreviewButton

class PreviewControlGeometryTest : WiredActivityTestCase() {
    fun testButtonFamiliesKeepTheirCssHeightWithoutScreenWidthScaling() {
        mainThread {
            activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1f)) {
                    AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                        Column {
                            AssistantPreviewButton("确认", {}, Modifier.testTag("assistant-button"))
                            ConversationAgentButton("确认", modifier = Modifier.testTag("agent-button"), onClick = {})
                            UsageAction("确认", {}, Modifier.testTag("usage-button"))
                        }
                    }
                }
            }
        }
        node("assistant-button").assertHeightIsEqualTo(44.dp)
        node("agent-button").assertHeightIsEqualTo(44.dp)
        node("usage-button").assertHeightIsEqualTo(44.dp)
    }
    fun testNormalButtonCornersMatchMiuixInBothThemesAndInterfaceScales() {
        for (dark in listOf(false, true)) for (scale in listOf(1f, .8f)) {
            val backdrop = if (dark) Color(0xFF101218) else Color.White
            val assistantFill = AssistantPreviewPalette(dark).primary
            val agentFill = Color(0xFF786C83)
            mainThread {
                activity.setContent {
                    val platform = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(platform.density, 1f)) {
                        AgentAppTheme(AppearanceSettings(interfaceScale = scale,
                            themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                            applyInterfaceScale = true) {
                            Column(Modifier.background(backdrop).padding(16.dp)) {
                                MiuixButton(onClick = {}, modifier = Modifier.size(160.dp, 44.dp)
                                    .testTag("assistant-corner-reference"),
                                    insideMargin = PaddingValues(0.dp),
                                    colors = ButtonDefaults.buttonColors(color = assistantFill)) {}
                                AssistantPreviewButton("", {}, Modifier.width(160.dp)
                                    .testTag("assistant-corner-actual"), primary = true)
                                MiuixButton(onClick = {}, modifier = Modifier.size(160.dp, 44.dp)
                                    .testTag("agent-corner-reference"),
                                    insideMargin = PaddingValues(0.dp),
                                    colors = ButtonDefaults.buttonColors(color = agentFill)) {}
                                ConversationAgentButton("", primary = true, primaryColor = agentFill,
                                    modifier = Modifier.width(160.dp).testTag("agent-corner-actual"), onClick = {})
                            }
                        }
                    }
                }
            }
            awaitUniqueTag("agent-corner-actual")
            for ((family, fill) in listOf("assistant" to assistantFill, "agent" to agentFill)) {
                val reference = node("$family-corner-reference").captureToImage().toPixelMap()
                val actual = node("$family-corner-actual").captureToImage().toPixelMap()
                assertTrue("$family width", kotlin.math.abs(reference.width - actual.width) <= 1)
                assertTrue("$family height", kotlin.math.abs(reference.height - actual.height) <= 1)
                for (bottom in listOf(false, true)) for (right in listOf(false, true)) {
                    for (row in 1 until minOf(reference.height, actual.height) / 2) {
                        val expectedInset = cornerInset(reference, row, bottom, right, backdrop, fill)
                        val actualInset = cornerInset(actual, row, bottom, right, backdrop, fill)
                        assertTrue("$family dark=$dark scale=$scale bottom=$bottom right=$right row=$row: " +
                            "$actualInset != $expectedInset",
                            kotlin.math.abs(expectedInset - actualInset) <= 1)
                    }
                }
            }
        }
    }

    fun testConversationPrimaryUsesCollaborationPaletteAndDisabledWholeSurface() {
        for (dark in listOf(false, true)) {
            var clicks = 0
            val palette = AssistantPreviewPalette(dark)
            val backdrop = if (dark) Color(0xFF101218) else Color.White
            mainThread { activity.setContent {
                AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                    applyInterfaceScale = false) {
                    Column(Modifier.background(backdrop).padding(16.dp)) {
                        ConversationAgentButton("确认", primary = true, modifier = Modifier.width(160.dp)
                            .testTag("agent-default-primary"), onClick = { clicks++ })
                        AssistantPreviewButton("完成", {}, Modifier.width(160.dp).testTag("collaboration-primary"), primary = true)
                        ConversationAgentButton("禁用", primary = true, enabled = false, modifier = Modifier.width(160.dp)
                            .testTag("agent-disabled-primary"), onClick = { clicks++ })
                    }
                }
            } }
            awaitUniqueTag("agent-default-primary")
            fun sample(tag: String): Color {
                val pixels = captureScreenNode(node(tag)).toPixelMap()
                return pixels[pixels.width / 2, (pixels.height * .15f).toInt()]
            }
            fun matches(expected: Color, actual: Color) {
                assertEquals(expected.red, actual.red, .025f)
                assertEquals(expected.green, actual.green, .025f)
                assertEquals(expected.blue, actual.blue, .025f)
            }
            matches(palette.primary, sample("agent-default-primary"))
            matches(sample("collaboration-primary"), sample("agent-default-primary"))
            matches(palette.primary.copy(alpha = .4f).compositeOver(backdrop), sample("agent-disabled-primary"))
            node("agent-disabled-primary").assertIsNotEnabled().performTouchInput { click() }
            node("agent-default-primary").performClick()
            assertEquals(1, clicks)
        }
    }

    private fun cornerInset(pixels: PixelMap, row: Int, bottom: Boolean, right: Boolean,
        backdrop: Color, fill: Color): Int {
        val y = if (bottom) pixels.height - 1 - row else row
        fun distance(a: Color, b: Color): Float =
            (a.red - b.red) * (a.red - b.red) + (a.green - b.green) * (a.green - b.green) +
                (a.blue - b.blue) * (a.blue - b.blue)
        return (0 until pixels.width / 2).firstOrNull { inset ->
            val x = if (right) pixels.width - 1 - inset else inset
            distance(pixels[x, y], fill) <= distance(pixels[x, y], backdrop)
        } ?: pixels.width / 2
    }

    fun testPlatformFontScaleIsAppliedOnlyOnceAndTextareasKeepCssMinimums() {
        val promptHeight = AtomicInteger()
        val memoryHeight = AtomicInteger()
        val fieldDensity = AtomicReference<Density>()
        mainThread {
            activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, .9f)) {
                    AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                        Column {
                            AssistantPreviewButton("助手确认", {})
                            ConversationAgentButton("代理确认", onClick = {})
                            UsageAction("用量确认", {})
                            ToolMiniAction("工具确认", Modifier.testTag("tool-mini"), onClick = {})
                            UserMessageText("用户正文")
                            fieldDensity.set(LocalDensity.current)
                            AssistantPreviewField("", {}, "提示词", singleLine = false,
                                minHeight = 110.dp, maxHeight = 250.dp,
                                fieldModifier = Modifier.onSizeChanged { promptHeight.set(it.height) }.testTag("prompt-area"))
                            AssistantPreviewField("", {}, "记忆", singleLine = false,
                                minHeight = 90.dp, maxHeight = 180.dp, bottomSpacing = 0.dp,
                                fieldModifier = Modifier.onSizeChanged { memoryHeight.set(it.height) }.testTag("memory-area"))
                        }
                    }
                }
            }
        }
        for ((text, size) in listOf("助手确认" to 14.sp, "代理确认" to 14.sp,
            "用量确认" to 14.sp, "工具确认" to 8.sp, "用户正文" to 16.sp)) {
            val results = mutableListOf<TextLayoutResult>()
            ui.onNodeWithText(text, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
            assertEquals(size, results.single().layoutInput.style.fontSize)
            assertEquals(.9f, results.single().layoutInput.density.fontScale)
            if (text == "用户正文") assertEquals(26.sp, results.single().layoutInput.style.lineHeight)
        }
        node("tool-mini").assertHeightIsEqualTo(27.dp)
        node("prompt-area").assertExists()
        node("memory-area").assertExists()
        ui.waitForIdle()
        val density = checkNotNull(fieldDensity.get()).density
        assertTrue(promptHeight.get() > 0 && memoryHeight.get() > 0)
        // Measure the decorated field; text semantics exclude its 9dp vertical padding.
        assertEquals(110f, promptHeight.get() / density, .5f)
        assertEquals(90f, memoryHeight.get() / density, .5f)
    }

}
