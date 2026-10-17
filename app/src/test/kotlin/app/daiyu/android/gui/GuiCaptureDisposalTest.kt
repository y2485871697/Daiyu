package app.daiyu.android.gui

import org.junit.Assert.*
import org.junit.Test

/** Exercises the exact helper used by TakeScreenshotCallback.onSuccess's finally block.
 * The callbacks stand in only for recycle/close, not for a different capture backend. */
class GuiCaptureDisposalTest {
    @Test fun everyCombinationOfDisposalExceptionsAttemptsEveryResourceAndOnlyAllSuccessTerminates() {
        for (failures in 0..7) {
            var released = 0
            val request = GuiCaptureRequest<String> { released++ }
            request.future.complete(GuiResult.Value("encoded image"))
            val attempted = mutableListOf<String>()
            fun dispose(name: String, bit: Int) {
                attempted += name
                if (failures and bit != 0) throw IllegalStateException("$name disposal unconfirmed")
            }
            request.disposeSuccessResources(
                { dispose("software", 1) }, { dispose("hardware", 2) }, { dispose("buffer", 4) })
            assertEquals("failure mask $failures", listOf("software", "hardware", "buffer"), attempted)
            assertEquals("a published image is not a resource receipt: mask $failures",
                if (failures == 0) 1 else 0, released)
            assertTrue(request.future.get() is GuiResult.Value)
            if (failures == 0) {
                request.disposeSuccessResources({}, {}, {})
                assertEquals("one terminal receipt even if callback cleanup repeats", 1, released)
            }
        }
    }

    @Test fun rejectedEncodingOrEarlyReturnAndNullableBitmapsStillRequireBufferDisposal() {
        for (code in listOf("OBSERVATION_STALE", "SCREENSHOT_DIMENSIONS_EXCEED_LIMIT",
            "SCREENSHOT_ENCODING_UNAVAILABLE", "SCREENSHOT_UNAVAILABLE")) {
            var released = 0
            val request = GuiCaptureRequest<String> { released++ }
            request.future.complete(GuiResult.Rejected(code))
            var bufferClosed = 0
            // Null software/hardware are no-ops in the production finally block.
            request.disposeSuccessResources({}, {}, { bufferClosed++ })
            assertEquals(1, bufferClosed)
            assertEquals(1, released)
            assertEquals(code, (request.future.get() as GuiResult.Rejected).code)
        }
    }

    @Test fun lateSuccessAfterUncertainSubmissionOrConsumerCancellationCannotDrainOnFailedDisposal() {
        for (cancelConsumer in listOf(false, true)) {
            var released = 0
            val request = GuiCaptureRequest<String> { released++ }
            request.submit { if (!cancelConsumer) throw IllegalStateException("accepted before throw") }
            if (cancelConsumer) request.future.cancel(true)
            assertFalse(request.future.complete(GuiResult.Value("late image")))
            val attempted = mutableListOf<String>()
            request.disposeSuccessResources(
                { attempted += "software"; throw IllegalStateException("recycle failed") },
                { attempted += "hardware" }, { attempted += "buffer" })
            assertEquals(listOf("software", "hardware", "buffer"), attempted)
            assertEquals(0, released)
            if (cancelConsumer) assertTrue(request.future.isCancelled)
            else assertEquals("SCREENSHOT_DELIVERY_UNCONFIRMED", (request.future.get() as GuiResult.Rejected).code)
        }
    }
}
