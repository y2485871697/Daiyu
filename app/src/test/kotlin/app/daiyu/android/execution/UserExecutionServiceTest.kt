package app.daiyu.android.execution

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.android.controller.ServiceController
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Config(sdk = [26, 36], manifest = Config.NONE, application = Application::class)
class UserExecutionServiceTest {
    private fun acquire(context: Context, stop: () -> Unit): UserExecutionLeases.Lease? {
        val task = FutureTask { UserExecutionService.acquire(context, stop) }
        Thread(task).start()
        if (context is StartingContext) {
            assertTrue(context.requested.await(2, TimeUnit.SECONDS))
            context.createService()
        }
        return task.get(3, TimeUnit.SECONDS)
    }
    private class StartingContext(private val application: Context) : ContextWrapper(application) {
        var service: ServiceController<UserExecutionService>? = null
        val requested = java.util.concurrent.CountDownLatch(1)
        private var intent: Intent? = null
        fun createService() {
            service = Robolectric.buildService(UserExecutionService::class.java, checkNotNull(intent)).create()
            checkNotNull(service).get().onStartCommand(intent, 0, 1)
        }
        override fun getApplicationContext(): Context = this
        override fun startForegroundService(intent: Intent): android.content.ComponentName {
            this.intent = intent
            requested.countDown()
            return android.content.ComponentName(application, UserExecutionService::class.java)
        }
    }

    @Test fun foregroundIsConfirmedBeforeAdmissionAndFinalReleaseRemovesNotification() {
        val context = StartingContext(RuntimeEnvironment.getApplication())
        val lease = checkNotNull(acquire(context) { fail("normal completion must not stop tasks") })
        val controller = checkNotNull(context.service)
        val service = controller.get()
        val shadow = shadowOf(service)
        assertEquals(1107, shadow.lastForegroundNotificationId)
        assertTrue(shadow.isLastForegroundNotificationAttached)
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            assertEquals(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, service.foregroundServiceType)
        }
        val notification = shadow.lastForegroundNotification
        assertEquals(Notification.FLAG_ONGOING_EVENT, notification.flags and Notification.FLAG_ONGOING_EVENT)
        assertEquals(Notification.FLAG_ONLY_ALERT_ONCE, notification.flags and Notification.FLAG_ONLY_ALERT_ONCE)
        val manager = service.getSystemService(NotificationManager::class.java)
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(notification.channelId).importance)
        assertEquals(android.app.Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
        UserExecutionService.release(lease)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(shadow.isStoppedBySelf)
        assertTrue(shadow.isForegroundStopped)
        controller.destroy()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun platformStartRejectionDoesNotAdmitOrStopUnrelatedTasks() {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun startForegroundService(intent: Intent): android.content.ComponentName {
                throw SecurityException("test start rejection")
            }
        }
        val stopped = AtomicInteger()
        assertNull(acquire(context) { stopped.incrementAndGet() })
        assertEquals(0, stopped.get())
    }

    @Test fun notificationStopKeepsForegroundUntilCallbackOwnerActuallyCloses() {
        val context = StartingContext(RuntimeEnvironment.getApplication())
        val stopped = java.util.concurrent.CountDownLatch(1)
        val lease = checkNotNull(acquire(context) { stopped.countDown() })
        val controller = checkNotNull(context.service)
        val service = controller.get()
        service.onStartCommand(Intent().setAction(UserExecutionService.ACTION_STOP), 0, 2)
        assertTrue(stopped.await(2, TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(shadowOf(service).isForegroundStopped)
        assertTrue(lease.awaitStarted(0))
        UserExecutionService.release(lease)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(shadowOf(service).isForegroundStopped)
        controller.destroy()
        shadowOf(Looper.getMainLooper()).idle()
    }
}
