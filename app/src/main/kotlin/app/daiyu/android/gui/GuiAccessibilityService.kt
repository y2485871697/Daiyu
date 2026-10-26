package app.daiyu.android.gui

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
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
                AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) != 0) GuiRuntime.changed(this)
        // Do not retain event text or continuously collect a UI tree.
    }
    override fun onInterrupt() { GuiRuntime.disconnected(this) }
    override fun onUnbind(intent: Intent?): Boolean { GuiRuntime.disconnected(this); return super.onUnbind(intent) }
    override fun onDestroy() {
        if (watchingPermission) { contentResolver.unregisterContentObserver(permissionObserver); watchingPermission = false }
        GuiRuntime.disconnected(this); super.onDestroy()
    }
}

internal data class GuiScreenshot(val observationId: String, val width: Int, val height: Int, val png: ByteArray)

/** Narrow platform seam: admission and bounded traversal remain in the runtime. */
internal interface GuiRuntimeAccess {
    fun enabled(current: GuiAccessibilityService): Boolean = GuiSystemSettings.enabledBySystem(current)
    fun unlocked(current: GuiAccessibilityService): Boolean =
        !current.getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
            current.getSystemService(PowerManager::class.java).isInteractive
    fun root(current: GuiAccessibilityService): AccessibilityNodeInfo? = current.rootInActiveWindow
    @androidx.annotation.RequiresApi(30)
    fun capture(current: GuiAccessibilityService, success: (GuiCaptureFrame) -> Unit, failure: (Int) -> Unit)
}

