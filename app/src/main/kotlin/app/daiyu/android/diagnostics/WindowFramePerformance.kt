package app.daiyu.android.diagnostics

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.FrameMetrics
import android.view.View
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import app.daiyu.android.ui.navigation.PageBackRouteKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.WeakHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** One optional worker per resumed Activity, shared by its main and dialog windows. */
internal class WindowFramePerformance private constructor(
    private val activity: ComponentActivity, private val store: DiagnosticLogStore,
) {
    internal fun backOwner() = BackPerformanceRecorder.Owner(this) { delay, work ->
        handler?.postDelayed({ work() }, delay); Unit
    }
    private val log = store.frames
    private val diagnostic = app.daiyu.android.BuildConfig.JANK_DIAGNOSTICS
    private val windows = LinkedHashMap<Window, String>()
    private val sessions = LinkedHashMap<Window, Session>() // Main thread owns listener membership.
    private val popupLeases = LinkedHashMap<Window, PopupPerformanceCapture.Lease>()
    private var thread: HandlerThread? = null
    @Volatile private var handler: Handler? = null
    private var route = "startup"
    @Volatile private var scrollPhase = BackPerformanceRecorder.Scroll.IDLE
    internal fun scroll(phase: BackPerformanceRecorder.Scroll) { scrollPhase = phase }
    internal fun requestPopup(type: PopupPerformanceCapture.Type) {
        if (resumed && !closed) RuntimeDiagnostics.popupRequest(type)
    }
    private val contexts = LinkedHashMap<Window, LinkedHashMap<Any, String>>()
    private fun path(window: Window) = (listOf(route) + (contexts[window]?.values?.toList() ?: emptyList())).joinToString("/")
    internal fun context(window: Window, token: Any, label: String?) {
        if (label == null) {
            val labels = contexts[window] ?: return
            labels.remove(token)
            if (labels.isEmpty()) contexts.remove(window)
        } else contexts.getOrPut(window) { LinkedHashMap() }[token] = label
        val current = path(window)
        val now = System.nanoTime()
        val session = sessions[window]
        handler?.post { session?.timeline?.change(now, current); session?.batch?.flush("context") }
    }
    private var resumed = false
    private var enabled = true // Main-window automatic back capture is available in ordinary Release.
    private var closed = false
    @Volatile private var retired: CountDownLatch? = null

    private inner class Session(val window: Window, val kind: String) {
        val timeline = PerformanceRouteTimeline()
        val incident: JankIncidentBuffer = JankIncidentBuffer(
            emit = { rows, complete -> JankDiagnostics.save(activity.applicationContext, kind, rows, complete) },
            trigger = {
                JankDiagnostics.trigger()
                handler?.postDelayed({ incident.checkpoint() }, 2000)
            },
        )
        val batch = PerformanceFrameBatch({ epoch, row ->
            row.put("window", kind).put("sdk", Build.VERSION.SDK_INT)
            row.put("app_version", app.daiyu.android.BuildConfig.VERSION_NAME)
            row.put("version_code", app.daiyu.android.BuildConfig.VERSION_CODE)
            row.put("budget_source", if (Build.VERSION.SDK_INT >= 31) "deadline_or_observed_refresh_period" else "observed_refresh_period")
            log.append(epoch, row)
        })
        @Volatile var refreshHz = refreshRate(window)
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, drops ->
            val epoch = log.captureEpoch()
            val automaticBack = kind == "main" && BackPerformance.recorder.observing(this@WindowFramePerformance)
            val popupWindow = if (kind == "main") null else window
            val automaticPopup = PopupPerformance.capture.observing(this@WindowFramePerformance, popupWindow)
            if (epoch == null && !diagnostic && !automaticBack && !automaticPopup) return@OnFrameMetricsAvailableListener
            try {
            val intended = metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
            val vsync = intended.takeIf { it > 0 } ?: System.nanoTime()
            val deadline = if (Build.VERSION.SDK_INT >= 31) metrics.getMetric(FrameMetrics.DEADLINE) else -1L
            val budget = deadline.takeIf { it > 0 } ?: if (refreshHz > 0) (1e9 / refreshHz).toLong() else -1L
            val stages = longArrayOf(
                metrics.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION), metrics.getMetric(FrameMetrics.INPUT_HANDLING_DURATION),
                metrics.getMetric(FrameMetrics.ANIMATION_DURATION), metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION),
                metrics.getMetric(FrameMetrics.DRAW_DURATION), metrics.getMetric(FrameMetrics.SYNC_DURATION),
                metrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION), metrics.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION),
                if (Build.VERSION.SDK_INT >= 31) metrics.getMetric(FrameMetrics.GPU_DURATION) else -1L)
            val frame = PerformanceFrame(vsync,
                metrics.getMetric(FrameMetrics.TOTAL_DURATION), budget, refreshHz, stages, drops.coerceAtLeast(0),
                metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L)
            if (automaticBack) BackPerformance.safely {
                BackPerformance.recorder.frame(vsync, frame.totalNs, budget, refreshHz.toFloat(), stages, host = this@WindowFramePerformance)
            }
            if (automaticPopup) BackPerformance.safely {
                PopupPerformance.capture.frame(this@WindowFramePerformance, popupWindow, vsync,
                    frame.totalNs, budget, refreshHz.toFloat(), stages, scrollPhase)
            }
            if (epoch != null || diagnostic) {
                val page = timeline.pageAt(vsync)
                if (epoch != null) batch.add(epoch, page, frame)
                if (diagnostic) incident.add(page, frame)
            }
            } catch (_: RuntimeException) { log.reportCaptureFailure() }
        }
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            sessions.values.forEach { it.refreshHz = refreshRate(it.window) }
            val active = sessions.values.toList()
            handler?.post { active.forEach { it.batch.flush("interval") } }
            if (resumed && enabled && !closed) activity.window.decorView.postDelayed(this, 1000)
        }
    }

    fun page(key: Any) {
        val next = if (key is PageBackRouteKey)
            (listOf(PerformanceRoute.part(key.root)) + key.path.map(PerformanceRoute::part)).joinToString("/")
            else PerformanceRoute.part(key)
        if (route == next) return
        route = next
        val now = System.nanoTime()
        val active = sessions.values.toList()
        val paths = active.map { it to path(it.window) }
        handler?.post { paths.forEach { (session, path) -> session.timeline.change(now, path); session.batch.flush("route") } }
    }

    private fun attach(window: Window, kind: String) {
        if (sessions.containsKey(window) || !resumed || !enabled || closed) return
        if (kind != "main" && !diagnostic && log.captureEpoch() == null &&
            !PopupPerformance.capture.observing(this, window)) return
        val target = handler ?: HandlerThread("daiyu-frame-metrics").also { it.start(); thread = it }
            .let { Handler(it.looper).also { worker -> handler = worker } }
        val session = Session(window, kind)
        val now = System.nanoTime()
        session.timeline.change(now, path(window))
        try {
            window.addOnFrameMetricsAvailableListener(session.listener, target)
            sessions[window] = session
        } catch (_: RuntimeException) {
            // A detached/OEM-rejected window is reported, never a reason to break its UI.
            log.append(log.captureEpoch() ?: return, org.json.JSONObject().put("page", route)
                .put("window", kind).put("capture_unavailable", true).put("time_ms", System.currentTimeMillis()))
        }
    }
    private fun detach(window: Window) {
        val session = sessions.remove(window) ?: return
        try { window.removeOnFrameMetricsAvailableListener(session.listener) }
        catch (_: RuntimeException) { /* Window may already have been removed by the platform. */ }
        handler?.post { session.batch.flush("window_detached"); session.incident.flush() }
    }
    private fun reconcile() {
        activity.window.decorView.removeCallbacks(heartbeat)
        if (enabled && resumed && !closed) {
            windows.forEach { (window, kind) -> attach(window, kind) }
            activity.window.decorView.postDelayed(heartbeat, 1000)
        } else {
            sessions.keys.toList().forEach(::detach)
            if (closed) {
                val old = thread
                val done = CountDownLatch(1)
                retired = done
                fun releaseHook() { Handler(Looper.getMainLooper()).post {
                    log.unregisterCapture(this@WindowFramePerformance)
                } }
                if (handler?.post { done.countDown(); releaseHook(); old?.quitSafely() } != true) {
                    done.countDown(); releaseHook()
                }
                handler = null; thread = null
            }
        }
    }
    internal fun registerWindow(window: Window, kind: String,
        automaticPopup: PopupPerformanceCapture.Type? = null): PopupPerformanceCapture.Lease? {
        windows[window] = kind
        val lease = if (automaticPopup != null && resumed && !closed) PopupPerformance.capture.start(
            this, window, automaticPopup, scrollPhase,
            schedule = { delay, work -> activity.window.decorView.postDelayed({ work() }, delay); Unit },
            onEnded = { if (!diagnostic && log.captureEpoch() == null) detach(window) }) else null
        if (lease != null) popupLeases[window] = lease
        attach(window, kind)
        return lease
    }
    internal fun unregisterWindow(window: Window, lease: PopupPerformanceCapture.Lease? = null) {
        if (lease != null) {
            PopupPerformance.capture.close(lease)
            if (popupLeases[window] !== lease) return
            popupLeases.remove(window)
        } else if (popupLeases.containsKey(window)) return
        windows.remove(window); contexts.remove(window); detach(window)
    }

    companion object {
        private val hosts = WeakHashMap<Activity, WindowFramePerformance>()
        fun install(activity: ComponentActivity, store: DiagnosticLogStore?, initialRoute: String = "startup") {
            if (store == null) return
            val host = WindowFramePerformance(activity, store)
            host.route = if (initialRoute == "startup") "startup" else PerformanceRoute.part(initialRoute)
            hosts[activity] = host
            host.windows[activity.window] = "main"
            val flushHook: () -> Unit = {
                check(Looper.myLooper() != Looper.getMainLooper()) { "frame_export_requires_worker" }
                val failed = java.util.concurrent.atomic.AtomicBoolean(false)
                val done = CountDownLatch(1)
                var retirement: CountDownLatch? = null
                // Freeze worker/session membership together on their owner thread.
                activity.runOnUiThread {
                    val target = host.handler
                    val active = host.sessions.values.toList()
                    retirement = host.retired
                    if (target == null) done.countDown()
                    else if (!target.post {
                            try { active.forEach { it.batch.flush("export") } } finally { done.countDown() }
                        }) { failed.set(true); done.countDown() }
                }
                check(done.await(5, TimeUnit.SECONDS)) { "frame_capture_busy" }
                check(!failed.get()) { "frame_capture_unavailable" }
                check(retirement?.await(5, TimeUnit.SECONDS) != false) { "frame_capture_retiring" }
            }
            host.log.registerCapture(host, flushHook)
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        host.resumed = true; host.reconcile()
                        if (host.diagnostic) JankDiagnostics.ready()
                    }
                    Lifecycle.Event.ON_PAUSE -> {
                        BackPerformance.safely { BackPerformance.recorder.endHost(host, BackPerformanceRecorder.End.PAUSED) }
                        BackPerformance.safely { PopupPerformance.capture.endHost(host, BackPerformanceRecorder.End.PAUSED) }
                        host.resumed = false; host.reconcile()
                    }
                    Lifecycle.Event.ON_DESTROY -> {
                        BackPerformance.safely { BackPerformance.recorder.endHost(host, BackPerformanceRecorder.End.DESTROYED) }
                        BackPerformance.safely { PopupPerformance.capture.endHost(host, BackPerformanceRecorder.End.DESTROYED) }
                        host.closed = true; host.reconcile(); hosts.remove(activity)
                    }
                    else -> Unit
                }
            }
            activity.lifecycle.addObserver(observer)
            activity.lifecycleScope.launch {
                withContext(Dispatchers.IO) { store.load() }
                store.state.collect { state ->
                    host.enabled = true // Broad batches still use log.captureEpoch(), never this listener gate.
                    if (!host.diagnostic && (!state.loaded || !state.enabled || state.unavailable)) {
                        host.sessions.filterValues { it.kind != "main" &&
                            !PopupPerformance.capture.observing(host, it.window) }.keys.toList().forEach(host::detach)
                    }
                    host.reconcile()
                }
            }
        }
        private fun activity(context: Context): Activity? {
            var current = context
            while (current is ContextWrapper) {
                if (current is Activity) return current
                val base = current.baseContext
                if (base === current) break
                current = base
            }
            return current as? Activity
        }
        fun forContext(context: Context): WindowFramePerformance? = activity(context)?.let(hosts::get)
        private fun refreshRate(window: Window): Double =
            (window.decorView.display?.refreshRate?.toDouble() ?: -1.0).takeIf { it.isFinite() && it > 0 } ?: -1.0
        fun watchNative(window: Window) {
            val view = window.decorView
            val listener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) { forContext(window.context)?.registerWindow(window, "native-dialog") }
                override fun onViewDetachedFromWindow(v: View) { forContext(window.context)?.unregisterWindow(window) }
            }
            view.addOnAttachStateChangeListener(listener)
            if (view.isAttachedToWindow) listener.onViewAttachedToWindow(view)
        }
    }
}

