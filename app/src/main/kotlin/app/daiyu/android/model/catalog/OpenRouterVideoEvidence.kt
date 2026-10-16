package app.daiyu.android.model.catalog

import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.model.ProviderSnapshot
import app.daiyu.android.model.videos.OpenRouterVideoCapability
import app.daiyu.android.storage.ModelSettings
import org.json.JSONException
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

internal const val OPENROUTER_MODELS_ENDPOINT = "https://openrouter.ai/api/v1/models"

/** Parser/transport test result, deliberately NOT accepted by approval or capability APIs. */
internal class OpenRouterVideoModelFacts(val model: String, modalities: Set<String>) {
    val inputModalities: Set<String> = Collections.unmodifiableSet(modalities.toSet())
}

/** Strict, pure test seam. No parsing call can create runtime evidence. */
internal fun parseOpenRouterVideoFacts(body: String, source: String, exactModel: String): OpenRouterVideoModelFacts {
    fun invalid(): Nothing = throw RemoteModelException(RemoteModelFailure.PROTOCOL)
    if (source != OPENROUTER_MODELS_ENDPOINT) invalid()
    if (exactModel.isBlank() || exactModel != exactModel.trim() || exactModel.length > 200) invalid()
    try {
        val root = JSONObject(body)
        val hasMore = root.opt("has_more")
        if (hasMore != null && hasMore !== JSONObject.NULL && hasMore != false) invalid()
        val nextPage = root.opt("nextPageToken")
        if (nextPage != null && nextPage !== JSONObject.NULL && nextPage != "") invalid()
        val entries = root.getJSONArray("data")
        // This exact, unfiltered models GET must account for every row, not just the selected model.
        // Read the raw value: optInt/optLong would silently accept strings or truncate fractions.
        if (root.has("total_count")) {
            val totalCount = when (val value = root.get("total_count")) {
                is Int -> value.toLong()
                is Long -> value
                else -> invalid()
            }
            if (totalCount != entries.length().toLong()) invalid()
        }
        val linksValue = root.opt("links")
        if (linksValue != null && linksValue !== JSONObject.NULL) {
            val links = linksValue as? JSONObject ?: invalid()
            val next = links.opt("next")
            if (next != null && next !== JSONObject.NULL && next != "") invalid()
            // self/prev and other non-next metadata do not imply an incomplete result.
        }
        val seen = HashSet<String>()
        var found: OpenRouterVideoModelFacts? = null
        for (index in 0 until entries.length()) {
            val item = entries.optJSONObject(index) ?: invalid()
            val id = item.opt("id") as? String ?: invalid()
            // Do not trim/normalize identities or let order decide contradictory duplicates.
            if (id.isBlank() || id != id.trim() || !seen.add(id)) invalid()
            if (id != exactModel) continue
            val modalities = item.getJSONObject("architecture").getJSONArray("input_modalities")
            val values = LinkedHashSet<String>()
            for (m in 0 until modalities.length()) {
                val value = modalities.opt(m) as? String ?: invalid()
                if (value.isBlank() || value != value.trim() || !values.add(value)) invalid()
            }
            if ("video" !in values) invalid()
            found = OpenRouterVideoModelFacts(id, values)
        }
        return found ?: invalid()
    } catch (_: JSONException) {
        invalid()
    }
}

/**
 * In-memory, one-approval evidence. No boolean, JSON, cached catalogue or injected transport can
 * construct this type. Only a fresh production HTTPS GET to the exact official models endpoint
 * can mint it. The saved-settings facade checks the host's monotonic revision before/after I/O.
 * Architecture.video plus the documented OpenRouter video contract is required; a model name,
 * image support or Responses protocol by itself is never sufficient.
 */
class OpenRouterVideoEvidence private constructor(
    private val provider: ProviderSnapshot,
    val configurationRevision: Long,
    val model: String,
    val source: String,
    val endpoint: String,
    modalities: Set<String>,
    private val isSavedCurrent: () -> Boolean,
) {
    val inputModalities: Set<String> = Collections.unmodifiableSet(modalities.toSet())
    /** Documentary protocol basis, not a successful inference/upload probe. See integration doc. */
    val protocol: ProviderProtocol get() = ProviderProtocol.RESPONSES
    val protocolDocumentation: String get() =
        "https://openrouter.ai/docs/client-sdks/typescript/models/inputvideo"
    private val claimed = AtomicBoolean(false)

    fun matches(snapshot: ProviderSnapshot, revision: Long): Boolean =
        !Thread.currentThread().isInterrupted && provider === snapshot && configurationRevision == revision &&
            snapshot.protocol == protocol && snapshot.model == model &&
            snapshot.asModelSettings().requestEndpoint() == endpoint &&
            endpoint == OpenRouterVideoCapability.ENDPOINT && source == OPENROUTER_MODELS_ENDPOINT &&
            "video" in inputModalities && runCatching(isSavedCurrent).getOrDefault(false)

    internal fun claim(snapshot: ProviderSnapshot, revision: Long): Boolean =
        matches(snapshot, revision) && claimed.compareAndSet(false, true)

    override fun toString() = "OpenRouterVideoEvidence(<fresh catalog declaration>)"

    companion object {
        /** Deliberately no injected fetcher: test transports must never mint production proof. */
        internal fun fetchFresh(
            provider: ProviderSnapshot,
            revision: Long,
            isCancelled: () -> Boolean,
            loadSaved: () -> ModelSettings,
            currentConfigurationRevision: () -> Long,
        ): OpenRouterVideoEvidence {
            val isSavedCurrent = {
                currentConfigurationRevision() == revision &&
                    loadSaved() == provider.asModelSettings() && currentConfigurationRevision() == revision
            }
            require(isSavedCurrent()) { "Saved video configuration changed" }
            require(revision >= 0 && provider.protocol == ProviderProtocol.RESPONSES)
            val settings = provider.asModelSettings()
            require(settings.requestEndpoint() == OpenRouterVideoCapability.ENDPOINT)
            val facts = RemoteModelFetcher().fetchOpenRouterVideoFacts(
                RemoteModelRequest(OPENROUTER_MODELS_ENDPOINT, settings.apiKey, RemoteModelProtocol.OPENAI),
                provider.model, isCancelled,
            )
            if (Thread.currentThread().isInterrupted || isCancelled()) throw InterruptedException()
            require(isSavedCurrent()) { "Saved video configuration changed" }
            return OpenRouterVideoEvidence(provider, revision, facts.model,
                OPENROUTER_MODELS_ENDPOINT, settings.requestEndpoint(), facts.inputModalities, isSavedCurrent)
        }
    }
}
