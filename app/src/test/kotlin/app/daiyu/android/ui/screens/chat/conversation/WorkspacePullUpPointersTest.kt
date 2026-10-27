package app.daiyu.android.ui.screens.chat.conversation

import org.junit.Assert.*
import org.junit.Test

class WorkspacePullUpPointersTest {
    @Test fun singlePointerSequenceAndNextDownRemainAllowed() {
        val pointers = WorkspacePullUpPointers()
        pointers.update(0, 1)
        pointers.update(1, 1)
        pointers.update(1, 1)
        assertFalse(pointers.rejected)
        pointers.update(0, 1)
        assertFalse(pointers.rejected)
    }

    @Test fun secondPointerRejectsUntilAfterFinalRelease() {
        val pointers = WorkspacePullUpPointers()
        pointers.update(0, 1)
        pointers.update(1, 2)
        assertTrue(pointers.rejected)
        pointers.update(2, 2)
        pointers.update(1, 1)
        assertTrue(pointers.rejected)
        pointers.update(0, 1)
        assertFalse(pointers.rejected)
    }

    @Test fun simultaneousDownIsRejectedAndLaterSinglePointerCannotReviveIt() {
        val pointers = WorkspacePullUpPointers()
        pointers.update(0, 2)
        assertTrue(pointers.rejected)
        pointers.update(2, 1)
        assertTrue(pointers.rejected)
    }
}
