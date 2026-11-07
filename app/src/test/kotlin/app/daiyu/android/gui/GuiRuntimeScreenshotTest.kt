package app.daiyu.android.gui

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import app.daiyu.android.tasksurface.TaskSurfaceSelfTarget
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
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
import org.robolectric.annotation.RealObject
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAccessibilityNodeInfo
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** Exercises the real runtime/gate/traversal/callback path, not a second model of its policy. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], shadows = [GuiRuntimeScreenshotTest.CopyingWindowNodeShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
@Suppress("DEPRECATION")
class GuiRuntimeScreenshotTest {
    // A caller owns the returned window, not the node shadow's backing instance.
    @Implements(AccessibilityNodeInfo::class)
    class CopyingWindowNodeShadow : ShadowAccessibilityNodeInfo() {
        @RealObject private lateinit var actual: AccessibilityNodeInfo
        companion object {
            var refresh: ((AccessibilityNodeInfo) -> Boolean)? = null
            var childReads = 0
        }
        @Implementation public override fun getChild(index: Int): AccessibilityNodeInfo? {
            childReads++
            return super.getChild(index)
        }
        @Implementation public override fun refresh(): Boolean =
            super.refresh() && (refresh?.invoke(actual) ?: true)
        @Implementation
        public override fun getWindow(): AccessibilityWindowInfo? =
            super.getWindow()?.let { AccessibilityWindowInfo.obtain(it) }
    }

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
        var pixels = byteArrayOf(1)
        var image: GuiScreenshot? = null
        var encoded = 0
        var validationOnly = 0
        var encodeFailure = false
        var disposalFailure = false
        var disposalRequest: GuiCaptureRequest<GuiScreenshot>? = null
        val disposed = mutableListOf<String>()
        override fun encode(observationId: String): GuiResult<GuiScreenshot> {
            check(Looper.myLooper() !== Looper.getMainLooper())
            encoded++
            if (encodeFailure) error("encoding failed")
            return GuiResult.Value(image?.copy(observationId = observationId) ?: GuiScreenshot(observationId, 1, 1, pixels))
        }
        override fun validation(observationId: String): GuiResult<GuiScreenshot> {
            check(Looper.myLooper() !== Looper.getMainLooper())
            validationOnly++
            if (encodeFailure) error("validation encoding failed")
            return GuiResult.Value(image?.copy(observationId = observationId, png = ByteArray(0))
                ?: GuiScreenshot(observationId, 1, 1, ByteArray(0)))
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
        var active = true
        var windowType = AccessibilityWindowInfo.TYPE_APPLICATION
        var windowLayer = 0
        var windowBounds = Rect(0, 0, 100, 100)
        var rootBounds = Rect(0, 0, 100, 100)
        var gestures = 0
        var gestureAccepted = true
        var gestureCompleted: ((Boolean) -> Unit)? = null
        var submittedGesture: GuiGesture? = null
        override fun gesture(current: GuiAccessibilityService, gesture: GuiGesture, completed: (Boolean) -> Unit): Boolean {
            gestures++; submittedGesture = gesture; gestureCompleted = completed
            return gestureAccepted
        }
        var editable = false
        var focused = false
        var imeAdvertised = false
        var nodeActions = 0
        var rootClickable = true
        var actionAccepted = true
        var label = "before"
        var rootLabel = "root"
        var missing = false
        var throwOnSubmit = false
        var permissionEnabled = true
        var screenUnlocked = true
        var rootReads = 0
        var onRootRead: (() -> Unit)? = null
        var stableNodes = false
        var targetRefresh = true
        var targetBounds = Rect(0, 0, 100, 100)
        var descendants = 0
        var wrapper = false
        var targetChildren = 0
        var refreshCount = 0
        var onRefresh: ((Int) -> Unit)? = null
        var childLabel = "更多"
        var savedRoot: AccessibilityNodeInfo? = null
        var savedTarget: AccessibilityNodeInfo? = null
        var savedLabel: AccessibilityNodeInfo? = null
        fun refresh(node: AccessibilityNodeInfo): Boolean {
            val source = savedTarget?.takeIf { node == it } ?: return true
            if (!targetRefresh) return false
            refreshCount++; onRefresh?.invoke(refreshCount)
            ReflectionHelpers.setField(shadowOf(node), "parent", ReflectionHelpers.getField<AccessibilityNodeInfo?>(shadowOf(source), "parent"))
            node.text = source.text
            node.setBoundsInScreen(targetBounds)
            return true
        }
        var visual: GuiVisualWindow? = null
        var visualReads = 0
        var deferVisual = false
        val visualCallbacks = mutableListOf<(GuiVisualWindow?) -> Unit>()
        override fun visualWindow(current: GuiAccessibilityService, completed: (GuiVisualWindow?) -> Unit) {
            visualReads++
            if (deferVisual) visualCallbacks += completed else completed(visual)
        }
        fun completeVisual() { visualCallbacks.removeAt(0)(visual) }
        val callbacks = mutableListOf<Pair<(GuiCaptureFrame) -> Unit, (Int) -> Unit>>()
        override fun enabled(current: GuiAccessibilityService) = permissionEnabled
        override fun unlocked(current: GuiAccessibilityService) = screenUnlocked
        var listedWindows: (() -> List<AccessibilityWindowInfo>)? = null
        var windowReads = 0
        var configuration: android.accessibilityservice.AccessibilityServiceInfo? = null
        var configurationFailure = false
        var configurationReads = 0
        override fun configuration(current: GuiAccessibilityService): android.accessibilityservice.AccessibilityServiceInfo? {
            configurationReads++
            if (configurationFailure) error("private-config-source-exception")
            return configuration
        }
        var cacheClears = 0
        var cacheCleared = false
        var cacheClearAccepted = false
        var cacheClearFailure = false
        override fun windows(current: GuiAccessibilityService): List<AccessibilityWindowInfo> {
            windowReads++
            return listedWindows?.invoke().orEmpty()
        }
        override fun clearWindowCache(current: GuiAccessibilityService): Boolean {
            cacheClears++
            if (cacheClearFailure) error("cache unavailable")
            cacheCleared = cacheClearAccepted
            return cacheCleared
        }
        override fun root(current: GuiAccessibilityService): AccessibilityNodeInfo? {
            rootReads++
            onRootRead?.invoke()
            if (missing) return null
            val window = AccessibilityWindowInfo.obtain()
            // Android's hidden setters initialize real public-getter state; traversal/admission
            // still run unchanged. Only the platform source/capture and worker queue are controlled.
            ReflectionHelpers.callInstanceMethod<Unit>(window, "setId", ClassParameter.from(Int::class.javaPrimitiveType!!, windowId))
            ReflectionHelpers.callInstanceMethod<Unit>(window, "setType", ClassParameter.from(Int::class.javaPrimitiveType!!, windowType))
            ReflectionHelpers.callInstanceMethod<Unit>(window, "setActive", ClassParameter.from(Boolean::class.javaPrimitiveType!!, active))
            ReflectionHelpers.callInstanceMethod<Unit>(window, "setLayer", ClassParameter.from(Int::class.javaPrimitiveType!!, windowLayer))
            if (android.os.Build.VERSION.SDK_INT >= 30)
                ReflectionHelpers.callInstanceMethod<Unit>(window, "setDisplayId", ClassParameter.from(Int::class.javaPrimitiveType!!, displayId))
            shadowOf(window).setBoundsInScreen(windowBounds)
            fun node(text: String): AccessibilityNodeInfo = AccessibilityNodeInfo.obtain().apply {
                packageName = pkg; className = "View"; this.text = text
                isEnabled = true; isVisibleToUser = true; isClickable = true
                isEditable = editable; isFocused = focused
                if (imeAdvertised) addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER)
                setBoundsInScreen(Rect(0, 0, 100, 100))
                ReflectionHelpers.setField(this, "mWindowId", this@Platform.windowId)
                shadowOf(this).setAccessibilityWindowInfo(window)
                shadowOf(this).setRefreshReturnValue(true)
                shadowOf(this).setOnPerformActionListener { _, _ -> nodeActions++; actionAccepted }
            }
            val root = if (stableNodes) savedRoot ?: node(rootLabel).also { savedRoot = it } else node(rootLabel)
            root.text = rootLabel; root.isClickable = rootClickable; root.packageName = pkg
            root.setBoundsInScreen(rootBounds)
            ReflectionHelpers.setField(root, "mWindowId", windowId)
            shadowOf(root).setAccessibilityWindowInfo(window)
            if (!stableNodes || savedTarget == null) {
                val target = node(if (wrapper) "" else label)
                if (stableNodes) savedTarget = target
                shadowOf(root).addChild(target)
                if (wrapper) shadowOf(target).addChild(node(childLabel).also { if (stableNodes) savedLabel = it })
                repeat(targetChildren) { shadowOf(target).addChild(node("target-child-$it")) }
                repeat(descendants) { shadowOf(root).addChild(node("body-$it")) }
            }
            if (stableNodes) {
                savedTarget!!.text = if (wrapper) "" else label
                savedTarget!!.setBoundsInScreen(targetBounds)
                savedLabel?.text = childLabel
            }
            return if (stableNodes) AccessibilityNodeInfo.obtain(root) else root
        }
        var appLaunches = 0
        override fun launchIntent(current: GuiAccessibilityService, packageName: String): android.content.Intent =
            android.content.Intent(android.content.Intent.ACTION_MAIN)
                .setComponent(android.content.ComponentName(packageName, "$packageName.MainActivity"))
        override fun startActivity(current: GuiAccessibilityService, intent: android.content.Intent) {
            appLaunches++
            pkg = intent.component!!.packageName
            windowId++
            stableNodes = false; savedRoot = null; savedTarget = null; savedLabel = null
            descendants = 0
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
        CopyingWindowNodeShadow.refresh = platform::refresh
        CopyingWindowNodeShadow.childReads = 0
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
        CopyingWindowNodeShadow.refresh = null
    }

    private fun permitGestureWindow(overlay: Boolean = false) {
        platform.listedWindows = {
            val application = listedWindow(pkg = platform.pkg, id = platform.windowId, active = platform.active, layer = 1)
            shadowOf(application).setBoundsInScreen(Rect(0, 0, 100, 100))
            assertTrue(application.isActive)
            assertEquals(Rect(0, 0, 100, 100), Rect().also { application.getBoundsInScreen(it) })
            buildList {
                add(application)
                if (overlay) add(AccessibilityWindowInfo.obtain().apply {
                    shadowOf(this).apply {
                        setId(99); setType(AccessibilityWindowInfo.TYPE_INPUT_METHOD); setLayer(2)
                        if (android.os.Build.VERSION.SDK_INT >= 30) setDisplayId(0)
                        setBoundsInScreen(Rect(10, 10, 40, 40))
                    }
                })
            }
        }
    }
    @Test @Config(sdk = [26, 30]) fun explicitCoordinateTapWaitsForTerminalGestureReceiptAndRetiresObservation() {
        permitGestureWindow()
        val observed = observe()
        val action = GuiAction(observed.observationId, GuiOperation.COORDINATE_TAP, gesture = GuiGesture(20, 30, 20, 30, 50))
        assertTrue(session.approveExactActionFromUser(action) is GuiResult.Value)
        val result = GuiRuntime.beginAction(session.lease, action)
        assertTrue(result.toString(), result is GuiResult.Value)
        val started = result as GuiResult.Value
        assertEquals(1, platform.gestures)
        assertEquals(action.gesture, platform.submittedGesture)
        assertFalse(started.value.isDone)
        rejected(GuiRuntime.observe(session.lease), "GESTURE_IN_PROGRESS_NOT_EXECUTED")
        rejected(GuiRuntime.beginAction(session.lease, action), "GESTURE_IN_PROGRESS_NOT_EXECUTED")
        rejected(GuiRuntime.launchApp(session.lease, GuiAppLaunch("external.app")), "GESTURE_IN_PROGRESS_NOT_EXECUTED")
        assertEquals(1, platform.gestures)
        platform.gestureCompleted!!(true)
        assertEquals(GuiResult.Value("PLATFORM_GESTURE_COMPLETED_NOT_VERIFIED"), started.value.completed())
        rejected(GuiRuntime.validateExactTarget(session.lease, action), "OBSERVATION_REQUIRED")
    }
    @Test fun freeSwipeKeepsScreenLeaseUntilCancelledCallbackAndNeverRetries() {
        permitGestureWindow()
        val observed = observe()
        val action = GuiAction(observed.observationId, GuiOperation.SWIPE, gesture = GuiGesture(50, 90, 50, 10, 800))
        assertTrue(session.approveExactActionFromUser(action) is GuiResult.Value)
        val result = GuiRuntime.beginAction(session.lease, action)
        assertTrue(result.toString(), result is GuiResult.Value)
        val started = result as GuiResult.Value
        session.close()
        assertFalse(session.releaseConfirmed)
        rejected(GuiRuntime.openForUser(GuiOwner("other", "run")), "FOREGROUND_DRAIN_PENDING_NOT_EXECUTED")
        platform.gestureCompleted!!(false)
        assertEquals(GuiResult.Unknown("GESTURE_CANCELLED_UNCONFIRMED"), started.value.completed())
        assertTrue(session.releaseConfirmed)
        assertEquals(1, platform.gestures)
    }
    @Test fun coordinatesOutsideApplicationOrOnChangedTreeNeverDispatch() {
        permitGestureWindow()
        var observed = observe()
        var action = GuiAction(observed.observationId, GuiOperation.COORDINATE_TAP, gesture = GuiGesture(100, 30, 100, 30, 50))
        assertTrue(session.approveExactActionFromUser(action) is GuiResult.Value)
        rejected(GuiRuntime.action(session.lease, action), "GESTURE_OUTSIDE_OBSERVED_WINDOW_NOT_EXECUTED")
        observed = observe()
        action = GuiAction(observed.observationId, GuiOperation.SWIPE, gesture = GuiGesture(50, 90, 50, 10, 500))
        assertTrue(session.approveExactActionFromUser(action) is GuiResult.Value)
        platform.label = "changed-without-event"
        rejected(GuiRuntime.action(session.lease, action), "GESTURE_TREE_CHANGED_OR_INCOMPLETE_NOT_EXECUTED")
        assertEquals(0, platform.gestures)
    }
    @Test fun keyboardOrOverlayCoveringCoordinateNeverReceivesTheAuthorizedAppGesture() {
        permitGestureWindow(overlay = true)
        val observed = observe()
        val action = GuiAction(observed.observationId, GuiOperation.COORDINATE_TAP, gesture = GuiGesture(20, 30, 20, 30, 50))
        assertTrue(session.approveExactActionFromUser(action) is GuiResult.Value)
        rejected(GuiRuntime.action(session.lease, action), "GESTURE_WINDOW_OCCLUDED_NOT_EXECUTED")
        assertEquals(0, platform.gestures)
    }
    @Test fun imeSubmitRequiresFocusedEditableNodeAndAdvertisedPlatformAction() {
        platform.editable = true; platform.focused = true; platform.imeAdvertised = true
        val observed = observe()
        val action = GuiAction(observed.observationId, GuiOperation.IME_SUBMIT, 1)
        assertTrue(session.approveExactActionFromUser(action) is GuiResult.Value)
        assertEquals(GuiResult.Value("PLATFORM_ACTION_ACCEPTED_NOT_VERIFIED"), GuiRuntime.action(session.lease, action))
        assertEquals(1, platform.nodeActions)
        platform.focused = false
        val fresh = observe()
        val unfocused = GuiAction(fresh.observationId, GuiOperation.IME_SUBMIT, 1)
        assertTrue(session.approveExactActionFromUser(unfocused) is GuiResult.Value)
        rejected(GuiRuntime.action(session.lease, unfocused), "IME_SUBMIT_NOT_SUPPORTED_BY_FOCUSED_NODE_NOT_EXECUTED")
        assertEquals(1, platform.nodeActions)
    }
    @Test @Config(sdk = [26]) fun imeSubmitOnOlderAndroidIsExplicitlyNotExecuted() {
        platform.editable = true; platform.focused = true
        val observed = observe()
        val action = GuiAction(observed.observationId, GuiOperation.IME_SUBMIT, 1)
        assertTrue(session.approveExactActionFromUser(action) is GuiResult.Value)
        rejected(GuiRuntime.action(session.lease, action), "IME_SUBMIT_UNSUPPORTED_API_NOT_EXECUTED")
        assertEquals(0, platform.nodeActions)
    }
    @Test fun freshExpandedObservationRecoversTruncatedTreeWithoutRevivingOldIds() {
        platform.descendants = 160
        val original = observe()
        assertTrue(original.truncated)
        assertTrue(GuiRuntime.validateExactTarget(session.lease, GuiAction(original.observationId, GuiOperation.TAP, 1)) is GuiResult.Rejected)
        assertTrue(session.approveReadsFromUser(GuiReadApproval(1, 0)) is GuiResult.Value)
        val expanded = (GuiRuntime.observe(session.lease, 512) as GuiResult.Value).value
        assertFalse(expanded.truncated); assertEquals(162, expanded.nodes.size)
        assertNotEquals(original.observationId, expanded.observationId)
        rejected(GuiRuntime.validateExactTarget(session.lease, GuiAction(original.observationId, GuiOperation.TAP, 1)), "OBSERVATION_ID_MISMATCH")
        val action = GuiAction(expanded.observationId, GuiOperation.TAP, 161)
        assertTrue(session.approveExactActionFromUser(action) is GuiResult.Value)
        assertEquals(GuiResult.Value("PLATFORM_ACTION_ACCEPTED_NOT_VERIFIED"), GuiRuntime.action(session.lease, action))
        assertEquals(1, platform.nodeActions)
    }

    private fun observe(): GuiObservation {
        val result = session.previewFromUser()
        assertTrue(result.toString(), result is GuiResult.Value)
        return (result as GuiResult.Value).value
    }
    private fun approve(observation: GuiObservation): GuiAction =
        GuiAction(observation.observationId, GuiOperation.TAP, 1).also {
            assertTrue(session.approveExactActionFromUser(it) is GuiResult.Value)
        }
    private fun screenshot(observation: GuiObservation) = GuiRuntime.screenshot(session.lease, observation.observationId)
    private fun <T> java.util.concurrent.CompletableFuture<T>.completed(): T {
        assertTrue("Expected completion; callbacks=${platform.callbacks.size}, encoderTasks=${worker.tasks.size}", isDone)
        return get(1, java.util.concurrent.TimeUnit.SECONDS)
    }
    private fun rejected(result: GuiResult<*>, code: String) { assertEquals(GuiResult.Rejected(code), result) }
    private fun assertDisposed(frame: Frame) { assertEquals(listOf("software", "hardware", "buffer"), frame.disposed) }

    private fun openTarget(target: String?, token: TaskSurfaceSelfTarget? = null, surfaceOwner: Any? = null) {
        session.close()
        session = (GuiRuntime.openForUser(GuiOwner("target-session", "target-run"), target, token, surfaceOwner) as GuiResult.Value).value
        assertTrue(session.approveReadsFromUser(GuiReadApproval(3, 1)) is GuiResult.Value)
    }
    private fun openSelf(owner: Any = Any(), current: () -> Boolean = { true }): TaskSurfaceSelfTarget {
        val token = TaskSurfaceSelfTarget.foreground(service.packageName, owner, current)
        platform.pkg = service.packageName
        platform.stableNodes = true
        openTarget(service.packageName, token, owner)
        return token
    }
    private fun assertNoActions() {
        assertEquals(0, platform.nodeActions)
        assertTrue(shadowOf(service).globalActionsPerformed.isEmpty())
    }

    private fun defaultDelivery(current: () -> Boolean = { true }): GuiConfirmedDelivery {
        platform.rootClickable = false
        // No user read/action approval. The trusted product entry must supply its own exact grant.
        GuiRuntime.approveReads(session.lease, GuiReadApproval(0, 0))
        var delivered: GuiConfirmedDelivery? = null
        val result = session.prepareExactActionWithProductDefaults(GuiOperation.TAP,
            ownerCurrent = current, onConfirmed = { delivered = it; true })
        assertTrue(result.toString(), result is GuiResult.Value)
        return checkNotNull(delivered)
    }

    private fun listedWindow(pkg: String = "external.app", id: Int = 7, active: Boolean = true,
        focused: Boolean = false, layer: Int = 0, type: Int = AccessibilityWindowInfo.TYPE_APPLICATION): AccessibilityWindowInfo {
        platform.pkg = pkg; platform.windowId = id; platform.active = active; platform.windowType = type
        val root = checkNotNull(platform.root(service))
        return checkNotNull(root.window).also {
            shadowOf(it).setRoot(root); shadowOf(it).setFocused(focused); shadowOf(it).setLayer(layer)
        }
    }

    private fun visualWindow() = GuiVisualWindow("wm-token", "external.app", 10222,
        GuiBounds(0, 0, 1, 1), GuiBounds(0, 0, 1, 1), 0, emptyList())
    private fun visualImage(): GuiScreenshot {
        platform.missing = true; platform.visual = visualWindow()
        val pending = GuiRuntime.visualScreenshot(session.lease) { false }
        platform.success(); worker.runAll()
        return (pending.completed() as GuiResult.Value).value
    }
    private fun spatialFrame(change: Pair<Int, Int>? = null, blank: Boolean = false,
        width: Int = 512, height: Int = 768, targetX: Int = 400, targetY: Int = 650,
        changes: List<Pair<Int, Int>> = emptyList()): Frame {
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(0xffeeeeee.toInt())
            if (!blank) for (x in (targetX - 8)..(targetX + 8)) for (y in (targetY - 8)..(targetY + 8))
                bitmap.setPixel(x, y, if ((x + y) % 2 == 0) 0xff000000.toInt() else 0xffffffff.toInt())
            change?.let { bitmap.setPixel(it.first, it.second, 0xff123456.toInt()) }
            changes.forEach { bitmap.setPixel(it.first, it.second, 0xff123456.toInt()) }
            val png = java.io.ByteArrayOutputStream().also {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }.toByteArray()
            return Frame().apply { image = GuiScreenshot("fixture", width, height, png, visualPixels = GuiVisualPixels.from(bitmap)) }
        } finally { bitmap.recycle() }
    }
    private fun spatialImage(frame: Frame = spatialFrame()): GuiScreenshot {
        session.approveReadsFromUser(GuiReadApproval(0, 1))
        platform.missing = true
        platform.visual = visualWindow().copy(bounds = GuiBounds(0, 0, 512, 768), displayBounds = GuiBounds(0, 0, 512, 768))
        val pending = GuiRuntime.visualScreenshot(session.lease) { false }
        platform.success(frame); worker.runAll()
        return (pending.completed() as GuiResult.Value).value
    }
    private fun spatialAction(image: GuiScreenshot, operation: GuiOperation = GuiOperation.COORDINATE_TAP) =
        GuiAction(image.observationId, operation, visual = true,
            gesture = if (operation == GuiOperation.BACK) null else GuiGesture(400, 650, 400, 650, 50))

    @Test fun visualTapAllowsDistantAnimationWithExactTargetPixelsAndNeverReplays() {
        val original = spatialImage(); val action = spatialAction(original)
        val changed = spatialFrame(10 to 10)
        assertFalse(original.png.contentEquals(changed.image!!.png))
        contentEvent("external.app")
        val pending = GuiRuntime.visualAction(session.lease, action) { false }
        platform.success(changed); worker.runAll()
        try {
            assertEquals(1, platform.gestures); assertEquals(action.gesture, platform.submittedGesture)
            assertFalse(pending.isDone)
            rejected(GuiRuntime.visualAction(session.lease, action) { false }.completed(), "GESTURE_IN_PROGRESS_NOT_EXECUTED")
        } finally { platform.gestureCompleted?.invoke(true); platform.gestureCompleted = null }
        assertTrue(pending.completed() is GuiResult.Value)
        rejected(GuiRuntime.visualAction(session.lease, action) { false }.completed(), "CAPTURE_ID_MISMATCH_NOT_EXECUTED")
        assertEquals(1, platform.gestures); assertDisposed(changed)
    }
    @Test fun visualTapAdmitsChangedPixelsButStillRequiresPixelEvidenceAndSingleUse() {
        for (mode in 0..3) {
            val baseline = platform.gestures
            val original = spatialImage(); val action = spatialAction(original)
            val changed = when (mode) {
                0 -> spatialFrame(400 to 650) // hotspot changed
                1 -> spatialFrame(470 to 700) // nearby tile changed
                2 -> spatialFrame(10 to 10) // distant animation
                else -> spatialFrame(400 to 650).apply { image = image!!.copy(visualPixels = null) }
            }
            val pending = GuiRuntime.visualAction(session.lease, action) { false }
            platform.success(changed); worker.runAll()
            if (mode < 3) {
                assertEquals(baseline + 1, platform.gestures)
                platform.gestureCompleted?.invoke(true); platform.gestureCompleted = null
                assertTrue(pending.completed() is GuiResult.Value)
            } else {
                rejected(pending.completed(), "CAPTURE_CONTENT_CHANGED_NOT_EXECUTED")
                assertEquals(baseline, platform.gestures)
            }
            assertDisposed(changed)
            rejected(GuiRuntime.visualAction(session.lease, action) { false }.completed(), "CAPTURE_ID_MISMATCH_NOT_EXECUTED")
            assertEquals(baseline + if (mode < 3) 1 else 0, platform.gestures)
        }
    }
    @Test fun rejectedVisualTapRecoversOnlyThroughAnExplicitFreshCaptureAndNewDecision() {
        val original = spatialImage(); val oldAction = spatialAction(original)
        val rejectedAction = GuiRuntime.visualAction(session.lease, oldAction) { false }
        val changed = spatialFrame(400 to 650).apply { image = image!!.copy(visualPixels = null) }
        platform.success(changed); worker.runAll()
        rejected(rejectedAction.completed(), "CAPTURE_CONTENT_CHANGED_NOT_EXECUTED")
        assertEquals(0, platform.gestures); assertTrue(platform.callbacks.isEmpty()); assertDisposed(changed)
        rejected(GuiRuntime.visualAction(session.lease, oldAction) { false }.completed(), "CAPTURE_ID_MISMATCH_NOT_EXECUTED")

        val fresh = spatialImage(spatialFrame(400 to 650))
        assertNotEquals(original.observationId, fresh.observationId)
        rejected(GuiRuntime.visualAction(session.lease, oldAction) { false }.completed(), "CAPTURE_ID_MISMATCH_NOT_EXECUTED")
        val newAction = spatialAction(fresh)
        val pending = GuiRuntime.visualAction(session.lease, newAction) { false }
        val current = spatialFrame(400 to 650)
        platform.success(current); worker.runAll()
        try {
            assertEquals(1, platform.gestures)
            assertEquals(newAction.gesture, platform.submittedGesture)
            assertFalse(pending.isDone)
        } finally { platform.gestureCompleted?.invoke(true); platform.gestureCompleted = null }
        assertTrue(pending.completed() is GuiResult.Value); assertDisposed(current)
        rejected(GuiRuntime.visualAction(session.lease, newAction) { false }.completed(), "CAPTURE_ID_MISMATCH_NOT_EXECUTED")
        assertEquals(1, platform.gestures)
    }

    @Test fun visualTapValidationSkipsSecondPngEncoding() {
        val original = spatialImage()
        val action = spatialAction(original)
        val validation = spatialFrame(400 to 650)
        val pending = GuiRuntime.visualAction(session.lease, action) { false }
        platform.success(validation); worker.runAll()
        assertEquals(1, validation.validationOnly)
        assertEquals(0, validation.encoded)
        assertEquals(1, platform.gestures)
        platform.gestureCompleted?.invoke(true); platform.gestureCompleted = null
        assertTrue(pending.completed() is GuiResult.Value)
        assertDisposed(validation)
    }

    @Test fun freshCapturesAdmitRepeatedPixelChangesOnlyWithNewSingleUseDecisions() {
        repeat(2) { index ->
            val original = spatialImage(spatialFrame(if (index == 0) null else 400 to 650))
            val oldAction = spatialAction(original)
            val pending = GuiRuntime.visualAction(session.lease, oldAction) { false }
            val verification = spatialFrame(if (index == 0) 400 to 650 else 470 to 700)
            platform.success(verification); worker.runAll()
            assertEquals(index + 1, platform.gestures)
            rejected(GuiRuntime.visualAction(session.lease, oldAction) { false }.completed(), "GESTURE_IN_PROGRESS_NOT_EXECUTED")
            platform.gestureCompleted?.invoke(true); platform.gestureCompleted = null
            assertTrue(pending.completed() is GuiResult.Value)
            rejected(GuiRuntime.visualAction(session.lease, oldAction) { false }.completed(), "CAPTURE_ID_MISMATCH_NOT_EXECUTED")
            assertDisposed(verification)
        }
        assertEquals(2, platform.gestures)
    }
    @Test fun incidentGeometryKeepsAll49TilesAndAdmitsSixNearbyChangesWithDiagnosticBounds() {
        fun frame(changes: List<Pair<Int, Int>> = emptyList()) = spatialFrame(width = 1216, height = 2640,
            targetX = 1093, targetY = 2541, changes = changes)
        val before = frame().image!!.visualPixels!!
        val points = listOf(1000 to 2440, 1032 to 2440, 1064 to 2472, 1096 to 2504, 1128 to 2536, 1200 to 2639)
        val changed = frame(points).image!!.visualPixels!!
        val gesture = GuiGesture(1093, 2541, 1093, 2541, 50)
        val window = visualWindow().copy(bounds = GuiBounds(0, 0, 1216, 2640), displayBounds = GuiBounds(0, 0, 1216, 2640))
        val check = before.compareTap(changed, gesture, window)
        assertEquals(49, check.comparedTiles); assertEquals(6, check.changedTiles)
        assertEquals(GuiBounds(992, 2432, 1216, 2640), check.region)
        assertEquals(points.map { (x, y) -> GuiBounds(x / 32 * 32, y / 32 * 32,
            minOf(1216, x / 32 * 32 + 32), minOf(2640, y / 32 * 32 + 32)) }, check.changedTileBounds)
        assertFalse(check.targetTileChanged == true)
        assertTrue(check.accepted)
        val equal = changed.compareTap(changed, gesture, window)
        assertTrue(equal.accepted); assertEquals(0, equal.changedTiles); assertTrue(equal.changedTileBounds.isEmpty())
    }
    @Test fun localTapEvidenceNeverBypassesWindowIdentityGeometryOrForegroundChanges() {
        for (mode in 0..4) {
            val original = spatialImage(); val action = spatialAction(original)
            val window = platform.visual!!
            platform.visual = when (mode) {
                0 -> window.copy(identity = "replacement")
                1 -> window.copy(packageName = "other.app")
                2 -> window.copy(uid = 10223)
                3 -> window.copy(rotation = 90)
                else -> window.copy(displayBounds = GuiBounds(0, 0, 513, 768))
            }
            val pending = GuiRuntime.visualAction(session.lease, action) { false }
            platform.success(spatialFrame(10 to 10)); worker.runAll()
            assertTrue(pending.completed() is GuiResult.Rejected); assertEquals(0, platform.gestures)
            platform.visual = window
            rejected(GuiRuntime.visualAction(session.lease, action) { false }.completed(), "CAPTURE_ID_MISMATCH_NOT_EXECUTED")
            assertEquals(0, platform.gestures); assertTrue(platform.callbacks.isEmpty())
        }
    }
    @Test fun nonTapActionsStillRequireStableFramesButTapPixelChangesAreDiagnosticOnly() {
        for (operation in listOf(GuiOperation.SWIPE, GuiOperation.BACK)) {
            val original = spatialImage(); val pending = GuiRuntime.visualAction(session.lease, spatialAction(original, operation)) { false }
            platform.success(spatialFrame(10 to 10)); worker.runAll()
            rejected(pending.completed(), "CAPTURE_CONTENT_CHANGED_NOT_EXECUTED"); assertEquals(0, platform.gestures)
        }
        val blank = spatialImage(spatialFrame(blank = true))
        val pending = GuiRuntime.visualAction(session.lease, spatialAction(blank)) { false }
        val verification = spatialFrame(10 to 10, blank = true)
        platform.success(verification); worker.runAll()
        assertEquals(1, platform.gestures)
        platform.gestureCompleted?.invoke(true); platform.gestureCompleted = null
        assertTrue(pending.completed() is GuiResult.Value)
        assertDisposed(verification)
    }
    @Test fun exactPixelDiagnosticReasonsAndChangedTileCountsDoNotAlterAdmission() {
        val before = spatialFrame().image!!.visualPixels!!
        val gesture = GuiGesture(400, 650, 400, 650, 50)
        val window = visualWindow().copy(bounds = GuiBounds(0, 0, 512, 768), displayBounds = GuiBounds(0, 0, 512, 768))
        val missing = before.compareTap(null, gesture, window)
        assertEquals(app.daiyu.android.diagnostics.RuntimeDiagnostics.Code.PIXELS_MISSING, missing.reason)
        assertNull(missing.comparedTiles)
        val changed = before.compareTap(spatialFrame(400 to 650).image!!.visualPixels, gesture, window)
        assertEquals(app.daiyu.android.diagnostics.RuntimeDiagnostics.Code.LOCAL_PIXELS_CHANGED, changed.reason)
        assertEquals(1, changed.changedTiles)
        assertEquals(25, changed.comparedTiles)
        assertEquals(true, changed.targetTileChanged)
        assertTrue(changed.accepted)
        val distant = before.compareTap(spatialFrame(10 to 10).image!!.visualPixels, gesture, window)
        assertTrue(distant.accepted)
        assertEquals(false, distant.targetTileChanged)
        assertEquals(0, distant.changedTiles)
        val blank = spatialFrame(blank = true).image!!.visualPixels!!
        val blankCheck = blank.compareTap(spatialFrame(10 to 10, blank = true).image!!.visualPixels, gesture, window)
        assertEquals(app.daiyu.android.diagnostics.RuntimeDiagnostics.Code.BLANK_TARGET, blankCheck.reason)
        assertTrue(blankCheck.accepted)
    }

    @Test fun localPixelsAtBottomNavigationKeepTheWholeNeighborhoodAndOnlyAdmitTheTap() {
        fun pixels(change: Pair<Int, Int>? = null) = spatialFrame(change, width = 1216, height = 2640,
            targetX = 1092, targetY = 2549).image!!.visualPixels!!
        val original = pixels(); val distant = pixels(10 to 10)
        val gesture = GuiGesture(1092, 2549, 1092, 2549, 50)
        val window = visualWindow().copy(bounds = GuiBounds(0, 0, 1216, 2640), displayBounds = GuiBounds(0, 0, 1216, 2640))
        val cropped = window.copy(bounds = GuiBounds(0, 0, 1216, 2560))
        val navigation = window.copy(above = listOf(GuiVisualOccluder("navigation", GuiBounds(0, 2560, 1216, 2640))))
        assertEquals(56, original.compareTap(distant, gesture, cropped).comparedTiles)
        assertTrue(original.matchesTap(distant, gesture, cropped))
        assertTrue(original.matchesTap(distant, gesture, navigation))
        assertTrue(original.matchesTap(pixels(1092 to 2549), gesture, navigation))
        assertTrue(original.matchesTap(pixels(1190 to 2600), gesture, navigation))
        assertFalse(original.matchesTap(distant, gesture, window.copy(bounds = GuiBounds(0, 0, 1216, 2549))))
        assertFalse(original.matchesTap(distant, gesture, window.copy(
            above = listOf(GuiVisualOccluder("overlay", GuiBounds(1080, 2540, 1110, 2560))))))
    }

    @Test fun rootlessVisualCapturePublishesNoNodesAndUsesThePlatformCaptureOnlyOnce() {
        val image = visualImage()
        assertFalse(image.observationBound); assertEquals("external.app", image.visualPackage)
        assertNull(session.lease.observation); assertEquals(0, platform.rootReads)
        assertEquals(2, platform.visualReads); assertEquals(0, platform.nodeActions)
        val json = org.json.JSONObject(GuiScreenToolFactory.screenshotJson(image))
        assertEquals(image.observationId, json.getString("capture_id")); assertFalse(json.has("observation_id"))
        rejected(GuiRuntime.validateExactTarget(session.lease,
            GuiAction(image.observationId, GuiOperation.TAP, 0)), "OBSERVATION_REQUIRED")
    }
    @Test fun visualGestureRequiresTheSamePixelsAndConsumesCaptureBeforeTerminalReceipt() {
        val image = visualImage()
        val action = GuiAction(image.observationId, GuiOperation.COORDINATE_TAP,
            gesture = GuiGesture(0, 0, 0, 0, 50), visual = true)
        val pending = GuiRuntime.visualAction(session.lease, action) { false }
        platform.success(); worker.runAll()
        assertEquals(1, platform.gestures); assertFalse(pending.isDone)
        platform.gestureCompleted!!(true)
        assertTrue(pending.completed() is GuiResult.Value)
        rejected(GuiRuntime.visualAction(session.lease, action) { false }.completed(), "CAPTURE_ID_MISMATCH_NOT_EXECUTED")
        assertEquals(1, platform.gestures)
    }
    @Test fun changedPixelsWithoutAccessibilityEventsNeverDispatchVisualCoordinates() {
        val image = visualImage()
        val action = GuiAction(image.observationId, GuiOperation.SWIPE,
            gesture = GuiGesture(0, 0, 0, 0, 500), visual = true)
        val pending = GuiRuntime.visualAction(session.lease, action) { false }
        platform.success(Frame().apply { pixels = byteArrayOf(2) }); worker.runAll()
        rejected(pending.completed(), "CAPTURE_CONTENT_CHANGED_NOT_EXECUTED"); assertNoActions()
        rejected(GuiRuntime.visualAction(session.lease, action) { false }.completed(), "CAPTURE_ID_MISMATCH_NOT_EXECUTED")
        assertTrue(platform.callbacks.isEmpty())
    }
    @Test fun visualActionExpiresWhileItsVerificationFrameIsBeingEncoded() {
        val image = visualImage()
        val action = GuiAction(image.observationId, GuiOperation.COORDINATE_TAP,
            gesture = GuiGesture(0, 0, 0, 0, 50), visual = true)
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(GUI_OBSERVATION_TTL_MILLIS - 1))
        val pending = GuiRuntime.visualAction(session.lease, action) { false }
        platform.success()
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(2))
        worker.runAll()
        rejected(pending.completed(), "CAPTURE_STALE_NOT_EXECUTED"); assertNoActions()
        rejected(GuiRuntime.visualAction(session.lease, action) { false }.completed(), "CAPTURE_ID_MISMATCH_NOT_EXECUTED")
    }
    @Test fun windowChangeFailurePermanentlyRetiresTheVisualCapture() {
        val image = visualImage()
        val action = GuiAction(image.observationId, GuiOperation.COORDINATE_TAP,
            gesture = GuiGesture(0, 0, 0, 0, 50), visual = true)
        val pending = GuiRuntime.visualAction(session.lease, action) { false }
        platform.success(); platform.visual = visualWindow().copy(identity = "other-window"); worker.runAll()
        rejected(pending.completed(), "CAPTURE_WINDOW_CHANGED_NOT_EXECUTED")
        platform.visual = visualWindow()
        rejected(GuiRuntime.visualAction(session.lease, action) { false }.completed(), "CAPTURE_ID_MISMATCH_NOT_EXECUTED")
        assertTrue(platform.callbacks.isEmpty()); assertNoActions()
    }
    @Test fun abandonedVisualCaptureDisposesLateFrameWithoutPublishingAToken() {
        platform.visual = visualWindow(); var abandoned = false
        val pending = GuiRuntime.visualScreenshot(session.lease) { abandoned }
        abandoned = true
        val frame = platform.success(); worker.runAll()
        rejected(pending.completed(), "CANCELLED_NOT_EXECUTED")
        assertEquals(listOf("software", "hardware", "buffer"), frame.disposed)
        rejected(GuiRuntime.visualAction(session.lease, GuiAction("late", GuiOperation.BACK, visual = true)) { false }.completed(),
            "CAPTURE_ID_MISMATCH_NOT_EXECUTED")
        assertNoActions()
    }
    @Test fun visualCaptureRechecksWindowAfterEncodingAndDisposesOnTargetLoss() {
        platform.visual = visualWindow()
        val pending = GuiRuntime.visualScreenshot(session.lease) { false }
        val frame = platform.success()
        platform.visual = visualWindow().copy(identity = "changed")
        worker.runAll()
        rejected(pending.completed(), "CAPTURE_WINDOW_CHANGED_NOT_EXECUTED")
        assertEquals(listOf("software", "hardware", "buffer"), frame.disposed)
        assertNull(session.lease.observation); assertNoActions()
    }
    @Test fun missingWindowWitnessOrCancellationNeverStartsTheCapture() {
        rejected(GuiRuntime.visualScreenshot(session.lease) { false }.completed(), "VISUAL_WINDOW_UNVERIFIABLE_NOT_EXECUTED")
        session.approveReadsFromUser(GuiReadApproval(0, 1)); platform.visual = visualWindow()
        rejected(GuiRuntime.visualScreenshot(session.lease) { true }.completed(), "CANCELLED_NOT_EXECUTED")
        assertTrue(platform.callbacks.isEmpty()); assertNoActions()
    }
    @Test fun visualPlatformSecureFailureNeverFallsBackToRootPixels() {
        platform.visual = visualWindow()
        val pending = GuiRuntime.visualScreenshot(session.lease) { false }
        platform.callbacks.removeAt(0).second(6)
        rejected(pending.completed(), "SCREENSHOT_PLATFORM_ERROR_6")
        assertEquals(1, platform.visualReads); assertEquals(0, platform.rootReads); assertNoActions()
    }
    @Test fun cancelledVisualActionCannotDispatchAfterItsVerificationFrameArrives() {
        val image = visualImage(); var cancelled = false
        val pending = GuiRuntime.visualAction(session.lease, GuiAction(image.observationId, GuiOperation.COORDINATE_TAP,
            gesture = GuiGesture(0, 0, 0, 0, 50), visual = true)) { cancelled }
        cancelled = true
        val frame = platform.success(); worker.runAll()
        rejected(pending.completed(), "CANCELLED_NOT_EXECUTED")
        assertEquals(listOf("software", "hardware", "buffer"), frame.disposed); assertNoActions()
    }

    private fun contentEvent(pkg: String? = service.packageName, type: Int = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
        val event = contentEvent(type, pkg, 42)
        try { service.onAccessibilityEvent(event) } finally { event.recycle() }
    }

    @Test fun rootlessCaptureSurvivesOwnContentAtEveryAsyncStage() {
        platform.visual = visualWindow(); platform.deferVisual = true
        val pending = GuiRuntime.visualScreenshot(session.lease) { false }
        contentEvent(); contentEvent(type = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)
        platform.completeVisual()
        assertEquals(1, platform.callbacks.size)
        contentEvent()
        val frame = platform.success()
        contentEvent()
        worker.runAll()
        contentEvent()
        assertEquals(1, platform.visualCallbacks.size)
        platform.completeVisual()
        assertTrue(pending.completed() is GuiResult.Value)
        assertEquals(listOf("software", "hardware", "buffer"), frame.disposed)
        assertNull(session.lease.observation); assertNoActions()
    }

    @Test fun ownReplyContentKeepsPublishedVisualTokenAndItsPixelVerifiedAction() {
        val image = visualImage()
        for (type in listOf(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.TYPE_VIEW_SCROLLED,
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED)) contentEvent(type = type)
        val pending = GuiRuntime.visualAction(session.lease, GuiAction(image.observationId, GuiOperation.COORDINATE_TAP,
            gesture = GuiGesture(0, 0, 0, 0, 50), visual = true)) { false }
        contentEvent()
        platform.success(); worker.runAll()
        assertEquals(1, platform.gestures)
        platform.gestureCompleted!!(true)
        assertTrue(pending.completed() is GuiResult.Value)
    }

    @Test fun contentChangesDuringVisualVerificationStillNeedExactlyTheOriginalPixels() {
        val image = visualImage()
        contentEvent()
        val pending = GuiRuntime.visualAction(session.lease, GuiAction(image.observationId, GuiOperation.COORDINATE_TAP,
            gesture = GuiGesture(0, 0, 0, 0, 50), visual = true)) { false }
        contentEvent()
        platform.success(Frame().apply { pixels = byteArrayOf(2) }); worker.runAll()
        rejected(pending.completed(), "CAPTURE_CONTENT_CHANGED_NOT_EXECUTED"); assertNoActions()
    }

    @Test fun targetAndUnknownContentDuringCaptureKeepsFreshWindowWitnessedImages() {
        for (pkg in listOf("external.app", null, "", "unrelated.app")) {
            session.approveReadsFromUser(GuiReadApproval(0, 1)); platform.visual = visualWindow(); platform.deferVisual = true
            val pending = GuiRuntime.visualScreenshot(session.lease) { false }
            contentEvent(pkg); platform.completeVisual()
            contentEvent(pkg, AccessibilityEvent.TYPE_VIEW_SCROLLED)
            val frame = platform.success()
            contentEvent(pkg, AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED); worker.runAll()
            contentEvent(pkg, AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED); platform.completeVisual()
            assertTrue(pending.completed() is GuiResult.Value)
            assertDisposed(frame); assertNull(session.lease.observation); assertNoActions()
        }
    }

    @Test fun targetAndUnknownContentAfterPublicationStillRequiresPixelVerifiedSingleUseAction() {
        for (pkg in listOf("external.app", null, "", "unrelated.app")) {
            session.approveReadsFromUser(GuiReadApproval(0, 1))
            val image = visualImage()
            contentEvent(pkg)
            val action = GuiAction(image.observationId, GuiOperation.COORDINATE_TAP,
                gesture = GuiGesture(0, 0, 0, 0, 50), visual = true)
            val pending = GuiRuntime.visualAction(session.lease, action) { false }
            contentEvent(pkg, AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)
            val frame = platform.success(); worker.runAll()
            platform.gestureCompleted!!(true)
            assertTrue(pending.completed() is GuiResult.Value); assertDisposed(frame)
            rejected(GuiRuntime.visualAction(session.lease, action) { false }.completed(), "CAPTURE_ID_MISMATCH_NOT_EXECUTED")
        }
        assertEquals(4, platform.gestures); assertEquals(0, platform.nodeActions)
    }

    @Test fun targetContentWithDifferentPixelsRetiresTokenWithoutDispatching() {
        val image = visualImage(); contentEvent("external.app")
        val action = GuiAction(image.observationId, GuiOperation.COORDINATE_TAP,
            gesture = GuiGesture(0, 0, 0, 0, 50), visual = true)
        val pending = GuiRuntime.visualAction(session.lease, action) { false }
        contentEvent(null)
        val frame = platform.success(Frame().apply { pixels = byteArrayOf(2) }); worker.runAll()
        rejected(pending.completed(), "CAPTURE_CONTENT_CHANGED_NOT_EXECUTED"); assertDisposed(frame); assertNoActions()
        assertEquals(0, platform.gestures)
        rejected(GuiRuntime.visualAction(session.lease, action) { false }.completed(), "CAPTURE_ID_MISMATCH_NOT_EXECUTED")
    }

    @Test fun ordinaryObserveAndScreenshotRecoverRootlessReadsDespiteTargetContentAtEveryStage() {
        for (screenshot in listOf(false, true)) for (pkg in listOf("external.app", null)) {
            session.close()
            session = (GuiRuntime.openForUser(GuiOwner("session", "run")) as GuiResult.Value).value
            val owner = app.daiyu.android.integration.tools.runnerOwner(app.daiyu.android.domain.Message(
                app.daiyu.android.domain.MessageId("m"), app.daiyu.android.domain.ConversationId("c"),
                app.daiyu.android.domain.RunId("run"), app.daiyu.android.domain.Message.ASSISTANT_ROLE,
                emptyList(), app.daiyu.android.domain.RunStatus.RUNNING))
            session.bindDynamic(owner, app.daiyu.android.domain.tools.TaskAuthorization("session", "run"))
            platform.missing = true; platform.visual = visualWindow(); platform.deferVisual = true
            val future = java.util.concurrent.FutureTask<GuiResult<*>> {
                if (screenshot) session.dynamicScreenshot(null) { false } else session.dynamicScreen({ false }, 512)
            }
            Thread(future).start()
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
            while (platform.visualCallbacks.isEmpty() && !future.isDone && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5)
            }
            assertEquals(1, platform.visualCallbacks.size)
            contentEvent(pkg); platform.completeVisual()
            assertEquals(1, platform.callbacks.size)
            contentEvent(pkg, AccessibilityEvent.TYPE_VIEW_SCROLLED)
            val frame = platform.success()
            contentEvent(pkg, AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED); worker.runAll()
            contentEvent(pkg, AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED); platform.completeVisual()
            val value = future.get(3, java.util.concurrent.TimeUnit.SECONDS) as GuiResult.Value
            val image = if (screenshot) value.value as GuiScreenshot else (value.value as GuiScreenRead.Visual).image
            assertFalse(image.observationBound); assertTrue(image.observationId.isNotBlank())
            assertNull(session.lease.observation); assertDisposed(frame); assertNoActions()
            assertTrue(platform.callbacks.isEmpty()); assertTrue(platform.visualCallbacks.isEmpty())
        }
    }

    @Test fun newerNodeObservationStillFencesAnOlderRootlessCapture() {
        platform.visual = visualWindow()
        val pending = GuiRuntime.visualScreenshot(session.lease) { false }
        val frame = platform.success()
        val newer = observe()
        worker.runAll()
        rejected(pending.completed(), "CAPTURE_STALE_NOT_EXECUTED")
        assertEquals(newer, session.lease.observation); assertDisposed(frame); assertNoActions()
    }

    @Test fun rootlessCaptureAgeStillExpiresAndLogsOnlyBoundedMetadata() {
        platform.visual = visualWindow(); platform.deferVisual = true
        org.robolectric.shadows.ShadowLog.clear()
        val pending = GuiRuntime.visualScreenshot(session.lease) { false }
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(GUI_OBSERVATION_TTL_MILLIS + 1))
        platform.completeVisual()
        rejected(pending.completed(), "CAPTURE_STALE_NOT_EXECUTED")
        val line = org.robolectric.shadows.ShadowLog.getLogsForTag("DaiyuGui").map { it.msg }
            .single { it.startsWith("visual_capture_stale ") }
        assertTrue(line, line.contains("stage=initial_witness reason=age"))
        assertFalse(line.contains(service.packageName)); assertFalse(line.contains("external.app")); assertFalse(line.contains("wm-token"))
        assertTrue(line.length < 256); assertTrue(platform.callbacks.isEmpty()); assertNoActions()
    }

    @Test fun hardWindowEventsStillRejectEveryAsyncCaptureStageWithoutPublishing() {
        for (stage in 0..3) {
            session.approveReadsFromUser(GuiReadApproval(0, 1)); platform.visual = visualWindow(); platform.deferVisual = true
            val pending = GuiRuntime.visualScreenshot(session.lease) { false }
            if (stage == 0) contentEvent(type = AccessibilityEvent.TYPE_WINDOWS_CHANGED)
            platform.completeVisual()
            if (stage == 0) { rejected(pending.completed(), "CAPTURE_STALE_NOT_EXECUTED"); assertTrue(platform.callbacks.isEmpty()); continue }
            if (stage == 1) contentEvent(type = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
            val frame = platform.success()
            if (stage == 2) contentEvent(type = AccessibilityEvent.TYPE_WINDOWS_CHANGED)
            worker.runAll()
            if (platform.visualCallbacks.isNotEmpty()) {
                if (stage == 3) contentEvent(type = AccessibilityEvent.TYPE_WINDOWS_CHANGED)
                platform.completeVisual()
            }
            rejected(pending.completed(), "CAPTURE_STALE_NOT_EXECUTED")
            assertEquals(listOf("software", "hardware", "buffer"), frame.disposed)
            assertNull(session.lease.observation); assertNoActions()
        }
    }

    @Test fun ordinaryScreenshotWithoutIdNeverTraversesNodesAndExplicitStaleIdNeverReobserves() {
        val owner = app.daiyu.android.integration.tools.runnerOwner(app.daiyu.android.domain.Message(
            app.daiyu.android.domain.MessageId("m"), app.daiyu.android.domain.ConversationId("c"),
            app.daiyu.android.domain.RunId("run"), app.daiyu.android.domain.Message.ASSISTANT_ROLE,
            emptyList(), app.daiyu.android.domain.RunStatus.RUNNING))
        session.bindDynamic(owner, app.daiyu.android.domain.tools.TaskAuthorization("session", "run"))
        platform.descendants = 140
        val future = java.util.concurrent.FutureTask { session.dynamicScreenshot(null) { false } }
        val thread = Thread(future); thread.start()
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
        while (platform.callbacks.isEmpty() && !future.isDone && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5)
        }
        assertEquals(0, CopyingWindowNodeShadow.childReads)
        assertNull(session.lease.observation)
        assertEquals(1, platform.callbacks.size)
        platform.success(); worker.runAll()
        val image = future.get(3, java.util.concurrent.TimeUnit.SECONDS) as GuiResult.Value
        assertFalse(image.value.observationBound)
        assertTrue(image.value.observationId.isNotBlank())
        assertNull(session.lease.observation)
        val json = org.json.JSONObject(GuiScreenToolFactory.screenshotJson(image.value))
        assertEquals(image.value.observationId, json.getString("capture_id"))
        assertFalse(json.has("observation_id"))
        assertEquals(0, session.lease.reads); assertEquals(0, session.lease.screenshots)
        assertEquals(0, CopyingWindowNodeShadow.childReads)
        rejected(GuiRuntime.visualAction(session.lease, spatialAction(image.value)) { false }.completed(),
            "CAPTURE_ID_MISMATCH_NOT_EXECUTED")
        val reads = platform.rootReads
        val stale = java.util.concurrent.FutureTask { session.dynamicScreenshot("stale") { false } }
        Thread(stale).start()
        while (!stale.isDone && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5)
        }
        rejected(stale.get(3, java.util.concurrent.TimeUnit.SECONDS), "OBSERVATION_REQUIRED")
        assertEquals(reads, platform.rootReads); assertTrue(platform.callbacks.isEmpty()); assertNoActions()
    }

    @Test fun currentWindowScreenshotKeepsContentIndependentButFencesANewerReadAndWindowChanges() {
        for (mode in 0..5) {
            CopyingWindowNodeShadow.childReads = 0
            session.approveReadsFromUser(GuiReadApproval(3, 1))
            val pending = GuiRuntime.currentWindowScreenshot(session.lease) { false }
            assertEquals(0, CopyingWindowNodeShadow.childReads)
            assertEquals(3, session.lease.reads); assertEquals(0, session.lease.screenshots)
            when (mode) {
                0 -> contentEvent("external.app")
                1 -> contentEvent(type = AccessibilityEvent.TYPE_WINDOWS_CHANGED)
                2 -> observe()
                3 -> platform.windowId++
                4 -> { platform.rootBounds = Rect(0, 0, 100, 80); contentEvent("external.app") }
                5 -> platform.windowBounds = Rect(0, 0, 100, 90)
            }
            val frame = platform.success(); worker.runAll()
            if (mode == 0 || mode == 4) {
                val image = pending.completed() as GuiResult.Value
                assertFalse(image.value.observationBound); assertNull(image.value.visualPackage)
            } else rejected(pending.completed(), if (mode == 3 || mode == 5) "SCREENSHOT_WINDOW_CHANGED" else "CAPTURE_STALE_NOT_EXECUTED")
            if (mode == 2) assertTrue(CopyingWindowNodeShadow.childReads > 0)
            else assertEquals(0, CopyingWindowNodeShadow.childReads)
            assertDisposed(frame); assertNoActions()
        }
    }

    @Test fun currentWindowScreenshotCancellationAndSystemWindowNeverPublishANodeOrSubmitAnAction() {
        var cancelled = false
        val pending = GuiRuntime.currentWindowScreenshot(session.lease) { cancelled }
        cancelled = true
        val frame = platform.success(); worker.runAll()
        rejected(pending.completed(), "CANCELLED_NOT_EXECUTED"); assertDisposed(frame)
        assertNull(session.lease.observation); assertEquals(0, CopyingWindowNodeShadow.childReads)
        session.approveReadsFromUser(GuiReadApproval(0, 1)); platform.windowType = AccessibilityWindowInfo.TYPE_SYSTEM
        rejected(GuiRuntime.currentWindowScreenshot(session.lease) { false }.completed(), "SYSTEM_WINDOW_NODE_ACTIONS_ONLY_NOT_EXECUTED")
        assertTrue(platform.callbacks.isEmpty()); assertNoActions()
    }

    @Test fun implicitScreenshotSubmitsInTheSameMainTurnBeforeQueuedSelfContent() {
        openSelf()
        val owner = app.daiyu.android.integration.tools.runnerOwner(app.daiyu.android.domain.Message(
            app.daiyu.android.domain.MessageId("m"), app.daiyu.android.domain.ConversationId("c"),
            app.daiyu.android.domain.RunId("target-run"), app.daiyu.android.domain.Message.ASSISTANT_ROLE,
            emptyList(), app.daiyu.android.domain.RunStatus.RUNNING))
        session.bindDynamic(owner, app.daiyu.android.domain.tools.TaskAuthorization("target-session", "target-run"))
        var queuedEventRan = false
        var capturePrecededEvent = false
        platform.onRootRead = {
            platform.onRootRead = null
            GuiRuntime.main.post {
                capturePrecededEvent = platform.callbacks.size == 1
                queuedEventRan = true
                platform.rootLabel = "streamed reply"
                GuiRuntime.changed(service, contentOnly = true)
            }
        }
        val future = java.util.concurrent.FutureTask { session.dynamicScreenshot(null) { false } }
        Thread(future).start()
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
        while (!queuedEventRan && !future.isDone && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5)
        }
        assertTrue(queuedEventRan); assertTrue(capturePrecededEvent)
        assertEquals(0, CopyingWindowNodeShadow.childReads)
        assertEquals(1, platform.callbacks.size)
        platform.success(); worker.runAll()
        val image = future.get(3, java.util.concurrent.TimeUnit.SECONDS) as GuiResult.Value
        assertFalse(image.value.observationBound); assertNull(session.lease.observation)
        assertNoActions()
    }

    @Test fun visualOnlySelfCaptureSurvivesStreamingBeforeCallbackAndEncodingWithoutPublishingNodes() {
        openSelf()
        val observation = observe()
        val action = approve(observation)
        val future = GuiRuntime.screenshot(session.lease, observation.observationId, visualOnly = true)
        platform.rootLabel = "new reply"
        GuiRuntime.changed(service, contentOnly = true)
        val frame = platform.success()
        platform.rootLabel = "reply continues"
        GuiRuntime.changed(service, contentOnly = true)
        worker.runAll()
        val image = future.completed() as GuiResult.Value
        assertFalse(image.value.observationBound); assertEquals(observation.observationId, image.value.observationId)
        assertNull(session.lease.observation)
        rejected(GuiRuntime.action(session.lease, action), "OBSERVATION_REQUIRED")
        assertDisposed(frame); assertNoActions()
    }

    @Test fun visualOnlyCaptureRejectsWindowChangeAndLockAndDoesNotHidePlatformFailure() {
        openSelf(); val observation = observe()
        val future = GuiRuntime.screenshot(session.lease, observation.observationId, visualOnly = true)
        platform.windowId++
        val frame = platform.success()
        rejected(future.completed(), "SCREENSHOT_WINDOW_CHANGED"); assertDisposed(frame)
        openSelf(); val second = observe()
        val locked = GuiRuntime.screenshot(session.lease, second.observationId, visualOnly = true)
        platform.screenUnlocked = false
        val lockedFrame = platform.success()
        rejected(locked.completed(), "SCREEN_LOCKED_NOT_EXECUTED"); assertDisposed(lockedFrame)
        platform.screenUnlocked = true
        openSelf(); val third = observe()
        val failed = GuiRuntime.screenshot(session.lease, third.observationId, visualOnly = true)
        platform.callbacks.removeAt(0).second(4)
        rejected(failed.completed(), "SCREENSHOT_PLATFORM_ERROR_4")
        assertNull(session.lease.observation); assertNoActions()
    }

    @Test fun visualOnlyCaptureDoesNotRetireALaterObservationOrAcceptHardWindowEvents() {
        openSelf(); val observation = observe()
        val future = GuiRuntime.screenshot(session.lease, observation.observationId, visualOnly = true)
        val later = observe()
        val frame = platform.success()
        rejected(future.completed(), "OBSERVATION_ID_MISMATCH")
        assertEquals(later, session.lease.observation); assertDisposed(frame)
        session.approveReadsFromUser(GuiReadApproval(1, 1))
        val hard = GuiRuntime.screenshot(session.lease, later.observationId, visualOnly = true)
        GuiRuntime.changed(service)
        val hardFrame = platform.success()
        rejected(hard.completed(), "OBSERVATION_STALE"); assertDisposed(hardFrame)
        assertNoActions()
    }

    @Test fun visualOnlyEarlyTreeRejectionRetiresItsPrivateObservationButExplicitFailureDoesNot() {
        platform.label = "x".repeat(513)
        val visual = observe()
        rejected(GuiRuntime.screenshot(session.lease, visual.observationId, visualOnly = true).completed(),
            "SCREENSHOT_TREE_UNVERIFIABLE")
        assertNull(session.lease.observation); assertTrue(platform.callbacks.isEmpty())
        val explicit = observe()
        rejected(screenshot(explicit).completed(), "SCREENSHOT_TREE_UNVERIFIABLE")
        assertSame(explicit, session.lease.observation); assertNoActions()
    }

    @Test @Config(sdk = [29]) fun visualOnlyUnsupportedApiRetiresItsPrivateObservationWithoutCapture() {
        val observation = observe()
        rejected(GuiRuntime.screenshot(session.lease, observation.observationId, visualOnly = true).completed(),
            "SCREENSHOT_UNSUPPORTED_API")
        assertNull(session.lease.observation); assertTrue(platform.callbacks.isEmpty()); assertNoActions()
    }

    private fun idleListedWindow(id: Int = 7, type: Int = AccessibilityWindowInfo.TYPE_APPLICATION,
        layer: Int = 0): AccessibilityWindowInfo = AccessibilityWindowInfo.obtain().apply {
        shadowOf(this).apply {
            setId(id); setType(type); setActive(false); setFocused(false); setLayer(layer); setDisplayId(0)
            setBoundsInScreen(Rect(0, 0, 100, 100))
        }
    }

    private fun windowFailureLog(): String = org.robolectric.shadows.ShadowLog.getLogsForTag("DaiyuGui")
        .map { it.msg }.single { it.startsWith("window_admission_failed ") }

    @Test @Config(sdk = [30, 33]) fun idleWindowNullRootLogsCountsWithoutAnotherPlatformRead() {
        platform.missing = true
        platform.listedWindows = { listOf(idleListedWindow(), idleListedWindow(id = 9)) }
        org.robolectric.shadows.ShadowLog.clear()
        rejected(session.previewFromUser(), "APPLICATION_WINDOW_NOT_ACTIVE_NOT_EXECUTED")
        val line = windowFailureLog()
        val summary = "app=2/0/0,system=0/0/0,other=0,off_display=0"
        assertTrue(line.contains("reason=active_root_missing root_result=null windows=2 local=2"))
        assertTrue(line.contains("initial_windows=[$summary]")); assertTrue(line.contains("final_windows=[$summary]"))
        assertEquals(1, platform.windowReads); assertEquals(1, platform.rootReads)
        assertEquals(if (android.os.Build.VERSION.SDK_INT >= 33) 1 else 0, platform.cacheClears)
        assertNull(session.lease.observation); assertTrue(platform.callbacks.isEmpty())
        assertEquals(0, platform.gestures); assertNoActions()
    }

    @Test fun idleWindowThrowingRootLogsOnlyFixedReason() {
        platform.listedWindows = { listOf(idleListedWindow()) }
        platform.onRootRead = { error("private-root-exception-secret") }
        org.robolectric.shadows.ShadowLog.clear()
        rejected(session.previewFromUser(), "APPLICATION_WINDOW_METADATA_UNAVAILABLE_NOT_EXECUTED")
        val line = windowFailureLog()
        assertTrue(line.contains("reason=active_root_exception"))
        assertFalse(line.contains("private-root-exception-secret")); assertFalse(line.contains(platform.pkg))
        assertEquals(1, platform.windowReads); assertEquals(1, platform.rootReads)
        assertNull(session.lease.observation); assertTrue(platform.callbacks.isEmpty()); assertNoActions()
    }

    @Test fun windowFailureLogsNumericConfigurationButNotPackageFiltersOrText() {
        val info = android.accessibilityservice.AccessibilityServiceInfo().apply {
            flags = android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            eventTypes = AccessibilityEvent.TYPE_WINDOWS_CHANGED
            packageNames = arrayOf("private.filter.one", "private.filter.two")
        }
        platform.configuration = info
        platform.pkg = "private.target.app"; platform.rootLabel = "private-root-text"; platform.label = "private-child-text"
        platform.missing = true
        platform.listedWindows = { listOf(idleListedWindow()) }
        org.robolectric.shadows.ShadowLog.clear()
        rejected(session.previewFromUser(), "APPLICATION_WINDOW_NOT_ACTIVE_NOT_EXECUTED")
        val line = windowFailureLog()
        assertTrue(line, line.contains("config=[flags=${info.flags},capabilities=${info.capabilities},events=${info.eventTypes},package_filters=2]"))
        assertEquals(1, platform.configurationReads)
        listOf("private.filter.one", "private.filter.two", platform.pkg, platform.rootLabel, platform.label)
            .forEach { assertFalse(line.contains(it)) }
        assertTrue(line.length < 1024)
        assertEquals(1, platform.windowReads); assertEquals(1, platform.rootReads); assertNoActions()
    }

    @Test fun unavailableConfigurationKeepsWindowRejectionAndHidesSourceExceptions() {
        platform.missing = true
        platform.listedWindows = { listOf(idleListedWindow()) }
        for (throws in listOf(false, true)) {
            platform.configurationFailure = throws
            org.robolectric.shadows.ShadowLog.clear()
            rejected(session.previewFromUser(), "APPLICATION_WINDOW_NOT_ACTIVE_NOT_EXECUTED")
            val line = windowFailureLog()
            assertTrue(line, line.contains("config=[unavailable]"))
            assertFalse(line.contains("private-config-source-exception"))
        }
        assertEquals(2, platform.configurationReads); assertEquals(2, platform.windowReads); assertEquals(2, platform.rootReads)
        assertNull(session.lease.observation); assertTrue(platform.callbacks.isEmpty()); assertNoActions()
    }

    @Test @Config(sdk = [33]) fun refreshedWindowDiagnosticsDistinguishSystemOnlyAndOffDisplayWindows() {
        platform.missing = true; platform.cacheClearAccepted = true
        platform.listedWindows = {
            if (platform.cacheCleared) listOf(idleListedWindow(3, AccessibilityWindowInfo.TYPE_SYSTEM),
                idleListedWindow(7, AccessibilityWindowInfo.TYPE_SYSTEM),
                idleListedWindow(9).apply { shadowOf(this).setDisplayId(1) })
            else listOf(idleListedWindow(), idleListedWindow(9))
        }
        org.robolectric.shadows.ShadowLog.clear()
        rejected(session.previewFromUser(), "APPLICATION_WINDOW_NOT_ACTIVE_NOT_EXECUTED")
        val line = windowFailureLog()
        assertTrue(line.contains("cache_refresh=cleared"))
        assertTrue(line.contains("initial_windows=[app=2/0/0,system=0/0/0,other=0,off_display=0]"))
        assertTrue(line.contains("final_windows=[app=0/0/0,system=2/0/0,other=0,off_display=1]"))
        assertTrue(line.contains("root_result=null windows=3 local=2"))
        assertEquals(2, platform.windowReads); assertEquals(1, platform.rootReads); assertEquals(1, platform.cacheClears)
        assertNull(session.lease.observation); assertTrue(platform.callbacks.isEmpty()); assertNoActions()
    }

    @Test fun emptyWindowRootFailuresKeepTheirCodeAndDistinctPrivateSafeReasons() {
        platform.missing = true
        org.robolectric.shadows.ShadowLog.clear()
        rejected(session.previewFromUser(), "NO_ACTIVE_EXTERNAL_WINDOW")
        assertTrue(windowFailureLog().contains("reason=fallback_root_missing root_result=null"))
        platform.onRootRead = { error("private-fallback-secret") }
        org.robolectric.shadows.ShadowLog.clear()
        rejected(session.previewFromUser(), "NO_ACTIVE_EXTERNAL_WINDOW")
        val line = windowFailureLog()
        assertTrue(line.contains("reason=fallback_root_exception")); assertFalse(line.contains("private-fallback-secret"))
        assertEquals(2, platform.windowReads); assertEquals(2, platform.rootReads); assertEquals(0, platform.cacheClears)
        assertNull(session.lease.observation); assertNoActions()
    }

    @Test fun selectedWindowMissingRootLogsWithoutFallingBackToAnotherWindow() {
        platform.listedWindows = { listOf(idleListedWindow().apply { shadowOf(this).setActive(true) }) }
        org.robolectric.shadows.ShadowLog.clear()
        rejected(session.previewFromUser(), "APPLICATION_WINDOW_ROOT_UNAVAILABLE_NOT_EXECUTED")
        assertTrue(windowFailureLog().contains("reason=selected_root_missing"))
        assertTrue(windowFailureLog().contains("app=1/1/0"))
        assertEquals(1, platform.windowReads); assertEquals(0, platform.rootReads)
        assertNull(session.lease.observation); assertTrue(platform.callbacks.isEmpty()); assertNoActions()
    }

    @Test @Config(sdk = [33]) fun idleCachedWindowsRefreshOnceBeforeSelectingTheActiveApplication() {
        platform.cacheClearAccepted = true
        platform.listedWindows = {
            val app = if (platform.cacheCleared) listedWindow() else idleListedWindow()
            listOf(app, idleListedWindow(9, AccessibilityWindowInfo.TYPE_SYSTEM, 1).apply {
                shadowOf(this).setBoundsInScreen(Rect(0, 0, 100, 8))
            }, idleListedWindow(10, AccessibilityWindowInfo.TYPE_SYSTEM, 2).apply {
                shadowOf(this).setBoundsInScreen(Rect(0, 94, 100, 100))
            })
        }
        assertEquals("external.app", observe().packageName)
        assertEquals(1, platform.cacheClears)
        assertEquals(2, platform.windowReads)
        assertNoActions()
    }

    @Test @Config(sdk = [33]) fun idleCacheRefreshCannotSelectTheRequestedBackgroundPackage() {
        openTarget("wanted.app")
        platform.cacheClearAccepted = true
        platform.listedWindows = { if (platform.cacheCleared) listOf(listedWindow("other.app")) else listOf(idleListedWindow()) }
        rejected(session.previewFromUser(), "TARGET_PACKAGE_MISMATCH_NOT_EXECUTED")
        assertEquals(1, platform.cacheClears); assertEquals(2, platform.windowReads)
        assertNull(session.lease.observation); assertNoActions()
    }

    @Test @Config(sdk = [33]) fun cacheRefreshKeepsSystemDialogsExactNodeOnly() {
        platform.cacheClearAccepted = true
        platform.listedWindows = { if (platform.cacheCleared)
            listOf(listedWindow("android", 9, active = true, focused = true, layer = 2, type = AccessibilityWindowInfo.TYPE_SYSTEM))
            else listOf(idleListedWindow()) }
        val observed = observe()
        assertEquals(AccessibilityWindowInfo.TYPE_SYSTEM, observed.windowType)
        rejected(screenshot(observed).completed(), "SYSTEM_WINDOW_NODE_ACTIONS_ONLY_NOT_EXECUTED")
        assertEquals(1, platform.cacheClears); assertTrue(platform.callbacks.isEmpty())
        assertNoActions()
    }

    @Test @Config(sdk = [33]) fun stillIdleAfterCacheRefreshIsRejectedWithoutARefreshLoop() {
        platform.active = false
        platform.cacheClearAccepted = true
        platform.listedWindows = { listOf(idleListedWindow()) }
        org.robolectric.shadows.ShadowLog.clear()
        rejected(session.previewFromUser(), "APPLICATION_WINDOW_NOT_ACTIVE_NOT_EXECUTED")
        assertEquals(1, platform.cacheClears); assertEquals(2, platform.windowReads)
        val logs = org.robolectric.shadows.ShadowLog.getLogsForTag("DaiyuGui").map { it.msg }
        assertTrue(logs.any { it.contains("cache_refresh=cleared") && it.contains("reason=root_inactive") })
        assertTrue(logs.none { it.contains("external.app") || it.contains("before") || it.contains("reasoning") })
        assertNull(session.lease.observation); assertNoActions()
    }

    @Test @Config(sdk = [33]) fun refreshDoesNotBypassAnIdleSystemOverlapOrIdentityConflict() {
        platform.cacheClearAccepted = true
        for (windows in listOf<() -> List<AccessibilityWindowInfo>>(
            { listOf(idleListedWindow(), idleListedWindow(9, AccessibilityWindowInfo.TYPE_SYSTEM, 1)) },
            { listOf(idleListedWindow(), idleListedWindow()) },
            { listOf(idleListedWindow(layer = 1)) })) {
            openTarget(null); platform.cacheCleared = false
            platform.listedWindows = windows
            rejected(session.previewFromUser(), "APPLICATION_WINDOW_NOT_ACTIVE_NOT_EXECUTED")
            assertNull(session.lease.observation)
        }
        assertEquals(3, platform.cacheClears); assertEquals(6, platform.windowReads)
        assertNoActions()
    }

    @Test @Config(sdk = [33]) fun cacheRefreshFailureDoesNotPublishOrExecuteAnything() {
        platform.cacheClearFailure = true
        platform.listedWindows = { listOf(idleListedWindow()) }
        rejected(session.previewFromUser(), "APPLICATION_WINDOW_METADATA_UNAVAILABLE_NOT_EXECUTED")
        assertEquals(1, platform.cacheClears); assertEquals(1, platform.windowReads)
        assertNull(session.lease.observation); assertNoActions()
    }

    @Test @Config(sdk = [33]) fun refreshedWindowListsStillEnforceDisplayLimitAndIdentity() {
        platform.cacheClearAccepted = true
        for ((fresh, code) in listOf<Pair<() -> List<AccessibilityWindowInfo>, String>>(
            { emptyList<AccessibilityWindowInfo>() } to "APPLICATION_WINDOW_NOT_ACTIVE_NOT_EXECUTED",
            { List(65) { idleListedWindow(id = it) } } to "APPLICATION_WINDOW_LIMIT_EXCEEDED_NOT_EXECUTED",
            { listOf(listedWindow().apply { shadowOf(this).setDisplayId(1) }) } to "APPLICATION_WINDOW_DISPLAY_MISMATCH_NOT_EXECUTED",
            { listOf(listedWindow(), listedWindow("other.app", 8)) } to "APPLICATION_WINDOW_AMBIGUOUS_NOT_EXECUTED")) {
            openTarget(null); platform.cacheCleared = false
            platform.listedWindows = { if (platform.cacheCleared) fresh() else listOf(idleListedWindow()) }
            rejected(session.previewFromUser(), code)
            assertNull(session.lease.observation)
        }
        assertEquals(4, platform.cacheClears); assertEquals(8, platform.windowReads)
        assertNoActions()
    }

    @Test @Config(sdk = [33]) fun cacheRefreshCanSelectAFocusedOnlyWindowAndRejectAReadException() {
        platform.cacheClearAccepted = true
        platform.listedWindows = { if (platform.cacheCleared) listOf(listedWindow(active = false, focused = true)) else listOf(idleListedWindow()) }
        assertEquals("external.app", observe().packageName)
        assertEquals(1, platform.cacheClears); assertEquals(2, platform.windowReads)
        openTarget(null); platform.cacheCleared = false
        platform.listedWindows = { if (platform.cacheCleared) error("private source error") else listOf(idleListedWindow()) }
        rejected(session.previewFromUser(), "APPLICATION_WINDOW_METADATA_UNAVAILABLE_NOT_EXECUTED")
        assertEquals(2, platform.cacheClears); assertEquals(4, platform.windowReads)
        assertNull(session.lease.observation); assertNoActions()
    }

    @Test @Config(sdk = [33]) fun unavailableCacheRefreshKeepsTheExistingRootCrossCheck() {
        platform.listedWindows = { listOf(idleListedWindow()) }
        assertEquals("external.app", observe().packageName)
        assertEquals(1, platform.cacheClears); assertEquals(1, platform.windowReads)
        assertNoActions()
    }

    @Test fun olderApisKeepTheExistingRootCrossCheckWithoutCacheApiCalls() {
        platform.listedWindows = { listOf(idleListedWindow()) }
        assertEquals("external.app", observe().packageName)
        assertEquals(0, platform.cacheClears); assertEquals(1, platform.windowReads)
        assertNoActions()
    }

    @Test @Config(sdk = [33]) fun activeWindowListsNeverClearTheCache() {
        platform.cacheClearAccepted = true
        platform.listedWindows = { listOf(listedWindow()) }
        assertEquals("external.app", observe().packageName)
        assertEquals(0, platform.cacheClears); assertEquals(1, platform.windowReads)
        assertNoActions()
    }

    @Test fun idleListAndFreshRootFixtureHaveExplicitMatchingWindowMetadata() {
        val listed = idleListedWindow()
        val root = checkNotNull(platform.root(service))
        val fresh = checkNotNull(root.window)
        try {
            assertEquals(listed.id, fresh.id)
            assertEquals(listed.type, fresh.type)
            assertEquals(0, listed.layer)
            assertEquals(listed.layer, fresh.layer)
            assertEquals(listed.displayId, fresh.displayId)
            assertFalse(listed.isActive); assertFalse(listed.isFocused)
            assertTrue(fresh.isActive)
        } finally { fresh.recycle(); root.recycle(); listed.recycle() }
        assertNoActions()
    }

    @Test fun idleListedApplicationUsesOneFreshMatchingActiveRootWithoutRelaxingActionChecks() {
        platform.listedWindows = { listOf(idleListedWindow()) }
        val reads = platform.rootReads
        val observed = observe()
        assertEquals(reads + 1, platform.rootReads)
        assertEquals("external.app", observed.packageName)
        val action = approve(observed)
        platform.pkg = "changed.app"
        assertTrue(GuiRuntime.action(session.lease, action) is GuiResult.Rejected)
        assertNoActions()
    }

    @Test fun idleListAndStillInactiveRootDoNotProduceObservation() {
        platform.active = false
        platform.listedWindows = { listOf(idleListedWindow()) }
        rejected(session.previewFromUser(), "APPLICATION_WINDOW_NOT_ACTIVE_NOT_EXECUTED")
        assertNull(session.lease.observation); assertNoActions()
    }

    @Test fun idleListMissingRootAndIdentityConflictsStayRejected() {
        for (configure in listOf<(Platform) -> Unit>(
            { it.missing = true }, { it.windowId = 9 }, { it.displayId = 1 },
            { it.windowType = AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY })) {
            platform.missing = false; platform.windowId = 7; platform.displayId = 0
            platform.windowType = AccessibilityWindowInfo.TYPE_APPLICATION
            openTarget(null)
            platform.listedWindows = { listOf(idleListedWindow()) }
            configure(platform)
            assertTrue(session.previewFromUser() is GuiResult.Rejected)
            assertNull(session.lease.observation)
        }
        assertNoActions()
    }

    @Test fun idleListDoesNotRecoverDuplicateIdsOrALayerMismatchOrSystemRoots() {
        for (windows in listOf<() -> List<AccessibilityWindowInfo>>(
            { listOf(idleListedWindow(), idleListedWindow()) },
            { listOf(idleListedWindow(layer = 1)) },
            { listOf(idleListedWindow(type = AccessibilityWindowInfo.TYPE_SYSTEM)) })) {
            openTarget(null)
            platform.listedWindows = windows
            rejected(session.previewFromUser(), "APPLICATION_WINDOW_NOT_ACTIVE_NOT_EXECUTED")
            assertNull(session.lease.observation)
        }
        assertNoActions()
    }

    @Test fun idleApplicationCannotBeReadThroughAnOverlappingIdleSystemWindow() {
        platform.listedWindows = { listOf(idleListedWindow(),
            idleListedWindow(id = 9, type = AccessibilityWindowInfo.TYPE_SYSTEM, layer = 1)) }
        rejected(session.previewFromUser(), "APPLICATION_WINDOW_NOT_ACTIVE_NOT_EXECUTED")
        assertNull(session.lease.observation); assertNoActions()
    }

    @Test fun idleRecoveryPreservesExplicitTargetAndSelfPackageFences() {
        openTarget("wanted.app")
        platform.listedWindows = { listOf(idleListedWindow()) }
        rejected(session.previewFromUser(), "TARGET_PACKAGE_MISMATCH_NOT_EXECUTED")
        openTarget(null)
        platform.pkg = service.packageName
        rejected(session.previewFromUser(), "SELF_APP_TARGET_REQUIRED_NOT_EXECUTED")
        assertNull(session.lease.observation); assertNoActions()
    }

    @Test fun focusedApplicationWindowCanBeObservedWithoutRootInActiveWindow() {
        platform.listedWindows = { listOf(listedWindow(active = false, focused = true)) }
        assertEquals("external.app", observe().packageName)
        assertNoActions()
    }

    @Test fun ambiguousApplicationWindowsAreNotSelectedByTargetOrListOrder() {
        openTarget("external.app")
        platform.listedWindows = { listOf(listedWindow(id = 7), listedWindow("other.app", 8)) }
        rejected(session.previewFromUser(), "APPLICATION_WINDOW_AMBIGUOUS_NOT_EXECUTED")
        assertNull(session.lease.observation); assertNoActions()
    }

    @Test fun windowListSelectsActiveBeforeFocusedButNeverFallsThroughToRequestedBackgroundApp() {
        openTarget("external.app")
        platform.listedWindows = {
            listOf(listedWindow(active = false, focused = true, layer = 10), listedWindow("other.app", 8))
        }
        rejected(session.previewFromUser(), "TARGET_PACKAGE_MISMATCH_NOT_EXECUTED")
        assertNoActions()
    }

    @Test fun activeSystemRootCanBeObservedAndClickedExactlyOnceWithoutCoordinateFallback() {
        platform.windowType = AccessibilityWindowInfo.TYPE_SYSTEM
        val observation = observe()
        assertEquals(AccessibilityWindowInfo.TYPE_SYSTEM, observation.windowType)
        assertEquals(AccessibilityWindowInfo.TYPE_SYSTEM,
            org.json.JSONObject(GuiScreenToolFactory.observationJson(observation)).getInt("window_type"))
        val action = approve(observation)
        assertEquals(GuiResult.Value("PLATFORM_ACTION_ACCEPTED_NOT_VERIFIED"), GuiRuntime.action(session.lease, action))
        assertEquals(1, platform.nodeActions)
        assertTrue(GuiRuntime.action(session.lease, action) is GuiResult.Rejected)
        assertEquals(1, platform.nodeActions); assertEquals(0, platform.gestures)
    }

    @Test fun focusedSystemWindowIsSelectedAheadOfTheApplicationItCovers() {
        platform.listedWindows = {
            listOf(listedWindow(pkg = "underlying.app", id = 7, active = true, layer = 1),
                listedWindow(pkg = "system.dialog", id = 9, active = false, focused = true,
                    layer = 2, type = AccessibilityWindowInfo.TYPE_SYSTEM))
        }
        val observation = observe()
        assertEquals("system.dialog", observation.packageName)
        assertEquals(9, observation.windowId)
        assertEquals(AccessibilityWindowInfo.TYPE_SYSTEM, observation.windowType)
        val action = approve(observation)
        assertEquals(GuiResult.Value("PLATFORM_ACTION_ACCEPTED_NOT_VERIFIED"), GuiRuntime.action(session.lease, action))
        assertEquals(1, platform.nodeActions)
    }

    @Test fun focusedSystemWindowWinsATiedLayerWithoutReadingTheActiveApplication() {
        platform.listedWindows = {
            listOf(listedWindow(pkg = "underlying.app", id = 7, active = true, layer = 2),
                listedWindow(pkg = "system.dialog", id = 9, active = false, focused = true,
                    layer = 2, type = AccessibilityWindowInfo.TYPE_SYSTEM))
        }
        assertEquals("system.dialog", observe().packageName)
        assertNoActions()
    }

    @Test fun focusedSystemDialogBlocksSelfObservationAndCaptureWithoutGrantingItsNodes() {
        openSelf()
        val observation = observe()
        platform.listedWindows = {
            listOf(listedWindow(pkg = service.packageName, id = observation.windowId, active = true, layer = 1),
                listedWindow(pkg = "system.dialog", id = 9, active = false, focused = true,
                    layer = 2, type = AccessibilityWindowInfo.TYPE_SYSTEM))
        }
        rejected(screenshot(observation).completed(), "SELF_APP_TARGET_LOST")
        rejected(session.previewFromUser(), "SELF_APP_TARGET_LOST")
        assertNull(session.lease.observation); assertTrue(platform.callbacks.isEmpty()); assertNoActions()
    }

    @Test fun explicitApplicationTargetDoesNotReadThroughASystemDialog() {
        openTarget("underlying.app")
        platform.listedWindows = {
            listOf(listedWindow(pkg = "underlying.app", id = 7, active = true, layer = 1),
                listedWindow(pkg = "system.dialog", id = 9, active = false, focused = true,
                    layer = 2, type = AccessibilityWindowInfo.TYPE_SYSTEM))
        }
        rejected(session.previewFromUser(), "TARGET_PACKAGE_MISMATCH_NOT_EXECUTED")
        assertNull(session.lease.observation); assertNoActions()
    }

    @Test fun systemWindowListsStillRejectAmbiguityIdleAndOtherDisplays() {
        platform.listedWindows = {
            listOf(listedWindow(id = 7, type = AccessibilityWindowInfo.TYPE_SYSTEM),
                listedWindow(pkg = "other.system", id = 9, type = AccessibilityWindowInfo.TYPE_SYSTEM))
        }
        rejected(session.previewFromUser(), "APPLICATION_WINDOW_AMBIGUOUS_NOT_EXECUTED")
        platform.listedWindows = { listOf(listedWindow(active = false, type = AccessibilityWindowInfo.TYPE_SYSTEM)) }
        rejected(session.previewFromUser(), "APPLICATION_WINDOW_NOT_ACTIVE_NOT_EXECUTED")
        platform.listedWindows = null
        platform.active = true; platform.windowType = AccessibilityWindowInfo.TYPE_SYSTEM; platform.displayId = 1
        rejected(session.previewFromUser(), "APPLICATION_WINDOW_DISPLAY_MISMATCH_NOT_EXECUTED")
        assertNoActions()
    }

    @Test fun systemObservationDoesNotEnableScreenshotGlobalOrCoordinateActions() {
        platform.windowType = AccessibilityWindowInfo.TYPE_SYSTEM
        val observation = observe()
        rejected(screenshot(observation).completed(), "SYSTEM_WINDOW_NODE_ACTIONS_ONLY_NOT_EXECUTED")
        for (action in listOf(GuiAction(observation.observationId, GuiOperation.BACK),
            GuiAction(observation.observationId, GuiOperation.COORDINATE_TAP,
                gesture = GuiGesture(20, 20, 20, 20, 50)))) {
            assertTrue(session.approveExactActionFromUser(action) is GuiResult.Value)
            rejected(GuiRuntime.action(session.lease, action), "SYSTEM_WINDOW_NODE_ACTIONS_ONLY_NOT_EXECUTED")
        }
        assertTrue(platform.callbacks.isEmpty()); assertNoActions()
        val action = approve(observation)
        assertEquals(GuiResult.Value("PLATFORM_ACTION_ACCEPTED_NOT_VERIFIED"), GuiRuntime.action(session.lease, action))
        assertEquals(1, platform.nodeActions)
    }

    @Test fun systemNodeDeliveryRejectsChangedWindowTypeAndLockedScreen() {
        platform.windowType = AccessibilityWindowInfo.TYPE_SYSTEM
        val observation = observe(); val action = approve(observation)
        platform.windowType = AccessibilityWindowInfo.TYPE_APPLICATION
        rejected(GuiRuntime.action(session.lease, action), "TARGET_WINDOW_CHANGED_RECONFIRM_NOT_EXECUTED")
        assertNoActions()
        platform.windowType = AccessibilityWindowInfo.TYPE_SYSTEM
        val locked = observe(); val lockedAction = approve(locked)
        platform.screenUnlocked = false
        rejected(GuiRuntime.action(session.lease, lockedAction), "SCREEN_LOCKED_NOT_EXECUTED")
        assertNoActions()
    }

    @Test fun inputMethodsAndAccessibilityOverlaysNeverBecomeSystemDialogTargets() {
        for (type in listOf(AccessibilityWindowInfo.TYPE_INPUT_METHOD, AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY)) {
            platform.windowType = type
            rejected(session.previewFromUser(), "APPLICATION_WINDOW_REQUIRED_NOT_EXECUTED")
        }
        assertNoActions()
    }

    @Test fun productDefaultsIssueExactOriginalLeaseGrantWithoutUiOrExecutingActions() {
        val delivery = defaultDelivery()
        assertSame(session, delivery.session)
        assertEquals(setOf(delivery.action.effect(session.identity)), delivery.writes)
        assertEquals(setOf(delivery.action), GuiRuntime.approvedActions(session.lease))
        assertEquals(0, session.lease.reads); assertEquals(0, session.lease.screenshots)
        assertNoActions()
        assertTrue(GuiRuntime.action(session.lease, delivery.action) is GuiResult.Value)
        assertEquals(1, platform.nodeActions)
        assertTrue(GuiRuntime.action(session.lease, delivery.action) is GuiResult.Rejected)
        assertEquals(1, platform.nodeActions)
    }

    @Test fun defaultGrantExpiresAndCannotBeUsedAfterStopDisconnectOrOwnerLoss() {
        val expired = defaultDelivery()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(GUI_OBSERVATION_TTL_MILLIS + 1))
        rejected(GuiRuntime.action(session.lease, expired.action), "OBSERVATION_EXPIRED")
        assertNoActions()
        session.close()
        assertTrue(session.releaseConfirmed)
        rejected(GuiRuntime.action(session.lease, expired.action), "GUI_AUTHORIZATION_REVOKED")
        session = (GuiRuntime.openForUser(GuiOwner("next", "next")) as GuiResult.Value).value
        var handedOff = false
        rejected(session.prepareExactActionWithProductDefaults(GuiOperation.TAP,
            ownerCurrent = { false }, onConfirmed = { handedOff = true; true }), "GUI_RUN_OWNER_MISMATCH")
        assertFalse(handedOff)
        val next = defaultDelivery()
        GuiRuntime.disconnected(service)
        rejected(GuiRuntime.action(session.lease, next.action), "SERVICE_DISCONNECTED_NOT_EXECUTED")
        GuiRuntime.connected(service)
        rejected(GuiRuntime.action(session.lease, next.action), "GUI_AUTHORIZATION_REVOKED")
        assertNoActions()
    }

    @Test fun defaultPermissionDoesNotInventSystemPermissionAndAndroidRejectionConsumesAction() {
        platform.permissionEnabled = false
        var handedOff = false
        val denied = session.prepareExactActionWithProductDefaults(GuiOperation.TAP,
            ownerCurrent = { true }, onConfirmed = { handedOff = true; true })
        assertTrue(denied is GuiResult.Rejected)
        assertFalse(handedOff); assertNoActions()
        platform.permissionEnabled = true
        GuiRuntime.connected(service)
        session = (GuiRuntime.openForUser(GuiOwner("fresh", "fresh")) as GuiResult.Value).value
        val delivery = defaultDelivery()
        platform.actionAccepted = false
        assertEquals(GuiResult.Value("PLATFORM_ACTION_REJECTED"), GuiRuntime.action(session.lease, delivery.action))
        assertEquals(1, platform.nodeActions)
        assertTrue(GuiRuntime.action(session.lease, delivery.action) is GuiResult.Rejected)
        assertEquals(1, platform.nodeActions)
    }

    @Test fun screenOffRevokesDefaultGrantEvenIfNoToolRunsWhileLocked() {
        val delivery = defaultDelivery()
        GuiRuntime.screenOff(service)
        // Model resumes only after unlock: the old grant must already be dead.
        assertTrue(platform.screenUnlocked)
        rejected(GuiRuntime.action(session.lease, delivery.action), "GUI_AUTHORIZATION_REVOKED")
        assertNoActions()
    }

    @Test fun defaultGrantIsRevokedByLockAndUnlockCannotResurrectIt() {
        val delivery = defaultDelivery()
        platform.screenUnlocked = false
        rejected(GuiRuntime.action(session.lease, delivery.action), "SCREEN_LOCKED_NOT_EXECUTED")
        platform.screenUnlocked = true
        rejected(GuiRuntime.action(session.lease, delivery.action), "GUI_AUTHORIZATION_REVOKED")
        assertNoActions()
    }

    @Test fun recyclingReturnedWindowDoesNotInvalidateFixtureNodeWindowIds() {
        platform.windowId = 41
        val root = checkNotNull(platform.root(service))
        try {
            val child = checkNotNull(root.getChild(0))
            try {
                val first = checkNotNull(root.window)
                try {
                    assertEquals(41, first.id)
                    assertEquals(AccessibilityWindowInfo.TYPE_APPLICATION, first.type)
                    assertTrue(first.isActive)
                    assertEquals(0, first.displayId)
                } finally { first.recycle() }
                assertEquals(41, root.windowId)
                assertEquals(41, child.windowId)
                val second = checkNotNull(child.window)
                try { assertEquals(41, second.id) } finally { second.recycle() }
                assertEquals(41, root.windowId)
                assertEquals(41, child.windowId)
            } finally { child.recycle() }
        } finally { root.recycle() }
    }

    @Test fun liveSelfTokenAllowsOnlyTheOwnApplicationTreeExactNodeActionAndScreenshot() {
        openSelf()
        val observation = observe(); val action = approve(observation)
        assertEquals(service.packageName, observation.packageName)
        assertTrue(session.validateExactActionFromUser(action) is GuiResult.Value)
        val future = screenshot(observation); val frame = platform.success(); worker.runAll()
        assertTrue(future.completed() is GuiResult.Value); assertDisposed(frame)
        assertEquals(GuiResult.Value("PLATFORM_ACTION_ACCEPTED_NOT_VERIFIED"), GuiRuntime.action(session.lease, action))
        assertEquals(1, platform.nodeActions)
        assertTrue(shadowOf(service).globalActionsPerformed.isEmpty())
    }

    @Test fun missingTargetRemainsExternalEvenWhenALiveSelfTokenWasSupplied() {
        val owner = Any()
        val token = TaskSurfaceSelfTarget.foreground(service.packageName, owner) { true }
        openTarget(null, token, owner)
        platform.pkg = service.packageName
        rejected(session.previewFromUser(), "SELF_APP_TARGET_REQUIRED_NOT_EXECUTED")
        platform.pkg = "external.app"
        assertEquals("external.app", observe().packageName)
        assertNoActions()
    }

    @Test fun ownPackageCannotOpenWithMissingWrongOwnerWrongPackageOrRevokedToken() {
        session.close()
        val owner = Any()
        val valid = TaskSurfaceSelfTarget.foreground(service.packageName, owner) { true }
        val wrongPackage = TaskSurfaceSelfTarget.foreground("another.app", owner) { true }
        val ended = TaskSurfaceSelfTarget.foreground(service.packageName, owner) { false }
        val guiOwner = GuiOwner("new", "run")
        listOf(
            null to owner, valid to null, valid to Any(), wrongPackage to owner, ended to owner,
        ).forEach { (token, suppliedOwner) ->
            rejected(GuiRuntime.openForUser(guiOwner, service.packageName, token, suppliedOwner), "SELF_APP_TARGET_LOST")
        }
        assertEquals(0, platform.rootReads)
        assertNoActions()
    }

    @Test fun runtimeAlsoRejectsMalformedTrustedTargetsBeforeAcquiringOrReading() {
        session.close()
        listOf("", "external", "external..app", " external.app", "a." + "b".repeat(255)).forEach { target ->
            rejected(GuiRuntime.openForUser(GuiOwner("target", "run"), target), "INVALID_TARGET_PACKAGE_NOT_EXECUTED")
        }
        assertEquals(0, platform.rootReads); assertNoActions()
    }

    @Test fun explicitExternalTargetMatchesExactlyAndDoesNotFallThroughToAnotherOrOwnPackage() {
        openTarget("chosen.app")
        rejected(session.previewFromUser(), "TARGET_PACKAGE_MISMATCH_NOT_EXECUTED")
        platform.pkg = service.packageName
        rejected(session.previewFromUser(), "SELF_APP_TARGET_REQUIRED_NOT_EXECUTED")
        platform.pkg = "chosen.app"
        val observation = observe()
        platform.pkg = "external.app"
        rejected(screenshot(observation).completed(), "SCREENSHOT_TREE_UNVERIFIABLE")
        rejected(GuiRuntime.validateExactTarget(session.lease, GuiAction(observation.observationId, GuiOperation.TAP, 1)),
            "OBSERVATION_REQUIRED")
        assertTrue(platform.callbacks.isEmpty()); assertNoActions()
    }

    @Test fun explicitExternalStillRequiresAnActiveDefaultDisplayApplication() {
        openTarget("external.app")
        platform.active = false
        rejected(session.previewFromUser(), "APPLICATION_WINDOW_NOT_ACTIVE_NOT_EXECUTED")
        platform.active = true; platform.windowType = AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
        rejected(session.previewFromUser(), "APPLICATION_WINDOW_REQUIRED_NOT_EXECUTED")
        platform.windowType = AccessibilityWindowInfo.TYPE_APPLICATION; platform.displayId = 1
        rejected(session.previewFromUser(), "APPLICATION_WINDOW_DISPLAY_MISMATCH_NOT_EXECUTED")
        assertTrue(platform.callbacks.isEmpty()); assertNoActions()
    }

    @Test fun selfPackageLossWithoutAnEventRefusesOldNodeAndScreenshotWithoutAnyFallback() {
        openSelf()
        val observation = observe(); val action = approve(observation)
        platform.pkg = "external.app"
        rejected(GuiRuntime.action(session.lease, action), "SELF_APP_TARGET_LOST")
        rejected(screenshot(observation).completed(), "SELF_APP_TARGET_LOST")
        assertNull(session.lease.observation); assertTrue(session.lease.actions.isEmpty())
        assertEquals(1, session.lease.screenshots)
        // Returning to self does not resurrect the old node/id/approval.
        platform.pkg = service.packageName
        rejected(GuiRuntime.action(session.lease, action), "OBSERVATION_REQUIRED")
        assertTrue(platform.callbacks.isEmpty()); assertNoActions()
    }

    @Test fun selfPackageLossAfterAnEventReportsLossRatherThanExecutingAStaleNode() {
        openSelf()
        val observation = observe(); val action = approve(observation)
        platform.pkg = "external.app"; GuiRuntime.changed(service)
        rejected(GuiRuntime.action(session.lease, action), "SELF_APP_TARGET_LOST")
        rejected(screenshot(observation).completed(), "SELF_APP_TARGET_LOST")
        assertTrue(platform.callbacks.isEmpty()); assertNoActions()
    }

    @Test fun selfMissingInactiveOverlayAndOtherDisplayAllFailClosed() {
        listOf<(Platform) -> Unit>(
            { it.missing = true }, { it.active = false },
            { it.windowType = AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }, { it.displayId = 1 },
        ).forEach { loseTarget ->
            platform.missing = false; platform.active = true
            platform.windowType = AccessibilityWindowInfo.TYPE_APPLICATION; platform.displayId = 0
            openSelf()
            val observation = observe(); val action = approve(observation)
            loseTarget(platform)
            rejected(GuiRuntime.action(session.lease, action), "SELF_APP_TARGET_LOST")
            rejected(screenshot(observation).completed(), "SELF_APP_TARGET_LOST")
            assertTrue(platform.callbacks.isEmpty()); assertNoActions()
        }
    }

    @Test fun selfTokenAndGuardedAdmissionAreRecheckedOnTheOriginalLease() {
        val owner = Any()
        var current = true
        var admitted = true
        val token = TaskSurfaceSelfTarget.foreground(service.packageName, owner) { current }.guardedBy { admitted }
        platform.pkg = service.packageName
        platform.stableNodes = true
        openTarget(service.packageName, token, owner)
        val observation = observe(); val action = approve(observation)
        admitted = false
        rejected(GuiRuntime.action(session.lease, action), "SELF_APP_TARGET_LOST")
        rejected(session.previewFromUser(), "SELF_APP_TARGET_LOST")
        rejected(screenshot(observation).completed(), "SELF_APP_TARGET_LOST")
        admitted = true; current = false // run.end / original surface owner is no longer current
        rejected(session.approveReadsFromUser(GuiReadApproval(1, 1)), "SELF_APP_TARGET_LOST")
        assertTrue(platform.callbacks.isEmpty()); assertNoActions()
    }

    @Test fun selfTokenDoesNotBypassScreenLockOrSystemPermissionRevocation() {
        openSelf()
        val observation = observe(); val action = approve(observation)
        platform.screenUnlocked = false
        rejected(GuiRuntime.action(session.lease, action), "SCREEN_LOCKED_NOT_EXECUTED")
        rejected(screenshot(observation).completed(), "GUI_AUTHORIZATION_REVOKED")
        platform.screenUnlocked = true
        rejected(GuiRuntime.action(session.lease, action), "GUI_AUTHORIZATION_REVOKED")
        openSelf()
        val fresh = observe(); val freshAction = approve(fresh)
        platform.permissionEnabled = false
        rejected(GuiRuntime.action(session.lease, freshAction), "SERVICE_PERMISSION_REVOKED_NOT_EXECUTED")
        assertFalse(session.lease.valid)
        rejected(screenshot(fresh).completed(), "SERVICE_DISCONNECTED_NOT_EXECUTED")
        assertTrue(platform.callbacks.isEmpty()); assertNoActions()
    }

    @Test fun selfLossWhileEncodingIsQueuedRejectsDeliveryButDrainsTheSameLease() {
        openSelf()
        val observation = observe(); val future = screenshot(observation)
        val frame = platform.success()
        platform.pkg = "external.app"
        worker.runAll()
        rejected(future.completed(), "SELF_APP_TARGET_LOST"); assertDisposed(frame)
        session.close(); assertTrue(session.releaseConfirmed)
        assertNoActions()
    }

    @Test fun revokedSelfTokenBeforeCallbackDoesNotEncodeOrReleaseBeforeDisposal() {
        var current = true
        openSelf(current = { current })
        val observation = observe(); val future = screenshot(observation)
        current = false
        val frame = platform.success()
        rejected(future.completed(), "SELF_APP_TARGET_LOST")
        assertEquals(0, frame.encoded); assertDisposed(frame)
        session.close(); assertTrue(session.releaseConfirmed); assertNoActions()
    }

    @Test fun selfTokenRevocationAfterEncodingStillRejectsMainThreadDelivery() {
        var current = true
        openSelf(current = { current })
        val observation = observe(); val future = screenshot(observation); val frame = platform.success()
        worker.runAll(deliver = false)
        current = false
        shadowOf(Looper.getMainLooper()).idle()
        rejected(future.completed(), "SELF_APP_TARGET_LOST"); assertDisposed(frame)
        session.close(); assertTrue(session.releaseConfirmed); assertNoActions()
    }

    @Test fun selfGlobalActionsAreRejectedAtPreparationApprovalValidationAndExecution() {
        openSelf()
        val observation = observe()
        listOf(GuiOperation.BACK, GuiOperation.HOME, GuiOperation.RECENTS, GuiOperation.NOTIFICATIONS).forEach { operation ->
            val action = GuiAction(observation.observationId, operation)
            val code = "SELF_APP_GLOBAL_ACTION_NOT_ALLOWED_NOT_EXECUTED"
            rejected(session.approveExactActionFromUser(action), code)
            rejected(session.validateExactActionFromUser(action), code)
            rejected(GuiRuntime.action(session.lease, action), code)
            rejected(session.prepareExactActionFromUser(operation, ownerCurrent = { true }, onConfirmed = { false }), code)
        }
        assertTrue(session.lease.actions.isEmpty()); assertTrue(platform.callbacks.isEmpty()); assertNoActions()
    }

    @Test fun explicitLaunchLeavesTruncatedSelfScopeAndRequiresFreshExternalObservation() {
        platform.descendants = 2000
        openSelf()
        val self = observe()
        assertTrue(self.truncated)
        rejected(GuiRuntime.action(session.lease, GuiAction(self.observationId, GuiOperation.HOME)),
            "SELF_APP_GLOBAL_ACTION_NOT_ALLOWED_NOT_EXECUTED")
        val oldLease = session.lease
        val target = "com.eg.android.AlipayGphone"
        openTarget(target)
        assertFalse(oldLease.valid)
        assertTrue(GuiRuntime.launchApp(session.lease, GuiAppLaunch(target)) is GuiResult.Value)
        assertNull(session.lease.observation)
        val external = observe()
        assertEquals(target, external.packageName)
        assertNotEquals(self.observationId, external.observationId)
        rejected(GuiRuntime.validateExactTarget(session.lease, GuiAction(self.observationId, GuiOperation.TAP, 1)),
            "OBSERVATION_ID_MISMATCH")
        val action = approve(external)
        assertTrue(GuiRuntime.action(session.lease, action) is GuiResult.Value)
        assertEquals(1, platform.appLaunches)
        assertEquals(1, platform.nodeActions)
        assertTrue(shadowOf(service).globalActionsPerformed.isEmpty())
    }

    @Test fun unchangedTargetRemainsValidAfterElevenSeconds() {
        val observation = observe(); val action = approve(observation)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(11))
        assertTrue(GuiRuntime.action(session.lease, action) is GuiResult.Value)
        assertEquals(1, platform.nodeActions)
    }


    @Test fun actualSelfContentEventsKeepAnUnchangedTargetOnALongTruncatedChat() {
        listOf(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED).forEach { type ->
            platform.savedRoot = null; platform.savedTarget = null
            platform.descendants = 2000; platform.rootLabel = "streaming body"
            openSelf()
            val observation = observe(); assertTrue(observation.truncated)
            val action = approve(observation)
            service.onAccessibilityEvent(AccessibilityEvent.obtain(type).apply { packageName = service.packageName })
            rejected(screenshot(observation).completed(), "OBSERVATION_STALE")
            val before = platform.nodeActions
            assertTrue(GuiRuntime.action(session.lease, action) is GuiResult.Value)
            assertEquals(before + 1, platform.nodeActions)
            assertTrue(GuiRuntime.action(session.lease, action) is GuiResult.Rejected)
            assertEquals(before + 1, platform.nodeActions)
        }
    }

    private fun contentEvent(type: Int, pkg: String?, window: Int): AccessibilityEvent =
        AccessibilityEvent.obtain(type).apply {
            packageName = pkg
            ReflectionHelpers.callInstanceMethod<Unit>(this, "setWindowId",
                ClassParameter.from(Int::class.javaPrimitiveType!!, window))
        }

    @Test fun unlabelledContentInTheObservedSelfWindowStillUsesExactLocalValidation() {
        for (type in listOf(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED)) {
            platform.savedRoot = null; platform.savedTarget = null; platform.descendants = 2000
            openSelf(); val observation = observe(); val action = approve(observation)
            service.onAccessibilityEvent(contentEvent(type, null, observation.windowId))
            assertTrue(GuiRuntime.action(session.lease, action) is GuiResult.Value)
        }
        assertEquals(4, platform.nodeActions)
    }

    @Test fun foreignWindowContentDoesNotRetireTheSelfTargetBeforeActionApproval() {
        for (truncated in listOf(false, true)) for (pkg in listOf(null, "com.android.systemui")) {
            platform.savedRoot = null; platform.savedTarget = null; platform.descendants = if (truncated) 2000 else 0
            openSelf(); val observation = observe(); assertEquals(truncated, observation.truncated)
            val action = GuiAction(observation.observationId, GuiOperation.TAP, 1)
            service.onAccessibilityEvent(contentEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                pkg, observation.windowId + 1))
            assertEquals(observation.observationId, session.lease.observation!!.observationId)
            assertTrue(session.approveExactActionFromUser(action) is GuiResult.Value)
            rejected(screenshot(observation).completed(), "OBSERVATION_STALE")
            val actions = platform.nodeActions
            assertTrue(GuiRuntime.action(session.lease, action) is GuiResult.Value)
            assertEquals(actions + 1, platform.nodeActions)
            assertTrue(GuiRuntime.action(session.lease, action) is GuiResult.Rejected)
            assertEquals(actions + 1, platform.nodeActions)
        }
        assertEquals(4, platform.nodeActions)
    }

    @Test fun ownOtherWindowContentRemainsDirtyWithoutReadingOrReplacingTheObservedTarget() {
        openSelf(); val observation = observe(); val action = approve(observation)
        val reads = platform.rootReads
        service.onAccessibilityEvent(contentEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            service.packageName, observation.windowId + 1))
        assertEquals(reads, platform.rootReads)
        assertEquals(observation.observationId, session.lease.observation!!.observationId)
        rejected(screenshot(observation).completed(), "OBSERVATION_STALE")
        assertTrue(GuiRuntime.action(session.lease, action) is GuiResult.Value)
        assertEquals(1, platform.nodeActions)
    }

    @Test fun contentWithoutAnyWindowOrPackageEvidenceAndForeignSameWindowStillRetires() {
        for ((pkg, window) in listOf(null to -1, "com.android.systemui" to -1,
            "com.android.systemui" to platform.windowId)) {
            openSelf(); val observation = observe(); val action = approve(observation)
            service.onAccessibilityEvent(contentEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, pkg, window))
            rejected(GuiRuntime.action(session.lease, action), "OBSERVATION_STALE")
        }
        assertNoActions()
    }

    @Test fun foreignWindowContentDoesNotSoftenWindowClickFocusOrSelectionEvents() {
        for (type in listOf(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED, AccessibilityEvent.TYPE_VIEW_SELECTED)) {
            openSelf(); val observation = observe(); val action = approve(observation)
            service.onAccessibilityEvent(contentEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                "com.android.systemui", observation.windowId + 1))
            service.onAccessibilityEvent(contentEvent(type, service.packageName, observation.windowId))
            service.onAccessibilityEvent(contentEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                service.packageName, observation.windowId))
            assertTrue(GuiRuntime.action(session.lease, action) is GuiResult.Rejected)
        }
        assertNoActions()
    }

    @Test fun foreignWindowContentCannotHideLossOfTheActualSelfSurface() {
        openSelf(); val observation = observe(); val action = approve(observation)
        service.onAccessibilityEvent(contentEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            "com.android.systemui", observation.windowId + 1))
        platform.pkg = "another.app"; platform.savedRoot = null
        rejected(GuiRuntime.action(session.lease, action), "SELF_APP_TARGET_LOST")
        platform.pkg = service.packageName; platform.savedRoot = null
        assertTrue(GuiRuntime.action(session.lease, action) is GuiResult.Rejected)
        assertNoActions()
    }

    @Test fun newlyScopedContentStillRejectsTrueTargetChangesWithoutRetry() {
        openSelf(); val observation = observe(); val action = approve(observation)
        service.onAccessibilityEvent(contentEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            "com.android.systemui", observation.windowId + 1))
        platform.targetBounds = Rect(0, 25, 100, 125)
        rejected(GuiRuntime.action(session.lease, action), "NODE_STALE")
        assertNull(session.lease.observation); assertNoActions()
    }

    @Test fun foreignWindowContentKeepsTheExactExternalNodeBeforeApproval() {
        for (pkg in listOf(null, service.packageName, platform.pkg)) {
            openTarget(null)
            val observation = observe()
            val reads = platform.rootReads
            service.onAccessibilityEvent(contentEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                pkg, observation.windowId + 1))
            assertEquals(reads, platform.rootReads)
            assertEquals(observation.observationId, session.lease.observation!!.observationId)
            val action = GuiAction(observation.observationId, GuiOperation.TAP, 1)
            assertTrue(session.approveExactActionFromUser(action) is GuiResult.Value)
            rejected(screenshot(observation).completed(), "OBSERVATION_STALE")
            assertTrue(platform.callbacks.isEmpty())
            val actions = platform.nodeActions
            assertEquals(GuiResult.Value("PLATFORM_ACTION_ACCEPTED_NOT_VERIFIED"),
                GuiRuntime.action(session.lease, action))
            assertEquals(actions + 1, platform.nodeActions)
            assertTrue(GuiRuntime.action(session.lease, action) is GuiResult.Rejected)
            assertEquals(actions + 1, platform.nodeActions)
        }
        assertEquals(3, platform.nodeActions)
        assertEquals(0, platform.gestures)
    }

    @Test fun foreignWindowContentCannotReviveRetiredExternalObservations() {
        val retiring = listOf(
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED to platform.windowId,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED to -1,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED to platform.windowId + 1,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED to platform.windowId + 1,
            AccessibilityEvent.TYPE_VIEW_CLICKED to platform.windowId + 1,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED to platform.windowId + 1,
            AccessibilityEvent.TYPE_VIEW_FOCUSED to platform.windowId + 1,
            AccessibilityEvent.TYPE_VIEW_SELECTED to platform.windowId + 1,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED to platform.windowId + 1,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED to platform.windowId + 1,
            AccessibilityEvent.TYPE_VIEW_SCROLLED to platform.windowId + 1)
        for ((type, window) in retiring) {
            openTarget(null)
            val observation = observe(); val action = approve(observation)
            service.onAccessibilityEvent(contentEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                null, observation.windowId + 1))
            service.onAccessibilityEvent(contentEvent(type, platform.pkg, window))
            service.onAccessibilityEvent(contentEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                null, observation.windowId + 1))
            rejected(GuiRuntime.action(session.lease, action), "OBSERVATION_STALE")
        }
        assertNoActions(); assertEquals(0, platform.gestures)
    }

    @Test fun foreignWindowContentStillRevalidatesExternalWindowPackageIdentityBoundsAndHandle() {
        for (mutation in listOf("window", "package", "label", "bounds", "refresh", "duplicate")) {
            platform.pkg = "external.app"; platform.windowId = 7; platform.label = "before"
            platform.rootLabel = "root"; platform.targetBounds = Rect(0, 0, 100, 100)
            platform.targetRefresh = true; platform.stableNodes = true
            platform.savedRoot = null; platform.savedTarget = null
            openTarget(null)
            val observation = observe(); val action = approve(observation)
            service.onAccessibilityEvent(contentEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                null, observation.windowId + 1))
            val expected = when (mutation) {
                "window" -> { platform.windowId++; "TARGET_WINDOW_CHANGED_RECONFIRM_NOT_EXECUTED" }
                "package" -> { platform.pkg = "another.app"; "TARGET_WINDOW_CHANGED_RECONFIRM_NOT_EXECUTED" }
                "label" -> { platform.label = "changed"; "TARGET_NOT_UNIQUE_RECONFIRM_NOT_EXECUTED" }
                "bounds" -> { platform.targetBounds = Rect(0, 25, 100, 125); "TARGET_NOT_UNIQUE_RECONFIRM_NOT_EXECUTED" }
                "refresh" -> { platform.targetRefresh = false; "NODE_STALE" }
                else -> { platform.rootLabel = "before"; "TARGET_NOT_UNIQUE_RECONFIRM_NOT_EXECUTED" }
            }
            rejected(GuiRuntime.action(session.lease, action), expected)
            platform.pkg = "external.app"; platform.windowId = 7; platform.label = "before"
            platform.rootLabel = "root"; platform.targetBounds = Rect(0, 0, 100, 100)
            platform.targetRefresh = true
            rejected(GuiRuntime.action(session.lease, action), "OBSERVATION_REQUIRED")
        }
        assertNoActions(); assertEquals(0, platform.gestures)
    }

    @Test fun foreignWindowContentDoesNotGrantTruncatedExternalTreesTheSelfException() {
        platform.descendants = 160
        val observation = observe(); assertTrue(observation.truncated)
        val action = approve(observation)
        service.onAccessibilityEvent(contentEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            null, observation.windowId + 1))
        rejected(GuiRuntime.action(session.lease, action), "TARGET_NOT_UNIQUE_RECONFIRM_NOT_EXECUTED")
        assertNoActions(); assertEquals(0, platform.gestures)
    }

    @Test fun dirtyExternalObservationRejectsGlobalAndCoordinateActions() {
        permitGestureWindow()
        for (operation in listOf(GuiOperation.BACK, GuiOperation.COORDINATE_TAP)) {
            openTarget(null)
            val observation = observe()
            val action = if (operation == GuiOperation.BACK) GuiAction(observation.observationId, operation)
                else GuiAction(observation.observationId, operation, gesture = GuiGesture(20, 30, 20, 30, 50))
            assertTrue(session.approveExactActionFromUser(action) is GuiResult.Value)
            service.onAccessibilityEvent(contentEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                null, observation.windowId + 1))
            rejected(GuiRuntime.action(session.lease, action), "OBSERVATION_STALE")
        }
        assertNoActions(); assertEquals(0, platform.gestures)
    }

    @Test fun freshExternalObservationRecoversScreenshotWithoutRevivingTheOldId() {
        val original = observe()
        service.onAccessibilityEvent(contentEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            null, original.windowId + 1))
        rejected(screenshot(original).completed(), "OBSERVATION_STALE")
        assertTrue(platform.callbacks.isEmpty())
        val fresh = observe()
        assertNotEquals(original.observationId, fresh.observationId)
        rejected(GuiRuntime.validateExactTarget(session.lease,
            GuiAction(original.observationId, GuiOperation.TAP, 1)), "OBSERVATION_ID_MISMATCH")
        val result = screenshot(fresh)
        val frame = platform.success(); worker.runAll()
        assertTrue(result.completed() is GuiResult.Value); assertDisposed(frame)
        assertTrue(GuiRuntime.action(session.lease, approve(fresh)) is GuiResult.Value)
        assertEquals(1, platform.nodeActions)
    }

    @Test fun foreignWindowContentRejectsExternalScreenshotDuringCallbackAndEncoding() {
        for (duringEncoding in listOf(false, true)) {
            openTarget(null)
            val observation = observe(); val result = screenshot(observation)
            val frame = if (duringEncoding) platform.success() else null
            service.onAccessibilityEvent(contentEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                null, observation.windowId + 1))
            val delivered = frame ?: platform.success()
            worker.runAll()
            rejected(result.completed(), "OBSERVATION_STALE"); assertDisposed(delivered)
        }
        assertNoActions(); assertEquals(0, platform.gestures)
    }

    @Test fun externalSameWindowContentStillRetiresTheObservation() {
        val observation = observe(); val action = approve(observation)
        service.onAccessibilityEvent(contentEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            "com.android.systemui", observation.windowId))
        rejected(GuiRuntime.action(session.lease, action), "OBSERVATION_STALE")
        assertNoActions()
    }

    @Test fun staleDiagnosticsKeepOnlyTheFirstRetiringEventAndNeverCopyEventText() {
        org.robolectric.shadows.ShadowLog.clear()
        openSelf(); val observation = observe(); val action = approve(observation)
        service.onAccessibilityEvent(contentEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            service.packageName, observation.windowId).apply { text.add("private-chat-secret") })
        service.onAccessibilityEvent(contentEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED,
            "private.package", observation.windowId).apply { contentDescription = "credential-secret" })
        rejected(GuiRuntime.action(session.lease, action), "OBSERVATION_STALE")
        rejected(GuiRuntime.action(session.lease, action), "OBSERVATION_STALE")
        val logs = org.robolectric.shadows.ShadowLog.getLogsForTag("DaiyuGui")
        assertEquals(1, logs.size)
        val line = logs.single().msg
        assertTrue(line.contains("stage=validate_target"))
        assertTrue(line.contains("event_type=${AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED}"))
        assertTrue(line.contains("origin=self"))
        assertFalse(line.contains("private")); assertFalse(line.contains("credential"))
        assertFalse(line.contains(service.packageName)); assertFalse(line.contains(observation.observationId))
        assertTrue(line.length < 256)
        assertNoActions()
    }

    @Test fun staleDiagnosticsRecordContentThatActuallyRetiresAnExternalLease() {
        org.robolectric.shadows.ShadowLog.clear()
        val observation = observe(); val action = approve(observation)
        service.onAccessibilityEvent(contentEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            "com.android.systemui", observation.windowId))
        rejected(GuiRuntime.action(session.lease, action), "OBSERVATION_STALE")
        val line = org.robolectric.shadows.ShadowLog.getLogsForTag("DaiyuGui").single().msg
        assertTrue(line.contains("event_type=${AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED}"))
        assertTrue(line.contains("origin=other"))
        assertNoActions()
    }

    @Test fun staleAtApprovalLogsOnceBeforeAnyActionIsAttempted() {
        org.robolectric.shadows.ShadowLog.clear()
        openSelf(); val observation = observe()
        val action = GuiAction(observation.observationId, GuiOperation.TAP, 1)
        service.onAccessibilityEvent(contentEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            service.packageName, observation.windowId))
        rejected(session.approveExactActionFromUser(action), "OBSERVATION_STALE")
        rejected(GuiRuntime.action(session.lease, action), "OBSERVATION_STALE")
        val line = org.robolectric.shadows.ShadowLog.getLogsForTag("DaiyuGui").single().msg
        assertTrue(line.contains("stage=approve_action"))
        assertTrue(line.contains("event_type=${AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED}"))
        assertNoActions()
    }

    @Test fun dirtyScreenshotLogsItsContentCauseWithoutRetiringTheExactNode() {
        org.robolectric.shadows.ShadowLog.clear()
        openSelf(); val observation = observe(); val action = approve(observation)
        service.onAccessibilityEvent(contentEvent(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            null, observation.windowId))
        rejected(screenshot(observation).completed(), "OBSERVATION_STALE")
        rejected(screenshot(observation).completed(), "OBSERVATION_STALE")
        val line = org.robolectric.shadows.ShadowLog.getLogsForTag("DaiyuGui").single().msg
        assertTrue(line.contains("stage=validate_screenshot"))
        assertTrue(line.contains("event_type=${AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED}"))
        assertTrue(line.contains("origin=unspecified"))
        assertTrue(GuiRuntime.action(session.lease, action) is GuiResult.Value)
        assertEquals(1, platform.nodeActions)
    }

    @Test fun screenshotCallbackLogsAFirstHardEventEvenAfterEarlierContentDirtiness() {
        org.robolectric.shadows.ShadowLog.clear()
        openSelf(); val observation = observe(); val future = screenshot(observation)
        service.onAccessibilityEvent(contentEvent(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            null, observation.windowId))
        service.onAccessibilityEvent(contentEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            service.packageName, observation.windowId))
        val frame = platform.success()
        rejected(future.completed(), "OBSERVATION_STALE")
        assertDisposed(frame); assertEquals(0, frame.encoded)
        val line = org.robolectric.shadows.ShadowLog.getLogsForTag("DaiyuGui").single().msg
        assertTrue(line.contains("stage=capture_callback"))
        assertTrue(line.contains("event_type=${AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED}"))
        assertTrue(line.contains("origin=self"))
        assertNoActions()
    }

    @Test @Config(sdk = [26])
    fun api26EventDiagnosticsDoNotReadNewerFrameworkWindowChanges() {
        org.robolectric.shadows.ShadowLog.clear()
        openSelf(); val observation = observe(); val action = approve(observation)
        service.onAccessibilityEvent(contentEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            service.packageName, observation.windowId))
        rejected(GuiRuntime.action(session.lease, action), "OBSERVATION_STALE")
        val line = org.robolectric.shadows.ShadowLog.getLogsForTag("DaiyuGui").single().msg
        assertTrue(line.contains("window_changes=0"))
        assertTrue(line.contains("event_type=${AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED}"))
        assertNoActions()
    }

    @Test fun aNewObservationResetsTheCauseAndItsSingleLogBudget() {
        org.robolectric.shadows.ShadowLog.clear()
        openSelf()
        for (type in listOf(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_VIEW_FOCUSED)) {
            val observation = observe(); val action = approve(observation)
            service.onAccessibilityEvent(contentEvent(type, service.packageName, observation.windowId))
            rejected(GuiRuntime.action(session.lease, action), "OBSERVATION_STALE")
        }
        val logs = org.robolectric.shadows.ShadowLog.getLogsForTag("DaiyuGui")
        assertEquals(2, logs.size)
        assertTrue(logs[0].msg.contains("event_type=${AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED}"))
        assertTrue(logs[1].msg.contains("event_type=${AccessibilityEvent.TYPE_VIEW_FOCUSED}"))
        assertNoActions()
    }

    @Test fun anIncompleteTargetSubtreeNeverUsesTheLongChatException() {
        platform.targetChildren = 40
        openSelf(); val observation = observe(); val action = approve(observation)
        rejected(GuiRuntime.action(session.lease, action), "NODE_STALE")
        assertNull(session.lease.observation); assertNoActions()
    }

    @Test fun aChildSemanticChangeBetweenFirstValidationAndDeliveryStillRejects() {
        platform.wrapper = true
        openSelf(); val observation = observe(); val action = approve(observation)
        platform.onRefresh = { count -> if (count == 2) platform.childLabel = "删除" }
        rejected(GuiRuntime.action(session.lease, action), "NODE_STALE")
        assertNull(session.lease.observation); assertNoActions()
    }

    @Test fun reparentingTheOriginalTargetUnderANewAncestorIsNotAllowed() {
        openSelf(); val observation = observe(); val action = approve(observation)
        platform.savedTarget!!.let { target ->
            val middle = AccessibilityNodeInfo.obtain().apply { packageName = service.packageName }
            shadowOf(middle).addChild(target)
        }
        rejected(GuiRuntime.action(session.lease, action), "NODE_STALE")
        assertNull(session.lease.observation); assertNoActions()
    }

    @Test fun anEmptyWrapperCannotKeepItsGrantWhenItsChildActionChanges() {
        platform.wrapper = true
        openSelf(); val observation = observe(); val action = approve(observation)
        platform.childLabel = "删除"
        GuiRuntime.changed(service, contentOnly = true)
        rejected(GuiRuntime.action(session.lease, action), "NODE_STALE")
        platform.childLabel = "更多"
        rejected(GuiRuntime.action(session.lease, action), "OBSERVATION_REQUIRED")
        assertNoActions()
    }

    @Test fun aDetachedOriginalHandleCannotBorrowAnIdenticalNewRoot() {
        openSelf(); val observation = observe(); val action = approve(observation)
        platform.savedRoot = null; platform.savedTarget = null
        rejected(GuiRuntime.action(session.lease, action), "NODE_STALE")
        assertNoActions()
    }

    @Test fun aFailedRefreshDoesNotSearchForAReplacementOrRetry() {
        openSelf(); val observation = observe(); val action = approve(observation)
        platform.targetRefresh = false
        rejected(GuiRuntime.action(session.lease, action), "NODE_STALE")
        platform.targetRefresh = true
        rejected(GuiRuntime.action(session.lease, action), "OBSERVATION_REQUIRED")
        assertNoActions()
    }

    @Test fun trueTargetMovementStillRetiresTheObservation() {
        openSelf(); val observation = observe(); val action = approve(observation)
        platform.targetBounds = Rect(0, 20, 100, 120)
        service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_SCROLLED).apply { packageName = service.packageName })
        rejected(GuiRuntime.action(session.lease, action), "NODE_STALE")
        assertNoActions()
    }

    @Test fun hardSelfEventsCannotReviveAnOldGrantWhenThePageReturns() {
        listOf(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_FOCUSED).forEach { type ->
            openSelf(); val observation = observe(); val action = approve(observation)
            service.onAccessibilityEvent(AccessibilityEvent.obtain(type).apply { packageName = service.packageName })
            assertTrue(GuiRuntime.action(session.lease, action) is GuiResult.Rejected)
        }
        assertNoActions()
    }

    @Test fun selfStreamingContentDoesNotInvalidateAnUnchangedOriginalNode() {
        openSelf()
        val observation = observe(); val action = approve(observation)
        platform.rootLabel = "streaming reply grew"
        GuiRuntime.changed(service, contentOnly = true)
        assertTrue(GuiRuntime.action(session.lease, action) is GuiResult.Value)
        assertEquals(1, platform.nodeActions)
        assertTrue(GuiRuntime.action(session.lease, action) is GuiResult.Rejected)
        assertEquals(1, platform.nodeActions)
    }

    @Test fun changedSelfWindowCannotReviveTheOriginalObservationWhenItReturns() {
        openSelf()
        val observation = observe(); val action = approve(observation)
        GuiRuntime.changed(service, contentOnly = true)
        platform.windowId++
        rejected(GuiRuntime.action(session.lease, action), "TARGET_WINDOW_CHANGED_RECONFIRM_NOT_EXECUTED")
        platform.windowId--
        rejected(GuiRuntime.action(session.lease, action), "OBSERVATION_REQUIRED")
        assertNoActions()
    }

    @Test fun anUnreportedSelfWindowChangeCannotReviveTheOldActionGrant() {
        openSelf()
        val observation = observe(); val action = approve(observation)
        platform.windowId++
        rejected(GuiRuntime.action(session.lease, action), "TARGET_WINDOW_CHANGED_RECONFIRM_NOT_EXECUTED")
        platform.windowId--
        rejected(GuiRuntime.action(session.lease, action), "OBSERVATION_REQUIRED")
        assertNoActions()
    }

    @Test fun anUnreportedWindowChangeCannotReviveTheOldScreenshotObservation() {
        val observation = observe()
        platform.windowId++
        rejected(screenshot(observation).completed(), "SCREENSHOT_WINDOW_CHANGED")
        platform.windowId--
        rejected(screenshot(observation).completed(), "OBSERVATION_REQUIRED")
        assertTrue(platform.callbacks.isEmpty())
    }

    @Test fun selfContentChangeStillRejectsAChangedTargetAndAnyScreenshot() {
        openSelf()
        val observation = observe(); val action = approve(observation)
        GuiRuntime.changed(service, contentOnly = true)
        rejected(screenshot(observation).completed(), "OBSERVATION_STALE")
        platform.label = "different target"
        rejected(GuiRuntime.action(session.lease, action), "NODE_STALE")
        platform.label = "before"
        rejected(GuiRuntime.action(session.lease, action), "OBSERVATION_REQUIRED")
        assertTrue(platform.callbacks.isEmpty()); assertNoActions()
    }

    @Test fun externalContentAndSelfWindowChangesStillInvalidateTheObservation() {
        val external = observe(); val externalAction = approve(external)
        GuiRuntime.changed(service, contentOnly = true)
        rejected(GuiRuntime.action(session.lease, externalAction), "OBSERVATION_STALE")
        openSelf()
        val self = observe(); val selfAction = approve(self)
        GuiRuntime.changed(service)
        rejected(GuiRuntime.action(session.lease, selfAction), "OBSERVATION_STALE")
        assertNoActions()
    }

    @Test fun aTruncatedSelfReplyCanOnlyAuthorizeAnUnchangedUniqueOriginalNode() {
        openSelf()
        platform.rootLabel = "x".repeat(513)
        val observation = observe(); assertTrue(observation.truncated)
        val action = approve(observation)
        GuiRuntime.changed(service, contentOnly = true)
        assertTrue(GuiRuntime.action(session.lease, action) is GuiResult.Value)
        assertEquals(1, platform.nodeActions)
    }

    @Test fun defaultExternalGlobalRouteRemainsOrdinaryAndDoesNotAcquireASelfException() {
        val observation = observe()
        val action = GuiAction(observation.observationId, GuiOperation.BACK)
        assertTrue(session.approveExactActionFromUser(action) is GuiResult.Value)
        assertTrue(GuiRuntime.action(session.lease, action) is GuiResult.Value)
        assertEquals(listOf(AccessibilityService.GLOBAL_ACTION_BACK), shadowOf(service).globalActionsPerformed)
        assertEquals(0, platform.nodeActions)
    }

    @Test fun closeAndNewOwnerDoNotInheritSelfParametersAndOldLeaseCannotAffectTheNewOne() {
        openSelf()
        val old = session; val observation = observe(); val action = approve(observation)
        openTarget(null)
        val replacement = session
        rejected(replacement.previewFromUser(), "SELF_APP_TARGET_REQUIRED_NOT_EXECUTED")
        rejected(GuiRuntime.action(old.lease, action), "FOREGROUND_BUSY")
        old.close() // must not clear a replacement lease's parameters
        platform.pkg = "external.app"
        assertEquals("external.app", observe().packageName)
        assertNoActions()
    }

    @Test fun disconnectAndReconnectDoNotInheritSelfParametersOrReviveOldLease() {
        openSelf()
        val old = session; val observation = observe(); val action = approve(observation)
        GuiRuntime.disconnected(service); GuiRuntime.connected(service)
        rejected(GuiRuntime.action(old.lease, action), "GUI_AUTHORIZATION_REVOKED")
        openTarget(null)
        rejected(session.previewFromUser(), "SELF_APP_TARGET_REQUIRED_NOT_EXECUTED")
        platform.pkg = "external.app"
        assertEquals("external.app", observe().packageName)
        assertNoActions()
    }

    @Test fun selfUnknownStillFencesOriginalSessionAndCannotOpenAReplacementLease() {
        openSelf()
        val observation = observe(); val action = approve(observation)
        val reads = platform.rootReads
        session.onCentralUnknown()
        rejected(session.observe { false }, "GUI_PREVIOUS_OPERATION_UNCONFIRMED")
        rejected(session.action(action) { false }, "GUI_PREVIOUS_OPERATION_UNCONFIRMED")
        rejected(session.screenshot(observation.observationId) { false }, "GUI_PREVIOUS_OPERATION_UNCONFIRMED")
        rejected(GuiRuntime.openForUser(GuiOwner("replacement", "run")), "FOREGROUND_BUSY")
        assertEquals(reads, platform.rootReads); assertTrue(platform.callbacks.isEmpty()); assertNoActions()
    }

    @Test fun selfCloseWhileEncodingIsQueuedRetainsDrainAndDoesNotLeakNarrowParameters() {
        openSelf()
        val observation = observe(); val future = screenshot(observation); val frame = platform.success()
        session.close(); assertFalse(session.releaseConfirmed)
        rejected(GuiRuntime.openForUser(GuiOwner("replacement", "run")), "FOREGROUND_DRAIN_PENDING_NOT_EXECUTED")
        worker.runAll()
        rejected(future.completed(), "GUI_AUTHORIZATION_REVOKED"); assertDisposed(frame)
        assertTrue(session.releaseConfirmed)
        openTarget(null)
        rejected(session.previewFromUser(), "SELF_APP_TARGET_REQUIRED_NOT_EXECUTED")
        assertNoActions()
    }

    @Test fun windowChangesWithoutAnEventRefuseBeforePlatformSubmission() {
        val observation = observe(); assertEquals(platform.windowId, observation.windowId); platform.windowId++
        rejected(screenshot(observation).completed(), "SCREENSHOT_WINDOW_CHANGED")
        assertTrue(platform.callbacks.isEmpty())
        assertEquals(1, session.lease.screenshots)
    }

    @Test fun nodeChangesWithoutAnEventRefuseBeforePlatformSubmission() {
        val observation = observe(); platform.label = "after"
        rejected(screenshot(observation).completed(), "SCREENSHOT_TREE_CHANGED")
        assertTrue(platform.callbacks.isEmpty())
    }

    @Test fun packageChangesWithoutAnEventRefuseBeforePlatformSubmission() {
        val observation = observe(); platform.pkg = "another.app"
        rejected(screenshot(observation).completed(), "SCREENSHOT_WINDOW_CHANGED")
        assertTrue(platform.callbacks.isEmpty())
    }

    @Test fun movedDisplayOrUnavailableTreeCannotBeVerified() {
        val observation = observe(); platform.displayId = 1
        rejected(screenshot(observation).completed(), "SCREENSHOT_TREE_UNVERIFIABLE")
        assertNull(session.lease.observation)
        platform.displayId = 0; platform.missing = true
        rejected(screenshot(observation).completed(), "OBSERVATION_REQUIRED")
        assertTrue(platform.callbacks.isEmpty())
    }

    @Test fun truncatedObservationCannotAuthorizeAPartialTreeMatch() {
        platform.label = "x".repeat(513)
        val observation = observe()
        assertTrue(observation.truncated)
        rejected(screenshot(observation).completed(), "SCREENSHOT_TREE_UNVERIFIABLE")
        assertTrue(platform.callbacks.isEmpty())
    }

    @Test fun failedReplacementRetiresOldIdAndExactActions() {
        val observation = observe(); approve(observation)
        platform.missing = true
        rejected(session.previewFromUser(), "NO_ACTIVE_EXTERNAL_WINDOW")
        assertNull(session.lease.observation)
        assertTrue(session.lease.actions.isEmpty())
        rejected(screenshot(observation).completed(), "OBSERVATION_REQUIRED")
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
        val image = (future.completed() as GuiResult.Value).value
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
        rejected(future.completed(), "SCREENSHOT_TREE_CHANGED")
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
        rejected(future.completed(), "SCREENSHOT_TREE_CHANGED")
        assertDisposed(frame)
    }

    @Test fun displayChangeBeforeDeliveryCannotBeVerified() {
        val observation = observe(); val future = screenshot(observation)
        val frame = platform.success(); platform.displayId = 1
        worker.runAll()
        rejected(future.completed(), "SCREENSHOT_TREE_UNVERIFIABLE")
        assertDisposed(frame)
    }

    @Test fun windowChangeBeforeLateCallbackIsRejectedAtDelivery() {
        val observation = observe(); val future = screenshot(observation)
        platform.windowId++
        val frame = platform.success(); worker.runAll()
        rejected(future.completed(), "SCREENSHOT_WINDOW_CHANGED")
        assertDisposed(frame)
    }

    @Test fun closeBeforeLateCallbackKeepsDrainUntilAllResourcesAreDisposed() {
        val observation = observe(); val future = screenshot(observation)
        session.close(); assertFalse(session.releaseConfirmed)
        rejected(GuiRuntime.openForUser(GuiOwner("next", "run")), "FOREGROUND_DRAIN_PENDING_NOT_EXECUTED")
        val frame = platform.success()
        rejected(future.completed(), "GUI_AUTHORIZATION_REVOKED")
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
        rejected(future.completed(), "GUI_AUTHORIZATION_REVOKED")
        assertDisposed(frame); assertTrue(session.releaseConfirmed)
    }

    @Test fun encoderRejectionDisposesAndDoesNotHoldTheSlot() {
        val observation = observe(); val future = screenshot(observation)
        GuiRuntime.encoder = Executor { throw RejectedExecutionException() }
        val frame = platform.success()
        rejected(future.completed(), "SCREENSHOT_ENCODING_UNAVAILABLE")
        assertDisposed(frame); session.close(); assertTrue(session.releaseConfirmed)
    }

    @Test fun encodingExceptionDisposesAndDoesNotHoldTheSlot() {
        val observation = observe(); val future = screenshot(observation)
        val frame = platform.success(Frame().apply { encodeFailure = true })
        worker.runAll()
        rejected(future.completed(), "SCREENSHOT_UNAVAILABLE")
        assertDisposed(frame); session.close(); assertTrue(session.releaseConfirmed)
    }

    @Test fun submissionUnknownRetainsReservationUntilLateSuccessDisposal() {
        val observation = observe(); platform.throwOnSubmit = true
        val future = screenshot(observation)
        rejected(future.completed(), "SCREENSHOT_DELIVERY_UNCONFIRMED")
        session.close(); assertFalse(session.releaseConfirmed)
        val frame = platform.success()
        assertEquals(0, frame.encoded); assertDisposed(frame)
        assertTrue(session.releaseConfirmed)
        rejected(future.completed(), "SCREENSHOT_DELIVERY_UNCONFIRMED")
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
