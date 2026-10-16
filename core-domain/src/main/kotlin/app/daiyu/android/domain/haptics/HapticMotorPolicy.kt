package app.daiyu.android.domain.haptics

/** Capability-based fallback; the Android port alone constructs API-specific effects. */
sealed interface HapticMotorEffect {
    data class Primitive(val tick: Boolean, val scale: Float) : HapticMotorEffect
    data object SystemTick : HapticMotorEffect
    /** Null amplitude means the platform's DEFAULT_AMPLITUDE, not silence. */
    data class OneShot(val durationMillis: Long, val amplitude: Int?) : HapticMotorEffect
}

fun motorEffect(request: HapticRequest, api: Int, primitiveSupported: Boolean,
                amplitudeSupported: Boolean): HapticMotorEffect {
    val tick = request.kind == HapticKind.TICK || (request.kind == HapticKind.THRESHOLD && api < 34)
    val intensity = request.intensity
    if (intensity == HapticIntensity.DEFAULT && api >= 29) return HapticMotorEffect.SystemTick
    if (intensity != HapticIntensity.DEFAULT && api >= 30 && primitiveSupported) {
        return HapticMotorEffect.Primitive(tick, if (tick) intensity.tickScale else intensity.clickScale)
    }
    val amplitude = if (intensity == HapticIntensity.DEFAULT || !amplitudeSupported) null
        else if (tick) intensity.tickAmplitude else intensity.clickAmplitude
    return HapticMotorEffect.OneShot(if (tick) 10L else 16L, amplitude)
}