@Composable
internal fun ObservePageFrames(routeKey: Any) {
    val host = WindowFramePerformance.forContext(LocalContext.current)
    SideEffect { host?.page(routeKey) }
}

@Composable
internal fun ObserveDialogFrames(kind: String, automaticPopup: PopupPerformanceCapture.Type? = null) {
    val host = WindowFramePerformance.forContext(LocalContext.current)
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    DisposableEffect(host, window, kind, automaticPopup) {
        // Fixed call-site categories, never dialog title/text.
        val safeKind = kind.takeIf { it in setOf("dialog", "sheet", "alert", "confirmation", "text-input") } ?: "dialog"
        val lease = if (window != null) host?.registerWindow(window, safeKind, automaticPopup) else null
        onDispose { if (window != null) host?.unregisterWindow(window, lease) }
    }
}

/** Discrete UI context; state keys trigger boundaries but are never serialized. */
@Composable
internal fun ObserveFrameContext(category: String, detail: String, stateKey: Any? = Unit) {
    val context = LocalContext.current
    val host = WindowFramePerformance.forContext(context)
    val view = LocalView.current
    val dialog = (view.parent as? DialogWindowProvider)?.window
    val window = dialog ?: (context.findFrameActivity()?.window)
    val token = remember(host, window, category) { Any() }
    val safeCategory = category.takeIf { it in setOf("screen", "chat-session", "terminal-mode", "terminal-session", "linux-tab", "browser-tab", "browser-panel", "provider-tab", "popup", "interaction") } ?: "context"
    val safeDetail = if (category == "screen") PerformanceScreenNames.label(detail) else detail.takeIf {
        it in setOf("new", "selected", "android-simple", "android-console", "linux-simple", "linux-console", "basic", "files", "content", "history", "settings", "downloads", "models", "connection", "receipt", "active", "paused", "menu-open", "menu-exit", "back-predictive", "back-drag", "page-transition", "back-settle", "idle")
    } ?: "detail"
    DisposableEffect(host, window, token, safeDetail, stateKey) {
        if (window != null) host?.context(window, token, "$safeCategory=$safeDetail")
        onDispose { if (window != null) host?.context(window, token, null) }
    }
}

