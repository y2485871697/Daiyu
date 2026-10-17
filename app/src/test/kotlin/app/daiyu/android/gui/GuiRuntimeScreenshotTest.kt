package app.daiyu.android.gui

import android.graphics.Rect
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** Exercises the real runtime/gate/traversal/callback path, not a second model of its policy. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@LooperMode(LooperMode.Mode.PAUSED)
@Suppress("DEPRECATION")
class GuiRuntimeScreenshotTest {
    private lateinit var service: GuiAccessibilityService
    private lateinit var session: GuiRunSession
    private lateinit var platform: Platform
    private lateinit var worker: QueuedWorker
    private lateinit var originalAccess: GuiRuntimeAccess
    private lateinit var originalEncoder: Executor

    private class QueuedWorker : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun runAll(deliver: Boolean = true) {
            while (tasks.isNotEmpty()) {
                val task = tasks.removeFirst()
                var failure: Throwable? = null
                val thread = Thread { try { task.run() } catch (caught: Throwable) { failure = caught } }
                thread.start(); thread.join(5_000)
                check(!thread.isAlive) { "encoder did not finish" }
                failure?.let { throw AssertionError("encoder task failed", it) }
            }
            if (deliver) shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private class Frame : GuiCaptureFrame {
        var encoded = 0
        var encodeFailure = false
        var disposalFailure = false
        var disposalRequest: GuiCaptureRequest<GuiScreenshot>? = null
        val disposed = mutableListOf<String>()
        override fun encode(observationId: String): GuiResult<GuiScreenshot> {
            check(Looper.myLooper() !== Looper.getMainLooper())
            encoded++
            if (encodeFailure) error("encoding failed")
            return GuiResult.Value(GuiScreenshot(observationId, 1, 1, byteArrayOf(1)))
        }
        override fun dispose(request: GuiCaptureRequest<GuiScreenshot>) {
            check(Looper.myLooper() === Looper.getMainLooper())
            disposalRequest = request
            request.disposeSuccessResources(
                { disposed += "software"; if (disposalFailure) error("disposal failed") },
                { disposed += "hardware" }, { disposed += "buffer" })
        }
    }

    private class Platform : GuiRuntimeAccess {
        var pkg = "external.app"
        var windowId = 7
        var displayId = 0
        var label = "before"
        var missing = false
        var throwOnSubmit = false
        var rootReads = 0
        val callbacks = mutableListOf<Pair<(GuiCaptureFrame) -> Unit, (Int) -> Unit>>()
        override fun enabled(current: GuiAccessibilityService) = true
        override fun unlocked(current: GuiAccessibilityService) = true
        override fun root(current: GuiAccessibilityService): AccessibilityNodeInfo? {
            rootReads++
            if (missing) return null
            val window = AccessibilityWindowInfo.obtain()
            // Android's hidden setters initialize real public-getter state; traversal/admission
            // still run unchanged. Only the platform source/capture and worker queue are controlled.
            ReflectionHelpers.callInstanceMethod<Unit>(window, "setId", ClassParameter.from(Int::class.javaPrimitiveType!!, windowId))
            ReflectionHelpers.callInstanceMethod<Unit>(window, "setType", ClassParameter.from(Int::class.javaPrimitiveType!!, AccessibilityWindowInfo.TYPE_APPLICATION))
            ReflectionHelpers.callInstanceMethod<Unit>(window, "setActive", ClassParameter.from(Boolean::class.javaPrimitiveType!!, true))
            ReflectionHelpers.callInstanceMethod<Unit>(window, "setDisplayId", ClassParameter.from(Int::class.javaPrimitiveType!!, displayId))
            fun node(text: String): AccessibilityNodeInfo = AccessibilityNodeInfo.obtain().apply {
                packageName = pkg; className = "View"; this.text = text
                isEnabled = true; isVisibleToUser = true; isClickable = true
                setBoundsInScreen(Rect(0, 0, 100, 100))
                ReflectionHelpers.setField(this, "mWindowId", windowId)
                shadowOf(this).setWindow(window)
            }
            val root = node("root")
            shadowOf(root).addChild(node(label))
            return root
        }
        override fun capture(current: GuiAccessibilityService, success: (GuiCaptureFrame) -> Unit, failure: (Int) -> Unit) {
            callbacks += success to failure
            if (throwOnSubmit) error("accepted but transport threw")
        }
        fun success(frame: Frame = Frame()): Frame { callbacks.removeAt(0).first(frame); return frame }
    }

    @Before fun setUp() {
        originalAccess = GuiRuntime.access; originalEncoder = GuiRuntime.encoder
        platform = Platform(); worker = QueuedWorker()
        GuiRuntime.access = platform; GuiRuntime.encoder = worker
        service = Robolectric.buildService(GuiAccessibilityService::class.java).create().get()
        GuiRuntime.connected(service)
        session = (GuiRuntime.openForUser(GuiOwner("session", "run")) as GuiResult.Value).value
        session.approveReadsFromUser(GuiReadApproval(3, 1))
    }

