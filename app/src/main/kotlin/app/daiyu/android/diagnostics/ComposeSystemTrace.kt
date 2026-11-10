package app.daiyu.android.diagnostics

import android.os.Build
import android.os.Trace
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi

/** Publishes compiler-generated composable names to the existing Android system trace. */
@OptIn(InternalComposeTracingApi::class)
internal object ComposeSystemTrace {
    private var installed = false

    fun install() {
        if (installed || Build.VERSION.SDK_INT < 29) return
        Composer.setTracer(SystemTracer)
        installed = true
    }

    private object SystemTracer : CompositionTracer {
        override fun isTraceInProgress(): Boolean =
            Build.VERSION.SDK_INT >= 29 && Trace.isEnabled()

        override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
            Trace.beginSection(info.take(MAX_SECTION_NAME_LENGTH))
        }

        override fun traceEventEnd() {
            Trace.endSection()
        }
    }

    private const val MAX_SECTION_NAME_LENGTH = 127
}
