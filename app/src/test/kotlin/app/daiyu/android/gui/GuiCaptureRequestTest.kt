package app.daiyu.android.gui

import org.junit.Assert.*
import org.junit.Test

class GuiCaptureRequestTest {
    @Test fun submissionThrowDoesNotDrainAndLateSuccessWaitsForResourceDisposal() {
        var released = 0
        val request = GuiCaptureRequest<String> { released++ }
        var lateSuccess: (() -> Unit)? = null
        request.submit {
            lateSuccess = { request.future.complete(GuiResult.Value("late image")); Unit }
            throw IllegalStateException("accepted then threw")
        }
        assertTrue(request.future.get() is GuiResult.Rejected)
        assertEquals(0, released)
        lateSuccess!!()
        assertEquals(0, released) // callback began, buffer still owned
        request.resourcesDisposed()
        assertEquals(1, released)
        request.resourcesDisposed()
        assertEquals(1, released)
        assertTrue(request.future.get() is GuiResult.Rejected) // never publish revoked late image
    }

    @Test fun lateFailureIsTheOnlyReleaseReceiptAfterSubmissionThrow() {
        var released = 0
        val request = GuiCaptureRequest<String> { released++ }
        request.submit { throw IllegalStateException("delivery uncertain") }
        assertEquals(0, released)
        request.callbackFailure("SCREENSHOT_PLATFORM_ERROR_1")
        request.callbackFailure("SCREENSHOT_PLATFORM_ERROR_1")
        assertEquals(1, released)
    }

    @Test fun timeoutOrCancelledConsumerDoesNotReleasePlatformReservation() {
        var released = false
        val request = GuiCaptureRequest<String> { released = true }
        request.submit { }
        request.future.cancel(true)
        assertFalse(released)
        request.resourcesDisposed()
        assertTrue(released)
    }
}