    @After fun tearDown() {
        session.close()
        while (platform.callbacks.isNotEmpty()) platform.callbacks.removeAt(0).second(1)
        worker.runAll()
        GuiRuntime.disconnected(service)
        GuiRuntime.access = originalAccess; GuiRuntime.encoder = originalEncoder
    }

    private fun observe(): GuiObservation = (session.previewFromUser() as GuiResult.Value).value
    private fun approve(observation: GuiObservation): GuiAction =
        GuiAction(observation.observationId, GuiOperation.TAP, 1).also {
            assertTrue(session.approveExactActionFromUser(it) is GuiResult.Value)
        }
    private fun screenshot(observation: GuiObservation) = GuiRuntime.screenshot(session.lease, observation.observationId)
    private fun rejected(result: GuiResult<*>, code: String) { assertEquals(GuiResult.Rejected(code), result) }
    private fun assertDisposed(frame: Frame) { assertEquals(listOf("software", "hardware", "buffer"), frame.disposed) }

    @Test fun windowChangesWithoutAnEventRefuseBeforePlatformSubmission() {
        val observation = observe(); platform.windowId++
        rejected(screenshot(observation).join(), "SCREENSHOT_WINDOW_CHANGED")
        assertTrue(platform.callbacks.isEmpty())
        assertEquals(1, session.lease.screenshots)
    }

    @Test fun nodeChangesWithoutAnEventRefuseBeforePlatformSubmission() {
        val observation = observe(); platform.label = "after"
        rejected(screenshot(observation).join(), "SCREENSHOT_TREE_CHANGED")
        assertTrue(platform.callbacks.isEmpty())
    }

    @Test fun packageChangesWithoutAnEventRefuseBeforePlatformSubmission() {
        val observation = observe(); platform.pkg = "another.app"
        rejected(screenshot(observation).join(), "SCREENSHOT_WINDOW_CHANGED")
        assertTrue(platform.callbacks.isEmpty())
    }

    @Test fun movedDisplayOrUnavailableTreeCannotBeVerified() {
        val observation = observe(); platform.displayId = 1
        rejected(screenshot(observation).join(), "SCREENSHOT_TREE_UNVERIFIABLE")
        platform.displayId = 0; platform.missing = true
        rejected(screenshot(observation).join(), "SCREENSHOT_TREE_UNVERIFIABLE")
        assertTrue(platform.callbacks.isEmpty())
    }

    @Test fun truncatedObservationCannotAuthorizeAPartialTreeMatch() {
        platform.label = "x".repeat(513)
        val observation = observe()
        assertTrue(observation.truncated)
        rejected(screenshot(observation).join(), "SCREENSHOT_TREE_UNVERIFIABLE")
        assertTrue(platform.callbacks.isEmpty())
    }

    @Test fun failedReplacementRetiresOldIdAndExactActions() {
        val observation = observe(); approve(observation)
        platform.missing = true
        rejected(session.previewFromUser(), "NO_ACTIVE_EXTERNAL_WINDOW")
        assertNull(session.lease.observation)
        assertTrue(session.lease.actions.isEmpty())
        rejected(screenshot(observation).join(), "OBSERVATION_REQUIRED")
        rejected(session.approveExactActionFromUser(GuiAction(observation.observationId, GuiOperation.TAP, 1)), "OBSERVATION_REQUIRED")
    }

    @Test fun successfulCaptureDoesNotPublishAnObservationOrChangeApproval() {
        val observation = observe(); val action = approve(observation)
        val remainingReads = session.lease.reads
        val future = screenshot(observation)
        val frame = platform.success()
        assertFalse(future.isDone)
        assertEquals(0, frame.encoded)
        worker.runAll()
        val image = (future.join() as GuiResult.Value).value
        assertEquals(observation.observationId, image.observationId)
        assertSame(observation, session.lease.observation)
        assertEquals(setOf(action), session.lease.actions)
        assertEquals(remainingReads, session.lease.reads)
        assertEquals(0, session.lease.screenshots)
        assertEquals(3, platform.rootReads) // published, pre-submit, post-encode/pre-delivery
        assertDisposed(frame)
        session.close(); assertTrue(session.releaseConfirmed)
    }

    @Test fun nodeChangeDuringWorkerQueueIsRejectedAtDeliveryAndResourcesReleased() {
        val observation = observe(); val action = approve(observation)
        val future = screenshot(observation); val frame = platform.success()
        platform.label = "changed without accessibility event"
        worker.runAll()
        rejected(future.join(), "SCREENSHOT_TREE_CHANGED")
        assertSame(observation, session.lease.observation)
        assertEquals(setOf(action), session.lease.actions)
        assertDisposed(frame)
        session.close(); assertTrue(session.releaseConfirmed)
    }

