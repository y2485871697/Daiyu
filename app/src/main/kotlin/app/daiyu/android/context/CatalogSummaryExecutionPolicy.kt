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
    return CatalogSummaryExecutionPolicy.configuration(binding, ProviderRepository(context).freezeCatalog(target).settings)
}
