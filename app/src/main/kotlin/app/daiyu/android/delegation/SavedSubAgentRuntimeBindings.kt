package app.daiyu.android.delegation

import app.daiyu.android.domain.ModelPort
import app.daiyu.android.domain.delegation.ChildModelDefinition
import app.daiyu.android.domain.delegation.ChildModelRole
import app.daiyu.android.domain.delegation.ChildTaskDisplay
import app.daiyu.android.model.*
import app.daiyu.android.storage.ModelSettings

internal class FrozenSubAgentModels(
    definitions: Map<String, ChildModelDefinition>, private val current: () -> Boolean,
    private val latest: (() -> FrozenSubAgentModels)? = null,
    val descriptors: List<SubAgentDescriptor> = emptyList(),
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
        profileCurrent: (String) -> Boolean = { true },
        modelDeleted: (SubAgentProfile) -> Boolean = { false },
        currentLifetime: ((String) -> Long)? = null,
        runtimeStart: ConversationSubAgentPreferences.RuntimeStart? = null,
        createContext: ((ProviderSnapshot, app.daiyu.android.domain.Message) -> app.daiyu.android.domain.runtime.RunContextRuntime?)? = null): FrozenSubAgentModels {
        val frozen = config.detached(); frozen.validate()
        val enabled = if (frozen.enabled) frozen.profiles else emptyList()
        val selected = enabled.map { profile ->
            val provider = requireNotNull(providers.singleOrNull { it.id == profile.providerId && it.isEnabled }) {
                "Saved child provider is unavailable"
            }
            require(profile.role in setOf("implementation", "review")) { "Only text children are supported" }
            val model = requireNotNull(provider.models.singleOrNull { it.id == profile.modelId && it.isEnabled }) {
                "Saved child model is unavailable"
            }
            require(model.modelId.isNotBlank() && !model.supportsImageGeneration &&
                !model.supportsVideoGeneration && !model.supportsSpeechSynthesis) { "Media children are unsupported" }
            val effort = ReasoningEffort.textPreference(profile.reasoning, model.defaultReasoning, model.modelId)
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
                requestOptions = options.copy(speedMode = speed, preferNativeSearch = false)))
            Binding(profile, saved, ReasoningRequestPolicy.freeze(childBase, effort), model.displayName.ifBlank { saved.model })
        }
        val current = {
            bindingsCurrent() && selected.all { binding ->
                ModelFactory.snapshot(resolveSavedModel(binding.profile.providerId, binding.profile.modelId)) == binding.saved
            }
        }
        require(current()) { "Saved provider or model record changed during preparation" }
        fun identity(providerId: String, saved: ProviderSnapshot): String {
            val route = listOf(providerId, saved.protocol.wireName,
                saved.endpoint, saved.responsesPath, saved.model,
                saved.customHeaders.encode(), saved.requestOptions.customBody).joinToString("\u0000")
            return java.security.MessageDigest.getInstance("SHA-256").digest(route.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
        val definitions = selected.associate { binding ->
            val model = SubAgentParallelModel(binding.profile.providerId, binding.child.model)
            val display = ChildTaskDisplay(binding.profile.name, binding.child.model, binding.modelName)
            val original = ChildModelDefinition(frozen.poolKey(owner, binding.profile.id), frozen.parallelLimit(binding.profile.id, model), display,
                latest = null, selection = binding.child, valid = { profileCurrent(binding.profile.id) },
                admissionAllowed = { !modelDeleted(binding.profile) },
                role = if (binding.profile.role == "implementation") ChildModelRole.IMPLEMENTATION else ChildModelRole.REVIEW) { runModel(binding.child) }
                .apply { if (createContext != null) withContext { initial -> createContext(binding.child, initial) } }
                .identified(binding.profile.id, identity(binding.profile.providerId, binding.saved), frozen.presetApplicationToken)
                .routed(binding.profile.providerId, binding.child.model)
            fun taskDefinition(): ChildModelDefinition {
                val lock = Any()
                var assigned = binding.profile
                var last = original
                val guard = runtimeStart?.let { start -> CommittedProfileBinding(start, binding.profile) { previous, candidate ->
                    runCatching {
                        fun modelOf(profile: SubAgentProfile) =
                            ModelFactory.snapshot(resolveSavedModel(profile.providerId, profile.modelId)).model
                        val previousModel = runCatching { modelOf(previous) }.getOrDefault(last.requestModel?.modelId ?: binding.child.model)
                        previousModel == modelOf(candidate)
                    }.getOrDefault(false)
                } }
                var lifetime = currentLifetime?.invoke(assigned.id)
                var application = frozen.presetApplicationToken
                var revoked = false
                var eligibleIds = frozen.profiles.map { it.id }.toSet()
                var offeredIds = setOf(binding.profile.id)
                var observedConfig = frozen
                fun currentBinding(): Boolean = synchronized(lock) {
                    if (revoked) return@synchronized false
                    if (guard != null) {
                        val view = guard.current() ?: run { revoked = true; return@synchronized false }
                        assigned = view.profile
                        return@synchronized true
                    }
                    val next = latestConfig?.invoke() ?: return@synchronized profileCurrent(binding.profile.id)
                    val switchingPreset = next.presetApplicationToken != application
                    val stable = next.profiles.singleOrNull { it.id == assigned.id }?.role == binding.profile.role &&
                        currentLifetime?.invoke(assigned.id) == lifetime &&
                        (currentLifetime != null || profileCurrent(assigned.id))
                    if (!switchingPreset && stable) {
                        if (next != observedConfig) offeredIds = setOf(assigned.id)
                        observedConfig = next
                        return@synchronized true
                    }
                    val compatible = next.profiles.filter {
                        it.role == binding.profile.role && (switchingPreset || it.id in eligibleIds) &&
                            (switchingPreset || it.id != assigned.id)
                    }.mapNotNull { profile ->
                        runCatching { latestModel?.invoke(profile.id) }.getOrNull()?.takeIf {
                            it.role == original.role && it.isValid() && it.acceptsNewTask()
                        }?.let { profile to it }
                    }
                    val matching = compatible.filter { it.second.requestModel?.modelId == last.requestModel?.modelId }
                    val choices = matching.ifEmpty { compatible }
                    val replacement = choices.firstOrNull { it.first.id == assigned.id } ?: choices.firstOrNull()
                        ?: run { revoked = true; return@synchronized false }
                    check(latestConfig.invoke() == next) { "child_model_configuration_changed" }
                    if (switchingPreset) eligibleIds = next.profiles.map { it.id }.toSet()
                    offeredIds = choices.map { it.first.id }.toSet()
                    assigned = replacement.first
                    last = replacement.second
                    lifetime = currentLifetime?.invoke(assigned.id)
                    application = next.presetApplicationToken
                    observedConfig = next
                    true
                }

                fun deletedSelection(): Boolean = synchronized(lock) {
                    val next = latestConfig?.invoke()?.profiles?.singleOrNull { it.id == assigned.id }
                    modelDeleted(assigned) || (next != null && modelDeleted(next))
                }
                if (latestConfig == null || latestModel == null) return original
                return ChildModelDefinition(original.poolKey, original.parallelLimit, display,
                        latest = { synchronized(lock) {
                            check(currentBinding()) { "child_profile_removed_or_role_changed" }
                            if (deletedSelection()) last else try {
                                val guardBefore = guard?.current()
                                val next = latestConfig()
                                val profile = checkNotNull(next.profiles.singleOrNull { it.id == assigned.id })
                                check(profile.role == binding.profile.role) { "child_profile_removed_or_role_changed" }
                                val resolved = latestModel(profile.id)
                                check(latestConfig() == next && (if (guard != null) guard.current()?.revision == guardBefore?.revision
                                    else currentLifetime?.invoke(profile.id) == lifetime && (currentLifetime != null || profileCurrent(profile.id)))) {
                                    "child_model_configuration_changed"
                                }
                                assigned = profile
                                resolved.also { last = it }
                            } catch (failure: Exception) {
                                if (deletedSelection()) last else throw failure
                            }
                        } }, selection = binding.child, valid = ::currentBinding,
                        admissionAllowed = { !deletedSelection() }, role = original.role) { runModel(binding.child) }
                        .apply { if (createContext != null) withContext { initial -> createContext(binding.child, initial) } }
                        .identified(binding.profile.id, identity(binding.profile.providerId, binding.saved), frozen.presetApplicationToken)
                    .routed(binding.profile.providerId, binding.child.model)
                    .scheduling(candidates = { synchronized(lock) {
                        check(currentBinding()) { "child_profile_removed_or_role_changed" }
                        if (deletedSelection()) listOf(last) else {
                            val view = guard?.current()
                            check(guard == null || view != null) { "child_profile_removed_or_role_changed" }
                            val next = view?.config ?: latestConfig()
                            val admitted = view?.candidates ?: next.profiles.filter {
                                it.id in offeredIds && it.role == binding.profile.role
                            }
                            val resolved = admitted.mapNotNull { profile ->
                                runCatching { latestModel(profile.id) }.getOrNull()?.takeIf {
                                    it.role == original.role && it.isValid() && it.acceptsNewTask()
                                }?.let { profile to it }
                            }
                            check(latestConfig() == next) { "child_model_configuration_changed" }
                            if (guard != null) resolved.map { it.second } else {
                                val matching = resolved.filter { it.second.requestModel?.modelId == last.requestModel?.modelId }
                                matching.ifEmpty { resolved }.map { it.second }
                            }
                        }
                    } }, select = { candidate -> synchronized(lock) {
                        if (!currentBinding()) return@synchronized false
                        if (deletedSelection()) return@synchronized candidate.sameSelection(last)
                        val next = latestConfig()
                        val view = guard?.current()
                        if (guard != null && view == null) return@synchronized false
                        val profile = (view?.candidates ?: next.profiles.filter { it.id in offeredIds })
                            .singleOrNull { it.id == candidate.profileId && it.role == binding.profile.role }
                            ?: return@synchronized false
                        if (!candidate.sameSelection(latestModel(profile.id)) || latestConfig() != next) return@synchronized false
                        if (guard != null && !guard.select(profile.id, checkNotNull(view).revision)) return@synchronized false
                        assigned = profile
                        lifetime = currentLifetime?.invoke(profile.id)
                        last = candidate
                        true
                    } })
            }
            binding.profile.id to taskDefinition().taskScoped(::taskDefinition)
        }
        return FrozenSubAgentModels(definitions, current, latest = if (latestConfig == null || latestModels == null) null else ({
            val next = latestConfig()
            val selected = latestModels()
            check(latestConfig() == next) { "child_model_configuration_changed" }
            selected
        }), descriptors = selected.map { binding ->
            SubAgentDescriptor(binding.profile.id, binding.profile.name, binding.profile.role,
                binding.profile.tier.takeIf { binding.profile.supportsTaskTier }, binding.child.model, binding.modelName)
        })
    }
}
