package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.data.model.AppearanceThemeMode
import app.daiyu.android.domain.ReportedReasoning
import app.daiyu.android.ui.app.AgentAppTheme
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

class WorkTimelineConnectorTest : WiredActivityTestCase() {
    fun testOrderedMixedStepsStayJoinedWhenThoughtAndToolExpandInBothThemes() = verify(legacy = false)
    fun testLegacySegmentsStayJoinedWhenThoughtAndToolExpandInBothThemes() = verify(legacy = true)

    private fun verify(legacy: Boolean) {
        val thought = ReportedReasoning("connector-thought", "connector thought")
        val lastThought = ReportedReasoning("connector-last", "last thought")
        val owner = WorkStepOwnerUi("connector-c", "connector-r", "connector-a")
        val first = WorkStepUi("connector-first", "first tool", "", WorkStepState.OK, owner = owner)
        val second = first.copy(id = "connector-second", name = "second tool")
        val content = listOf(MessageContentUi.Reasoning(thought), MessageContentUi.Tool(first),
            MessageContentUi.Tool(second), MessageContentUi.Reasoning(lastThought))
        runBlocking { projectMarkdown(listOf("connector thought", "last thought")) }
        for (dark in listOf(false, true)) {
            lateinit var density: Density
            var background = Color.Unspecified
            var line = Color.Unspecified
            mainThread { activity.setContent { key(legacy, dark) {
                AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT),
                    applyInterfaceScale = false) {
                    density = LocalDensity.current
                    val colors = conversationColors()
                    background = colors.surface.copy(alpha = 1f)
                    line = colors.line.compositeOver(background)
                    Box(Modifier.statusBarsPadding()) {
                        Box(Modifier.width(300.dp).background(background).testTag("connector-raster")) {
                            if (legacy) WorkProcessCard(WorkProcessUi(listOf(
                                WorkSegmentUi(reasoning = thought, steps = listOf(first, second)),
                                WorkSegmentUi(reasoning = lastThought)), "运行中", running = true), "connector",
                                toolContent = { Box(Modifier.height(64.dp)) })
                            else MessageContent(content, ChatScreenActions(), null, markdown = false,
                                toolContent = { Box(Modifier.height(64.dp)) },
                                reasoningBody = { reported, connector -> ReasoningStepRow("connector", reported, connector) })
                        }
                    }
                }
            } } }
            awaitUniqueTag("work-reasoning:connector-last")
            awaitVisualIdle()
            assertJoined(density, background, line)
            ui.onNodeWithTag("tool-step-header:connector-first").performClick()
            awaitVisualIdle()
            assertJoined(density, background, line)
            ui.onNodeWithTag("reported-reasoning-header:connector:reasoning:connector-thought").performClick()
            awaitVisualIdle()
            assertJoined(density, background, line)
            ui.onNodeWithTag("tool-step-header:connector-first").performClick()
            ui.onNodeWithTag("reported-reasoning-header:connector:reasoning:connector-thought").performClick()
            awaitVisualIdle()
            assertJoined(density, background, line)
        }
        assertEquals(0, fixture.opens.get())
    }

    private fun assertJoined(density: Density, background: Color, line: Color) {
        val raster = ui.onNodeWithTag("connector-raster").assertIsDisplayed()
        val bounds = raster.fetchSemanticsNode().boundsInRoot
        val tags = listOf("work-reasoning:connector-thought", "tool-step:connector-first",
            "tool-step:connector-second", "work-reasoning:connector-last")
        val rows = tags.map { ui.onNodeWithTag(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
        val pixels = raster.captureToImage().toPixelMap()
        assertEquals(bounds, raster.fetchSemanticsNode().boundsInRoot)
        for ((tag, before) in tags.zip(rows)) assertEquals(before, ui.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot)
        fun distance(a: Color, b: Color) = maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue))
        val contrast = distance(line, background)
        assertTrue("fixture line must differ from background", contrast > .01f)
        val x = rows.first().left - bounds.left + with(density) { 5.dp.toPx() }
        val xs = floor(x - 1).toInt()..ceil(x + 1).toInt()
        for ((before, after) in rows.zipWithNext()) {
            val from = ceil(before.top - bounds.top + with(density) { 27.dp.toPx() }).toInt()
            val to = floor(after.top - bounds.top + with(density) { 17.dp.toPx() }).toInt() - 1
            assertTrue("connector must span separate rows", to > from)
            for (y in from..to) assertTrue("disconnected connector at y=$y, rows=$rows",
                xs.any { distance(pixels[it, y], background) > contrast * .35f })
        }
        val first = rows.first()
        val last = rows.last()
        val blankRanges = listOf(
            ceil(first.top - bounds.top + with(density) { 1.dp.toPx() }).toInt() until
                floor(first.top - bounds.top + with(density) { 16.dp.toPx() }).toInt(),
            ceil(last.top - bounds.top + with(density) { 28.dp.toPx() }).toInt() until
                floor(last.bottom - bounds.top - 1).toInt(),
        )
        for (range in blankRanges) for (y in range) assertTrue("first/last step must not have a stray connector at y=$y",
            xs.all { distance(pixels[it, y], background) < contrast * .1f })
    }
}
