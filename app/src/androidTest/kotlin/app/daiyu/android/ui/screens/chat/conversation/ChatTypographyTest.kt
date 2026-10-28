package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daiyu.android.MarkdownParser
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.domain.RunStatus
import app.daiyu.android.ui.app.AgentAppTheme

class ChatTypographyTest : WiredActivityTestCase() {
    private val user = "用户正文保持原样 **不是标题**，放大后仍能换行阅读。"
    private val raw = "流式正文尚在解析 **保持可读**"
    private val ordinary = "普通助手正文，说明能力与回答问题，不需要眯着眼睛阅读。"
    private val mixed = "混合消息正文与普通回答保持相同字号和行高。"
    private val structured = "## 小标题\n\n- 列表正文\n\n> 引用正文\n\n" +
        "| 表头 | 第二列 |\n| --- | --- |\n| 表格正文 | 内容 |\n\n```kotlin\nval example = 1\n```"

    private fun show(dark: Boolean, scale: Float, fontScale: Float) {
        val parsed = MarkdownParser.parse(structured)
        mainThread {
            activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    key(dark, scale, fontScale) {
                        AgentAppTheme(AppearanceSettings(
                            themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT,
                            interfaceScale = scale), true) {
                            LazyColumn(Modifier.requiredWidth(320.dp).testTag("type-list")) {
                                item { UserMessageText(user) }
                                item { ParsedMessageMarkdown(raw, null) }
                                item {
                                    AssistantTurn(ChatTurnUi.Assistant("ordinary", finalText = ordinary,
                                        status = RunStatus.RUNNING), ChatScreenActions())
                                }
                                item {
                                    AssistantTurn(ChatTurnUi.Assistant("mixed", status = RunStatus.RUNNING,
                                        content = listOf(MessageContentUi.Text("body", mixed))), ChatScreenActions())
                                }
                                item { ParsedMessageMarkdown(structured, parsed) }
                            }
                        }
                    }
                }
            }
        }
        ui.waitForIdle()
    }

    private fun layout(text: String): TextLayoutResult {
        node("type-list").scrollToDescendant(hasText(text))
        val target = ui.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
        val results = mutableListOf<TextLayoutResult>()
        target.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
        val host = node("type-list").fetchSemanticsNode().boundsInRoot
        val bounds = target.fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.left >= host.left - 1f && bounds.right <= host.right + 1f)
        return results.single()
    }

    fun testBodiesListsQuotesAndTablesAreReadableWithoutDoubleScaling() {
        for (dark in listOf(false, true)) for (scale in listOf(1f, .8f)) for (fontScale in listOf(1f, 1.3f)) {
            show(dark, scale, fontScale)
            for (text in listOf(user, raw, ordinary, mixed, "列表正文", "引用正文", "表头", "表格正文")) {
                val result = layout(text)
                assertEquals(16.sp, result.layoutInput.style.fontSize)
                assertEquals(26.sp, result.layoutInput.style.lineHeight)
                assertEquals(fontScale, result.layoutInput.density.fontScale, .001f)
                assertFalse("Text must wrap, not clip: $text", result.hasVisualOverflow)
            }
            val heading = layout("小标题")
            assertEquals(22.sp, heading.layoutInput.style.fontSize)
            assertEquals(30.sp, heading.layoutInput.style.lineHeight)
            val code = layout("val example = 1")
            assertEquals(14.sp, code.layoutInput.style.fontSize)
            assertEquals(22.sp, code.layoutInput.style.lineHeight)
        }
        assertEquals(0, fixture.opens.get())
    }
}
