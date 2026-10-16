package app.daiyu.android.context

import android.content.Context
import android.content.SharedPreferences
import app.daiyu.android.domain.context.CompressionPolicy
import org.json.JSONObject
import org.json.JSONTokener
import java.util.WeakHashMap

/** V2: retention counts complete user/assistant turns, not physical message rows.
 * Pressure uses (window - reserve) * triggerPercent / 100, exactly as ContextPolicy does.
 * A null window stays unknown, even when automatic is selected. */
data class ProductionCompressionSettings(
    val automatic: Boolean = false,
    val contextWindowTokens: Long? = null,
    val reserveTokens: Long = 4096,
    val triggerPercent: Int = 80,
    val keepTurns: Int = 4,
    val summaryModel: SummaryModelBinding? = null,
) {
    init {
        CompressionPolicy(automatic && contextWindowTokens != null, contextWindowTokens,
            triggerPercent, reserveTokens, keepTurns)
    }

    internal fun coordinatorSettings() = CompressionSettings(
        automatic = automatic, contextWindowTokens = contextWindowTokens,
        reserveTokens = reserveTokens, triggerPercent = triggerPercent,
        summaryModel = summaryModel, keepTurns = keepTurns)
}

/** This adapter deliberately has no v1 message-to-turn conversion. */
internal object ProductionCompressionPolicyResolver : CompressionPolicyResolver {
    override fun resolve(settings: CompressionSettings): CompressionPolicy {
        val policy = CompressionPolicy(
            enabled = settings.automaticWithKnownWindow,
            contextTokens = settings.contextWindowTokens,
            triggerPercent = settings.triggerPercent,
            reserveTokens = settings.reserveTokens,
            keepTurns = requireNotNull(settings.keepTurns) { "旧版保留消息数不等于轮数；请明确重新保存压缩设置" })
        if (policy.enabled && settings.summaryModel == null)
            throw SummaryProfileUnavailableException(SummaryProfileUnavailableReason.MISSING)
        return policy
    }
}

/** One atomic v2 record in the historical compression namespace. Explicit save is the only
 * migration: it removes v1 without interpreting its keepRecent field. No credentials here. */
class ProductionCompressionSettingsStore(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(NAMESPACE, Context.MODE_PRIVATE))

    fun snapshot(): ProductionCompressionSettings = synchronized(lock) {
        check(fenced[preferences] != true) { "压缩设置写入未确认；请重新保存" }
        check(!preferences.contains(CompressionSettingsStore.KEY)) {
            "旧版压缩配置使用消息数；请选择保留轮数并明确重新保存"
        }
        val raw = preferences.getString(KEY, null) ?: return@synchronized ProductionCompressionSettings()
        val json = configurationObject(raw)
        json.requireFields(setOf("version", "automatic", "window", "reserve", "trigger", "keepTurns", "summaryModel"))
        require(json.configurationLong("version") == 2L) { "压缩配置版本不可用；请重新保存" }
        ProductionCompressionSettings(
            automatic = json.configurationBoolean("automatic"),
            contextWindowTokens = if (json.isNull("window")) null else json.configurationLong("window"),
            reserveTokens = json.configurationLong("reserve"),
            triggerPercent = json.configurationInt("trigger"),
            keepTurns = json.configurationInt("keepTurns"),
            summaryModel = if (json.isNull("summaryModel")) null else decodeBinding(json.getJSONObject("summaryModel")))
    }

    fun save(settings: ProductionCompressionSettings) = synchronized(lock) {
        val raw = JSONObject().put("version", 2).put("automatic", settings.automatic)
            .put("window", settings.contextWindowTokens ?: JSONObject.NULL)
            .put("reserve", settings.reserveTokens).put("trigger", settings.triggerPercent)
            .put("keepTurns", settings.keepTurns)
            .put("summaryModel", settings.summaryModel?.let(::encodeBinding) ?: JSONObject.NULL).toString()
        fenced[preferences] = true
        check(preferences.edit().putString(KEY, raw).remove(CompressionSettingsStore.KEY).commit()) {
            "压缩设置未持久化；请重新保存"
        }
        check(preferences.getString(KEY, null) == raw && !preferences.contains(CompressionSettingsStore.KEY)) {
            "压缩设置写入未确认；请重新保存"
        }
        fenced.remove(preferences)
        Unit
    }

    companion object {
        internal const val NAMESPACE = "context-compression"
        internal const val KEY = "compression-settings-v2"
        private val lock = Any()
        private val fenced = WeakHashMap<SharedPreferences, Boolean>()
    }
}

internal fun configurationObject(raw: String): JSONObject {
    val parser = JSONTokener(raw)
    val value = parser.nextValue()
    require(value is JSONObject && parser.nextClean() == '\u0000') { "配置 JSON 无效" }
    return value
}
internal fun JSONObject.requireFields(fields: Set<String>) {
    require(keys().asSequence().toSet() == fields) { "配置字段缺失或未知；请重新保存" }
}
internal fun JSONObject.configurationLong(name: String): Long {
    val value = get(name)
    require(value is Int || value is Long) { "配置 $name 必须为整数" }
    return (value as Number).toLong()
}
internal fun JSONObject.configurationInt(name: String): Int {
    val value = configurationLong(name)
    require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "配置 $name 越界" }
    return value.toInt()
}
internal fun JSONObject.configurationBoolean(name: String): Boolean {
    val value = get(name)
    require(value is Boolean) { "配置 $name 必须为布尔值" }
    return value
}
internal fun JSONObject.configurationString(name: String): String {
    val value = get(name)
    require(value is String) { "配置 $name 必须为文本" }
    return value
}
internal fun encodeBinding(binding: SummaryModelBinding): JSONObject = JSONObject()
    .put("provider", binding.providerId).put("model", binding.modelId)
internal fun decodeBinding(json: JSONObject): SummaryModelBinding {
    json.requireFields(setOf("provider", "model"))
    return SummaryModelBinding(json.configurationString("provider"), json.configurationString("model"))
}
