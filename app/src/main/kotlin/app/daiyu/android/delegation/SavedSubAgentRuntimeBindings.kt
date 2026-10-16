package app.daiyu.android.delegation

import app.daiyu.android.domain.ModelPort
import app.daiyu.android.domain.delegation.ChildModelDefinition
import app.daiyu.android.model.*
import app.daiyu.android.storage.ModelSettings

/** Explicit, conversation-scoped permission to use a saved credential for an exact selection. */
internal class SavedSubAgentRuntimeBindings(
    private val loadSettings: () -> ModelSettings,
    private val createModel: (ProviderSnapshot) -> ModelPort = ModelFactory::create
) {
    private class Approval(val profile: SubAgentProfile, val saved: ProviderSnapshot, val child: ProviderSnapshot)
    private val approvals = mutableMapOf<SubAgentConfigKey.Conversation, MutableMap<String, Approval>>()

    @Synchronized fun authorizeSavedModel(owner: SubAgentConfigKey.Conversation, profile: SubAgentProfile,
        expectedApiModel: String, provider: SubAgentProvider) {
        val owned = approvals.getOrPut(owner) { linkedMapOf() }
        owned.remove(profile.id)
        require(profile.enabled && profile.role == "review" && profile.imageResolution == null) { "Only text review children are supported" }
        require(profile.providerId.isNotBlank() && profile.modelId.isNotBlank() && provider.id == profile.providerId && provider.isEnabled)
        val selected = requireNotNull(provider.models.singleOrNull { it.id == profile.modelId }) { "Unknown child model selection" }
        require(selected.isEnabled && selected.modelId == expectedApiModel && expectedApiModel.isNotBlank()) { "Child model selection mismatch" }
        require(!selected.supportsImageGeneration && !selected.supportsVideoGeneration && !selected.supportsSpeechSynthesis) { "Media children are unsupported" }
        // Protocol-specific reasoning adapters are outside this first text-only stage.
        require(selected.defaultReasoning == ReasoningEffort.OFF &&
            (profile.reasoning ?: selected.defaultReasoning) == ReasoningEffort.OFF && !selected.reasoningMandatory &&
            selected.reasoningEfforts.isEmpty()) { "Child reasoning policy is not supported yet" }
        val saved = ModelFactory.snapshot(loadSettings())
        val options = saved.requestOptions
        val body = if (options.customBody.isEmpty()) org.json.JSONObject() else MessagesRequest.jsonObject(options.customBody, 64)
        require(!body.has("model") || body.get("model") == expectedApiModel) { "Saved custom model conflicts with child selection" }
        require(listOf("reasoning", "thinking", "reasoning_effort", "output_config", "service_tier").none(body::has)) { "Child reasoning policy is not supported yet" }
        val speed = profile.gptSpeedByModel[SubAgentProfile.modelReasoningKey(profile.providerId, profile.modelId)] ?: options.speedMode
        if (speed != null || options.codex) require(saved.protocol == ProviderProtocol.RESPONSES && selected.supportsGptSpeed &&
            GptServiceTier.isGptSpeedModel(expectedApiModel)) { "Child GPT policy is unsupported" }
        val child = ModelFactory.snapshot(saved.asModelSettings().copy(model = expectedApiModel,
            batteryEnabled = false, deviceStatusEnabled = false, requestOptions = options.copy(speedMode = speed)))
        val detached = profile.copy(reasoningByModel = profile.reasoningByModel.toMap(), gptSpeedByModel = profile.gptSpeedByModel.toMap())
        owned[profile.id] = Approval(detached, saved, child)
    }

    @Synchronized fun freeze(owner: SubAgentConfigKey.Conversation, config: ConversationSubAgentConfig,
        runModel: (ProviderSnapshot) -> ModelPort = createModel): Map<String, ChildModelDefinition> {
        val frozen = config.detached(); frozen.validate()
        require(frozen.enabled) { "Sub-agent configuration is disabled" }
        val enabled = frozen.profiles.filter { it.enabled }
        require(enabled.isNotEmpty()) { "No enabled child profiles" }
        val selected = enabled.map { profile ->
            requireNotNull(approvals[owner]?.get(profile.id)) { "Child profile has no explicit saved-model binding" }
                .also { require(profile == it.profile) { "Child profile changed after binding" } }
        }
        val current = ModelFactory.snapshot(loadSettings())
        require(selected.all { it.saved == current }) { "Saved model settings changed after binding" }
        return selected.associate { grant ->
            val model = SubAgentParallelModel(grant.profile.providerId, grant.child.model)
            grant.profile.id to ChildModelDefinition(frozen.poolKey(owner, model), frozen.parallelLimit(model)) { runModel(grant.child) }
        }
    }

    @Synchronized fun revoke(owner: SubAgentConfigKey.Conversation, profileId: String) { approvals[owner]?.remove(profileId) }
    @Synchronized fun clear() { approvals.clear() }
    override fun toString() = "SavedSubAgentRuntimeBindings(settings=<redacted>)"
}
