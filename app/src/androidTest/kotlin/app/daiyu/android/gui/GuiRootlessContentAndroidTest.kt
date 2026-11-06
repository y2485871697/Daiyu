package app.daiyu.android.gui

import android.content.Context
import android.content.ContextWrapper
import android.test.InstrumentationTestCase
import android.view.accessibility.AccessibilityEvent
import app.daiyu.android.controlMain
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/** Real main-loop event/capture bookkeeping; platform images, witnesses and gestures are fake. */
@Suppress("DEPRECATION")
class GuiRootlessContentAndroidTest : InstrumentationTestCase() {
    private class Platform : GuiRuntimeAccess {
        val window = GuiVisualWindow("synthetic", "external.app", 10222,
            GuiBounds(0, 0, 1, 1), GuiBounds(0, 0, 1, 1), 0, emptyList())
        val windows = mutableListOf<(GuiVisualWindow?) -> Unit>()
        var captured: ((GuiCaptureFrame) -> Unit)? = null
        var completedGesture: ((Boolean) -> Unit)? = null
        var gestures = 0
        var disposed = 0
        override fun enabled(current: GuiAccessibilityService) = true
        override fun unlocked(current: GuiAccessibilityService) = true
        override fun visualWindow(current: GuiAccessibilityService, completed: (GuiVisualWindow?) -> Unit) { windows += completed }
        override fun capture(current: GuiAccessibilityService, success: (GuiCaptureFrame) -> Unit, failure: (Int) -> Unit) { captured = success }
        override fun gesture(current: GuiAccessibilityService, gesture: GuiGesture, completed: (Boolean) -> Unit): Boolean {
            gestures++; completedGesture = completed; return true
        }
        fun witness() { windows.removeAt(0)(window) }
        fun image(pixels: ByteArray = byteArrayOf(1)) {
            val success = checkNotNull(captured); captured = null
            success(object : GuiCaptureFrame {
                override fun encode(observationId: String) = GuiResult.Value(GuiScreenshot(observationId, 1, 1, pixels))
                override fun dispose(request: GuiCaptureRequest<GuiScreenshot>) { disposed++; request.resourcesDisposed() }
            })
        }
    }
    private fun fixture(block: (GuiAccessibilityService, GuiRunSession, Platform) -> Unit) {
        lateinit var service: GuiAccessibilityService
        lateinit var session: GuiRunSession
        lateinit var original: GuiRuntimeAccess
        lateinit var encoder: Executor
        val platform = Platform()
        controlMain {
            service = GuiAccessibilityService()
            ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java).apply { isAccessible = true }
                .invoke(service, instrumentation.targetContext)
            original = GuiRuntime.access; encoder = GuiRuntime.encoder
            GuiRuntime.access = platform; GuiRuntime.encoder = Executor { it.run() }; GuiRuntime.connected(service)
            val id = UUID.randomUUID().toString()
            session = (GuiRuntime.openForUser(GuiOwner(id, id)) as GuiResult.Value).value
            session.approveReadsFromUser(GuiReadApproval(0, 1))
        }
        try { block(service, session, platform) } finally {
            controlMain { session.close(); GuiRuntime.disconnected(service); GuiRuntime.access = original; GuiRuntime.encoder = encoder }
        }
    }
    private fun event(service: GuiAccessibilityService, pkg: String? = service.packageName,
        type: Int = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
        val event = AccessibilityEvent.obtain(type).apply { packageName = pkg }
        try { service.onAccessibilityEvent(event) } finally { event.recycle() }
    }
    private fun unsupported(session: GuiRunSession, platform: Platform): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= 30) return false
        controlMain {
            assertEquals(GuiResult.Rejected("VISUAL_CAPTURE_UNAVAILABLE_NOT_EXECUTED"),
                GuiRuntime.visualScreenshot(session.lease) { false }.get())
            assertTrue(platform.windows.isEmpty()); assertNull(platform.captured); assertEquals(0, platform.gestures)
        }
        return true
    }
    private fun capture(service: GuiAccessibilityService, session: GuiRunSession, platform: Platform,
        pkg: String? = service.packageName): GuiScreenshot {
        lateinit var pending: CompletableFuture<GuiResult<GuiScreenshot>>
        controlMain {
            pending = GuiRuntime.visualScreenshot(session.lease) { false }
            event(service, pkg); platform.witness(); event(service, pkg); platform.image(); event(service, pkg)
        }
        instrumentation.waitForIdleSync()
        controlMain { event(service, pkg); platform.witness() }
        return (pending.get() as GuiResult.Value).value
    }
    fun testOwnContentAcrossAsyncWitnessesKeepsPixelVerifiedSingleUseAction() = fixture { service, session, platform ->
        if (unsupported(session, platform)) return@fixture
        val image = capture(service, session, platform)
        lateinit var pending: CompletableFuture<GuiResult<String>>
        controlMain {
            for (type in listOf(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.TYPE_VIEW_SCROLLED,
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED)) event(service, type = type)
            pending = GuiRuntime.visualAction(session.lease, GuiAction(image.observationId, GuiOperation.COORDINATE_TAP,
                gesture = GuiGesture(0, 0, 0, 0, 50), visual = true)) { false }
            event(service); platform.witness(); platform.image()
        }
        instrumentation.waitForIdleSync()
        controlMain {
            event(service); platform.witness()
            assertEquals(1, platform.gestures); platform.completedGesture!!(true)
            assertEquals(GuiResult.Rejected("CAPTURE_ID_MISMATCH_NOT_EXECUTED"), GuiRuntime.visualAction(session.lease,
                GuiAction(image.observationId, GuiOperation.BACK, visual = true)) { false }.get())
            assertNull(session.lease.observation)
        }
        assertTrue(pending.get() is GuiResult.Value); assertEquals(2, platform.disposed)
    }
    fun testHardEventsStillRejectBeforePlatformCapture() {
        for ((pkg, type) in listOf("external.app" to AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            null to AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            "app.daiyu.android" to AccessibilityEvent.TYPE_WINDOWS_CHANGED)) fixture { service, session, platform ->
            if (unsupported(session, platform)) return@fixture
            controlMain {
                val pending = GuiRuntime.visualScreenshot(session.lease) { false }
                event(service, pkg, type); platform.witness()
                assertEquals(GuiResult.Rejected("CAPTURE_STALE_NOT_EXECUTED"), pending.get())
                assertNull(platform.captured); assertEquals(0, platform.gestures)
            }
        }
    }
    fun testChangedPixelsAfterOwnContentNeverDispatch() = fixture { service, session, platform ->
        if (unsupported(session, platform)) return@fixture
        val image = capture(service, session, platform)
        lateinit var pending: CompletableFuture<GuiResult<String>>
        controlMain {
            event(service)
            pending = GuiRuntime.visualAction(session.lease, GuiAction(image.observationId, GuiOperation.COORDINATE_TAP,
                gesture = GuiGesture(0, 0, 0, 0, 50), visual = true)) { false }
            platform.witness(); event(service); platform.image(byteArrayOf(2))
        }
        instrumentation.waitForIdleSync()
        controlMain { event(service); platform.witness(); assertEquals(0, platform.gestures) }
        assertEquals(GuiResult.Rejected("CAPTURE_CONTENT_CHANGED_NOT_EXECUTED"), pending.get())
        assertEquals(2, platform.disposed)
    }
    fun testTargetAndUnknownContentAcrossCaptureAndVerificationKeepsSingleUseGestures() {
        for (pkg in listOf("external.app", null, "")) fixture { service, session, platform ->
            if (unsupported(session, platform)) return@fixture
            val image = capture(service, session, platform, pkg)
            lateinit var pending: CompletableFuture<GuiResult<String>>
            controlMain {
                for (type in listOf(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.TYPE_VIEW_SCROLLED,
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED)) event(service, pkg, type)
                pending = GuiRuntime.visualAction(session.lease, GuiAction(image.observationId, GuiOperation.COORDINATE_TAP,
                    gesture = GuiGesture(0, 0, 0, 0, 50), visual = true)) { false }
                event(service, pkg); platform.witness(); event(service, pkg); platform.image()
            }
            instrumentation.waitForIdleSync()
            controlMain {
                event(service, pkg); platform.witness()
                assertEquals(1, platform.gestures); platform.completedGesture!!(true)
                assertEquals(GuiResult.Rejected("CAPTURE_ID_MISMATCH_NOT_EXECUTED"), GuiRuntime.visualAction(session.lease,
                    GuiAction(image.observationId, GuiOperation.BACK, visual = true)) { false }.get())
                assertNull(session.lease.observation)
            }
            assertTrue(pending.get() is GuiResult.Value); assertEquals(2, platform.disposed)
        }
    }

    fun testTargetContentStillRejectsChangedPixelsWithoutDispatching() = fixture { service, session, platform ->
        if (unsupported(session, platform)) return@fixture
        val image = capture(service, session, platform, "external.app")
        lateinit var pending: CompletableFuture<GuiResult<String>>
        controlMain {
            event(service, "external.app")
            pending = GuiRuntime.visualAction(session.lease, GuiAction(image.observationId, GuiOperation.COORDINATE_TAP,
                gesture = GuiGesture(0, 0, 0, 0, 50), visual = true)) { false }
            platform.witness(); event(service, null); platform.image(byteArrayOf(2))
        }
        instrumentation.waitForIdleSync()
        controlMain { event(service, "external.app"); platform.witness(); assertEquals(0, platform.gestures) }
        assertEquals(GuiResult.Rejected("CAPTURE_CONTENT_CHANGED_NOT_EXECUTED"), pending.get())
        assertEquals(2, platform.disposed)
    }
}
