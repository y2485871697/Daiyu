package app.daiyu.android.model.catalog

import app.daiyu.android.ui.screens.chat.conversation.LocalCatalogChatModelPickerSession
import org.junit.Assert.*
import org.junit.Test

class LocalCatalogChatModelPickerSessionTest {
    @Test fun importedNamesAndExactSessionTicketsAreReusedWithoutRemoteList() {
        val a = CatalogModelParameters.newModel(RemoteModel("a", "My local A"))
        val b = CatalogModelParameters.newModel(RemoteModel("b", "My local B"))
        val snapshot = SavedModelCatalogSnapshot("provider", 42, listOf(a, b))
        val session = LocalCatalogChatModelPickerSession(snapshot, "a")
        assertEquals("a", session.state.current)
        assertEquals(listOf("a", "b"), session.state.models.map { it.id })
        assertEquals(listOf("My local A", "My local B"), session.state.models.map { it.displayName })
        assertEquals(snapshot.selectionFor("b"), session.selectionFor("b"))
        assertNull(session.selectionFor("remote-only"))
        val next = snapshot.copy(revision = 43)
        assertNotEquals(next.selectionFor("b"), session.selectionFor("b"))
    }

    @Test fun sessionDetachesListAndNeverInventsCurrentModelRecord() {
        val records = mutableListOf(CatalogModelParameters.newModel(RemoteModel("b", "B")))
        val snapshot = SavedModelCatalogSnapshot("provider", 1, records)
        val session = LocalCatalogChatModelPickerSession(snapshot, "not-local")
        records.clear()
        assertEquals(listOf("b"), session.state.models.map { it.id })
        assertNotNull(session.selectionFor("b"))
        assertNull(session.selectionFor("not-local"))
    }

    @Test fun emptyLocalCatalogOffersSettingsInsteadOfRemoteFallback() {
        val session = LocalCatalogChatModelPickerSession(
            SavedModelCatalogSnapshot("provider", 1, emptyList()), "old-active-id")
        assertTrue(session.state.models.isEmpty())
        assertNotNull(session.state.message)
        assertNull(session.selectionFor("old-active-id"))
    }
}
