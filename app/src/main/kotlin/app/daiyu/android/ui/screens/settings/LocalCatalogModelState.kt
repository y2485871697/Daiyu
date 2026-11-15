package app.daiyu.android.ui.screens.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.daiyu.android.model.catalog.SavedModelCatalogPersistence
import app.daiyu.android.model.catalog.SavedModelCatalogSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One provider-page visit owns its saved model list, independently of the selected tab. */
internal class LocalCatalogModelState<T>(
    private val providerIdentity: String,
    private val persistence: SavedModelCatalogPersistence<T>,
) {
    var snapshot by mutableStateOf<SavedModelCatalogSnapshot<T>?>(null)
        private set
    var loaded by mutableStateOf(false)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    private val reads = Mutex()
    private var invalidation = 0L
    private var loadedInvalidation = -1L

    fun invalidate() { invalidation++ }

    suspend fun load(force: Boolean = false): SavedModelCatalogSnapshot<T>? = reads.withLock {
        if (!force && loaded && loadedInvalidation == invalidation) return@withLock snapshot
        val revision = invalidation
        refreshing = true
        try {
            val value = withContext(Dispatchers.IO) { persistence.read(providerIdentity) }
            snapshot = value
            loaded = true
            loadedInvalidation = revision
            failed = false
            value
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            loaded = true
            failed = true
            throw failure
        } finally {
            refreshing = false
        }
    }
}
