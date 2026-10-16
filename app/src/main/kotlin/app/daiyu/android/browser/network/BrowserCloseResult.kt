package app.daiyu.android.browser.network

/** Bounded, nonblocking teardown snapshot. Codes contain no URLs, paths, cookies or exceptions.
 * `drained` means tracked work really returned, NOT that teardown succeeded or a command is known.
 * An UNKNOWN execution remains UNKNOWN after a successful teardown. Poll closeStatus after close.
 * No Future completion/cancellation is used as evidence of raw I/O exit.
 */
data class BrowserCloseResult(
    val closeRequested: Boolean,
    val pending: Set<String> = emptySet(),
    val failures: Set<String> = emptySet(),
) {
    val drained: Boolean get() = closeRequested && pending.isEmpty()
    val successful: Boolean get() = drained && failures.isEmpty()

    fun merge(other: BrowserCloseResult) = BrowserCloseResult(
        closeRequested && other.closeRequested, pending + other.pending, failures + other.failures,
    )
}
