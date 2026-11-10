package app.daiyu.android.diagnostics

import android.os.Build
import android.os.Looper
import android.os.Trace
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi

/** Compiler names reach both system trace and the narrow automatic back report. */
@OptIn(InternalComposeTracingApi::class)
internal object ComposeSystemTrace {
    private var installed = false
    private val events = ThreadLocal<CompositionTraceEvents>()
    private val samples = BackCompositionSamples(
        observed = BackPerformance.recorder::compositionObserved,
        limited = BackPerformance.recorder::compositionLimited,
        completed = BackPerformance.recorder::composition,
    )

    fun install() {
        if (installed) return
        Composer.setTracer(SystemTracer)
        installed = true
    }

    private fun systemEnabled(): Boolean = Build.VERSION.SDK_INT >= 29 && Trace.isEnabled()
    private fun onMainThread(): Boolean = Looper.myLooper() === Looper.getMainLooper()

    private object SystemTracer : CompositionTracer {
        override fun isTraceInProgress(): Boolean = events.get()?.pending == true || systemEnabled() ||
            (onMainThread() && samples.enabled(BackPerformance.recorder.compositionToken()))

        override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
            val stack = events.get() ?: CompositionTraceEvents().also(events::set)
            if (stack.start(systemEnabled())) Trace.beginSection(info.take(BackPerformanceRecorder.MAX_COMPOSE_NAME))
            if (onMainThread()) BackPerformance.safely {
                samples.start(BackPerformance.recorder.compositionToken(), info)
            }
        }

        override fun traceEventEnd() {
            if (onMainThread()) BackPerformance.safely { samples.end(BackPerformance.recorder.compositionToken()) }
            if (events.get()?.end() == true) Trace.endSection()
        }
    }
}
