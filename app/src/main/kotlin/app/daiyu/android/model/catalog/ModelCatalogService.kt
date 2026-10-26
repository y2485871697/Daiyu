package app.daiyu.android.model.catalog

import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.model.ProviderSnapshot
import app.daiyu.android.storage.ModelSettings
import app.daiyu.android.storage.ModelSettingsStore
import java.net.URI
import java.util.concurrent.CancellationException

/** Blocking worker-side facade. Saved and draft credentials never share a request. */
class ModelCatalogService(
    private val loadSaved: () -> ModelSettings,
    private val fetcher: RemoteModelFetcher = RemoteModelFetcher(),
    private val speechTransport: app.daiyu.android.voice.providers.VoiceHttpTransport? = null,
    private val loadSavedForRun: () -> ModelSettings = loadSaved,
) {
    constructor(store: ModelSettingsStore, fetcher: RemoteModelFetcher = RemoteModelFetcher()) :
        this(store::load, fetcher, loadSavedForRun = { store.loadForRun() })

    /**
     * Worker-only, uncached production proof for one pending approval. The revision belongs to
     * the host's saved-config epoch (increment even for edit-away/edit-back and credential reset).
     * The supplied snapshot must be the exact object the eventual frozen run will retain.
     * Unlike selector fetches, this intentionally does NOT use an injected/test fetcher.
     */
    fun fetchSavedVideoEvidence(
        snapshot: ProviderSnapshot,
        configurationRevision: Long,
        currentConfigurationRevision: () -> Long,
        isCancelled: () -> Boolean = { false },
    ): OpenRouterVideoEvidence {
        fun checkSaved() {
            if (Thread.currentThread().isInterrupted || isCancelled()) throw InterruptedException()
            require(configurationRevision >= 0 && currentConfigurationRevision() == configurationRevision) {
                "Saved video configuration changed"
            }
            require(sameVideoRunConfiguration(loadSavedForRun(), snapshot)) { "Video snapshot is not the saved configuration" }
            require(currentConfigurationRevision() == configurationRevision) { "Saved video configuration changed" }
        }
        checkSaved()
        val evidence = OpenRouterVideoEvidence.fetchFresh(snapshot, configurationRevision, isCancelled,
            loadSavedForRun, currentConfigurationRevision)
        checkSaved()
        return evidence
    }

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

    internal fun testDraft(settings: ModelSettings, isCancelled: () -> Boolean): ModelConnectionResult = try {
        if (app.daiyu.android.voice.providers.DoubaoSpeech.isOpenspeech(settings.endpoint))
            app.daiyu.android.voice.providers.DoubaoSpeechConnection(speechTransport
                ?: app.daiyu.android.voice.network.OkHttpVoiceTransport()).test(settings, isCancelled)
        else ModelConnectionResult.Connected(fetchDraft(settings, isCancelled).size)
    } catch (_: InterruptedException) {
        ModelConnectionResult.Failed(RemoteModelFailure.CANCELLED)
    } catch (_: CancellationException) {
        ModelConnectionResult.Failed(RemoteModelFailure.CANCELLED)
    } catch (failure: RemoteModelException) {
        ModelConnectionResult.Failed(if (isCancelled() || Thread.currentThread().isInterrupted)
            RemoteModelFailure.CANCELLED else failure.failure)
    } catch (_: Exception) {
        ModelConnectionResult.Failed(if (isCancelled() || Thread.currentThread().isInterrupted)
            RemoteModelFailure.CANCELLED else RemoteModelFailure.REQUEST)
    }

    fun fetchDraft(settings: ModelSettings, isCancelled: () -> Boolean = { false }): List<RemoteModel> {
        if (Thread.currentThread().isInterrupted || isCancelled()) throw InterruptedException()
        if (app.daiyu.android.voice.providers.DoubaoSpeech.isOpenspeech(settings.endpoint)) {
            settings.validateConnection()
            return app.daiyu.android.voice.providers.DoubaoSpeech.catalogModels.map { (id, label) -> RemoteModel(id, label) }
        }
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
        ProviderProtocol.CHAT_COMPLETIONS -> RemoteModelProtocol.OPENAI
        ProviderProtocol.GEMINI -> RemoteModelProtocol.GEMINI
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
        val basePath = when (settings.protocol) {
            ProviderProtocol.RESPONSES, ProviderProtocol.MESSAGES -> {
                val suffix = if (settings.protocol == ProviderProtocol.RESPONSES) "/v1/responses" else "/v1/messages"
                val inferenceSuffix = if (settings.protocol == ProviderProtocol.RESPONSES) "/responses" else "/messages"
                val looksLikeInference = path.endsWith("/responses") || path.endsWith("/messages")
                require(!looksLikeInference || path.endsWith(suffix))
                if (path.endsWith(suffix)) path.removeSuffix(inferenceSuffix) else path
            }
            ProviderProtocol.CHAT_COMPLETIONS -> {
                require(!path.endsWith("/responses") && !path.endsWith("/messages"))
                path.removeSuffix("/chat/completions")
            }
            ProviderProtocol.GEMINI -> {
                require(!path.endsWith("/responses") && !path.endsWith("/messages") && !path.endsWith("/chat/completions"))
                if (path.contains("/models/")) {
                    require(path.endsWith(":streamGenerateContent"))
                    path.substringBeforeLast("/models/")
                } else path
            }
            ProviderProtocol.UNKNOWN -> throw IllegalArgumentException("Unknown protocol")
        }
        "${uri.scheme}://${uri.rawAuthority}$basePath"
    } catch (_: Exception) { throw RemoteModelException(RemoteModelFailure.INVALID_ENDPOINT) }
    // The inference-only model, body policy and responsesPath do not select a catalogue URL.
    return RemoteModelRequest(base, settings.apiKey, protocol, anthropicVersion = settings.requestOptions.anthropicVersion, customHeaders = settings.customHeaders, oauth = settings.oauth)
}
