package app.daiyu.android.pet

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import app.daiyu.android.domain.Message
import app.daiyu.android.domain.pet.PetCancel
import app.daiyu.android.domain.pet.PetCommandResult
import app.daiyu.android.domain.pet.PetModelPort
import app.daiyu.android.domain.pet.PetSettings
import app.daiyu.android.domain.pet.PetVoicePort
import java.util.concurrent.atomic.AtomicBoolean

private fun systemOverlayPermission(app: Context): () -> Boolean = { Settings.canDrawOverlays(app) }

/** A LIVE, successfully persisted assistant-owner transition, not a loaded-history diff.
 * The source must supply the actual rows before/after the same commit, never synthesize RUNNING.
 */
data class PetAssistantTransition(val before: Message, val after: Message, val title: String)

/** Subscribe without replay. Emit only after commit succeeds. Cancellation must release the listener.
 * Callbacks may be on workers; the application holder fences queued callbacks on cancellation.
 */
fun interface PetAssistantTransitionSource {
    fun subscribe(listener: (PetAssistantTransition) -> Unit): PetCancel
}

/** One instance retained by the application's composition root, NOT by an Activity/Composable.
 *
 * Activity destruction detaches only the in-app layer; it does not destroy the runtime/global root.
 * No service, permission intent, main-model fallback or implicit permission authorization is used.
 * [close] ends this instance permanently. Keep it for repeated close if WM cleanup is UNKNOWN;
 * do not replace it with a fresh instance until [cleanupComplete] is true.
 *
 * Construct and call UI/settings methods on main; onModelConfigurationChanged is worker-safe.
 * Returned leases may be cancelled anywhere.
 * The resolver must independently resolve/validate assistantId + providerId + modelId, including
 * their relationship, and return null if any record/credential is unavailable. ModelSettings.model
 * is the selected record's API name, not the model record ID. Ownership of voice transfers here.
 */