    @Test fun treeChangeAfterEncodingButBeforeMainDeliveryIsStillRejected() {
        val observation = observe(); val future = screenshot(observation)
        val frame = platform.success()
        worker.runAll(deliver = false)
        assertEquals(1, frame.encoded); assertFalse(future.isDone)
        platform.label = "changed after encoding"
        shadowOf(Looper.getMainLooper()).idle()
        rejected(future.join(), "SCREENSHOT_TREE_CHANGED")
        assertDisposed(frame)
    }

    @Test fun displayChangeBeforeDeliveryCannotBeVerified() {
        val observation = observe(); val future = screenshot(observation)
        val frame = platform.success(); platform.displayId = 1
        worker.runAll()
        rejected(future.join(), "SCREENSHOT_TREE_UNVERIFIABLE")
        assertDisposed(frame)
    }

    @Test fun windowChangeBeforeLateCallbackIsRejectedAtDelivery() {
        val observation = observe(); val future = screenshot(observation)
        platform.windowId++
        val frame = platform.success(); worker.runAll()
        rejected(future.join(), "SCREENSHOT_WINDOW_CHANGED")
        assertDisposed(frame)
    }

    @Test fun closeBeforeLateCallbackKeepsDrainUntilAllResourcesAreDisposed() {
        val observation = observe(); val future = screenshot(observation)
        session.close(); assertFalse(session.releaseConfirmed)
        rejected(GuiRuntime.openForUser(GuiOwner("next", "run")), "FOREGROUND_DRAIN_PENDING_NOT_EXECUTED")
        val frame = platform.success()
        rejected(future.join(), "GUI_AUTHORIZATION_REVOKED")
        assertEquals(0, frame.encoded); assertTrue(worker.tasks.isEmpty())
        assertDisposed(frame); assertTrue(session.releaseConfirmed)
    }

    @Test fun cancellationWhileEncodingIsQueuedDoesNotReleaseReservationEarly() {
        val observation = observe(); val future = screenshot(observation)
        val frame = platform.success()
        future.cancel(false); session.close()
        assertFalse(session.releaseConfirmed); assertTrue(frame.disposed.isEmpty())
        worker.runAll()
        assertEquals(0, frame.encoded)
        assertDisposed(frame); assertTrue(session.releaseConfirmed)
    }

    @Test fun closeWhileEncodingIsQueuedRejectsDeliveryButStillDrains() {
        val observation = observe(); val future = screenshot(observation)
        val frame = platform.success(); session.close()
        assertFalse(session.releaseConfirmed)
        worker.runAll()
        rejected(future.join(), "GUI_AUTHORIZATION_REVOKED")
        assertDisposed(frame); assertTrue(session.releaseConfirmed)
    }

    @Test fun encoderRejectionDisposesAndDoesNotHoldTheSlot() {
        val observation = observe(); val future = screenshot(observation)
        GuiRuntime.encoder = Executor { throw RejectedExecutionException() }
        val frame = platform.success()
        rejected(future.join(), "SCREENSHOT_ENCODING_UNAVAILABLE")
        assertDisposed(frame); session.close(); assertTrue(session.releaseConfirmed)
    }

    @Test fun encodingExceptionDisposesAndDoesNotHoldTheSlot() {
        val observation = observe(); val future = screenshot(observation)
        val frame = platform.success(Frame().apply { encodeFailure = true })
        worker.runAll()
        rejected(future.join(), "SCREENSHOT_UNAVAILABLE")
        assertDisposed(frame); session.close(); assertTrue(session.releaseConfirmed)
    }

    @Test fun submissionUnknownRetainsReservationUntilLateSuccessDisposal() {
        val observation = observe(); platform.throwOnSubmit = true
        val future = screenshot(observation)
        rejected(future.join(), "SCREENSHOT_DELIVERY_UNCONFIRMED")
        session.close(); assertFalse(session.releaseConfirmed)
        val frame = platform.success()
        assertEquals(0, frame.encoded); assertDisposed(frame)
        assertTrue(session.releaseConfirmed)
        rejected(future.join(), "SCREENSHOT_DELIVERY_UNCONFIRMED")
    }

    @Test fun failedDisposalAttemptsEveryResourceAndCannotManufactureDrain() {
        val observation = observe(); screenshot(observation)
        val frame = platform.success(Frame().apply { disposalFailure = true })
        session.close(); worker.runAll()
        assertDisposed(frame); assertFalse(session.releaseConfirmed)
        frame.disposalFailure = false
        frame.dispose(frame.disposalRequest!!)
        assertTrue(session.releaseConfirmed)
    }
}
