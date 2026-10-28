package app.daiyu.android.model.catalog

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CatalogBatchDeletionTest {
    @get:Rule val temporary = TemporaryFolder()
    private var encodingForbidden = false
    private val codec = object : CatalogRecordCodec<String> {
        override fun encode(record: String): String { check(!encodingForbidden); return record }
        override fun decode(encoded: String) = encoded
    }
    private fun backend(file: File = File(temporary.root, "models.bin")) = FileSavedModelCatalogPersistence(file, codec)
    private fun initial(store: FileSavedModelCatalogPersistence<String>) = store.register(
        listOf("current", "first", "second", "keep").map { SavedCatalogModel(it, "Name $it", "encoded-$it") })
    private fun tickets(base: SavedModelCatalogSnapshot<String>, vararg ids: String) = ids.map { checkNotNull(base.selectionFor(it)) }

    @Test fun deletesManyWithOneRevisionAndNeverReencodesSurvivors() {
        val store = backend(); val base = initial(store)
        encodingForbidden = true
        assertEquals(CatalogLocalEditResult.APPLIED, store.deleteSelections(tickets(base, "first", "second"), { false }, { false }))
        val after = checkNotNull(backend().current())
        assertEquals(base.providerIdentity, after.providerIdentity)
        assertEquals(base.revision + 1, after.revision)
        assertEquals(base.models.filterNot { it.id in setOf("first", "second") }, after.models)
    }
    @Test fun rejectsWholeBatchContainingCurrentModel() {
        val store = backend(); val base = initial(store)
        assertEquals(CatalogLocalEditResult.CURRENT_MODEL,
            store.deleteSelections(tickets(base, "first", "current"), { "current" in it }, { false }))
        assertEquals(base, store.current())
    }
    @Test fun checksCurrentModelInsideLeaseAndRechecksCancellationBeforeRename() {
        val store = backend(); val base = initial(store)
        var checked = false
        assertEquals(CatalogLocalEditResult.CANCELLED,
            store.deleteSelections(tickets(base, "first", "second"), { checked = true; false }, { checked }))
        assertTrue(checked)
        assertEquals(base, store.current())
    }
    @Test fun cancellationBeforeValidationDoesNotWrite() {
        val store = backend(); val base = initial(store)
        assertEquals(CatalogLocalEditResult.CANCELLED,
            store.deleteSelections(tickets(base, "first", "second"), { error("must not check current") }, { true }))
        assertEquals(base, store.current())
    }
    @Test fun rejectsAnyChangedCompleteRecordWithoutPartialDeletion() {
        val store = backend(); val base = initial(store)
        val selections = tickets(base, "first", "second").map {
            if (it.record.id == "second") it.copy(record = it.record.copy(settings = "tampered")) else it
        }
        assertEquals(CatalogLocalEditResult.STALE, store.deleteSelections(selections, { false }, { false }))
        assertEquals(base, store.current())
    }
    @Test fun missingMixedProviderOrMixedRevisionTicketsLeaveEveryRecord() {
        val store = backend(); val base = initial(store)
        val first = checkNotNull(base.selectionFor("first")); val second = checkNotNull(base.selectionFor("second"))
        for (invalid in listOf(second.copy(providerIdentity = "another-provider"),
            second.copy(expectedRevision = base.revision + 1), second.copy(record = second.record.copy(id = "missing")))) {
            assertEquals(CatalogLocalEditResult.STALE, store.deleteSelections(listOf(first, invalid), { false }, { false }))
            assertEquals(base, store.current())
        }
    }
    @Test fun rejectsEmptyAndDuplicateIds() {
        val store = backend(); val base = initial(store); val first = checkNotNull(base.selectionFor("first"))
        assertEquals(CatalogLocalEditResult.FAILED, store.deleteSelections(emptyList(), { false }, { false }))
        assertEquals(CatalogLocalEditResult.FAILED, store.deleteSelections(listOf(first, first), { false }, { false }))
        assertEquals(base, store.current())
    }
    @Test fun appendMakesEveryOldTicketStaleAndOtherProviderStaysUnchanged() {
        val store = backend(); val base = initial(store)
        val other = backend(File(temporary.root, "other.bin")); val otherBase = initial(other)
        assertEquals(CatalogCommitResult.COMMITTED, store.compareAndAppend(base.providerIdentity, base.revision,
            listOf(SavedCatalogModel("new", "New", "new-settings"))) { false })
        val afterAppend = store.current()
        assertEquals(CatalogLocalEditResult.STALE, store.deleteSelections(tickets(base, "first", "second"), { false }, { false }))
        assertEquals(afterAppend, store.current()); assertEquals(otherBase, other.current())
    }
    @Test fun currentCheckAndWriteHoldLeaseAgainstAnotherInstance() {
        val file = File(temporary.root, "models.bin"); val store = backend(file); val base = initial(store)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val attempted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val deleting = executor.submit<CatalogLocalEditResult> {
                store.deleteSelections(tickets(base, "first", "second"), {
                    entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); false
                }, { false })
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val competing = executor.submit<SavedModelCatalogSnapshot<String>?> {
                attempted.countDown(); backend(file).current()
            }
            assertTrue(attempted.await(5, TimeUnit.SECONDS)); assertFalse(competing.isDone)
            release.countDown()
            assertEquals(CatalogLocalEditResult.APPLIED, deleting.get(5, TimeUnit.SECONDS))
            assertEquals(base.models.filterNot { it.id in setOf("first", "second") }, competing.get(5, TimeUnit.SECONDS)?.models)
        } finally { release.countDown(); executor.shutdownNow() }
    }
}
