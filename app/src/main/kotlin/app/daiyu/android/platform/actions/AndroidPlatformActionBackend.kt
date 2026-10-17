package app.daiyu.android.platform.actions

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.provider.AlarmClock
import android.view.KeyEvent
import app.daiyu.android.domain.tools.ExecutionReceipt

/** Standard public Android APIs only. No root, Shizuku, hidden API, GUI or shell fallback. */
internal class AndroidPlatformActionBackend(context: Context) : PlatformActionBackend {
    private val app = context.applicationContext
    override fun unavailable(kind: PlatformActionKind): String? = when {
        kind == PlatformActionKind.WIFI && Build.VERSION.SDK_INT >= 29 ->
            "wifi_toggle_requires_privileged_identity_on_android_10_plus"
        else -> null
    }

    override fun execute(action: PlatformAction, cancelled: () -> Boolean): ExecutionReceipt {
        unavailable(action.kind)?.let { return ExecutionReceipt.NotExecuted(it) }
        if (cancelled()) return ExecutionReceipt.NotExecuted("platform_action_cancelled")
        return when (action.kind) {
            PlatformActionKind.ALARM, PlatformActionKind.TIMER -> clock(action, cancelled)
            PlatformActionKind.VOLUME -> volume(action, cancelled)
            PlatformActionKind.MEDIA -> media(action, cancelled)
            PlatformActionKind.WIFI -> wifi(action, cancelled)
        }
    }

    private fun clock(action: PlatformAction, cancelled: () -> Boolean): ExecutionReceipt {
        // Never bypass Android's background activity restrictions or switch to a main-screen fallback.
        val foreground = try {
            val state = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(state)
            state.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        } catch (_: Exception) { false }
        if (!foreground) return ExecutionReceipt.NotExecuted("clock_requires_visible_foreground_app")
        if (app.checkSelfPermission("com.android.alarm.permission.SET_ALARM") != PackageManager.PERMISSION_GRANTED)
            return ExecutionReceipt.NotExecuted("set_alarm_permission_missing")
        val args = action.arguments
        val intent = Intent(if (action.kind == PlatformActionKind.ALARM) AlarmClock.ACTION_SET_ALARM else AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_MESSAGE, args.text("label"))
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (action.kind == PlatformActionKind.ALARM) {
            intent.putExtra(AlarmClock.EXTRA_HOUR, args.integer("hour").toInt())
            intent.putExtra(AlarmClock.EXTRA_MINUTES, args.integer("minute").toInt())
        } else intent.putExtra(AlarmClock.EXTRA_LENGTH, args.integer("seconds").toInt())
        val target = try { intent.resolveActivity(app.packageManager) } catch (_: Exception) { null }
            ?: return ExecutionReceipt.NotExecuted("clock_handler_unavailable")
        intent.component = target
        if (cancelled()) return ExecutionReceipt.NotExecuted("platform_action_cancelled")
        // startActivity has no effect receipt; even a non-throwing submission is not confirmation.
        return try {
            app.startActivity(intent)
            ExecutionReceipt.Unknown("clock_intent_submitted_effect_unverified")
        } catch (_: Exception) { ExecutionReceipt.Unknown("clock_intent_effect_unverified") }
    }

