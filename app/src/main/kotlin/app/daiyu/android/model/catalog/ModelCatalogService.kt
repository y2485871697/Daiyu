package app.daiyu.android.model.catalog

import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.storage.ModelSettings
import app.daiyu.android.storage.ModelSettingsStore
import java.net.URI
import java.util.concurrent.CancellationException

/** Blocking worker-side facade. Saved and draft credentials never share a request. */
class ModelCatalogService(
    private val loadSaved: () -> ModelSettings,
    private val fetcher: RemoteModelFetcher = RemoteModelFetcher()
) {
    constructor(store: ModelSettingsStore, fetcher: RemoteModelFetcher = RemoteModelFetcher()) :
        this(store::load, fetcher)

    fun fetchSaved(isCancelled: () -> Boolean = { false }): List<RemoteModel> {
        if (Thread.currentThread().isInterrupted || isCancelled()) throw InterruptedException()
        val settings = try { loadSaved() }
        catch (cancelled: InterruptedException) { throw cancelled }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (Thread.currentThread().isInterrupted || isCancelled()) throw InterruptedException()
            throw RemoteModelException(RemoteModelFailure.AUTHENTICATION)
        }
        return fetchDraft(settings, isCancelled)
    }

    fun fetchDraft(settings: ModelSettings, isCancelled: () -> Boolean = { false }): List<RemoteModel> {
        if (Thread.currentThread().isInterrupted || isCancelled()) throw InterruptedException()
        val request = catalogRequest(settings)
        val models = fetcher.fetch(request, isCancelled)
        if (Thread.currentThread().isInterrupted || isCancelled()) throw InterruptedException()
        return models
    }
}

internal fun catalogRequest(settings: ModelSettings): RemoteModelRequest {
    val protocol = when (settings.protocol) {
        ProviderProtocol.RESPONSES -> RemoteModelProtocol.OPENAI
        ProviderProtocol.MESSAGES -> RemoteModelProtocol.ANTHROPIC
        ProviderProtocol.UNKNOWN -> throw RemoteModelException(RemoteModelFailure.REQUEST)
    }
    if (settings.apiKey.length > 8192 ||
        settings.apiKey.any { it == '\r' || it == '\n' }) {
        throw RemoteModelException(RemoteModelFailure.AUTHENTICATION)
    }
    val base = try {
        val uri = URI(settings.endpoint)
        // Validate the configured authority before deriving exactly one same-origin resource.
        remoteModelsEndpoint(settings.endpoint, protocol)
        val path = uri.rawPath.orEmpty().trimEnd('/')
        val suffix = if (settings.protocol == ProviderProtocol.RESPONSES) "/v1/responses" else "/v1/messages"
        val inferenceSuffix = if (settings.protocol == ProviderProtocol.RESPONSES) "/responses" else "/messages"
        val looksLikeInference = path.endsWith("/responses") || path.endsWith("/messages")
        require(!looksLikeInference || path.endsWith(suffix))
        val basePath = if (path.endsWith(suffix)) path.removeSuffix(inferenceSuffix) else path
        "${uri.scheme}://${uri.rawAuthority}$basePath"
    } catch (_: Exception) { throw RemoteModelException(RemoteModelFailure.INVALID_ENDPOINT) }
    // The inference-only model, body policy and responsesPath do not select a catalogue URL.
    return RemoteModelRequest(base, settings.apiKey, protocol)
}
