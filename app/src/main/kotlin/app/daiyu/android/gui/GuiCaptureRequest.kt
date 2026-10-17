package app.daiyu.android.gui

import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

/** Submission can throw AFTER the platform accepted a capture. Only a terminal callback, and
 * (for success) confirmed disposal of its resources, proves this request no longer owns a slot. */
internal class GuiCaptureRequest<T>(private val released: () -> Unit) {
    val future = CompletableFuture<GuiResult<T>>()
    private val terminal = AtomicBoolean(false)
    fun submit(block: () -> Unit) {
        try { block() }
        catch (_: Exception) {
            future.complete(GuiResult.Rejected("SCREENSHOT_DELIVERY_UNCONFIRMED"))
            // No callback receipt: keep the resource reservation even if no callback ever arrives.
        }
    }
    fun callbackFailure(code: String) {
        future.complete(GuiResult.Rejected(code))
        resourcesDisposed()
    }
    fun resourcesDisposed() {
        if (terminal.compareAndSet(false, true)) released()
    }
}
