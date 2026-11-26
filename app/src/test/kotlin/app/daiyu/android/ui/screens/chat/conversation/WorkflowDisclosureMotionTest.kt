package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test
import androidx.compose.runtime.BroadcastFrameClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class WorkflowDisclosureMotionTest {
    private val viewport = Rect(20f, 100f, 340f, 500f)

    @Test fun upperAndLowerHeadersUseTheSafeViewportMidpoint() {
        assertFalse(workflowDisclosureExpandsUp(120f, viewport))
        assertTrue(workflowDisclosureExpandsUp(480f, viewport))
    }

    @Test fun idleAtTheBottomUsesTheSafeViewportMidpoint() {
        assertFalse(workflowDisclosureExpandsUp(299f, viewport))
        assertTrue(workflowDisclosureExpandsUp(300f, viewport))
        assertTrue(workflowDisclosureExpandsUp(301f, viewport))
    }

    @Test fun inactiveOutputDoesNotResumeFollowEvenWhenTheListRemainsAtBottom() {
        var resumed = false
        val viewportState = WorkflowDisclosureViewport({ true }, { viewport }, { resumed = true }, { true }, { _, _ -> 0f })
        viewportState.pointerDown(true)
        val motion = WorkflowDisclosureMotion(WorkflowDisclosureContext(viewportState, "finished") { false })
        assertFalse(motion.upwards)
        motion.toggle(opening = true)
        assertFalse(resumed)
    }

    @Test fun downwardOpeningCancelsFollowWithoutRequiringAPress() {
        var following = false
        var resumed = false
        val viewportState = WorkflowDisclosureViewport({ following }, { viewport }, { resumed = true }, { true }, { _, _ -> 0f },
            detachFollow = { following = false })
        val motion = WorkflowDisclosureMotion(WorkflowDisclosureContext(viewportState, "active") { true })
        following = true
        motion.toggle(opening = true)
        assertFalse(following)
        assertFalse(resumed)
    }

    @Test fun detachedReaderUsesTheSafeViewportMidpoint() {
        assertFalse(workflowDisclosureExpandsUp(299f, viewport))
        assertTrue(workflowDisclosureExpandsUp(300f, viewport))
        assertTrue(workflowDisclosureExpandsUp(301f, viewport))
    }

    @Test fun translatedViewportHasTheSameDirection() {
        val translated = Rect(20f, 1100f, 340f, 1500f)
        assertFalse(workflowDisclosureExpandsUp(1299f, translated))
        assertTrue(workflowDisclosureExpandsUp(1301f, translated))
    }

    @Test fun pressRemembersFollowBeforeTheListCancelsIt() {
        var following = true
        val context = WorkflowDisclosureViewport({ following }, { viewport }, { following = true }, { true }, { _, _ -> 0f })
        context.pointerDown(following)
        following = false
        assertTrue(context.followingOnPress)
        assertEquals(1L, context.gesture)
        context.pointerDown(following)
        assertFalse(context.followingOnPress)
        assertEquals(2L, context.gesture)
    }

    @Test fun draggingDiscardsThePressAndAnimationOwner() {
        val context = WorkflowDisclosureViewport({ true }, { viewport }, {}, { true }, { _, _ -> 0f })
        context.pointerDown(true)
        context.active = WorkflowDisclosureMotion(WorkflowDisclosureContext(context, "turn") { true })
        context.cancelAnchor()
        assertFalse(context.followingOnPress)
        assertNull(context.active)
    }
    @Test fun pressKeepsAnAnimatingAnchorUntilTheClickCanClaimIt() {
        val viewportState = WorkflowDisclosureViewport({ false }, { viewport }, {}, { true }, { _, _ -> 0f })
        val owner = WorkflowDisclosureMotion(WorkflowDisclosureContext(viewportState, "turn") { false })
        viewportState.active = owner
        viewportState.pointerDown(false)
        assertSame(owner, viewportState.active)
        viewportState.claimGesture()
        viewportState.pointerUp()
        assertSame(owner, viewportState.active)
        viewportState.pointerDown(false)
        assertSame(owner, viewportState.active)
        viewportState.pointerUp()
        assertNull(viewportState.active)
    }

    @Test fun draggingStillCancelsAnAnchorRetainedByThePress() {
        val viewportState = WorkflowDisclosureViewport({ true }, { viewport }, {}, { true }, { _, _ -> 0f })
        viewportState.active = WorkflowDisclosureMotion(WorkflowDisclosureContext(viewportState, "turn") { true })
        viewportState.pointerDown(true)
        viewportState.cancelAnchor()
        viewportState.claimGesture()
        viewportState.pointerUp()
        assertNull(viewportState.active)
        assertFalse(viewportState.followingOnPress)
    }

    @Test fun reversedWorkflowCollapseNeverGrowsAndReopeningRetainsTheNewExtent() {
        val extent = WorkflowCollapseExtent()
        assertEquals(44, extent.measure(44, expanded = true))
        assertEquals(44, extent.measure(56, expanded = false))
        assertEquals(40, extent.measure(40, expanded = false))
        assertEquals(40, extent.measure(48, expanded = false))
        assertEquals(52, extent.measure(52, expanded = true))
        assertEquals(52, extent.measure(60, expanded = false))
        assertEquals(0, extent.measure(0, expanded = false))
        assertEquals(24, extent.measure(24, expanded = true))
    }

    @Test fun workflowCollapseUsesThePlacedExtentRatherThanAnAheadOfFrameMeasure() {
        val extent = WorkflowCollapseExtent()
        assertEquals(44, extent.measure(44, expanded = true))
        assertEquals(56, extent.measure(56, expanded = true))
        assertEquals(44, extent.measure(60, expanded = false, closingLimit = 44))
        assertEquals(40, extent.measure(40, expanded = false, closingLimit = 44))
        assertEquals(48, extent.measure(48, expanded = true))
        assertEquals(48, extent.measure(52, expanded = false, closingLimit = 48))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun longCardClosesFromVisiblePixelsWithoutWaitingForHiddenHeight() = runTest {
        val clock = BroadcastFrameClock()
        val extent = WorkflowCardExtent(initiallyExpanded = true)
        extent.fullHeight = 1200
        assertEquals(1200, extent.measure(true, null))
        backgroundScope.launch(clock) { extent.animate(true) }
        runCurrent()
        clock.sendFrame(1_000_000_000L); runCurrent()
        clock.sendFrame(2_000_000_000L); runCurrent()
        extent.measure(true, null); extent.placed()
        assertTrue(extent.idle(true))

        // Deliberately start the effect before the closing measure.
        backgroundScope.launch(clock) { extent.animate(false, closingLimit = 200) }
        runCurrent()
        clock.sendFrame(2_016_000_000L); runCurrent()
        var previous = extent.measure(false, 200)
        assertEquals(200, previous)
        repeat(40) { frame ->
            clock.sendFrame(2_032_000_000L + frame * 16_000_000L); runCurrent()
            val current = extent.measure(false, 200)
            assertTrue("Closing cannot grow: $previous -> $current", current <= previous)
            if (previous in 40..190) assertTrue("No middle plateau: $previous -> $current", current < previous)
            if (frame == 0) assertTrue("First elapsed frame must shrink visible pixels", current < 200)
            previous = current
        }
        clock.sendFrame(4_000_000_000L); runCurrent()
        assertEquals(0, extent.measure(false, 200))
        extent.placed()
        assertTrue(extent.idle(false))
        assertFalse(extent.keepsContent(false))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun reversingBeforeTheFirstElapsedClosingFrameKeepsTheVisiblePixelOrigin() = runTest {
        val clock = BroadcastFrameClock()
        val extent = WorkflowCardExtent(initiallyExpanded = true)
        extent.fullHeight = 1200
        extent.measure(true, null)
        backgroundScope.launch(clock) { extent.animate(true) }
        runCurrent()
        clock.sendFrame(1_000_000_000L); runCurrent()
        clock.sendFrame(2_000_000_000L); runCurrent()
        extent.measure(false, 200)
        val closing = backgroundScope.launch(clock) { extent.animate(false, closingLimit = 200) }
        runCurrent()
        clock.sendFrame(2_016_000_000L); runCurrent()
        assertEquals(200, extent.measure(false, 200))
        closing.cancel(); closing.join()

        assertEquals("Reopening must not restore the hidden 1200px", 200, extent.measure(true, null))
        val opening = backgroundScope.launch(clock) { extent.animate(true) }
        runCurrent()
        clock.sendFrame(2_032_000_000L); runCurrent()
        assertEquals(200, extent.measure(true, null))
        clock.sendFrame(2_048_000_000L); runCurrent()
        assertTrue(extent.measure(true, null) in 201..1199)
        opening.cancel(); opening.join()

        extent.measure(false, 200)
        backgroundScope.launch(clock) { extent.animate(false, closingLimit = 200) }
        runCurrent()
        clock.sendFrame(2_064_000_000L); runCurrent()
        clock.sendFrame(2_080_000_000L); runCurrent()
        assertTrue("Reversed opening velocity cannot delay closing", extent.measure(false, 200) < 200)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun upwardOpeningAndClosingReversalsKeepContentPlacementContinuous() = runTest {
        val clock = BroadcastFrameClock()
        val extent = WorkflowCardExtent(initiallyExpanded = false)
        extent.fullHeight = 1000
        extent.measure(true, null)
        val opening = backgroundScope.launch(clock) { extent.animate(true, upwards = true) }
        runCurrent()
        clock.sendFrame(1_000_000_000L); runCurrent()
        clock.sendFrame(1_032_000_000L); runCurrent()
        val before = extent.measure(true, null)
        val offset = extent.contentOffset
        assertTrue(before in 1..999)
        assertTrue(offset < 0)
        opening.cancel(); opening.join()
        assertEquals(before, extent.measure(false, 200))
        assertEquals("Closing cannot switch Bottom to Top in one placement", offset, extent.contentOffset)
        val closing = backgroundScope.launch(clock) { extent.animate(false, 200, upwards = true) }
        runCurrent()
        extent.measure(false, 200)
        assertEquals(offset, extent.contentOffset)
        clock.sendFrame(1_048_000_000L); runCurrent()
        clock.sendFrame(1_064_000_000L); runCurrent()
        val closingHeight = extent.measure(false, 200)
        val closingOffset = extent.contentOffset
        closing.cancel(); closing.join()
        assertEquals(closingHeight, extent.measure(true, null))
        assertEquals(closingOffset, extent.contentOffset)
    }


    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun expandedCardFollowsNestedMeasurementsWithoutASecondAnimation() = runTest {
        val clock = BroadcastFrameClock()
        val extent = WorkflowCardExtent(initiallyExpanded = true)
        extent.fullHeight = 480
        extent.measure(true, null)
        backgroundScope.launch(clock) { extent.animate(true) }
        runCurrent()
        clock.sendFrame(1_000_000_000L); runCurrent()
        clock.sendFrame(2_000_000_000L); runCurrent()
        extent.measure(true, null); extent.placed()
        assertTrue(extent.idle(true))

        for (height in listOf(483, 600, 436)) {
            extent.fullHeight = height
            assertEquals("A settled parent must place the child's current height", height, extent.measure(true, null))
            extent.placed()
            assertEquals(0, extent.contentOffset)
            assertTrue(extent.idle(true))
            val update = backgroundScope.launch(clock) { extent.animate(true) }
            runCurrent()
            assertTrue("Nested height changes must not start a parent spring", update.isCompleted)
            assertTrue(extent.idle(true))
        }
        extent.fullHeight = 620
        assertEquals(620, extent.measure(true, null))
        extent.placed()
        assertEquals("Closing before the effect uses the last placed pixels", 580, extent.measure(false, 580))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun growingOpenCardPlacesEachNewHeightWithoutRestartingTheSpring() = runTest {
        val clock = BroadcastFrameClock()
        val extent = WorkflowCardExtent(initiallyExpanded = true)
        extent.fullHeight = 300
        assertEquals(300, extent.measure(true, null))
        val first = backgroundScope.launch(clock) { extent.animate(true) }
        runCurrent()
        assertTrue("An initially open card has nothing to disclose", first.isCompleted)
        for (height in listOf(326, 352, 378)) {
            extent.fullHeight = height
            val update = backgroundScope.launch(clock) { extent.animate(true) }
            runCurrent()
            assertTrue("Growth must not wait for a frame", update.isCompleted)
            assertEquals("The card must cover the newest line at once", height, extent.measure(true, null))
            assertEquals(0, extent.contentOffset)
        }
    }

    @Test fun upperAnchorYieldsToReceivedSourcesAndAlreadyPendingTyping() {
        var received: String? = null
        var pending = false
        val viewportState = WorkflowDisclosureViewport({ true }, { viewport }, {}, { true }, { _, _ -> 0f },
            output = { received }, outputPending = { pending })
        val motion = WorkflowDisclosureMotion(WorkflowDisclosureContext(viewportState, "turn") { true })
        assertFalse(motion.outputChanged())
        received = "new"
        assertTrue("A new source must be observed before its first visible frame", motion.outputChanged())
        received = null
        pending = true
        assertTrue("Existing backlog must not need another network delta", motion.outputChanged())
        pending = false
        assertFalse(motion.outputChanged())
    }

    @Test fun downwardDisclosureDoesNotFollowOverflowFromLaterTurnContent() {
        val clearance = workflowDisclosureTopClearance(4f, 136f, 305f)
        assertEquals(0f, maxOf(0f, clearance), 0f)
        assertEquals("A negative anchor delta can restore the original top", -4f, maxOf(-4f, clearance), 0f)
    }

    @Test fun topAnchorWithClearanceMovesOnlyItsOwnOverflowWhenTheComposerRises() {
        assertEquals(4f, workflowDisclosureTopClearance(184f, 309f, 305f), 0f)
        assertEquals(2f, workflowDisclosureTopClearance(2f, 309f, 305f), 0f)
        assertEquals(0f, workflowDisclosureTopClearance(180f, 305f, 305f), 0f)
    }

    @Test fun topAnchorWithClearanceCanReturnWithoutCrossingTheSafeBottom() {
        val clearance = workflowDisclosureTopClearance(180f, 302f, 305f)
        assertEquals(-3f, maxOf(-10f, clearance), 0f)
        assertEquals(-10f, maxOf(-10f, workflowDisclosureTopClearance(-12f, 280f, 305f)), 0f)
    }


    @Test fun toolOutputFingerprintObservesMixedResultsAndLegacyProjection() {
        val step = WorkStepUi("tool", "fixture", "same", WorkStepState.OK, renderKey = "fixture:tool:0")
        val mixed = ChatTurnUi.Assistant("turn", content = listOf(MessageContentUi.Tool(step, result = "ab")))
        assertEquals(mixed.workflowDisclosureToolOutput(), mixed.copy(content = listOf(
            MessageContentUi.Tool(step.copy(), result = "ab"))).workflowDisclosureToolOutput())
        assertNotEquals("Equal-length new results must not be missed", mixed.workflowDisclosureToolOutput(),
            mixed.copy(content = listOf(MessageContentUi.Tool(step, result = "cd"))).workflowDisclosureToolOutput())
        assertNotEquals(mixed.copy(content = listOf(MessageContentUi.Tool(step, result = null))).workflowDisclosureToolOutput(),
            mixed.copy(content = listOf(MessageContentUi.Tool(step, result = ""))).workflowDisclosureToolOutput())
        val work = WorkProcessUi(listOf(WorkSegmentUi(steps = listOf(step))), "进行中", running = true)
        val legacy = ChatTurnUi.Assistant("turn", workProcess = work)
        val projected = step.copy(categoryDetails = ToolCategoryDetailsUi(heading = "fixture", notice = "",
            rich = ToolRichDetailsUi(prose = ToolDisclosureUi("结果", "new tool text"))))
        assertNotEquals("Legacy results need not populate Tool.result", legacy.workflowDisclosureToolOutput(),
            legacy.copy(workProcess = work.copy(segments = listOf(WorkSegmentUi(steps = listOf(projected)))))
                .workflowDisclosureToolOutput())
    }

    @Test fun toolOutputFingerprintIgnoresControlOnlyUpdates() {
        val step = WorkStepUi("tool", "fixture", "same", WorkStepState.PENDING, activity = ToolActivityUi.RUNNING)
        val work = WorkProcessUi(listOf(WorkSegmentUi(steps = listOf(step))), "执行中", running = true)
        val before = ChatTurnUi.Assistant("turn", workProcess = work, content = listOf(MessageContentUi.Tool(step)))
        val paused = step.copy(activity = ToolActivityUi.PAUSED)
        val after = before.copy(workProcess = work.copy(segments = listOf(WorkSegmentUi(steps = listOf(paused))),
            summary = "已暂停", running = false, controlActivity = ToolActivityUi.PAUSED),
            content = listOf(MessageContentUi.Tool(paused)))
        assertEquals(before.workflowDisclosureToolOutput(), after.workflowDisclosureToolOutput())
    }

}
