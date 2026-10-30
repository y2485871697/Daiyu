package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.sp
import app.daiyu.android.MarkdownBlock
import app.daiyu.android.MarkdownParser
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.domain.*
import app.daiyu.android.ui.app.AgentAppTheme
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ReportedReasoningMarkdownTest : WiredActivityTestCase() {
    private class ParseGate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fun awaitRelease() {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "Test did not release Markdown parser" }
        }
    }

    private fun await(condition: () -> Boolean) {
        ui.waitUntil(5_000) {
            ui.mainClock.advanceTimeBy(32)
            condition()
        }
    }

    private fun awaitText(text: String) = await {
        ui.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().size == 1
    }

    fun testRichHistoricalSummaryRendersMarkdownWithoutChangingAnswer() {
        val source = "**Planning concise warm Chinese story answer** *italic* `**literal**`\n\n" +
            "## Heading\n\n- **first item**\n- second item\n\n```text\nprintln(\"**kept**\")\n```"
        val summary = ReportedReasoning("r", source, 1_200L)
        val message = Message(MessageId("history"), ConversationId("chat"), RunId("run"), Message.ASSISTANT_ROLE,
            listOf(MessageBlock.Text("body", "answer remains")), RunStatus.COMPLETED,
            reportedReasoning = listOf(summary))
        prepareRestoredMarkdown(listOf(message))
        val opened = mutableListOf<String>()
        val handler = object : UriHandler { override fun openUri(uri: String) { opened += uri } }
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                CompositionLocalProvider(LocalUriHandler provides handler) {
                    Column(Modifier.width(340.dp).verticalScroll(rememberScrollState())) {
                        AssistantTurn(ChatTurnUi.Assistant("history", finalText = "answer remains",
                            status = RunStatus.COMPLETED, reportedReasoning = listOf(summary)), ChatScreenActions())
                    }
                }
            }
        } }
        ui.onNodeWithTag("work-process-header").assertExists().performClick()
        ui.onNodeWithContentDescription("展开模型已报告的思考 / 摘要").performClick()
        val text = ui.onNodeWithText("Planning concise warm Chinese story answer italic **literal**", useUnmergedTree = true)
        val rendered = text.assertIsDisplayed().fetchSemanticsNode().config[SemanticsProperties.Text].single()
        assertTrue(rendered.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertTrue(rendered.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
        assertTrue(rendered.spanStyles.any { it.item.fontFamily == FontFamily.Monospace })
        val layouts = mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        assertEquals(16.sp, layouts.single().layoutInput.style.fontSize)
        assertEquals(26.sp, layouts.single().layoutInput.style.lineHeight)
        ui.onNodeWithText("Heading", useUnmergedTree = true).assertExists()
        ui.onNodeWithText("first item", useUnmergedTree = true).assertExists()
        ui.onNodeWithTag("markdown-code-text", useUnmergedTree = true).assertTextEquals("println(\"**kept**\")")
        assertTrue(ui.onNodeWithTag("markdown-code-viewport", useUnmergedTree = true)
            .getUnclippedBoundsInRoot().height <= 220.dp)
        ui.onNodeWithText("answer remains", useUnmergedTree = true).assertExists()
        ui.onAllNodesWithTag("markdown-pending", useUnmergedTree = true).assertCountEquals(0)
        assertEquals(source, summary.text)
        assertEquals(source, message.reportedReasoning.single().text)
        assertTrue(opened.isEmpty())
    }

    fun testColdAndAppendedSummaryNeverExposeRawMarkup() {
        ui.mainClock.autoAdvance = false
        val first = "Cold **first**."
        val second = "$first **second**."
        val gates = mapOf(first to ParseGate(), second to ParseGate())
        val parser: (String) -> List<MarkdownBlock> = { source ->
            assertNotSame(android.os.Looper.getMainLooper().thread, Thread.currentThread())
            gates[source]?.awaitRelease()
            MarkdownParser.parse(source)
        }
        var source by mutableStateOf(first)
        try {
            mainThread { activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    CompositionLocalProvider(LocalMarkdownParser provides parser) {
                        ReportedReasoningCard("cold", listOf(ReportedReasoning("r", source, 0L, 0L)),
                            allowLiveTiming = true, nanoTime = { 0L })
                    }
                }
            } }
            await { gates.getValue(first).entered.count == 0L }
            ui.onAllNodesWithTag("markdown-pending", useUnmergedTree = true).assertCountEquals(0)
            ui.onNodeWithText(first, useUnmergedTree = true).assertDoesNotExist()
            gates.getValue(first).release.countDown()
            awaitText("Cold first.")
            await { ui.onNodeWithText("Cold first.", useUnmergedTree = true).isDisplayed() }
            mainThread { source = second }
            await { gates.getValue(second).entered.count == 0L }
            ui.onNodeWithText("Cold first.", useUnmergedTree = true).assertIsDisplayed()
            ui.onNodeWithText(second, useUnmergedTree = true).assertDoesNotExist()
            gates.getValue(second).release.countDown()
            awaitText("Cold first. second.")
            ui.onNodeWithText("Cold first.", useUnmergedTree = true).assertDoesNotExist()
        } finally {
            gates.values.forEach { it.release.countDown() }
            ui.mainClock.autoAdvance = true
        }
    }

    fun testOwnerAndItemChangesDoNotBorrowPreviousFormattedSummary() {
        ui.mainClock.autoAdvance = false
        val sources = listOf("**prefix**", "**prefix** second", "**prefix** second third")
        val gates = sources.associateWith { ParseGate() }
        val parser: (String) -> List<MarkdownBlock> = { source ->
            gates.getValue(source).awaitRelease()
            MarkdownParser.parse(source)
        }
        var owner by mutableStateOf("owner-a")
        var item by mutableStateOf("item-a")
        var source by mutableStateOf(sources.first())
        try {
            mainThread { activity.setContent {
                AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                    CompositionLocalProvider(LocalMarkdownParser provides parser) {
                        ReportedReasoningCard(owner, listOf(ReportedReasoning(item, source, 0L, 0L)),
                            allowLiveTiming = true, nanoTime = { 0L })
                    }
                }
            } }
            await { gates.getValue(sources[0]).entered.count == 0L }
            gates.getValue(sources[0]).release.countDown()
            awaitText("prefix")
            mainThread { item = "item-b"; source = sources[1] }
            await { gates.getValue(sources[1]).entered.count == 0L }
            ui.onNodeWithText("prefix", useUnmergedTree = true).assertDoesNotExist()
            ui.onAllNodesWithTag("markdown-pending", useUnmergedTree = true).assertCountEquals(0)
            gates.getValue(sources[1]).release.countDown()
            awaitText("prefix second")
            mainThread { owner = "owner-b"; source = sources[2] }
            await { gates.getValue(sources[2]).entered.count == 0L }
            ui.onNodeWithText("prefix second", useUnmergedTree = true).assertDoesNotExist()
            ui.onAllNodesWithTag("markdown-pending", useUnmergedTree = true).assertCountEquals(0)
            gates.getValue(sources[2]).release.countDown()
            awaitText("prefix second third")
        } finally {
            gates.values.forEach { it.release.countDown() }
            ui.mainClock.autoAdvance = true
        }
    }

    fun testSummaryAndElapsedTextRemainReadableInLightAndDarkThemes() {
        val source = "Readable **summary**"
        val message = Message(MessageId("contrast"), ConversationId("chat"), RunId("run"), Message.ASSISTANT_ROLE,
            emptyList(), RunStatus.COMPLETED, reportedReasoning = listOf(ReportedReasoning("r", source, 1_200L)))
        prepareRestoredMarkdown(listOf(message))
        var dark by mutableStateOf(false)
        var background = Color.Unspecified
        mainThread { activity.setContent {
            AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                applyInterfaceScale = false) {
                val parent = if (dark) Color.Black else Color.White
                val surface = conversationColors().surface.compositeOver(parent)
                SideEffect { background = surface }
                Column(Modifier.background(parent)) { ReportedReasoningCard("contrast", message.reportedReasoning) }
            }
        } }
        ui.onNodeWithContentDescription("展开模型已报告的思考 / 摘要").performClick()
        for (isDark in listOf(false, true)) {
            mainThread { dark = isDark }
            ui.waitForIdle()
            for (text in listOf("Readable summary", "1.2s")) {
                val layouts = mutableListOf<TextLayoutResult>()
                ui.onNodeWithText(text, useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
                val foreground = layouts.single().layoutInput.style.color.compositeOver(background).luminance()
                val backdrop = background.luminance()
                val ratio = (maxOf(foreground, backdrop) + .05f) / (minOf(foreground, backdrop) + .05f)
                assertTrue("$text contrast in dark=$isDark: $ratio", ratio >= 4.5f)
            }
        }
    }
}
