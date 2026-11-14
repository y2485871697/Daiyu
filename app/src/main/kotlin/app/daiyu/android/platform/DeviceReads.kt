package app.daiyu.android.platform

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZonedDateTime

/** 适配 DeviceReads 的时间、网络和音频状态读取，来源见 docs/R06-platform-production.md。
 * 电池与设备状态由 AndroidReadOnlyTools 提供，DeviceActions 写操作另行处理。
 */
internal object DeviceReads {
    fun time(): JSONObject {
        val now = ZonedDateTime.now()
        return success(JSONObject().put("epoch_ms", now.toInstant().toEpochMilli())
            .put("local_iso", now.toString()).put("timezone", now.zone.id)
            .put("utc_offset_seconds", now.offset.totalSeconds))
    }

    fun network(context: Context): JSONObject {
        requirePermission(context, Manifest.permission.ACCESS_NETWORK_STATE)?.let { return it }
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return failure("unsupported")
        val network = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val transports = JSONArray()
        if (caps != null) {
            listOf("wifi" to NetworkCapabilities.TRANSPORT_WIFI, "cellular" to NetworkCapabilities.TRANSPORT_CELLULAR,
                "ethernet" to NetworkCapabilities.TRANSPORT_ETHERNET, "vpn" to NetworkCapabilities.TRANSPORT_VPN,
                "bluetooth" to NetworkCapabilities.TRANSPORT_BLUETOOTH).forEach { (name, id) ->
                if (caps.hasTransport(id)) transports.put(name)
            }
        }
        val data = JSONObject().put("active_network", network != null).put("transports", transports)
            .put("internet_capability", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false)
            .put("validated", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ?: false)
            .put("metered", cm.isActiveNetworkMetered)
        data.put("wifi", sample {
            requirePermission(context, Manifest.permission.ACCESS_WIFI_STATE)?.let { return@sample it }
            val wm = context.getSystemService(WifiManager::class.java) ?: return@sample failure("unsupported")
            success(JSONObject().put("enabled", wm.isWifiEnabled))
        })
        data.put("bluetooth", sample {
            val permission = if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH
            requirePermission(context, permission)?.let { return@sample it }
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                ?: return@sample failure("unsupported", "no_bluetooth_adapter")
            success(JSONObject().put("enabled", adapter.isEnabled))
        })
        return success(data)
    }

    fun audio(context: Context): JSONObject {
        val manager = context.getSystemService(AudioManager::class.java) ?: return failure("unsupported")
        val streams = JSONObject()
        // 分别读取媒体、铃声、闹钟和通知音量，以及各音量流的上下限。
        mapOf("music" to AudioManager.STREAM_MUSIC, "ring" to AudioManager.STREAM_RING,
            "alarm" to AudioManager.STREAM_ALARM, "notification" to AudioManager.STREAM_NOTIFICATION).forEach { (name, id) ->
            streams.put(name, JSONObject().put("level", manager.getStreamVolume(id))
                .put("maximum", manager.getStreamMaxVolume(id)).put("minimum", if (Build.VERSION.SDK_INT >= 28) manager.getStreamMinVolume(id) else 0))
        }
        return success(JSONObject().put("streams", streams).put("ringer_mode", manager.ringerMode)
            .put("music_active", manager.isMusicActive).put("volume_fixed", manager.isVolumeFixed))
    }

    private fun sample(block: () -> JSONObject): JSONObject = try { block() }
        catch (_: SecurityException) { failure("permission_denied") }
        catch (_: RuntimeException) { failure("unavailable") }
}