/** A frame owns its resources until disposal is confirmed, including time queued for encoding. */
internal interface GuiCaptureFrame {
    @WorkerThread fun encode(observationId: String): GuiResult<GuiScreenshot>
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
    @WorkerThread override fun encode(observationId: String): GuiResult<GuiScreenshot> {
        if (buffer.width > 2048 || buffer.height > 4096 || buffer.width.toLong() * buffer.height > 8_388_608)
            return GuiResult.Rejected("SCREENSHOT_DIMENSIONS_EXCEED_LIMIT")
        hardware = Bitmap.wrapHardwareBuffer(buffer, colorSpace)
        software = hardware?.copy(Bitmap.Config.ARGB_8888, false)
        val image = software ?: return GuiResult.Rejected("SCREENSHOT_ENCODING_UNAVAILABLE")
        val bytes = object : ByteArrayOutputStream() {
            override fun write(b: ByteArray, off: Int, len: Int) {
                check(count.toLong() + len <= 2_097_152) { "screenshot_size_limit" }; super.write(b, off, len)
            }
            override fun write(b: Int) { check(count < 2_097_152); super.write(b) }
        }
        if (!image.compress(Bitmap.CompressFormat.PNG, 100, bytes))
            return GuiResult.Rejected("SCREENSHOT_ENCODING_UNAVAILABLE")
        return GuiResult.Value(GuiScreenshot(observationId, image.width, image.height, bytes.toByteArray()))
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
    private var confirmation: GuiConfirmationOverlay? = null
    // Narrow parameters belong to the original gate lease, never to the process or a later owner.
    private data class TargetBinding(val lease: GuiLeaseGate.Lease, val packageName: String?,
        val selfTarget: TaskSurfaceSelfTarget?, val surfaceOwner: Any?)
    private var targetBinding: TargetBinding? = null
    private fun selfCurrent(target: TaskSurfaceSelfTarget?, owner: Any?, ownPackage: String): Boolean =
        owner != null && try { target?.isCurrent(owner, ownPackage) == true } catch (_: Exception) { false }
    private fun targetRejection(lease: GuiLeaseGate.Lease): GuiResult.Rejected? {
        gate.rejection(lease)?.let { return it }
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
            is GuiResult.Value -> { fetched.value.recycle(); null }
            is GuiResult.Rejected -> fetched
            is GuiResult.Unknown -> error("root admission cannot be unknown")
        }
    }
    internal fun showConfirmation(session: GuiRunSession, operation: GuiOperation, text: String?,
        ownerCurrent: () -> Boolean, onNotExecuted: (String) -> Unit, onRevoked: () -> Unit,
        deliver: (GuiConfirmedDelivery) -> Boolean): GuiResult<Unit> {
        onMain(); operationRejection(session.lease, operation)?.let { return it }; screenRejection()?.let { return it }
        selfRootRejection(session.lease)?.let { return it }
        if (confirmation != null) return GuiResult.Rejected("CONFIRMATION_ALREADY_OPEN")
        val host = GuiConfirmationOverlay(service!!, session, operation, text, ownerCurrent, onNotExecuted, onRevoked, deliver)
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
    fun changed(value: GuiAccessibilityService) { onMain(); if (service === value) { generation++; clearNodes() } }
    private data class NodeContext(val packageName: String, val windowId: Int, val children: Int, val visible: Boolean)
    private data class TreeSample(val packageName: String, val windowId: Int, val nodes: List<GuiNode>,
        val contexts: List<NodeContext>, val truncated: Boolean) {
        // Redacted passwords and incomplete traversals cannot prove tree equality.
        val verifiable: Boolean get() = !truncated && nodes.none { it.password }
    }
    private var screenshotBinding: Pair<String, TreeSample>? = null
    @Suppress("DEPRECATION") private fun clearNodes() {
        screenshotBinding = null
        retained.values.forEach { it.recycle() }; retained.clear(); identities.clear()
    }
    private fun screenRejection(): GuiResult.Rejected? {
        val current = service ?: return GuiResult.Rejected("SERVICE_DISCONNECTED_NOT_EXECUTED")
        val enabled = try { access.enabled(current) } catch (_: Exception) { false }
        if (!enabled) {
            disconnected(current)
            return GuiResult.Rejected("SERVICE_PERMISSION_REVOKED_NOT_EXECUTED")
        }
        if (!(try { access.unlocked(current) } catch (_: Exception) { false }))
            return GuiResult.Rejected("SCREEN_LOCKED_NOT_EXECUTED")
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
        if (captures.isNotEmpty() || closingLeases.isNotEmpty()) return GuiResult.Rejected("FOREGROUND_DRAIN_PENDING_NOT_EXECUTED")
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
    internal fun approveReads(lease: GuiLeaseGate.Lease, approval: GuiReadApproval): GuiResult<Unit> {
        onMain(); targetRejection(lease)?.let { return it }; return gate.approveReads(lease, approval)
    }
    internal fun approveAction(lease: GuiLeaseGate.Lease, action: GuiAction): GuiResult<Unit> {
        onMain(); operationRejection(lease, action.operation)?.let { return it }
        selfRootRejection(lease)?.let { return it }
        return gate.approveAction(lease, action, generation)
    }
    internal fun approvedActions(lease: GuiLeaseGate.Lease): Set<GuiAction> {
        onMain(); if (targetRejection(lease) != null) return emptySet()
        return gate.approvedActions(lease)
    }
    private val captures = mutableMapOf<GuiLeaseGate.Lease, Int>()
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
        if ((captures[lease] ?: 0) != 0) return
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

    /** Default-display active application only. Self is a lease-local, live-token exception,
     * never a global allow-own switch. An explicit external target must also match exactly. */
    @Suppress("DEPRECATION")
    private fun externalRoot(lease: GuiLeaseGate.Lease, current: GuiAccessibilityService): GuiResult<AccessibilityNodeInfo> {
        targetRejection(lease)?.let { return it }
        val binding = targetBinding!! // targetRejection proved the original lease owns these parameters
        val self = isSelfLease(lease)
        val root = try { access.root(current) } catch (_: Exception) { null }
            ?: return if (self) selfLost(lease) else GuiResult.Rejected("NO_ACTIVE_EXTERNAL_WINDOW")
        val valid = try {
            val window = root.window
            try {
                val pkg = root.packageName?.toString().orEmpty()
                pkg.isNotBlank() && pkg.length <= 256 && (pkg != current.packageName || self) &&
                    (binding.packageName == null || pkg == binding.packageName) &&
                    window != null && window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION &&
                    window.isActive && (Build.VERSION.SDK_INT < 30 || window.displayId == Display.DEFAULT_DISPLAY)
            } finally { window?.recycle() }
        } catch (_: Exception) { false }
        if (!valid) {
            root.recycle()
            return if (self) selfLost(lease) else GuiResult.Rejected("EXTERNAL_APPLICATION_ROOT_REQUIRED_NOT_EXECUTED")
        }
        return GuiResult.Value(root)
    }

    /** Reacquire the admitted application tree; do NOT publish a new observation/clear the approved one.
     * A bounded complete traversal must find exactly one matching target. No coordinate fallback. */
    @Suppress("DEPRECATION")
    internal fun validateExactTarget(lease: GuiLeaseGate.Lease, action: GuiAction): GuiResult<Unit> {
        onMain(); operationRejection(lease, action.operation)?.let { return it }
        screenRejection()?.let { return it }; selfRootRejection(lease)?.let { return it }
        gate.validateObservation(lease, action.observationId, generation)?.let { return it }
        val observed = lease.observation!!
        val fetched = externalRoot(lease, service!!)
        if (fetched !is GuiResult.Value) return fetched as GuiResult.Rejected
        val root = fetched.value
        if (root.windowId != observed.windowId || root.packageName?.toString() != observed.packageName) {
            root.recycle(); return GuiResult.Rejected("TARGET_WINDOW_CHANGED_RECONFIRM_NOT_EXECUTED")
        }
        val original = action.nodeId?.let { id -> observed.nodes.singleOrNull { it.id == id } }
        if (action.nodeId != null && original == null) { root.recycle(); return GuiResult.Rejected("NODE_NOT_IN_OBSERVATION") }
        var count = 0
        var matches = 0
        var complete = !observed.truncated
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            try {
                if (++count > 128 || depth > 16) { complete = false; return }
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
                if (node.childCount > 64) complete = false
                for (i in 0 until minOf(node.childCount, 64)) {
                    if (count >= 128) { complete = false; break }
                    val child = node.getChild(i)
                    if (child == null) complete = false else visit(child, depth + 1)
                }
            } finally { node.recycle() }
        }
        return try {
            visit(root, 0)
            if (!complete || (original != null && (matches != 1 || original.password || !original.enabled)))
                GuiResult.Rejected("TARGET_NOT_UNIQUE_RECONFIRM_NOT_EXECUTED")
            else GuiResult.Value(Unit)
        } catch (_: Exception) { GuiResult.Rejected("TARGET_UNAVAILABLE_RECONFIRM_NOT_EXECUTED") }
    }

    /** Same admission, node/depth/child/text bounds for publishing and private verification.
     * Verification never retains nodes, consumes a read, publishes an ID, or changes approvals. */
    @Suppress("DEPRECATION")
    private fun sampleTree(lease: GuiLeaseGate.Lease, retain: Boolean): GuiResult<TreeSample> {
        val fetched = externalRoot(lease, service!!)
        if (fetched !is GuiResult.Value) return fetched as GuiResult.Rejected
        val source = fetched.value
        val result = mutableListOf<GuiNode>()
        val contexts = mutableListOf<NodeContext>()
        var truncated = false
        var textBudget = 16_384
        fun bounded(value: CharSequence?): String {
            val limit = minOf(512, textBudget)
            val text = value?.take(limit)?.toString().orEmpty()
            if ((value?.length ?: 0) > limit) truncated = true
            textBudget -= text.length
            return text
        }
        fun visit(node: AccessibilityNodeInfo, parent: Int?, depth: Int) {
            if (result.size >= 128 || depth > 16) { truncated = true; node.recycle(); return }
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
                if (count > 64) truncated = true
                for (index in 0 until minOf(count, 64)) {
                    if (result.size >= 128) { truncated = true; break }
                    val child = node.getChild(index)
                    if (child == null) truncated = true else visit(child, id, depth + 1)
                }
            } finally { if (!retain) node.recycle() }
        }
        // Read root metadata inside visit's disposal scope as well.
        return try {
            visit(source, null, 0)
            val root = contexts.first()
            GuiResult.Value(TreeSample(root.packageName, root.windowId, result, contexts, truncated))
        } catch (_: Exception) { GuiResult.Rejected("OBSERVATION_UNAVAILABLE") }
    }

