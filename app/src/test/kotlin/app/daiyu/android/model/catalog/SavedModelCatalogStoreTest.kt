package app.daiyu.android.model.catalog

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class SavedModelCatalogStoreTest {
    private data class LocalSettings(
        val enabled: Boolean = true,
        val contextWindow: Int = 8192,
        val customBody: String = "",
        val capabilities: Set<String> = emptySet(),
    )
    private val customized = SavedCatalogModel("existing", "Local name",
        LocalSettings(false, 12345, "local override", setOf("vision", "reasoning")))
    private val remote = listOf(RemoteModel("existing", "Remote renamed"), RemoteModel("add", "New"), RemoteModel("skip"))
    private fun selection() = ModelSelectionState.capture("provider", 4, remote)

    /** Test transaction adapter, not a claimed production persistence implementation. */
    private class MemoryPersistence(initial: SavedModelCatalogSnapshot<LocalSettings>) : SavedModelCatalogPersistence<LocalSettings> {
        var snapshot = initial
        var reads = 0
        var attempts = 0
        var writes = 0
        var outcome = CatalogCommitResult.COMMITTED
        var beforeCommit: () -> Unit = {}
        var failRead = false
        var failCommit = false
        override fun read(providerIdentity: String): SavedModelCatalogSnapshot<LocalSettings>? {
            reads++
            if (failRead) error("sensitive read error")
            return snapshot.takeIf { it.providerIdentity == providerIdentity }
        }
        override fun compareAndAppend(providerIdentity: String, expectedRevision: Long,
            additions: List<SavedCatalogModel<LocalSettings>>, isCancelled: () -> Boolean): CatalogCommitResult {
            attempts++
            beforeCommit()
            if (isCancelled()) return CatalogCommitResult.CANCELLED
            if (snapshot.providerIdentity != providerIdentity || snapshot.revision != expectedRevision) return CatalogCommitResult.STALE
            if (failCommit) error("sensitive commit error")
            if (outcome != CatalogCommitResult.COMMITTED) return outcome
            if (additions.isNotEmpty()) {
                snapshot = snapshot.copy(revision = snapshot.revision + 1, models = snapshot.models + additions)
                writes++
            }
            return CatalogCommitResult.COMMITTED
        }
    }
    private fun persistence() = MemoryPersistence(SavedModelCatalogSnapshot("provider", 4, listOf(customized)))
    private fun store(persistence: MemoryPersistence, created: MutableList<String> = mutableListOf()) =
        SavedModelCatalogStore(persistence) {
            created += it.id
            SavedCatalogModel(it.id, it.displayName, LocalSettings())
        }

    @Test fun onlySelectedMissingIdsAreAppendedAndExistingConfigurationIsNotEvenRecreated() {
        val db = persistence()
        val created = mutableListOf<String>()
        val result = store(db, created).appendSelected(selection().toggle("existing").toggle("add"))
        assertEquals(CatalogImportResult.Applied(1), result)
        assertEquals(listOf("add"), created)
        assertEquals(listOf("existing", "add"), db.snapshot.models.map { it.id })
        assertSame(customized, db.snapshot.models.first())
        assertEquals(LocalSettings(), db.snapshot.models.last().settings)
        assertEquals(5L, db.snapshot.revision)
        assertEquals(1, db.writes)
    }

    @Test fun selectedAllExistingIsNoOpAndRepeatedStaleImportCannotDuplicate() {
        val db = persistence()
        val store = store(db)
        val initial = db.snapshot
        assertEquals(CatalogImportResult.Applied(0), store.appendSelected(selection().toggle("existing")))
        assertSame(initial, db.snapshot)
        assertEquals(0, db.writes)
        val add = selection().toggle("add")
        assertEquals(CatalogImportResult.Applied(1), store.appendSelected(add))
        assertEquals(CatalogImportResult.Stale, store.appendSelected(add))
        assertEquals(1, db.writes)
        assertEquals(listOf("existing", "add"), db.snapshot.models.map { it.id })
    }

    @Test fun emptySelectionAndCancellationBeforeReadNeverCallPersistence() {
        val db = persistence()
        val store = store(db)
        assertEquals(CatalogImportResult.EmptySelection, store.appendSelected(selection()))
        assertEquals(CatalogImportResult.Cancelled, store.appendSelected(selection().toggle("add")) { true })
        assertEquals(0, db.reads)
        assertEquals(0, db.attempts)
        assertEquals(0, db.writes)
    }

    @Test fun providerOrRevisionMismatchNeverCreatesOrWrites() {
        for (state in listOf(
            ModelSelectionState.capture("other-provider", 4, remote),
            ModelSelectionState.capture("provider", 3, remote),
        )) {
            val db = persistence()
            val created = mutableListOf<String>()
            assertEquals(CatalogImportResult.Stale, store(db, created).appendSelected(state.toggle("add")))
            assertTrue(created.isEmpty())
            assertEquals(0, db.attempts)
            assertEquals(0, db.writes)
        }
    }

    @Test fun atomicComparePreventsProviderAndRevisionRacesIncludingAllExistingSelection() {
        for (identityChanges in listOf(false, true)) {
            for (id in listOf("add", "existing")) {
                val db = persistence()
                db.beforeCommit = { db.snapshot = if (identityChanges) db.snapshot.copy(providerIdentity = "replacement")
                    else db.snapshot.copy(revision = 5) }
                assertEquals(CatalogImportResult.Stale, store(db).appendSelected(selection().toggle(id)))
                assertEquals(listOf(customized), db.snapshot.models)
                assertEquals(0, db.writes)
            }
        }
    }

    @Test fun readFailuresAndAtomicWriteFailuresLeaveCatalogUntouched() {
        for (failure in listOf("read", "throw", "result")) {
            val db = persistence()
            val initial = db.snapshot
            when (failure) {
                "read" -> db.failRead = true
                "throw" -> db.failCommit = true
                else -> db.outcome = CatalogCommitResult.FAILED
            }
            assertEquals(CatalogImportResult.Failed, store(db).appendSelected(selection().toggle("add")))
            assertSame(initial, db.snapshot)
            assertEquals(0, db.writes)
        }
    }

    @Test fun failingOrInvalidNewModelFactoryCannotPartiallyAppend() {
        for (wrongId in listOf(false, true)) {
            val db = persistence()
            val store = SavedModelCatalogStore(db) { model ->
                if (model.id == "skip") {
                    if (!wrongId) error("factory failed after creating first record")
                    SavedCatalogModel("not-selected", "wrong", LocalSettings())
                } else SavedCatalogModel(model.id, model.displayName, LocalSettings())
            }
            assertEquals(CatalogImportResult.Failed, store.appendSelected(selection().toggle("add").toggle("skip")))
            assertEquals(listOf(customized), db.snapshot.models)
            assertEquals(0, db.attempts)
            assertEquals(0, db.writes)
        }
    }

    @Test fun cancellationDuringPreparationOrAtCommitNeverWrites() {
        val state = selection().toggle("add")
        val preparing = persistence()
        var cancelled = false
        val coordinator = SavedModelCatalogStore(preparing) {
            cancelled = true
            SavedCatalogModel(it.id, it.displayName, LocalSettings())
        }
        assertEquals(CatalogImportResult.Cancelled, coordinator.appendSelected(state) { cancelled })
        assertEquals(0, preparing.attempts)
        assertEquals(0, preparing.writes)
        val committing = persistence()
        cancelled = false
        committing.beforeCommit = { cancelled = true }
        assertEquals(CatalogImportResult.Cancelled, store(committing).appendSelected(state) { cancelled })
        assertEquals(0, committing.writes)
        assertEquals(listOf(customized), committing.snapshot.models)
    }

    @Test fun thrownCancellationFromFactoryIsNotReportedAsSuccessOrFailure() {
        val db = persistence()
        val store = SavedModelCatalogStore<LocalSettings>(db) { throw CancellationException("cancelled") }
        assertEquals(CatalogImportResult.Cancelled, store.appendSelected(selection().toggle("add")))
        assertEquals(0, db.writes)
        assertEquals(0, db.attempts)
    }
}
