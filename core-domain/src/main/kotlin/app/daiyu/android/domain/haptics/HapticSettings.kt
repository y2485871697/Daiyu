package app.daiyu.android.domain.haptics

import java.util.Locale

/** Wire values and levels match the local settings, not a percentage of system strength. */
enum class HapticIntensity(
    val wireValue: String,
    val clickScale: Float,
    val tickScale: Float,
    val clickAmplitude: Int,
    val tickAmplitude: Int,
) {
    DEFAULT("default", 0f, 0f, 0, 0),
    LOW("low", 0.25f, 0.18f, 48, 32),
    MEDIUM("medium", 0.60f, 0.48f, 140, 96),
    HIGH("high", 1f, 0.85f, 255, 200);

    fun primitiveScale(base: Float): Float = when (this) {
        DEFAULT -> base
        LOW -> (base * 0.35f).coerceIn(0.12f, 1f)
        MEDIUM -> (base * 0.75f).coerceIn(0.35f, 1f)
        HIGH -> 1f
    }

    companion object {
        fun fromWire(value: String?): HapticIntensity = entries.firstOrNull {
            it.wireValue == value?.trim()?.lowercase(Locale.ROOT)
        } ?: DEFAULT
    }
}

data class HapticSettings(
    val touch: Boolean = true,
    val generation: Boolean = true,
    val background: Boolean = true,
    val backgroundReasoning: Boolean = true,
    val intensity: HapticIntensity = HapticIntensity.DEFAULT,
) {
    val generationOn get() = touch && generation
    val backgroundOn get() = generationOn && background
    val backgroundReasoningOn get() = backgroundOn && backgroundReasoning
}

/** A failed durable write must not publish the requested settings as successfully saved. */
interface HapticSettingsStore {
    fun read(): HapticSettings
    fun write(settings: HapticSettings): Boolean
}
