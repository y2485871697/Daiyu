package app.daiyu.android.execution

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.test.InstrumentationTestCase
import app.daiyu.android.RunControlFixture
import app.daiyu.android.awaitControl
import app.daiyu.android.controlMain
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Suppress("DEPRECATION")
class UserExecutionServiceTest : InstrumentationTestCase() {
    private fun foregroundPresent(context: Context): Boolean =
        context.getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE).any {
            it.service.className == UserExecutionService::class.java.name && it.foreground
        }

    private fun waitUntil(expected: Boolean, context: Context) {
        val deadline = android.os.SystemClock.uptimeMillis() + 5_000
        while (foregroundPresent(context) != expected && android.os.SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertEquals(expected, foregroundPresent(context))
    }

    fun testForegroundLeaseAndNotificationStopWaitForConfirmedOwnerRelease() {
        val context = instrumentation.targetContext
        val stopped = CountDownLatch(1)
        val lease = checkNotNull(UserExecutionService.acquire(context) { stopped.countDown() })
        try {
            assertTrue(lease.awaitStarted(0))
            waitUntil(true, context)
            context.startService(Intent(context, UserExecutionService::class.java).setAction(UserExecutionService.ACTION_STOP))
            assertTrue(stopped.await(5, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            assertTrue(foregroundPresent(context))
        } finally { UserExecutionService.release(lease) }
        waitUntil(false, context)
    }

    fun testControllerPauseAndStopKeepForegroundUntilTransportActuallyExits() {
        val context = instrumentation.targetContext
        val fixture = RunControlFixture(context)
        val session = fixture.session(releaseOnCancel = false, holdSeconds = 30)
        try {
            awaitControl("controller ready") { fixture.controller.ready }
            assertNotNull(fixture.controller.send(null, "foreground lifetime"))
            assertTrue(session.entered.await(5, TimeUnit.SECONDS))
            val owner = checkNotNull(fixture.controller.active)
            waitUntil(true, context)
            controlMain { assertTrue(fixture.controller.pause(owner)) }
            assertTrue(session.cancelled.await(5, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            assertTrue(foregroundPresent(context))
            controlMain { assertTrue(fixture.controller.stop(owner)) }
            instrumentation.waitForIdleSync()
            assertEquals(1L, session.release.count)
            assertTrue(foregroundPresent(context))
            session.release.countDown()
            awaitControl("stopped provider and tools drained") { fixture.controller.active == null }
            waitUntil(false, context)
            assertEquals(1, fixture.opens.get())
        } finally {
            session.release.countDown()
            fixture.close()
        }
    }

    fun testControllerCompletionAndFailedPreparationLeaveNoIdleForegroundService() {
        val context = instrumentation.targetContext
        val fixture = RunControlFixture(context)
        try {
            awaitControl("controller ready") { fixture.controller.ready }
            val session = fixture.session(held = false)
            assertNotNull(fixture.controller.send(null, "one request"))
            assertTrue(session.entered.await(5, TimeUnit.SECONDS))
            awaitControl("normal completion") { fixture.controller.active == null }
            waitUntil(false, context)
            assertEquals(1, fixture.opens.get())
        } finally { fixture.close() }
        val rejected = RunControlFixture(context, beforeModel = { error("injected preparation failure") })
        try {
            awaitControl("rejected controller ready") { rejected.controller.ready }
            try { rejected.controller.send(null, "do not send") } catch (_: IllegalStateException) { }
            waitUntil(false, context)
            assertEquals(0, rejected.opens.get())
        } finally { rejected.close() }
    }

    fun testIndependentOwnersKeepForegroundUntilLastRelease() {
        val context = instrumentation.targetContext
        val first = checkNotNull(UserExecutionService.acquire(context) { fail("normal release must not stop") })
        val second = checkNotNull(UserExecutionService.acquire(context) { fail("normal release must not stop") })
        try {
            UserExecutionService.release(first)
            instrumentation.waitForIdleSync()
            assertTrue(foregroundPresent(context))
            assertTrue(second.awaitStarted(0))
        } finally {
            UserExecutionService.release(first)
            UserExecutionService.release(second)
        }
        waitUntil(false, context)
    }
}
