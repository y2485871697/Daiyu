package app.daiyu.android.execution

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import app.daiyu.android.R
import java.util.concurrent.Executors

/** Holds only user-initiated chat execution, including its waiting and physical-drain phases. */
internal class UserExecutionService : Service() {
    private var owner: UserExecutionLeases.Owner? = null
    private var lastStartId = 0
    private val stops = Executors.newSingleThreadExecutor { work -> Thread(work, "daiyu-execution-stop") }

    override fun onCreate() {
        super.onCreate()
        instance = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "任务运行", NotificationManager.IMPORTANCE_LOW))
        ensureForeground()
    }

    private fun ensureForeground() {
        if (owner?.let(leases::isCurrent) == true) return
        val current = leases.attach()
        owner = current
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else startForeground(NOTIFICATION_ID, notification())
            leases.started(current)
        } catch (failure: RuntimeException) {
            Log.e("UserExecution", "Foreground execution could not start", failure)
            stopOwned(current)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent?.action == ACTION_STOP) owner?.let { stopOwned(it, revoke = false) }
        else { ensureForeground(); refresh() }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopOwned(current: UserExecutionLeases.Owner, revoke: Boolean = true) {
        val callbacks = if (revoke) leases.revoke(current) else leases.requestStop(current)
        stops.execute {
            callbacks.forEach { callback ->
                try { callback() } catch (failure: Exception) { Log.e("UserExecution", "Task stop failed", failure) }
            }
            main.post { if (instance === this) refresh() }
        }
    }

    private fun refresh() {
        if (lastStartId == 0) return
        val current = owner ?: return
        if (leases.retireIfIdle(current) || !leases.isCurrent(current)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(lastStartId)
        } else getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent().setClassName(this, "$packageName.MainActivity"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, UserExecutionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.task17_ic_stat_cat)
            .setContentTitle("代鱼任务运行中")
            .setContentText("任务运行或等待处理中")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "停止任务", stop).build())
            .build()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        owner?.let { stopOwned(it) }
        stops.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "daiyu_user_execution"
        private const val NOTIFICATION_ID = 1107
        internal const val ACTION_STOP = "app.daiyu.android.STOP_USER_EXECUTION"
        private val leases = UserExecutionLeases()
        private val main = Handler(Looper.getMainLooper())
        @Volatile private var instance: UserExecutionService? = null

        /** Worker only: no model request starts before Android confirms foreground promotion. */
        fun acquire(context: Context, onStop: () -> Unit): UserExecutionLeases.Lease? {
            check(Looper.myLooper() != Looper.getMainLooper())
            val lease = leases.acquire(onStop)
            try {
                context.applicationContext.startForegroundService(Intent(context, UserExecutionService::class.java))
                if (lease.awaitStarted(8_000)) return lease
            } catch (failure: RuntimeException) {
                Log.e("UserExecution", "Foreground execution start rejected", failure)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            release(lease)
            return null
        }

        fun release(lease: UserExecutionLeases.Lease) {
            lease.close()
            main.post { instance?.refresh() }
        }
    }
}
