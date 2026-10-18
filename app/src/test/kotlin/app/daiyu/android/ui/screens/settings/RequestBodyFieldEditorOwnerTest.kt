package app.daiyu.android.ui.screens.settings

import org.junit.Assert.*
import org.junit.Test

class RequestBodyFieldEditorOwnerTest {
    @Test fun equalNewDraftRevokesOldOwnerEvenIfTheOriginalReturns() {
        val original = ModelSettingsDraftUi(customBody = "{\"private\":true}")
        val owner = RequestBodyFieldEditorOwner(original)
        assertTrue(owner.current(original, true))
        assertFalse(owner.current(original.copy(), true))
        assertFalse(owner.current(original, true))
        assertTrue(RequestBodyFieldEditorOwner(original).current(original, true))
        assertFalse(owner.toString().contains("private"))
    }
    @Test fun disableAndDisposalCannotReviveAnEarlierEditor() {
        val draft = ModelSettingsDraftUi()
        val disabled = RequestBodyFieldEditorOwner(draft)
        assertFalse(disabled.current(draft, false)); assertFalse(disabled.current(draft, true))
        val disposed = RequestBodyFieldEditorOwner(draft)
        disposed.revoke(); assertFalse(disposed.current(draft, true))
    }
}
