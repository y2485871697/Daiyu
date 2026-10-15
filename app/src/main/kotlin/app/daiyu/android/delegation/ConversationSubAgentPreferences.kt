package app.daiyu.android.delegation

import android.content.SharedPreferences
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

internal sealed class SubAgentConfigKey {
    abstract val value: String
    data class Conversation(override val value: String) : SubAgentConfigKey() { init { require(value.isNotBlank()) } }
    data class Draft(override val value: String) : SubAgentConfigKey() { init { require(value.isNotBlank()) } }
    data class Preset(override val value: String) : SubAgentConfigKey() { init { require(value.isNotBlank()) } }
}
internal data class SubAgentParallelModel(val providerId: String, val apiModel: String) {
    init { require(providerId.isNotBlank() && apiModel.isNotBlank()) }
    fun legacyKey(): String = "agent_model_parallel_" + MessageDigest.getInstance("SHA-256")
        .digest((providerId + "\u0000" + apiModel).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
internal data class ConversationSubAgentConfig(
    val profiles: List<SubAgentProfile>,
    val enabled: Boolean = true,
    val parallelLimits: Map<SubAgentParallelModel, Int> = emptyMap(),
    val legacyParallelLimits: Map<String, Int> = emptyMap(),
    val appliedPresetId: String? = null,
    val appliedPresetName: String? = null,
    val presetApplicationToken: String? = null,
) {
    fun detached(): ConversationSubAgentConfig = copy(
        profiles = profiles.map { it.copy(reasoningByModel = it.reasoningByModel.toMap(), gptSpeedByModel = it.gptSpeedByModel.toMap()) }.toList(),
        parallelLimits = parallelLimits.toMap(), legacyParallelLimits = legacyParallelLimits.toMap())
    fun parallelLimit(model: SubAgentParallelModel): Int = parallelLimits[model] ?: legacyParallelLimits[model.legacyKey()] ?: 1
    fun validate() {
        require(profiles.map { it.id }.distinct().size == profiles.size) { "Duplicate profile IDs" }
        profiles.forEach { profile ->
            require(profile.role == "implementation" || profile.tier == null)
            require(profile.imageResolution == null || profile.imageResolution in ImageResolutionTier.values) { "Invalid profile image resolution" }
            require(profile.reasoningByModel.keys.all { key ->
                val parts = key.split('\u0000'); parts.size == 2 && parts.all { it.isNotBlank() }
            }) { "Invalid reasoning-memory model key" }
            require(profile.gptSpeedByModel.keys.all { key ->
                val parts = key.split('\u0000'); parts.size == 2 && parts.all { it.isNotBlank() }
            }) { "Invalid GPT-speed-memory model key" }
        }
        require(listOf(appliedPresetId, appliedPresetName, presetApplicationToken).all { it == null || it.isNotBlank() }) { "Invalid preset application metadata" }
        require(parallelLimits.values.all { it >= 0 })
        require(legacyParallelLimits.all { (key, limit) -> key.matches(Regex("agent_model_parallel_[0-9a-f]{64}")) && limit >= 0 })
    }
    fun poolKey(owner: SubAgentConfigKey, model: SubAgentParallelModel): String =
        "subagent:v1:" + when (owner) {
            is SubAgentConfigKey.Conversation -> "c:"
            is SubAgentConfigKey.Draft -> "d:"
            is SubAgentConfigKey.Preset -> error("Presets cannot own runtime pools")
        } + Base64.getUrlEncoder().withoutPadding().encodeToString(owner.value.toByteArray(Charsets.UTF_8)) + ":" +
            Base64.getUrlEncoder().withoutPadding().encodeToString(model.providerId.toByteArray(Charsets.UTF_8)) + ":" +
            Base64.getUrlEncoder().withoutPadding().encodeToString(model.apiModel.toByteArray(Charsets.UTF_8))
}

/** Instances using the same preferences share a lock, durability fence, and per-owner versions. */
internal class ConversationSubAgentPreferences(
    private val preferences: SharedPreferences,
    private val canEdit: (SubAgentConfigKey) -> Boolean = { true },
) {
    companion object {
        internal const val SEED_KEY = "agent_conversation_child_seed_v1"
        internal const val OWNER_PREFIX = "agent_conversation_child_owner_v1_"
        internal const val BIND_PREFIX = "agent_conversation_child_binding_v1_"
        internal const val RESET_MARKER_KEY = "agent_subagent_configuration_reset_v2"
        internal const val UI_DRAFT_KEY = "agent_conversation_child_ui_draft_v1"
        private const val VERSION = 1
        private val lock = Any()
        private class SharedState {
            val changes = MutableStateFlow(0L)
            val owners = mutableMapOf<SubAgentConfigKey, MutableStateFlow<Long>>()
            var pending: Map<String, Any?>? = null
        }
        private val states = java.util.WeakHashMap<SharedPreferences, SharedState>()
        private fun stateFor(preferences: SharedPreferences): SharedState = synchronized(lock) { states.getOrPut(preferences) { SharedState() } }
    }
    sealed class WriteResult {
        data class Saved(val revision: Long, val config: ConversationSubAgentConfig) : WriteResult()
        object Rejected : WriteResult()
    }
    private val state = stateFor(preferences)
    val revision: StateFlow<Long> get() = state.changes
    fun revision(owner: SubAgentConfigKey): StateFlow<Long> = synchronized(lock) { state.owners.getOrPut(owner) { MutableStateFlow(0L) } }
    data class OwnerState(val revision: Long, val exists: Boolean)
    fun ownerState(owner: SubAgentConfigKey): OwnerState = synchronized(lock) {
        ensureClean(); OwnerState(revision(owner).value, stored(key(owner)) != null)
    }
    fun flow(owner: SubAgentConfigKey) = state.changes.map { snapshot(owner) }.distinctUntilChanged()
    private fun key(owner: SubAgentConfigKey): String = OWNER_PREFIX + when (owner) {
        is SubAgentConfigKey.Conversation -> "c_"
        is SubAgentConfigKey.Draft -> "d_"
        is SubAgentConfigKey.Preset -> "p_"
    } + Base64.getUrlEncoder().withoutPadding().encodeToString(owner.value.toByteArray(Charsets.UTF_8))
    private fun binding(conversation: SubAgentConfigKey.Conversation) = BIND_PREFIX + key(conversation).removePrefix(OWNER_PREFIX)
    private fun ensureClean() { check(state.pending == null) { "Sub-agent storage durability unknown; call recoverDurability() before accessing configs" } }
    fun recoverDurability(): Boolean = synchronized(lock) {
        val original = state.pending ?: return@synchronized true
        val restored = try {
            val edit = preferences.edit()
            original.forEach { (name, value) -> putRaw(edit, name, value) }
            edit.commit() && original.all { (name, value) -> preferences.all[name] == value }
        } catch (_: Exception) { false }
        if (restored) state.pending = null
        restored
    }
    private fun storedUnchecked(name: String): String? {
        if (!preferences.contains(name)) return null
        return preferences.getString(name, null) ?: error("Invalid config value type: $name")
    }
    private fun stored(name: String): String? { ensureClean(); return storedUnchecked(name) }
    private fun putRaw(edit: SharedPreferences.Editor, name: String, value: Any?) {
        when (value) {
            null -> edit.remove(name)
            is String -> edit.putString(name, value)
            is Boolean -> edit.putBoolean(name, value)
            is Int -> edit.putInt(name, value)
            is Long -> edit.putLong(name, value)
            is Float -> edit.putFloat(name, value)
            is Set<*> -> { require(value.all { it is String }) { "Invalid preference set: $name" }; edit.putStringSet(name, value.map { it as String }.toSet()) }
            else -> error("Unsupported preference type: $name")
        }
    }
    private fun transaction(changes: Map<String, String?>, vararg owners: SubAgentConfigKey) {
        ensureClean()
        val values = preferences.all
        val old = changes.keys.associateWith { name -> values[name].let { if (it is Set<*>) it.toSet() else it } }
        try {
            val edit = preferences.edit()
            changes.forEach { (name, value) -> if (value == null) edit.remove(name) else edit.putString(name, value) }
            check(edit.commit()) { "Sub-agent config commit failed" }
        } catch (failure: Exception) {
            state.pending = old
            try { val rollback = preferences.edit(); old.forEach { (name, value) -> putRaw(rollback, name, value) }; rollback.commit() }
            catch (_: Exception) { /* pending originals still held */ }
            throw failure
        }
        owners.distinct().forEach { owner -> val flow = state.owners.getOrPut(owner) { MutableStateFlow(0L) }; flow.value = flow.value + 1 }
        state.changes.value = state.changes.value + 1
    }
    fun isConfigurationResetComplete(): Boolean = synchronized(lock) {
        stored(RESET_MARKER_KEY)?.let { require(it == "1") { "Invalid sub-agent reset marker" }; true } ?: false
    }
    fun resetLegacyConfigurationOnce(): Boolean = synchronized(lock) {
        ensureClean()
        if (isConfigurationResetComplete()) return@synchronized true
        val names = preferences.all.keys.filter { name ->
            name == SEED_KEY || name == SubAgentPreferences.PROFILES_KEY || name == SubAgentPresetCatalog.KEY ||
                name == UI_DRAFT_KEY || name == SubAgentModelDefaults.KEY ||
                name.startsWith(OWNER_PREFIX + "c_") || name.startsWith(OWNER_PREFIX + "d_") || name.startsWith(OWNER_PREFIX + "p_") || name.startsWith(BIND_PREFIX) ||
                name.matches(Regex("agent_child_[0-3]_(provider|model|reasoning|task_tier|image_resolution|enabled)")) ||
                name.matches(Regex("agent_model_parallel_[0-9a-f]{64}")) || name.startsWith("agent_collaboration_")
        }
        val changes = names.associateWith { null as String? }.toMutableMap()
        changes[RESET_MARKER_KEY] = "1"
        changes[UI_DRAFT_KEY] = ""
        changes[SubAgentPreferences.PROFILES_KEY] = JSONObject().put("version", 1).put("agents", JSONArray()).toString()
        changes[SEED_KEY] = encode(ConversationSubAgentConfig(emptyList(), enabled = false))
        changes[SubAgentPresetCatalog.KEY] = SubAgentPresetCatalog.encode(emptyList())
        changes[SubAgentModelDefaults.KEY] = SubAgentModelDefaults.encode(emptyMap())
        transaction(changes, *state.owners.keys.toTypedArray())
        true
    }
    private fun seed(): ConversationSubAgentConfig = stored(SEED_KEY)?.let(::decode) ?: ConversationSubAgentConfig(emptyList(), enabled = false)
    private fun initial(owner: SubAgentConfigKey): ConversationSubAgentConfig {
        require(owner !is SubAgentConfigKey.Preset) { "Presets have no seed fallback" }; return seed().detached()
    }
    private fun catalog(): List<SubAgentPresetCatalog.Entry>? = stored(SubAgentPresetCatalog.KEY)?.let(SubAgentPresetCatalog::decode)
    private fun catalogForWrite(): Pair<List<SubAgentPresetCatalog.Entry>, Map<String, String?>> {
        catalog()?.let { entries -> validatePresetOwners(entries); return entries to emptyMap() }
        validatePresetOwners(emptyList()); return emptyList<SubAgentPresetCatalog.Entry>() to emptyMap()
    }
    private fun validatePresetOwners(entries: List<SubAgentPresetCatalog.Entry>) {
        val expected = entries.map { key(SubAgentConfigKey.Preset(it.id)) }.toSet()
        val actual = preferences.all.keys.filter { it.startsWith(OWNER_PREFIX + "p_") }.toSet()
        require(expected == actual) { "Preset catalog and payloads disagree" }
        expected.forEach { decode(stored(it) ?: error("Missing preset payload")) }
    }
    fun presets(): List<SubAgentPreset> = synchronized(lock) {
        val (entries, migration) = catalogForWrite(); check(migration.isEmpty())
        entries.map { SubAgentPreset(it.id, it.name, read(SubAgentConfigKey.Preset(it.id)).detached()) }
    }
    fun presetsFlow() = state.changes.map { presets() }.distinctUntilChanged()
    fun presetExists(id: String): Boolean = synchronized(lock) {
        ensureClean()
        if (id.isBlank() || catalog()?.none { it.id == id } != false) return@synchronized false
        val payload = stored(key(SubAgentConfigKey.Preset(id))) ?: return@synchronized false
        decode(payload); true
    }
    fun addPreset(name: String): SubAgentPreset = synchronized(lock) {
        SubAgentPresetCatalog.validateName(name)
        val (entries, migration) = catalogForWrite()
        val id = UUID.randomUUID().toString(); val owner = SubAgentConfigKey.Preset(id)
        val config = ConversationSubAgentConfig(profiles = emptyList())
        val next = entries + SubAgentPresetCatalog.Entry(id, name)
        transaction(migration + mapOf(SubAgentPresetCatalog.KEY to SubAgentPresetCatalog.encode(next), key(owner) to encode(config)),
            *(migration.keys.filter { it.startsWith(OWNER_PREFIX) }.map { ownerFromSuffix(it.removePrefix(OWNER_PREFIX)) } + owner).toTypedArray())
        SubAgentPreset(id, name, config.detached())
    }
    fun renamePreset(id: String, name: String): Boolean = synchronized(lock) {
        ensureClean(); val entries = catalog() ?: return@synchronized false
        if (entries.none { it.id == id }) return@synchronized false
        SubAgentPresetCatalog.validateName(name); validatePresetOwners(entries)
        val owner = SubAgentConfigKey.Preset(id)
        transaction(mapOf(SubAgentPresetCatalog.KEY to SubAgentPresetCatalog.encode(entries.map { if (it.id == id) it.copy(name = name) else it })), owner)
        true
    }
    fun removePreset(id: String): Boolean = synchronized(lock) {
        ensureClean(); val entries = catalog() ?: return@synchronized false
        if (entries.none { it.id == id }) return@synchronized false
        validatePresetOwners(entries)
        val owner = SubAgentConfigKey.Preset(id)
        transaction(mapOf(SubAgentPresetCatalog.KEY to SubAgentPresetCatalog.encode(entries.filterNot { it.id == id }), key(owner) to null), owner)
        true
    }
    fun applyPreset(owner: SubAgentConfigKey, presetId: String, canApply: () -> Boolean = { true }): WriteResult = synchronized(lock) {
        ensureClean()
        if (owner is SubAgentConfigKey.Preset || !canEdit(owner) || !canApply()) return@synchronized WriteResult.Rejected
        val entry = catalog()?.firstOrNull { it.id == presetId } ?: return@synchronized WriteResult.Rejected
        val payload = stored(key(SubAgentConfigKey.Preset(entry.id))) ?: return@synchronized WriteResult.Rejected
        stored(key(owner))?.let(::decode)
        val next = decode(payload).detached().copy(appliedPresetId = entry.id, appliedPresetName = entry.name, presetApplicationToken = UUID.randomUUID().toString())
        transaction(mapOf(key(owner) to encode(next)), owner)
        WriteResult.Saved(revision(owner).value, next.detached())
    }
    private fun read(owner: SubAgentConfigKey): ConversationSubAgentConfig {
        if (owner is SubAgentConfigKey.Preset) {
            require(catalog()?.any { it.id == owner.value } == true) { "Preset is absent: ${owner.value}" }
            return decode(stored(key(owner)) ?: error("Preset payload is absent: ${owner.value}"))
        }
        return stored(key(owner))?.let(::decode) ?: initial(owner)
    }
    fun snapshot(owner: SubAgentConfigKey): ConversationSubAgentConfig = synchronized(lock) { read(owner).detached() }
    fun previewSnapshot(owner: SubAgentConfigKey): ConversationSubAgentConfig = synchronized(lock) {
        (if (owner is SubAgentConfigKey.Preset) read(owner) else stored(key(owner))?.let(::decode) ?: initial(owner)).detached()
    }
    fun existingDraftOrNull(owner: SubAgentConfigKey.Draft): ConversationSubAgentConfig? = synchronized(lock) { stored(key(owner))?.let { decode(it).detached() } }
    fun createConversation(owner: SubAgentConfigKey.Conversation, source: SubAgentConfigKey? = null): ConversationSubAgentConfig = synchronized(lock) {
        require(source !is SubAgentConfigKey.Preset) { "Use applyPreset to copy presets" }
        stored(key(owner))?.let { return@synchronized decode(it).detached() }
        val config = (source?.let { read(it) } ?: initial(owner)).detached()
        transaction(mapOf(key(owner) to encode(config)), owner); config.detached()
    }
    fun createDraft(source: SubAgentConfigKey? = null): SubAgentConfigKey.Draft = synchronized(lock) {
        require(source !is SubAgentConfigKey.Preset) { "Use applyPreset to copy presets" }
        val config = (source?.let { read(it) } ?: seed()).detached()
        val owner = SubAgentConfigKey.Draft(UUID.randomUUID().toString())
        transaction(mapOf(key(owner) to encode(config)), owner); owner
    }
    private fun bindings(): Map<String, String> = preferences.all.keys.filter { it.startsWith(BIND_PREFIX) }.associateWith { stored(it) ?: error("Missing binding") }
    private fun assertUnshared(draft: SubAgentConfigKey.Draft, conversation: SubAgentConfigKey.Conversation) {
        require(bindings().none { (name, value) -> value == draft.value && name != binding(conversation) }) { "Draft is already bound to a different conversation" }
    }
    fun bindDraft(draft: SubAgentConfigKey.Draft, conversation: SubAgentConfigKey.Conversation): ConversationSubAgentConfig = synchronized(lock) {
        ensureClean(); assertUnshared(draft, conversation)
        val existingBinding = stored(binding(conversation))
        if (existingBinding != null) require(existingBinding == draft.value) { "Conversation bound to another draft" }
        stored(key(conversation))?.let {
            require(existingBinding == draft.value) { "Existing conversation is not bound to this draft" }
            require(stored(key(draft)) != null) { "Bound draft missing" }; return@synchronized decode(it).detached()
        }
        require(existingBinding == null) { "Bound conversation config missing" }
        val data = stored(key(draft)) ?: error("Draft is absent: ${draft.value}")
        val config = decode(data)
        transaction(mapOf(key(conversation) to encode(config), binding(conversation) to draft.value), conversation); config.detached()
    }
    fun confirmBoundDraft(draft: SubAgentConfigKey.Draft, conversation: SubAgentConfigKey.Conversation): Boolean = synchronized(lock) {
        ensureClean()
        if (stored(binding(conversation)) != draft.value) return@synchronized false
        assertUnshared(draft, conversation)
        decode(stored(key(conversation)) ?: error("Bound conversation config missing"))
        decode(stored(key(draft)) ?: error("Draft config missing"))
        transaction(mapOf(key(draft) to null, binding(conversation) to null), draft, conversation); true
    }
    fun update(owner: SubAgentConfigKey, change: (ConversationSubAgentConfig) -> ConversationSubAgentConfig): WriteResult = synchronized(lock) {
        ensureClean()
        if (owner is SubAgentConfigKey.Preset && !presetExists(owner.value)) return@synchronized WriteResult.Rejected
        if (owner !is SubAgentConfigKey.Preset && !canEdit(owner)) return@synchronized WriteResult.Rejected
        val next = change(read(owner).detached()).detached()
        if (owner is SubAgentConfigKey.Preset && !presetExists(owner.value)) return@synchronized WriteResult.Rejected
        next.validate(); transaction(mapOf(key(owner) to encode(next)), owner)
        WriteResult.Saved(revision(owner).value, next.detached())
    }
    fun modelDefaults(profile: SubAgentProfile): SubAgentModelDefaults.Entry? = synchronized(lock) { defaults()[SubAgentProfile.modelReasoningKey(profile.providerId, profile.modelId)] }
    private fun defaults(): Map<String, SubAgentModelDefaults.Entry> = stored(SubAgentModelDefaults.KEY)?.let(SubAgentModelDefaults::decode).orEmpty()
    fun updateConfirmedProfile(owner: SubAgentConfigKey, profileId: String, binding: SubAgentParallelModel? = null,
        canCommit: () -> Boolean = { true }, expectedOwnerState: OwnerState? = null,
        change: (ConversationSubAgentConfig) -> ConversationSubAgentConfig): WriteResult = synchronized(lock) {
        ensureClean()
        if (!canCommit() || (owner !is SubAgentConfigKey.Preset && !canEdit(owner)) ||
            (owner is SubAgentConfigKey.Draft && stored(key(owner)) == null) ||
            (expectedOwnerState != null && ownerState(owner) != expectedOwnerState) ||
            (owner is SubAgentConfigKey.Preset && !presetExists(owner.value))) return@synchronized WriteResult.Rejected
        val next = change(read(owner).detached()).detached(); next.validate()
        val profile = next.profiles.singleOrNull { it.id == profileId } ?: return@synchronized WriteResult.Rejected
        val changes = mutableMapOf<String, String?>(key(owner) to encode(next))
        if (profile.providerId.isNotBlank() && profile.modelId.isNotBlank()) {
            val entries = defaults(); val identity = SubAgentProfile.modelReasoningKey(profile.providerId, profile.modelId)
            val previous = entries[identity]
            require(binding == null || binding.providerId == profile.providerId) { "Profile/provider binding mismatch" }
            val resolved = binding ?: previous?.apiModel?.let { SubAgentParallelModel(profile.providerId, it) }
            val remembered = SubAgentModelDefaults.confirmed(profile, previous, resolved, resolved?.let(next::parallelLimit))
            changes[SubAgentModelDefaults.KEY] = SubAgentModelDefaults.encode(entries + (identity to remembered))
        }
        if (!canCommit() || (owner !is SubAgentConfigKey.Preset && !canEdit(owner)) ||
            (owner is SubAgentConfigKey.Draft && stored(key(owner)) == null) ||
            (expectedOwnerState != null && ownerState(owner) != expectedOwnerState) ||
            (owner is SubAgentConfigKey.Preset && !presetExists(owner.value))) return@synchronized WriteResult.Rejected
        transaction(changes, owner); WriteResult.Saved(revision(owner).value, next.detached())
    }
    fun legacyProfiles(): List<SubAgentProfile> = synchronized(lock) {
        val raw = stored(SubAgentPreferences.PROFILES_KEY) ?: return@synchronized emptyList()
        if (raw.isBlank()) return@synchronized emptyList()
        val json = JSONObject(raw); require(int(json, "version") == VERSION) { "Invalid legacy profile version" }
        val agents = array(json, "agents"); val profiles = (0 until agents.length()).map { profile(agents.getJSONObject(it)) }
        ConversationSubAgentConfig(profiles).validate(); profiles
    }
    fun saveLegacyConfiguration(profiles: List<SubAgentProfile>? = null, confirmedProfile: SubAgentProfile? = null,
        binding: SubAgentParallelModel? = null, parallelLimit: Int? = null) = synchronized(lock) {
        ensureClean(); val changes = mutableMapOf<String, String?>()
        profiles?.let {
            ConversationSubAgentConfig(it).validate()
            changes[SubAgentPreferences.PROFILES_KEY] = JSONObject().put("version", 1).put("agents", JSONArray(it.map(SubAgentProfile::toJson))).toString()
        }
        require(parallelLimit == null || (binding != null && parallelLimit >= 0))
        if (parallelLimit != null) changes[binding!!.legacyKey()] = parallelLimit.toString()
        var entries = defaults()
        confirmedProfile?.takeIf { it.providerId.isNotBlank() && it.modelId.isNotBlank() }?.let { profile ->
            require(binding == null || binding.providerId == profile.providerId)
            val identity = SubAgentProfile.modelReasoningKey(profile.providerId, profile.modelId); val previous = entries[identity]
            val api = binding ?: previous?.apiModel?.let { SubAgentParallelModel(profile.providerId, it) }
            val limit = parallelLimit ?: api?.let { model ->
                when (val raw = preferences.all[model.legacyKey()]) {
                    null -> previous?.parallelLimit ?: 1
                    is Int -> raw
                    is String -> raw.toIntOrNull() ?: error("Invalid legacy parallel limit")
                    else -> error("Invalid legacy parallel limit type")
                }.also { require(it == null || it >= 0) }
            }
            entries = entries + (identity to SubAgentModelDefaults.confirmed(profile, previous, api, limit))
        }
        if (parallelLimit != null && binding != null) entries = entries.mapValues { (_, entry) ->
            if (entry.providerId == binding.providerId && entry.apiModel == binding.apiModel) entry.copy(parallelLimit = parallelLimit) else entry
        }
        if (confirmedProfile != null || parallelLimit != null) changes[SubAgentModelDefaults.KEY] = SubAgentModelDefaults.encode(entries)
        transaction(changes)
    }
    fun delete(owner: SubAgentConfigKey): Boolean = synchronized(lock) {
        ensureClean()
        if (owner is SubAgentConfigKey.Preset) return@synchronized removePreset(owner.value)
        if (owner is SubAgentConfigKey.Draft) require(bindings().values.none { it == owner.value }) { "Draft still bound" }
        if (stored(key(owner)) == null) return@synchronized false
        val names = mutableMapOf(key(owner) to null as String?)
        if (owner is SubAgentConfigKey.Conversation && stored(binding(owner)) != null) names[binding(owner)] = null
        transaction(names, owner); true
    }
    fun export(owner: SubAgentConfigKey): String = synchronized(lock) { encode(read(owner)) }
    fun validateArchive(raw: String) { decode(raw) }
    fun importOwner(owner: SubAgentConfigKey, archive: String, overwrite: Boolean = false): Boolean = synchronized(lock) {
        ensureClean(); require(owner !is SubAgentConfigKey.Preset) { "Preset imports must not bypass the catalog" }
        val config = decode(archive)
        if (!overwrite && stored(key(owner)) != null) { decode(stored(key(owner))!!); return@synchronized false }
        transaction(mapOf(key(owner) to encode(config.detached())), owner); true
    }
    private fun ownerFromSuffix(suffix: String): SubAgentConfigKey {
        require(suffix.take(2) in setOf("c_", "d_", "p_")) { "Invalid owner key" }
        val encoded = suffix.substring(2); val value = String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
        require(value.isNotBlank() && Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8)) == encoded)
        return when (suffix.take(2)) {
            "c_" -> SubAgentConfigKey.Conversation(value); "d_" -> SubAgentConfigKey.Draft(value); "p_" -> SubAgentConfigKey.Preset(value)
            else -> error("Invalid owner key")
        }
    }
    fun validatePreferenceArchives(values: Map<String, String>) {
        values[RESET_MARKER_KEY]?.let { require(it == "1") { "Invalid sub-agent reset marker" } }
        values[SubAgentModelDefaults.KEY]?.let(SubAgentModelDefaults::validate); values[SEED_KEY]?.let(::decode)
        val entries = values[SubAgentPresetCatalog.KEY]?.let(SubAgentPresetCatalog::decode).orEmpty()
        val expected = entries.map { key(SubAgentConfigKey.Preset(it.id)) }.toSet(); val presets = mutableSetOf<String>()
        values.filterKeys { it.startsWith(OWNER_PREFIX) }.forEach { (name, payload) ->
            val owner = ownerFromSuffix(name.removePrefix(OWNER_PREFIX)); decode(payload)
            if (owner is SubAgentConfigKey.Preset) presets.add(name)
        }
        require(expected == presets) { "Preset catalog and payloads disagree" }
        val boundDrafts = mutableSetOf<String>()
        values.filterKeys { it.startsWith(BIND_PREFIX) }.forEach { (name, draftId) ->
            val owner = ownerFromSuffix(name.removePrefix(BIND_PREFIX))
            require(owner is SubAgentConfigKey.Conversation && values.containsKey(key(owner))) { "Bound conversation missing" }
            require(draftId.isNotBlank() && boundDrafts.add(draftId) && values.containsKey(key(SubAgentConfigKey.Draft(draftId)))) { "Invalid or shared bound draft association" }
        }
    }
    fun validateRestoredPreferences() = synchronized(lock) {
        ensureClean()
        val values = preferences.all.keys.filter {
            it == SEED_KEY || it == SubAgentPresetCatalog.KEY || it == SubAgentModelDefaults.KEY || it == RESET_MARKER_KEY || it.startsWith(OWNER_PREFIX) || it.startsWith(BIND_PREFIX)
        }.associateWith { stored(it) ?: error("Missing sub-agent value: $it") }
        validatePreferenceArchives(values)
    }
    fun refreshAfterRestore() = synchronized(lock) {
        validateRestoredPreferences(); state.changes.value = state.changes.value + 1
        state.owners.values.forEach { it.value = it.value + 1 }
    }
    private fun encode(config: ConversationSubAgentConfig): String {
        config.validate()
        return JSONObject().put("version", VERSION).put("enabled", config.enabled)
            .put("applied_preset_id", config.appliedPresetId ?: JSONObject.NULL)
            .put("applied_preset_name", config.appliedPresetName ?: JSONObject.NULL)
            .put("preset_application_token", config.presetApplicationToken ?: JSONObject.NULL)
            .put("agents", JSONArray(config.profiles.map { it.toJson() }))
            .put("parallel_limits", JSONArray(config.parallelLimits.map { (model, limit) -> JSONObject().put("provider", model.providerId).put("api_model", model.apiModel).put("limit", limit) }))
            .put("legacy_parallel_limits", JSONArray(config.legacyParallelLimits.map { (hash, limit) -> JSONObject().put("hash", hash).put("limit", limit) })).toString()
    }
    private fun string(j: JSONObject, name: String): String { require(j.has(name) && j.opt(name) is String) { "Invalid string: $name" }; return j.getString(name) }
    private fun optionalString(j: JSONObject, name: String): String? {
        if (!j.has(name) || j.isNull(name)) return null
        return string(j, name).also { require(it.isNotBlank()) { "Invalid preset metadata: $name" } }
    }
    private fun bool(j: JSONObject, name: String): Boolean { require(j.has(name) && j.opt(name) is Boolean) { "Invalid boolean: $name" }; return j.getBoolean(name) }
    private fun int(j: JSONObject, name: String): Int { require(j.has(name) && j.opt(name) is Int) { "Invalid integer: $name" }; return j.getInt(name) }
    private fun array(j: JSONObject, name: String): JSONArray { require(j.has(name) && j.opt(name) is JSONArray) { "Invalid array: $name" }; return j.getJSONArray(name) }
    private fun profile(j: JSONObject): SubAgentProfile {
        require(string(j, "id").isNotBlank() && string(j, "name").isNotBlank())
        require(string(j, "role") in setOf("implementation", "review", "image_generation", "video_generation"))
        bool(j, "enabled"); string(j, "provider"); string(j, "model")
        val tier = string(j, "tier"); require(tier.isEmpty() || (j.getString("role") == "implementation" && SubAgentTaskTier.fromWireValue(tier) != null))
        val effort = string(j, "reasoning"); require(effort.isEmpty() || ReasoningEffort.fromWireValue(effort) != null)
        val resolution = string(j, "image_resolution"); require(resolution.isEmpty() || resolution in ImageResolutionTier.values)
        val memory = array(j, "reasoning_memory"); val pairs = mutableSetOf<Pair<String, String>>()
        for (i in 0 until memory.length()) {
            val item = memory.getJSONObject(i); val provider = string(item, "provider"); val model = string(item, "model")
            require(provider.isNotBlank() && model.isNotBlank() && '\u0000' !in provider && '\u0000' !in model)
            require(pairs.add(provider to model)); require(ReasoningEffort.fromWireValue(string(item, "reasoning")) != null)
        }
        if (j.has("gpt_speed_memory")) {
            val speeds = array(j, "gpt_speed_memory"); val speedPairs = mutableSetOf<Pair<String, String>>()
            for (i in 0 until speeds.length()) {
                val item = speeds.getJSONObject(i); val provider = string(item, "provider"); val model = string(item, "model")
                require(provider.isNotBlank() && model.isNotBlank() && '\u0000' !in provider && '\u0000' !in model)
                require(speedPairs.add(provider to model)); val speed = string(item, "speed")
                require(app.daiyu.android.model.GptSpeedMode.entries.any { it.name == speed })
            }
        }
        return SubAgentProfile.fromJson(j)
    }
    private fun decode(raw: String): ConversationSubAgentConfig {
        val json = JSONObject(raw); require(int(json, "version") == VERSION) { "Unsupported sub-agent config version" }
        val agents = array(json, "agents"); val limits = array(json, "parallel_limits")
        val models = (0 until limits.length()).map { index -> val item = limits.getJSONObject(index); SubAgentParallelModel(string(item, "provider"), string(item, "api_model")) to int(item, "limit") }
        require(models.map { it.first }.distinct().size == models.size)
        val legacy = if (json.has("legacy_parallel_limits")) array(json, "legacy_parallel_limits") else JSONArray()
        val hashes = (0 until legacy.length()).map { index -> val item = legacy.getJSONObject(index); string(item, "hash") to int(item, "limit") }
        require(hashes.map { it.first }.distinct().size == hashes.size)
        val config = ConversationSubAgentConfig(profiles = (0 until agents.length()).map { index -> profile(agents.getJSONObject(index)) },
            enabled = bool(json, "enabled"), parallelLimits = models.toMap(), legacyParallelLimits = hashes.toMap(),
            appliedPresetId = optionalString(json, "applied_preset_id"), appliedPresetName = optionalString(json, "applied_preset_name"),
            presetApplicationToken = optionalString(json, "preset_application_token"))
        config.validate(); return config.detached()
    }
}
