package app.daiyu.android.haptics

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import app.daiyu.android.domain.haptics.*
import java.lang.ref.WeakReference

/** API 26 boundary. A View is weakly held; the runtime never retains an Activity through a port. */
class AndroidHapticPort(view: View) : HapticPort {
    private val view = WeakReference(view)
    private val context = view.context.applicationContext
    private var motorStarted = false

    @Suppress("DEPRECATION")
    private fun vibrator(): Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    @Suppress("DEPRECATION")
    private fun systemEnabled(): Boolean = try {
        Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0
    } catch (_: Exception) { true }

    override fun play(request: HapticRequest): HapticDelivery {
        if (!systemEnabled()) return HapticDelivery.SYSTEM_DISABLED
        return try {
            if (request.agentAction == null && request.intensity == HapticIntensity.DEFAULT) {
                val target = view.get()
                val accepted = if (request.preview) {
                    target?.performHapticFeedback(constant(request.kind),
                        HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
                } else target?.performHapticFeedback(constant(request.kind))
                if (accepted == true) {
                    motorStarted = true
                    return HapticDelivery.PLAYED
                }
                if (!request.generation) return HapticDelivery.UNAVAILABLE
            }
            motor(request)
        } catch (_: SecurityException) { HapticDelivery.PERMISSION_DENIED }
          catch (_: Exception) { HapticDelivery.FAILED }
    }

    private fun constant(kind: HapticKind): Int = when (kind) {
        HapticKind.CLICK -> HapticFeedbackConstants.CONTEXT_CLICK
        HapticKind.KEYBOARD -> HapticFeedbackConstants.KEYBOARD_TAP
        HapticKind.LONG_PRESS -> HapticFeedbackConstants.LONG_PRESS
        HapticKind.TICK -> if (Build.VERSION.SDK_INT >= 34)
            HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK
        HapticKind.THRESHOLD -> if (Build.VERSION.SDK_INT >= 34)
            HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.CLOCK_TICK
        HapticKind.CONFIRM -> if (Build.VERSION.SDK_INT >= 30)
            HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK
    }

    private fun motor(request: HapticRequest): HapticDelivery {
        if (context.checkSelfPermission(Manifest.permission.VIBRATE) != PackageManager.PERMISSION_GRANTED) {
            return HapticDelivery.PERMISSION_DENIED
        }
        val vibrator = vibrator() ?: return HapticDelivery.UNAVAILABLE
        if (!vibrator.hasVibrator()) return HapticDelivery.UNAVAILABLE
        request.agentAction?.let { action ->
            val effect = agentEffect(vibrator, action, request.intensity)
            vibrator.vibrate(effect)
            motorStarted = true
            return HapticDelivery.PLAYED
        }
        val tick = request.kind == HapticKind.TICK ||
            (request.kind == HapticKind.THRESHOLD && Build.VERSION.SDK_INT < 34)
        val primitive = if (tick) VibrationEffect.Composition.PRIMITIVE_TICK
            else VibrationEffect.Composition.PRIMITIVE_CLICK
        val supported = Build.VERSION.SDK_INT >= 30 &&
            vibrator.arePrimitivesSupported(primitive).firstOrNull() == true
        val plan = motorEffect(request, Build.VERSION.SDK_INT, supported, vibrator.hasAmplitudeControl())
        val effect = when (plan) {
            HapticMotorEffect.SystemTick -> {
                check(Build.VERSION.SDK_INT >= 29)
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
            }
            is HapticMotorEffect.Primitive -> {
                check(Build.VERSION.SDK_INT >= 30)
                VibrationEffect.startComposition().addPrimitive(primitive, plan.scale).compose()
            }
            is HapticMotorEffect.OneShot -> VibrationEffect.createOneShot(plan.durationMillis,
                plan.amplitude ?: VibrationEffect.DEFAULT_AMPLITUDE)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            vibrator.vibrate(effect, VibrationAttributes.Builder()
                .setUsage(VibrationAttributes.USAGE_TOUCH).build())
        } else {
            vibrator.vibrate(effect, AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        }
        motorStarted = true
        return HapticDelivery.PLAYED
    }

    private fun agentEffect(vibrator: Vibrator, action: HapticAgentAction,
                            intensity: HapticIntensity): VibrationEffect {
        val primitive = when (action) {
            HapticAgentAction.TAP, HapticAgentAction.RUN_STARTED -> VibrationEffect.Composition.PRIMITIVE_CLICK
            HapticAgentAction.LONG_PRESS -> VibrationEffect.Composition.PRIMITIVE_LOW_TICK
            HapticAgentAction.SWIPE -> VibrationEffect.Composition.PRIMITIVE_TICK
        }
        if (Build.VERSION.SDK_INT >= 30 && vibrator.arePrimitivesSupported(primitive).firstOrNull() == true) {
            return VibrationEffect.startComposition()
                .addPrimitive(primitive, intensity.primitiveScale(action.baseScale)).compose()
        }
        if (Build.VERSION.SDK_INT >= 29) {
            return VibrationEffect.createPredefined(when (action) {
                HapticAgentAction.TAP, HapticAgentAction.SWIPE -> VibrationEffect.EFFECT_TICK
                HapticAgentAction.LONG_PRESS -> VibrationEffect.EFFECT_HEAVY_CLICK
                HapticAgentAction.RUN_STARTED -> VibrationEffect.EFFECT_CLICK
            })
        }
        // API26-28 has neither primitives nor predefined effects; retain a short system pulse.
        return VibrationEffect.createOneShot(if (action == HapticAgentAction.LONG_PRESS) 16L else 10L,
            VibrationEffect.DEFAULT_AMPLITUDE)
    }

    /** Never cancel for ordinary overlapping ticks; only runtime close/gate changes call this. */
    override fun cancel() {
        if (!motorStarted) return
        motorStarted = false
        try { vibrator()?.cancel() } catch (_: Exception) { /* Permission may have been revoked. */ }
    }
}
