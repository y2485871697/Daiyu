package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
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
