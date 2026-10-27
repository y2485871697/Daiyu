package app.daiyu.android.title

import android.content.Context
import android.content.SharedPreferences
import app.daiyu.android.model.ProviderRequestOptions
import app.daiyu.android.model.ProviderSnapshot
import app.daiyu.android.storage.ModelSettings
import app.daiyu.android.storage.ModelSettingsStore
import org.json.JSONObject
import org.json.JSONTokener

/** Opaque profile identity, NOT the current chat-model selection. Never contains credentials. */
data class TitleModelBinding(val providerId: String, val modelId: String) {
    init { require(providerId.isNotBlank() && modelId.isNotBlank()) }
}
data class TitleModelSettings(val enabled: Boolean = false, val binding: TitleModelBinding? = null, val revision: String? = null)
data class TitleModelProfile(val binding: TitleModelBinding, val provider: ModelSettings)

class TitleModelUnavailableException : IllegalStateException("独立标题模型失绑定或配置不可用；未回退主模型")

/** A dedicated encrypted ModelSettingsStore plus a fail-closed metadata gate. No main-store reads.
 * All operations are worker-only. Changing a profile requires a NEW opaque binding; an old frozen
 * job cannot resolve a newly edited profile. Save is local and never creates a model session. */
class TitleModelSettingsStore internal constructor(
    private val preferences: SharedPreferences,
    private val loadModel: () -> ModelSettings,
    private val saveModel: (ModelSettings, Boolean, Boolean) -> Unit,
    private val namespace: String = NAMESPACE,
) {
    private constructor(preferences: SharedPreferences, model: ModelSettingsStore) :
        this(preferences, model::load, { p, protocol, options -> model.save(p, protocol, options) })
    constructor(context: Context) : this(context.getSharedPreferences(NAMESPACE, Context.MODE_PRIVATE),
        ModelSettingsStore(context, "title-model-api-key", "title-model-provider"))

    // Settings mutation, job admission and session publication share ONE monitor. Callbacks
    // only fence in-memory capabilities; transport cancellation always runs outside it.
    internal val admissionGate: Any get() = lock
    internal fun observeRevocation(owner: Any, revoke: () -> List<app.daiyu.android.domain.ModelSession>) = synchronized(lock) {
        revocations.getOrPut(namespace) { mutableMapOf() }[owner] = revoke
    }
    internal fun removeRevocation(owner: Any) = synchronized(lock) { revocations[namespace]?.remove(owner); Unit }
    private fun change(write: () -> Unit) {
        var sessions: List<app.daiyu.android.domain.ModelSession> = emptyList()
        try {
            synchronized(lock) {
                sessions = revocations[namespace]?.values?.flatMap { it() }.orEmpty()
                write()
            }
        } finally {
            sessions.forEach { try { it.cancel() } catch (_: Exception) { } }
        }
    }

    fun snapshot(): TitleModelSettings = synchronized(lock) { read().first }

    fun resolve(binding: TitleModelBinding): ProviderSnapshot = synchronized(lock) {
        val (settings, savedIdentity) = read()
        if (!settings.enabled || settings.binding != binding) unavailable()
        val provider = try { loadModel() } catch (_: Exception) { unavailable() }
        if (savedIdentity != identity(provider)) unavailable()
        try { validate(provider); provider.copy(usageIdentity = app.daiyu.android.model.UsageModelIdentity(binding.providerId, "标题模型")).requestSnapshot() } catch (_: Exception) { unavailable() }
    }

    /** Serialize the final DB CAS with local disable/rebind; never silently use a new profile. */
    fun <T> whileCurrent(binding: TitleModelBinding?, frozen: ProviderSnapshot, revision: String?, commit: () -> T): T = synchronized(lock) {
        val current = read().first
        if (current.revision != revision) unavailable()
        if (binding == null) {
            if (current.enabled) unavailable()
        } else if (resolve(binding) != frozen) unavailable()
        commit()
    }

    /** Explicit disable also fences previously prepared jobs. It never erases/rebinds a key. */
    fun disable() = change {
        persist(JSONObject().put("version", 1).put("state", "ready").put("enabled", false)
            .put("binding", JSONObject.NULL).put("identity", JSONObject.NULL).put("revision", java.util.UUID.randomUUID().toString()).toString())
    }

    fun save(profile: TitleModelProfile, enabled: Boolean = true,
        protocolChangeAuthorized: Boolean = false, requestOptionsReplacementAuthorized: Boolean = false) = change {
        validate(profile.provider)
        // Do not allow a changed endpoint/protocol/model to steal an existing opaque binding.
        val previous = try { read() } catch (_: Exception) { null }
        require(previous == null || previous.first.binding != profile.binding || previous.second == identity(profile.provider)) {
            "修改标题模型配置必须使用新的固定绑定"
        }
        val raw = JSONObject().put("version", 1).put("state", "ready").put("enabled", enabled)
            .put("binding", JSONObject().put("provider", profile.binding.providerId).put("model", profile.binding.modelId))
            .put("identity", identity(profile.provider)).put("revision", java.util.UUID.randomUUID().toString()).toString()
        fenced.add(namespace)
        try {
            write(SAVING)
            saveModel(profile.provider, protocolChangeAuthorized, requestOptionsReplacementAuthorized)
            write(raw)
        } catch (_: Exception) {
            // commit=false/throw may already have changed the in-memory value, even to 'ready'.
            try { write(SAVING) } catch (_: Exception) { }
            unavailable()
        }
        fenced.remove(namespace)
        Unit
    }

    private fun persist(raw: String) {
        fenced.add(namespace)
        try { write(raw) } catch (_: Exception) {
            try { write(SAVING) } catch (_: Exception) { }
            unavailable()
        }
        fenced.remove(namespace)
    }
    private fun write(raw: String) {
        check(preferences.edit().putString(KEY, raw).commit())
        check(preferences.getString(KEY, null) == raw)
    }
    private fun read(): Pair<TitleModelSettings, String?> {
        if (namespace in fenced) unavailable()
        try {
            val raw = preferences.getString(KEY, null) ?: return TitleModelSettings() to null
            val parser = JSONTokener(raw)
            val json = parser.nextValue()
            require(json is JSONObject && parser.nextClean() == '\u0000')
            require(json.keys().asSequence().toSet() == setOf("version", "state", "enabled", "binding", "identity", "revision"))
            require(json.get("version") == 1 && json.get("state") == "ready" && json.get("enabled") is Boolean)
            val binding = if (json.isNull("binding")) null else json.getJSONObject("binding").let {
                require(it.keys().asSequence().toSet() == setOf("provider", "model"))
                TitleModelBinding(it.get("provider") as String, it.get("model") as String)
            }
            val enabled = json.getBoolean("enabled")
            val identity = if (json.isNull("identity")) null else json.get("identity") as String
            require(binding != null || !enabled)
            require((binding == null) == (identity == null))
            val revision = json.get("revision") as String
            require(revision.isNotBlank())
            return TitleModelSettings(enabled, binding, revision) to identity
        } catch (_: Exception) { unavailable() }
    }

    companion object {
        internal const val NAMESPACE = "conversation-title-settings"
        internal const val KEY = "title-model-v1"
        private const val SAVING = "{\"version\":1,\"state\":\"saving\"}"
        private val lock = Any()
        private val fenced = mutableSetOf<String>()
        private val revocations = mutableMapOf<String, MutableMap<Any, () -> List<app.daiyu.android.domain.ModelSession>>>()
        private fun unavailable(): Nothing = throw TitleModelUnavailableException()
        private fun validate(provider: ModelSettings) {
            require(!provider.batteryEnabled && !provider.deviceStatusEnabled)
            // No custom body can override the model, inject instructions, or enable built-in tools.
            require(provider.requestOptions == ProviderRequestOptions()) { "标题模型不接受自定义请求策略" }
            provider.validate()
        }
        private fun identity(p: ModelSettings): String = JSONObject().put("endpoint", p.endpoint)
            .put("model", p.model).put("protocol", p.protocol.wireName).put("path", p.responsesPath)
            .put("options", p.requestOptions.encode()).put("battery", p.batteryEnabled)
            .put("deviceStatus", p.deviceStatusEnabled).toString()
    }
}
