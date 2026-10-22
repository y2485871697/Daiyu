package app.daiyu.android.delegation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SubAgentModelDisplayStateTest {
    private val providers = listOf(SubAgentProvider("provider", "Provider", listOf(SubAgentModel("selection", "api-model"))))

    @Test fun clearRevokesLookupButRetainsLabelsUntilTheCurrentRefreshFinishes() = runBlocking {
        val source = SavedCatalogSubAgentModelSource()
        source.refreshAll { providers }
        source.clear()
        assertTrue(source.providers.value.isEmpty())
        assertTrue(source.refreshing.value)
        assertEquals(providers, source.displayProviders.value)
        source.refreshAll { providers }
        assertEquals(providers, source.providers.value)
        assertEquals(providers, source.displayProviders.value)
        assertFalse(source.refreshing.value)
    }

    @Test fun aRealEmptyResultOrFailureReplacesRetainedLabels() = runBlocking {
        for (failure in listOf(false, true)) {
            val source = SavedCatalogSubAgentModelSource()
            source.refreshAll { providers }
            source.refreshAll { if (failure) error("synthetic failure") else emptyList() }
            assertTrue(source.providers.value.isEmpty())
            assertTrue(source.displayProviders.value.isEmpty())
            assertFalse(source.refreshing.value)
        }
    }

    @Test fun lateRefreshCannotReplaceNewLabelsOrEndANewerRefresh() = runBlocking {
        val source = SavedCatalogSubAgentModelSource()
        source.refreshAll { providers }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val old = launch(start = CoroutineStart.UNDISPATCHED) {
            source.refreshAll { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); emptyList() }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            source.clear()
            release.countDown()
            old.join()
            assertTrue(source.refreshing.value)
            assertTrue(source.providers.value.isEmpty())
            assertEquals(providers, source.displayProviders.value)
            val changed = providers.map { it.copy(name = "Renamed provider") }
            source.refreshAll { changed }
            assertFalse(source.refreshing.value)
            assertEquals(changed, source.displayProviders.value)
        } finally { release.countDown(); old.join() }
    }

    @Test fun cancellationNeverReauthorizesRetainedPresentation() = runBlocking {
        val source = SavedCatalogSubAgentModelSource()
        source.refreshAll { providers }
        try {
            source.refreshAll { throw CancellationException("synthetic cancellation") }
            fail("cancellation swallowed")
        } catch (_: CancellationException) { }
        assertTrue(source.providers.value.isEmpty())
        assertTrue(source.refreshing.value)
        assertEquals(providers, source.displayProviders.value)
    }
}