class PetApplicationHost internal constructor(
    context: Context,
    model: PetModelPort,
    voice: PetVoicePort?,
    onOpenSettings: () -> Unit,
    windowPort: PetOverlayWindowPort<View>?,
    private val systemCanDrawOverlays: () -> Boolean
) : AutoCloseable {
    constructor(context: Context, resolver: PetModelResolver, voice: PetVoicePort? = null,
        onOpenSettings: () -> Unit) : this(context.applicationContext, AndroidPetModelPort(resolver),
        voice, onOpenSettings, null, systemOverlayPermission(context.applicationContext))

    private val ui = Handler(Looper.getMainLooper())
    private var closing = false
    @Volatile var closed: Boolean = false
        private set
    /** Session-local opt-in; persisted globalVisible alone is never permission to open a window. */
    val overlayAllowed: Boolean get() = overlayGateOpen
    @Volatile private var overlayGateOpen = false

    /** Use this exact instance for PetSettingsScreen; a settings screen must not create a host.
     * snapshot.canDrawOverlays reports the effective (explicit gate AND system permission) value.
     */
    val host = PetHost(context.applicationContext, model, voice, onOpenSettings, windowPort,
        { overlayAllowed && runCatching { systemCanDrawOverlays() }.getOrDefault(false) })
    private val modelAdapter = model as? AndroidPetModelPort
    init { modelAdapter?.bindOwner(host.controller) }
    val cleanupComplete: Boolean get() = closed && host.snapshot().overlayState == PetOverlayState.CLOSED
    private var attachment: Any? = null
    private var completionBinding: CompletionBinding? = null

    private class CompletionBinding {
        val active = AtomicBoolean(true)
        var subscription: PetCancel? = null // main only
        fun retire() {
            active.set(false)
            val old = subscription
            subscription = null
            runCatching { old?.cancel() }
        }
    }

    /** Rebind after configuration change. Cancelling an old lease cannot detach a newer mount. */
    fun attach(container: FrameLayout, owner: LifecycleOwner): PetCancel {
        mainThread(); check(!closed)
        check(owner.lifecycle.currentState != Lifecycle.State.DESTROYED)
        val ticket = Any()
        attachment = ticket
        host.detach()
        host.attach(container, owner, closeOnOwnerDestroy = false)
        val active = AtomicBoolean(true)
        return PetCancel {
            if (active.compareAndSet(true, false)) onMain {
                if (!closed && attachment === ticket) {
                    attachment = null
                    host.detach()
                }
            }
        }
    }

    /** Explicit parent policy/consent gate, independent of the saved globalVisible preference.
     * true still requires Settings.canDrawOverlays. false removes an existing window even when
     * no Activity is attached. This method never asks for or grants the Android permission.
     */
    fun setOverlayAllowed(allowed: Boolean) {
        mainThread(); if (closed) return
        overlayGateOpen = allowed
        host.refreshOverlayPermission()
    }

    /** Call after returning from a parent-owned permission UI; does not open the explicit gate. */
    fun refreshOverlayPermission() { mainThread(); if (!closed) host.refreshOverlayPermission() }

    fun updateSettings(settings: PetSettings): PetCommandResult {
        mainThread()
        return host.updateSettings(settings)
    }

    /** Registry/credential edits can change the binding WITHOUT changing the three saved IDs.
     * Worker-safe and synchronous: retire adapter authority AND controller effects before returning.
     * Transport cancellation runs asynchronously; this does not wait for physical I/O drain.
     * The shared controller monitor prevents a checked callback waiting on that monitor from later
     * delivering a stale reaction/usage receipt. Only visual synchronization is deferred to main;
     * that queued work must never invalidate a request accepted under the new configuration.
     */
    fun onModelConfigurationChanged(): PetCommandResult {
        val result = synchronized(host.controller) {
            if (closed) return PetCommandResult.Rejected("宿主已关闭")
            modelAdapter?.invalidateConfiguration()
            host.controller.setEnabled(host.controller.snapshot().state.settings.enabled)
        }
        onMain { if (!closed) host.refreshOverlayPermission() }
        return result
    }

    /** Bind the real post-commit producer, not ChatController's generic UI-change notification.
     * Rebinding retires the old source. Neither its late events nor its late cancellation may
     * deliver to/close a new binding. Domain validation and persisted run dedup remain in PetHost.
     */
    fun connectAssistantTransitions(source: PetAssistantTransitionSource): PetCancel {
        mainThread(); check(!closed)
        val binding = CompletionBinding()
        val old = completionBinding
        completionBinding = binding
        old?.retire()
        // A source's cancellation may synchronously close/rebind the application holder.
        if (!closed && completionBinding === binding && binding.active.get()) {
            try {
                val subscription = source.subscribe { event ->
                    if (binding.active.get()) ui.post {
                        if (!closed && completionBinding === binding && binding.active.get())
                            host.onRunTransition(event.before, event.after, event.title)
                    }
                }
                if (!closed && completionBinding === binding && binding.active.get())
                    binding.subscription = subscription
                else runCatching { subscription.cancel() }
            } catch (failure: Exception) {
                binding.retire()
                if (completionBinding === binding) completionBinding = null
                throw failure
            }
        } else binding.retire()
        return PetCancel {
            // Fence immediately even when cancellation comes from a worker before main drains.
            if (binding.active.compareAndSet(true, false)) onMain {
                if (completionBinding === binding) completionBinding = null
                binding.retire()
            }
        }
    }

    override fun close() {
        mainThread(); if (closing) return
        closing = true
        try {
            closed = true
            overlayGateOpen = false
            attachment = null
            val binding = completionBinding
            completionBinding = null
            binding?.retire()
            ui.removeCallbacksAndMessages(null)
            // Do not null host: UNKNOWN root ownership survives, and repeated close retries it.
            host.close()
        } finally { closing = false }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else ui.post { action() }
    }
    private fun mainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "PetApplicationHost requires main thread" }
    }
}
