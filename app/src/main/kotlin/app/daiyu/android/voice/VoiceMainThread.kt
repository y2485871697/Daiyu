package app.daiyu.android.voice

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** AudioOwnerGate must not grant a new lease until Android resources have actually released. */
private val voiceCallbackExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "daiyu-voice-events").apply { isDaemon = true }
}
internal fun voiceEvent(action: () -> Unit) { voiceCallbackExecutor.execute(action) }

internal fun releaseOnMain(handler: Handler, action: () -> Unit) {
    require(handler.looper == Looper.getMainLooper()) { "audio_release_requires_main_handler" }
    if (Looper.myLooper() == Looper.getMainLooper()) { action(); return }
    val done = CountDownLatch(1)
    var failure: Throwable? = null
    check(handler.post {
        try { action() } catch (error: Throwable) { failure = error } finally { done.countDown() }
    }) { "audio_release_rejected" }
    val released = try { done.await(3, TimeUnit.SECONDS) } catch (interrupted: InterruptedException) {
        // Keep the queued teardown, but never tell the owner gate that release succeeded.
        Thread.currentThread().interrupt()
        throw IllegalStateException("audio_release_interrupted", interrupted)
    }
    check(released) { "audio_release_pending" }
    failure?.let { throw IllegalStateException("audio_release_failed", it) }
}
