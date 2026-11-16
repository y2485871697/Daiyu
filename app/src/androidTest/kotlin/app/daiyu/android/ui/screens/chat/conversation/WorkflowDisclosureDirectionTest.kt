package app.daiyu.android.ui.screens.chat.conversation

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.daiyu.android.WiredActivityTestCase
import app.daiyu.android.data.model.AppearanceSettings
import app.daiyu.android.domain.RunStatus
import app.daiyu.android.ui.app.AgentAppTheme

class WorkflowDisclosureDirectionTest : WiredActivityTestCase() {
    private var density = 1f
    private val work = WorkProcessUi(emptyList(), "已完成")

    private fun mountList(following: Boolean, lower: Boolean = false, tool: Boolean = false,
        status: RunStatus = RunStatus.RUNNING, streaming: Boolean = true) {
        mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            density = LocalDensity.current.density
            BoxWithConstraints(Modifier.size(320.dp, 420.dp)) {
                ChatConversationLayers(Modifier.size(maxWidth, maxHeight), messages = { inset ->
                    ChatMessageList(List(8) { ChatTurnUi.User("u$it", "history $it") } + ChatTurnUi.Assistant("tail", status = status, isStreaming = streaming),
                        ChatScreenActions(), composerBottomInset = inset,
                        scrollRequest = if (following) ChatScrollRequest(1) else ChatScrollRequest(1, messageId = "tail", followBottom = false),
                        turnExtras = { turn -> if (turn.id == "tail") {
                            if (lower) Box(Modifier.height(140.dp))
                            if (tool) WorkStepRow(WorkStepUi("tool", "fixture", "", WorkStepState.OK), toolContent = {
                                Box(Modifier.fillMaxWidth().height(80.dp).testTag("direction-body"))
                            }) else WorkProcessCard(work, "direction-fixture", content = {
                                Box(Modifier.fillMaxWidth().height(80.dp).testTag("direction-body"))
                            })
                            if (!lower) Box(Modifier.height(180.dp))
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

    private fun geometry(tag: String = "work-process-header"): String = ui.onNodeWithTag(tag)
        .fetchSemanticsNode().config[WorkflowDisclosureGeometry].invoke()

    private fun touchHeader() { ui.onNodeWithTag("work-process-header").performTouchInput { click() } }

    fun testFollowingUpperHeaderStillExpandsUpAndNeverGrowsBehindComposer() {
        mountList(following = true)
        val header = ui.onNodeWithTag("work-process-header").fetchSemanticsNode().boundsInRoot
        val listTop = ui.onNodeWithTag("chat-messages").fetchSemanticsNode().boundsInRoot.top
        val safeBottom = ui.onNodeWithTag("direction-composer").fetchSemanticsNode().boundsInRoot.top - ChatTailClearance.value * density
        assertTrue("Fixture must distinguish follow priority from the upper-half fallback", header.center.y < (listTop + safeBottom) / 2f)
        val anchor = bottom()
        ui.mainClock.autoAdvance = false
        try {
            touchHeader()
            repeat(60) {
                ui.mainClock.advanceTimeByFrame(); ui.waitForIdle()
                assertDirection(true)
                assertTrue("No disclosure frame may reach behind the composer", bottom() <= safeBottom + 1f)
                assertEquals("Upward disclosure holds its lower edge: ${geometry()}", anchor, bottom(), 2f)
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
        val safeBottom = ui.onNodeWithTag("direction-composer").fetchSemanticsNode().boundsInRoot.top - ChatTailClearance.value * density
        assertEquals("Fixture header must be in the requested safe half", lower, center >= (listTop + safeBottom) / 2f)
    }

    fun testCompletedReplyAtBottomUsesUpperHalfInsteadOfThePressFollowSnapshot() {
        mountList(following = true, status = RunStatus.COMPLETED, streaming = false)
        assertHalf(lower = false)
        val anchor = top()
        touchHeader(); ui.waitForIdle()
        assertDirection(false)
        assertEquals(anchor, top(), 2f)
        ui.onNodeWithTag("direction-body").assertExists()
    }

    fun testCompletedReplyAtBottomStillExpandsUpInTheLowerHalf() {
        mountList(following = true, lower = true, status = RunStatus.COMPLETED, streaming = false)
        assertHalf(lower = true)
        val anchor = bottom()
        touchHeader(); ui.waitForIdle()
        assertDirection(true)
        assertEquals(anchor, bottom(), 2f)
    }

    fun testStoppedReplyAtBottomUsesTheUpperHalfFallback() {
        mountList(following = true, status = RunStatus.STOPPED, streaming = false)
        assertHalf(lower = false)
        touchHeader(); ui.waitForIdle()
        assertDirection(false)
    }

    fun testRunningWithoutStreamingAtBottomUsesTheUpperHalfFallback() {
        mountList(following = true, streaming = false)
        assertHalf(lower = false)
        touchHeader(); ui.waitForIdle()
        assertDirection(false)
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

    fun testDetachedLowerHeaderExpandsUpAndWorkflowCollapseShrinksTowardTop() {
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
                val surface = ui.onNodeWithTag("work-process-surface").fetchSemanticsNode()
                assertTrue("Workflow collapse must reduce its detail height", surface.size.height > 0)
            }
        } finally { ui.mainClock.autoAdvance = true }
        ui.onNodeWithTag("direction-body").assertDoesNotExist()
        assertTrue("Workflow remains visible after its fixed upward collapse", bottom() > top())
    }

    fun testDirectionStaysFrozenUntilFullyClosedAndNextCycleUsesTheNewState() {
        var following by mutableStateOf(true)
        mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            val viewport = remember { WorkflowDisclosureViewport({ following }, { Rect(0f, 0f, 10000f, 10000f) }, {}, { true }, { _, _ -> 0f }) }
            CompositionLocalProvider(LocalWorkflowDisclosureContext provides remember { WorkflowDisclosureContext(viewport, "stable") { true } }) {
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

    fun testOutputCompletionKeepsTheCurrentEdgeUntilFullyClosed() {
        var outputActive by mutableStateOf(true)
        mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            val viewport = remember { WorkflowDisclosureViewport({ true }, { Rect(0f, 0f, 10000f, 10000f) }, {}, { true }, { _, _ -> 0f }) }
            CompositionLocalProvider(LocalWorkflowDisclosureContext provides remember {
                WorkflowDisclosureContext(viewport, "completing") { outputActive }
            }) {
                WorkProcessCard(work, "completing", content = { Box(Modifier.height(80.dp).testTag("completion-body")) })
            }
        } } }
        ui.waitForIdle()
        ui.mainClock.autoAdvance = false
        try {
            touchHeader()
            ui.mainClock.advanceTimeBy(64); ui.waitForIdle()
            assertDirection(true)
            mainThread { outputActive = false }
            touchHeader()
            ui.mainClock.advanceTimeBy(48); ui.waitForIdle()
            assertDirection(true)
            touchHeader()
            ui.mainClock.advanceTimeBy(400); ui.waitForIdle()
            assertDirection(true)
            touchHeader()
            ui.mainClock.advanceTimeBy(400); ui.waitForIdle()
            assertDirection(true)
        } finally { ui.mainClock.autoAdvance = true }
        ui.onNodeWithTag("completion-body").assertDoesNotExist()
        touchHeader(); ui.waitForIdle()
        assertDirection(false)
    }

    fun testToolDetailsUseTheSameFollowingPriority() {
        mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            val viewport = remember { WorkflowDisclosureViewport({ true }, { Rect(0f, 0f, 10000f, 10000f) }, {}, { true }, { _, _ -> 0f }) }
            CompositionLocalProvider(LocalWorkflowDisclosureContext provides remember { WorkflowDisclosureContext(viewport, "tool") { true } }) {
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
            CompositionLocalProvider(LocalWorkflowDisclosureContext provides remember { WorkflowDisclosureContext(viewport, "reasoning") { true } }) {
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



    fun testWorkflowManualAndAutomaticCollapseBothKeepTheHeaderAndLiftTheBottom() {
        for (closing in listOf("manual", "complete", "stop")) {
            var running by mutableStateOf(true)
            var stopped by mutableStateOf(false)
            mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                androidx.compose.runtime.key(closing) {
                    val viewport = remember { WorkflowDisclosureViewport({ true }, { Rect(0f, 0f, 10000f, 10000f) }, {}, { false }, { _, _ -> 0f }) }
                    CompositionLocalProvider(LocalWorkflowDisclosureContext provides remember {
                        WorkflowDisclosureContext(viewport, "collapse-fixture:$closing") { true }
                    }) {
                        Column { WorkProcessCard(WorkProcessUi(emptyList(), "fixture", running = running), "collapse-fixture:$closing", stopped = stopped,
                            content = { Box(Modifier.height(120.dp).testTag("collapse-body")) }) }
                    }
                }
            } } }
            ui.waitForIdle()
            ui.onNodeWithTag("work-process-header").assert(SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "已展开"))
            ui.onNodeWithTag("collapse-body").assertHeightIsEqualTo(120.dp)
            val fixedTop = top()
            val originalBottom = bottom()
            var sawIntermediate = false
            ui.mainClock.autoAdvance = false
            try {
                when (closing) {
                    "complete" -> ui.runOnUiThread { running = false }
                    "stop" -> ui.runOnUiThread { stopped = true }
                    else -> touchHeader()
                }
                var previousBottom = originalBottom
                repeat(40) {
                    ui.mainClock.advanceTimeByFrame(); ui.waitForIdle()
                    assertEquals("Closing workflow never pulls its header downward", fixedTop, top(), 1f)
                    val currentBottom = bottom()
                    assertTrue("Workflow bottom moves upward while closing: $closing", currentBottom <= previousBottom + 1f)
                    if (currentBottom < originalBottom - 1f &&
                        ui.onAllNodesWithTag("collapse-body").fetchSemanticsNodes().isNotEmpty()) sawIntermediate = true
                    previousBottom = currentBottom
                }
            } finally { ui.mainClock.autoAdvance = true }
            assertTrue("Every closing path must retain real intermediate animation frames: $closing", sawIntermediate)
            ui.onNodeWithTag("collapse-body").assertDoesNotExist()
            assertTrue("Fixed upward collapse must remove real height", bottom() < originalBottom - 60f)
        }
    }

    fun testWorkflowCollapsePolicyIsAlwaysTopButToolPolicyKeepsItsCycleEdge() {
        val viewport = WorkflowDisclosureViewport({ true }, { Rect(0f, 0f, 10000f, 10000f) }, {}, { true }, { _, _ -> 0f })
        val context = WorkflowDisclosureContext(viewport, "collapse") { true }
        val workflow = WorkflowDisclosureMotion(context, collapseUp = true)
        val tool = WorkflowDisclosureMotion(context)
        assertEquals(androidx.compose.ui.Alignment.Bottom, workflow.alignment)
        assertEquals(androidx.compose.ui.Alignment.Top, workflow.collapseAlignment)
        assertEquals(androidx.compose.ui.Alignment.Bottom, tool.collapseAlignment)
    }

    private var supplementalFollowing by mutableStateOf(false)
    private var supplementalOutputActive by mutableStateOf(false)
    private val supplementalKinds = listOf("question", "category", "child-live", "child-receipt")

    private fun supplementalHeader(kind: String) = when (kind) {
        "question" -> "question-toggle"
        "category" -> "tool-entries"
        "child-live" -> "tool-child-summary:child"
        else -> "tool-child-receipt-header:0"
    }

    private fun mountSupplemental(kind: String, following: Boolean, outputActive: Boolean, lower: Boolean) {
        supplementalFollowing = following
        supplementalOutputActive = outputActive
        mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            androidx.compose.runtime.key(kind) {
            var safe by remember { mutableStateOf<Rect?>(null) }
            val viewport = remember { WorkflowDisclosureViewport({ supplementalFollowing }, { safe }, {}, { false }, { _, _ -> 0f }) }
            Box(Modifier.size(320.dp, 420.dp).onGloballyPositioned { safe = it.boundsInWindow() }) {
                CompositionLocalProvider(LocalWorkflowDisclosureContext provides remember {
                    WorkflowDisclosureContext(viewport, "supplemental") { supplementalOutputActive }
                }) {
                    Column {
                        if (lower) Box(Modifier.height(260.dp))
                        when (kind) {
                            "question" -> {
                                val owner = app.daiyu.android.domain.question.QuestionIdentity("direction", "run", "owner", "call")
                                val question = app.daiyu.android.domain.question.UserQuestion("direction", "Choose a direction", listOf(
                                    app.daiyu.android.domain.question.QuestionOption("keep", "Keep"),
                                    app.daiyu.android.domain.question.QuestionOption("change", "Change")))
                                QuestionCard(owner, app.daiyu.android.domain.question.QuestionRecord(owner.storageKey, question,
                                    answer = app.daiyu.android.domain.question.QuestionAnswer.Selected("keep")), false,
                                    submit = { _, _, _ -> fail("Opening saved question must not submit") })
                            }
                            "category" -> ToolCategoryContent("memory", ToolCategoryDetailsUi("fixture", notice = "",
                                rich = ToolRichDetailsUi(entries = listOf(ToolRichEntryUi("entry",
                                    listOf(ToolFieldUi("value", "content")))))))
                            "child-live" -> ChildTaskDisclosure(SubAgentStatusRow(
                                app.daiyu.android.domain.delegation.ChildRunOwner("chat", "run", "group", 1, "child", "message"),
                                "worker", "已完成"), details = { Box(Modifier.height(60.dp)) })
                            else -> ChildTaskReceiptContent(ToolCategoryDetailsUi("fixture", notice = "",
                                rich = ToolRichDetailsUi(entries = listOf(ToolRichEntryUi("worker",
                                    listOf(ToolFieldUi("状态", "已完成")), listOf(ToolDisclosureUi("result", "content")))))))
                        }
                    }
                }
            }
            }
        } } }
        ui.waitForIdle()
    }

    private fun assertSupplementalDirection(following: Boolean, outputActive: Boolean, lower: Boolean, expected: Boolean) {
        for (kind in supplementalKinds) {
            mountSupplemental(kind, following, outputActive, lower)
            val header = supplementalHeader(kind)
            ui.onNodeWithTag(header).performTouchInput { click() }; ui.waitForIdle()
            assertDirection(expected, header)
            ui.onNodeWithTag(header).performTouchInput { click() }; ui.waitForIdle()
            assertDirection(expected, header)
        }
    }

    fun testAllSupplementalDisclosuresUseActiveStreamingFollowPriority() =
        assertSupplementalDirection(following = true, outputActive = true, lower = false, expected = true)

    fun testAllSupplementalDisclosuresAtIdleBottomUseTheUpperHalf() =
        assertSupplementalDirection(following = true, outputActive = false, lower = false, expected = false)

    fun testAllSupplementalDisclosuresDetachedInUpperHalfExpandDown() =
        assertSupplementalDirection(following = false, outputActive = true, lower = false, expected = false)

    fun testAllSupplementalDisclosuresDetachedInLowerHalfExpandUp() =
        assertSupplementalDirection(following = false, outputActive = true, lower = true, expected = true)

    fun testSupplementalCycleKeepsItsEdgeWhenStreamingEndsAndRechoosesAfterClose() {
        for (kind in supplementalKinds) {
            mountSupplemental(kind, following = true, outputActive = true, lower = false)
            val header = supplementalHeader(kind)
            if (kind == "question") {
                ui.onNodeWithTag(header).assert(SemanticsMatcher.expectValue(
                    androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "已折叠"))
            }
            ui.onNodeWithTag(header).performTouchInput { click() }; ui.waitForIdle()
            assertDirection(true, header)
            mainThread { supplementalFollowing = false; supplementalOutputActive = false }
            ui.onNodeWithTag(header).performTouchInput { click() }; ui.waitForIdle()
            assertDirection(true, header)
            ui.onNodeWithTag(header).performTouchInput { click() }; ui.waitForIdle()
            assertDirection(false, header)
        }
    }

    private fun assertLowerReadingBoundaryIsStationary(status: RunStatus, streaming: Boolean) {
        val step = WorkStepUi("reading-tool", "fixture", "", WorkStepState.OK)
        mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            density = LocalDensity.current.density
            BoxWithConstraints(Modifier.size(320.dp, 420.dp)) {
                ChatConversationLayers(Modifier.size(maxWidth, maxHeight), messages = { inset ->
                    ChatMessageList(List(8) { ChatTurnUi.User("u$it", "history $it") } +
                        ChatTurnUi.Assistant("tail", status = status, isStreaming = streaming),
                        ChatScreenActions(), composerBottomInset = inset,
                        scrollRequest = ChatScrollRequest(1, messageId = "tail", followBottom = false),
                        turnExtras = { turn -> if (turn.id == "tail") {
                            WorkProcessCard(work, "reading-fixture", content = {
                                Column {
                                    Box(Modifier.height(88.dp))
                                    WorkStepRow(step, toolContent = {
                                        Box(Modifier.fillMaxWidth().height(80.dp).testTag("reading-detail"))
                                    })
                                    Box(Modifier.fillMaxWidth().height(12.dp).background(Color.Magenta)
                                        .testTag("reading-anchor"))
                                    Box(Modifier.height(240.dp))
                                }
                            })
                            Box(Modifier.fillMaxWidth().height(12.dp).testTag("reading-footer"))
                            Box(Modifier.height(240.dp))
                        } })
                }, composer = { Box(Modifier.fillMaxWidth().height(96.dp).testTag("reading-composer")) })
            }
        } } }
        ui.waitForIdle()
        val parentHeader = ui.onNodeWithTag("work-process-header").fetchSemanticsNode().boundsInRoot
        val parentListTop = ui.onNodeWithTag("chat-messages").fetchSemanticsNode().boundsInRoot.top
        val parentSafeBottom = ui.onNodeWithTag("reading-composer").fetchSemanticsNode().boundsInRoot.top -
            ChatTailClearance.value * density
        assertTrue("The enclosing workflow must initially open downwards",
            parentHeader.center.y < (parentListTop + parentSafeBottom) / 2f)
        touchHeader(); ui.waitForIdle()
        assertDirection(false)
        val headerTag = "tool-step-header:reading-tool"
        val header = ui.onNodeWithTag(headerTag).fetchSemanticsNode().boundsInRoot
        val listTop = ui.onNodeWithTag("chat-messages").fetchSemanticsNode().boundsInRoot.top
        val safeBottom = ui.onNodeWithTag("reading-composer").fetchSemanticsNode().boundsInRoot.top -
            ChatTailClearance.value * density
        assertTrue("Fixture tool must be in the lower safe half", header.center.y >= (listTop + safeBottom) / 2f)
        assertTrue("Fixture tool must be visible above the composer", header.bottom < safeBottom)
        assertTrue("Fixture must retain an existing workflow bottom beyond the safe viewport", bottom() > safeBottom + 80f * density)
        fun position(tag: String): Float = ui.onNodeWithTag(tag).fetchSemanticsNode()
            .layoutInfo.coordinates.positionInRoot().y
        val readingAnchor = position("reading-anchor")
        val footerAnchor = position("reading-footer")
        assertTrue("The existing reading marker must be visible", readingAnchor >= listTop && readingAnchor < safeBottom)
        var phase = "ready"
        fun assertReadingAnchor() {
            assertEquals("Upward opening and downward collapse must not move the following text ($phase): ${geometry(headerTag)} parent=${geometry()}",
                readingAnchor, position("reading-anchor"), 1f)
            assertEquals("The same operation must not move the remainder after the workflow ($phase): ${geometry(headerTag)} parent=${geometry()}",
                footerAnchor, position("reading-footer"), 1f)
        }
        ui.mainClock.autoAdvance = false
        try {
            for ((opening, frames) in listOf(true to 60, false to 60, true to 5, false to 60)) {
                val initialHeight = ui.onNodeWithTag("work-process-surface")
                    .fetchSemanticsNode().layoutInfo.coordinates.size.height
                var changedHeight = false
                if (!opening) {
                    ui.onNodeWithTag(headerTag).performTouchInput { down(center) }
                    repeat(3) {
                        phase = "pressed-before-reversal frame=$it"
                        ui.mainClock.advanceTimeByFrame(); ui.waitForIdle()
                        assertReadingAnchor()
                    }
                    ui.onNodeWithTag(headerTag).performTouchInput { up() }
                } else ui.onNodeWithTag(headerTag).performTouchInput { click() }
                repeat(frames) {
                    phase = "opening=$opening frames=$frames frame=$it"
                    ui.mainClock.advanceTimeByFrame(); ui.waitForIdle()
                    assertDirection(true, headerTag)
                    assertReadingAnchor()
                    val height = ui.onNodeWithTag("work-process-surface")
                        .fetchSemanticsNode().layoutInfo.coordinates.size.height
                    if (height != initialHeight) changedHeight = true
                }
                assertTrue("The disclosure must really animate its height", changedHeight)
                if (opening && frames == 60) {
                    assertTrue("The detail must really finish expanding", ui.onNodeWithTag("reading-detail")
                        .fetchSemanticsNode().boundsInRoot.height >= 79f * density)
                } else if (!opening) ui.onNodeWithTag("reading-detail").assertDoesNotExist()
            }
            repeat(12) {
                ui.mainClock.advanceTimeByFrame(); ui.waitForIdle()
                assertReadingAnchor()
            }
            assertTrue("A settled disclosure must release the outer follower: ${geometry()}",
                " owner=null" in geometry())
        } finally { ui.mainClock.autoAdvance = true }
    }

