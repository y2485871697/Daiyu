package app.daiyu.android.model.reconnect

/** Retained five TOTAL windows, not retry counts or per-attempt delays.
 * Evidence: f001fb82 ErrorReconnectPolicyTest.policiesUseStableValuesAndTotalRetryWindows. */
enum class ReconnectDurationPreset(val wireId: String, val windowMillis: Long?, val label: String) {
    NONE("none", 0L, "不重连"),
    WINDOW_30S("window_30s", 30_000L, "30 秒"),
    WINDOW_1M("window_1m", 60_000L, "1 分钟"),
    WINDOW_5M("window_5m", 300_000L, "5 分钟"),
    CONTINUOUS("continuous", null, "持续重连"),
    ;

    companion object {
        /** Unknown persisted values must never enable reconnect. No trim/case/numeric coercion. */
        fun fromPersistedValue(raw: String?): ReconnectDurationPreset =
            entries.singleOrNull { it.wireId == raw } ?: NONE
    }
}

/** Global preference, captured once per run. The retained settings default is continuous;
 * this does NOT enable Runner retries or manufacture transport capability. NONE is explicit off. */
data class ReconnectSettings(val duration: ReconnectDurationPreset = ReconnectDurationPreset.CONTINUOUS) {
    val enabled: Boolean get() = duration != ReconnectDurationPreset.NONE
}

class ReconnectSettingsException internal constructor() :
    IllegalStateException("错误重连配置无效或版本不支持；请明确重新保存")

/** Independent versioned canonical record. No speculative migration of old app keys/datastore.
 * Missing preference preserves the retained continuous default; an unknown policy in version 1
 * is NONE, without rewriting the record. Invalid envelope/type/version is an explicit error. */
object ReconnectSettingsCodec {
    fun encode(settings: ReconnectSettings): String = "v1:${settings.duration.wireId}"

    fun decode(raw: String?): ReconnectSettings {
        if (raw == null) return ReconnectSettings()
        if (raw.length !in 3..128 || !raw.startsWith("v1:") || raw.indexOf(':', 3) >= 0)
            throw ReconnectSettingsException()
        return ReconnectSettings(ReconnectDurationPreset.fromPersistedValue(raw.substring(3)))
    }
}
