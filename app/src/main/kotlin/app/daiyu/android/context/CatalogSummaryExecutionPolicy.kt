package app.daiyu.android.context

import android.content.Context
import app.daiyu.android.model.SummaryProviderFactory
import app.daiyu.android.storage.ModelSettings
import app.daiyu.android.storage.ProviderRepository

/** Application request limits, not provider capability metadata. Only the selected model supplies capacity. */
internal object CatalogSummaryExecutionPolicy {
    const val OUTPUT_CAP_TOKENS = 4096
    const val DEADLINE_MILLIS = 60_000L
    private const val EXTRA_FRAMING_RESERVE = 512L

    fun chatConfiguration(snapshot: app.daiyu.android.model.ProviderSnapshot, window: Long): SummaryProviderConfiguration {
        require(window in 1024..10_000_000)
        val settings = summarySettings(snapshot.asModelSettings())
        val binding = SummaryModelBinding("frozen-chat-summary", settings.model)
        return configuration(binding, settings.copy(contextWindowOverride = window.toInt()))
    }

    /** Project chat-only reasoning/output/tool options onto the existing tool-free summary policy. */
    fun summarySettings(settings: ModelSettings): ModelSettings {
        val options = settings.requestOptions
        val body = if (options.customBody.isEmpty()) org.json.JSONObject() else
            app.daiyu.android.model.MessagesRequest.jsonObject(options.customBody)
        val projected = org.json.JSONObject()
        listOf("temperature", "top_p", "service_tier").forEach { key ->
            if (body.has(key)) projected.put(key, body.get(key))
        }
        return settings.copy(model = if (body.has("model")) body.getString("model") else settings.model,
            batteryEnabled = false, deviceStatusEnabled = false,
            requestOptions = options.copy(customBody = projected.toString(), openAiWebSearchEnabled = false))
    }

    fun configuration(binding: SummaryModelBinding, settings: ModelSettings): SummaryProviderConfiguration {
        val window = settings.contextWindowOverride?.toLong()
            ?: throw SummaryProfileUnavailableException(SummaryProfileUnavailableReason.INVALID_CONFIGURATION)
        val snapshot = settings.copy(batteryEnabled = false, deviceStatusEnabled = false).requestSnapshot()
        SummaryProviderFactory.validate(snapshot)
        val overhead = ProviderSummaryModelPort.nativeInstructionReserveTokens + EXTRA_FRAMING_RESERVE
        // The wire output cap bounds generated output; no additional reasoning capacity is inferred.
        val input = window - OUTPUT_CAP_TOKENS.toLong() - overhead
        require(input > 0) { "摘要模型窗口不足以容纳应用执行预算" }
        return SummaryProviderConfiguration(binding, snapshot, snapshot.model, window, input,
            OUTPUT_CAP_TOKENS, 0L, overhead, DEADLINE_MILLIS)
    }
}

internal fun catalogSummaryConfiguration(context: Context, binding: SummaryModelBinding): SummaryProviderConfiguration {
    val target = requireNotNull(CatalogSummaryBindings.decode(binding))
    return CatalogSummaryExecutionPolicy.configuration(binding,
        CatalogSummaryExecutionPolicy.summarySettings(ProviderRepository(context).freezeCatalog(target).settings))
}

internal fun catalogSummaryDescriptor(context: Context, binding: SummaryModelBinding): SummaryProfileDescriptor {
    val target = requireNotNull(CatalogSummaryBindings.decode(binding))
    return catalogSummaryDescriptor(binding, ProviderRepository(context).catalogEndpoint(target))
}

private fun catalogSummaryDescriptor(binding: SummaryModelBinding, endpoint: String): SummaryProfileDescriptor {
    val target = requireNotNull(CatalogSummaryBindings.decode(binding))
    return SummaryProfileDescriptor(binding, binding.providerId, endpoint, target.apiModel)
}

internal fun catalogSummaryConfiguration(context: Context, expected: SummaryProfileDescriptor): SummaryProviderConfiguration {
    val target = requireNotNull(CatalogSummaryBindings.decode(expected.binding))
    val frozen = ProviderRepository(context).freezeCatalog(target) { endpoint ->
        if (catalogSummaryDescriptor(expected.binding, endpoint) != expected) throw SummaryProfileChangedException()
    }
    return CatalogSummaryExecutionPolicy.configuration(expected.binding, CatalogSummaryExecutionPolicy.summarySettings(frozen.settings))
}
