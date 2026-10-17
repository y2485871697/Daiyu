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

/** System-bound only. No root, shell, Shizuku, polling, hidden APIs, or automatic gestures. */
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

/** All Android access and all lease transitions run on main. Worker adapters post cancellable work. */
internal object GuiRuntime {
    val main = Handler(Looper.getMainLooper())
    private val gate = GuiLeaseGate(SystemClock::elapsedRealtime)
    private var service: GuiAccessibilityService? = null
    private var generation = 0L
    private var confirmation: GuiConfirmationOverlay? = null
    internal fun showConfirmation(session: GuiRunSession, operation: GuiOperation, text: String?,
        ownerCurrent: () -> Boolean, onNotExecuted: (String) -> Unit, onRevoked: () -> Unit,
        deliver: (GuiConfirmedDelivery) -> Boolean): GuiResult<Unit> {
        onMain(); gate.rejection(session.lease)?.let { return it }; screenRejection()?.let { return it }
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
    fun connected(value: GuiAccessibilityService) {
        onMain(); confirmation?.revoke()
        clearNodes(); service = value; generation++; gate.connect()
    }
    fun disconnected(value: GuiAccessibilityService) {
        onMain()
        if (service === value) {
            confirmation?.revoke()
            clearNodes(); service = null; generation++; gate.disconnect()
        }
    }
    fun changed(value: GuiAccessibilityService) { onMain(); if (service === value) { generation++; clearNodes() } }
    @Suppress("DEPRECATION") private fun clearNodes() {
        retained.values.forEach { it.recycle() }; retained.clear(); identities.clear()
    }
    private fun screenRejection(): GuiResult.Rejected? {
        val current = service ?: return GuiResult.Rejected("SERVICE_DISCONNECTED_NOT_EXECUTED")
        val enabled = try { GuiSystemSettings.enabledBySystem(current) } catch (_: Exception) { false }
        if (!enabled) {
            disconnected(current)
            return GuiResult.Rejected("SERVICE_PERMISSION_REVOKED_NOT_EXECUTED")
        }
        if (current.getSystemService(KeyguardManager::class.java).isKeyguardLocked ||
            !current.getSystemService(PowerManager::class.java).isInteractive)
            return GuiResult.Rejected("SCREEN_LOCKED_NOT_EXECUTED")
        return null
    }
    /** Called only in response to a trusted user operation, never from tool preparation. */
    fun openForUser(owner: GuiOwner): GuiResult<GuiRunSession> {
        onMain(); screenRejection()?.let { return it }
        if (captures.isNotEmpty() || closingLeases.isNotEmpty()) return GuiResult.Rejected("FOREGROUND_DRAIN_PENDING_NOT_EXECUTED")
        return when (val acquired = gate.acquire(owner)) {
            is GuiResult.Value -> GuiResult.Value(GuiRunSession(acquired.value))
            is GuiResult.Rejected -> acquired
            is GuiResult.Unknown -> acquired
        }
    }
    internal fun approveReads(lease: GuiLeaseGate.Lease, approval: GuiReadApproval): GuiResult<Unit> {
        onMain(); return gate.approveReads(lease, approval)
    }
    internal fun approveAction(lease: GuiLeaseGate.Lease, action: GuiAction): GuiResult<Unit> {
        onMain(); return gate.approveAction(lease, action, generation)
    }
    internal fun approvedActions(lease: GuiLeaseGate.Lease): Set<GuiAction> { onMain(); return gate.approvedActions(lease) }
    private val captures = mutableMapOf<GuiLeaseGate.Lease, Int>()
    private val draining = mutableMapOf<GuiLeaseGate.Lease, () -> Unit>()
    private val closingLeases = mutableSetOf<GuiLeaseGate.Lease>()
    internal fun close(lease: GuiLeaseGate.Lease, onReleased: () -> Unit) {
        onMain(); closingLeases.add(lease)
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

    /** Accessibility overlays and our Activity are never a valid external target. */
    @Suppress("DEPRECATION")
    private fun externalRoot(current: GuiAccessibilityService): GuiResult<AccessibilityNodeInfo> {
        val root = try { current.rootInActiveWindow } catch (_: Exception) { null }
            ?: return GuiResult.Rejected("NO_ACTIVE_EXTERNAL_WINDOW")
        val valid = try {
            val window = root.window
            try {
                val pkg = root.packageName?.toString().orEmpty()
                pkg.isNotBlank() && pkg.length <= 256 && pkg != current.packageName &&
                    window != null && window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION &&
                    window.isActive && (Build.VERSION.SDK_INT < 30 || window.displayId == Display.DEFAULT_DISPLAY)
            } finally { window?.recycle() }
        } catch (_: Exception) { false }
        if (!valid) { root.recycle(); return GuiResult.Rejected("EXTERNAL_APPLICATION_ROOT_REQUIRED_NOT_EXECUTED") }
        return GuiResult.Value(root)
    }

    /** Reacquire the real external tree; do NOT publish a new observation/clear the approved one.
     * A bounded complete traversal must find exactly one matching target. No coordinate fallback. */
    @Suppress("DEPRECATION")
    internal fun validateExactTarget(lease: GuiLeaseGate.Lease, action: GuiAction): GuiResult<Unit> {
        onMain(); gate.validateObservation(lease, action.observationId, generation)?.let { return it }
        screenRejection()?.let { return it }
        val observed = lease.observation!!
        val fetched = externalRoot(service!!)
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

    @Suppress("DEPRECATION")
    internal fun observe(lease: GuiLeaseGate.Lease): GuiResult<GuiObservation> {
        onMain(); gate.rejection(lease)?.let { return it }; screenRejection()?.let { return it }
        val permit = gate.consumeRead(lease, screenshot = false)
        if (permit !is GuiResult.Value) return permit as GuiResult.Rejected
        clearNodes()
        val fetched = externalRoot(service!!)
        if (fetched !is GuiResult.Value) return fetched as GuiResult.Rejected
        val source = fetched.value
        val startGeneration = generation
        val result = mutableListOf<GuiNode>()
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
            retained[id] = node
            identities[id] = if (node.isPassword) null else identity(node)
            val rect = Rect(); node.getBoundsInScreen(rect)
            result += GuiNode(id, parent, GuiBounds(rect.left, rect.top, rect.right, rect.bottom),
                if (node.isPassword) "" else bounded(node.text), if (node.isPassword) "" else bounded(node.contentDescription),
                bounded(node.className), bounded(node.viewIdResourceName), node.isClickable, node.isLongClickable,
                node.isScrollable, node.isEditable, node.isEnabled, node.isPassword)
            val count = node.childCount
            if (count > 64) truncated = true
            for (index in 0 until minOf(count, 64)) {
                if (result.size >= 128) { truncated = true; break }
                node.getChild(index)?.let { visit(it, id, depth + 1) }
            }
        }
        return try {
            if (Build.VERSION.SDK_INT >= 30) {
                val windowInfo = source.window
                val displayId = windowInfo?.displayId
                windowInfo?.recycle()
                if (displayId != Display.DEFAULT_DISPLAY) {
                    source.recycle()
                    return GuiResult.Rejected("NON_DEFAULT_DISPLAY_REFUSED")
                }
            }
            val pkg = source.packageName?.take(256)?.toString().orEmpty()
            val window = source.windowId
            visit(source, null, 0)
            val observation = GuiObservation(UUID.randomUUID().toString(), pkg, window, startGeneration,
                SystemClock.elapsedRealtime(), result, truncated)
            gate.publish(lease, observation)
        } catch (_: Exception) {
            clearNodes(); GuiResult.Rejected("OBSERVATION_UNAVAILABLE")
        }
    }

    @Suppress("DEPRECATION")
    internal fun action(lease: GuiLeaseGate.Lease, action: GuiAction): GuiResult<String> {
        onMain(); gate.rejection(lease)?.let { return it }; screenRejection()?.let { return it }
        gate.validateObservation(lease, action.observationId, generation)?.let { return it }
        val current = service ?: return GuiResult.Rejected("SERVICE_DISCONNECTED_NOT_EXECUTED")
        val verified = validateExactTarget(lease, action)
        if (verified is GuiResult.Rejected) return verified
        val observed = lease.observation!!
        try {
            val active = current.rootInActiveWindow ?: return GuiResult.Rejected("NO_ACTIVE_WINDOW")
            val sameWindow = try {
                active.windowId == observed.windowId && active.packageName?.toString().orEmpty() == observed.packageName
            } finally { active.recycle() }
            if (!sameWindow) return GuiResult.Rejected("OBSERVATION_STALE")
        } catch (_: Exception) { return GuiResult.Rejected("ACTIVE_WINDOW_UNAVAILABLE_NOT_EXECUTED") }
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
        gate.rejection(lease)?.let { return rejected(it.code) }; screenRejection()?.let { return rejected(it.code) }
        gate.validateObservation(lease, observationId, generation)?.let { return rejected(it.code) }
        if (Build.VERSION.SDK_INT < 30) return rejected("SCREENSHOT_UNSUPPORTED_API")
        val permit = gate.consumeRead(lease, screenshot = true)
        if (permit is GuiResult.Rejected) return rejected(permit.code)
        val current = service ?: return rejected("SERVICE_DISCONNECTED_NOT_EXECUTED")
        captures[lease] = (captures[lease] ?: 0) + 1
        val request = GuiCaptureRequest<GuiScreenshot> { captureFinished(lease) }
        val future = request.future
        request.submit {
            current.takeScreenshot(Display.DEFAULT_DISPLAY, current.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    val buffer = screenshot.hardwareBuffer
                    var hardware: Bitmap? = null
                    var software: Bitmap? = null
                    try {
                        gate.validateObservation(lease, observationId, generation)?.let { future.complete(it); return }
                        screenRejection()?.let { future.complete(it); return }
                        if (buffer.width > 2048 || buffer.height > 4096 || buffer.width.toLong() * buffer.height > 8_388_608) {
                            future.complete(GuiResult.Rejected("SCREENSHOT_DIMENSIONS_EXCEED_LIMIT")); return
                        }
                        hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                        software = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                        val image = software ?: run { future.complete(GuiResult.Rejected("SCREENSHOT_ENCODING_UNAVAILABLE")); return }
                        val bytes = object : ByteArrayOutputStream() {
                            override fun write(b: ByteArray, off: Int, len: Int) {
                                check(count.toLong() + len <= 2_097_152) { "screenshot_size_limit" }; super.write(b, off, len)
                            }
                            override fun write(b: Int) { check(count < 2_097_152); super.write(b) }
                        }
                        if (!image.compress(Bitmap.CompressFormat.PNG, 100, bytes)) {
                            future.complete(GuiResult.Rejected("SCREENSHOT_ENCODING_UNAVAILABLE")); return
                        }
                        future.complete(GuiResult.Value(GuiScreenshot(observationId, image.width, image.height, bytes.toByteArray())))
                    } catch (_: Exception) { future.complete(GuiResult.Rejected("SCREENSHOT_UNAVAILABLE")) }
                    finally {
                        // Attempt every disposal even if another one throws. A failed disposal
                        // must retain the slot: callback arrival alone is not a resource receipt.
                        request.disposeSuccessResources(
                            software = { software?.recycle(); Unit },
                            hardware = { hardware?.recycle(); Unit },
                            buffer = { buffer.close() },
                        )
                    }
                }
                override fun onFailure(errorCode: Int) {
                    request.callbackFailure("SCREENSHOT_PLATFORM_ERROR_$errorCode")
                }
            })
        }
        return future
    }
}
