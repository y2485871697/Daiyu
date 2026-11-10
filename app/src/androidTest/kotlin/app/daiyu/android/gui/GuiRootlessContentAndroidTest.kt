package app.daiyu.android.gui

import android.graphics.Bitmap
import app.daiyu.android.diagnostics.RuntimeDiagnostics
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
        var window = GuiVisualWindow("synthetic", "external.app", 10222,
            GuiBounds(0, 0, 1, 1), GuiBounds(0, 0, 1, 1), 0, emptyList())
        val windows = mutableListOf<(GuiVisualWindow?) -> Unit>()
        var captured: ((GuiCaptureFrame) -> Unit)? = null
        var completedGesture: ((Boolean) -> Unit)? = null
        var gestures = 0
        var lastGesture: GuiGesture? = null
        val globalKeys = mutableListOf<Int>()
        override fun globalAction(current: GuiAccessibilityService, action: Int): Boolean { globalKeys += action; return true }
        var disposed = 0
        var rootReads = 0
        override fun root(current: GuiAccessibilityService): android.view.accessibility.AccessibilityNodeInfo? {
            rootReads++; return null
        }
        override fun windows(current: GuiAccessibilityService): List<android.view.accessibility.AccessibilityWindowInfo> = emptyList()
        override fun enabled(current: GuiAccessibilityService) = true
        override fun unlocked(current: GuiAccessibilityService) = true
        override fun visualWindow(current: GuiAccessibilityService, completed: (GuiVisualWindow?) -> Unit) { windows += completed }
        override fun capture(current: GuiAccessibilityService, success: (GuiCaptureFrame) -> Unit, failure: (Int) -> Unit) { captured = success }
        override fun gesture(current: GuiAccessibilityService, gesture: GuiGesture, completed: (Boolean) -> Unit): Boolean {
            gestures++; lastGesture = gesture; completedGesture = completed; return true
        }
        fun witness() { windows.removeAt(0)(window) }
        fun image(pixels: ByteArray = byteArrayOf(1), spatial: GuiScreenshot? = null) {
            val success = checkNotNull(captured); captured = null
            success(object : GuiCaptureFrame {
                override fun encode(observationId: String) = GuiResult.Value(spatial?.copy(observationId = observationId) ?: GuiScreenshot(observationId, 1, 1, pixels))
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
        pkg: String? = service.packageName, spatial: GuiScreenshot? = null, direct: Boolean = false): GuiScreenshot {
        lateinit var pending: CompletableFuture<GuiResult<GuiScreenshot>>
        controlMain {
            pending = if (direct) GuiRuntime.currentWindowScreenshot(session.lease) { false }
                else GuiRuntime.visualScreenshot(session.lease) { false }
            event(service, pkg); platform.witness(); event(service, pkg); platform.image(spatial = spatial); event(service, pkg)
        }
        instrumentation.waitForIdleSync()
        controlMain { event(service, pkg); platform.witness() }
        return (pending.get() as GuiResult.Value).value
    }
    fun testCurrentWindowScreenshotWithoutNodesUsesOneCaptureAndPublishesNoObservation() = fixture { service, session, platform ->
        if (unsupported(session, platform)) return@fixture
        val image = capture(service, session, platform, direct = true)
        assertFalse(image.observationBound); assertEquals("external.app", image.visualPackage)
        assertEquals(1, platform.rootReads)
        controlMain {
            assertNull(session.lease.observation)
            assertEquals(0, session.lease.reads)
            assertTrue(platform.windows.isEmpty()); assertNull(platform.captured)
        }
        assertEquals(1, platform.disposed); assertEquals(0, platform.gestures)
    }

    fun testOwnContentAcrossWindowWitnessKeepsSingleUseAction() = fixture { service, session, platform ->
        if (unsupported(session, platform)) return@fixture
        val image = capture(service, session, platform)
        val action = GuiAction(image.observationId, GuiOperation.COORDINATE_TAP,
            gesture = GuiGesture(0, 0, 0, 0, 50), visual = true)
        lateinit var pending: CompletableFuture<GuiResult<String>>
        controlMain {
            pending = GuiRuntime.visualAction(session.lease, action) { false }
            event(service); assertEquals(0, platform.gestures); assertNull(platform.captured)
            platform.witness(); assertEquals(1, platform.gestures)
            assertFalse(pending.isDone); platform.completedGesture!!(true); platform.completedGesture = null
            assertEquals(GuiResult.Rejected("CAPTURE_ID_MISMATCH_NOT_EXECUTED"), GuiRuntime.visualAction(session.lease, action) { false }.get())
        }
        assertTrue(pending.get() is GuiResult.Value); assertEquals(1, platform.disposed)
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
    fun testNoPixelEvidenceIsRequiredForWindowBoundSwipe() = fixture { service, session, platform ->
        if (unsupported(session, platform)) return@fixture
        val image = capture(service, session, platform)
        lateinit var pending: CompletableFuture<GuiResult<String>>
        controlMain {
            pending = GuiRuntime.visualAction(session.lease, GuiAction(image.observationId, GuiOperation.SWIPE,
                gesture = GuiGesture(0, 0, 0, 0, 500), visual = true)) { false }
            event(service); platform.witness()
            assertNull(platform.captured); assertEquals(1, platform.gestures)
            platform.completedGesture!!(true); platform.completedGesture = null
        }
        assertTrue(pending.get() is GuiResult.Value); assertEquals(1, platform.disposed)
    }
    fun testTargetAndUnknownContentAcrossWindowWitnessKeepsSingleUseGestures() = fixture { service, session, platform ->
        if (unsupported(session, platform)) return@fixture
        for (pkg in listOf("external.app", null, "", "unrelated.app")) {
            controlMain { session.approveReadsFromUser(GuiReadApproval(0, 1)) }
            val image = capture(service, session, platform, pkg)
            val action = GuiAction(image.observationId, GuiOperation.COORDINATE_TAP,
                gesture = GuiGesture(0, 0, 0, 0, 50), visual = true)
            lateinit var pending: CompletableFuture<GuiResult<String>>
            controlMain {
                pending = GuiRuntime.visualAction(session.lease, action) { false }
                event(service, pkg); platform.witness()
                platform.completedGesture!!(true); platform.completedGesture = null
                assertEquals(GuiResult.Rejected("CAPTURE_ID_MISMATCH_NOT_EXECUTED"), GuiRuntime.visualAction(session.lease, action) { false }.get())
                assertNull(platform.captured)
            }
            assertTrue(pending.get() is GuiResult.Value)
        }
        assertEquals(4, platform.gestures); assertEquals(4, platform.disposed)
    }

    fun testCancelledMetadataDecisionNeverDispatchesLate() = fixture { service, session, platform ->
        if (unsupported(session, platform)) return@fixture
        val image = capture(service, session, platform, "external.app")
        var cancelled = false
        lateinit var pending: CompletableFuture<GuiResult<String>>
        controlMain {
            pending = GuiRuntime.visualAction(session.lease, GuiAction(image.observationId, GuiOperation.COORDINATE_TAP,
                gesture = GuiGesture(0, 0, 0, 0, 50), visual = true)) { cancelled }
            cancelled = true; event(service, "external.app"); platform.witness()
            assertEquals(0, platform.gestures); assertNull(platform.captured)
        }
        assertEquals(GuiResult.Rejected("CANCELLED_NOT_EXECUTED"), pending.get()); assertEquals(1, platform.disposed)
    }
    private fun spatialImage(change: Pair<Int, Int>? = null, width: Int = 512, height: Int = 768,
        targetX: Int = 400, targetY: Int = 650): GuiScreenshot {
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(0xffeeeeee.toInt())
            for (x in (targetX - 8)..(targetX + 8)) for (y in (targetY - 8)..(targetY + 8))
                bitmap.setPixel(x, y, if ((x + y) % 2 == 0) 0xff000000.toInt() else 0xffffffff.toInt())
            change?.let { bitmap.setPixel(it.first, it.second, 0xff123456.toInt()) }
            val png = java.io.ByteArrayOutputStream().also {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }.toByteArray()
            return GuiScreenshot("fixture", width, height, png, visualPixels = GuiVisualPixels.from(bitmap))
        } finally { bitmap.recycle() }
    }
    fun testAllCoordinateGesturesUseOneWindowWitnessAndNoSecondCapture() = fixture { service, session, platform ->
        if (unsupported(session, platform)) return@fixture
        controlMain { platform.window = platform.window.copy(bounds = GuiBounds(0, 0, 512, 768), displayBounds = GuiBounds(0, 0, 512, 768)) }
        val gestures = listOf(
            GuiOperation.COORDINATE_TAP to GuiGesture(400, 650, 400, 650, 50),
            GuiOperation.COORDINATE_LONG_PRESS to GuiGesture(400, 650, 400, 650, 800),
            GuiOperation.SWIPE to GuiGesture(250, 600, 250, 200, 500),
            GuiOperation.SWIPE to GuiGesture(250, 200, 250, 600, 500),
            GuiOperation.SWIPE to GuiGesture(450, 400, 50, 400, 500),
            GuiOperation.SWIPE to GuiGesture(50, 400, 450, 400, 500))
        for ((index, pair) in gestures.withIndex()) {
            controlMain { session.approveReadsFromUser(GuiReadApproval(0, 1)) }
            val image = capture(service, session, platform, "external.app", spatialImage())
            val action = GuiAction(image.observationId, pair.first, gesture = pair.second, visual = true)
            lateinit var pending: CompletableFuture<GuiResult<String>>
            controlMain {
                pending = GuiRuntime.visualAction(session.lease, action) { false }
                event(service, "external.app"); assertEquals(index, platform.gestures)
                platform.witness(); assertEquals(index + 1, platform.gestures)
                assertEquals(pair.second, platform.lastGesture); assertNull(platform.captured)
                assertFalse(pending.isDone); platform.completedGesture!!(true); platform.completedGesture = null
                assertEquals(GuiResult.Rejected("CAPTURE_ID_MISMATCH_NOT_EXECUTED"), GuiRuntime.visualAction(session.lease, action) { false }.get())
            }
            assertTrue(pending.get() is GuiResult.Value)
        }
        assertEquals(6, platform.disposed)
    }
    fun testWindowGeometryIdentityUidAndOcclusionStillReject() = fixture { service, session, platform ->
        if (unsupported(session, platform)) return@fixture
        controlMain { platform.window = platform.window.copy(bounds = GuiBounds(0, 0, 512, 768), displayBounds = GuiBounds(0, 0, 512, 768)) }
        for (mode in 0..6) {
            controlMain { session.approveReadsFromUser(GuiReadApproval(0, 1)) }
            val original = capture(service, session, platform, "external.app", spatialImage())
            val window = platform.window
            val action = GuiAction(original.observationId, GuiOperation.COORDINATE_TAP,
                gesture = GuiGesture(400, 650, 400, 650, 50), visual = true)
            lateinit var pending: CompletableFuture<GuiResult<String>>
            controlMain {
                pending = GuiRuntime.visualAction(session.lease, action) { false }
                platform.window = when (mode) {
                    0 -> window.copy(identity = "replacement")
                    1 -> window.copy(displayBounds = GuiBounds(0, 0, 513, 768))
                    2 -> window.copy(packageName = "other.app")
                    3 -> window.copy(above = listOf(GuiVisualOccluder("overlay", GuiBounds(390, 640, 410, 660))))
                    4 -> window.copy(rotation = 90)
                    5 -> window.copy(uid = 10223)
                    else -> window.copy(bounds = GuiBounds(0, 0, 512, 700))
                }
                platform.witness(); assertEquals(0, platform.gestures); assertNull(platform.captured)
                platform.window = window
                assertEquals(GuiResult.Rejected("CAPTURE_ID_MISMATCH_NOT_EXECUTED"), GuiRuntime.visualAction(session.lease, action) { false }.get())
            }
            assertTrue(pending.get() is GuiResult.Rejected)
        }
        assertEquals(7, platform.disposed)
    }

    fun testVisualSystemKeysNeedOnlyCurrentWindowMetadata() = fixture { service, session, platform ->
        if (unsupported(session, platform)) return@fixture
        val keys = listOf(GuiOperation.BACK to android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK,
            GuiOperation.HOME to android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME,
            GuiOperation.RECENTS to android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS,
            GuiOperation.NOTIFICATIONS to android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
        for ((index, pair) in keys.withIndex()) {
            controlMain { session.approveReadsFromUser(GuiReadApproval(0, 1)) }
            val image = capture(service, session, platform)
            val action = GuiAction(image.observationId, pair.first, visual = true)
            lateinit var pending: CompletableFuture<GuiResult<String>>
            controlMain {
                pending = GuiRuntime.visualAction(session.lease, action) { false }
                assertEquals(index, platform.globalKeys.size)
                event(service, "external.app"); platform.witness()
                assertEquals(pair.second, platform.globalKeys.last())
                assertEquals(index + 1, platform.globalKeys.size)
                assertNull(platform.captured); assertEquals(0, platform.gestures)
                assertEquals(GuiResult.Rejected("CAPTURE_ID_MISMATCH_NOT_EXECUTED"), GuiRuntime.visualAction(session.lease, action) { false }.get())
            }
            assertTrue(pending.get() is GuiResult.Value)
        }
    }

    private val fixturePixelGesture = GuiGesture(1093, 2541, 1093, 2541, 50)
    private val fixturePixelWindow = GuiVisualWindow("fixture", "external.app", 10222,
        GuiBounds(0, 0, 1216, 2640), GuiBounds(0, 0, 1216, 2640), 0, emptyList())

    private fun fixturePixels(changes: List<Pair<Int, Int>> = emptyList()): GuiVisualPixels {
        val bitmap = Bitmap.createBitmap(1216, 2640, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(0xffeeeeee.toInt())
            bitmap.setPixel(1093, 2541, 0xff000000.toInt())
            changes.forEach { (x, y) -> bitmap.setPixel(x, y, 0xff123456.toInt()) }
            return GuiVisualPixels.from(bitmap)
        } finally { bitmap.recycle() }
    }

    fun testExact49TileRegionReportsSixSyntheticChangesWithoutBlockingAuthorizedTap() {
        val before = fixturePixels()
        val points = listOf(1000 to 2440, 1032 to 2440, 1064 to 2472, 1096 to 2504, 1128 to 2536, 1200 to 2639)
        val check = before.compareTap(fixturePixels(points), fixturePixelGesture, fixturePixelWindow)
        assertEquals(RuntimeDiagnostics.Code.LOCAL_PIXELS_CHANGED, check.reason)
        assertTrue(check.accepted); assertEquals(false, check.targetTileChanged)
        assertEquals(49, check.comparedTiles); assertEquals(6, check.changedTiles)
        assertEquals(GuiBounds(992, 2432, 1216, 2640), check.region)
        assertEquals(points.map { (x, y) -> GuiBounds(x / 32 * 32, y / 32 * 32,
            minOf(1216, x / 32 * 32 + 32), minOf(2640, y / 32 * 32 + 32)) }, check.changedTileBounds)
    }

    fun testChangedTargetPixelsAreAdmittedAndRemainDiagnosable() {
        val before = fixturePixels(); val changed = fixturePixels(listOf(1093 to 2541))
        val target = before.compareTap(changed, fixturePixelGesture, fixturePixelWindow)
        assertTrue(target.accepted); assertEquals(true, target.targetTileChanged)
        val fresh = changed.compareTap(fixturePixels(listOf(1093 to 2541)), fixturePixelGesture, fixturePixelWindow)
        assertTrue(fresh.accepted); assertEquals(0, fresh.changedTiles); assertTrue(fresh.changedTileBounds.isEmpty())
        assertTrue(before.compareTap(fixturePixels(listOf(10 to 10)), fixturePixelGesture, fixturePixelWindow).accepted)
    }

    fun testMissingPixelsAndOccludedOrChangedDisplaysRemainRejected() {
        val before = fixturePixels()
        assertFalse(before.compareTap(null, fixturePixelGesture, fixturePixelWindow).accepted)
        assertFalse(before.compareTap(before, fixturePixelGesture, fixturePixelWindow.copy(displayBounds = GuiBounds(0, 0, 1216, 2639))).accepted)
        assertFalse(before.compareTap(before, fixturePixelGesture, fixturePixelWindow.copy(above = listOf(
            GuiVisualOccluder("overlay", GuiBounds(1080, 2530, 1110, 2560))))).accepted)
    }
}