    fun testUpwardToolAndDownwardCollapseKeepTheLowerReadingBoundaryStill() =
        assertLowerReadingBoundaryIsStationary(RunStatus.COMPLETED, streaming = false)

    fun testBoundedUpwardToolAndDownwardCollapseKeepTheLowerReadingBoundaryStill() =
        assertLowerReadingBoundaryIsStationary(RunStatus.RUNNING, streaming = true)

    fun testNestedWorkflowAndToolMoveTheirReadingWindowInsteadOfClippingTheIdentity() {
        val step = WorkStepUi("nested-tool", "fixture", "", WorkStepState.OK)
        mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
            density = LocalDensity.current.density
            BoxWithConstraints(Modifier.size(320.dp, 420.dp).testTag("nested-host")) {
                ChatConversationLayers(Modifier.size(maxWidth, maxHeight), messages = { inset ->
                    ChatMessageList(List(8) { ChatTurnUi.User("u$it", "history $it") } +
                        ChatTurnUi.Assistant("tail", workProcess = WorkProcessUi(listOf(WorkSegmentUi(steps = listOf(step))), "已完成"),
                            status = RunStatus.RUNNING, isStreaming = true), ChatScreenActions(),
                        composerBottomInset = inset, scrollRequest = ChatScrollRequest(1),
                        toolContent = { Box(Modifier.fillMaxWidth().height(80.dp).background(Color.Magenta).testTag("nested-body")) })
                }, composer = { Box(Modifier.fillMaxWidth().height(96.dp).testTag("nested-composer")) })
            }
        } } }
        ui.waitForIdle()
        fun nestedFrame(): Pair<Float, Float> {
            val node = ui.onNodeWithTag("reply-identity:tail").fetchSemanticsNode()
            val coordinates = node.layoutInfo.coordinates
            val fullTop = coordinates.positionInRoot().y
            val window = ui.onNodeWithTag("chat-stream-viewport:tail").fetchSemanticsNode()
                .layoutInfo.coordinates.positionInRoot().y
            val identity = node.boundsInRoot
            assertTrue("Short nested disclosure must keep the avatar above its local draw-clip edge", identity.top >= window - 1f)
            assertTrue("Unclipped identity origin must stay below the local window", fullTop >= window - 1f)
            assertEquals("Identity top must never be partially clipped", node.positionInRoot.y, identity.top, 1f)
            assertEquals("Identity retains its complete tagged height in every frame", node.size.height.toFloat(), identity.height, 1f)
            val safeBottom = ui.onNodeWithTag("nested-composer").fetchSemanticsNode().boundsInRoot.top - ChatTailClearance.value * density
            assertTrue("Nested disclosure must remain above the input: bottom=${bottom()} safe=$safeBottom ${geometry()}", bottom() <= safeBottom + 1f)
            val surface = ui.onNodeWithTag("work-process-surface").fetchSemanticsNode().layoutInfo.coordinates
            return fullTop to surface.size.height.toFloat()
        }
        fun animateNested(header: String, opening: Boolean, frames: Int = 40) {
            ui.mainClock.autoAdvance = false
            try {
                var previous = nestedFrame()
                ui.onNodeWithTag(header).performTouchInput { click() }
                repeat(frames) {
                    ui.mainClock.advanceTimeByFrame(); ui.waitForIdle()
                    assertDirection(true, header)
                    val current = nestedFrame()
                    val identityDelta = current.first - previous.first
                    val heightDelta = current.second - previous.second
                    val compensation = identityDelta + if (opening || header != "work-process-header") heightDelta else 0f
                    assertTrue("Nested anchor cannot jump in either direction: $compensation",
                        kotlin.math.abs(compensation) <= 14f * density + 1f)
                    previous = current
                }
            } finally { ui.mainClock.autoAdvance = true }
        }
        val originalIdentity = nestedFrame().first
        for (header in listOf("work-process-header", "tool-step-header:nested-tool")) {
            animateNested(header, opening = true, frames = 5)
            animateNested(header, opening = false, frames = 3)
            animateNested(header, opening = true)
        }
        val identity = ui.onNodeWithTag("reply-identity:tail").fetchSemanticsNode().boundsInRoot
        assertTrue("The whole row must move up, not only its contents inside a fixed clip", identity.top < originalIdentity - 40f * density)
        ui.onNodeWithTag("work-process-header").assertIsDisplayed()
        ui.onNodeWithTag("tool-step-header:nested-tool").assertIsDisplayed()
        val host = ui.onNodeWithTag("nested-host")
        val hostBounds = host.fetchSemanticsNode().boundsInRoot
        val body = ui.onNodeWithTag("nested-body").fetchSemanticsNode().boundsInRoot
        val pixels = host.captureToImage().toPixelMap()
        val pixel = pixels[(body.center.x - hostBounds.left).toInt(), (body.center.y - hostBounds.top).toInt()]
        assertTrue("The nested details are actually drawn through all ancestors", pixel.red > .9f && pixel.blue > .9f && pixel.green < .1f)
        animateNested("tool-step-header:nested-tool", opening = false)
        animateNested("work-process-header", opening = false)
        assertDirection(true)
        assertEquals(originalIdentity, ui.onNodeWithTag("reply-identity:tail").fetchSemanticsNode().boundsInRoot.top, 2f)
    }

    fun testLongWorkflowManualAndAutomaticCollapseHasNoHiddenHeightPlateau() {
        for (closing in listOf("manual", "complete", "stop")) {
            var running by mutableStateOf(true)
            var stopped by mutableStateOf(false)
            var safeBottom = 0f
            mainThread { activity.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = false) {
                density = LocalDensity.current.density
                Box(Modifier.size(320.dp, 420.dp).clipToBounds().onGloballyPositioned {
                    safeBottom = it.boundsInWindow().top + 280f * density
                }.verticalScroll(rememberScrollState())) {
                    val viewport = remember {
                        WorkflowDisclosureViewport({ true }, { Rect(0f, 0f, 10000f, safeBottom) }, {}, { false }, { _, _ -> 0f })
                    }
                    CompositionLocalProvider(LocalWorkflowDisclosureContext provides remember {
                        WorkflowDisclosureContext(viewport, "long-collapse:$closing") { running }
                    }) {
                        WorkProcessCard(WorkProcessUi(emptyList(), "13步长流程", running = running), "long-collapse:$closing", stopped = stopped,
                            content = { Column(Modifier.testTag("long-collapse-body")) { repeat(13) { Box(Modifier.fillMaxWidth().height(48.dp)) } } })
                    }
                }
            } } }
            ui.waitForIdle()
            val fixedTop = top()
            val header = ui.onNodeWithTag("work-process-header").fetchSemanticsNode()
            assertEquals("Header bounds must include its padding", fixedTop, header.positionInRoot.y, 1f)
            val headerBottom = header.positionInWindow.y + header.size.height
            val fullDetail = bottom() - fixedTop - header.size.height
            val start = safeBottom - headerBottom
            assertTrue("Fixture must contain more than twice its visible height", fullDetail > start * 2f)
            ui.onNodeWithTag("work-process-header").assertIsDisplayed()
            fun visibleDetail(): Float {
                val surface = ui.onNodeWithTag("work-process-surface").fetchSemanticsNode()
                return (minOf(surface.positionInWindow.y + surface.size.height, safeBottom) - headerBottom).coerceAtLeast(0f)
            }
            val samples = mutableListOf(start)
            ui.mainClock.autoAdvance = false
            try {
                when (closing) {
                    "complete" -> ui.runOnUiThread { running = false }
                    "stop" -> ui.runOnUiThread { stopped = true }
                    else -> touchHeader()
                }
                repeat(40) {
                    ui.mainClock.advanceTimeByFrame(); ui.waitForIdle()
                    val current = visibleDetail()
                    assertEquals("Long card keeps its top during $closing", fixedTop, top(), 1f)
                    assertTrue("Closing extent cannot grow: $samples -> $current", current <= samples.last() + 1f)
                    samples += current
                }
                assertTrue("Visible closing starts within three logical frames: $samples", samples[3] < start - 1f)
                samples.zipWithNext().drop(2).forEach { (before, after) ->
                    if (before > start * .2f && before < start * .95f) {
                        assertTrue("No visible middle plateau: $samples", after < before)
                    }
                }
                assertTrue("Retain intermediate visible frames: $samples", samples.take(5).drop(1).any { it > 1f && it < start - 1f })
                assertEquals("Closed detail during $closing: $samples ${geometry()}", 0f, samples.last(), 1f)
                ui.onNodeWithTag("long-collapse-body").assertDoesNotExist()
                val settledBottom = bottom()
                repeat(12) {
                    ui.mainClock.advanceTimeByFrame(); ui.waitForIdle()
                    assertEquals(fixedTop, top(), 1f)
                    assertEquals(settledBottom, bottom(), 1f)
                }
            } finally { ui.mainClock.autoAdvance = true }
        }
    }

}
