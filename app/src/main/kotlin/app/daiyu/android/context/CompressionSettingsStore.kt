package app.daiyu.android.context

import android.content.Context
import android.content.SharedPreferences
import app.daiyu.android.model.ProviderProtocol
import org.json.JSONObject
import java.util.WeakHashMap

/** Identity only: credentials and live model clients never belong in compression preferences. */
data class SummaryModelBinding(val providerId: String, val modelId: String) {
    init { require(providerId.isNotBlank() && modelId.isNotBlank()) }
}

/**
 * A detached preference snapshot, not a model-capacity registry or a boundary algorithm.
 * keepRecentMessages retains the reference's 0..100 MESSAGE setting, not Policy.keepTurns.
 * A host policy adapter must explicitly resolve that distinction; silently coercing 0 to 1
 * or treating messages as turns would change the old configuration's meaning.
 */
data class CompressionSettings(
    val automatic: Boolean = false,
    val contextWindowTokens: Long? = null,
    val reserveTokens: Long = 4096,
    val triggerPercent: Int = 80,
    val keepRecentMessages: Int = 4,
    val summaryModel: SummaryModelBinding? = null,
    /** Only explicit v2 production configuration supplies turns; never derived from v1 messages. */
    val keepTurns: Int? = null,
    val tokenTail: Boolean = false,
    val summaryProtocol: ProviderProtocol? = null,
) {
    init {
        require(summaryProtocol == null || summaryProtocol in setOf(ProviderProtocol.CHAT_COMPLETIONS, ProviderProtocol.RESPONSES))
        require(contextWindowTokens == null || contextWindowTokens > 0)
        require(reserveTokens >= 0 && triggerPercent in 10..95)
        require(keepRecentMessages in 0..100)
        require(keepTurns == null || keepTurns in 1..100)
    }
    val automaticWithKnownWindow: Boolean get() = automatic && contextWindowTokens != null

    companion object {
        /** Reference semantics only; key names / migration ownership must be supplied by the host. */
        fun fromLegacy(preferenceEnabled: Boolean, configuredWindow: Int?, keepRecent: Int = 4,
            summaryModel: SummaryModelBinding? = null): CompressionSettings = CompressionSettings(
            automatic = preferenceEnabled,
            contextWindowTokens = configuredWindow?.takeIf { it > 0 }?.toLong(),
            keepRecentMessages = keepRecent.coerceIn(0, 100), summaryModel = summaryModel)
    }
}

/**
 * Single-record atomic preference snapshot. No guessed legacy keys and no automatic migration.
 * An unsuccessful commit fences every instance sharing this SharedPreferences until an explicit
 * successful save. SharedPreferences may have changed its in-memory value even on commit=false.
 */
class CompressionSettingsStore(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("context-compression", Context.MODE_PRIVATE))

    fun snapshot(): CompressionSettings = synchronized(lock) {
        check(fenced[preferences] != true) { "压缩设置写入结果未确认；请明确重新保存" }
        val raw = preferences.getString(KEY, null) ?: return@synchronized CompressionSettings()
        decode(raw)
    }

    fun save(settings: CompressionSettings) = synchronized(lock) {
        require(settings.keepTurns == null) { "轮数配置请使用 ProductionCompressionSettingsStore" }
        val raw = encode(settings)
        fenced[preferences] = true
        check(preferences.edit().putString(KEY, raw).commit()) { "压缩设置未持久化" }
        check(preferences.getString(KEY, null) == raw) { "压缩设置写入结果未确认" }
        fenced.remove(preferences)
        Unit
    }

    companion object {
        internal const val KEY = "compression-settings-v1"
        private val lock = Any()
        private val fenced = WeakHashMap<SharedPreferences, Boolean>()
        private val keys = setOf("version", "automatic", "window", "reserve", "trigger", "keepRecent", "summaryModel")

        private fun encode(settings: CompressionSettings): String = JSONObject()
            .put("version", 1).put("automatic", settings.automatic)
            .put("window", settings.contextWindowTokens ?: JSONObject.NULL)
            .put("reserve", settings.reserveTokens).put("trigger", settings.triggerPercent)
            .put("keepRecent", settings.keepRecentMessages)
            .put("summaryModel", settings.summaryModel?.let {
                JSONObject().put("provider", it.providerId).put("model", it.modelId)
            } ?: JSONObject.NULL).toString()

        private fun decode(raw: String): CompressionSettings {
            val parser = org.json.JSONTokener(raw)
            val json = parser.nextValue()
            require(json is JSONObject && parser.nextClean() == '\u0000')
            require(json.keys().asSequence().toSet() == keys)
            fun number(name: String): Long {
                val value = json.get(name)
                require(value is Int || value is Long) { "压缩配置数值类型非法" }
                return (value as Number).toLong()
            }
            require(number("version") == 1L && json.get("automatic") is Boolean)
            val trigger = number("trigger"); val keep = number("keepRecent")
            require(trigger in 10L..95L && keep in 0L..100L)
            val binding = if (json.isNull("summaryModel")) null else json.getJSONObject("summaryModel").let {
                require(it.keys().asSequence().toSet() == setOf("provider", "model"))
                val provider = it.get("provider"); val model = it.get("model")
                require(provider is String && model is String)
                SummaryModelBinding(provider, model)
            }
            return CompressionSettings(json.getBoolean("automatic"),
                if (json.isNull("window")) null else number("window"), number("reserve"),
                trigger.toInt(), keep.toInt(), binding)
        }
    }
}
