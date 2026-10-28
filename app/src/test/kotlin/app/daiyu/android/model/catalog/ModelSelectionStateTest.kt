package app.daiyu.android.model.catalog

import org.junit.Assert.*
import org.junit.Test

class ModelSelectionStateTest {
    private val models = listOf(
        RemoteModel("alpha", "Shared name"),
        RemoteModel("beta", "Shared name"),
        RemoteModel("gamma", "Gamma label"),
    )
    private fun selection() = ModelSelectionState.capture("host-provider", 7, models)

    @Test fun startsEmptyAndCountsUniqueStableIdsNotNamesOrPositions() {
        val input = models.toMutableList().apply {
            add(RemoteModel(" alpha ", "ignored duplicate"))
            add(RemoteModel(" ", "invalid"))
        }
        val state = ModelSelectionState.capture("host-provider", 7, input)
        input.clear()
        assertEquals(3, state.totalCount)
        assertEquals(3, state.visibleCount)
        assertEquals(0, state.selectedCount)
        assertFalse(state.canConfirm)
        val selected = state.toggle("beta")
        assertEquals(setOf("beta"), selected.selectedIds)
        assertEquals("Shared name", selected.selectedModels.single().displayName)
        assertEquals(0, state.selectedCount)
        assertSame(state, state.toggle("not-an-id"))
        assertEquals("host-provider", selected.providerIdentity)
        assertEquals(7L, selected.expectedRevision)
    }

    @Test fun searchMatchesNameOrIdIgnoringCaseAndTrimsOnlyTheQuery() {
        val state = selection()
        assertEquals(listOf("alpha", "beta"), state.withQuery(" SHARED ").visibleModels.map { it.id })
        assertEquals(listOf("beta"), state.withQuery("BeT").visibleModels.map { it.id })
        assertEquals(listOf("gamma"), state.withQuery("LABEL").visibleModels.map { it.id })
        assertEquals(0, state.withQuery("missing").visibleCount)
        assertEquals(3, state.withQuery("  ").visibleCount)
    }

    @Test fun hiddenSelectionsSurviveSearchAndBulkOperationsOnlyAffectVisibleRows() {
        val alpha = selection().toggle("alpha").withQuery("label")
        assertEquals(1, alpha.selectedCount)
        assertEquals(listOf("gamma"), alpha.visibleModels.map { it.id })
        val allVisible = alpha.selectAllVisible()
        assertEquals(setOf("alpha", "gamma"), allVisible.selectedIds)
        assertEquals(setOf("alpha"), allVisible.invertVisible().selectedIds)
        val shared = allVisible.withQuery("shared").invertVisible()
        assertEquals(setOf("beta", "gamma"), shared.selectedIds)
        assertEquals(listOf("beta", "gamma"), shared.selectedModels.map { it.id })
        val noMatches = shared.withQuery("nothing")
        assertEquals(shared.selectedIds, noMatches.selectAllVisible().selectedIds)
        assertEquals(shared.selectedIds, noMatches.invertVisible().selectedIds)
    }

    @Test fun selectAllAndInvertAreDeterministicAndConfirmUsesCatalogOrder() {
        val all = selection().selectAllVisible()
        assertEquals(3, all.selectedCount)
        assertEquals(all.selectedIds, all.selectAllVisible().selectedIds)
        assertFalse(all.invertVisible().canConfirm)
        val selectedInReverse = selection().toggle("gamma").toggle("alpha")
        assertEquals(listOf("alpha", "gamma"), selectedInReverse.selectedModels.map { it.id })
        assertFalse(selectedInReverse.toggle("alpha").toggle("gamma").canConfirm)
    }

    @Test fun comparisonIgnoresCaseButWireIdKeepsFirstSpelling() {
        val state = ModelSelectionState.capture("provider", 0,
            listOf(RemoteModel("Model"), RemoteModel("model", "  ")))
        assertEquals(1, state.totalCount)
        assertEquals("Model", state.models.single().displayName)
        assertEquals(setOf("Model"), state.toggle("Model").selectedIds)
        assertEquals(1, state.withQuery("MODEL").visibleCount)
    }

    @Test fun emptyCatalogHasNoEnabledConfirmOrBulkSelection() {
        val empty = ModelSelectionState.capture("provider", 0, emptyList())
        assertEquals(0, empty.totalCount)
        assertFalse(empty.selectAllVisible().canConfirm)
        assertFalse(empty.invertVisible().canConfirm)
    }
}
