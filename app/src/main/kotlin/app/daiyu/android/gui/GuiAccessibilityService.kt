package app.daiyu.android.gui

import app.daiyu.android.diagnostics.RuntimeDiagnostics
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Path
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import androidx.annotation.WorkerThread
import app.daiyu.android.tasksurface.TaskSurfaceSelfTarget

/** System-bound only. Keepalive runs separately in system_server; it grants no GUI task access. */
class GuiAccessibilityService : AccessibilityService() {
    private var watchingPermission = false
    private var watchingScreen = false
    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) GuiRuntime.screenOff(this@GuiAccessibilityService)
        }
    }
    private val permissionObserver = object : android.database.ContentObserver(GuiRuntime.main) {
        override fun onChange(selfChange: Boolean) {
            val enabled = try { GuiSystemSettings.enabledBySystem(this@GuiAccessibilityService) } catch (_: Exception) { false }
            if (!enabled) GuiRuntime.disconnected(this@GuiAccessibilityService)
        }
    }
    override fun onServiceConnected() {
        super.onServiceConnected()
        if (!watchingPermission) {
            try {
                contentResolver.registerContentObserver(android.provider.Settings.Secure.getUriFor(
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, permissionObserver)
                contentResolver.registerContentObserver(android.provider.Settings.Secure.getUriFor(
                    android.provider.Settings.Secure.ACCESSIBILITY_ENABLED), false, permissionObserver)
                watchingPermission = true
            } catch (_: Exception) {
                contentResolver.unregisterContentObserver(permissionObserver)
                GuiRuntime.disconnected(this)
                return // unable to watch revocation: never advertise a connected host
            }
        }
        if (!watchingScreen) {
            try {
                androidx.core.content.ContextCompat.registerReceiver(this, screenReceiver,
                    android.content.IntentFilter(Intent.ACTION_SCREEN_OFF), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
                watchingScreen = true
            } catch (_: Exception) {
                GuiRuntime.disconnected(this)
                return // unable to observe lock/off revocation: never advertise a usable host
            }
        }
        serviceInfo = serviceInfo.apply {
            eventTypes = eventTypes or AccessibilityEvent.TYPE_VIEW_CLICKED or AccessibilityEvent.TYPE_VIEW_LONG_CLICKED or
                AccessibilityEvent.TYPE_VIEW_FOCUSED or AccessibilityEvent.TYPE_VIEW_SELECTED
        }
        GuiRuntime.connected(this)
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null && (try { GuiRuntime.isOverlayInteraction(event) } catch (_: Exception) { false })) return
        if (event != null && event.eventType and (AccessibilityEvent.TYPE_VIEW_CLICKED or AccessibilityEvent.TYPE_VIEW_LONG_CLICKED or
                AccessibilityEvent.TYPE_VIEW_FOCUSED or AccessibilityEvent.TYPE_VIEW_SELECTED or AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED or AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) != 0) GuiRuntime.changed(this, event)
        // Do not retain event text or continuously collect a UI tree.
    }
    override fun onInterrupt() { GuiRuntime.disconnected(this) }
    override fun onUnbind(intent: Intent?): Boolean { GuiRuntime.disconnected(this); return super.onUnbind(intent) }
    override fun onDestroy() {
        if (watchingScreen) { unregisterReceiver(screenReceiver); watchingScreen = false }
        if (watchingPermission) { contentResolver.unregisterContentObserver(permissionObserver); watchingPermission = false }
        GuiRuntime.disconnected(this); super.onDestroy()
    }
}

internal data class GuiScreenshot(val observationId: String, val width: Int, val height: Int, val png: ByteArray,
    val observationBound: Boolean = true, val visualPackage: String? = null,
    val visualPixels: GuiVisualPixels? = null)

/** Narrow platform seam: admission and bounded traversal remain in the runtime. */
internal interface GuiRuntimeAccess {
    fun enabled(current: GuiAccessibilityService): Boolean = GuiSystemSettings.enabledBySystem(current)
    fun unlocked(current: GuiAccessibilityService): Boolean =
        !current.getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
            current.getSystemService(PowerManager::class.java).isInteractive
    fun root(current: GuiAccessibilityService): AccessibilityNodeInfo? = current.rootInActiveWindow
    fun windows(current: GuiAccessibilityService): List<android.view.accessibility.AccessibilityWindowInfo> = current.windows.orEmpty()
    fun visualWindow(current: GuiAccessibilityService, completed: (GuiVisualWindow?) -> Unit) =
        AndroidGuiVisualWindows.read(current, completed)
    fun configuration(current: GuiAccessibilityService): android.accessibilityservice.AccessibilityServiceInfo? = current.serviceInfo
    @androidx.annotation.RequiresApi(33)
    fun clearWindowCache(current: GuiAccessibilityService): Boolean = current.clearCache()
    fun launchIntent(current: GuiAccessibilityService, packageName: String): Intent? =
        current.packageManager.getLaunchIntentForPackage(packageName)
    fun startActivity(current: GuiAccessibilityService, intent: Intent) { current.startActivity(intent) }
    fun gesture(current: GuiAccessibilityService, gesture: GuiGesture, completed: (Boolean) -> Unit): Boolean {
        val path = Path().apply {
            moveTo(gesture.x.toFloat(), gesture.y.toFloat())
            if (gesture.x != gesture.endX || gesture.y != gesture.endY) lineTo(gesture.endX.toFloat(), gesture.endY.toFloat())
        }
        val request = GestureDescription.Builder().addStroke(
            GestureDescription.StrokeDescription(path, 0, gesture.durationMillis)).build()
        return current.dispatchGesture(request, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) { completed(true) }
            override fun onCancelled(gestureDescription: GestureDescription) { completed(false) }
        }, GuiRuntime.main)
    }
    @androidx.annotation.RequiresApi(30)
    fun capture(current: GuiAccessibilityService, success: (GuiCaptureFrame) -> Unit, failure: (Int) -> Unit)
}

/** A frame owns its resources until disposal is confirmed, including time queued for encoding. */
internal interface GuiCaptureFrame {
    @WorkerThread fun encode(observationId: String): GuiResult<GuiScreenshot>
    @WorkerThread fun validation(observationId: String): GuiResult<GuiScreenshot> = encode(observationId)
    fun dispose(request: GuiCaptureRequest<GuiScreenshot>)
}

private object AndroidGuiRuntimeAccess : GuiRuntimeAccess {
    @androidx.annotation.RequiresApi(30)
    override fun capture(current: GuiAccessibilityService, success: (GuiCaptureFrame) -> Unit, failure: (Int) -> Unit) {
        current.takeScreenshot(Display.DEFAULT_DISPLAY, current.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) { success(AndroidGuiCaptureFrame(screenshot)) }
            override fun onFailure(errorCode: Int) { failure(errorCode) }
        })
    }
}

/** Encoding has no service, lease, or mutable runtime-state access. */
@androidx.annotation.RequiresApi(30)
private class AndroidGuiCaptureFrame(screenshot: AccessibilityService.ScreenshotResult) : GuiCaptureFrame {
    private val buffer = screenshot.hardwareBuffer
    private val colorSpace = screenshot.colorSpace
    private var hardware: Bitmap? = null
    private var software: Bitmap? = null
    @WorkerThread override fun encode(observationId: String): GuiResult<GuiScreenshot> = encode(observationId, true)
    @WorkerThread override fun validation(observationId: String): GuiResult<GuiScreenshot> = encode(observationId, false)
    @WorkerThread private fun encode(observationId: String, includePng: Boolean): GuiResult<GuiScreenshot> {
        if (buffer.width > 2048 || buffer.height > 4096 || buffer.width.toLong() * buffer.height > 8_388_608)
            return GuiResult.Rejected("SCREENSHOT_DIMENSIONS_EXCEED_LIMIT")
        hardware = Bitmap.wrapHardwareBuffer(buffer, colorSpace)
        software = hardware?.copy(Bitmap.Config.ARGB_8888, false)
        val image = software ?: return GuiResult.Rejected("SCREENSHOT_ENCODING_UNAVAILABLE")
        val pixels = RuntimeDiagnostics.measure(RuntimeDiagnostics.Stage.GUI_PIXEL_EXTRACT) { GuiVisualPixels.from(image) }
        if (!includePng) return GuiResult.Value(GuiScreenshot(observationId, image.width, image.height, ByteArray(0),
            visualPixels = pixels))
        val bytes = object : ByteArrayOutputStream() {
            override fun write(b: ByteArray, off: Int, len: Int) {
                check(count.toLong() + len <= 2_097_152) { "screenshot_size_limit" }; super.write(b, off, len)
            }
            override fun write(b: Int) { check(count < 2_097_152); super.write(b) }
        }
        if (!image.compress(Bitmap.CompressFormat.PNG, 100, bytes))
            return GuiResult.Rejected("SCREENSHOT_ENCODING_UNAVAILABLE")
        return GuiResult.Value(GuiScreenshot(observationId, image.width, image.height, bytes.toByteArray(),
            visualPixels = pixels))
    }
    override fun dispose(request: GuiCaptureRequest<GuiScreenshot>) {
        request.disposeSuccessResources(
            software = { software?.recycle(); Unit }, hardware = { hardware?.recycle(); Unit }, buffer = { buffer.close() })
    }
}

