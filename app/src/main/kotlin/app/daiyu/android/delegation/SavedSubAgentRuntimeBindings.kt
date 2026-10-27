package app.daiyu.android.delegation

import app.daiyu.android.domain.ModelPort
import app.daiyu.android.domain.delegation.ChildModelDefinition
import app.daiyu.android.domain.delegation.ChildTaskDisplay
import app.daiyu.android.model.*
import app.daiyu.android.storage.ModelSettings

internal class FrozenSubAgentModels(
    definitions: Map<String, ChildModelDefinition>, private val current: () -> Boolean,
    private val latest: (() -> FrozenSubAgentModels)? = null,
) : Map<String, ChildModelDefinition> by definitions {
    fun isCurrent(): Boolean = current()
    fun forNewRequests(): FrozenSubAgentModels = latest?.invoke() ?: this
}

/** Admission is frozen; compatible model edits are resolved only at a new request boundary. */
internal class SavedSubAgentRuntimeBindings(
    private val createModel: (ProviderSnapshot) -> ModelPort = ModelFactory::create,
) {
    private class Binding(val profile: SubAgentProfile, val saved: ProviderSnapshot,
        val child: ProviderSnapshot, val modelName: String)

    fun freeze(owner: SubAgentConfigKey.Conversation, config: ConversationSubAgentConfig,
        providers: List<SubAgentProvider>, resolveSavedModel: (String, String) -> ModelSettings,
        bindingsCurrent: () -> Boolean = { true },
        runModel: (ProviderSnapshot) -> ModelPort = createModel,
        latestConfig: (() -> ConversationSubAgentConfig)? = null,
        latestModel: ((String) -> ChildModelDefinition)? = null,
        latestModels: (() -> FrozenSubAgentModels)? = null,
        profileCurrent: (String) -> Boolean = { true }): FrozenSubAgentModels {
        val frozen = config.detached(); frozen.validate()
        val enabled = if (frozen.enabled) frozen.profiles.filter { it.enabled } else emptyList()
        val selected = enabled.map { profile ->
            val provider = requireNotNull(providers.singleOrNull { it.id == profile.providerId && it.isEnabled }) {
                "Saved child provider is unavailable"
            }
            require(profile.role == "review") { "Only text children are supported" }
            val model = requireNotNull(provider.models.singleOrNull { it.id == profile.modelId && it.isEnabled }) {
                "Saved child model is unavailable"
            }
            require(model.modelId.isNotBlank() && !model.supportsImageGeneration &&
                !model.supportsVideoGeneration && !model.supportsSpeechSynthesis) { "Media children are unsupported" }
            val effort = ReasoningEffort.textPreference(profile.reasoning, model.defaultReasoning, model.modelId)
            if (model.reasoningMandatory && !effort.enablesReasoning)
                throw app.daiyu.android.domain.ModelRequestException(app.daiyu.android.domain.ModelFailure.CONFIGURATION,
                    app.daiyu.android.domain.ModelRequestDiagnostic(
                        rejection = app.daiyu.android.domain.RequestRejection.UNSUPPORTED_VALUE,
                        field = app.daiyu.android.domain.RequestField.REASONING,
                        unsupportedReasoningEffort = app.daiyu.android.domain.RequestReasoningEffort.OFF))
            val saved = ModelFactory.snapshot(resolveSavedModel(profile.providerId, profile.modelId))
            require(saved.model == model.modelId) { "Saved model record changed" }
            val options = saved.requestOptions
            val body = if (options.customBody.isEmpty()) org.json.JSONObject() else MessagesRequest.jsonObject(options.customBody, 64)
            require(!body.has("model") || body.get("model") == model.modelId) { "Saved custom model conflicts with child selection" }
            require(!body.has("service_tier")) { "Child service tier must use its configured speed" }
            val speed = profile.gptSpeedByModel[SubAgentProfile.modelReasoningKey(profile.providerId, profile.modelId)] ?: options.speedMode
            if (speed != null || options.codex) require(saved.protocol == ProviderProtocol.RESPONSES &&
                model.supportsGptSpeed && GptServiceTier.isGptSpeedModel(model.modelId)) { "Child GPT policy is unsupported" }
            val childBase = ModelFactory.snapshot(saved.asModelSettings().copy(batteryEnabled = false, deviceStatusEnabled = false,
                usageIdentity = app.daiyu.android.model.UsageModelIdentity(provider.id, provider.name),
                requestOptions = options.copy(speedMode = speed, openAiWebSearchEnabled = false)))
            Binding(profile, saved, ReasoningRequestPolicy.freeze(childBase, effort), model.displayName.ifBlank { saved.model })
        }
        val current = {
            bindingsCurrent() && selected.all { binding ->
                ModelFactory.snapshot(resolveSavedModel(binding.profile.providerId, binding.profile.modelId)) == binding.saved
            }
        }
        require(current()) { "Saved provider or model record changed during preparation" }
        val definitions = selected.associate { binding ->
            val model = SubAgentParallelModel(binding.profile.providerId, binding.child.model)
            val display = ChildTaskDisplay(binding.profile.name, binding.child.model, binding.modelName)
            val original = ChildModelDefinition(frozen.poolKey(owner, model), frozen.parallelLimit(model), display,
                latest = null, selection = binding.child, valid = { profileCurrent(binding.profile.id) }) { runModel(binding.child) }
            binding.profile.id to if (latestConfig == null || latestModel == null) original else
                ChildModelDefinition(original.poolKey, original.parallelLimit, display,
                    latest = {
                        val next = latestConfig()
                        check(next.profiles.singleOrNull { it.id == binding.profile.id }?.role == binding.profile.role) {
                            "child_profile_removed_or_role_changed"
                        }
                        val selected = latestModel(binding.profile.id)
                        check(latestConfig() == next) { "child_model_configuration_changed" }
                        selected
                    }, selection = binding.child, valid = { profileCurrent(binding.profile.id) }) { runModel(binding.child) }
        }
        return FrozenSubAgentModels(definitions, current, latest = if (latestConfig == null || latestModels == null) null else ({
            val next = latestConfig()
            val selected = latestModels()
            check(latestConfig() == next) { "child_model_configuration_changed" }
            selected
        }))
    }
}
