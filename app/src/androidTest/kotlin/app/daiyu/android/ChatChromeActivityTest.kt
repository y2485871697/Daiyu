package app.daiyu.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme
import app.daiyu.android.ui.screens.chat.conversation.ChatComposer
import app.daiyu.android.ui.screens.chat.conversation.ChatScreenActions
import app.daiyu.android.ui.screens.chat.conversation.ChatTopBar
import app.daiyu.android.ui.screens.chat.conversation.ComposerPhase
import app.daiyu.android.ui.screens.chat.conversation.ComposerUi
import kotlin.math.abs

/** UI geometry and action dispatch; real run ownership stays covered by RunControlActivityTest. */
class ChatChromeActivityTest : WiredActivityTestCase() {
    private fun composer() = ComposerUi(
        text = "  下一条消息  ", phase = ComposerPhase.RUNNING,
        modelLabel = "超长模型名称-provider/model-".repeat(20),
        reasoningLabel = "高", assistantLabel = "代码助手",
        contextFraction = 0.37f, controlOwnerKey = "chrome-owner",
    )

    private fun show(composer: ComposerUi, actions: ChatScreenActions = ChatScreenActions()) {
        mainThread {
            activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1f)) {
                    AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                        Column(Modifier.requiredWidth(320.dp).testTag("chrome-host")) {
                            ChatTopBar("测试会话", null, actions, assistantLabel = composer.assistantLabel)
                            ChatComposer(composer, actions)
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

    fun testRunningComposerAt320DpKeepsActionsSeparateAndContextReadable() {
        val state = composer()
        show(state)
        val host = node("chrome-host").assertWidthIsEqualTo(320.dp).fetchSemanticsNode().boundsInRoot
        val controls = listOf(button("附件"), button("推理强度，当前为 高"),
            button("选择助手，当前为 代码助手"), button("语音输入"), node("chat-control"), node("chat-send"))
        val bounds = controls.mapIndexed { index, target ->
            target.assertIsDisplayed().assertHasClickAction()
                .assertWidthIsEqualTo(if (index == 3) 48.dp else 40.dp)
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
        assertFalse("Context must fit: constraints=${contextLayout.layoutInput.constraints}, " +
            "size=${contextLayout.size}, paragraphWidth=${contextLayout.multiParagraph.width}, " +
            "intrinsicWidth=${contextLayout.multiParagraph.intrinsics.maxIntrinsicWidth}, bounds=$context, host=$host",
            contextLayout.didOverflowWidth)
    }

    fun testTouchDispatchesPickersMenusSendPauseAndLongStop() {
        val events = mutableListOf<String>()
        var presses = 0; var releases = 0
        val state = composer()
        show(state, ChatScreenActions(
            onOpenReasoning = { events.add("reasoning") }, onOpenAssistant = { events.add("assistant") },
            onMore = { events.add("more") }, onMenuAction = { events.add("menu:${it.name}") },
            onModelClick = { events.add("model") }, onContextClick = { events.add("context") },
            onVoice = { events.add("voice") }, onSend = { events.add("send<$it>") },
            onPrimaryTap = { events.add("pause") }, onPrimaryLongPress = { events.add("stop") },
            onControlPress = { presses++ }, onControlRelease = { releases++ },
        ))
        touch(node("chat-reasoning")); touch(node("chat-assistant"))
        touch(button("新建会话")); touch(button("更多"))
        touch(ui.onNodeWithText("重命名").performScrollTo())
        ui.onNodeWithText("重命名").assertDoesNotExist()
        touch(node("chat-model")); touch(node("chat-context")); touch(button("语音输入"))
        node("chat-send").assertContentDescriptionEquals("追加消息")
        touch(node("chat-send")); touch(node("chat-control"))
        node("chat-control").performTouchInput { longClick() }
        ui.runOnIdle {
            assertEquals(listOf("reasoning", "assistant", "menu:NEW_CHAT", "more", "menu:RENAME",
                "model", "context", "voice", "send<${state.text}>", "pause", "stop"), events.toList())
            assertEquals(3, presses); assertEquals(3, releases)
        }
    }

    fun testDisabledComposerDoesNotAcquireControlGestureOrDispatchActions() {
        var callbacks = 0
        show(composer().copy(enabled = false), ChatScreenActions(
            onControlPress = { callbacks++ }, onControlRelease = { callbacks++ },
            onPrimaryTap = { callbacks++ }, onPrimaryLongPress = { callbacks++ },
            onSend = { callbacks++ }, onOpenReasoning = { callbacks++ }, onOpenAssistant = { callbacks++ },
        ))
        listOf("chat-control", "chat-send", "chat-reasoning", "chat-assistant").forEach {
            node(it).assertIsNotEnabled().performTouchInput { click(center) }
        }
        node("chat-control").performTouchInput { longClick() }
        ui.runOnIdle { assertEquals(0, callbacks) }
    }
}
