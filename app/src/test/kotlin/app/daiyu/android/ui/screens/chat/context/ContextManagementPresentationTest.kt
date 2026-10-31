package app.daiyu.android.ui.screens.chat.context

import app.daiyu.android.context.CatalogSummaryBindings
import app.daiyu.android.storage.ProviderCatalogRunTarget
import app.daiyu.android.storage.ProviderEditorSlot
import app.daiyu.android.model.ProviderProtocol
import org.junit.Assert.*
import org.junit.Test

class ContextManagementPresentationTest {
    private val draft = CompressionDialogDraft(true, "unsaved model A", "provider-A/model-A", ProviderProtocol.RESPONSES)
    private val binding = CatalogSummaryBindings.encode(ProviderCatalogRunTarget(
        ProviderEditorSlot("00000000-0000-0000-0000-000000000003", 7), "00000000-0000-0000-0000-000000000001", 11,
        "00000000-0000-0000-0000-000000000002", "api-A", ProviderProtocol.RESPONSES))

    @Test fun draftRetainsExactUnsavedModelRatherThanSavedFallback() {
        val presentation = ContextManagementPresentation()
        presentation.retain(draft, binding)
        assertEquals(draft, presentation.draft)
        assertSame(binding, presentation.binding)
        assertEquals("api-A", CatalogSummaryBindings.decode(presentation.binding!!)!!.apiModel)
        assertEquals(ProviderProtocol.RESPONSES, presentation.draft!!.protocol)
    }

    @Test fun customModelToggleKeepsExactBindingForReenable() {
        val presentation = ContextManagementPresentation()
        presentation.retain(draft, binding)
        presentation.retain(draft.copy(customModel = false), presentation.binding)
        assertFalse(presentation.draft!!.customModel)
        assertSame(binding, presentation.binding)
        presentation.retain(presentation.draft!!.copy(customModel = true), presentation.binding)
        assertEquals(draft, presentation.draft)
        assertSame(binding, presentation.binding)
    }

    @Test fun resetDropsDraftBindingPickerAndLocalNotice() {
        val presentation = ContextManagementPresentation()
        presentation.retain(draft, binding)
        presentation.choosingModel = true
        presentation.notice = "old notice"
        presentation.reset()
        assertNull(presentation.draft)
        assertNull(presentation.binding)
        assertFalse(presentation.choosingModel)
        assertNull(presentation.notice)
    }
}
