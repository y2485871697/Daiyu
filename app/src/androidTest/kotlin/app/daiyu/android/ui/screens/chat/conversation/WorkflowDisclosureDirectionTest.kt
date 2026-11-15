package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.ui.app.AgentAppTheme

class WorkflowDisclosureDirectionTest : WiredActivityTestCase() {
    private var density = 1f
    private val work = WorkProcessUi(emptyList(), "已完成")

    private fun mountList(following: Boolean, lower: Boolean = false, tool: Boolean = false) {
        mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            density = LocalDensity.current.density
            BoxWithConstraints(Modifier.size(320.dp, 420.dp)) {
                ChatConversationLayers(Modifier.size(maxWidth, maxHeight), messages = { inset ->
                    ChatMessageList(List(8) { ChatTurnUi.User("u$it", "history $it") } + ChatTurnUi.Assistant("tail"),
                        ChatScreenActions(), composerBottomInset = inset,
                        scrollRequest = if (following) ChatScrollRequest(1) else ChatScrollRequest(1, messageId = "tail", followBottom = false),
                        turnExtras = { turn -> if (turn.id == "tail") {
                            if (lower) Box(Modifier.height(140.dp))
                            if (tool) WorkStepRow(WorkStepUi("tool", "fixture", "", WorkStepState.OK), toolContent = {
                                Box(Modifier.fillMaxWidth().height(80.dp).testTag("direction-body"))
                            }) else WorkProcessCard(work, "direction-fixture", content = {
                                Box(Modifier.fillMaxWidth().height(80.dp).testTag("direction-body"))
                            })
                            if (following) Box(Modifier.height(180.dp))
                        } })
                }, composer = { Box(Modifier.fillMaxWidth().height(96.dp).testTag("direction-composer")) })
            }
        } } }
        ui.waitForIdle()
        ui.onNodeWithTag(if (tool) "tool-step-header:tool" else "work-process-header").assertIsDisplayed()
    }

    private fun bottom(): Float {
        val coordinates = ui.onNodeWithTag("work-process-surface").fetchSemanticsNode().layoutInfo.coordinates
        return coordinates.positionInRoot().y + coordinates.size.height
    }

    private fun top(): Float = ui.onNodeWithTag("work-process-surface")
        .fetchSemanticsNode().layoutInfo.coordinates.positionInRoot().y

    private fun assertDirection(up: Boolean, tag: String = "work-process-header") {
        ui.onNodeWithTag(tag).assert(SemanticsMatcher.expectValue(WorkflowDisclosureUp, up))
    }

    private fun touchHeader() { ui.onNodeWithTag("work-process-header").performTouchInput { click() } }

    fun testFollowingUpperHeaderStillExpandsUpAndNeverGrowsBehindComposer() {
        mountList(following = true)
        val header = ui.onNodeWithTag("work-process-header").fetchSemanticsNode().boundsInRoot
        val listTop = ui.onNodeWithTag("chat-messages").fetchSemanticsNode().boundsInRoot.top
        val safeBottom = ui.onNodeWithTag("direction-composer").fetchSemanticsNode().boundsInRoot.top - 16f * density
        assertTrue("Fixture must distinguish follow priority from the upper-half fallback", header.center.y < (listTop + safeBottom) / 2f)
        val anchor = bottom()
        ui.mainClock.autoAdvance = false
        try {
            touchHeader()
            repeat(60) {
                ui.mainClock.advanceTimeByFrame(); ui.waitForIdle()
                assertDirection(true)
                assertTrue("No disclosure frame may reach behind the composer", bottom() <= safeBottom + 1f)
                assertEquals("Upward disclosure holds its lower edge", anchor, bottom(), 2f)
            }
        } finally { ui.mainClock.autoAdvance = true }
        ui.onNodeWithTag("work-process-header").assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "已展开"))
        assertTrue("The detail must really finish expanding", ui.onNodeWithTag("direction-body").fetchSemanticsNode().boundsInRoot.height >= 79f * density)
        assertEquals(0, fixture.opens.get())
    }

    private fun assertHalf(lower: Boolean) {
        val center = ui.onNodeWithTag("work-process-header").fetchSemanticsNode().boundsInRoot.center.y
        val listTop = ui.onNodeWithTag("chat-messages").fetchSemanticsNode().boundsInRoot.top
        val safeBottom = ui.onNodeWithTag("direction-composer").fetchSemanticsNode().boundsInRoot.top - 16f * density
        assertEquals("Fixture header must be in the requested safe half", lower, center >= (listTop + safeBottom) / 2f)
    }

    fun testDetachedUpperHeaderExpandsDownWithoutMovingItsTop() {
        mountList(following = false)
        assertHalf(lower = false)
        val anchor = top()
        touchHeader(); ui.waitForIdle()
        assertDirection(false)
        assertEquals(anchor, top(), 2f)
        ui.onNodeWithTag("direction-body").assertExists()
    }

    fun testDetachedLowerHeaderExpandsUpAndCollapseUsesTheSameEdge() {
        mountList(following = false, lower = true)
        assertHalf(lower = true)
        val anchor = bottom()
        val originalTop = top()
        touchHeader(); ui.waitForIdle()
        assertDirection(true)
        assertEquals(anchor, bottom(), 2f)
        assertTrue("Content growth must actually move upward, not just change clipping", top() < originalTop - 40f * density)
        ui.mainClock.autoAdvance = false
        try {
            touchHeader()
            repeat(60) {
                ui.mainClock.advanceTimeByFrame(); ui.waitForIdle()
                assertDirection(true)
                assertEquals("Collapse must retain the same edge on every frame", anchor, bottom(), 2f)
            }
        } finally { ui.mainClock.autoAdvance = true }
        ui.onNodeWithTag("direction-body").assertDoesNotExist()
        assertEquals(originalTop, top(), 2f)
    }

    fun testDirectionStaysFrozenUntilFullyClosedAndNextCycleUsesTheNewState() {
        var following by mutableStateOf(true)
        mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            val viewport = remember { WorkflowDisclosureViewport({ following }, { Rect(0f, 0f, 10000f, 10000f) }, {}, { true }, { _, _ -> 0f }) }
            CompositionLocalProvider(LocalWorkflowDisclosureContext provides remember { WorkflowDisclosureContext(viewport, "stable") }) {
                Column { WorkProcessCard(work, "stable", content = { Box(Modifier.height(80.dp)) }) }
            }
        } } }
        ui.waitForIdle()
        ui.mainClock.autoAdvance = false
        try {
            ui.onNodeWithTag("work-process-header").performClick()
            ui.mainClock.advanceTimeBy(64); ui.waitForIdle()
            assertDirection(true)
            mainThread { following = false }
            ui.onNodeWithTag("work-process-header").performClick()
            ui.mainClock.advanceTimeBy(48); ui.waitForIdle()
            assertDirection(true)
            ui.onNodeWithTag("work-process-header").performClick()
            ui.mainClock.advanceTimeBy(400); ui.waitForIdle()
            assertDirection(true)
            ui.onNodeWithTag("work-process-header").performClick()
            ui.mainClock.advanceTimeBy(400); ui.waitForIdle()
            assertDirection(true)
        } finally { ui.mainClock.autoAdvance = true }
        ui.onNodeWithTag("work-process-header").performClick(); ui.waitForIdle()
        assertDirection(false)
    }

    fun testToolDetailsUseTheSameFollowingPriority() {
        mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            val viewport = remember { WorkflowDisclosureViewport({ true }, { Rect(0f, 0f, 10000f, 10000f) }, {}, { true }, { _, _ -> 0f }) }
            CompositionLocalProvider(LocalWorkflowDisclosureContext provides remember { WorkflowDisclosureContext(viewport, "tool") }) {
                WorkStepRow(WorkStepUi("tool", "fixture", "detail", WorkStepState.OK))
            }
        } } }
        ui.waitForIdle()
        ui.onNodeWithTag("tool-step-header:tool").performClick(); ui.waitForIdle()
        assertDirection(true, "tool-step-header:tool")
    }
    fun testRealToolHeaderTouchRetainsTheFollowingPriority() {
        mountList(following = true, tool = true)
        ui.onNodeWithTag("tool-step-header:tool").performTouchInput { click() }
        ui.waitForIdle()
        assertDirection(true, "tool-step-header:tool")
        ui.onNodeWithTag("direction-body").assertExists()
    }

    fun testReasoningSummaryUsesTheSameFollowingPriority() {
        kotlinx.coroutines.runBlocking { projectMarkdown(listOf("direction summary")) }
        mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            val viewport = remember { WorkflowDisclosureViewport({ true }, { Rect(0f, 0f, 10000f, 10000f) }, {}, { true }, { _, _ -> 0f }) }
            CompositionLocalProvider(LocalWorkflowDisclosureContext provides remember { WorkflowDisclosureContext(viewport, "reasoning") }) {
                ReportedReasoningCard("reasoning", listOf(app.daiyu.android.domain.ReportedReasoning("summary", "direction summary")))
            }
        } } }
        ui.waitForIdle()
        ui.onNodeWithTag("reported-reasoning-header:reasoning").performTouchInput { click() }; ui.waitForIdle()
        assertDirection(true, "reported-reasoning-header:reasoning")
        ui.onNodeWithTag("reported-reasoning-content:reasoning").assertExists()
        ui.onNodeWithTag("reported-reasoning-content:reasoning").performTouchInput { click() }; ui.waitForIdle()
        assertDirection(true, "reported-reasoning-header:reasoning")
        ui.onNodeWithTag("reported-reasoning-content:reasoning").assertDoesNotExist()
    }

}
