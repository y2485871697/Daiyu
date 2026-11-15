package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class WorkflowDisclosureMotionTest {
    private val viewport = Rect(20f, 100f, 340f, 500f)

    @Test fun followingAlwaysWinsEvenInTheUpperHalf() {
        assertTrue(workflowDisclosureExpandsUp(true, 120f, viewport))
        assertTrue(workflowDisclosureExpandsUp(true, 480f, viewport))
    }

    @Test fun detachedReaderUsesTheSafeViewportMidpoint() {
        assertFalse(workflowDisclosureExpandsUp(false, 299f, viewport))
        assertTrue(workflowDisclosureExpandsUp(false, 300f, viewport))
        assertTrue(workflowDisclosureExpandsUp(false, 301f, viewport))
    }

    @Test fun translatedViewportHasTheSameDirection() {
        val translated = Rect(20f, 1100f, 340f, 1500f)
        assertFalse(workflowDisclosureExpandsUp(false, 1299f, translated))
        assertTrue(workflowDisclosureExpandsUp(false, 1301f, translated))
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
        context.active = WorkflowDisclosureMotion(WorkflowDisclosureContext(context, "turn"))
        context.cancelAnchor()
        assertFalse(context.followingOnPress)
        assertNull(context.active)
    }
}
