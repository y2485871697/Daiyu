package app.daiyu.android.vdapproval

import java.util.concurrent.atomic.AtomicBoolean

/** One explicit cleanup-screen visit. Not a text editor/send owner, run owner, grant or lease.
 * Invalidate synchronously BEFORE replacing/removing the visit or changing Activity/conversation.
 * Closing only fences queued cleanup work; it never stops a run, writes or releases anything.
 */
internal class R08CleanupUiOwner : AutoCloseable {
    private val active = AtomicBoolean(true)
    internal val isActive: Boolean get() = active.get()
    override fun close() { active.set(false) }
}