private fun Context.findFrameActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        val base = context.baseContext
        if (base === context) break
        context = base
    }
    return context as? Activity
}

/** Only discrete labels are observed; animated fractions/coordinates never enter composition. */
@Composable
internal fun ObserveDiagnosticInteraction(kind: String, readPhase: () -> String) {
    val diagnostic = app.daiyu.android.BuildConfig.JANK_DIAGNOSTICS
    if (!diagnostic && kind != "chat-scroll") return
    val context = LocalContext.current
    val host = WindowFramePerformance.forContext(context)
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window ?: context.findFrameActivity()?.window
    val token = remember(host, window, kind) { Any() }
    val current = androidx.compose.runtime.rememberUpdatedState(readPhase)
    val safeKind = kind.takeIf { it in setOf("menu", "popup", "navigation", "drawer", "chat-scroll") } ?: "interaction"
    androidx.compose.runtime.LaunchedEffect(host, window, token) {
        val cookie = System.identityHashCode(token)
        var activeSpan: String? = null
        try {
            androidx.compose.runtime.snapshotFlow { current.value() }.collect { phase ->
                val safePhase = phase.takeIf { it in setOf("idle", "entering", "open", "exiting", "retained",
                    "drag", "scroll", "predictive", "settle", "forward", "return", "closed", "moving") } ?: "idle"
                if (safeKind == "chat-scroll") host?.scroll(when (safePhase) {
                    "drag" -> BackPerformanceRecorder.Scroll.DRAG
                    "scroll" -> BackPerformanceRecorder.Scroll.NON_DRAG_SCROLL
                    else -> BackPerformanceRecorder.Scroll.IDLE
                })
                if (!diagnostic) return@collect
                if (window != null) host?.context(window, token, "$safeKind=$safePhase")
                activeSpan?.let { JankDiagnostics.endPhase(it, cookie) }
                activeSpan = "daiyu.$safeKind=$safePhase"
                JankDiagnostics.beginPhase(checkNotNull(activeSpan), cookie)
                JankDiagnostics.counter("daiyu.$safeKind", when (safePhase) {
                    "idle", "closed" -> 0; "entering", "drag" -> 1; "open", "scroll", "predictive" -> 2
                    "exiting", "settle", "moving" -> 3; "forward" -> 4; "return" -> 5; else -> 6
                })
            }
        } finally {
            if (safeKind == "chat-scroll") host?.scroll(BackPerformanceRecorder.Scroll.IDLE)
            activeSpan?.let { JankDiagnostics.endPhase(it, cookie) }
            if (diagnostic && window != null) host?.context(window, token, null)
        }
    }
}
