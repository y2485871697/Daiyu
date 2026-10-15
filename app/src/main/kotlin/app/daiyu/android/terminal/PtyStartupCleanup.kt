package app.daiyu.android.terminal

import java.util.concurrent.TimeUnit

/** Failure after launch transfers cleanup ownership; it is not proof that the helper stopped. */
class PtyStartupFailure internal constructor(
    message: String,
    val cleanup: PtyStartupCleanup,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** Retains the original Process, including when session construction or journal commit failed. */
class PtyStartupCleanup internal constructor(
    private val process: Process,
    private val request: () -> Unit,
) {
    fun requestClose() {
        var failure: Exception? = null
        try { request() } catch (close: Exception) { failure = close }
        // Construction may have failed before a session could take ownership of stdin.
        try { process.outputStream.close() } catch (close: Exception) {
            if (failure == null) failure = close else if (failure !== close) failure.addSuppressed(close)
        }
        failure?.let { throw it }
    }

    /** Worker-only. Retain the Linux generation lease until this returns true. */
    fun awaitTermination(timeoutMillis: Long): Boolean {
        require(timeoutMillis in 1..30_000)
        return process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
    }
}
