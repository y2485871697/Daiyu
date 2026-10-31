package app.daiyu.android.config

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Device-local interaction preference backed by an owner-supplied preferences file.
 * Reading is side-effect free.
 */
internal class InteractiveModePreference(private val preferences: SharedPreferences?) {
    val enabled: Boolean get() = read(preferences)

    /** Explicit user intent is the only write path, including an explicit false. */
    fun setEnabled(enabled: Boolean) {
        preferences?.edit()?.putBoolean(PREF_KEY, enabled)?.apply()
    }

    /**
     * Capture once at run entry. Use this SAME value for the user-barrier registry and capability grant.
     * Keep it with that run: later preference changes apply only to subsequently started runs.
     */
    fun snapshot(): Snapshot = Snapshot(enabled)

    data class Snapshot(val enabled: Boolean)

    /** Immediate current value, then relevant changes, always delivered on the main thread. */
    fun observe(onChanged: (Boolean) -> Unit): AutoCloseable {
        val main = Handler(Looper.getMainLooper())
        val closed = AtomicBoolean(false)
        val refresh = Runnable { if (!closed.get()) onChanged(enabled) }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (!closed.get() && (key == null || key == PREF_KEY)) {
                if (Looper.myLooper() == main.looper) refresh.run() else main.post(refresh)
            }
        }
        preferences?.registerOnSharedPreferenceChangeListener(listener)
        if (Looper.myLooper() == main.looper) refresh.run() else main.post(refresh)
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                preferences?.unregisterOnSharedPreferenceChangeListener(listener)
                main.removeCallbacks(refresh)
            }
        }
    }

    companion object {
        const val PREF_KEY = "interactive_mode"

        /** Missing, unavailable or unreadable preferences mean off. */
        fun read(preferences: SharedPreferences?): Boolean =
            runCatching { preferences?.getBoolean(PREF_KEY, false) ?: false }.getOrDefault(false)
    }
}
