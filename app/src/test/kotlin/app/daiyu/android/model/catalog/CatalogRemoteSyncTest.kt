package app.daiyu.android.model.catalog

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CatalogRemoteSyncTest {
    @get:Rule val temporary = TemporaryFolder()
    private val codec = object : CatalogRecordCodec<String> {
        override fun encode(record: String) = record
        override fun decode(encoded: String) = encoded
    }
    private fun store() = FileSavedModelCatalogPersistence(File(temporary.newFolder(), "catalog"), codec)
    private fun remote(id: String) = SavedCatalogModel(id, "Custom $id", "remote:private overrides")
    private fun session(store: SavedModelCatalogPersistence<String>) = CatalogImportSession(store,
        { SavedCatalogModel(it.id, it.displayName, "remote:defaults") }, { it.settings.startsWith("remote:") })
    private suspend fun sync(session: CatalogImportSession<String>, store: FileSavedModelCatalogPersistence<String>,
        request: CatalogImportSession.Request, models: List<RemoteModel>?) {
        session.fetched(request, models) { tickets, cancelled -> withContext(Dispatchers.IO) { store.deleteSelections(tickets, cancelled) } }
    }

    @Test fun cleanupPrecedesCandidatesAndCancelDoesNotRestoreDeletedRemoteModels() = runBlocking {
        val store = store()
        val kept = remote("Keep")
        val manual = SavedCatalogModel("manual", "Manual", "manual:options")
        val initial = store.register(listOf(kept, remote("old"), manual))
        val session = session(store)
        val request = session.begin(initial.providerIdentity)!!
        sync(session, store, request, listOf(RemoteModel(" keep "), RemoteModel("NEW"), RemoteModel("new")))
        assertEquals(listOf(kept, manual), store.current()!!.models)
        assertEquals(listOf("NEW"), session.state.selection!!.models.map { it.id })
        assertEquals(initial.revision + 1, session.state.selection!!.expectedRevision)
        assertEquals("已清理 1 个过时模型", session.state.status)
        session.dismiss()
        assertEquals(listOf(kept, manual), FileSavedModelCatalogPersistence(File(temporary.root.listFiles()!!.single(), "catalog"), codec).current()!!.models)
    }

    @Test fun addingAfterCleanupUsesNewRevisionAndKeepsLocalSettings() = runBlocking {
        val store = store(); val kept = remote("a")
        val initial = store.register(listOf(kept, remote("old")))
        val session = session(store); val request = session.begin(initial.providerIdentity)!!
        sync(session, store, request, listOf(RemoteModel("a", "Remote rename"), RemoteModel("b")))
        val selected = session.state.selection!!.toggle("b"); session.select(selected)
        val commit = session.prepareCommit(selected)!!
        val result = session.performCommit(commit); session.completed(commit, result)
        assertEquals(CatalogImportResult.Applied(1), result)
        assertEquals(listOf(kept, SavedCatalogModel("b", "b", "remote:defaults")), store.current()!!.models)
    }

    @Test fun cleanupWithoutNewModelsStillCompletesAndAllExistingDoesNotWrite() = runBlocking {
        val store = store(); val kept = remote("a")
        val initial = store.register(listOf(kept, remote("old")))
        val session = session(store)
        sync(session, store, session.begin(initial.providerIdentity)!!, listOf(RemoteModel("A")))
        assertNull(session.state.selection)
        assertEquals("已清理 1 个过时模型；没有可添加的新模型", session.state.status)
        val after = store.current()
        sync(session, store, session.begin(initial.providerIdentity)!!, listOf(RemoteModel("a")))
        assertEquals(after, store.current())
        assertEquals("没有可添加的新模型", session.state.status)
    }

    @Test fun failureEmptyAndBlankResponsesNeverPrune() = runBlocking {
        for (response in listOf(null, emptyList(), listOf(RemoteModel(" ")))) {
            val store = store(); val initial = store.register(listOf(remote("old")))
            val session = session(store)
            session.fetched(session.begin(initial.providerIdentity)!!, response) { _, _ -> error("must not delete") }
            assertEquals(initial, store.current()); assertNull(session.state.selection); assertFalse(session.state.loading)
        }
    }

    @Test fun unmarkedAndManualRecordsAreNotInObsoleteBatch() = runBlocking {
        val store = store(); val manual = SavedCatalogModel("manual", "Manual", "local")
        val initial = store.register(listOf(manual, remote("old")))
        val session = session(store)
        session.fetched(session.begin(initial.providerIdentity)!!, listOf(RemoteModel("new"))) { tickets, cancelled ->
            assertEquals(listOf("old"), tickets.map { it.record.id })
            withContext(Dispatchers.IO) { store.deleteSelections(tickets, cancelled) }
        }
        assertEquals(listOf(manual), store.current()!!.models)
    }

    @Test fun staleFetchCannotDeleteOrPublish() = runBlocking {
        val store = store(); val initial = store.register(listOf(remote("old")))
        val session = session(store); val request = session.begin(initial.providerIdentity)!!
        store.configurationChange { }
        session.fetched(request, listOf(RemoteModel("new"))) { _, _ -> error("must not delete") }
        assertEquals(initial.models, store.current()!!.models)
        assertEquals("服务商或目录已变化，请重新获取", session.state.status)
    }

    @Test fun staleFailedAndCancelledCleanupNeverPublishCandidates() = runBlocking {
        for (result in listOf(CatalogLocalEditResult.STALE, CatalogLocalEditResult.FAILED, CatalogLocalEditResult.CANCELLED)) {
            val store = store(); val initial = store.register(listOf(remote("old")))
            val session = session(store)
            session.fetched(session.begin(initial.providerIdentity)!!, listOf(RemoteModel("new"))) { _, _ -> result }
            assertNull(session.state.selection); assertFalse(session.state.loading); assertEquals(initial, store.current())
        }
    }

    @Test fun cleanupCasRejectsConcurrentEditAndReReadRejectsLaterMutation() = runBlocking {
        for (after in listOf(false, true)) {
            val store = store(); val initial = store.register(listOf(remote("old")))
            val session = session(store)
            session.fetched(session.begin(initial.providerIdentity)!!, listOf(RemoteModel("new"))) { tickets, cancelled ->
                if (!after) store.configurationChange { }
                val result = withContext(Dispatchers.IO) { store.deleteSelections(tickets, cancelled) }
                if (after) store.configurationChange { }
                result
            }
            assertNull(session.state.selection)
            assertEquals("服务商或目录已变化，请重新获取", session.state.status)
            assertEquals(if (after) emptyList<SavedCatalogModel<String>>() else initial.models, store.current()!!.models)
        }
    }

    @Test fun invalidatedOrDuplicateCallbackDoesNotStartAnotherCleanup() = runBlocking {
        val store = store(); val initial = store.register(listOf(remote("old")))
        val session = session(store); val request = session.begin(initial.providerIdentity)!!
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val pending = launch(start = CoroutineStart.UNDISPATCHED) {
            session.fetched(request, listOf(RemoteModel("new"))) { tickets, cancelled ->
                entered.complete(Unit); release.await()
                withContext(Dispatchers.IO) { store.deleteSelections(tickets, cancelled) }
            }
        }
        entered.await()
        session.fetched(request, listOf(RemoteModel("duplicate"))) { _, _ -> error("duplicate delete") }
        session.invalidate(); release.complete(Unit); pending.join()
        assertEquals(initial, store.current()); assertNull(session.state.selection)
    }

    @Test fun lateCommittedCleanupDoesNotOverwriteNewerFetch() = runBlocking {
        val store = store(); val initial = store.register(listOf(remote("old")))
        val session = session(store); val request = session.begin(initial.providerIdentity)!!
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val pending = launch(start = CoroutineStart.UNDISPATCHED) {
            session.fetched(request, listOf(RemoteModel("new"))) { tickets, cancelled ->
                val result = withContext(Dispatchers.IO) { store.deleteSelections(tickets, cancelled) }
                entered.complete(Unit); release.await(); result
            }
        }
        entered.await()
        val latest = session.begin(initial.providerIdentity)!!
        session.fetched(latest, listOf(RemoteModel("latest")))
        val expected = session.state
        release.complete(Unit); pending.join()
        assertEquals(expected, session.state); assertTrue(store.current()!!.models.isEmpty())
    }

    @Test fun unsavedConnectionDoesNotReadOrAuthorizeRemoteCleanup() = runBlocking {
        val store = store(); val initial = store.register(listOf(remote("old")))
        val session = session(store)
        assertNull(session.begin(initial.providerIdentity, connectionSaved = false))
        assertEquals("请先保存提供商设置，再拉取模型", session.state.status)
        assertEquals(initial, store.current())
    }

    @Test fun commitAlsoExcludesExistingCaseVariants() {
        val store = store(); val kept = remote("Keep"); val initial = store.register(listOf(kept))
        val selected = ModelSelectionState.capture(initial.providerIdentity, initial.revision,
            listOf(RemoteModel("keep"), RemoteModel("new"), RemoteModel("NEW"))).selectAllVisible()
        val result = SavedModelCatalogStore(store) { SavedCatalogModel(it.id, it.displayName, "defaults") }.appendSelected(selected)
        assertEquals(CatalogImportResult.Applied(1), result)
        assertEquals(listOf("Keep", "new"), store.current()!!.models.map { it.id })
    }
}
