package app.daiyu.android.browser.download

import app.daiyu.android.browser.session.BrowserTabLease
import java.io.OutputStream

/** Memory-only capability issued by the coordinator's successful completion publication.
 * No path/URI constructor, receipt parser, or model-facing save tool exists.
 * The original lease is provenance, NOT renewed browser execution authority: a normally
 * closed run's completed bytes remain saveable by its owning conversation's explicit UI.
 */
class BrowserCompletedDownload internal constructor(
    val id: String,
    val lease: BrowserTabLease,
    val size: Long,
    val sha256: String,
    private val source: BrowserDownloadCoordinator,
) {
    fun saveOriginal(openDestination: () -> OutputStream, assertOwner: () -> Unit) =
        source.saveCompleted(this, openDestination, assertOwner)
}

/** Trusted host associates completions with a conversation, never with model-provided paths.
 * Bounded, memory-only inventory; eviction/recreation requires a new trusted completion.
 */
class BrowserDownloadShelf(private val limit: Int = 128) {
    private val lock = Any()
    private val entries = linkedMapOf<BrowserCompletedDownload, String>()
    init { require(limit > 0) }

    fun recorder(conversationId: String, owner: app.daiyu.android.browser.session.BrowserOwner):
        (BrowserCompletedDownload) -> Unit = { reference ->
        require(reference.lease.owner == owner) { "BROWSER_SAVE_ORIGINAL_OWNER" }
        synchronized(lock) {
            entries[reference] = conversationId
            while (entries.size > limit) entries.remove(entries.keys.first())
        }
    }

    fun list(conversationId: String): List<BrowserCompletedDownload> = synchronized(lock) {
        entries.filterValues { it == conversationId }.keys.toList()
    }

    fun assertOwned(conversationId: String, reference: BrowserCompletedDownload) = synchronized(lock) {
        check(entries[reference] == conversationId) { "BROWSER_SAVE_REFERENCE_EXPIRED" }
    }
}
