package app.daiyu.android.haptics

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.View
import app.daiyu.android.domain.haptics.*
import java.util.Collections
import java.util.IdentityHashMap

/** One process-local instance shared by hosts and stream producers. No service or global singleton. */
class HapticsRuntime internal constructor(
    val store: LocalHapticSettingsStore,
    private val portFactory: (View) -> HapticPort,
) : AutoCloseable {
    constructor(store: LocalHapticSettingsStore) : this(store, ::AndroidHapticPort)

    private val handler = Handler(Looper.getMainLooper())
    private val engine = HapticEngine(HapticClock(SystemClock::uptimeMillis))
    private var closed = false
    private val tick = Runnable { if (!closed) { engine.drain(); schedule() } }
    // Ordinary UI controls share the same confirmed settings, but never claim a chat conversation.
    private var activeUiPort: HapticPort? = null
    // The engine only arbitrates its Sessions. UI playback must share the same motor owner.
    private var latestPlaybackToken: Any? = null

    private fun ownedPort(view: View): HapticPort {
        val delegate = portFactory(view)
        return object : HapticPort {
            private var token: Any? = null

            override fun play(request: HapticRequest): HapticDelivery {
                val result = delegate.play(request)
                if (result == HapticDelivery.PLAYED) {
                    token = Any()
                    latestPlaybackToken = token
                }
                return result
            }

            override fun cancel() {
                val owned = token ?: return
                token = null
                if (latestPlaybackToken !== owned) return
                // Revoke before calling the device, even if cancellation throws.
                latestPlaybackToken = null
                delegate.cancel()
            }
        }
    }
    private val subscription = store.observe { value -> dispatch {
        if (!value.touch) cancelUi()
        engine.updateSettings(value)
    } }
    private var foregroundAdapter: ForegroundAdapter? = null

    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper()) {
        "Haptics lifecycle and View calls must run on the main thread"
    }
    private fun dispatch(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (!closed) { block(); schedule() }
        } else handler.post { if (!closed) { block(); schedule() } }
    }
    private fun schedule() {
        handler.removeCallbacks(tick)
        engine.nextDueMillis()?.let { handler.postAtTime(tick, it) }
    }

    /** Confirmed click callbacks only. This path cannot emit generation or bypass app/system gates. */
    fun uiTouch(view: View, kind: HapticKind): HapticDelivery? {
        checkMain()
        if (closed || !engine.foreground || !store.read().touch || !view.isAttachedToWindow ||
            view.windowVisibility != View.VISIBLE) return null
        val port = ownedPort(view)
        return port.play(HapticRequest(kind, store.read().intensity)).also {
            if (it == HapticDelivery.PLAYED) activeUiPort = port
        }
    }

    private fun cancelUi() { activeUiPort?.cancel(); activeUiPort = null }

    /** Attach once per host instance; duplicate screens must not share a Session. */
    fun attach(view: View, conversationId: String, resumed: Boolean = false): Session {
        checkMain()
        check(!closed)
        return Session(engine.attach(conversationId, ownedPort(view), resumed))
    }

    inner class Session internal constructor(private val owner: HapticEngine.Owner) : AutoCloseable {
        @Volatile private var disposed = false
        fun lifecycle(resumed: Boolean, visibleOutput: Boolean, enabled: Boolean = true) {
            checkMain()
            if (!disposed && !closed) {
                engine.lifecycle(owner, resumed, visibleOutput, enabled)
                schedule()
            }
        }
        fun touch(kind: HapticKind = HapticKind.CLICK): HapticDelivery? {
            checkMain()
            return if (disposed || closed) null else engine.touch(owner, kind)
        }
        fun preview(intensity: HapticIntensity): HapticDelivery? {
            checkMain()
            return if (disposed || closed) null else engine.preview(owner, intensity)
        }
        fun agentAction(action: HapticAgentAction): HapticDelivery? {
            checkMain()
            return if (disposed || closed) null else engine.agentAction(owner, action)
        }
        fun selectionStart() { checkMain(); if (!disposed && !closed) engine.selectionStart(owner) }
        /** UI reveal clock only; network chunks must use backgroundOutput instead. */
        fun visibleAdvance() { checkMain(); if (!disposed && !closed) engine.visibleAdvance(owner) }
        fun backgroundOutput(graphemes: Int, reasoning: Boolean = false) = dispatch {
            if (!disposed) engine.backgroundOutput(owner, graphemes, reasoning)
        }
        fun toolAppeared(id: String) = dispatch { if (!disposed) engine.toolAppeared(owner, id) }
        fun reasoningCompleted(id: String) = dispatch { if (!disposed) engine.reasoningCompleted(owner, id) }
        override fun close() {
            checkMain()
            if (disposed) return
            disposed = true
            engine.detach(owner)
            schedule()
        }
    }

    /** Installs primary-display visibility tracking; uninstalled runtimes retain foreground=true. */
    fun installForeground(application: Application) {
        checkMain()
        check(!closed)
        if (foregroundAdapter != null) return
        foregroundAdapter = ForegroundAdapter(application).also { it.install() }
    }

    private inner class ForegroundAdapter(private val app: Application) : Application.ActivityLifecycleCallbacks {
        private val visible = Collections.newSetFromMap(IdentityHashMap<Activity, Boolean>())
        fun install() {
            engine.setForeground(false)
            app.registerActivityLifecycleCallbacks(this)
        }
        fun uninstall() { app.unregisterActivityLifecycleCallbacks(this); visible.clear() }
        private fun publish() {
            if (visible.isEmpty()) cancelUi()
            engine.setForeground(visible.isNotEmpty())
            schedule()
        }
        override fun onActivityStarted(activity: Activity) {
            @Suppress("DEPRECATION")
            val id = if (Build.VERSION.SDK_INT >= 30) activity.display?.displayId
                else activity.windowManager.defaultDisplay.displayId
            if (id == null || id == Display.DEFAULT_DISPLAY) visible.add(activity)
            publish()
        }
        override fun onActivityStopped(activity: Activity) { visible.remove(activity); publish() }
        override fun onActivityDestroyed(activity: Activity) { visible.remove(activity); publish() }
        override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    }

    override fun close() {
        checkMain()
        if (closed) return
        closed = true
        subscription.close()
        cancelUi()
        foregroundAdapter?.uninstall()
        handler.removeCallbacksAndMessages(null)
        engine.close()
    }
}
