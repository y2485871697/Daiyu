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
    @Test fun sameApiModelIdKeepsDistinctFrozenProviderCatalogTickets() {
        val a = CatalogModelParameters.newModel(RemoteModel("shared", "A"))
        val b = CatalogModelParameters.newModel(RemoteModel("shared", "B"))
        val active = SavedModelCatalogSnapshot("account-a", 3, listOf(a))
        val otherModels = mutableListOf(b)
        val other = SavedModelCatalogSnapshot("account-b", 7, otherModels)
        val catalogs = mutableMapOf("slot-b" to other)
        val session = LocalCatalogChatModelPickerSession(active, "shared", providerId = "slot-a", providerCatalogs = catalogs)
        otherModels.clear(); catalogs.clear()
        assertEquals(active.selectionFor("shared"), session.selectionFor("slot-a", "shared"))
        assertEquals(SavedModelCatalogSnapshot("account-b", 7, listOf(b)).selectionFor("shared"),
            session.selectionFor("slot-b", "shared"))
        assertNotEquals(session.selectionFor("slot-a", "shared"), session.selectionFor("slot-b", "shared"))
        assertNull(session.selectionFor("deleted-slot", "shared"))
        assertNull(session.selectionFor("slot-b", "remote-only"))
    }

    @Test fun noActiveProviderCanStillResolveAnExplicitLocalProviderTicket() {
        val other = SavedModelCatalogSnapshot("account-b", 4,
            listOf(CatalogModelParameters.newModel(RemoteModel("b", "B"))))
        val session = LocalCatalogChatModelPickerSession(null, "", providerId = "", providerCatalogs = mapOf("slot-b" to other))
        assertTrue(session.state.models.isEmpty())
        assertNull(session.selectionFor("b"))
        assertEquals(other.selectionFor("b"), session.selectionFor("slot-b", "b"))
    }

}
