package app.daiyu.android.haptics

import android.content.Context
import android.content.SharedPreferences
import app.daiyu.android.domain.haptics.*

/**
 * Device-local private preferences. One store per runtime; no remote config, backup API or network.
 * commit() is intentional: apply() cannot report a failed durable write.
 */
class LocalHapticSettingsStore(private val preferences: SharedPreferences) : HapticSettingsStore {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME, Context.MODE_PRIVATE))

    private var current = load()
    private val observers = linkedSetOf<(HapticSettings) -> Unit>()
    @Synchronized override fun read(): HapticSettings = current

    @Synchronized override fun write(settings: HapticSettings): Boolean {
        val old = current
        val saved = try { edit(settings).commit() } catch (_: Exception) { false }
        if (!saved) {
            // Android commit(false) can already have mutated the in-memory map. Restore best-effort;
            // the confirmed snapshot remains old even if the rollback also fails.
            try { edit(old).commit() } catch (_: Exception) { /* Still report failure. */ }
            return false
        }
        current = settings
        observers.toList().forEach { it(settings) }
        return true
    }

    @Synchronized fun observe(observer: (HapticSettings) -> Unit): AutoCloseable {
        observers += observer
        observer(current)
        return AutoCloseable { synchronized(this) { observers -= observer } }
    }

    private fun load(): HapticSettings {
        fun boolean(key: String) = try { preferences.getBoolean(key, true) }
            catch (_: ClassCastException) { true }
        val intensity = try { preferences.getString(INTENSITY, "default") }
            catch (_: ClassCastException) { "default" }
        return HapticSettings(boolean(TOUCH), boolean(GENERATION), boolean(BACKGROUND),
            boolean(REASONING), HapticIntensity.fromWire(intensity))
    }
    private fun edit(value: HapticSettings): SharedPreferences.Editor = preferences.edit()
        .putBoolean(TOUCH, value.touch).putBoolean(GENERATION, value.generation)
        .putBoolean(BACKGROUND, value.background).putBoolean(REASONING, value.backgroundReasoning)
        .putString(INTENSITY, value.intensity.wireValue)

    companion object {
        const val PREFERENCES_NAME = "daiyu_haptics_local"
        const val TOUCH = "haptic_touch_feedback"
        const val GENERATION = "haptic_message_generation"
        const val BACKGROUND = "haptic_background"
        const val REASONING = "haptic_background_reasoning"
        const val INTENSITY = "haptic_intensity"
    }
}