    internal fun observe(lease: GuiLeaseGate.Lease): GuiResult<GuiObservation> {
        onMain(); targetRejection(lease)?.let { return it }
        gate.retireObservation(lease); clearNodes()
        screenRejection()?.let { return it }
        val permit = gate.consumeRead(lease, screenshot = false)
        if (permit !is GuiResult.Value) return permit as GuiResult.Rejected
        val startGeneration = generation
        val sampled = sampleTree(lease, retain = true)
        if (sampled !is GuiResult.Value) { clearNodes(); return sampled as GuiResult.Rejected }
        val tree = sampled.value
        val observation = GuiObservation(UUID.randomUUID().toString(), tree.packageName, tree.windowId, startGeneration,
            SystemClock.elapsedRealtime(), tree.nodes, tree.truncated)
        val published = gate.publish(lease, observation)
        if (published is GuiResult.Value) screenshotBinding = observation.observationId to tree else clearNodes()
        return published
    }

    /** Logical stability only, not physical atomicity of a platform frame and accessibility tree. */
    private fun validateScreenshot(lease: GuiLeaseGate.Lease, observationId: String): GuiResult.Rejected? {
        onMain(); targetRejection(lease)?.let { return it }; screenRejection()?.let { return it }
        selfRootRejection(lease)?.let { return it }
        gate.validateObservation(lease, observationId, generation)?.let { return it }
        val binding = screenshotBinding?.takeIf { it.first == observationId }?.second
            ?: return GuiResult.Rejected("SCREENSHOT_TREE_UNVERIFIABLE")
        if (!binding.verifiable) return GuiResult.Rejected("SCREENSHOT_TREE_UNVERIFIABLE")
        val sampled = sampleTree(lease, retain = false)
        if (sampled !is GuiResult.Value) {
            if (sampled is GuiResult.Rejected && sampled.code == "SELF_APP_TARGET_LOST") return sampled
            return GuiResult.Rejected("SCREENSHOT_TREE_UNVERIFIABLE")
        }
        val actual = sampled.value
        if (!actual.verifiable) return GuiResult.Rejected("SCREENSHOT_TREE_UNVERIFIABLE")
        if (actual.packageName != binding.packageName || actual.windowId != binding.windowId)
            return GuiResult.Rejected("SCREENSHOT_WINDOW_CHANGED")
        if (actual != binding) return GuiResult.Rejected("SCREENSHOT_TREE_CHANGED")
        return null
    }

