package app.daiyu.android.ui.components.subagent

import app.daiyu.android.delegation.MediaReasoningSettings
import app.daiyu.android.delegation.ReasoningEffort
import app.daiyu.android.delegation.SubAgentModelConfig
import app.daiyu.android.delegation.SubAgentPreferences
import app.daiyu.android.delegation.SubAgentProfile

internal data class SubAgentThinkingPreference(val effort: ReasoningEffort, val options: List<ReasoningEffort>)

/** Text chat choices are request preferences, not inferred model capabilities. */
internal fun subAgentThinkingPreference(profile: SubAgentProfile, config: SubAgentModelConfig): SubAgentThinkingPreference {
    if (!profile.isMedia) return SubAgentThinkingPreference(
        profile.reasoning ?: ReasoningEffort.DEFAULT,
        ReasoningEffort.entries.filter { it != ReasoningEffort.DEFAULT },
    )
    return SubAgentThinkingPreference(SubAgentPreferences.applyReasoning(profile, config).effectiveReasoningEffort,
        MediaReasoningSettings.resolve(config, profile.role).efforts)
}
