package app.daiyu.android.ui.components

import androidx.compose.runtime.*
import app.daiyu.android.delegation.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect

internal data class SubAgentParallelLimitChange(val model: SubAgentParallelModel, val value: Int, val expected: Int)
internal sealed interface SubAgentEditorState {
    data object Loading : SubAgentEditorState
    data class Loaded(val config: ConversationSubAgentConfig) : SubAgentEditorState
    data class Error(val reason: String) : SubAgentEditorState
}

/** Capture this object when opening a picker: callbacks must never retarget the currently selected owner. */
internal class ConversationSubAgentEditor(
    val owner: SubAgentConfigKey,
    val repository: ConversationSubAgentPreferences,
    private val providerLookup: suspend (String) -> SubAgentProvider?,
    val canEdit: () -> Boolean,
) {
    constructor(owner: SubAgentConfigKey, repository: ConversationSubAgentPreferences,
        modelSource: SubAgentModelSource, canEdit: () -> Boolean) :
        this(owner, repository, { id -> modelSource.providers.value.singleOrNull { it.id == id } }, canEdit) {
        this.modelSource = modelSource
    }
    private var modelSource: SubAgentModelSource? = null
    val modelRefreshing get() = modelSource?.refreshing ?: SubAgentModelSource.Empty.refreshing
    val modelDisplayProviders get() = modelSource?.displayProviders
    private class LostOwner : RuntimeException()
    private var storedState: SubAgentEditorState by mutableStateOf(SubAgentEditorState.Loading)
    private var lifecycleFailure: (() -> String?)? = null
    val state: SubAgentEditorState get() = lifecycleFailure?.invoke()?.let { SubAgentEditorState.Error(it) } ?: storedState
    private var retryVersion by mutableIntStateOf(0)
    private var disposed = false
    val isDisposed: Boolean get() = disposed
    val enabled: Boolean get() = !disposed && state is SubAgentEditorState.Loaded && canEdit()
    fun dispose() { disposed = true }
    fun scoped(applicationToken: String?, gate: () -> Boolean = { true }): ConversationSubAgentEditor {
        val root = this
        return ConversationSubAgentEditor(owner, repository, providerLookup,
            { root.enabled && gate() && root.applicationMatches(applicationToken) }).also { it.modelSource = modelSource }
    }
    private fun applicationMatches(token: String?): Boolean = try { repository.snapshot(owner).presetApplicationToken == token } catch (_: Exception) { false }
    fun applyPreset(id: String, canApply: () -> Boolean = { true }): ConversationSubAgentPreferences.WriteResult {
        if (!enabled || !canApply()) return ConversationSubAgentPreferences.WriteResult.Rejected
        return try {
            repository.applyPreset(owner, id) { enabled && canApply() }.also { result ->
                if (result is ConversationSubAgentPreferences.WriteResult.Saved) storedState = SubAgentEditorState.Loaded(result.config)
            }
        } catch (failure: Exception) { fail(failure); ConversationSubAgentPreferences.WriteResult.Rejected }
    }
    private fun fail(failure: Exception) {
        if (failure is CancellationException) throw failure
        storedState = SubAgentEditorState.Error(failure.message ?: failure.javaClass.simpleName)
    }
    private var lifecycleRecovery: (() -> Boolean)? = null
    init { try { storedState = SubAgentEditorState.Loaded(repository.snapshot(owner)) } catch (failure: Exception) { fail(failure) } }
    fun bindLifecycleState(reason: () -> String?, recovery: () -> Boolean) { lifecycleFailure = reason; lifecycleRecovery = recovery }
    fun retry() {
        try {
            check(repository.recoverDurability()) { "子代理配置恢复失败；请重试" }
            if (lifecycleFailure?.invoke() != null) check(lifecycleRecovery?.invoke() == true) { "会话配置尚未恢复，原配置已保留" }
            storedState = SubAgentEditorState.Loading; retryVersion++
        } catch (failure: Exception) { fail(failure) }
    }
    @Composable fun observe(): SubAgentEditorState {
        val version = retryVersion
        LaunchedEffect(this, version) {
            if (state is SubAgentEditorState.Error) return@LaunchedEffect
            try {
                storedState = SubAgentEditorState.Loaded(repository.snapshot(owner))
                repository.flow(owner).collect { config -> if (state !is SubAgentEditorState.Error) storedState = SubAgentEditorState.Loaded(config) }
            } catch (failure: Exception) { fail(failure) }
        }
        return state
    }
    fun update(change: (ConversationSubAgentConfig) -> ConversationSubAgentConfig): ConversationSubAgentPreferences.WriteResult {
        if (!enabled) return ConversationSubAgentPreferences.WriteResult.Rejected
        val observedToken = (state as? SubAgentEditorState.Loaded)?.config?.presetApplicationToken
        return try { repository.update(owner) { old ->
            if (!enabled || old.presetApplicationToken != observedToken) throw LostOwner()
            change(old)
        } } catch (_: LostOwner) { ConversationSubAgentPreferences.WriteResult.Rejected }
        catch (failure: Exception) { fail(failure); ConversationSubAgentPreferences.WriteResult.Rejected }
    }
    private val resolvedBindings = mutableMapOf<String, SubAgentParallelModel>()
    fun newProfileDraft(): SubAgentProfile {
        val current = repository.previewSnapshot(owner)
        var number = current.profiles.size + 1
        while (current.profiles.any { it.name == "子代理 $number" }) number++
        return SubAgentProfile(UUID.randomUUID().toString(), "子代理 $number", parallelLimit = 1)
    }
    fun changeProfileModel(profile: SubAgentProfile, selection: ModelFeatureSelection): SubAgentProfile {
        require(selection.providerId.isNotBlank() && selection.modelId.isNotBlank()) { "A model selection is required" }
        val blank = profile.copy(providerId = selection.providerId, modelId = selection.modelId,
            tier = null, reasoning = null, imageResolution = null, reasoningByModel = emptyMap(), gptSpeedByModel = emptyMap())
        return repository.modelDefaults(blank)?.restore(blank) ?: blank
    }
    fun rememberedParallelLimit(profile: SubAgentProfile): Int? = profile.parallelLimit
    suspend fun commitProfileDraft(draft: SubAgentProfile, expectedProfile: SubAgentProfile?, expectedApplicationToken: String?,
        parallelLimitChange: SubAgentParallelLimitChange?, canCommit: () -> Boolean = { true }): ConversationSubAgentPreferences.WriteResult {
        if (!enabled || !canCommit()) return ConversationSubAgentPreferences.WriteResult.Rejected
        return try {
            val capturedOwnerState = repository.ownerState(owner)
            if (owner is SubAgentConfigKey.Draft && !capturedOwnerState.exists) throw LostOwner()
            if (draft.providerId.isBlank() || draft.modelId.isBlank()) throw LostOwner()
            val binding = run {
                val provider = providerLookup(draft.providerId)?.takeIf { it.id == draft.providerId && it.isEnabled } ?: throw LostOwner()
                currentCoroutineContext().ensureActive()
                val model = provider.models.singleOrNull { it.id == draft.modelId && it.isEnabled } ?: throw LostOwner()
                if (model.modelId.isBlank() || model.supportsSpeechSynthesis || !draft.acceptsModel(model.supportsImageGeneration, model.supportsVideoGeneration)) throw LostOwner()
                SubAgentParallelModel(provider.id, model.modelId)
            }
            if (parallelLimitChange != null && (parallelLimitChange.model != binding || parallelLimitChange.value < 0 || parallelLimitChange.expected < 0)) throw LostOwner()
            repository.updateConfirmedProfile(owner, draft.id, binding, { enabled && canCommit() }, expectedOwnerState = capturedOwnerState) { old ->
                if (!enabled || !canCommit() || old.presetApplicationToken != expectedApplicationToken) throw LostOwner()
                val current = old.profiles.singleOrNull { it.id == draft.id }
                if (expectedProfile == null) { if (current != null || old.profiles.any { it.name == draft.name }) throw LostOwner() }
                else if (expectedProfile.id != draft.id || current != expectedProfile) throw LostOwner()
                if (parallelLimitChange != null && old.parallelLimit(draft.id, parallelLimitChange.model) != parallelLimitChange.expected) throw LostOwner()
                val confirmed = draft.copy(parallelLimit = parallelLimitChange?.value ?: draft.parallelLimit ?: old.parallelLimit(draft.id, binding))
                old.copy(profiles = if (expectedProfile == null) old.profiles + confirmed else old.profiles.map { if (it.id == draft.id) confirmed else it })
            }.also { result -> if (result is ConversationSubAgentPreferences.WriteResult.Saved) {
                resolvedBindings[SubAgentProfile.modelReasoningKey(draft.providerId, draft.modelId)] = binding
                storedState = SubAgentEditorState.Loaded(result.config)
            } }
        } catch (_: LostOwner) { ConversationSubAgentPreferences.WriteResult.Rejected }
        catch (failure: Exception) { fail(failure); ConversationSubAgentPreferences.WriteResult.Rejected }
    }
    fun removeProfileDraft(expectedProfile: SubAgentProfile, expectedApplicationToken: String?,
        canCommit: () -> Boolean = { true }): ConversationSubAgentPreferences.WriteResult {
        if (!enabled || !canCommit()) return ConversationSubAgentPreferences.WriteResult.Rejected
        return try {
            repository.removeProfile(owner, expectedProfile, expectedApplicationToken) { enabled && canCommit() }.also { result ->
                if (result is ConversationSubAgentPreferences.WriteResult.Saved) storedState = SubAgentEditorState.Loaded(result.config)
            }
        } catch (failure: Exception) { fail(failure); ConversationSubAgentPreferences.WriteResult.Rejected }
    }
    fun add(): ConversationSubAgentPreferences.WriteResult { val draft = newProfileDraft(); return update { old -> old.copy(profiles = old.profiles + draft) } }
    private fun confirmedUpdate(id: String, binding: SubAgentParallelModel? = null,
        change: (ConversationSubAgentConfig) -> ConversationSubAgentConfig): ConversationSubAgentPreferences.WriteResult {
        if (!enabled) return ConversationSubAgentPreferences.WriteResult.Rejected
        val token = (state as? SubAgentEditorState.Loaded)?.config?.presetApplicationToken
        return try { repository.updateConfirmedProfile(owner, id, binding, { enabled }) { old ->
            if (!enabled || old.presetApplicationToken != token) throw LostOwner(); change(old)
        }.also { result -> if (result is ConversationSubAgentPreferences.WriteResult.Saved) storedState = SubAgentEditorState.Loaded(result.config) }
        } catch (_: LostOwner) { ConversationSubAgentPreferences.WriteResult.Rejected }
        catch (failure: Exception) { fail(failure); ConversationSubAgentPreferences.WriteResult.Rejected }
    }
    fun updateProfile(id: String, change: (SubAgentProfile) -> SubAgentProfile): ConversationSubAgentPreferences.WriteResult = confirmedUpdate(id) { old ->
        if (old.profiles.none { it.id == id }) throw LostOwner()
        old.copy(profiles = old.profiles.map { profile -> if (profile.id != id) profile else change(profile).normalizedTaskTier().also { require(it.id == id) } })
    }
    fun remove(id: String) = update { old ->
        if (old.profiles.none { it.id == id }) throw LostOwner(); old.copy(profiles = old.profiles.filterNot { profile -> profile.id == id })
    }
    fun setEnabled(value: Boolean) = update { it.copy(enabled = value) }
    fun saveParallelLimit(expectedProfile: SubAgentProfile, apiModel: String, limit: Int,
        expectedApplicationToken: String?, expectedLimit: Int): ConversationSubAgentPreferences.WriteResult {
        val id = expectedProfile.id; val providerId = expectedProfile.providerId; val modelId = expectedProfile.modelId
        if (limit < 0 || expectedLimit < 0 || providerId.isBlank() || apiModel.isBlank()) return ConversationSubAgentPreferences.WriteResult.Rejected
        val binding = SubAgentParallelModel(providerId, apiModel)
        return confirmedUpdate(id, binding) { old ->
            val profile = old.profiles.singleOrNull { it.id == id } ?: throw LostOwner()
            if (profile != expectedProfile || old.presetApplicationToken != expectedApplicationToken ||
                old.parallelLimit(id, binding) != expectedLimit) throw LostOwner()
            val known = resolvedBindings[SubAgentProfile.modelReasoningKey(providerId, modelId)] ?: repository.modelDefaults(profile)?.apiModel?.let { SubAgentParallelModel(providerId, it) }
            if (known != null && known != binding) throw LostOwner()
            old.copy(profiles = old.profiles.map { if (it.id == id) it.copy(parallelLimit = limit) else it })
        }.also { result -> if (result is ConversationSubAgentPreferences.WriteResult.Saved) resolvedBindings[SubAgentProfile.modelReasoningKey(providerId, modelId)] = binding }
    }
    suspend fun cycleGptSpeed(id: String, providerId: String, modelId: String, expected: SubAgentProfile? = null): ConversationSubAgentPreferences.WriteResult {
        if (!enabled) return ConversationSubAgentPreferences.WriteResult.Rejected
        return try {
            val capturedConfig = repository.snapshot(owner); val capturedToken = capturedConfig.presetApplicationToken
            if ((state as? SubAgentEditorState.Loaded)?.config?.presetApplicationToken != capturedToken) throw LostOwner()
            val captured = capturedConfig.profiles.singleOrNull { it.id == id } ?: throw LostOwner()
            if (captured.providerId != providerId || captured.modelId != modelId || (expected != null && captured != expected)) throw LostOwner()
            val resolvedProvider = providerLookup(providerId); currentCoroutineContext().ensureActive()
            val provider = resolvedProvider?.takeIf { it.id == providerId } ?: throw LostOwner()
            val model = provider.models.singleOrNull { it.id == modelId } ?: throw LostOwner()
            if (!enabled || !supportsGptSpeedBinding(provider, model)) throw LostOwner()
            val binding = SubAgentParallelModel(providerId, model.modelId)
            confirmedUpdate(id, binding) { config ->
                if (config.presetApplicationToken != capturedToken) throw LostOwner()
                val old = config.profiles.singleOrNull { it.id == id } ?: throw LostOwner()
                if (old != (expected ?: captured) || old.providerId != providerId || old.modelId != modelId || !supportsGptSpeedBinding(provider, model)) throw LostOwner()
                config.copy(profiles = config.profiles.map { profile -> if (profile.id != id) profile else old.copy(gptSpeedByModel = old.gptSpeedByModel + (SubAgentProfile.modelReasoningKey(providerId, modelId) to old.gptSpeedForModel(fallback = model.defaultGptSpeed).next())) })
            }.also { result -> if (result is ConversationSubAgentPreferences.WriteResult.Saved) resolvedBindings[SubAgentProfile.modelReasoningKey(providerId, modelId)] = binding }
        } catch (_: LostOwner) { ConversationSubAgentPreferences.WriteResult.Rejected }
        catch (failure: Exception) { fail(failure); ConversationSubAgentPreferences.WriteResult.Rejected }
    }
    fun saveModel(id: String, selection: ModelFeatureSelection, expected: SubAgentProfile? = null) = confirmedUpdate(id) { config ->
        if (selection.providerId.isBlank() || selection.modelId.isBlank()) throw LostOwner()
        val old = config.profiles.singleOrNull { it.id == id } ?: throw LostOwner()
        if (expected != null && old != expected) throw LostOwner()
        if (old.providerId == selection.providerId && old.modelId == selection.modelId) config else {
            val previousBinding = resolvedBindings[SubAgentProfile.modelReasoningKey(old.providerId, old.modelId)]
                ?: modelSource?.providers?.value?.singleOrNull { it.id == old.providerId }?.models
                    ?.singleOrNull { it.id == old.modelId }?.modelId?.takeIf { it.isNotBlank() }
                    ?.let { SubAgentParallelModel(old.providerId, it) }
                ?: repository.modelDefaults(old)?.apiModel?.let { SubAgentParallelModel(old.providerId, it) }
            val limit = old.parallelLimit ?: previousBinding?.let { config.parallelLimit(old.id, it) } ?: 1
            val restored = changeProfileModel(old, selection).copy(parallelLimit = limit)
            config.copy(profiles = config.profiles.map { if (it.id == id) restored else it })
        }
    }
}
internal val LocalConversationSubAgentEditor = compositionLocalOf<ConversationSubAgentEditor?> { null }
