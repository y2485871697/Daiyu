package app.daiyu.android.model.catalog

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class CatalogImportSessionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val codec = object : CatalogRecordCodec<String> {
        override fun encode(record: String) = record
        override fun decode(encoded: String) = encoded
    }
    private fun persistence() = FileSavedModelCatalogPersistence(File(temporary.root, "models"), codec)
    private val old = SavedCatalogModel("existing", "Customized name", "custom local parameters")
    private val remote = listOf(RemoteModel("existing", "Remote name"), RemoteModel("add"), RemoteModel("unchecked"))
    private fun session(store: SavedModelCatalogPersistence<String>) = CatalogImportSession(store,
        { SavedCatalogModel(it.id, it.displayName, "normal defaults") })

    /** Capture a real disk snapshot, then hold its delivery to simulate a blocked OS-lock/read. */
    private class PausedRead(private val delegate: SavedModelCatalogPersistence<String>) :
        SavedModelCatalogPersistence<String> by delegate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        private val armed = java.util.concurrent.atomic.AtomicBoolean(false)
        @Volatile var readThread: Thread? = null
        fun arm() { armed.set(true) }
        override fun read(providerIdentity: String): SavedModelCatalogSnapshot<String>? {
            val snapshot = delegate.read(providerIdentity)
            if (armed.compareAndSet(true, false)) {
                readThread = Thread.currentThread()
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            return snapshot
        }
    }

    @Test fun pendingBeginReadsOffUiAndCannotStartAfterInvalidationOrReplaceNewerState() = runBlocking {
        val uiThread = Thread.currentThread()
        for (replace in listOf(false, true)) {
            val store = FileSavedModelCatalogPersistence(File(temporary.root, "begin-$replace"), codec)
            val initial = store.register()
            val paused = PausedRead(store).also { it.arm() }
            val session = CatalogImportSession(paused, { SavedCatalogModel(it.id, it.displayName, "defaults") }) {
                assertSame(uiThread, Thread.currentThread())
            }
            var admitted: CatalogImportSession.Request? = null
            val pending = launch(start = CoroutineStart.UNDISPATCHED) { admitted = session.begin(initial.providerIdentity) }
            try {
                assertTrue(paused.entered.await(5, TimeUnit.SECONDS))
                assertNotSame(uiThread, paused.readThread)
                assertTrue(session.state.loading)
                if (replace) {
                    val latest = session.begin(initial.providerIdentity)!!
                    session.fetched(latest, listOf(RemoteModel("latest")))
                } else {
                    session.invalidate() // host draft edit or lifecycle disposal
                }
                val expected = session.state
                paused.release.countDown()
                pending.join()
                assertNull(admitted) // no obsolete network request can be launched
                assertEquals(expected, session.state)
                assertEquals(initial, store.current())
            } finally { paused.release.countDown(); pending.join() }
        }
    }

    @Test fun pendingFetchedReadCannotPublishAfterInvalidationReplacementOrRejection() = runBlocking {
        val uiThread = Thread.currentThread()
        for (change in listOf("invalidate", "replace", "reject")) {
            val store = FileSavedModelCatalogPersistence(File(temporary.root, "fetched-$change"), codec)
            val initial = store.register()
            val paused = PausedRead(store)
            val session = CatalogImportSession(paused, { SavedCatalogModel(it.id, it.displayName, "defaults") }) {
                assertSame(uiThread, Thread.currentThread())
            }
            val request = session.begin(initial.providerIdentity)!!
            paused.arm()
            val pending = launch(start = CoroutineStart.UNDISPATCHED) { session.fetched(request, remote) }
            try {
                assertTrue(paused.entered.await(5, TimeUnit.SECONDS))
                assertNotSame(uiThread, paused.readThread)
                when (change) {
                    "invalidate" -> session.invalidate()
                    "replace" -> session.fetched(session.begin(initial.providerIdentity)!!, listOf(RemoteModel("latest")))
                    "reject" -> session.rejected(request)
                }
                val expected = session.state
                paused.release.countDown()
                pending.join()
                assertEquals(expected, session.state)
                assertEquals(initial, store.current())
            } finally { paused.release.countDown(); pending.join() }
        }
    }

    @Test fun fetchThenSelectAndConfirmOnlyAppendsCheckedMissingModels() = runBlocking {
        val store = persistence()
        val initial = store.register(listOf(old))
        val session = session(store)
        val request = session.begin(initial.providerIdentity)!!
        session.fetched(request, remote)
        assertEquals(initial, store.current()) // fetching is never a save
        val checked = session.state.selection!!.toggle("existing").toggle("add")
        session.select(checked)
        val commit = session.prepareCommit(checked)!!
        assertTrue(session.state.saving)
        val result = session.performCommit(commit)
        session.completed(commit, result)
        assertEquals(CatalogImportResult.Applied(1), result)
        assertNull(session.state.selection)
        assertFalse(session.state.saving)
        assertEquals(listOf(old, SavedCatalogModel("add", "add", "normal defaults")), store.current()!!.models)
    }

    @Test fun cancelAndLateFetchCallbacksCannotImportOrOpenDialog() = runBlocking {
        val store = persistence()
        val initial = store.register(listOf(old))
        val session = session(store)
        val request = session.begin(initial.providerIdentity)!!
        session.dismiss()
        assertTrue(request.isCancelled())
        session.fetched(request, remote)
        assertNull(session.state.selection)
        assertEquals(initial, store.current())
        val second = session.begin(initial.providerIdentity)!!
        session.fetched(second, remote)
        session.dismiss()
        assertNull(session.state.selection)
        assertEquals(initial, store.current())
    }

    @Test fun onlyLatestFetchCanPublishAndFailureNeverShowsDialog() = runBlocking {
        val store = persistence()
        val initial = store.register()
        val session = session(store)
        val oldRequest = session.begin(initial.providerIdentity)!!
        val currentRequest = session.begin(initial.providerIdentity)!!
        session.fetched(oldRequest, remote)
        assertTrue(session.state.loading)
        assertNull(session.state.selection)
        session.fetched(currentRequest, null)
        assertFalse(session.state.loading)
        assertNull(session.state.selection)
        assertEquals("获取失败，请重试", session.state.status)
        assertEquals(initial, store.current())
    }

    @Test fun changesDuringFetchOrAfterDialogWasOpenedCannotCommit() = runBlocking {
        for (duringFetch in listOf(true, false)) {
            val file = File(temporary.root, "case-$duringFetch")
            val store = FileSavedModelCatalogPersistence(file, codec)
            val initial = store.register(listOf(old))
            val session = session(store)
            val request = session.begin(initial.providerIdentity)!!
            if (duringFetch) store.configurationChange { }
            session.fetched(request, remote)
            if (duringFetch) {
                assertNull(session.state.selection)
                assertEquals("服务商或目录已变化，请重新获取", session.state.status)
            } else {
                val checked = session.state.selection!!.toggle("add")
                session.select(checked)
                store.configurationChange { }
                val commit = session.prepareCommit(checked)!!
                val result = session.performCommit(commit)
                session.completed(commit, result)
                assertEquals(CatalogImportResult.Stale, result)
                assertNull(session.state.selection)
            }
            assertEquals(listOf(old), store.current()!!.models)
        }
    }

    @Test fun deletionWhileDialogIsOpenAndInvalidationBeforeWorkerBothWriteNothing() = runBlocking {
        for (deleted in listOf(true, false)) {
            val store = FileSavedModelCatalogPersistence(File(temporary.root, "case-$deleted"), codec)
            val initial = store.register(listOf(old))
            val session = session(store)
            session.fetched(session.begin(initial.providerIdentity)!!, remote)
            val checked = session.state.selection!!.toggle("add")
            session.select(checked)
            val commit = session.prepareCommit(checked)!!
            if (deleted) store.deleteProvider(initial.providerIdentity) else session.invalidate()
            val result = session.performCommit(commit)
            session.completed(commit, result)
            assertEquals(if (deleted) CatalogImportResult.Stale else CatalogImportResult.Cancelled, result)
            assertNull(session.state.selection)
            if (!deleted) assertEquals(initial, store.current())
        }
    }

    @Test fun cancellationAfterDurableCommitDoesNotHideSuccessOrRollBack() = runBlocking {
        val store = persistence()
        val initial = store.register()
        val session = session(store)
        val request = session.begin(initial.providerIdentity)!!
        session.fetched(request, remote)
        val checked = session.state.selection!!.toggle("add")
        session.select(checked)
        val commit = session.prepareCommit(checked)!!
        session.dismiss() // dismissal is ignored during saving
        assertFalse(request.isCancelled())
        val result = session.performCommit(commit)
        request.cancel() // after the atomic commit, before result dispatch
        session.completed(commit, result)
        assertEquals(CatalogImportResult.Applied(1), result)
        assertEquals("已添加 1 个模型", session.state.status)
        assertEquals(listOf("add"), store.current()!!.models.map { it.id })
    }

    @Test fun failureRetainsCheckedSelectionAndAllowsRetryWithoutSavingOldRecords() = runBlocking {
        val store = persistence()
        val initial = store.register(listOf(old))
        var fail = true
        val session = CatalogImportSession(store, {
            if (fail) error("private failure details")
            SavedCatalogModel(it.id, it.displayName, "defaults")
        })
        session.fetched(session.begin(initial.providerIdentity)!!, remote)
        val checked = session.state.selection!!.toggle("add")
        session.select(checked)
        val first = session.prepareCommit(checked)!!
        session.completed(first, session.performCommit(first))
        assertSame(checked, session.state.selection)
        assertEquals("模型未添加，请重试", session.state.error)
        assertFalse(session.state.saving)
        assertEquals(initial, store.current())
        fail = false
        val retry = session.prepareCommit(checked)!!
        session.completed(retry, session.performCommit(retry))
        assertEquals(listOf("existing", "add"), store.current()!!.models.map { it.id })
        assertEquals(old, store.current()!!.models.first())
    }

    @Test fun forgedOrEmptySelectionAndRejectedFetchNeverStartCommit() = runBlocking {
        val store = persistence()
        val initial = store.register()
        val session = session(store)
        val request = session.begin(initial.providerIdentity)!!
        session.rejected(request)
        session.fetched(request, remote)
        assertNull(session.state.selection)
        val next = session.begin(initial.providerIdentity)!!
        session.fetched(next, remote)
        assertNull(session.prepareCommit(session.state.selection!!))
        val forged = ModelSelectionState.capture(initial.providerIdentity, initial.revision, remote).toggle("add")
        session.select(forged)
        assertNull(session.prepareCommit(forged))
        assertEquals(initial, store.current())
    }
}
