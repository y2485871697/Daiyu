package app.daiyu.android.pet

import android.app.AppOpsManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import app.daiyu.android.domain.Message
import app.daiyu.android.domain.pet.*

/** Single parent-facing API. Create ONE per owner on main, attach once, close on owner destruction.
 * No observer of chat/history and no fabricated completions: parent supplies live persisted transitions.
 * Global window may outlive foreground visibility, never this host. No service/permission launch.
 * Inject an independent model port (AndroidPetModelPort bridges existing transports) and optional voice.
 * Ownership of these pet-only ports transfers here; AutoCloseable ports are closed with the host.
 */
class PetHost internal constructor(
    context: Context,
    private val model: PetModelPort,
    private val voice: PetVoicePort?,
    private val onOpenSettings: () -> Unit,
    windowPort: PetOverlayWindowPort<View>?,
    private val canDrawOverlays: () -> Boolean
) : AutoCloseable {
    constructor(context: Context, model: PetModelPort, voice: PetVoicePort? = null,
        onOpenSettings: () -> Unit) : this(context, model, voice, onOpenSettings, null,
        { Settings.canDrawOverlays(context.applicationContext) })

    private val app = context.applicationContext
    private val ui = Handler(Looper.getMainLooper())
    internal val resources = WhaleMaidResources(app)
    internal val controller = PetController(AndroidPetStore(app), model, AndroidPetScheduler(),
        PetClock { System.currentTimeMillis() }, voice)
    @Volatile private var closed = false
    private var closing = false
    @Volatile private var overlayState = PetOverlayState.DISABLED
    @Volatile private var overlayError: String? = null
    private var foreground = false
    private var mount: FrameLayout? = null
    private var container: FrameLayout? = null
    private var lifecycle: Lifecycle? = null
    private val listeners = linkedSetOf<(PetHostSnapshot) -> Unit>()
    private val appOps = app.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    private val permissionListener = AppOpsManager.OnOpChangedListener { op, packageName ->
        if (op == AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW && (packageName == null || packageName == app.packageName))
            ui.post { if (!closed) sync() }
    }
    private var watchingPermission = false
    private val overlay = PetOverlayHost(app, controller, resources, onOpenSettings,
        windowPort = windowPort, canDrawOverlays = canDrawOverlays) { state, error ->
        val changed = overlayState != state || overlayError != error
        overlayState = state; overlayError = error
        if (changed) {
            // Layout/WM failures may arrive outside a controller update. Publish fallback promptly.
            updateInAppVisibility(controller.snapshot())
            val next = snapshot()
            listeners.toList().forEach { runCatching { it(next) } }
        }
    }
    private val ownerObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> { foreground = true; refreshOverlayPermission() }
            Lifecycle.Event.ON_STOP -> { foreground = false; sync() }
            Lifecycle.Event.ON_DESTROY -> close()
            else -> Unit
        }
    }
    private val observation: PetCancel
    init {
        mainThread()
        // Fail visibly on missing/corrupt art rather than silently replacing the licensed sprite.
        resources.spriteSheet(); resources.cupboard(); PetFood.entries.forEach { resources.food(it.resourceKey) }
        watchingPermission = runCatching {
            appOps.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, app.packageName, permissionListener); true
        }.getOrDefault(false)
        observation = controller.observe { ui.post { if (!closed) sync() } }
    }
    /** Mount a transparent, touch-through in-app layer above the content. Global mode still uses WM.
     * ON_STOP hides in-app art; global art stays until disabled/revoked/ON_DESTROY/close.
     */
    fun attach(container: FrameLayout, owner: LifecycleOwner) {
        mainThread(); check(!closed); check(mount == null) { "PetHost already attached" }
        check(owner.lifecycle.currentState != Lifecycle.State.DESTROYED)
        this.container = container
        mount = FrameLayout(container.context).also { layer ->
            layer.addView(PetViewFactory.createPet(container.context, controller, resources, false, onOpenSettings),
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            container.addView(layer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        lifecycle = owner.lifecycle
        foreground = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        owner.lifecycle.addObserver(ownerObserver)
        sync()
    }
    fun createSettingsView(context: Context): View {
        mainThread(); check(!closed)
        return PetViewFactory.createSettings(context, this)
    }
    fun setEnabled(enabled: Boolean): PetCommandResult {
        mainThread(); if (closed) return PetCommandResult.Rejected("宿主已关闭")
        val result = controller.setEnabled(enabled); sync(); return result
    }
    fun setGlobalVisible(enabled: Boolean): PetCommandResult {
        mainThread(); if (closed) return PetCommandResult.Rejected("宿主已关闭")
        val result = controller.updateSettings(controller.snapshot().state.settings.copy(globalVisible = enabled))
        sync(); return result
    }
    fun updateSettings(settings: PetSettings): PetCommandResult {
        mainThread(); if (closed) return PetCommandResult.Rejected("宿主已关闭")
        val result = controller.updateSettings(settings); sync(); return result
    }
    fun feed(food: PetFood): PetCommandResult {
        mainThread(); if (closed) return PetCommandResult.Rejected("宿主已关闭")
        return controller.feed(food)
    }
    /** Call only AFTER the same assistant owner's actual RUNNING -> COMPLETED row commits.
     * before/after are existing public Message objects, not synthetic snapshots.
     * Safe from worker threads; domain checks status, identity and persisted run deduplication.
     */
    fun onRunTransition(before: Message, after: Message, title: String): PetCommandResult =
        if (closed) PetCommandResult.Rejected("宿主已关闭") else controller.onRunTransition(before, after, title)

    /** Queries Settings.canDrawOverlays; never launches settings or grants anything.
     * Called on owner start and AppOps changes; parent can call after its own permission UI returns.
     */
    fun refreshOverlayPermission() { mainThread(); if (!closed) sync() }
    fun snapshot(): PetHostSnapshot = PetHostSnapshot(controller.snapshot(),
        canDrawOverlays(), overlayState, overlayError, closed)
    fun observe(listener: (PetHostSnapshot) -> Unit): PetCancel {
        mainThread()
        if (!closed) listeners.add(listener)
        runCatching { listener(snapshot()) }
        return PetCancel {
            if (Looper.myLooper() == Looper.getMainLooper()) listeners.remove(listener)
            else ui.post { listeners.remove(listener) }
        }
    }
    private fun sync() {
        mainThread(); if (closed) return
        val current = controller.snapshot()
        // Until attach, even persisted enabled/global settings cannot create an unowned window.
        if (mount != null) overlay.sync(current)
        updateInAppVisibility(current)
        val next = snapshot()
        listeners.toList().forEach { runCatching { it(next) } }
    }
    private fun updateInAppVisibility(current: PetSnapshot) {
        val inApp = !closed && foreground && !current.closed && current.state.settings.enabled &&
            overlayState != PetOverlayState.VISIBLE && current.error?.contains("存储") != true
        mount?.visibility = if (inApp) View.VISIBLE else View.GONE
    }
    override fun close() {
        mainThread(); if (closing) return
        // Overlay state and final host notifications are synchronous and may call close again.
        // Keep the fence up through both cleanup and notification, including retry attempts.
        closing = true
        try {
            if (closed) {
                // Runtime/subscriptions are already released, but UNKNOWN still owns a root.
                if (overlayState != PetOverlayState.CLOSED) overlay.close()
                return
            }
            closed = true
            lifecycle?.removeObserver(ownerObserver); lifecycle = null
            if (watchingPermission) runCatching { appOps.stopWatchingMode(permissionListener) }
            watchingPermission = false
            observation.cancel(); ui.removeCallbacksAndMessages(null)
            overlay.close(); controller.close()
            runCatching { (model as? AutoCloseable)?.close() }
            runCatching { (voice as? AutoCloseable)?.close() }
            mount?.let { container?.removeView(it) }; mount = null; container = null
            val next = snapshot(); listeners.toList().forEach { runCatching { it(next) } }; listeners.clear()
        } finally {
            closing = false
        }
    }
    private fun mainThread() { check(Looper.myLooper() == Looper.getMainLooper()) { "PetHost UI methods require main thread" } }
}

data class PetHostSnapshot(val pet: PetSnapshot, val canDrawOverlays: Boolean,
    val overlayState: PetOverlayState, val overlayError: String? = null, val closed: Boolean = false)