/** All tree access and lease transitions run on main; only bounded image encoding runs on worker. */
internal object GuiRuntime {
    internal var access: GuiRuntimeAccess = AndroidGuiRuntimeAccess
    // Other workers belong to controllers/audio/model lifecycles; none is a reusable GUI encoder.
    internal var encoder: Executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "daiyu-gui-png").apply { isDaemon = true }
    }
    val main = Handler(Looper.getMainLooper())
    private val gate = GuiLeaseGate(SystemClock::elapsedRealtime)
    @Volatile private var service: GuiAccessibilityService? = null
    private var generation = 0L
    private var visualGeneration = 0L
    private var windowReadGeneration = 0L
    private var confirmation: GuiConfirmationOverlay? = null
    // Narrow parameters belong to the original gate lease, never to the process or a later owner.
    private data class TargetBinding(val lease: GuiLeaseGate.Lease, val packageName: String?,
        val selfTarget: TaskSurfaceSelfTarget?, val surfaceOwner: Any?)
    private var targetBinding: TargetBinding? = null
    private fun selfCurrent(target: TaskSurfaceSelfTarget?, owner: Any?, ownPackage: String): Boolean =
        owner != null && try { target?.isCurrent(owner, ownPackage) == true } catch (_: Exception) { false }
    private fun targetRejection(lease: GuiLeaseGate.Lease): GuiResult.Rejected? {
        gate.rejection(lease)?.let { return it }
        if (gestures.containsKey(lease)) return GuiResult.Rejected("GESTURE_IN_PROGRESS_NOT_EXECUTED")
        val binding = targetBinding?.takeIf { it.lease === lease }
            ?: return GuiResult.Rejected("GUI_AUTHORIZATION_REVOKED")
        if (binding.selfTarget != null &&
            (binding.packageName != service!!.packageName ||
                !selfCurrent(binding.selfTarget, binding.surfaceOwner, service!!.packageName))) return selfLost(lease)
        return null
    }
    private fun selfLost(lease: GuiLeaseGate.Lease): GuiResult.Rejected {
        gate.retireObservation(lease); clearNodes()
        return GuiResult.Rejected("SELF_APP_TARGET_LOST")
    }
    private fun isSelfLease(lease: GuiLeaseGate.Lease): Boolean =
        targetBinding?.let { it.lease === lease && it.selfTarget != null } == true
    private fun operationRejection(lease: GuiLeaseGate.Lease, operation: GuiOperation): GuiResult.Rejected? {
        targetRejection(lease)?.let { return it }
        return if (isSelfLease(lease) && operation in setOf(GuiOperation.BACK, GuiOperation.HOME,
                GuiOperation.RECENTS, GuiOperation.NOTIFICATIONS))
            GuiResult.Rejected("SELF_APP_GLOBAL_ACTION_NOT_ALLOWED_NOT_EXECUTED") else null
    }
    // Check self-window loss before stale-generation errors, including changes without an event.
    @Suppress("DEPRECATION")
    private fun selfRootRejection(lease: GuiLeaseGate.Lease): GuiResult.Rejected? {
        targetRejection(lease)?.let { return it }
        if (!isSelfLease(lease)) return null
        return when (val fetched = externalRoot(lease, service!!)) {
            is GuiResult.Value -> { fetched.value.node.recycle(); null }
            is GuiResult.Rejected -> fetched
            is GuiResult.Unknown -> error("root admission cannot be unknown")
        }
    }
    internal fun showConfirmation(session: GuiRunSession, operation: GuiOperation, text: String?,
        ownerCurrent: () -> Boolean, onNotExecuted: (String) -> Unit, onRevoked: () -> Unit,
        deliver: (GuiConfirmedDelivery) -> Boolean, productDefaults: Boolean = false): GuiResult<Unit> {
        onMain(); operationRejection(session.lease, operation)?.let { return it }; screenRejection()?.let { return it }
        if (operation in setOf(GuiOperation.COORDINATE_TAP, GuiOperation.SWIPE))
            return GuiResult.Rejected("GUI_GESTURE_REQUIRES_OBSERVED_COORDINATES")
        selfRootRejection(session.lease)?.let { return it }
        if (confirmation != null) return GuiResult.Rejected("CONFIRMATION_ALREADY_OPEN")
        val host = GuiConfirmationOverlay(service!!, session, operation, text, ownerCurrent, onNotExecuted, onRevoked, deliver, productDefaults)
        confirmation = host
        return try { host.show(); GuiResult.Value(Unit) } catch (_: Exception) {
            session.close(); GuiResult.Rejected("OVERLAY_UNAVAILABLE_NOT_EXECUTED")
        }
    }
    internal fun isOverlayInteraction(event: AccessibilityEvent): Boolean {
        onMain(); return confirmation?.isLocalInteraction(event) == true
    }
    internal fun dismissConfirmation(lease: GuiLeaseGate.Lease): Boolean {
        onMain()
        val host = confirmation?.takeIf { it.session.lease === lease } ?: return true
        if (!host.dispose()) return false
        confirmation = null
        return true
    }
    private val retained = linkedMapOf<Int, AccessibilityNodeInfo>()
    private val identities = linkedMapOf<Int, List<String>?>()
    private data class LocalNode(val node: GuiNode, val context: NodeContext)
    private data class LocalTarget(val subtree: List<LocalNode>, val ancestors: List<Int>)
    private val selfTargets = mutableMapOf<Int, LocalTarget>()
    private fun identity(node: AccessibilityNodeInfo): List<String>? {
        val fields = listOf(node.text, node.contentDescription, node.className, node.viewIdResourceName)
        if (fields.any { (it?.length ?: 0) > 512 }) return null
        return fields.map { it?.toString().orEmpty() }
    }
    private fun onMain() { check(Looper.myLooper() === Looper.getMainLooper()) { "gui_main_thread_required" } }
    fun isConnected(): Boolean { onMain(); return service != null }
    internal fun hasServiceConnection(): Boolean = service != null
    fun connected(value: GuiAccessibilityService) {
        onMain(); confirmation?.revoke()
        targetBinding = null; clearNodes(); service = value; generation++; gate.connect()
    }
    fun disconnected(value: GuiAccessibilityService) {
        onMain()
        if (service === value) {
            confirmation?.revoke()
            targetBinding = null; clearNodes(); service = null; generation++; gate.disconnect()
        }
    }
    fun screenOff(value: GuiAccessibilityService) {
        onMain()
        if (service !== value) return
        targetBinding?.lease?.valid = false
        generation++; clearNodes()
        confirmation?.revoke("SCREEN_LOCKED_NOT_EXECUTED")
    }
    private data class StaleEvent(val observationId: String, val type: Int, val window: Int,
        val windowChanges: Int, val origin: String)
    private var staleEvent: StaleEvent? = null
    private var staleLoggedFor: String? = null

    internal fun changed(value: GuiAccessibilityService, event: AccessibilityEvent) {
        onMain()
        if (service !== value) return
        val lease = targetBinding?.lease
        val observed = lease?.observation
        val pkg = event.packageName?.toString()
        val content = event.eventType in setOf(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED, AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED)
        if (!content) windowReadGeneration++
        // Our reply content is not the external rootless target. Keep both pending reads and
        // published captures; WM witnesses, occlusion and pixel revalidation still fence actions.
        if (content && pkg == value.packageName && lease != null && observed == null && !isSelfLease(lease) &&
            ((captures[lease] ?: 0) > 0 || visualCapture?.lease === lease)) return
        val sameWindow = observed != null && event.windowId == observed.windowId
        val otherWindow = observed != null && event.windowId >= 0 && event.windowId != observed.windowId
        // Scope content evidence to its framework window, not every notification/system UI update.
        // The self lease still validates the active root and original target twice before delivery.
        val localContent = content && (pkg == value.packageName || pkg == null && sameWindow || otherWindow)
        val previousGeneration = generation
        val keepExternalNodes = event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            observed != null && observed.windowId >= 0 && otherWindow && lease != null &&
            !isSelfLease(lease) && retained.isNotEmpty()
        if (keepExternalNodes) {
            // Other-window content cannot prove this external target changed. Retain exact nodes
            // for live revalidation, while screenshots and actions without a node stay dirty.
            contentChanged = true
        } else {
            // Content retires node handles, not visual evidence. Visual actions re-read WM and pixels.
            changed(value, contentOnly = localContent,
                invalidateVisual = !content || lease == null || isSelfLease(lease))
        }
        if (observed != null && observed.generation == previousGeneration &&
            (generation != previousGeneration || staleEvent == null)) {
            staleEvent = StaleEvent(observed.observationId, event.eventType, event.windowId,
                if (Build.VERSION.SDK_INT >= 28) event.windowChanges else 0,
                if (pkg == value.packageName) "self" else if (pkg == null) "unspecified" else "other")
        }
    }

    private fun validateObservation(lease: GuiLeaseGate.Lease, id: String, stage: String): GuiResult.Rejected? {
        val rejection = gate.validateObservation(lease, id, generation) ?: return null
        return recordObservationRejection(lease, id, stage, rejection)
    }

    private fun recordObservationRejection(lease: GuiLeaseGate.Lease, id: String, stage: String,
        rejection: GuiResult.Rejected): GuiResult.Rejected {
        if (rejection.code == "OBSERVATION_STALE" && staleLoggedFor != id) {
            staleLoggedFor = id
            val observed = lease.observation
            val cause = staleEvent?.takeIf { it.observationId == id }
            // One bounded metadata line per rejected observation; no event text, package, observation IDs or UI tree.
            runCatching { android.util.Log.i("DaiyuGui", "observation_stale stage=$stage" +
                " event_type=${cause?.type ?: 0} event_window=${cause?.window ?: -1}" +
                " window_changes=${cause?.windowChanges ?: 0} origin=${cause?.origin ?: "runtime"}" +
                " observed_window=${observed?.windowId ?: -1}" +
                " generation=${observed?.generation ?: -1}:$generation") }
        }
        return rejection
    }

    fun changed(value: GuiAccessibilityService, contentOnly: Boolean = false) {
        onMain()
        if (service !== value) return
        if (!contentOnly) windowReadGeneration++
        changed(value, contentOnly, invalidateVisual = true)
    }

    private fun changed(value: GuiAccessibilityService, contentOnly: Boolean, invalidateVisual: Boolean) {
        onMain()
        if (service !== value) return
        val binding = targetBinding
        if (contentOnly && binding != null && isSelfLease(binding.lease) && retained.isNotEmpty()) {
            // Streaming the assistant's own reply must not replace the selected node identity.
            // Screenshots/global actions still reject this dirty tree; node actions refresh the original handle.
            contentChanged = true
        } else { generation++; clearNodes(invalidateVisual) }
    }
    private data class NodeContext(val packageName: String, val windowId: Int, val children: Int, val visible: Boolean)
    private data class TreeSample(val packageName: String, val windowId: Int, val windowType: Int, val nodes: List<GuiNode>,
        val contexts: List<NodeContext>, val truncated: Boolean, val maxNodes: Int) {
        // Redacted passwords and incomplete traversals cannot prove tree equality.
        val verifiable: Boolean get() = !truncated && nodes.none { it.password }
    }
    private var screenshotBinding: Pair<String, TreeSample>? = null
    private data class VisualCapture(val lease: GuiLeaseGate.Lease, val id: String, val visualGeneration: Long,
        val capturedAt: Long, val window: GuiVisualWindow, val digest: List<Byte>, val pixels: GuiVisualPixels?)
    private var visualCapture: VisualCapture? = null
    private var contentChanged = false
    @Suppress("DEPRECATION") private fun clearNodes(invalidateVisual: Boolean = true) {
        if (invalidateVisual) { visualGeneration++; visualCapture = null }
        screenshotBinding = null
        contentChanged = false
        retained.values.forEach { it.recycle() }; retained.clear(); identities.clear(); selfTargets.clear()
    }
    /** Capture only complete target subtrees from the original observation, never from a later tree. */
    private fun retainSelfTargets(tree: TreeSample) {
        val children = tree.nodes.groupBy { it.parent }
        for (target in tree.nodes.filter { it.clickable || it.longClickable || it.scrollable || it.editable }) {
            val subtree = mutableListOf<LocalNode>()
            var chars = 0
            fun collect(node: GuiNode, parent: Int?, depth: Int): Boolean {
                if (depth > 4 || subtree.size >= 32 || node.password) return false
                val context = tree.contexts[node.id]
                val fields = identities[node.id] ?: return false
                if (fields != listOf(node.text, node.description, node.className, node.viewId)) return false
                chars += fields.sumOf { it.length }
                if (chars > 4096 || context.packageName != tree.packageName || context.windowId != tree.windowId) return false
                val descendants = children[node.id].orEmpty()
                if (descendants.size != context.children) return false
                val id = subtree.size
                subtree += LocalNode(node.copy(id = id, parent = parent), context)
                return descendants.all { collect(it, id, depth + 1) }
            }
            if (!collect(target, null, 0)) continue
            val ancestors = mutableListOf<Int>()
            var parent = target.parent
            while (parent != null && ancestors.size < guiTreeDepth(tree.maxNodes)) {
                ancestors += parent
                parent = tree.nodes[parent].parent
            }
            val chain = listOf(target.id) + ancestors
            if (parent != null || chain.last() != 0 || chain.indices.any { i ->
                chain.take(i).any { retained[it] == retained[chain[i]] }
            }) continue
            selfTargets[target.id] = LocalTarget(subtree, ancestors)
        }
    }

    /** An exact framework handle anchored to the original ancestors replaces whole-chat scanning for self only. */
    @Suppress("DEPRECATION")
    private fun validateSelfTarget(lease: GuiLeaseGate.Lease, id: Int, root: AccessibilityNodeInfo): GuiResult<Unit> {
        fun reject() = rejectChangedTarget(lease, "NODE_STALE")
        val expected = selfTargets[id] ?: return reject()
        val target = retained[id] ?: return reject()
        try {
            if (!target.refresh()) return reject()
            val actual = mutableListOf<LocalNode>()
            var chars = 0
            fun collect(node: AccessibilityNodeInfo, parent: Int?, depth: Int): Boolean {
                if (depth > 4 || actual.size >= 32 || node.isPassword) return false
                val fields = identity(node) ?: return false
                chars += fields.sumOf { it.length }
                if (chars > 4096 || node.childCount > 32) return false
                val pkg = node.packageName?.toString().orEmpty()
                if (pkg.length > 256) return false
                val bounds = Rect(); node.getBoundsInScreen(bounds)
                val index = actual.size
                actual += LocalNode(GuiNode(index, parent, GuiBounds(bounds.left, bounds.top, bounds.right, bounds.bottom),
                    fields[0], fields[1], fields[2], fields[3], node.isClickable, node.isLongClickable, node.isScrollable,
                    node.isEditable, node.isEnabled, node.isPassword), NodeContext(pkg, node.windowId, node.childCount, node.isVisibleToUser))
                for (childId in 0 until node.childCount) {
                    val child = node.getChild(childId) ?: return false
                    try { if (!collect(child, index, depth + 1)) return false } finally { child.recycle() }
                }
                return true
            }
            if (!collect(target, null, 0) || actual != expected.subtree || !target.isEnabled || !target.isVisibleToUser) return reject()
            var link = target
            try {
                for (ancestor in expected.ancestors) {
                    val parent = link.parent ?: return reject()
                    if (link !== target) link.recycle()
                    link = parent
                    if (link != retained[ancestor] || link.windowId != root.windowId || link.packageName != root.packageName) return reject()
                }
                if (link != root) return reject()
            } finally { if (link !== target) link.recycle() }
            return GuiResult.Value(Unit)
        } catch (_: Exception) { return reject() }
    }

    private fun screenRejection(): GuiResult.Rejected? {
        val current = service ?: return GuiResult.Rejected("SERVICE_DISCONNECTED_NOT_EXECUTED")
        val enabled = try { access.enabled(current) } catch (_: Exception) { false }
        if (!enabled) {
            disconnected(current)
            return GuiResult.Rejected("SERVICE_PERMISSION_REVOKED_NOT_EXECUTED")
        }
        if (!(try { access.unlocked(current) } catch (_: Exception) { false })) {
            // Unlocking later must not revive this run's snapshot or its exact grants.
            targetBinding?.lease?.let { it.valid = false }
            clearNodes()
            return GuiResult.Rejected("SCREEN_LOCKED_NOT_EXECUTED")
        }
        return null
    }
    /** Main-thread, trusted user callback only. Null target means external-only, not inferred self.
     * An own-package target additionally needs the current task-surface token and its exact owner.
     * This only narrows the original lease; all ordinary approvals/admission/drain rules still apply.
     * No launch, settings change, virtual display, prompt or fallback is performed here. */
    fun openForUser(owner: GuiOwner, targetPackage: String? = null,
        selfTarget: TaskSurfaceSelfTarget? = null, surfaceOwner: Any? = null): GuiResult<GuiRunSession> {
        onMain(); screenRejection()?.let { return it }
        if (targetPackage != null && !GuiUserActionRequest.validTargetPackage(targetPackage))
            return GuiResult.Rejected("INVALID_TARGET_PACKAGE_NOT_EXECUTED")
        val own = targetPackage == service!!.packageName
        if (own && !selfCurrent(selfTarget, surfaceOwner, service!!.packageName))
            return GuiResult.Rejected("SELF_APP_TARGET_LOST")
        if (captures.isNotEmpty() || gestures.isNotEmpty() || closingLeases.isNotEmpty()) return GuiResult.Rejected("FOREGROUND_DRAIN_PENDING_NOT_EXECUTED")
        return when (val acquired = gate.acquire(owner)) {
            is GuiResult.Value -> {
                targetBinding = TargetBinding(acquired.value, targetPackage,
                    if (own) selfTarget else null, if (own) surfaceOwner else null)
                GuiResult.Value(GuiRunSession(acquired.value))
            }
            is GuiResult.Rejected -> acquired
            is GuiResult.Unknown -> acquired
        }
    }
    internal fun launchApp(lease: GuiLeaseGate.Lease, request: GuiAppLaunch,
        cancelled: () -> Boolean = { false }): GuiResult<String> {
        onMain()
        if (cancelled()) return GuiResult.Rejected("CANCELLED_NOT_EXECUTED")
        targetRejection(lease)?.let { return it }; screenRejection()?.let { return it }
        if (targetBinding?.packageName != request.packageName)
            return GuiResult.Rejected("APPLICATION_TARGET_MISMATCH_NOT_EXECUTED")
        val current = service!!
        val intent = try { access.launchIntent(current, request.packageName) }
        catch (_: Exception) { return GuiResult.Rejected("APPLICATION_RESOLUTION_FAILED_NOT_EXECUTED") }
            ?: return GuiResult.Rejected("APPLICATION_NOT_LAUNCHABLE_NOT_EXECUTED")
        if (intent.component?.packageName != request.packageName)
            return GuiResult.Rejected("APPLICATION_TARGET_MISMATCH_NOT_EXECUTED")
        if (cancelled()) return GuiResult.Rejected("CANCELLED_NOT_EXECUTED")
        targetRejection(lease)?.let { return it }; screenRejection()?.let { return it }
        return try {
            access.startActivity(current, Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            GuiResult.Value("PLATFORM_LAUNCH_ACCEPTED_NOT_VERIFIED")
        } catch (_: android.content.ActivityNotFoundException) {
            GuiResult.Rejected("APPLICATION_NOT_LAUNCHABLE_NOT_EXECUTED")
        } catch (_: SecurityException) {
            GuiResult.Rejected("APPLICATION_LAUNCH_PERMISSION_DENIED_NOT_EXECUTED")
        } catch (_: Exception) {
            GuiResult.Unknown("APPLICATION_LAUNCH_UNCONFIRMED")
        } finally { gate.retireObservation(lease); generation++; clearNodes() }
    }
    internal fun approveReads(lease: GuiLeaseGate.Lease, approval: GuiReadApproval): GuiResult<Unit> {
        onMain(); targetRejection(lease)?.let { return it }; return gate.approveReads(lease, approval)
    }
    internal fun approveAction(lease: GuiLeaseGate.Lease, action: GuiAction): GuiResult<Unit> {
        onMain(); operationRejection(lease, action.operation)?.let { return it }
        selfRootRejection(lease)?.let { return it }
        return gate.approveAction(lease, action, generation).also {
            if (it is GuiResult.Rejected) recordObservationRejection(lease, action.observationId, "approve_action", it)
        }
    }
    internal fun approvedActions(lease: GuiLeaseGate.Lease): Set<GuiAction> {
        onMain(); if (targetRejection(lease) != null) return emptySet()
        return gate.approvedActions(lease)
    }
    private val captures = mutableMapOf<GuiLeaseGate.Lease, Int>()
    private val gestures = mutableMapOf<GuiLeaseGate.Lease, CompletableFuture<GuiResult<String>>>()
    private val draining = mutableMapOf<GuiLeaseGate.Lease, () -> Unit>()
    private val closingLeases = mutableSetOf<GuiLeaseGate.Lease>()
    internal fun close(lease: GuiLeaseGate.Lease, onReleased: () -> Unit) {
        onMain(); closingLeases.add(lease)
        if (targetBinding?.lease === lease) targetBinding = null
        lease.valid = false // fence even if overlay/node disposal fails; never manufacture a release
        if (!dismissConfirmation(lease)) return
        if (gate.ownsSlot(lease)) clearNodes()
        // A pending platform capture still owns the slot until terminal callback/resource disposal.
        draining[lease] = onReleased
        finishDrain(lease)
    }
    private fun finishDrain(lease: GuiLeaseGate.Lease) {
        if ((captures[lease] ?: 0) != 0 || gestures.containsKey(lease)) return
        val callback = draining.remove(lease) ?: return
        closingLeases.remove(lease)
        gate.close(lease)
        callback()
    }
    private fun captureFinished(lease: GuiLeaseGate.Lease) {
        val remaining = (captures[lease] ?: 1) - 1
        if (remaining == 0) captures.remove(lease) else captures[lease] = remaining
        finishDrain(lease)
    }

    private data class AdmittedRoot(val node: AccessibilityNodeInfo, val windowType: Int, val windowBounds: GuiBounds)

    /** Current default-display application or system dialog. System windows authorize exact
     * nodes only; self still requires its original application window and live lease token. */
    @Suppress("DEPRECATION")
    private fun externalRoot(lease: GuiLeaseGate.Lease, current: GuiAccessibilityService): GuiResult<AdmittedRoot> {
        targetRejection(lease)?.let { return it }
        val binding = targetBinding!!
        val self = isSelfLease(lease)
        var cacheRefresh = "not_attempted"
        var initialWindows = "not_read"
        var finalWindows = "not_read"
        fun windowSummary(windows: List<android.view.accessibility.AccessibilityWindowInfo>): String = try {
            if (windows.size > 64) "over_limit" else {
                val local = windows.filter { Build.VERSION.SDK_INT < 30 || it.displayId == Display.DEFAULT_DISPLAY }
                fun counts(type: Int): String {
                    val matching = local.filter { it.type == type }
                    return "${matching.size}/${matching.count { it.isActive }}/${matching.count { it.isFocused }}"
                }
                "app=${counts(android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION)}," +
                    "system=${counts(android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM)}," +
                    "other=${local.count { it.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION && it.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM }}," +
                    "off_display=${windows.size - local.size}"
            }
        } catch (_: Exception) { "unavailable" }
        fun configurationSummary(): String = try {
            val info = access.configuration(current)
            if (info == null) "unavailable" else
                "flags=${info.flags},capabilities=${info.capabilities},events=${info.eventTypes}," +
                    "package_filters=${info.packageNames?.size ?: 0}"
        } catch (_: Exception) { "unavailable" }
        fun reject(code: String, detail: String? = null): GuiResult.Rejected {
            runCatching {
                android.util.Log.i("DaiyuGui", "window_admission_failed code=$code cache_refresh=$cacheRefresh ${detail ?: "reason=window_admission_rejected"}" +
                    " initial_windows=[$initialWindows] final_windows=[$finalWindows] config=[${configurationSummary()}]")
            }
            if (self) return selfLost(lease)
            gate.retireObservation(lease)
            clearNodes()
            return GuiResult.Rejected(code)
        }
        fun onDefaultDisplay(window: android.view.accessibility.AccessibilityWindowInfo): Boolean =
            Build.VERSION.SDK_INT < 30 || window.displayId == Display.DEFAULT_DISPLAY
        fun admit(node: AccessibilityNodeInfo, window: android.view.accessibility.AccessibilityWindowInfo): GuiResult<AdmittedRoot> {
            val failure = try {
                val pkg = node.packageName?.toString().orEmpty()
                when {
                    !onDefaultDisplay(window) -> "APPLICATION_WINDOW_DISPLAY_MISMATCH_NOT_EXECUTED"
                    window.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION &&
                        (self || window.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM) -> "APPLICATION_WINDOW_REQUIRED_NOT_EXECUTED"
                    !window.isActive && !window.isFocused -> "APPLICATION_WINDOW_NOT_ACTIVE_NOT_EXECUTED"
                    node.windowId != window.id -> "APPLICATION_WINDOW_ID_MISMATCH_NOT_EXECUTED"
                    pkg.isBlank() || pkg.length > 256 -> "APPLICATION_PACKAGE_UNAVAILABLE_NOT_EXECUTED"
                    pkg == current.packageName && !self -> "SELF_APP_TARGET_REQUIRED_NOT_EXECUTED"
                    binding.packageName != null && pkg != binding.packageName -> "TARGET_PACKAGE_MISMATCH_NOT_EXECUTED"
                    else -> {
                        val bounds = Rect().also { window.getBoundsInScreen(it) }
                        return GuiResult.Value(AdmittedRoot(node, window.type,
                            GuiBounds(bounds.left, bounds.top, bounds.right, bounds.bottom)))
                    }
                }
            } catch (_: Exception) { "APPLICATION_WINDOW_METADATA_UNAVAILABLE_NOT_EXECUTED" }
            node.recycle()
            return reject(failure, if (failure == "APPLICATION_WINDOW_NOT_ACTIVE_NOT_EXECUTED") "reason=root_inactive" else null)
        }
        var windows = try { access.windows(current) } catch (_: Exception) {
            return reject("APPLICATION_WINDOW_METADATA_UNAVAILABLE_NOT_EXECUTED", "reason=window_list_exception")
        }
        initialWindows = windowSummary(windows)
        finalWindows = initialWindows
        if (windows.isNotEmpty()) {
            try {
                if (windows.size > 64) return reject("APPLICATION_WINDOW_LIMIT_EXCEEDED_NOT_EXECUTED")
                fun supportedWindows() = windows.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION ||
                    it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM }
                var supported = supportedWindows()
                var local = supported.filter(::onDefaultDisplay)
                if (Build.VERSION.SDK_INT >= 33 && local.isNotEmpty() && local.none { it.isActive || it.isFocused }) {
                    // getWindows and node.window share the framework cache. Invalidate that one
                    // snapshot once; all admission and action fences still inspect the new data.
                    val cleared = try { access.clearWindowCache(current) } catch (_: Exception) {
                        cacheRefresh = "exception"
                        return reject("APPLICATION_WINDOW_METADATA_UNAVAILABLE_NOT_EXECUTED", "reason=cache_clear_exception")
                    }
                    cacheRefresh = if (cleared) "cleared" else "unavailable"
                    if (cleared) {
                        val previous = windows
                        windows = emptyList()
                        previous.forEach { runCatching { it.recycle() } }
                        finalWindows = "read_failed"
                        windows = try { access.windows(current) } catch (_: Exception) {
                            return reject("APPLICATION_WINDOW_METADATA_UNAVAILABLE_NOT_EXECUTED", "reason=refreshed_list_exception")
                        }
                        finalWindows = windowSummary(windows)
                        targetRejection(lease)?.let { return it }
                        if (windows.size > 64) return reject("APPLICATION_WINDOW_LIMIT_EXCEEDED_NOT_EXECUTED")
                        if (windows.isEmpty()) return reject("APPLICATION_WINDOW_NOT_ACTIVE_NOT_EXECUTED", "reason=refreshed_list_empty")
                        supported = supportedWindows()
                        local = supported.filter(::onDefaultDisplay)
                    }
                }
                if (local.isEmpty()) return reject(if (supported.isNotEmpty())
                    "APPLICATION_WINDOW_DISPLAY_MISMATCH_NOT_EXECUTED" else "APPLICATION_WINDOW_REQUIRED_NOT_EXECUTED")
                val systemLayer = local.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM && it.isFocused }
                    .maxOfOrNull { it.layer }
                val candidates = local.filter { (it.isActive || it.isFocused) && (systemLayer == null ||
                    it.layer > systemLayer || it.layer == systemLayer &&
                    it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM && it.isFocused) }
                if (candidates.isEmpty()) {
                    // Cross-check the same listed window, never a requested background package.
                    val node = try { access.root(current) } catch (_: Exception) {
                        return reject("APPLICATION_WINDOW_METADATA_UNAVAILABLE_NOT_EXECUTED", "reason=active_root_exception")
                    } ?: return reject("APPLICATION_WINDOW_NOT_ACTIVE_NOT_EXECUTED", "reason=active_root_missing root_result=null windows=${windows.size} local=${local.size}")
                    val window = try { node.window } catch (_: Exception) { null }
                    if (window == null) {
                        node.recycle()
                        return reject("APPLICATION_WINDOW_METADATA_UNAVAILABLE_NOT_EXECUTED")
                    }
                    try {
                        val listed = local.filter { it.id == window.id }.singleOrNull()?.takeIf {
                            it.type == window.type && it.layer == window.layer &&
                                window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION && onDefaultDisplay(window)
                        }
                        val bounds = Rect().also { window.getBoundsInScreen(it) }
                        val blocked = local.any { other ->
                            if (other.id == window.id || other.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM ||
                                other.layer < window.layer) false
                            else {
                                val otherBounds = Rect().also { other.getBoundsInScreen(it) }
                                otherBounds.isEmpty || bounds.isEmpty || Rect.intersects(bounds, otherBounds)
                            }
                        }
                        if (listed == null || blocked) {
                            node.recycle()
                            return reject("APPLICATION_WINDOW_NOT_ACTIVE_NOT_EXECUTED",
                                "reason=${if (listed == null) "root_identity_conflict" else "system_overlap"}" +
                                    " windows=${windows.size} local=${local.size} root_active=${window.isActive} root_focused=${window.isFocused}")
                        }
                        return admit(node, window)
                    } catch (_: Exception) {
                        node.recycle()
                        return reject("APPLICATION_WINDOW_METADATA_UNAVAILABLE_NOT_EXECUTED")
                    } finally { window.recycle() }
                }
                fun rank(window: android.view.accessibility.AccessibilityWindowInfo): Int = if (window.isActive) 2 else 1
                val selected = candidates.maxWithOrNull(compareBy<android.view.accessibility.AccessibilityWindowInfo>({ rank(it) }, { it.layer }))!!
                if (candidates.count { rank(it) == rank(selected) && it.layer == selected.layer } != 1)
                    return reject("APPLICATION_WINDOW_AMBIGUOUS_NOT_EXECUTED")
                val node = try { selected.root } catch (_: Exception) {
                    return reject("APPLICATION_WINDOW_METADATA_UNAVAILABLE_NOT_EXECUTED", "reason=selected_root_exception")
                } ?: return reject("APPLICATION_WINDOW_ROOT_UNAVAILABLE_NOT_EXECUTED", "reason=selected_root_missing")
                return admit(node, selected)
            } catch (_: Exception) { return reject("APPLICATION_WINDOW_METADATA_UNAVAILABLE_NOT_EXECUTED") }
            finally { windows.forEach { runCatching { it.recycle() } } }
        }
        val node = try { access.root(current) } catch (_: Exception) {
            return reject("NO_ACTIVE_EXTERNAL_WINDOW", "reason=fallback_root_exception")
        } ?: return reject("NO_ACTIVE_EXTERNAL_WINDOW", "reason=fallback_root_missing root_result=null")
        val window = try { node.window } catch (_: Exception) { null }
        if (window == null) {
            node.recycle()
            return reject("APPLICATION_WINDOW_METADATA_UNAVAILABLE_NOT_EXECUTED")
        }
        return try { admit(node, window) } finally { window.recycle() }
    }

    private fun rejectChangedTarget(lease: GuiLeaseGate.Lease, code: String): GuiResult.Rejected {
        val rejection = GuiResult.Rejected(code)
        lease.observation?.let { recordObservationRejection(lease, it.observationId, "target_changed", rejection) }
        gate.retireObservation(lease); clearNodes()
        return rejection
    }

    /** Reacquire the admitted application or system tree; do NOT publish a new observation/clear the approved one.
     * A bounded complete traversal must find exactly one matching target. No coordinate fallback. */
    @Suppress("DEPRECATION")
    internal fun validateExactTarget(lease: GuiLeaseGate.Lease, action: GuiAction): GuiResult<Unit> {
        onMain(); operationRejection(lease, action.operation)?.let { return it }
        screenRejection()?.let { return it }; selfRootRejection(lease)?.let { return it }
        validateObservation(lease, action.observationId, "validate_target")?.let { return it }
        val observed = lease.observation!!
        if (contentChanged && action.nodeId == null) return recordObservationRejection(lease,
            action.observationId, "validate_target", GuiResult.Rejected("OBSERVATION_STALE"))
        if (observed.windowType != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION && action.nodeId == null)
            return GuiResult.Rejected("SYSTEM_WINDOW_NODE_ACTIONS_ONLY_NOT_EXECUTED")
        val fetched = externalRoot(lease, service!!)
        if (fetched !is GuiResult.Value) return fetched as GuiResult.Rejected
        val root = fetched.value.node
        if (fetched.value.windowType != observed.windowType || root.windowId != observed.windowId || root.packageName?.toString() != observed.packageName) {
            root.recycle(); return rejectChangedTarget(lease, "TARGET_WINDOW_CHANGED_RECONFIRM_NOT_EXECUTED")
        }
        val original = action.nodeId?.let { id -> observed.nodes.singleOrNull { it.id == id } }
        if (action.nodeId != null && original == null) { root.recycle(); return GuiResult.Rejected("NODE_NOT_IN_OBSERVATION") }
        if (isSelfLease(lease) && action.nodeId != null) {
            return try { validateSelfTarget(lease, action.nodeId, root) } finally { root.recycle() }
        }
        var count = 0
        var matches = 0
        var complete = !observed.truncated
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            try {
                if (++count > observed.maxNodes || depth > guiTreeDepth(observed.maxNodes)) { complete = false; return }
                if (original != null) {
                    val rect = Rect(); node.getBoundsInScreen(rect)
                    val fields = identity(node)
                    if (node.windowId == observed.windowId && node.packageName?.toString() == observed.packageName &&
                        fields != null && fields == listOf(original.text, original.description, original.className, original.viewId) &&
                        GuiBounds(rect.left, rect.top, rect.right, rect.bottom) == original.bounds &&
                        node.isClickable == original.clickable && node.isLongClickable == original.longClickable &&
                        node.isScrollable == original.scrollable && node.isEditable == original.editable &&
                        node.isPassword == original.password && node.isEnabled == original.enabled && node.isVisibleToUser) matches++
                }
                if (node.childCount > guiTreeChildren(observed.maxNodes)) complete = false
                for (i in 0 until minOf(node.childCount, guiTreeChildren(observed.maxNodes))) {
                    if (count >= observed.maxNodes) { complete = false; break }
                    val child = node.getChild(i)
                    if (child == null) complete = false else visit(child, depth + 1)
                }
            } finally { node.recycle() }
        }
        return try {
            visit(root, 0)
            if (!complete || (original != null && (matches != 1 || original.password || !original.enabled)))
                rejectChangedTarget(lease, "TARGET_NOT_UNIQUE_RECONFIRM_NOT_EXECUTED")
            else GuiResult.Value(Unit)
        } catch (_: Exception) { rejectChangedTarget(lease, "TARGET_UNAVAILABLE_RECONFIRM_NOT_EXECUTED") }
    }

    /** Same admission, node/depth/child/text bounds for publishing and private verification.
     * Verification never retains nodes, consumes a read, publishes an ID, or changes approvals. */
    @Suppress("DEPRECATION")
    private fun sampleTree(lease: GuiLeaseGate.Lease, retain: Boolean, maxNodes: Int = 128): GuiResult<TreeSample> {
        val fetched = externalRoot(lease, service!!)
        if (fetched !is GuiResult.Value) return fetched as GuiResult.Rejected
        val admitted = fetched.value
        val source = admitted.node
        val result = mutableListOf<GuiNode>()
        val contexts = mutableListOf<NodeContext>()
        var truncated = false
        var textBudget = maxNodes * 128
        fun bounded(value: CharSequence?): String {
            val limit = minOf(512, textBudget)
            val text = value?.take(limit)?.toString().orEmpty()
            if ((value?.length ?: 0) > limit) truncated = true
            textBudget -= text.length
            return text
        }
        fun visit(node: AccessibilityNodeInfo, parent: Int?, depth: Int) {
            if (result.size >= maxNodes || depth > guiTreeDepth(maxNodes)) { truncated = true; node.recycle(); return }
            val id = result.size
            if (retain) {
                retained[id] = node
                identities[id] = if (node.isPassword) null else identity(node)
            }
            try {
                val rect = Rect(); node.getBoundsInScreen(rect)
                result += GuiNode(id, parent, GuiBounds(rect.left, rect.top, rect.right, rect.bottom),
                    if (node.isPassword) "" else bounded(node.text), if (node.isPassword) "" else bounded(node.contentDescription),
                    bounded(node.className), bounded(node.viewIdResourceName), node.isClickable, node.isLongClickable,
                    node.isScrollable, node.isEditable, node.isEnabled, node.isPassword)
                val count = node.childCount
                val pkg = node.packageName
                if ((pkg?.length ?: 0) > 256) truncated = true
                contexts += NodeContext(pkg?.take(256)?.toString().orEmpty(), node.windowId, count, node.isVisibleToUser)
                if (count > guiTreeChildren(maxNodes)) truncated = true
                for (index in 0 until minOf(count, guiTreeChildren(maxNodes))) {
                    if (result.size >= maxNodes) { truncated = true; break }
                    val child = node.getChild(index)
                    if (child == null) truncated = true else visit(child, id, depth + 1)
                }
            } finally { if (!retain) node.recycle() }
        }
        // Read root metadata inside visit's disposal scope as well.
        return try {
            visit(source, null, 0)
            val root = contexts.first()
            GuiResult.Value(TreeSample(root.packageName, root.windowId, admitted.windowType, result, contexts, truncated, maxNodes))
        } catch (_: Exception) { GuiResult.Rejected("OBSERVATION_UNAVAILABLE") }
    }

    internal fun observe(lease: GuiLeaseGate.Lease, maxNodes: Int = 128): GuiResult<GuiObservation> =
        RuntimeDiagnostics.measure(RuntimeDiagnostics.Stage.GUI_OBSERVE, size = maxNodes.toLong()) {
            observeInternal(lease, maxNodes).also { if (it is GuiResult.Rejected)
                RuntimeDiagnostics.guiResult(it.code, RuntimeDiagnostics.Stage.GUI_OBSERVE) }
        }

    private fun observeInternal(lease: GuiLeaseGate.Lease, maxNodes: Int): GuiResult<GuiObservation> {
        require(maxNodes in 128..512)
        onMain(); targetRejection(lease)?.let { return it }
        staleEvent = null; staleLoggedFor = null
        windowReadGeneration++
        gate.retireObservation(lease); clearNodes()
        screenRejection()?.let { return it }
        val permit = gate.consumeRead(lease, screenshot = false)
        if (permit !is GuiResult.Value) return permit as GuiResult.Rejected
        val startGeneration = generation
        val sampled = sampleTree(lease, retain = true, maxNodes = maxNodes)
        if (sampled !is GuiResult.Value) { clearNodes(); return sampled as GuiResult.Rejected }
        val tree = sampled.value
        if (isSelfLease(lease)) retainSelfTargets(tree)
        val observation = GuiObservation(UUID.randomUUID().toString(), tree.packageName, tree.windowId, startGeneration,
            SystemClock.elapsedRealtime(), tree.nodes, tree.truncated, maxNodes, tree.windowType)
        val published = gate.publish(lease, observation)
        if (published is GuiResult.Value) screenshotBinding = observation.observationId to tree else clearNodes()
        return published
    }

    private fun observedMaxNodes(lease: GuiLeaseGate.Lease): Int = lease.observation?.maxNodes ?: 128

    /** Logical stability only, not physical atomicity of a platform frame and accessibility tree. */
    private fun validateScreenshot(lease: GuiLeaseGate.Lease, observationId: String): GuiResult.Rejected? {
        onMain(); targetRejection(lease)?.let { return it }; screenRejection()?.let { return it }
        selfRootRejection(lease)?.let { return it }
        validateObservation(lease, observationId, "validate_screenshot")?.let { return it }
        if (contentChanged) return recordObservationRejection(lease, observationId,
            "validate_screenshot", GuiResult.Rejected("OBSERVATION_STALE"))
        val binding = screenshotBinding?.takeIf { it.first == observationId }?.second
            ?: return GuiResult.Rejected("SCREENSHOT_TREE_UNVERIFIABLE")
        if (!binding.verifiable) return GuiResult.Rejected("SCREENSHOT_TREE_UNVERIFIABLE")
        val sampled = sampleTree(lease, retain = false, maxNodes = observedMaxNodes(lease))
        if (sampled !is GuiResult.Value) {
            if (sampled is GuiResult.Rejected && sampled.code == "SELF_APP_TARGET_LOST") return sampled
            return GuiResult.Rejected("SCREENSHOT_TREE_UNVERIFIABLE")
        }
        val actual = sampled.value
        if (!actual.verifiable) return GuiResult.Rejected("SCREENSHOT_TREE_UNVERIFIABLE")
        if (actual.packageName != binding.packageName || actual.windowId != binding.windowId)
            return rejectChangedTarget(lease, "SCREENSHOT_WINDOW_CHANGED")
        if (actual != binding) return GuiResult.Rejected("SCREENSHOT_TREE_CHANGED")
        return null
    }

    @Suppress("DEPRECATION")
    internal fun action(lease: GuiLeaseGate.Lease, action: GuiAction): GuiResult<String> =
        RuntimeDiagnostics.measure(RuntimeDiagnostics.Stage.GUI_ACTION) {
            actionInternal(lease, action).also {
                when (it) {
                    is GuiResult.Rejected -> RuntimeDiagnostics.guiResult(it.code)
                    is GuiResult.Unknown -> RuntimeDiagnostics.guiResult(it.code)
                    else -> Unit
                }
            }
        }

    private fun actionInternal(lease: GuiLeaseGate.Lease, action: GuiAction): GuiResult<String> {
        onMain(); operationRejection(lease, action.operation)?.let { return it }; screenRejection()?.let { return it }
        val current = service ?: return GuiResult.Rejected("SERVICE_DISCONNECTED_NOT_EXECUTED")
        val verified = validateExactTarget(lease, action)
        if (verified is GuiResult.Rejected) return verified
        val observed = lease.observation!!
        val node = action.nodeId?.let { retained[it] }
        if (action.nodeId != null) {
            val original = observed.nodes.firstOrNull { it.id == action.nodeId }
                ?: return GuiResult.Rejected("NODE_NOT_IN_OBSERVATION")
            try {
                if (node == null || !node.refresh()) return rejectChangedTarget(lease, "NODE_STALE")
                if (node.isPassword) return GuiResult.Rejected("PASSWORD_NODE_REFUSED")
                val originalIdentity = identities[action.nodeId]
                    ?: return GuiResult.Rejected("TARGET_IDENTITY_EXCEEDS_BOUND")
                val rect = Rect(); node.getBoundsInScreen(rect)
                if (node.windowId != observed.windowId || node.packageName?.toString().orEmpty() != observed.packageName ||
                    GuiBounds(rect.left, rect.top, rect.right, rect.bottom) != original.bounds ||
                    identity(node) != originalIdentity || node.isClickable != original.clickable ||
                    node.isLongClickable != original.longClickable || node.isScrollable != original.scrollable ||
                    node.isEditable != original.editable || !node.isVisibleToUser || !node.isEnabled)
                    return rejectChangedTarget(lease, "NODE_STALE")
            } catch (_: Exception) { return GuiResult.Rejected("NODE_UNAVAILABLE_NOT_EXECUTED") }
        }
        // Recheck active package/window and the live narrow token after node refresh, before delivery.
        try {
            val fetched = externalRoot(lease, current)
            if (fetched !is GuiResult.Value) return fetched as GuiResult.Rejected
            val active = fetched.value.node
            val sameWindow = try {
                if (isSelfLease(lease) && action.nodeId != null) {
                    val checked = validateSelfTarget(lease, action.nodeId, active)
                    if (checked is GuiResult.Rejected) return checked
                }
                fetched.value.windowType == observed.windowType && active.windowId == observed.windowId &&
                    active.packageName?.toString().orEmpty() == observed.packageName
            } finally { active.recycle() }
            if (!sameWindow) return rejectChangedTarget(lease, "OBSERVATION_STALE")
        } catch (_: Exception) { return GuiResult.Rejected("ACTIVE_WINDOW_UNAVAILABLE_NOT_EXECUTED") }
        if (action.operation == GuiOperation.IME_SUBMIT) {
            if (Build.VERSION.SDK_INT < 30) return GuiResult.Rejected("IME_SUBMIT_UNSUPPORTED_API_NOT_EXECUTED")
            if (node == null || !node.isEditable || !node.isFocused ||
                node.actionList.none { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id })
                return GuiResult.Rejected("IME_SUBMIT_NOT_SUPPORTED_BY_FOCUSED_NODE_NOT_EXECUTED")
        }
        action.gesture?.let { gesture ->
            val bounds = observed.nodes.firstOrNull()?.bounds ?: return GuiResult.Rejected("OBSERVATION_REQUIRED")
            fun inside(x: Int, y: Int) = x >= bounds.left && x < bounds.right && y >= bounds.top && y < bounds.bottom
            if (!inside(gesture.x, gesture.y) || !inside(gesture.endX, gesture.endY))
                return GuiResult.Rejected("GESTURE_OUTSIDE_OBSERVED_WINDOW_NOT_EXECUTED")
            val binding = screenshotBinding?.takeIf { it.first == observed.observationId }?.second
                ?: return GuiResult.Rejected("GESTURE_TREE_UNVERIFIABLE_NOT_EXECUTED")
            val sampled = sampleTree(lease, retain = false, maxNodes = observed.maxNodes)
            if (!binding.verifiable || sampled !is GuiResult.Value || sampled.value != binding)
                return rejectChangedTarget(lease, "GESTURE_TREE_CHANGED_OR_INCOMPLETE_NOT_EXECUTED")
            gestureWindowRejection(current, observed, gesture)?.let { return it }
        }
        val consumed = gate.consumeAction(lease, action, generation)
        if (consumed is GuiResult.Rejected) return consumed
        // No coordinate/ancestor fallback or automatic retry when an advertised action is unsupported.
        return try {
            if (action.gesture != null) return dispatchGesture(current, lease, action.gesture)
            val accepted = when (action.operation) {
                GuiOperation.TAP -> node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                GuiOperation.LONG_PRESS -> node!!.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                GuiOperation.SCROLL_FORWARD -> node!!.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                GuiOperation.SCROLL_BACKWARD -> node!!.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                GuiOperation.INPUT -> node!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, action.text)
                })
                GuiOperation.IME_SUBMIT -> node!!.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                GuiOperation.COORDINATE_TAP, GuiOperation.SWIPE -> error("gesture_required")
                GuiOperation.BACK -> current.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                GuiOperation.HOME -> current.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                GuiOperation.RECENTS -> current.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                GuiOperation.NOTIFICATIONS -> current.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            }
            GuiResult.Value(if (accepted) "PLATFORM_ACTION_ACCEPTED_NOT_VERIFIED" else "PLATFORM_ACTION_REJECTED")
        } catch (_: Exception) { GuiResult.Unknown("ACTION_DELIVERY_UNCONFIRMED") }
        finally { generation++; clearNodes() }
    }

    @Suppress("DEPRECATION")
    private fun gestureWindowRejection(current: GuiAccessibilityService, observed: GuiObservation,
        gesture: GuiGesture): GuiResult.Rejected? {
        val windows = try { access.windows(current) }
            catch (_: Exception) { return GuiResult.Rejected("GESTURE_WINDOWS_UNVERIFIABLE_NOT_EXECUTED") }
        return try {
            if (windows.size !in 1..64) return GuiResult.Rejected("GESTURE_WINDOWS_UNVERIFIABLE_NOT_EXECUTED")
            fun onDisplay(window: android.view.accessibility.AccessibilityWindowInfo): Boolean =
                Build.VERSION.SDK_INT < 30 || window.displayId == Display.DEFAULT_DISPLAY
            val target = windows.singleOrNull { onDisplay(it) && it.id == observed.windowId &&
                it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
                ?: return GuiResult.Rejected("GESTURE_WINDOWS_UNVERIFIABLE_NOT_EXECUTED")
            val targetBounds = Rect().also { target.getBoundsInScreen(it) }
            if (!targetBounds.contains(gesture.x, gesture.y) || !targetBounds.contains(gesture.endX, gesture.endY))
                return GuiResult.Rejected("GESTURE_OUTSIDE_OBSERVED_WINDOW_NOT_EXECUTED")
            val pathBounds = Rect(minOf(gesture.x, gesture.endX), minOf(gesture.y, gesture.endY),
                maxOf(gesture.x, gesture.endX) + 1, maxOf(gesture.y, gesture.endY) + 1)
            if (windows.any { window ->
                onDisplay(window) && window.id != target.id && window.layer >= target.layer &&
                    Rect.intersects(pathBounds, Rect().also { window.getBoundsInScreen(it) })
            }) GuiResult.Rejected("GESTURE_WINDOW_OCCLUDED_NOT_EXECUTED") else null
        } catch (_: Exception) { GuiResult.Rejected("GESTURE_WINDOWS_UNVERIFIABLE_NOT_EXECUTED") }
        finally { windows.forEach { it.recycle() } }
    }

    internal fun beginAction(lease: GuiLeaseGate.Lease, action: GuiAction): GuiResult<CompletableFuture<GuiResult<String>>> {
        val result = action(lease, action)
        return when (result) {
            is GuiResult.Value -> GuiResult.Value(gestures[lease] ?: CompletableFuture.completedFuture(result))
            is GuiResult.Rejected -> result
            is GuiResult.Unknown -> result
        }
    }
    private fun dispatchGesture(current: GuiAccessibilityService, lease: GuiLeaseGate.Lease,
        gesture: GuiGesture): GuiResult<String> {
        val future = CompletableFuture<GuiResult<String>>()
        gestures[lease] = future
        val submission = RuntimeDiagnostics.begin(RuntimeDiagnostics.Stage.GUI_GESTURE_SUBMIT)
        val callback = RuntimeDiagnostics.begin(RuntimeDiagnostics.Stage.GUI_GESTURE_WAIT)
        future.whenComplete { _, error -> callback.end(error) }
        return try {
            val accepted = access.gesture(current, gesture) { completed ->
                onMain()
                callback.mark(RuntimeDiagnostics.Code.GESTURE_CALLBACK, count = if (completed) 1 else 0)
                if (gestures[lease] === future) {
                    gestures.remove(lease)
                    future.complete(if (completed) GuiResult.Value("PLATFORM_GESTURE_COMPLETED_NOT_VERIFIED")
                        else GuiResult.Unknown("GESTURE_CANCELLED_UNCONFIRMED"))
                    finishDrain(lease)
                }
            }
            submission.mark(RuntimeDiagnostics.Code.GESTURE_SUBMITTED, count = if (accepted) 1 else 0)
            submission.end()
            if (!accepted) {
                gestures.remove(lease)
                future.complete(GuiResult.Value("PLATFORM_GESTURE_REJECTED"))
                GuiResult.Value("PLATFORM_GESTURE_REJECTED")
            } else if (future.isDone) future.getNow(GuiResult.Unknown("GESTURE_DELIVERY_UNCONFIRMED"))
            else GuiResult.Value("PLATFORM_GESTURE_ACCEPTED_NOT_VERIFIED")
        } catch (failure: Exception) {
            submission.end(failure)
            // Submission may have reached Android; only its terminal callback may release the slot.
            GuiResult.Unknown("GESTURE_DELIVERY_UNCONFIRMED")
        }
    }

    internal fun supportsVisualFallback(lease: GuiLeaseGate.Lease, code: String): Boolean {
        onMain()
        return !isSelfLease(lease) && code in setOf("APPLICATION_WINDOW_NOT_ACTIVE_NOT_EXECUTED",
            "APPLICATION_WINDOW_ROOT_UNAVAILABLE_NOT_EXECUTED", "NO_ACTIVE_EXTERNAL_WINDOW")
    }

    private fun visualRejection(lease: GuiLeaseGate.Lease): GuiResult.Rejected? =
        targetRejection(lease) ?: screenRejection() ?: if (isSelfLease(lease))
            GuiResult.Rejected("SELF_APP_VISUAL_FALLBACK_NOT_EXECUTED") else null

    /** No synthetic tree: a capture token authorizes only a fresh visual gesture, never nodes. */
    internal fun visualScreenshot(lease: GuiLeaseGate.Lease, cancelled: () -> Boolean): CompletableFuture<GuiResult<GuiScreenshot>> {
        onMain()
        windowReadGeneration++
        gate.retireObservation(lease); clearNodes()
        visualRejection(lease)?.let { return CompletableFuture.completedFuture(it) }
        val permit = gate.consumeRead(lease, screenshot = true)
        if (permit is GuiResult.Rejected) return CompletableFuture.completedFuture(permit)
        val future = CompletableFuture<GuiResult<GuiScreenshot>>()
        val id = UUID.randomUUID().toString()
        val capturedAt = SystemClock.elapsedRealtime()
        captureVisualFrame(lease, id, cancelled) { image, window, startVisualGeneration ->
            if (image is GuiResult.Value && window != null) {
                visualCapture = VisualCapture(lease, id, startVisualGeneration, capturedAt, window,
                    java.security.MessageDigest.getInstance("SHA-256").digest(image.value.png).toList(), image.value.visualPixels)
                future.complete(GuiResult.Value(image.value.copy(observationBound = false, visualPackage = window.packageName)))
            } else future.complete(image)
        }
        return future
    }

    private fun matchesVisualContent(captured: VisualCapture, image: GuiScreenshot, action: GuiAction): Boolean =
        RuntimeDiagnostics.measure(RuntimeDiagnostics.Stage.GUI_VALIDATE) {
            if (action.operation == GuiOperation.COORDINATE_TAP && action.gesture != null && captured.pixels != null) {
                val check = captured.pixels.compareTap(image.visualPixels, action.gesture, captured.window)
                RuntimeDiagnostics.pixels(check)
                check.accepted
            } else if (java.security.MessageDigest.getInstance("SHA-256").digest(image.png).toList() == captured.digest) {
                RuntimeDiagnostics.event(RuntimeDiagnostics.Stage.GUI_VALIDATE, RuntimeDiagnostics.Code.FRAME_EQUAL)
                true
            } else {
                RuntimeDiagnostics.event(RuntimeDiagnostics.Stage.GUI_VALIDATE, RuntimeDiagnostics.Code.FRAME_CHANGED)
                false
            }
        }

    internal fun visualAction(lease: GuiLeaseGate.Lease, action: GuiAction, cancelled: () -> Boolean): CompletableFuture<GuiResult<String>> {
        onMain()
        val future = CompletableFuture<GuiResult<String>>()
        val actionSpan = RuntimeDiagnostics.begin(RuntimeDiagnostics.Stage.GUI_ACTION)
        future.whenComplete { result, error ->
            actionSpan.end(error)
            when (result) {
                is GuiResult.Rejected -> RuntimeDiagnostics.guiResult(result.code)
                is GuiResult.Unknown -> RuntimeDiagnostics.guiResult(result.code)
                else -> Unit
            }
        }
        fun reject(code: String): CompletableFuture<GuiResult<String>> {
            future.complete(GuiResult.Rejected(code)); return future
        }
        visualRejection(lease)?.let { return reject(it.code) }
        val captured = visualCapture?.takeIf { it.lease === lease && it.id == action.observationId }
            ?: return reject("CAPTURE_ID_MISMATCH_NOT_EXECUTED")
        if (action.nodeId != null || action.operation !in setOf(GuiOperation.COORDINATE_TAP, GuiOperation.SWIPE, GuiOperation.BACK))
            return reject("VISUAL_CAPTURE_COORDINATES_OR_BACK_ONLY_NOT_EXECUTED")
        fun retire() { if (visualCapture === captured) visualCapture = null }
        if (captured.visualGeneration != visualGeneration || SystemClock.elapsedRealtime() - captured.capturedAt !in 0..GUI_OBSERVATION_TTL_MILLIS) {
            retire(); return reject("CAPTURE_STALE_NOT_EXECUTED")
        }
        if (action.gesture != null && !captured.window.admits(action.gesture))
            return reject("GESTURE_WINDOW_OCCLUDED_OR_OUTSIDE_NOT_EXECUTED")
        RuntimeDiagnostics.event(RuntimeDiagnostics.Stage.GUI_VALIDATE, RuntimeDiagnostics.Code.CAPTURE_AGE,
            size = GUI_OBSERVATION_TTL_MILLIS, count = SystemClock.elapsedRealtime() - captured.capturedAt)
        // Recheck the window and geometry; changing tap pixels are recorded, not a reason to block.
        captureVisualFrame(lease, captured.id, cancelled, validationOnly = action.operation == GuiOperation.COORDINATE_TAP && captured.pixels != null) { image, window, _ ->
            val rejection = visualRejection(lease) ?: if (cancelled()) GuiResult.Rejected("CANCELLED_NOT_EXECUTED") else null
            when {
                future.isDone -> Unit
                rejection != null -> { retire(); future.complete(rejection) }
                image is GuiResult.Rejected -> { retire(); future.complete(image) }
                image is GuiResult.Unknown -> { retire(); future.complete(image) }
                visualCapture !== captured || window != captured.window || captured.visualGeneration != visualGeneration ||
                    SystemClock.elapsedRealtime() - captured.capturedAt !in 0..GUI_OBSERVATION_TTL_MILLIS -> {
                    retire(); future.complete(GuiResult.Rejected("CAPTURE_STALE_NOT_EXECUTED"))
                }
                !matchesVisualContent(captured, (image as GuiResult.Value).value, action) -> {
                    retire(); future.complete(GuiResult.Rejected("CAPTURE_CONTENT_CHANGED_NOT_EXECUTED"))
                }
                else -> {
                    visualCapture = null // consume before dispatch, including denied/unknown submission
                    val current = service
                    if (current == null) future.complete(GuiResult.Rejected("SERVICE_DISCONNECTED_NOT_EXECUTED"))
                    else if (action.gesture != null) {
                        val submitted = dispatchGesture(current, lease, action.gesture)
                        generation++; clearNodes()
                        val pending = gestures[lease]
                        if (pending == null) future.complete(submitted)
                        else pending.whenComplete { value, error -> future.complete(value ?: GuiResult.Unknown("GESTURE_DELIVERY_UNCONFIRMED")) }
                    } else {
                        val result = try { GuiResult.Value(if (current.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
                            "PLATFORM_ACTION_ACCEPTED_NOT_VERIFIED" else "PLATFORM_ACTION_REJECTED") }
                            catch (_: Exception) { GuiResult.Unknown("ACTION_DELIVERY_UNCONFIRMED") }
                        generation++; clearNodes(); future.complete(result)
                    }
                }
            }
        }
        return future
    }

    private fun captureVisualFrame(lease: GuiLeaseGate.Lease, id: String, cancelled: () -> Boolean,
        validationOnly: Boolean = false, completed: (GuiResult<GuiScreenshot>, GuiVisualWindow?, Long) -> Unit) {
        val current = service
        if (current == null || Build.VERSION.SDK_INT < 30) {
            completed(GuiResult.Rejected("VISUAL_CAPTURE_UNAVAILABLE_NOT_EXECUTED"), null, visualGeneration); return
        }
        val captureSpan = RuntimeDiagnostics.begin(RuntimeDiagnostics.Stage.GUI_CAPTURE)
        val startVisualGeneration = visualGeneration
        val startedAt = SystemClock.elapsedRealtime()
        captures[lease] = (captures[lease] ?: 0) + 1
        val request = GuiCaptureRequest<GuiScreenshot> {
            if (Looper.myLooper() === Looper.getMainLooper()) captureFinished(lease) else main.post { captureFinished(lease) }
        }
        var initial: GuiVisualWindow? = null
        request.future.whenComplete { value, error ->
            captureSpan.end(error)
            when (value) {
                is GuiResult.Rejected -> RuntimeDiagnostics.guiResult(value.code, RuntimeDiagnostics.Stage.GUI_CAPTURE)
                is GuiResult.Unknown -> RuntimeDiagnostics.guiResult(value.code, RuntimeDiagnostics.Stage.GUI_CAPTURE)
                else -> Unit
            }
            completed(value ?: GuiResult.Rejected("VISUAL_CAPTURE_UNAVAILABLE_NOT_EXECUTED"), initial, startVisualGeneration)
        }
        fun currentRejection(stage: String): GuiResult.Rejected? {
            visualRejection(lease)?.let { return it }
            if (cancelled()) return GuiResult.Rejected("CANCELLED_NOT_EXECUTED")
            val age = SystemClock.elapsedRealtime() - startedAt
            val reason = when {
                service !== current -> "service"
                visualGeneration != startVisualGeneration -> "generation"
                age !in 0..GUI_OBSERVATION_TTL_MILLIS -> "age"
                else -> return null
            }
            if (!request.future.isDone) runCatching { android.util.Log.i("DaiyuGui",
                "visual_capture_stale stage=$stage reason=$reason age_ms=$age generation=$startVisualGeneration:$visualGeneration") }
            return GuiResult.Rejected("CAPTURE_STALE_NOT_EXECUTED")
        }
        fun windowRejection(window: GuiVisualWindow?, stage: String): GuiResult.Rejected? {
            currentRejection(stage)?.let { return it }
            if (window == null) return GuiResult.Rejected("VISUAL_WINDOW_UNVERIFIABLE_NOT_EXECUTED")
            if (window.packageName == current.packageName) return GuiResult.Rejected("SELF_APP_TARGET_REQUIRED_NOT_EXECUTED")
            if (targetBinding?.packageName != null && targetBinding?.packageName != window.packageName)
                return GuiResult.Rejected("TARGET_PACKAGE_MISMATCH_NOT_EXECUTED")
            return null
        }
        // Keep the same reservation while the read-only Root witness and platform callback drain.
        val initialWitness = RuntimeDiagnostics.begin(RuntimeDiagnostics.Stage.GUI_WINDOW_WITNESS)
        request.future.whenComplete { _, error -> initialWitness.end(error) }
        try { access.visualWindow(current) { window ->
            initialWitness.mark(RuntimeDiagnostics.Code.INITIAL_WITNESS)
            initialWitness.end()
            initial = window
            val denied = windowRejection(window, "initial_witness")
            request.submit {
                if (denied != null) { request.callbackFailure(denied.code); return@submit }
                val platformCapture = RuntimeDiagnostics.begin(RuntimeDiagnostics.Stage.GUI_PLATFORM_CAPTURE)
                request.future.whenComplete { _, error -> platformCapture.end(error) }
                try { access.capture(current, success = success@ { frame ->
                    platformCapture.mark(RuntimeDiagnostics.Code.CAPTURE_CALLBACK)
                    platformCapture.end()
                    fun dispose() { try { frame.dispose(request) } catch (_: Exception) { /* retain drain */ } }
                    val invalid = currentRejection("platform_callback")
                    if (invalid != null || request.future.isDone) {
                        if (invalid != null) request.future.complete(invalid)
                        dispose(); return@success
                    }
                    try {
                        encoder.execute {
                            if (validationOnly) RuntimeDiagnostics.event(RuntimeDiagnostics.Stage.GUI_ENCODE,
                                RuntimeDiagnostics.Code.VALIDATION_PIXELS_ONLY)
                            val image = try { RuntimeDiagnostics.measure(RuntimeDiagnostics.Stage.GUI_ENCODE) {
                                if (validationOnly) frame.validation(id) else frame.encode(id)
                            } } catch (_: Exception) { GuiResult.Rejected("SCREENSHOT_ENCODING_UNAVAILABLE") }
                            if (!main.post {
                                try {
                                    val finalWitness = RuntimeDiagnostics.begin(RuntimeDiagnostics.Stage.GUI_WINDOW_WITNESS)
                                    request.future.whenComplete { _, error -> finalWitness.end(error) }
                                    access.visualWindow(current) { finalWindow ->
                                        finalWitness.mark(RuntimeDiagnostics.Code.FINAL_WITNESS)
                                        finalWitness.end()
                                        try {
                                            val rejection = windowRejection(finalWindow, "final_witness") ?: if (finalWindow != initial)
                                                GuiResult.Rejected("CAPTURE_WINDOW_CHANGED_NOT_EXECUTED") else null
                                            val dimensions = image is GuiResult.Value && (image.value.width != initial?.displayBounds?.right ||
                                                image.value.height != initial?.displayBounds?.bottom)
                                            request.future.complete(rejection ?: if (dimensions)
                                                GuiResult.Rejected("CAPTURE_GEOMETRY_CHANGED_NOT_EXECUTED") else image)
                                        } finally { dispose() }
                                    }
                                } catch (failure: Exception) {
                                    request.future.complete(GuiResult.Rejected("VISUAL_WINDOW_UNVERIFIABLE_NOT_EXECUTED")); dispose()
                                }
                            }) { request.future.complete(GuiResult.Rejected("SCREENSHOT_RESULT_UNAVAILABLE")); dispose() }
                        }
                    } catch (_: Exception) { request.future.complete(GuiResult.Rejected("SCREENSHOT_ENCODING_UNAVAILABLE")); dispose() }
                }, failure = { error ->
                    platformCapture.mark(RuntimeDiagnostics.Code.CAPTURE_CALLBACK_FAILED, size = error.toLong())
                    platformCapture.end()
                    request.callbackFailure("SCREENSHOT_PLATFORM_ERROR_$error")
                })
                } catch (failure: Exception) { platformCapture.end(failure); throw failure }
            }
        } } catch (failure: Exception) { initialWitness.end(failure); request.callbackFailure("VISUAL_WINDOW_UNVERIFIABLE_NOT_EXECUTED") }
    }

    /** No capture occurs while observing. API 30+ only; secure windows/platform errors stay errors. */
    internal fun screenshot(lease: GuiLeaseGate.Lease, observationId: String,
        visualOnly: Boolean = false): CompletableFuture<GuiResult<GuiScreenshot>> {
        onMain()
        fun retireVisualObservation() {
            if (visualOnly && gate.ownsSlot(lease) && lease.observation?.observationId == observationId) {
                gate.retireObservation(lease); clearNodes()
            }
        }
        fun rejected(code: String) = CompletableFuture.completedFuture<GuiResult<GuiScreenshot>>(GuiResult.Rejected(code))
            .also { retireVisualObservation() }
        targetRejection(lease)?.let { return rejected(it.code) }; screenRejection()?.let { return rejected(it.code) }
        selfRootRejection(lease)?.let { return rejected(it.code) }
        validateObservation(lease, observationId, "capture")?.let { return rejected(it.code) }
        if (Build.VERSION.SDK_INT < 30) return rejected("SCREENSHOT_UNSUPPORTED_API")
        val observed = lease.observation!!
        if (observed.windowType != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION)
            return rejected("SYSTEM_WINDOW_NODE_ACTIONS_ONLY_NOT_EXECUTED")
        // A current self-app image is a visual read, never an authorization for old node IDs.
        // Its live content may stream; the original lease, generation, window and display may not change.
        @Suppress("DEPRECATION")
        fun validateCapture(): GuiResult.Rejected? {
            if (!visualOnly || !isSelfLease(lease)) return validateScreenshot(lease, observationId)
            targetRejection(lease)?.let { return it }; screenRejection()?.let { return it }
            validateObservation(lease, observationId, "capture_current_window")?.let { return it }
            val fetched = externalRoot(lease, service!!)
            if (fetched !is GuiResult.Value) return fetched as GuiResult.Rejected
            val root = fetched.value.node
            return try {
                if (root.packageName?.toString().orEmpty() != observed.packageName || root.windowId != observed.windowId)
                    rejectChangedTarget(lease, "SCREENSHOT_WINDOW_CHANGED") else null
            } finally { root.recycle() }
        }
        validateCapture()?.let { return rejected(it.code) }
        val permit = gate.consumeRead(lease, screenshot = true)
        if (permit is GuiResult.Rejected) return rejected(permit.code)
        return captureScreenshot(lease, observationId, visualOnly,
            validateCallback = { targetRejection(lease) ?: screenRejection() ?: selfRootRejection(lease) ?:
                if (visualOnly) validateCapture() else validateObservation(lease, observationId, "capture_callback") },
            validateResult = ::validateCapture, retireObservation = ::retireVisualObservation)
    }

    private data class CaptureWindow(val packageName: String, val windowId: Int, val type: Int, val bounds: GuiBounds)

    /** Current-window image only: window metadata is checked, but no UI tree is sampled or published. */
    @Suppress("DEPRECATION")
    internal fun currentWindowScreenshot(lease: GuiLeaseGate.Lease,
        cancelled: () -> Boolean): CompletableFuture<GuiResult<GuiScreenshot>> {
        onMain()
        windowReadGeneration++
        gate.retireObservation(lease); clearNodes()
        fun rejected(code: String) = CompletableFuture.completedFuture<GuiResult<GuiScreenshot>>(GuiResult.Rejected(code))
        targetRejection(lease)?.let { return rejected(it.code) }
        screenRejection()?.let { return rejected(it.code) }
        if (cancelled()) return rejected("CANCELLED_NOT_EXECUTED")
        val current = service ?: return rejected("SERVICE_DISCONNECTED_NOT_EXECUTED")
        val fetched = externalRoot(lease, current)
        if (fetched !is GuiResult.Value) {
            val denied = fetched as GuiResult.Rejected
            return if (supportsVisualFallback(lease, denied.code)) visualScreenshot(lease, cancelled) else rejected(denied.code)
        }
        fun identity(admitted: AdmittedRoot): CaptureWindow = try {
            CaptureWindow(admitted.node.packageName?.toString().orEmpty(), admitted.node.windowId, admitted.windowType,
                admitted.windowBounds)
        } finally { admitted.node.recycle() }
        val window = identity(fetched.value)
        if (window.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION)
            return rejected("SYSTEM_WINDOW_NODE_ACTIONS_ONLY_NOT_EXECUTED")
        if (Build.VERSION.SDK_INT < 30) return rejected("SCREENSHOT_UNSUPPORTED_API")
        val version = windowReadGeneration
        val started = SystemClock.elapsedRealtime()
        fun validateWindow(): GuiResult.Rejected? {
            targetRejection(lease)?.let { return it }
            screenRejection()?.let { return it }
            if (cancelled()) return GuiResult.Rejected("CANCELLED_NOT_EXECUTED")
            if (service !== current || windowReadGeneration != version ||
                SystemClock.elapsedRealtime() - started !in 0..GUI_OBSERVATION_TTL_MILLIS)
                return GuiResult.Rejected("CAPTURE_STALE_NOT_EXECUTED")
            val latest = externalRoot(lease, current)
            if (latest !is GuiResult.Value) return latest as GuiResult.Rejected
            return if (identity(latest.value) != window) GuiResult.Rejected("SCREENSHOT_WINDOW_CHANGED") else null
        }
        validateWindow()?.let { return rejected(it.code) }
        val permit = gate.consumeRead(lease, screenshot = true)
        if (permit is GuiResult.Rejected) return rejected(permit.code)
        return captureScreenshot(lease, UUID.randomUUID().toString(), visualOnly = true,
            validateCallback = ::validateWindow, validateResult = ::validateWindow)
    }

    private fun captureScreenshot(lease: GuiLeaseGate.Lease, observationId: String, visualOnly: Boolean,
        validateCallback: () -> GuiResult.Rejected?, validateResult: () -> GuiResult.Rejected?,
        retireObservation: () -> Unit = {}): CompletableFuture<GuiResult<GuiScreenshot>> {
        val current = service ?: return CompletableFuture.completedFuture(GuiResult.Rejected("SERVICE_DISCONNECTED_NOT_EXECUTED"))
        captures[lease] = (captures[lease] ?: 0) + 1
        val request = GuiCaptureRequest<GuiScreenshot> {
            if (Looper.myLooper() === Looper.getMainLooper()) captureFinished(lease)
            else main.post { captureFinished(lease) } // no main receipt means no manufactured slot release
        }
        val future = request.future
        val captureSpan = RuntimeDiagnostics.begin(RuntimeDiagnostics.Stage.GUI_CAPTURE)
        future.whenComplete { value, error ->
            captureSpan.end(error)
            if (value is GuiResult.Rejected) RuntimeDiagnostics.guiResult(value.code, RuntimeDiagnostics.Stage.GUI_CAPTURE)
        }
        future.whenComplete { _, _ ->
            val retire = Runnable { retireObservation() }
            if (Looper.myLooper() === Looper.getMainLooper()) retire.run() else main.post(retire)
        }
        request.submit {
            val platformCapture = RuntimeDiagnostics.begin(RuntimeDiagnostics.Stage.GUI_PLATFORM_CAPTURE)
            future.whenComplete { _, error -> platformCapture.end(error) }
            access.capture(current, success = success@ { frame ->
                platformCapture.mark(RuntimeDiagnostics.Code.CAPTURE_CALLBACK); platformCapture.end()
                onMain()
                fun dispose() {
                    // Unconfirmed disposal must NOT manufacture a drain receipt.
                    try { frame.dispose(request) } catch (_: Exception) { /* reservation retained */ }
                }
                try {
                    val rejection = validateCallback()
                    if (future.isDone || rejection != null) {
                        if (rejection != null) future.complete(rejection)
                        dispose(); return@success
                    }
                    encoder.execute {
                        // No lease/tree/service access on this worker. Cancellation does not release resources.
                        val encoded = try {
                            if (future.isDone) GuiResult.Rejected("SCREENSHOT_CANCELLED") else RuntimeDiagnostics.measure(RuntimeDiagnostics.Stage.GUI_ENCODE) { frame.encode(observationId) }
                        } catch (_: Exception) { GuiResult.Rejected("SCREENSHOT_UNAVAILABLE") }
                        val posted = try {
                            main.post {
                                try {
                                    if (!future.isDone) {
                                        val invalid = validateResult()
                                        val result = if (visualOnly && encoded is GuiResult.Value)
                                            GuiResult.Value(encoded.value.copy(observationBound = false)) else encoded
                                        future.complete(invalid ?: result)
                                    }
                                } catch (_: Exception) { future.complete(GuiResult.Rejected("SCREENSHOT_UNAVAILABLE")) }
                                finally { dispose() }
                            }
                        } catch (_: Exception) { false }
                        if (!posted) {
                            future.complete(GuiResult.Rejected("SCREENSHOT_RESULT_UNAVAILABLE"))
                            dispose() // free the frame even if main can no longer acknowledge drain
                        }
                    }
                } catch (_: Exception) {
                    future.complete(GuiResult.Rejected("SCREENSHOT_ENCODING_UNAVAILABLE"))
                    dispose()
                }
            }, failure = { code ->
                platformCapture.mark(RuntimeDiagnostics.Code.CAPTURE_CALLBACK_FAILED, size = code.toLong()); platformCapture.end()
                onMain(); request.callbackFailure("SCREENSHOT_PLATFORM_ERROR_$code")
            })
        }
        return future
    }
}
