package app.daiyu.android.ui.screens.chat.conversation

import android.os.Debug
import android.test.InstrumentationTestCase
import java.io.File
import org.json.JSONObject

/** Diagnostic wrapper; the canonical fixture, SameItem actions and assertions run unchanged. */
@Suppress("DEPRECATION")
class ChatMessageSpacingProfileTest : InstrumentationTestCase() {
    fun testProfileOriginalSameItemStreamingGrowth() {
        val directory = File(checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)),
            "visual-diagnostics/same-item-profile").apply { check(mkdirs() || isDirectory) }
        val trace = File(directory, "original-same-item-${System.nanoTime()}.trace")
        val original = ChatMessageSpacingTest().apply {
            injectInstrumentation(this@ChatMessageSpacingProfileTest.instrumentation)
            name = "testSameItemStreamingGrowthFollowsUntilTouchAndExplicitBottomRestoresIt"
        }
        val bufferBytes = 64 * 1024 * 1024
        val intervalMicros = 2_000
        var primary: Throwable? = null
        val started = System.nanoTime()
        Debug.startMethodTracingSampling(trace.absolutePath, bufferBytes, intervalMicros)
        try {
            original.runBare()
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            var diagnosticFailure: Throwable? = null
            try { Debug.stopMethodTracing() }
            catch (failure: Throwable) { diagnosticFailure = failure }
            try {
                File(directory, trace.nameWithoutExtension + ".json").writeText(JSONObject()
                    .put("canonical_method", original.name)
                    .put("trace", trace.name)
                    .put("sampling_interval_micros", intervalMicros)
                    .put("buffer_bytes", bufferBytes)
                    .put("wrapper_wall_nanos", System.nanoTime() - started)
                    .put("canonical_completed", primary == null)
                    .put("canonical_failure", primary?.javaClass?.name ?: JSONObject.NULL)
                    .put("trace_bytes", trace.length())
                    .put("formal_acceptance", false)
                    .put("full_regression_passed", false).toString(2) + "\n")
            } catch (failure: Throwable) {
                if (diagnosticFailure == null) diagnosticFailure = failure
                else diagnosticFailure.addSuppressed(failure)
            }
            diagnosticFailure?.let { failure ->
                if (primary == null) throw failure else primary.addSuppressed(failure)
            }
        }
    }
}
