package app.daiyu.android.model.catalog

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CatalogModelSelectionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val codec = object : CatalogRecordCodec<String> {
        override fun encode(record: String) = record
        override fun decode(encoded: String) = encoded
    }
    private fun backend(file: File) = FileSavedModelCatalogPersistence(file, codec)
    private fun record(id: String) = SavedCatalogModel(id, "Local $id", "policy-$id")

    @Test fun offlineReadAndSelectionSurviveReopenWithoutImportOrNetwork() {
        val file = File(temporary.root, "catalog")
        val before = backend(file).register(listOf(record("a"), record("b")))
        val bytes = file.readBytes()
        val reopened = backend(file)
        assertEquals(before, reopened.current())
        assertArrayEquals(bytes, file.readBytes())
        val ticket = reopened.current()!!.selectionFor("b")!!
        assertEquals(record("b"), reopened.consumeSelection(ticket) { it })
        assertEquals(before.models, reopened.current()!!.models)
        assertTrue(reopened.current()!!.revision > before.revision)
        assertNull(before.selectionFor("not-imported"))
    }

    @Test fun recordTamperingIsRejectedEvenAtCurrentProviderRevision() {
        val store = backend(File(temporary.root, "catalog"))
        val before = store.register(listOf(record("a")))
        val ticket = before.selectionFor("a")!!
        val altered = listOf(
            ticket.copy(record = ticket.record.copy(settings = "different-policy")),
            ticket.copy(record = ticket.record.copy(displayName = "different-name")),
            ticket.copy(record = record("missing")),
            ticket.copy(providerIdentity = "another-provider"),
            ticket.copy(expectedRevision = before.revision + 1),
        )
        altered.forEach { invalid ->
            assertThrows(IllegalStateException::class.java) { store.consumeSelection(invalid) { error("must not run") } }
        }
        assertEquals(before, store.current())
    }

    @Test fun configurationChangeAppendAndRecordEditAllRejectEarlierTickets() {
        val store = backend(File(temporary.root, "catalog"))
        var snapshot = store.register(listOf(record("a")))
        fun rejectsOldTicket(change: () -> Unit) {
            val ticket = snapshot.selectionFor("a")!!
            change()
            assertThrows(IllegalStateException::class.java) { store.consumeSelection(ticket) { error("must not run") } }
            snapshot = store.current()!!
        }
        rejectsOldTicket { store.configurationChange {} }
        rejectsOldTicket {
            assertEquals(CatalogCommitResult.COMMITTED,
                store.compareAndAppend(snapshot.providerIdentity, snapshot.revision, listOf(record("b"))) { false })
        }
        rejectsOldTicket { store.updateRegisteredModel("a") { it!!.copy(settings = "new-policy") } }
    }

    @Test fun deletionRecreationAndAccountReplacementRejectOldSelections() {
        val store = backend(File(temporary.root, "catalog"))
        val first = store.register(listOf(record("a")))
        val ticket = first.selectionFor("a")!!
        assertTrue(store.deleteProvider(first.providerIdentity))
        assertNull(store.current())
        assertThrows(IllegalStateException::class.java) { store.consumeSelection(ticket) {} }
        val recreated = store.register(listOf(record("a")))
        assertNotEquals(first.providerIdentity, recreated.providerIdentity)
        assertThrows(IllegalStateException::class.java) { store.consumeSelection(ticket) {} }
        val recreatedTicket = recreated.selectionFor("a")!!
        store.replaceRegisteredProvider(listOf(record("a").copy(settings = "new-account-defaults")))
        assertThrows(IllegalStateException::class.java) { store.consumeSelection(recreatedTicket) {} }
        assertEquals("new-account-defaults", store.current()!!.models.single().settings)
    }

    @Test fun failedAndNoOpHostSelectionsStillFenceReplayedTickets() {
        val store = backend(File(temporary.root, "catalog"))
        val first = store.register(listOf(record("a")))
        val ticket = first.selectionFor("a")!!
        assertThrows(IllegalStateException::class.java) { store.consumeSelection(ticket) { error("host commit failed") } }
        assertEquals(first.models, store.current()!!.models)
        assertThrows(IllegalStateException::class.java) { store.consumeSelection(ticket) {} }
        val fresh = store.current()!!.selectionFor("a")!!
        store.consumeSelection(fresh) {}
        assertThrows(IllegalStateException::class.java) { store.consumeSelection(fresh) {} }
    }

    @Test fun updatingOneRecordNeverReencodesOtherRecords() {
        val encoded = mutableListOf<String>()
        val store = FileSavedModelCatalogPersistence(File(temporary.root, "catalog"), object : CatalogRecordCodec<String> {
            override fun encode(record: String): String { encoded += record; return record }
            override fun decode(encoded: String) = encoded
        })
        store.register(listOf(record("a"), record("b")))
        encoded.clear()
        store.updateRegisteredModel("a") { it!!.copy(settings = "edited-a") }
        assertEquals(listOf("edited-a"), encoded)
        assertEquals(listOf(record("a").copy(settings = "edited-a"), record("b")), store.current()!!.models)
    }

    @Test fun unreadableSelectedRecordAndFailedRecordUpdateNeverRunHostOrRewriteBytes() {
        val file = File(temporary.root, "catalog")
        val store = backend(file)
        val before = store.register(listOf(record("a")))
        val bytes = file.readBytes()
        val failing = FileSavedModelCatalogPersistence(file, object : CatalogRecordCodec<String> {
            override fun encode(record: String): String = error("cannot encode")
            override fun decode(encoded: String): String = error("cannot decode")
        })
        var called = false
        assertThrows(IllegalStateException::class.java) {
            failing.consumeSelection(before.selectionFor("a")!!) { called = true }
        }
        assertFalse(called)
        assertArrayEquals(bytes, file.readBytes())
        assertThrows(IllegalStateException::class.java) {
            failing.updateRegisteredModel("new") { record("new") }
        }
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(before, store.current())
    }

    @Test fun providerLeaseCoversValidationAndEntireHostMutation() {
        val file = File(temporary.root, "catalog")
        val store = backend(file)
        val otherInstance = backend(file)
        val ticket = store.register(listOf(record("a"))).selectionFor("a")!!
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val attempted = CountDownLatch(1)
        val mutated = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val selection = executor.submit {
                store.consumeSelection(ticket) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val change = executor.submit {
                attempted.countDown()
                otherInstance.configurationChange { mutated.countDown() }
            }
            assertTrue(attempted.await(5, TimeUnit.SECONDS))
            assertFalse(mutated.await(100, TimeUnit.MILLISECONDS))
            release.countDown()
            selection.get(5, TimeUnit.SECONDS)
            change.get(5, TimeUnit.SECONDS)
            assertEquals(0L, mutated.count)
        } finally { release.countDown(); executor.shutdownNow() }
    }
}
