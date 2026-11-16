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

    @Test fun streamingFollowWinsEvenInTheUpperHalf() {
        assertTrue(workflowDisclosureExpandsUp(true, true, 120f, viewport))
        assertTrue(workflowDisclosureExpandsUp(true, true, 480f, viewport))
    }

    @Test fun idleAtTheBottomUsesTheSafeViewportMidpoint() {
        assertFalse(workflowDisclosureExpandsUp(true, false, 299f, viewport))
        assertTrue(workflowDisclosureExpandsUp(true, false, 300f, viewport))
        assertTrue(workflowDisclosureExpandsUp(true, false, 301f, viewport))
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

    @Test fun detachedReaderUsesTheSafeViewportMidpoint() {
        assertFalse(workflowDisclosureExpandsUp(false, true, 299f, viewport))
        assertTrue(workflowDisclosureExpandsUp(false, true, 300f, viewport))
        assertTrue(workflowDisclosureExpandsUp(false, true, 301f, viewport))
    }

    @Test fun translatedViewportHasTheSameDirection() {
        val translated = Rect(20f, 1100f, 340f, 1500f)
        assertFalse(workflowDisclosureExpandsUp(false, true, 1299f, translated))
        assertTrue(workflowDisclosureExpandsUp(false, true, 1301f, translated))
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

}
