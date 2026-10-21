package app.daiyu.android.context

import android.content.Context
import android.content.SharedPreferences
import app.daiyu.android.domain.context.CompressionPolicy
import org.json.JSONObject
import org.json.JSONTokener
import java.util.WeakHashMap

/** Production uses the original token tail; reserve affects send capacity, not the 80% trigger. */
data class ProductionCompressionSettings(
    val automatic: Boolean = false,
    val contextWindowTokens: Long? = null,
    val reserveTokens: Long = 4096,
    val triggerPercent: Int = 80,
    val summaryModel: SummaryModelBinding? = null,
) {
    init {
        require(triggerPercent == 80)
        CompressionPolicy(automatic && contextWindowTokens != null, contextWindowTokens,
            triggerPercent, reserveTokens, tokenTail = true)
    }

    internal fun coordinatorSettings() = CompressionSettings(
        automatic = automatic, contextWindowTokens = contextWindowTokens,
        reserveTokens = reserveTokens, triggerPercent = triggerPercent,
        summaryModel = summaryModel, tokenTail = true)
}

internal object ProductionCompressionPolicyResolver : CompressionPolicyResolver {
    override fun resolve(settings: CompressionSettings) = CompressionPolicy(
        enabled = settings.automaticWithKnownWindow, contextTokens = settings.contextWindowTokens,
        triggerPercent = 80, reserveTokens = settings.reserveTokens, tokenTail = true)
}

internal class CompressionSettingsWriteUnconfirmed : IllegalStateException("压缩设置写入未确认；请重新保存")

/** Strict legacy decoding precedes a single atomic v3 write. Mixed records never choose a winner. */
class ProductionCompressionSettingsStore(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(NAMESPACE, Context.MODE_PRIVATE))

    fun snapshot(): ProductionCompressionSettings = synchronized(lock) {
        if (fenced[preferences] == true) throw CompressionSettingsWriteUnconfirmed()
        val present = listOf(KEY, V2_KEY, CompressionSettingsStore.KEY).filter(preferences::contains)
        check(present.size <= 1) { "压缩配置版本冲突；请重新保存" }
        if (present.isEmpty()) return@synchronized ProductionCompressionSettings()
        val key = present.single()
        val raw = requireNotNull(preferences.getString(key, null))
        val json = configurationObject(raw)
        val legacy = key != KEY
        val retention = if (key == CompressionSettingsStore.KEY) "keepRecent" else "keepTurns"
        val fields = setOf("version", "automatic", "window", "reserve", "trigger", "summaryModel")
        json.requireFields(if (legacy) fields + retention else fields)
        require(json.configurationLong("version") == when (key) {
            KEY -> 3L; V2_KEY -> 2L; else -> 1L
        }) { "压缩配置版本无效" }
        val trigger = json.configurationInt("trigger")
        require(trigger in 10..95 && (legacy || trigger == 80))
        if (legacy) require(json.configurationInt(retention) in
            if (key == CompressionSettingsStore.KEY) 0..100 else 1..100)
        val settings = ProductionCompressionSettings(
            automatic = json.configurationBoolean("automatic"),
            contextWindowTokens = if (json.isNull("window")) null else json.configurationLong("window"),
            reserveTokens = json.configurationLong("reserve"),
            summaryModel = if (json.isNull("summaryModel")) null else decodeBinding(json.getJSONObject("summaryModel")))
        if (legacy) save(settings)
        settings
    }

    fun changeAutomatic(expected: Boolean): ProductionCompressionSettings = synchronized(lock) {
        val before = snapshot()
        check(before.automatic == expected) { "compression_configuration_changed" }
        val next = before.copy(automatic = !expected)
        save(next)
        snapshot().also { check(it == next) { "compression_write_unconfirmed" } }
    }

    fun save(settings: ProductionCompressionSettings) = synchronized(lock) {
        val raw = JSONObject().put("version", 3).put("automatic", settings.automatic)
            .put("window", settings.contextWindowTokens ?: JSONObject.NULL)
            .put("reserve", settings.reserveTokens).put("trigger", 80)
            .put("summaryModel", settings.summaryModel?.let(::encodeBinding) ?: JSONObject.NULL).toString()
        fenced[preferences] = true
        val confirmed = preferences.edit().putString(KEY, raw).remove(V2_KEY)
            .remove(CompressionSettingsStore.KEY).commit()
        if (!confirmed || preferences.getString(KEY, null) != raw || preferences.contains(V2_KEY) ||
            preferences.contains(CompressionSettingsStore.KEY)) throw CompressionSettingsWriteUnconfirmed()
        fenced.remove(preferences)
        Unit
    }

    companion object {
        internal const val NAMESPACE = "context-compression"
        internal const val KEY = "compression-settings-v3"
        internal const val V2_KEY = "compression-settings-v2"
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
