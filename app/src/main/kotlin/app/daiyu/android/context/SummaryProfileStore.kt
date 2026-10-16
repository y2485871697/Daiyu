package app.daiyu.android.context

import android.content.Context
import android.content.SharedPreferences
import app.daiyu.android.model.SummaryProviderFactory
import app.daiyu.android.storage.ModelSettings
import app.daiyu.android.storage.ModelSettingsStore
import org.json.JSONObject

/** All limits are explicit for this exact summary model. No main-model/default capacity lookup. */
data class SummaryProfileBudgets(
    val contextWindowTokens: Long,
    val inputBudgetTokens: Long,
    val maxOutputTokens: Int,
    val reasoningReserveTokens: Long,
    val providerOverheadTokens: Long,
    val deadlineMillis: Long,
)

/** The complete save API. Provider tools must be off; credentials are encrypted by ModelSettingsStore. */
data class SummaryProfileSettings(
    val binding: SummaryModelBinding,
    val provider: ModelSettings,
    val budgets: SummaryProfileBudgets,
) {
    internal fun configuration(): SummaryProviderConfiguration {
        require(!provider.batteryEnabled && !provider.deviceStatusEnabled) { "摘要 profile 不得启用工具" }
        val snapshot = provider.requestSnapshot()
        SummaryProviderFactory.validate(snapshot)
        return SummaryProviderConfiguration(binding, snapshot, provider.model,
            budgets.contextWindowTokens, budgets.inputBudgetTokens, budgets.maxOutputTokens,
            budgets.reasoningReserveTokens, budgets.providerOverheadTokens, budgets.deadlineMillis)
    }
}

enum class SummaryProfileUnavailableReason {
    MISSING, INCOMPLETE_SAVE, SAVE_UNCONFIRMED, INVALID_CONFIGURATION, BINDING_MISMATCH, CREDENTIAL_UNAVAILABLE,
}

class SummaryProfileUnavailableException(val reason: SummaryProfileUnavailableReason) :
    IllegalStateException("独立摘要 profile 不可用（${reason.name}）；请重新保存，未回退主模型")

/** A single independent summary profile. The metadata is a separate, non-secret atomic record.
 * Commit and read back 'saving' BEFORE credentials change. On failure, best-effort restore that
 * gate: commit may have written 'ready' even when it reports failure. A namespace-wide process
 * fence remains until explicit successful save, including when gate restoration is unconfirmed.
 * Confirmed restoration protects reopen; total storage failure cannot promise cross-process
 * failure detection. All instances use one lock. Call load/save off the UI thread. */