    @Suppress("DEPRECATION")
    internal fun action(lease: GuiLeaseGate.Lease, action: GuiAction): GuiResult<String> {
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
                if (node == null || !node.refresh()) return GuiResult.Rejected("NODE_STALE")
                if (node.isPassword) return GuiResult.Rejected("PASSWORD_NODE_REFUSED")
                val originalIdentity = identities[action.nodeId]
                    ?: return GuiResult.Rejected("TARGET_IDENTITY_EXCEEDS_BOUND")
                val rect = Rect(); node.getBoundsInScreen(rect)
                if (node.windowId != observed.windowId || node.packageName?.toString().orEmpty() != observed.packageName ||
                    GuiBounds(rect.left, rect.top, rect.right, rect.bottom) != original.bounds ||
                    identity(node) != originalIdentity || !node.isVisibleToUser || !node.isEnabled)
                    return GuiResult.Rejected("NODE_STALE")
            } catch (_: Exception) { return GuiResult.Rejected("NODE_UNAVAILABLE_NOT_EXECUTED") }
        }
        // Recheck active package/window and the live narrow token after node refresh, before delivery.
        try {
            val fetched = externalRoot(lease, current)
            if (fetched !is GuiResult.Value) return fetched as GuiResult.Rejected
            val active = fetched.value
            val sameWindow = try {
                active.windowId == observed.windowId && active.packageName?.toString().orEmpty() == observed.packageName
            } finally { active.recycle() }
            if (!sameWindow) return GuiResult.Rejected("OBSERVATION_STALE")
        } catch (_: Exception) { return GuiResult.Rejected("ACTIVE_WINDOW_UNAVAILABLE_NOT_EXECUTED") }
        val consumed = gate.consumeAction(lease, action, generation)
        if (consumed is GuiResult.Rejected) return consumed
        // No coordinate/ancestor fallback or automatic retry when an advertised action is unsupported.
        return try {
            val accepted = when (action.operation) {
                GuiOperation.TAP -> node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                GuiOperation.LONG_PRESS -> node!!.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                GuiOperation.SCROLL_FORWARD -> node!!.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                GuiOperation.SCROLL_BACKWARD -> node!!.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                GuiOperation.INPUT -> node!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, action.text)
                })
                GuiOperation.BACK -> current.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                GuiOperation.HOME -> current.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                GuiOperation.RECENTS -> current.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                GuiOperation.NOTIFICATIONS -> current.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            }
            GuiResult.Value(if (accepted) "PLATFORM_ACTION_ACCEPTED_NOT_VERIFIED" else "PLATFORM_ACTION_REJECTED")
        } catch (_: Exception) { GuiResult.Unknown("ACTION_DELIVERY_UNCONFIRMED") }
        finally { generation++; clearNodes() }
    }

    /** No capture occurs while observing. API 30+ only; secure windows/platform errors stay errors. */
    internal fun screenshot(lease: GuiLeaseGate.Lease, observationId: String): CompletableFuture<GuiResult<GuiScreenshot>> {
        onMain()
        fun rejected(code: String) = CompletableFuture.completedFuture<GuiResult<GuiScreenshot>>(GuiResult.Rejected(code))
        targetRejection(lease)?.let { return rejected(it.code) }; screenRejection()?.let { return rejected(it.code) }
        selfRootRejection(lease)?.let { return rejected(it.code) }
        gate.validateObservation(lease, observationId, generation)?.let { return rejected(it.code) }
        if (Build.VERSION.SDK_INT < 30) return rejected("SCREENSHOT_UNSUPPORTED_API")
        validateScreenshot(lease, observationId)?.let { return rejected(it.code) }
        val permit = gate.consumeRead(lease, screenshot = true)
        if (permit is GuiResult.Rejected) return rejected(permit.code)
        val current = service ?: return rejected("SERVICE_DISCONNECTED_NOT_EXECUTED")
        captures[lease] = (captures[lease] ?: 0) + 1
        val request = GuiCaptureRequest<GuiScreenshot> {
            if (Looper.myLooper() === Looper.getMainLooper()) captureFinished(lease)
            else main.post { captureFinished(lease) } // no main receipt means no manufactured slot release
        }
        val future = request.future
        request.submit {
            access.capture(current, success = success@ { frame ->
                onMain()
                fun dispose() {
                    // Unconfirmed disposal must NOT manufacture a drain receipt.
                    try { frame.dispose(request) } catch (_: Exception) { /* reservation retained */ }
                }
                try {
                    val rejection = targetRejection(lease) ?: screenRejection() ?: selfRootRejection(lease) ?:
                        gate.validateObservation(lease, observationId, generation)
                    if (future.isDone || rejection != null) {
                        if (rejection != null) future.complete(rejection)
                        dispose(); return@success
                    }
                    encoder.execute {
                        // No lease/tree/service access on this worker. Cancellation does not release resources.
                        val encoded = try {
                            if (future.isDone) GuiResult.Rejected("SCREENSHOT_CANCELLED") else frame.encode(observationId)
                        } catch (_: Exception) { GuiResult.Rejected("SCREENSHOT_UNAVAILABLE") }
                        val posted = try {
                            main.post {
                                try {
                                    if (!future.isDone) {
                                        val invalid = validateScreenshot(lease, observationId)
                                        future.complete(invalid ?: encoded)
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
                onMain(); request.callbackFailure("SCREENSHOT_PLATFORM_ERROR_$code")
            })
        }
        return future
    }
}
