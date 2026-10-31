package app.daiyu.android.ui.components

import org.junit.Assert.*
import org.junit.Test

class BottomSheetActionTest {
    @Test fun selectionWaitsForExitAndOnlyTheFirstActionIsDelivered() {
        val motion = BottomSheetMotion()
        var selected = 0
        motion.requestAction { selected = 1 }
        motion.requestAction { selected = 2 }
        motion.requestDismiss(BottomSheetDismissSource.System)
        assertEquals(0, selected)
        assertEquals(BottomSheetDismissSource.Action, motion.dismissSource)
        motion.takeAction()?.invoke()
        motion.takeAction()?.invoke()
        assertEquals(1, selected)
    }

    @Test fun anExistingDismissCannotBeReplacedBySelection() {
        for (source in listOf(BottomSheetDismissSource.System, BottomSheetDismissSource.Outside)) {
            val motion = BottomSheetMotion()
            var selected = false
            motion.requestDismiss(source)
            motion.requestAction { selected = true }
            assertEquals(source, motion.dismissSource)
            assertNull(motion.takeAction())
            assertFalse(selected)
        }
    }

    @Test fun pauseCancelsSelectionAndAllowsAnIndependentNewRequest() {
        val motion = BottomSheetMotion()
        var old = 0
        var next = 0
        motion.requestAction { old++ }
        motion.cancelDismiss()
        assertNull(motion.dismissSource)
        assertNull(motion.takeAction())
        motion.requestAction { next++ }
        motion.takeAction()?.invoke()
        assertEquals(0, old)
        assertEquals(1, next)
    }

    @Test fun selectedCallbackIsTheOneCapturedAtTheClickNotAReplacement() {
        val motion = BottomSheetMotion()
        var revision = -1
        var action: () -> Unit = { revision = 1 }
        motion.requestAction(action)
        action = { revision = 2 }
        motion.takeAction()?.invoke()
        assertEquals(1, revision)
        motion.cancelDismiss()
        motion.requestAction(action)
        motion.takeAction()?.invoke()
        assertEquals(2, revision)
    }
}
