package app.daiyu.android.diagnostics

import android.os.Trace

/** Release-visible platform slices. Use fixed names and synchronous, non-composable work only. */
internal inline fun <T> synchronousTrace(name: String, block: () -> T): T {
    Trace.beginSection(name)
    return try { block() } finally { Trace.endSection() }
}
