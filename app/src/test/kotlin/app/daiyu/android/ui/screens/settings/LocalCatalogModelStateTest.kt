package app.daiyu.android.ui.screens.settings

import app.daiyu.android.model.catalog.CatalogRecordCodec
import app.daiyu.android.model.catalog.FileSavedModelCatalogPersistence
import app.daiyu.android.model.catalog.SavedCatalogModel
import app.daiyu.android.model.catalog.SavedModelCatalogPersistence
import app.daiyu.android.model.catalog.SavedModelCatalogSnapshot
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LocalCatalogModelStateTest {
    @get:Rule val temporary = TemporaryFolder()
    private val codec = object : CatalogRecordCodec<String> {
        override fun encode(record: String) = record
        override fun decode(encoded: String) = encoded
    }
    private fun store() = FileSavedModelCatalogPersistence(File(temporary.root, "catalog"), codec)
    private val old = SavedCatalogModel("old", "Old", "parameters")

    @Test fun repeatedLoadsReuseOneOffThreadReadUntilExplicitRefresh() = runBlocking {
        val store = store()
        val initial = store.register(listOf(old))
        val reads = AtomicInteger()
        val owner = Thread.currentThread()
        val counted = object : SavedModelCatalogPersistence<String> by store {
            override fun read(providerIdentity: String): SavedModelCatalogSnapshot<String>? {
                assertNotSame(owner, Thread.currentThread())
                reads.incrementAndGet()
                return store.read(providerIdentity)
            }
        }
        val state = LocalCatalogModelState(initial.providerIdentity, counted)
        repeat(4) { assertEquals(initial, state.load()) }
        assertEquals(1, reads.get())
        store.compareAndAppend(initial.providerIdentity, initial.revision,
            listOf(SavedCatalogModel("added", "Added", "defaults"))) { false }
        assertEquals(initial, state.load())
        assertEquals(2, state.load(force = true)!!.models.size)
        assertEquals(2, reads.get())
        state.invalidate()
        state.load()
        assertEquals(3, reads.get())
        assertTrue(state.loaded)
        assertFalse(state.refreshing)
        assertFalse(state.failed)
    }

    @Test fun preloadAndTabReadShareTheSamePendingDiskRead() = runBlocking {
        val store = store()
        val initial = store.register(listOf(old))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reads = AtomicInteger()
        val paused = object : SavedModelCatalogPersistence<String> by store {
            override fun read(providerIdentity: String): SavedModelCatalogSnapshot<String>? {
                reads.incrementAndGet(); entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return store.read(providerIdentity)
            }
        }
        val state = LocalCatalogModelState(initial.providerIdentity, paused)
        val preload = launch(start = CoroutineStart.UNDISPATCHED) { state.load() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(state.loaded)
            val tab = launch(start = CoroutineStart.UNDISPATCHED) { state.load() }
            release.countDown()
            preload.join(); tab.join()
            assertEquals(1, reads.get())
            assertEquals(initial, state.snapshot)
        } finally { release.countDown(); preload.join() }
    }

    @Test fun failedRefreshKeepsTheLastListAndExplicitRetryRecovers() = runBlocking {
        val store = store()
        val initial = store.register(listOf(old))
        var shouldFail = false
        val backend = object : SavedModelCatalogPersistence<String> by store {
            override fun read(providerIdentity: String): SavedModelCatalogSnapshot<String>? {
                if (shouldFail) error("read unavailable")
                return store.read(providerIdentity)
            }
        }
        val state = LocalCatalogModelState(initial.providerIdentity, backend)
        state.load()
        shouldFail = true
        try { state.load(force = true); fail("Expected read failure") } catch (_: IllegalStateException) { }
        assertEquals(initial, state.snapshot)
        assertTrue(state.failed)
        assertFalse(state.refreshing)
        shouldFail = false
        assertEquals(initial, state.load(force = true))
        assertFalse(state.failed)
    }

    @Test fun cancelledOwnerCannotPublishItsPendingRead() = runBlocking {
        val store = store()
        val initial = store.register(listOf(old))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val backend = object : SavedModelCatalogPersistence<String> by store {
            override fun read(providerIdentity: String): SavedModelCatalogSnapshot<String>? {
                val value = store.read(providerIdentity)
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return value
            }
        }
        val state = LocalCatalogModelState(initial.providerIdentity, backend)
        val pending = launch(start = CoroutineStart.UNDISPATCHED) { state.load() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            pending.cancel()
        } finally { release.countDown(); pending.join() }
        assertNull(state.snapshot)
        assertFalse(state.loaded)
        assertFalse(state.refreshing)
        assertFalse(state.failed)
    }
}