    private fun audio(): AudioManager? = app.getSystemService(AudioManager::class.java)
    private fun volume(action: PlatformAction, cancelled: () -> Boolean): ExecutionReceipt {
        if (app.checkSelfPermission(Manifest.permission.MODIFY_AUDIO_SETTINGS) != PackageManager.PERMISSION_GRANTED)
            return ExecutionReceipt.NotExecuted("modify_audio_settings_missing")
        val manager = try { audio() } catch (_: Exception) { null }
            ?: return ExecutionReceipt.NotExecuted("audio_service_unavailable")
        val stream = if (action.arguments.text("stream") == "music") AudioManager.STREAM_MUSIC else AudioManager.STREAM_ALARM
        val index = action.arguments.integer("index").toInt()
        try {
            val minimum = if (Build.VERSION.SDK_INT >= 28) manager.getStreamMinVolume(stream) else 0
            if (manager.isVolumeFixed || index !in minimum..manager.getStreamMaxVolume(stream))
                return ExecutionReceipt.NotExecuted("volume_index_unavailable_or_fixed")
        } catch (_: Exception) { return ExecutionReceipt.NotExecuted("volume_preflight_unavailable") }
        if (cancelled()) return ExecutionReceipt.NotExecuted("platform_action_cancelled")
        return try {
            manager.setStreamVolume(stream, index, 0)
            val actual = manager.getStreamVolume(stream)
            if (actual == index) ExecutionReceipt.Confirmed("{\"stream\":\"${action.arguments.text("stream")}\",\"index\":$actual,\"read_back\":true}")
            else ExecutionReceipt.Unknown("volume_readback_mismatch")
        } catch (_: Exception) { ExecutionReceipt.Unknown("volume_effect_unverified") }
    }

    private fun media(action: PlatformAction, cancelled: () -> Boolean): ExecutionReceipt {
        val manager = try { audio() } catch (_: Exception) { null }
            ?: return ExecutionReceipt.NotExecuted("audio_service_unavailable")
        val key = when (action.arguments.text("key")) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            else -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        }
        if (cancelled()) return ExecutionReceipt.NotExecuted("platform_action_cancelled")
        return try {
            val now = SystemClock.uptimeMillis()
            manager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0))
            // Always pair an admitted DOWN; stopping cannot retract a submitted key.
            manager.dispatchMediaKeyEvent(KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, key, 0))
            ExecutionReceipt.Unknown("media_key_submitted_effect_unverified")
        } catch (_: Exception) { ExecutionReceipt.Unknown("media_key_effect_unverified") }
    }

    @Suppress("DEPRECATION")
    private fun wifi(action: PlatformAction, cancelled: () -> Boolean): ExecutionReceipt {
        // Public API is usable only on API 26–28 for this non-device-owner, target-37 app.
        if (Build.VERSION.SDK_INT >= 29) return ExecutionReceipt.NotExecuted("wifi_toggle_unsupported")
        if (app.checkSelfPermission(Manifest.permission.CHANGE_WIFI_STATE) != PackageManager.PERMISSION_GRANTED ||
            app.checkSelfPermission(Manifest.permission.ACCESS_WIFI_STATE) != PackageManager.PERMISSION_GRANTED)
            return ExecutionReceipt.NotExecuted("wifi_permission_missing")
        val manager = try { app.getSystemService(WifiManager::class.java) } catch (_: Exception) { null }
            ?: return ExecutionReceipt.NotExecuted("wifi_service_unavailable")
        val enabled = action.arguments.boolean("enabled")
        try { manager.wifiState } catch (_: Exception) { return ExecutionReceipt.NotExecuted("wifi_preflight_unavailable") }
        if (cancelled()) return ExecutionReceipt.NotExecuted("platform_action_cancelled")
        return try {
            manager.setWifiEnabled(enabled)
            val expected = if (enabled) WifiManager.WIFI_STATE_ENABLED else WifiManager.WIFI_STATE_DISABLED
            val deadline = SystemClock.elapsedRealtime() + 2000
            while (true) {
                val actual = manager.wifiState
                if (actual == expected) return ExecutionReceipt.Confirmed("{\"enabled\":$enabled,\"wifi_state\":$actual,\"read_back\":true}")
                if (cancelled() || SystemClock.elapsedRealtime() >= deadline) break
                Thread.sleep(50)
            }
            ExecutionReceipt.Unknown("wifi_readback_unconfirmed")
        } catch (_: Exception) { ExecutionReceipt.Unknown("wifi_effect_unverified") }
    }
}
