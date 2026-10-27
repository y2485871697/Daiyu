package app.daiyu.android.ui.components.subagent

import app.daiyu.android.delegation.MediaReasoningSettings
import app.daiyu.android.delegation.ReasoningEffort
import app.daiyu.android.delegation.SubAgentModelConfig
import app.daiyu.android.delegation.SubAgentPreferences
import app.daiyu.android.delegation.SubAgentProfile

internal data class SubAgentThinkingPreference(val effort: ReasoningEffort, val options: List<ReasoningEffort>)

/** The selected text effort and its choices share the request policy. */
internal fun subAgentThinkingPreference(profile: SubAgentProfile, config: SubAgentModelConfig): SubAgentThinkingPreference {
    if (!profile.isMedia) return SubAgentThinkingPreference(
        ReasoningEffort.textPreference(profile.reasoning, config.effectiveReasoningEffort, config.model),
        ReasoningEffort.textChoices(config.model),
    )
    return SubAgentThinkingPreference(SubAgentPreferences.applyReasoning(profile, config).effectiveReasoningEffort,
        MediaReasoningSettings.resolve(config, profile.role).efforts)
}