class SummaryProfileStore internal constructor(
    private val preferences: SharedPreferences,
    private val loadModel: () -> ModelSettings,
    private val saveModel: (ModelSettings, Boolean, Boolean) -> Unit,
    // Stable storage namespace, never the identity of a SharedPreferences wrapper.
    private val metadataNamespace: String = METADATA_NAMESPACE,
) {
    private constructor(preferences: SharedPreferences, model: ModelSettingsStore) :
        this(preferences, model::load, { settings, protocol, options -> model.save(settings, protocol, options) })

    constructor(context: Context) : this(
        context.getSharedPreferences(METADATA_NAMESPACE, Context.MODE_PRIVATE),
        ModelSettingsStore(context, KEY_ALIAS_PREFIX, MODEL_NAMESPACE))

    fun load(binding: SummaryModelBinding): SummaryProfileSettings = synchronized(lock) {
        fenced[metadataNamespace]?.let { unavailable(it) }
        val raw = try { preferences.getString(KEY, null) }
            catch (_: Exception) { unavailable(SummaryProfileUnavailableReason.INVALID_CONFIGURATION) }
            ?: unavailable(SummaryProfileUnavailableReason.MISSING)
        val metadata = try { configurationObject(raw) }
            catch (_: Exception) { unavailable(SummaryProfileUnavailableReason.INVALID_CONFIGURATION) }
        if (metadata.optString("state") == "saving") unavailable(SummaryProfileUnavailableReason.INCOMPLETE_SAVE)
        val savedBinding: SummaryModelBinding
        val budgets: SummaryProfileBudgets
        val identity: String
        try {
            metadata.requireFields(setOf("version", "state", "binding", "identity", "budgets"))
            require(metadata.configurationLong("version") == 1L && metadata.configurationString("state") == "ready")
            savedBinding = decodeBinding(metadata.getJSONObject("binding"))
            identity = metadata.configurationString("identity")
            val values = metadata.getJSONObject("budgets")
            values.requireFields(setOf("window", "input", "output", "reasoning", "overhead", "deadlineMillis"))
            budgets = SummaryProfileBudgets(values.configurationLong("window"), values.configurationLong("input"),
                values.configurationInt("output"), values.configurationLong("reasoning"),
                values.configurationLong("overhead"), values.configurationLong("deadlineMillis"))
        } catch (_: Exception) { unavailable(SummaryProfileUnavailableReason.INVALID_CONFIGURATION) }
        // Reject stale selection before any credential access.
        if (savedBinding != binding) unavailable(SummaryProfileUnavailableReason.BINDING_MISMATCH)
        val provider = try { loadModel() }
            catch (_: Exception) { unavailable(SummaryProfileUnavailableReason.CREDENTIAL_UNAVAILABLE) }
        if (provider.apiKey.isBlank()) unavailable(SummaryProfileUnavailableReason.CREDENTIAL_UNAVAILABLE)
        if (providerIdentity(provider) != identity) unavailable(SummaryProfileUnavailableReason.BINDING_MISMATCH)
        val profile = SummaryProfileSettings(savedBinding, provider, budgets)
        try { profile.configuration() }
        catch (_: Exception) { unavailable(SummaryProfileUnavailableReason.INVALID_CONFIGURATION) }
        profile
    }

    fun configuration(binding: SummaryModelBinding): SummaryProviderConfiguration = load(binding).configuration()

    /** Explicit protocol/request-options replacement authorization follows the main store's rules.
     * This never updates compression selection automatically; save its binding separately. */
    fun save(profile: SummaryProfileSettings, protocolChangeAuthorized: Boolean = false,
        requestOptionsReplacementAuthorized: Boolean = false) = synchronized(lock) {
        profile.configuration() // Validate all budgets, endpoint and tools BEFORE touching persisted state.
        val budgets = profile.budgets
        val raw = JSONObject().put("version", 1).put("state", "ready")
            .put("binding", encodeBinding(profile.binding)).put("identity", providerIdentity(profile.provider))
            .put("budgets", JSONObject().put("window", budgets.contextWindowTokens)
                .put("input", budgets.inputBudgetTokens).put("output", budgets.maxOutputTokens)
                .put("reasoning", budgets.reasoningReserveTokens).put("overhead", budgets.providerOverheadTokens)
                .put("deadlineMillis", budgets.deadlineMillis)).toString()
        fenced[metadataNamespace] = SummaryProfileUnavailableReason.SAVE_UNCONFIRMED
        try {
            check(preferences.edit().putString(KEY, SAVING).commit()) { "摘要 profile 保存门禁未持久化" }
            check(preferences.getString(KEY, null) == SAVING) { "摘要 profile 保存门禁未确认" }
            saveModel(profile.provider, protocolChangeAuthorized, requestOptionsReplacementAuthorized)
            check(preferences.edit().putString(KEY, raw).commit()) { "摘要 profile 未持久化" }
            check(preferences.getString(KEY, null) == raw) { "摘要 profile 写入未确认" }
        } catch (failure: Exception) {
            // Neither false nor throw guarantees that commit left the previous value intact.
            // Always attempt both restoration and readback, even if restoration throws.
            val committed = try { preferences.edit().putString(KEY, SAVING).commit() }
                catch (_: Exception) { false }
            val readBack = try { preferences.getString(KEY, null) == SAVING }
                catch (_: Exception) { false }
            val reason = if (committed && readBack) SummaryProfileUnavailableReason.INCOMPLETE_SAVE
                else SummaryProfileUnavailableReason.SAVE_UNCONFIRMED
            fenced[metadataNamespace] = reason
            throw SummaryProfileUnavailableException(reason).apply { initCause(failure) }
        }
        fenced.remove(metadataNamespace)
        Unit
    }

    companion object {
        internal const val MODEL_NAMESPACE = "context-summary-model-settings"
        internal const val METADATA_NAMESPACE = "context-summary-profile"
        internal const val KEY_ALIAS_PREFIX = "context-summary-api-key"
        internal const val KEY = "summary-profile-v1"
        private val lock = Any()
        private const val SAVING = "{\"version\":1,\"state\":\"saving\"}"
        // Strong keys are intentional: an uncertain save must outlive every wrapper/store instance.
        private val fenced = mutableMapOf<String, SummaryProfileUnavailableReason>()

        private fun unavailable(reason: SummaryProfileUnavailableReason): Nothing = throw SummaryProfileUnavailableException(reason)
        private fun providerIdentity(provider: ModelSettings): String {
            val canonical = JSONObject().put("endpoint", provider.endpoint).put("model", provider.model)
                .put("protocol", provider.protocol.wireName).put("responsesPath", provider.responsesPath)
                .put("options", provider.requestOptions.encode())
                .put("battery", provider.batteryEnabled).put("deviceStatus", provider.deviceStatusEnabled).toString()
            return java.security.MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}
