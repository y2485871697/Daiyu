package app.daiyu.android.voice.control

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.daiyu.android.MainActivity
import app.daiyu.android.R

/** One explicit foreground call; task removal ends it, never sticky-redials it. */
internal class VoiceCallService : Service() {
    private var activeOwner: Owner? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "语音通话", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val current = owner
        if (intent?.action == STOP) {
            if (intent.getLongExtra("owner", -1) == current?.id) end()
            return START_NOT_STICKY
        }
        if (current == null || intent?.getLongExtra("owner", -1) != current.id) {
            if (current == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        activeOwner = current
        try {
            val notification = notification()
            if (Build.VERSION.SDK_INT >= 30) startForeground(ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(ID, notification)
            current.ready = true
            current.started(true)
        } catch (_: RuntimeException) {
            if (owner === current) owner = null
            activeOwner = null
            current.started(false)
            current.stop()
            stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, (activeOwner?.id ?: 0).toInt(), Intent(this, VoiceCallService::class.java).setAction(STOP).putExtra("owner", activeOwner?.id ?: -1),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.task17_ic_stat_cat)
            .setContentTitle("代鱼语音通话中").setContentText("点击返回通话")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "挂断", stop).build()).build()
    }
    private fun end() {
        val previous = activeOwner
        activeOwner = null
        val owns = previous != null && owner === previous
        if (owns) owner = null
        try { if (owns) previous?.stop?.invoke() } finally {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }
    override fun onTaskRemoved(rootIntent: Intent?) { end(); super.onTaskRemoved(rootIntent) }
    override fun onDestroy() { end(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        private const val CHANNEL = "daiyu_voice_call"
        private const val ID = 1110
        private const val STOP = "app.daiyu.android.HANG_UP_VOICE_CALL"
        private class Owner(val id: Long, val stop: () -> Unit, val started: (Boolean) -> Unit) { var ready = false }
        private var serial = 0L
        private var owner: Owner? = null
        fun start(context: Context, stop: () -> Unit, started: (Boolean) -> Unit) {
            check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
            if (owner?.ready == true) { started(true); return }
            if (owner != null) { started(false); return }
            val next = Owner(++serial, stop, started)
            owner = next
            try { context.applicationContext.startForegroundService(Intent(context, VoiceCallService::class.java).putExtra("owner", next.id)) }
            catch (_: RuntimeException) { if (owner === next) owner = null; started(false) }
        }
        fun release(context: Context) {
            owner = null
            context.applicationContext.stopService(Intent(context, VoiceCallService::class.java))
        }
    }
}
