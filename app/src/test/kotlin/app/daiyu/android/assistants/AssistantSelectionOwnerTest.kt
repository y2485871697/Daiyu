package app.daiyu.android.assistants

import org.junit.Assert.*
import org.junit.Test

class AssistantSelectionOwnerTest {
    @Test fun stalePickerCallbacksCannotRepairAnotherEditorOrReopenedPicker() {
        val original=AssistantSelectionOwner("A",7,null,false)
        assertTrue(original.accepts(original,"A",7,true))
        assertFalse(original.accepts(original,"B",7,true))
        assertFalse(original.accepts(original,"A",8,true)) // A -> B -> A is another editor mount.
        assertFalse(original.accepts(original,"A",7,false)) // Dismissed callbacks are revoked.
        assertFalse(original.accepts(null,"A",7,true))
        val reopened=AssistantSelectionOwner("A",7,null,false)
        assertFalse(original.accepts(reopened,"A",7,true))
        assertTrue(reopened.accepts(reopened,"A",7,true))
        assertNull(original.expectedAssistantId) // NULL must not become the global selection.
    }

    @Test fun newChatGlobalPickerAlsoBelongsToExactMount() {
        val request=AssistantSelectionOwner(null,2,"default",true)
        assertTrue(request.accepts(request,null,2,true))
        assertFalse(request.accepts(request,"created",2,true))
        assertFalse(request.accepts(request,null,3,true))
    }
}
