package app.daiyu.android.delegation

import app.daiyu.android.model.GptSpeedMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Read-only UI snapshot. The caller projects its model registry; no credentials or old repository. */
internal interface SubAgentModelSource {
    val providers: StateFlow<List<SubAgentProvider>>
    /** Retained labels for presentation only; confirmation still resolves against providers. */
    val displayProviders: StateFlow<List<SubAgentProvider>> get() = providers
    val refreshing: StateFlow<Boolean> get() = NotRefreshing
    val models: List<SubAgentModel> get() = providers.value.flatMap { it.models }

    companion object {
        private val NotRefreshing = MutableStateFlow(false).asStateFlow()
        val Empty: SubAgentModelSource = object : SubAgentModelSource {
            override val providers = MutableStateFlow<List<SubAgentProvider>>(emptyList())
        }
    }
}

internal data class SubAgentProvider(
    val id: String,
    val name: String,
    val models: List<SubAgentModel>,
    val isEnabled: Boolean = true,
)

/** Capability information must come from the selected record, never from a fallback model. */
internal data class SubAgentModel(
    val id: String,
    val modelId: String,
    val displayName: String = modelId,
    val isEnabled: Boolean = true,
    val supportsImageGeneration: Boolean = false,
    val supportsVideoGeneration: Boolean = false,
    val supportsSpeechSynthesis: Boolean = false,
    val supportsGptSpeed: Boolean = false,
    val reasoningEfforts: List<ReasoningEffort> = emptyList(),
    val defaultReasoning: ReasoningEffort = ReasoningEffort.OFF,
    val reasoningMandatory: Boolean = false,
    val reasoningCanDisable: Boolean = false,
    val mediaReasoningSupported: Boolean = false,
    val mediaReasoningLabel: String = "当前接口未适配",
    val mediaReasoningEfforts: List<ReasoningEffort>? = null,
    val grokImage: Boolean = false,
)

internal data class ModelFeatureSelection(val enabled: Boolean, val providerId: String, val modelId: String)

internal enum class ReasoningEffort(val wireValue: String, val displayName: String, val rank: Int) {
    OFF("off", "关闭", 0), DEFAULT("default", "默认", 1), MINIMAL("minimal", "最小", 2),
    LOW("low", "低", 3), MEDIUM("medium", "中", 4), HIGH("high", "高", 5),
    XHIGH("xhigh", "超高", 6), MAX("max", "极高", 7);
    val enablesReasoning: Boolean get() = this != OFF
    companion object {
        fun fromWireValue(value: String?): ReasoningEffort? {
            val normalized = value?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wireValue == normalized } ?: when (normalized) {
                "none" -> OFF
                "x-high", "extra_high", "extra-high" -> XHIGH
                else -> null
            }
        }
    }
}

internal object ImageResolutionTier {
    val values = listOf("low", "medium", "high")
    fun label(value: String): String = when (value) { "low" -> "低"; "medium" -> "中"; "high" -> "高"; else -> value }
}

internal data class SubAgentModelConfig(
    val providerId: String,
    val providerName: String,
    val model: String,
    val modelDisplayName: String,
    val reasoningCapabilities: SubAgentReasoningCapabilities,
    val effectiveReasoningEffort: ReasoningEffort,
    val mediaReasoningSupported: Boolean,
    val mediaReasoningLabel: String,
    val mediaReasoningEfforts: List<ReasoningEffort>?,
    val grokImage: Boolean,
)
internal data class SubAgentReasoningCapabilities(
    val supportedEfforts: List<ReasoningEffort>, val mandatory: Boolean = false, val canDisable: Boolean = false,
) {
    val selectableEfforts: List<ReasoningEffort> get() = buildList {
        if (canDisable && !mandatory) add(ReasoningEffort.OFF)
        supportedEfforts.asSequence().filter { it != ReasoningEffort.OFF && it != ReasoningEffort.DEFAULT }
            .distinct().sortedBy(ReasoningEffort::rank).forEach(::add)
    }
    fun normalize(requested: ReasoningEffort): ReasoningEffort {
        val selectable = selectableEfforts
        if (requested in selectable) return requested
        if (requested == ReasoningEffort.OFF || requested == ReasoningEffort.DEFAULT) return when {
            canDisable && !mandatory -> ReasoningEffort.OFF
            supportedEfforts.isEmpty() && mandatory -> ReasoningEffort.DEFAULT
            else -> selectable.firstOrNull { it != ReasoningEffort.OFF } ?: ReasoningEffort.OFF
        }
        return selectable.filter { it != ReasoningEffort.OFF && it.rank <= requested.rank }.maxByOrNull(ReasoningEffort::rank)
            ?: selectable.firstOrNull { it != ReasoningEffort.OFF }
            ?: if (mandatory) ReasoningEffort.DEFAULT else ReasoningEffort.OFF
    }
}

internal fun buildSubAgentModelConfig(provider: SubAgentProvider, model: SubAgentModel) = SubAgentModelConfig(
    provider.id, provider.name, model.modelId, model.displayName,
    SubAgentReasoningCapabilities(model.reasoningEfforts, model.reasoningMandatory, model.reasoningCanDisable), model.defaultReasoning,
    model.mediaReasoningSupported, model.mediaReasoningLabel, model.mediaReasoningEfforts, model.grokImage,
)
internal fun supportsGptSpeedBinding(provider: SubAgentProvider?, model: SubAgentModel?): Boolean =
    provider?.isEnabled == true && model?.isEnabled == true && model.supportsGptSpeed &&
        !model.supportsImageGeneration && !model.supportsVideoGeneration && !model.supportsSpeechSynthesis

internal fun GptSpeedMode.next(): GptSpeedMode = GptSpeedMode.entries[(ordinal + 1) % GptSpeedMode.entries.size]

/** Only the profile UI's feature projection is kept, not any old request/runtime implementation. */
internal object SubAgentPreferences {
    const val PROFILES_KEY = "agent_child_profiles_v1"
    fun applyReasoning(profile: SubAgentProfile, config: SubAgentModelConfig): SubAgentModelConfig {
        if (profile.isMedia) return config.copy(effectiveReasoningEffort = profile.reasoning ?: config.effectiveReasoningEffort)
        val requested = profile.reasoning ?: return config
        return config.copy(effectiveReasoningEffort = config.reasoningCapabilities.normalize(requested))
    }
}
internal object MediaReasoningSettings {
    enum class Status { SUPPORTED, UNSUPPORTED }
    data class Result(val status: Status, val efforts: List<ReasoningEffort>, val label: String)
    fun resolve(config: SubAgentModelConfig, role: String): Result = Result(
        if (config.mediaReasoningSupported) Status.SUPPORTED else Status.UNSUPPORTED,
        if (config.mediaReasoningSupported) config.mediaReasoningEfforts ?: config.reasoningCapabilities.selectableEfforts else emptyList(),
        config.mediaReasoningLabel,
    )
}
