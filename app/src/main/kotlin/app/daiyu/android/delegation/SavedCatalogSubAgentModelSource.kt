package app.daiyu.android.delegation

import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.model.catalog.CatalogModelParameters
import app.daiyu.android.model.catalog.SavedModelCatalogSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** A view of saved identities, not an execution binding or a capability detector. */
internal class SavedCatalogSubAgentModelSource : SubAgentModelSource {
    private val state = MutableStateFlow<List<SubAgentProvider>>(emptyList())
    override val providers = state.asStateFlow()
    private var generation = 0L

    /** Same UI lane as refresh; invalidates even a blocking read that has not returned yet. */
    fun clear() {
        generation++
        state.value = emptyList()
    }

    /** Called on the UI lane; blocking registry/credential work stays on IO. */
    suspend fun refresh(
        read: () -> SavedModelCatalogSnapshot<CatalogModelParameters>?,
        validateConnection: () -> ProviderProtocol,
    ) {
        clear()
        val request = generation
        val loaded = try {
            withContext(Dispatchers.IO) {
                val before = read() ?: return@withContext emptyList()
                val protocol = validateConnection()
                val after = read() ?: return@withContext emptyList()
                if (before.providerIdentity != after.providerIdentity || before.revision != after.revision)
                    emptyList()
                else listOf(projectSubAgentProvider(after, protocol)).filter { it.models.any { model -> model.isEnabled } }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { emptyList() }
        if (request == generation) state.value = loaded
    }
}

internal fun projectSubAgentProvider(
    snapshot: SavedModelCatalogSnapshot<CatalogModelParameters>, protocol: ProviderProtocol,
): SubAgentProvider {
    require(snapshot.models.map { it.settings.localIdentity }.distinct().size == snapshot.models.size)
    return SubAgentProvider(snapshot.providerIdentity, "主模型服务商", snapshot.models.map { record ->
        val enabled = try { record.settings.requestOptions.validate(protocol); true } catch (_: Exception) { false }
        SubAgentModel(id = record.settings.localIdentity, modelId = record.id,
            displayName = record.displayName, isEnabled = enabled)
    })
}
