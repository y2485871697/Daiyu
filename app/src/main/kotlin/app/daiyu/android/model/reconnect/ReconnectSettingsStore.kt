package app.daiyu.android.model.reconnect

import android.content.Context
import android.content.SharedPreferences
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** One-way page save admission. Revocation never waits for a preference commit. */
class ReconnectSettingsSaveOwner {
    private val active = AtomicBoolean(true)
    val isActive: Boolean get() = active.get()
    fun revoke() { active.set(false) }
    internal fun tryAdmit(): Boolean = active.compareAndSet(true, true)
}

/** Independent app-global record; no model/assistant/history preferences are read or changed.
 * Synchronous commit plus readback must run off main. A failed/uncertain write fences ALL
 * instances using the same preferences object, including SharedPreferences' in-memory mutation
 * on failed commit. Only another explicit successful save clears that process-local fence.
 * No settings observer can change an already-frozen run. */
class ReconnectSettingsStore(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences(
        NAMESPACE, Context.MODE_PRIVATE))

    fun snapshot(): ReconnectSettings = synchronized(lock) {
        check(fenced[preferences] != true) { "错误重连设置写入未确认；请重新保存" }
        val raw = try { preferences.getString(KEY, null) }
        catch (_: ClassCastException) { throw ReconnectSettingsException() }
        ReconnectSettingsCodec.decode(raw)
    }

    fun save(settings: ReconnectSettings): ReconnectSettings = save(settings, ReconnectSettingsSaveOwner())

    fun save(settings: ReconnectSettings, owner: ReconnectSettingsSaveOwner): ReconnectSettings = synchronized(lock) {
        // Admission orders against revoke BEFORE either writing or changing the failure fence.
        // A save admitted before revoke may finish; an old page cannot queue a new write.
        check(owner.tryAdmit()) { "错误重连设置页面已失效" }
        val raw = ReconnectSettingsCodec.encode(settings)
        fenced[preferences] = true
        check(preferences.edit().putString(KEY, raw).commit()) { "错误重连设置未持久化；请重新保存" }
        check(preferences.getString(KEY, null) == raw) { "错误重连设置回读不一致；请重新保存" }
        fenced.remove(preferences)
        ReconnectSettingsCodec.decode(raw)
    }

    companion object {
        const val NAMESPACE = "daiyu-model-reconnect"
        internal const val KEY = "reconnect-v1"
        private val lock = Any()
        private val fenced = WeakHashMap<SharedPreferences, Boolean>()
    }
}
