package app.daiyu.android.model.catalog

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FileSavedModelCatalogPersistenceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val codec = object : CatalogRecordCodec<String> {
        override fun encode(record: String) = record
        override fun decode(encoded: String) = encoded
    }
    private fun backend(file: File) = FileSavedModelCatalogPersistence(file, codec)
    private fun record(id: String) = SavedCatalogModel(id, "Local $id", "parameters-$id")

    @Test fun identityRevisionAndExactOldRecordsSurviveReopen() {
        val file = File(temporary.root, "primary.catalog")
        val store = backend(file)
        val initial = store.register(listOf(record("old")))
        assertEquals(initial.providerIdentity, UUID.fromString(initial.providerIdentity).toString())
        assertEquals(0L, initial.revision)
        assertEquals(CatalogCommitResult.COMMITTED, store.compareAndAppend(initial.providerIdentity, 0, listOf(record("new"))) { false })
        val reopened = backend(file).current()!!
        assertEquals(initial.providerIdentity, reopened.providerIdentity)
        assertEquals(1L, reopened.revision)
        assertEquals(listOf(record("old"), record("new")), reopened.models)
        assertEquals(reopened, backend(file).register(listOf(record("ignored"))))
    }

    @Test fun configurationMutationInvalidatesOldSessionWithoutReplacingIdentityOrModels() {
        val store = backend(File(temporary.root, "primary.catalog"))
        val initial = store.register(listOf(record("old")))
        store.configurationChange { /* host changes protocol/endpoint/key, not the catalog */ }
        assertEquals(CatalogCommitResult.STALE, store.compareAndAppend(initial.providerIdentity, 0, listOf(record("new"))) { false })
        val now = store.current()!!
        assertEquals(initial.providerIdentity, now.providerIdentity)
        assertEquals(1L, now.revision)
        assertEquals(initial.models, now.models)
    }

    @Test fun deletionAndRecreationNeverReviveOldIdentityAndDoNotTouchOtherProvider() {
        val first = backend(File(temporary.root, "first.catalog"))
        val otherFile = File(temporary.root, "second.catalog")
        val second = backend(otherFile)
        val before = first.register(listOf(record("a")))
        val other = second.register(listOf(record("b")))
        val bytes = otherFile.readBytes()
        assertTrue(first.deleteProvider(before.providerIdentity))
        assertNull(first.read(before.providerIdentity))
        assertNull(first.current())
        assertEquals(CatalogCommitResult.STALE, first.compareAndAppend(before.providerIdentity, before.revision, listOf(record("x"))) { false })
        val replacement = first.register()
        assertNotEquals(before.providerIdentity, replacement.providerIdentity)
        assertTrue(replacement.revision > before.revision)
        assertEquals(other, second.current())
        assertArrayEquals(bytes, otherFile.readBytes())
    }

    @Test fun cancellationImmediatelyBeforeRenameLeavesDurableAndPublishedStateUntouched() {
        val file = File(temporary.root, "primary.catalog")
        val store = backend(file)
        val before = store.register(listOf(record("old")))
        val bytes = file.readBytes()
        var checks = 0
        val result = store.compareAndAppend(before.providerIdentity, before.revision, listOf(record("new"))) { ++checks == 2 }
        assertEquals(CatalogCommitResult.CANCELLED, result)
        assertEquals(2, checks)
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(before, store.current())
        assertFalse(File(file.path + ".pending").exists())
    }

    @Test fun cancellationAfterTheFinalCheckCannotTurnSuccessfulRenameIntoCancelled() {
        val file = File(temporary.root, "primary.catalog")
        val store = backend(file)
        val before = store.register()
        var checks = 0
        var cancelledAfterFinalCheck = false
        val result = store.compareAndAppend(before.providerIdentity, 0, listOf(record("new"))) {
            checks++
            if (checks == 2) cancelledAfterFinalCheck = true
            false // cancellation happens immediately after this last precommit observation
        }
        assertTrue(cancelledAfterFinalCheck)
        assertEquals(2, checks)
        assertEquals(CatalogCommitResult.COMMITTED, result)
        assertEquals(listOf(record("new")), backend(file).current()!!.models)
    }

    @Test fun twoInstancesRacingTheSameRevisionHaveExactlyOneWinner() {
        val file = File(temporary.root, "primary.catalog")
        val first = backend(file)
        val second = backend(file)
        val before = first.register()
        val gate = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = listOf(first to "a", second to "b").map { (store, id) -> executor.submit<CatalogCommitResult> {
                check(gate.await(5, TimeUnit.SECONDS))
                store.compareAndAppend(before.providerIdentity, 0, listOf(record(id))) { false }
            } }
            gate.countDown()
            val results = futures.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it == CatalogCommitResult.COMMITTED })
            assertEquals(1, results.count { it == CatalogCommitResult.STALE })
            assertEquals(1, first.current()!!.models.size)
            assertEquals(1L, second.current()!!.revision)
        } finally { executor.shutdownNow() }
    }

    @Test fun invalidAdditionsAndCodecFailuresNeverRewriteOldBytes() {
        val file = File(temporary.root, "primary.catalog")
        val before = backend(file).register(listOf(record("old")))
        val bytes = file.readBytes()
        val failing = FileSavedModelCatalogPersistence(file, object : CatalogRecordCodec<String> {
            override fun encode(record: String): String = error("codec failure")
            override fun decode(encoded: String) = encoded
        })
        assertEquals(CatalogCommitResult.FAILED, failing.compareAndAppend(before.providerIdentity, 0, listOf(record("new"))) { false })
        assertEquals(CatalogCommitResult.FAILED, backend(file).compareAndAppend(before.providerIdentity, 0, listOf(record("old"))) { false })
        assertEquals(CatalogCommitResult.FAILED, backend(file).compareAndAppend(before.providerIdentity, 0,
            listOf(record("same"), record("same"))) { false })
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(before, backend(file).current())
    }

    @Test fun unreadableCatalogFailsClosedRatherThanRegeneratingIdentity() {
        val file = File(temporary.root, "primary.catalog")
        file.writeText("broken catalog")
        val bytes = file.readBytes()
        assertThrows(Exception::class.java) { backend(file).register() }
        assertEquals(CatalogCommitResult.FAILED, backend(file).compareAndAppend("old-provider", 0, listOf(record("new"))) { false })
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun nestedHostSaveBoundaryIsReentrantAndFailedSaveStillFencesOldFetch() {
        val store = backend(File(temporary.root, "primary.catalog"))
        val before = store.register(listOf(record("old")))
        assertThrows(IllegalStateException::class.java) {
            store.configurationChange { store.configurationChange { error("host save failed") } }
        }
        assertEquals(2L, store.current()!!.revision)
        assertEquals(before.models, store.current()!!.models)
        assertEquals(CatalogCommitResult.STALE, store.compareAndAppend(before.providerIdentity, 0, emptyList()) { false })
    }

    @Test fun allExistingSelectionNoOpStillPerformsAtomicRevisionCheck() {
        val file = File(temporary.root, "primary.catalog")
        val store = backend(file)
        val before = store.register(listOf(record("old")))
        val bytes = file.readBytes()
        assertEquals(CatalogCommitResult.COMMITTED, store.compareAndAppend(before.providerIdentity, 0, emptyList()) { false })
        assertArrayEquals(bytes, file.readBytes())
        store.configurationChange { }
        assertEquals(CatalogCommitResult.STALE, store.compareAndAppend(before.providerIdentity, 0, emptyList()) { false })
    }
}
