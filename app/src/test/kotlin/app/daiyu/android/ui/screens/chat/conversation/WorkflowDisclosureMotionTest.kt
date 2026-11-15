package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

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

}
