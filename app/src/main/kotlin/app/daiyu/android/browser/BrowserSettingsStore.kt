package app.daiyu.android.browser

import android.content.Context
import android.content.SharedPreferences
import app.daiyu.android.browser.session.BrowserAccess
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** One page's one-way save admission token; revocation never waits for the store lock. */
internal class BrowserSettingsSaveOwner {
    private val active = AtomicBoolean(true)
    val isActive: Boolean get() = active.get()

    fun revoke() { active.set(false) }

    // Even true -> true is a CAS: it orders admission against revoke, not a check-then-write.
    internal fun tryAdmit(): Boolean = active.compareAndSet(true, true)
}

/** A preference is only a capability ceiling. It never contains a task WRITE approval. */
data class BrowserSettings(
    val access: BrowserAccess = BrowserAccess.DISABLED,
    val productPolicy: Boolean = false,
) {
    // Product policy is separate from FULL: strict fixtures/editors cannot infer outbound consent.
    val historyConsent: String get() = if (productPolicy) "ALLOW" else "DENY"
    val allowedCookieNames: Set<String> get() = emptySet()
    // A finite name set cannot represent the product ceiling. The cookie backend must still
    // offload privately and withhold values from model/history receipts; this is not disclosure.
    val allCookieNamesAllowed: Boolean get() = productPolicy
    fun allowsCookieName(name: String): Boolean = name.isNotEmpty() &&
        (allCookieNamesAllowed || name in allowedCookieNames)
}

/**
 * Product sessions always have the full application capability ceiling; obsolete saved ceilings
 * no longer restrict them. Injected preferences retain the strict editor contract for fixtures.
 * This policy does not replace per-run ownership or Android system authorization.
 */
class BrowserSettingsStore private constructor(private val preferences: SharedPreferences, private val productDefaults: Boolean) {
    constructor(preferences: SharedPreferences) : this(preferences, false)
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences(
        "browser-settings", Context.MODE_PRIVATE), true)

    fun snapshot(): BrowserSettings = synchronized(lock) {
        if (productDefaults) return@synchronized BrowserSettings(BrowserAccess.FULL, productPolicy = true)
        check(fenced[preferences] != true) { "浏览器设置写入结果未确认，请重新保存" }
        val raw = try { preferences.getString(KEY, null) } catch (_: ClassCastException) { null }
        BrowserSettings(when (raw) {
            "v1:READ_ONLY" -> BrowserAccess.READ_ONLY
            "v1:FULL" -> BrowserAccess.FULL
            // Includes v1:DISABLED, DENY and v1:DENY. Never promote legacy consent.
            else -> BrowserAccess.DISABLED
        })
    }

    /** Return only a committed, read-back snapshot. SharedPreferences.apply is not sufficient. */
    fun save(settings: BrowserSettings): BrowserSettings = save(settings, BrowserSettingsSaveOwner())

    /**
     * Admission linearizes under the shared lock, before touching the fence or preferences.
     * A previously revoked owner cannot write or clear a fence. Once admitted, a save may finish
     * even if its owner is revoked during commit/read-back; revoke itself never takes this lock.
     */
    internal fun save(settings: BrowserSettings, owner: BrowserSettingsSaveOwner): BrowserSettings = synchronized(lock) {
        check(owner.tryAdmit()) { "浏览器设置页面已失效" }
        val raw = "v1:${settings.access.name}"
        fenced[preferences] = true
        check(preferences.edit().putString(KEY, raw).commit()) { "浏览器设置未持久化，请重新保存" }
        check(preferences.getString(KEY, null) == raw) { "浏览器设置回读不一致，请重新保存" }
        fenced.remove(preferences)
        snapshot()
    }

    companion object {
        internal const val KEY = "browser-settings-v1"
        private val lock = Any()
        private val fenced = WeakHashMap<SharedPreferences, Boolean>()
    }
}
