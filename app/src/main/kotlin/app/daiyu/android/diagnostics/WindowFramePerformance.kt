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
    private val log = store.frames
    private val windows = LinkedHashMap<Window, String>()
    private val sessions = LinkedHashMap<Window, Session>() // Main thread owns listener membership.
    private var thread: HandlerThread? = null
    @Volatile private var handler: Handler? = null
    private var route = "startup"
    private val contexts = LinkedHashMap<Window, LinkedHashMap<Any, String>>()
    private fun path(window: Window) = (listOf(route) + (contexts[window]?.values?.toList() ?: emptyList())).joinToString("/")
    internal fun context(window: Window, token: Any, label: String?) {
        val labels = contexts.getOrPut(window) { LinkedHashMap() }
        if (label == null) labels.remove(token) else labels[token] = label
        val current = path(window)
        val now = System.nanoTime()
        val session = sessions[window]
        handler?.post { session?.timeline?.change(now, current); session?.batch?.flush("context") }
    }
    private var resumed = false
    private var enabled = false
    private var closed = false
    @Volatile private var retired: CountDownLatch? = null

    private inner class Session(val window: Window, val kind: String) {
        val timeline = PerformanceRouteTimeline()
        val batch = PerformanceFrameBatch({ epoch, row ->
            row.put("window", kind).put("sdk", Build.VERSION.SDK_INT)
            row.put("app_version", app.daiyu.android.BuildConfig.VERSION_NAME)
            row.put("version_code", app.daiyu.android.BuildConfig.VERSION_CODE)
            row.put("budget_source", if (Build.VERSION.SDK_INT >= 31) "deadline_or_observed_refresh_period" else "observed_refresh_period")
            log.append(epoch, row)
        })
        @Volatile var refreshHz = refreshRate(window)
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, drops ->
            val epoch = log.captureEpoch() ?: return@OnFrameMetricsAvailableListener
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
            batch.add(epoch, timeline.pageAt(vsync), PerformanceFrame(vsync,
                metrics.getMetric(FrameMetrics.TOTAL_DURATION), budget, refreshHz, stages, drops.coerceAtLeast(0),
                metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L))
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
        handler?.post { session.batch.flush("window_detached") }
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
    internal fun registerWindow(window: Window, kind: String) { windows[window] = kind; attach(window, kind) }
    internal fun unregisterWindow(window: Window) { windows.remove(window); detach(window) }

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
                    Lifecycle.Event.ON_RESUME -> { host.resumed = true; host.reconcile() }
                    Lifecycle.Event.ON_PAUSE -> { host.resumed = false; host.reconcile() }
                    Lifecycle.Event.ON_DESTROY -> {
                        host.closed = true; host.reconcile(); hosts.remove(activity)
                    }
                    else -> Unit
                }
            }
            activity.lifecycle.addObserver(observer)
            activity.lifecycleScope.launch {
                withContext(Dispatchers.IO) { store.load() }
                store.state.collect { state ->
                    host.enabled = state.loaded && state.enabled && !state.unavailable
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
internal fun ObserveDialogFrames(kind: String) {
    val host = WindowFramePerformance.forContext(LocalContext.current)
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    DisposableEffect(host, window, kind) {
        // Fixed call-site categories, never dialog title/text.
        val safeKind = kind.takeIf { it in setOf("dialog", "sheet", "alert", "confirmation", "text-input") } ?: "dialog"
        if (window != null) host?.registerWindow(window, safeKind)
        onDispose { if (window != null) host?.unregisterWindow(window) }
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
    val safeCategory = category.takeIf { it in setOf("screen", "chat-session", "terminal-mode", "terminal-session", "linux-tab", "browser-tab", "browser-panel", "provider-tab", "popup") } ?: "context"
    val safeDetail = if (category == "screen") PerformanceScreenNames.label(detail) else detail.takeIf {
        it in setOf("new", "selected", "android-simple", "android-console", "linux-simple", "linux-console", "basic", "files", "content", "history", "settings", "downloads", "models", "connection", "receipt", "active", "paused")
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
