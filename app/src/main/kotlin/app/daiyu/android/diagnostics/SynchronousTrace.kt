package app.daiyu.android.diagnostics

import android.os.Trace

/** Release-visible synchronous slices. Inclusive nested durations are NOT additive.
 * A captured source token can cross IO; it must never be rebound to a newer gesture on return.
 * Inline preserves exactly-once calls, exceptions, return values and nonlocal returns.
 */
internal inline fun <T> synchronousTrace(
    name: String,
    sourceToken: BackPerformanceRecorder.Token? = captureBackPerformanceToken(),
    block: () -> T,
): T {
    val sample = try { BackPerformance.recorder.begin(name, sourceToken) } catch (_: Throwable) { null }
    var tracing = false
    try { Trace.beginSection(name); tracing = true } catch (_: Throwable) { }
    return try { block() } finally {
        if (tracing) try { Trace.endSection() } catch (_: Throwable) { }
        try { BackPerformance.recorder.complete(sample) } catch (_: Throwable) { }
    }
}

internal fun captureBackPerformanceToken(): BackPerformanceRecorder.Token? =
    try { BackPerformance.recorder.token() } catch (_: Throwable) { null }
